package eu.kanade.tachiyomi.animeextension.ru.senu

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray

/**
 * SENU Project — архив аниме-фильмов и сериалов бывших участников Onibaku Group.
 *
 * Сайт статический (Hugo):
 *  - полный каталог отдаётся один раз: GET /search/index.json (title, permalink, image, date, content);
 *  - страница релиза: /p/<id>/;
 *  - список серий: /player/list/<id>.txt (JSON, в поле file — список серверов через запятую).
 */
class SenuProject : AnimeHttpLegacySource() {

    override val name = "SENU Project"

    override val baseUrl = "https://senu.pro"

    override val lang = "ru"

    override val supportsLatest = true

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
            .set("Origin", baseUrl)
            .build()
    }

    @Volatile private var catalogCache: List<CatalogItem>? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page)

    override fun popularAnimeParse(response: Response): AnimesPage = catalogPage(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = catalogRequest(page, query)

    override fun searchAnimeParse(response: Response): AnimesPage = catalogPage(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    private fun catalogRequest(page: Int, query: String? = null): Request {
        val url = "$baseUrl/search/index.json".toHttpUrl().newBuilder()
            .addQueryParameter("p", page.toString())
            .apply { if (!query.isNullOrBlank()) addQueryParameter("q", query) }
            .build()
        return GET(url.toString(), headers)
    }

    private fun catalogPage(response: Response): AnimesPage {
        val items = parseCatalog(response.bodyString()).also { catalogCache = it }
        val page = response.request.url.queryParameter("p")?.toIntOrNull() ?: 1
        val query = response.request.url.queryParameter("q")
        val filtered = items
            .filter { query.isNullOrBlank() || it.matches(query) }
            .sortedByDescending { it.date }
        val animes = filtered.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE).map { item ->
            SAnime.create().apply {
                url = item.path
                title = item.title
                thumbnail_url = item.image
                description = item.description
            }
        }
        return AnimesPage(animes, filtered.size > page * PAGE_SIZE)
    }

    private fun parseCatalog(body: String): List<CatalogItem> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val permalink = item.optString("permalink")
            if (permalink.isBlank()) return@mapNotNull null
            CatalogItem(
                title = item.optString("title"),
                path = permalink.substringAfter(baseUrl).ifBlank { permalink },
                image = item.optString("image"),
                date = item.optString("date"),
                description = cleanDescription(item.optString("content")),
            )
        }
    }

    private fun cleanDescription(raw: String): String = raw
        .replace("&hellip;", "…")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace(Regex("<[^>]+>"), "")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val known = catalogCache?.firstOrNull { it.path == response.request.url.encodedPath }
        url = response.request.url.encodedPath
        title = known?.title ?: ""
        thumbnail_url = known?.image
        description = known?.description
        initialized = true
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val page = response.bodyString()
        val playlistPath = PLAYER_LIST_REGEX.find(page)?.groupValues?.get(1) ?: return emptyList()
        val playlist = client.newCall(GET("$baseUrl$playlistPath", headers)).execute()
            .use { it.bodyString() }
        val array = runCatching { JSONArray(playlist) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val sources = parseSources(item.optString("file"))
            if (sources.isEmpty()) return@mapNotNull null
            SEpisode.create().apply {
                url = sources.joinToString("|")
                name = item.optString("title").ifBlank { "Серия ${index + 1}" }
                episode_number = (index + 1).toFloat()
                scanlator = "SENU Project"
            }
        }
    }

    private fun parseSources(raw: String): List<Pair<String, String>> = raw
        .split(",")
        .mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val server = SERVER_REGEX.find(trimmed)?.groupValues?.get(1)
            val url = trimmed.removePrefix(if (server != null) "[$server]" else "")
            if (!url.startsWith("http")) return@mapNotNull null
            (server ?: "SENU") to url
        }
        .distinctBy { it.second }

    // =============================== Video ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = episode.url
        .split("|")
        .mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val server = SERVER_REGEX.find(trimmed)?.groupValues?.get(1)
            val url = trimmed.removePrefix(if (server != null) "[$server]" else "")
            if (!url.startsWith("http")) return@mapNotNull null
            Video(url, "HLS · ${server ?: "SENU"}", url, headers = videoHeaders)
        }
        .distinctBy { it.videoUrl }

    private data class CatalogItem(
        val title: String,
        val path: String,
        val image: String,
        val date: String,
        val description: String,
    ) {
        fun matches(query: String): Boolean =
            title.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)
    }

    companion object {
        private const val PAGE_SIZE = 40
        private val PLAYER_LIST_REGEX = Regex("""file:"(/player/list/[^"]+)"""")
        private val SERVER_REGEX = Regex("""^\[([^\]]+)]""")
    }
}
