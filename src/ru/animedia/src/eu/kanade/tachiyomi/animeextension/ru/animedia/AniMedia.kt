package eu.kanade.tachiyomi.animeextension.ru.animedia

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AniMedia :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniMedia"

    override val baseUrl = "https://animemedia.org"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = listRequest("/tv-series/", page)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest("/ongoing/", page)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        if (query.isNotBlank()) {
            val body = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("story", query)
                .add("search_start", page.toString())
                .add("full_search", "0")
                .add("result_from", (((page - 1) * SEARCH_PAGE_SIZE) + 1).toString())
                .build()

            return POST("$baseUrl/index.php?do=search", headers, body)
        }

        return listRequest(AniMediaFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = AniMediaFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        fun row(label: String): String? = document.select(".full-story-header__table-row")
            .firstOrNull { it.selectFirst("span")?.text()?.trim()?.removeSuffix(":") == label }
            ?.select("span")
            ?.getOrNull(1)
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst(".full-story-header__title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst(".full-story-header__poster-img img")?.absUrl("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = document.select(".full-story-header__categories a")
                .map { it.text().trim() }
                .filterNot { it.equals("Аниме", true) || it.toIntOrNull() != null }
                .joinToString()
            author = row("Студия")
            status = when {
                row("Количество эпизодов") == null -> SAnime.UNKNOWN
                document.location().contains("/ongoing/") -> SAnime.ONGOING
                else -> SAnime.COMPLETED
            }
            description = buildString {
                document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                document.select(".full-story-header__alt-title")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { appendLine("Другие названия: ${it.joinToString(" / ")}") }
                listOf("Год", "Премьера", "Количество эпизодов", "Озвучка", "Страна", "Продолжительность")
                    .forEach { label -> row(label)?.let { appendLine("$label: $it") } }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = runBlocking {
        val document = response.useAsJsoup()

        val player = document.selectFirst("video-player[data-title-id]")
            ?: throw Exception("Плеер не найден на странице тайтла")

        val titleId = player.attr("data-title-id")
        val publisherId = player.attr("data-publisher-id").ifBlank { "1" }
        val aggregator = player.attr("data-aggregator").ifBlank { "mali" }

        val playlist = client.newCall(playlistRequest(titleId, publisherId, aggregator))
            .awaitSuccess()
            .parseAs<PlaylistDto>()

        // One episode appears once per voice-over; group them so each episode is a single row.
        val grouped = playlist.items
            .filter { !it.vkId.isNullOrBlank() }
            .groupBy { (it.season ?: 1) to (it.episode ?: 1f) }

        val isSingle = grouped.size == 1

        grouped.entries
            .sortedWith(
                compareByDescending<Map.Entry<Pair<Int, Float>, List<PlaylistItemDto>>> { it.key.first }
                    .thenByDescending { it.key.second },
            )
            .map { (key, items) ->
                val season = key.first
                val number = key.second
                val voices = items.map { it.voiceLabel }.distinct()
                val itemName = items.first().name

                SEpisode.create().apply {
                    url = "$titleId|$publisherId|$aggregator|$season|${number.formatNumber()}"
                    episode_number = number
                    name = when {
                        isSingle -> "Фильм"
                        !itemName.isNullOrBlank() -> itemName
                        else -> "Эпизод ${number.formatNumber()}"
                    }
                    scanlator = voices.take(4).joinToString().takeIf { it.isNotBlank() }
                }
            }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split('|')
        if (parts.size < 5) return emptyList()

        val titleId = parts[0]
        val publisherId = parts[1]
        val aggregator = parts[2]
        val season = parts[3]
        val number = parts[4]

        val playlist = client.newCall(playlistRequest(titleId, publisherId, aggregator))
            .awaitSuccess()
            .parseAs<PlaylistDto>()

        val targets = playlist.items.filter {
            !it.vkId.isNullOrBlank() &&
                (it.season ?: 1).toString() == season &&
                (it.episode ?: 1f).formatNumber() == number
        }

        if (targets.isEmpty()) return emptyList()

        val ignoreDuplicates = preferences.getBoolean(PREF_ONE_PER_VOICE_KEY, PREF_ONE_PER_VOICE_DEFAULT)
        val chosen = if (ignoreDuplicates) targets.distinctBy { it.voiceLabel } else targets

        val videos = chosen.parallelCatchingFlatMap { item -> itemVideos(item) }

        if (videos.isEmpty()) {
            throw Exception("Не удалось получить ссылки на видео для этой серии")
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private suspend fun itemVideos(item: PlaylistItemDto): List<Video> {
        val vkId = item.vkId ?: return emptyList()

        val video = client.newCall(GET("$API_URL/player/sv/video/$vkId", apiHeaders()))
            .awaitSuccess()
            .parseAs<VideoDto>()

        val hlsUrl = video.sources?.hlsUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val label = item.voiceLabel

        // okcdn отдаёт варианты плейлиста путём «/expires/…». При склейке
        // получается двойной слеш (okcdn.ru//expires/…), и CDN отвечает
        // 400 Bad Request, поэтому разбираем master-плейлист сами.
        val master = client.newCall(GET(hlsUrl, apiHeaders())).awaitSuccess().bodyString()
        val videos = mutableListOf<Video>()
        var height = ""

        for (raw in master.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                height = RESOLUTION_REGEX.find(line)?.groupValues?.get(1).orEmpty()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val resolved = resolveUrl(hlsUrl, line).squeezeSlashes()
                val videoLabel = if (height.isBlank()) label else "$label - ${height}p"
                videos += Video(resolved, videoLabel, resolved, headers = apiHeaders())
            }
        }

        return videos.ifEmpty {
            listOf(Video(hlsUrl, label, hlsUrl, headers = apiHeaders()))
        }
    }

    /** Склеивает относительную ссылку из плейлиста с адресом master-файла. */
    private fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http")) return path
        val origin = base.substringBefore("/", "").let { base.substring(0, base.indexOf("//") + 2) + base.substringAfter("//").substringBefore("/") }
        if (path.startsWith("/")) return origin + path
        return base.substringBeforeLast("/") + "/" + path
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Предпочитаемое качество",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
        )

        screen.addSwitchPreference(
            key = PREF_ONE_PER_VOICE_KEY,
            default = PREF_ONE_PER_VOICE_DEFAULT,
            title = "Убирать дубликаты озвучек",
            summary = "Оставлять по одной записи на студию — сайт часто отдаёт повторы",
        )
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 1080

    // =============================== Utils ================================

    private fun playlistRequest(titleId: String, publisherId: String, aggregator: String): Request {
        val url = "$API_URL/player/sv/playlist".toHttpUrl().newBuilder()
            .addQueryParameter("pub", publisherId)
            .addQueryParameter("id", titleId)
            .addQueryParameter("aggr", aggregator)
            .build()

        return GET(url, apiHeaders())
    }

    private fun apiHeaders(): Headers = Headers.Builder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )
        .build()

    private fun listRequest(path: String, page: Int): Request {
        val cleanPath = path.ifBlank { "/tv-series/" }.removeSuffix("/")
        val url = if (page > 1) "$baseUrl$cleanPath/page/$page/" else "$baseUrl$cleanPath/"

        return GET(url, headers)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        // The page also carries a "recommended" carousel built from the same card
        // markup; including it duplicated titles already listed in the catalog.
        val animes = document.select("main a.new-anime__link, main a.new-series__link")
            .filter { it.closest(".carousel") == null }
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        val current = PAGE_REGEX.find(response.request.url.encodedPath)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        return AnimesPage(animes, document.hasPageAfter(current))
    }

    // DLE renders the pager as plain page links; a next page exists only when one of
    // them points past the page we are on.
    private fun Document.hasPageAfter(current: Int): Boolean = select(".pagination a[href], #pagination a[href], .navigation a[href]").any { link ->
        val page = PAGE_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        page != null && page > current
    }

    private fun Element.toSAnime(): SAnime? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val path = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)

        return SAnime.create().apply {
            url = path
            title = attr("title").ifBlank { selectFirst(".new-anime__title, .new-series__title")?.text().orEmpty() }.trim()
            thumbnail_url = selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
        }
    }

    private fun Float.formatNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Убирает двойной слеш после хоста: okcdn.ru//expires → okcdn.ru/expires. */
    private fun String.squeezeSlashes(): String = replace(Regex("(?<=^https?://[^/]{1,80})//+"), "/")

    companion object {
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
        private const val API_URL = "https://plapi.cdnvideohub.com/api/v1"
        private const val SEARCH_PAGE_SIZE = 12

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_ONE_PER_VOICE_KEY = "pref_one_per_voice"
        private const val PREF_ONE_PER_VOICE_DEFAULT = true

        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val PAGE_REGEX = Regex("""/page/(\d+)""")
    }
}
