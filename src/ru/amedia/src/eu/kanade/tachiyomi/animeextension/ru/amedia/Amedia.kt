package eu.kanade.tachiyomi.animeextension.ru.amedia

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Amedia (amedia.club) — сгенерировано extension-forge.
 *
 * Каталог и детали разбираются из HTML, плеер берётся через relay сайта
 * и передаётся в KodikExtractor.
 */
class Amedia :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Amedia"

    override val baseUrl = "https://amedia.club"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val kodik by lazy { KodikExtractor(client, headers, baseUrl, preferences) }

    /** id тайтла на сайте, по которому relay отдаёт плеер. */
    private var currentAnimeId: String = ""

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // =============================== Каталог ===============================

    private fun pageUrl(path: String, page: Int): String {
        if (page <= 1) return "$baseUrl$path"
        return "$baseUrl$path" + PAGE_SUFFIX.replace("{page}", page.toString())
    }

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl(CATALOG_PATH, page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(LATEST_PATH, page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList): Request {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl" + SEARCH_PATH.replace("{query}", encoded)
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val html = response.bodyString()
        val linkFilter = Regex(LINK_FILTER)
        val animes = ANCHOR_REGEX.findAll(html)
            .mapNotNull { match ->
                val href = match.groupValues[1]
                if (!href.contains(linkFilter.pattern)) return@mapNotNull null
                val path = href.substringAfter(baseUrl, href)
                val body = match.groupValues[2]
                SAnime.create().apply {
                    url = path
                    title = cleanTitle(textOf(body)).ifBlank { altOf(body) }
                    thumbnail_url = imageOf(body)?.let(::absoluteUrl)
                    initialized = false
                }
            }
            .filter { it.title.isNotBlank() && !it.title.equals("Подробнее", true) }
            .distinctBy { it.url }
            .toList()

        val nextPage = html.contains("${PAGE_MARKER}${response.request.url.toString().let { pageOf(it) + 1 }}")
        return AnimesPage(animes, nextPage)
    }

    private fun pageOf(url: String): Int = Regex("""(?:page/|page=|/)(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun textOf(fragment: String): String = Jsoup.parse(fragment).text()

    private fun altOf(fragment: String): String = Regex("""alt="([^"]*)"""").find(fragment)?.groupValues?.get(1).orEmpty()

    private fun imageOf(fragment: String): String? = Regex("""<img[^>]*src="([^"]+)"""").find(fragment)?.groupValues?.get(1)

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s*смотреть онлайн.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+аниме$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> "$baseUrl/$raw"
    }

    // =============================== Детали ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.bodyString()
        val doc = Jsoup.parse(html, baseUrl)

        currentAnimeId = Regex("""data-player-anime-id="(\d+)"""").find(html)?.groupValues?.get(1).orEmpty()

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = cleanTitle(doc.selectFirst(DETAILS_TITLE)?.text().orEmpty()).ifBlank { "Без названия" }
            thumbnail_url = (metaOf(doc, "og:image") ?: doc.selectFirst(POSTER_SELECTOR)?.absUrl("src"))
                ?.let(::absoluteUrl)
            description = metaOf(doc, "og:description")
                ?: doc.selectFirst(DESC_SELECTOR)?.text()?.takeIf { it.length > 80 }
            genre = doc.select(GENRE_SELECTOR).map { it.text().trim() }
                .filter { it.isNotEmpty() && it.length < 24 }
                .distinct()
                .take(12)
                .joinToString(", ")
                .ifBlank { null }
            initialized = true
        }
    }

    private fun metaOf(doc: org.jsoup.nodes.Document, property: String): String? =
        doc.selectFirst("""meta[property="$property"]""")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("""meta[name="$property"]""")?.attr("content")?.takeIf { it.isNotBlank() }

    // =============================== Серии ================================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = currentAnimeId.ifBlank { relayId(anime.url) }
        return GET(RELAY_URL.replace("{id}", id), headers)
    }

    private fun relayId(path: String): String = runCatching {
        val html = client.newCall(GET(baseUrl + path, headers)).execute().use { it.bodyString() }
        Regex("""data-player-anime-id="(\d+)"""").find(html)?.groupValues?.get(1).orEmpty()
    }.getOrDefault("")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val player = playerFromRelay(response)
        if (player.isBlank()) return emptyList()

        val page = runCatching {
            client.newCall(GET(player, headers.newBuilder().set("Referer", "$baseUrl/").build())).execute()
                .use { it.bodyString() }
        }.getOrNull() ?: return emptyList()

        return EPISODE_OPTION_REGEX.findAll(page).mapNotNull { match ->
            val attrs = attrsOf(match.value)
            val number = attrs["value"]?.toFloatOrNull() ?: return@mapNotNull null
            val title = attrs["data-title"].orEmpty()
            SEpisode.create().apply {
                url = "$player#e=" + number.toEpisodeNumber()
                name = title.ifBlank { "Серия " + number.toEpisodeNumber() }
                episode_number = number
            }
        }
            .distinctBy { it.episode_number }
            .sortedByDescending { it.episode_number }
            .toList()
    }

    private fun Float.toEpisodeNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun playerFromRelay(response: Response): String {
        val body = runCatching { response.bodyString() }.getOrNull() ?: return ""
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        return root.optJSONObject("data")?.optString("src").orEmpty()
    }

    private fun attrsOf(tag: String): Map<String, String> =
        Regex("""([a-z-]+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    // =============================== Видео ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val number = Regex("""#e=(\d+)""").find(episode.url)?.groupValues?.get(1)?.toFloatOrNull()
            ?: episode.episode_number
        val videos = kodik.videos(episode.url.substringBefore("#"), 1, number.toInt())
        if (videos.isEmpty()) throw Exception("Плеер не вернул видео")
        videos
    }

    // ============================== Настройки =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = KodikExtractor.PREF_QUALITY
            title = "Качество видео"
            entries = arrayOf("Максимальное", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("max", "1080", "720", "480", "360")
            summary = "%s"
            setDefaultValue("max")
        }.let(screen::addPreference)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val CATALOG_PATH = "/"
        private const val LATEST_PATH = "/"
        private const val SEARCH_PATH = "/search/?q={query}"
        private const val PAGE_SUFFIX = "page/{page}/"
        private const val PAGE_MARKER = "page/"
        private const val LINK_FILTER = """/\d+[^"']+\.html"""

        private const val DETAILS_TITLE = "h1"
        private const val POSTER_SELECTOR = "img"
        private const val DESC_SELECTOR = "div"
        private const val GENRE_SELECTOR = "a[href*='/genre/']"

        private const val RELAY_URL = "/engine/ajax/controller.php?mod=player&id={id}&slot=0"

        private val ANCHOR_REGEX = Regex(
            """<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val EPISODE_OPTION_REGEX = Regex("""<option[^>]*data-hash="[^"]+"[^>]*>""", RegexOption.IGNORE_CASE)
    }
}
