package eu.kanade.tachiyomi.animeextension.all.pornhub

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class PornhubFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        Pornhub("Pornhub", "https://www.pornhub.com", "en"),
    )
}

class Pornhub(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : AnimeHttpLegacySource() {

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", UA)
        add("Referer", "$baseUrl/")
        add("Accept-Language", "en-US,en;q=0.9")
    }

    // ============================== Каталог ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/video" + paging(page, "?"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/video/new" + paging(page, "?"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/video/search?search=${query.trim().replace(' ', '+')}" + paging(page, "&"), headers)

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    private fun paging(page: Int, sep: String): String = if (page > 1) "${sep}page=$page" else ""

    private fun listParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val animes = document.select("a[href*=\"view_video.php\"]").mapNotNull { el ->
            val href = el.attr("abs:href")
            if (!href.startsWith("http")) return@mapNotNull null
            val img = el.selectFirst("img")
            val cardTitle = el.attr("data-title")
                .ifEmpty { img?.attr("data-title").orEmpty() }
                .ifEmpty { el.attr("title").ifEmpty { img?.attr("alt").orEmpty() } }
                .ifEmpty { el.text().trim() }
            if (cardTitle.isEmpty()) return@mapNotNull null
            SAnime.create().apply {
                url = href.removePrefix("https://www.pornhub.com")
                title = cardTitle
                thumbnail_url = imgThumb(img)
            }
        }.distinctBy { it.url }
        val hasNext = document.selectFirst(".page_next") != null
        return AnimesPage(animes, hasNext)
    }

    private fun imgThumb(img: Element?): String? {
        if (img == null) return null
        for (a in listOf("data-thumb_url", "thumb_url", "data-mediumthumb", "data-src", "src")) {
            val v = img.attr(a)
            if (v.startsWith("http")) return v
        }
        return null
    }

    // ================================ Детали ================================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = document.select("a[href*=\"/video?c=\"], a[href*=\"/tags/\"]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it != "+" }
                .distinct().take(15).joinToString(", ")
            author = document.selectFirst("a[href^=\"/model/\"], a[href^=\"/pornstar/\"], a[href^=\"/users/\"]")?.text()?.trim()
            status = SAnime.COMPLETED
            description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        }
    }

    // ================================ Главы ================================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = response.request.url.encodedPath
            name = "Видео"
            episode_number = 1f
        },
    )

    // ================================ Видео ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val page = network.client.newCall(GET(baseUrl + episode.url, headers)).execute().body.string()

        val flash = FLASHVARS_REGEX.find(page)?.groupValues?.get(1)
            ?: throw Exception("Ссылки на видео не найдены")
        val root = JSON.parseToJsonElement(flash)
        val defs = (root as? JsonObject)?.get("mediaDefinitions") as? JsonArray ?: JsonArray(emptyList())
        val videos = defs.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val url = (obj["videoUrl"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            val quality = (obj["quality"] as? JsonPrimitive)?.content ?: ""
            val format = (obj["format"] as? JsonPrimitive)?.content ?: ""
            val label = if (quality.isNotBlank()) "$format $quality" else format.ifEmpty { "видео" }
            Video(url, label, url, headers = headers)
        }
        if (videos.isEmpty()) throw Exception("Ссылки на видео не найдены")
        return videos.sortedByDescending { it.videoTitle.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private val FLASHVARS_REGEX = Regex("flashvars_\\d+\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL)
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
