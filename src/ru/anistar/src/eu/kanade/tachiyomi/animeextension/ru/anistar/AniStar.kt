package eu.kanade.tachiyomi.animeextension.ru.anistar

import android.util.Log
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * AniStar — v31.astar.bz (DLE, windows-1251).
 *
 * Популярное: /filter/rating/page/N/
 * Недавнее:   /page/N/
 * Поиск:      /index.php?do=xfsearch&xf=<query>
 * Год:        /index.php?do=xfsearch&xf=<year>&type=year&r=anime
 * Жанр:       /anime/<genre>/
 * Плеер:      /test/player2/videoas.php?id=<id>&hash=<hash> (jwplayer, playlst[])
 * Видео:      /test/player2/playlist_hls.php?... -> master.m3u8 (360/720)
 */
class AniStar :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniStar"

    override val baseUrl = "https://v31.astar.bz"

    override val lang = "ru"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    private var pendingQuery: String = ""

    private var pendingParams: AniStarFilters.SearchParams? = null

    @Volatile private var dubName: String = ""

    private val deepEnt by lazy { DeepEntResolver(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )
        .set("Referer", "$baseUrl/")

    private val videoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniStarFilters.getSearchParameters(filters)
        pendingQuery = query
        pendingParams = params
        return GET(searchUrls(query, params, page).first(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val query = pendingQuery
        val params = pendingParams ?: AniStarFilters.SearchParams()
        val urls = searchUrls(query, params, 1)

        val first = listParse(response)
        if (urls.size <= 1) return first

        val merged = LinkedHashMap<String, SAnime>()
        first.animes.forEach { merged[it.url] = it }
        urls.drop(1).forEach { url ->
            runCatching {
                client.newCall(GET(url, headers)).execute().use { extra ->
                    listParse(extra).animes.forEach { merged.putIfAbsent(it.url, it) }
                }
            }
        }
        return AnimesPage(merged.values.toList(), false)
    }

    private fun searchUrls(query: String, params: AniStarFilters.SearchParams, page: Int): List<String> {
        if (query.isNotBlank()) {
            return listOf("$baseUrl/index.php?do=xfsearch&xf=${query.encoded()}")
        }
        val urls = mutableListOf<String>()
        params.genres.forEach { path ->
            urls += if (page <= 1) "$baseUrl$path" else "$baseUrl${path}page/$page/"
        }
        if (urls.isEmpty()) {
            params.years.forEach { year ->
                urls += "$baseUrl/index.php?do=xfsearch&xf=$year&type=year&r=anime"
            }
        }
        if (urls.isEmpty()) {
            urls += "$baseUrl/page/$page/"
        }
        return urls
    }

    override fun getFilterList(): AnimeFilterList =
        AniStarFilters.filterList(preferences?.getBoolean(PREF_NSFW, false) ?: false)

    // ============================ List parsing ============================

    private fun listParse(response: Response): AnimesPage = try {
        parseList(response)
    } catch (e: Throwable) {
        Log.e("AniStar", "listParse " + response.request.url + " code=" + response.code, e)
        throw e
    }

    private fun parseList(response: Response): AnimesPage {
        val document = Jsoup.parse(response.bodyCp1251(), baseUrl)
        val items = document.select("div#dle-content div.news").mapNotNull { card ->
            val link = card.selectFirst("div.news_header a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val titleText = link.text().trim()
            if (href.isBlank() || titleText.isBlank()) return@mapNotNull null
            val tags = card.select("p.tags a").map { it.text().trim() }
            val isAdPost = tags.any { it == "Новинки(онгоинги)" } &&
                tags.none { it != "Новинки(онгоинги)" && it != "Аниме" }
            if (isAdPost) return@mapNotNull null
            SAnime.create().apply {
                url = href.substringAfter(baseUrl)
                title = titleText
                thumbnail_url = card.selectFirst("img")?.let { imageUrl(it) }
            }
        }.distinctBy { it.url }

        val hasNext = items.size >= PAGE_SIZE && !response.request.url.encodedPath.startsWith("/filter/rating")
        return AnimesPage(items, hasNext)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.bodyCp1251(), baseUrl)
        val rating = document.selectFirst("[itemprop=ratingValue]")?.text()?.trim()
        val votes = document.selectFirst("[itemprop=ratingCount]")?.text()?.trim()
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("img[itemprop=image]")?.let { imageUrl(it) }
                ?: document.selectFirst("div.title_left img")?.let { imageUrl(it) }
            genre = document.select("p.tags a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it !in SERVICE_TAGS }
                .distinct()
                .joinToString()
            description = buildString {
                val body = document.selectFirst("[itemprop=description]")?.let { element ->
                    val copy = element.clone()
                    copy.select("script, noscript, .title_spoiler, div[onclick]").remove()
                    copy.text().trim()
                }
                if (!body.isNullOrBlank()) appendLine(body)
                if (!rating.isNullOrBlank()) {
                    appendLine()
                    appendLine("Рейтинг: $rating/10" + if (!votes.isNullOrBlank()) " (голосов: $votes)" else "")
                }
            }.trim()
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.bodyCp1251()
        val document = Jsoup.parse(html, baseUrl)

        EMBED_REGEX.find(html)?.let { match ->
            val embed = match.groupValues[1].replace("&amp;", "&")
            val pagePath = response.request.url.encodedPath
            val count = EPISODE_COUNT_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?.coerceIn(1, 200) ?: 1
            dubName = ""
            return (1..count).map { number ->
                SEpisode.create().apply {
                    url = "$DEEP_ENT_PREFIX$pagePath|$embed|$number"
                    name = "Серия $number"
                    episode_number = number.toFloat()
                    scanlator = "AniStar"
                }
            }.sortedByDescending { it.episode_number }
        }

        val playerPath = document.select("iframe[src*=/player2/]")
            .map { it.attr("src") }
            .firstOrNull { it.contains("videoas.php") && it.contains("hash=") }
            ?: PLAYER_REGEX.find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val playerUrl = if (playerPath.startsWith("http")) playerPath else baseUrl + playerPath.replace("&amp;", "&")

        val playerHeaders = headers.newBuilder()
            .set("Referer", baseUrl + response.request.url.encodedPath)
            .build()
        val playerPage = client.newCall(GET(playerUrl, playerHeaders)).execute().use { it.bodyUtf8() }
        val playlist = playerPage.substringAfter("var playlst=", "")
        if (playlist.isBlank()) return emptyList()

        val entries = EPISODE_REGEX.findAll(playlist).mapNotNull { match ->
            val title = match.groupValues[1].trim()
            val file = match.groupValues[2].trim()
            if (file.isBlank()) return@mapNotNull null
            title to file
        }.distinctBy { it.second }.toList()

        dubName = DUB_REGEX.find(playerPage)?.groupValues?.get(1)?.trim().orEmpty()

        // Specials would shift the numbering and break "Начать"; drop them unless the
        // release consists of specials only.
        val regular = entries.filterNot { SPECIAL_REGEX.containsMatchIn(it.first) }
        val source = regular.ifEmpty { entries }

        return source.mapIndexed { index, (title, file) ->
            val number = EPISODE_NUMBER_REGEX.find(title)?.value?.toFloatOrNull() ?: (index + 1f)
            SEpisode.create().apply {
                url = file
                name = title.ifBlank { "Серия ${index + 1}" }
                episode_number = number
                scanlator = dubName.ifBlank { "AniStar" }
            }
        }.distinctBy { it.episode_number }
            .sortedByDescending { it.episode_number }
    }

    // =============================== Video ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        if (episode.url.startsWith(DEEP_ENT_PREFIX)) {
            val parts = episode.url.removePrefix(DEEP_ENT_PREFIX).split("|")
            val pagePath = parts.getOrNull(0).orEmpty()
            val embed = parts.getOrNull(1).orEmpty()
            val number = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val pageUrl = if (pagePath.startsWith("http")) pagePath else baseUrl + pagePath
            val streams = runCatching {
                deepEnt.resolve(pageUrl, embed, number, 25).streams
            }.getOrNull().orEmpty()
            val stream = streams.firstOrNull { it.contains(".m3u8") }
                ?: return@withContext emptyList()
            return@withContext listOf(Video(stream, "AniStar - HLS", stream, headers = videoHeaders))
        }

        val playlistUrl = if (episode.url.startsWith("http")) episode.url else baseUrl + episode.url
        val master = client.newCall(GET(playlistUrl, videoHeaders)).awaitSuccess().bodyString()
        val variants = parseMaster(master)

        val working = variants.mapNotNull { (height, url) ->
            val ok = runCatching {
                client.newCall(GET(url, videoHeaders)).execute().use { response ->
                    response.isSuccessful && response.bodyString().startsWith("#EXTM3U")
                }
            }.getOrDefault(false)
            if (ok) height to url else null
        }.distinctBy { it.second }

        val preferred = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)?.toIntOrNull() ?: 0
        val ordered = working
            .sortedByDescending { it.first }
            .sortedByDescending { it.first == preferred }

        val best = ordered.firstOrNull()
        val target = best?.second ?: playlistUrl
        val quality = QUALITY_REGEX.find(target)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val label = if (quality > 0) "AniStar - ${quality}p" else "AniStar - HLS"
        listOf(Video(target, label, target, headers = videoHeaders))
    }

    private fun parseMaster(master: String): List<Pair<Int, String>> {
        val lines = master.lines().map { it.trim() }
        val out = mutableListOf<Pair<Int, String>>()
        var height = 0
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    height = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                line.startsWith("http") -> {
                    out += height to line
                    height = 0
                }
            }
        }
        return out
            .map { (height, url) -> height to normalizeVariant(url) }
            .distinctBy { it.second }
    }

    /**
     * Signed variants (sfv.an-media.org/key=...,end=...) expire and answer 403 "Wrong key".
     * The same file is served unsigned from sf2: /video/<hash>/<quality>.mp4/index.m3u8.
     */
    private fun normalizeVariant(url: String): String {
        val match = SIGNED_VARIANT_REGEX.find(url) ?: return url
        val hash = match.groupValues[1]
        val quality = if (url.contains("/720.")) "720" else "360"
        return "https://sf2.an-media.org/video/$hash/$quality.mp4/index.m3u8"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = listOf("Авто (максимальное)" to "0", "720p" to "720", "360p" to "360")
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Качество по умолчанию"
            entries = qualities.map { it.first }.toTypedArray()
            entryValues = qualities.map { it.second }.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_NSFW
            title = "Показывать NSFW-жанры"
            summary = "Добавляет разделы для взрослых в фильтр жанров"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================

    private fun Response.bodyCp1251(): String = body.bytes().toString(charset("windows-1251"))

    private fun Response.bodyUtf8(): String = body.bytes().toString(Charsets.UTF_8)

    private fun imageUrl(element: Element): String {
        val raw = element.attr("data-src").ifBlank {
            element.attr("data-original").ifBlank { element.attr("src") }
        }
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> baseUrl + raw
            else -> raw
        }
    }

    private fun String.encoded(): String = URLEncoder.encode(this, "windows-1251")

    companion object {
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "0"
        private const val PREF_NSFW = "pref_nsfw"

        private const val PAGE_SIZE = 10

        private val PLAYER_REGEX = Regex("""(/test/player2/videoas\.php\?id=\d+&(?:amp;)?hash=[A-Za-z0-9]+)""")

        private val EPISODE_NUMBER_REGEX = Regex("""\d+""")

        private val EPISODE_REGEX = Regex("""title:"([^"]*)",[\s\S]{0,600}?file:"([^"]+)"""")
        private val DUB_REGEX = Regex("""Озвучка:\s*<span[^>]*>\s*([^<]+)""")

        private const val DEEP_ENT_PREFIX = "deepent|"

        private val SERVICE_TAGS = setOf("Аниме", "Новинки(онгоинги)")

        private val EPISODE_COUNT_REGEX = Regex("""Серии:[^0-9]{0,12}(\d{1,4})""")

        private val EMBED_REGEX = Regex("""urlFrameGlobal='([^']*deep-ent[^']*)'""")

        private val SPECIAL_REGEX = Regex("""(?i)спешл|speshl|ova|спецвыпуск|фильм""")

        private val QUALITY_REGEX = Regex("""/(\d{3,4})\.mp4""")

        private val SIGNED_VARIANT_REGEX = Regex("""/media=hls/video/([0-9a-f]{16,})/""")

        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
