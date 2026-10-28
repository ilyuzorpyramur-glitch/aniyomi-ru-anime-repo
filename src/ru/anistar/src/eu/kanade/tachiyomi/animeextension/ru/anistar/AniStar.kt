package eu.kanade.tachiyomi.animeextension.ru.anistar

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

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

    override fun popularAnimeRequest(page: Int): Request = GET(popularUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    private fun popularUrl(page: Int): String =
        if (page <= 1) "$baseUrl/filter/rating/" else "$baseUrl/filter/rating//page/$page/"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(searchUrl(query, filters), headers)

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = AniStarFilters.FILTER_LIST

    private fun searchUrl(query: String, filters: AnimeFilterList): String {
        val year = filters.filterIsInstance<AniStarFilters.YearFilter>().firstOrNull()?.state ?: 0
        val genre = filters.filterIsInstance<AniStarFilters.GenreFilter>().firstOrNull()?.state ?: 0

        if (query.isNotBlank()) {
            return "$baseUrl/index.php?do=xfsearch&xf=${query.encoded()}"
        }
        if (genre > 0) {
            return "$baseUrl${AniStarFilters.GENRES[genre - 1].second}"
        }
        if (year > 0) {
            return "$baseUrl/index.php?do=xfsearch&xf=${AniStarFilters.YEARS[year - 1]}&type=year&r=anime"
        }
        return popularAnimeRequest(1).url.toString()
    }

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
            SAnime.create().apply {
                url = href.substringAfter(baseUrl)
                title = titleText
                thumbnail_url = card.selectFirst("img")?.let { imageUrl(it) }
            }
        }.distinctBy { it.url }

        return AnimesPage(items, items.size >= PAGE_SIZE)
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
            genre = document.select("a[href*=/anime/]")
                .map { it.text().trim() }
                .filter { it.length in 3..24 }
                .distinct()
                .take(10)
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
        val document = Jsoup.parse(response.bodyCp1251(), baseUrl)
        val playerUrl = document.select("iframe[src*=/player2/]")
            .map { it.absUrl("src").ifBlank { it.attr("src") } }
            .firstOrNull { it.contains("videoas.php") && it.contains("hash=") }
            ?: return emptyList()

        val playerPage = client.newCall(GET(playerUrl, headers)).execute().use { it.bodyCp1251() }
        val playlist = playerPage.substringAfter("var playlst=", "")
        if (playlist.isBlank()) return emptyList()

        val entries = EPISODE_REGEX.findAll(playlist).mapNotNull { match ->
            val title = match.groupValues[1].trim()
            val file = match.groupValues[2].trim()
            if (file.isBlank()) return@mapNotNull null
            title to file
        }.distinctBy { it.second }.toList()

        return entries.mapIndexed { index, (title, file) ->
            SEpisode.create().apply {
                url = file
                name = title.ifBlank { "Серия ${index + 1}" }
                episode_number = index + 1f
                scanlator = "AniStar"
            }
        }
    }

    // =============================== Video ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val playlistUrl = if (episode.url.startsWith("http")) episode.url else baseUrl + episode.url
        val master = client.newCall(GET(playlistUrl, videoHeaders)).awaitSuccess().bodyString()
        val variants = parseMaster(master)
        if (variants.isEmpty()) {
            return@withContext listOf(Video(playlistUrl, "HLS", playlistUrl, headers = videoHeaders))
        }
        val preferred = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)?.toIntOrNull() ?: 0
        variants
            .sortedByDescending { it.first == preferred }
            .sortedByDescending { it.first }
            .sortedByDescending { it.first == preferred }
            .map { (height, url) ->
                Video(url, if (height > 0) "${height}p" else "HLS", url, headers = videoHeaders)
            }
    }

    private fun parseMaster(master: String): List<Pair<Int, String>> {
        val lines = master.lines().map { it.trim() }
        val out = mutableListOf<Pair<Int, String>>()
        var height = 0
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    height = RESOLUTION_REGEX.find(line)?.groupValues?.get(2)?.toIntOrNull() ?: 0
                }
                line.startsWith("http") -> {
                    out += height to line
                    height = 0
                }
            }
        }
        return out.distinctBy { it.second }
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
    }

    // ============================== Helpers ===============================

    private fun Response.bodyCp1251(): String = body.bytes().toString(charset("windows-1251"))

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

        private const val PAGE_SIZE = 10

        private val EPISODE_REGEX = Regex("""title:"([^"]*)",[\s\S]{0,600}?file:"([^"]+)"""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}

object AniStarFilters {

    class YearFilter : AnimeFilter.Select<String>("Год", YEARS_TITLES, 0)

    class GenreFilter : AnimeFilter.Select<String>("Жанр", GENRE_TITLES, 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(YearFilter(), GenreFilter())

    val YEARS: List<String> = (2026 downTo 2000).map { it.toString() }

    private val YEARS_TITLES = arrayOf("Любой") + YEARS.toTypedArray()

    val GENRES: List<Pair<String, String>> = listOf(
        "Экшен" to "/anime/action/",
        "Комедия" to "/anime/comedy/",
        "Романтика" to "/anime/romance/",
        "Фэнтези" to "/anime/fantasy/",
        "Драма" to "/anime/drama/",
        "Сёнен" to "/anime/seinen/",
        "Меха" to "/anime/mecha/",
        "Ужасы" to "/anime/horror/",
        "Детектив" to "/anime/detective/",
        "Приключения" to "/anime/adventure/",
        "Фантастика" to "/anime/sci-fi/",
        "Повседневность" to "/anime/slice-of-life/",
        "Спорт" to "/anime/sport/",
        "Исторический" to "/anime/historical/",
    )

    private val GENRE_TITLES = arrayOf("Любой") + GENRES.map { it.first }.toTypedArray()
}
