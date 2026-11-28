package eu.kanade.tachiyomi.animeextension.all.rule34video

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class Rule34VideoFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        Rule34Video("Rule34Video", "https://rule34video.com", "en"),
    )
}

class Rule34Video(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : AnimeHttpLegacySource() {

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", UA)
        add("Referer", "$baseUrl/")
    }

    // Сайт без пагинации: /page/2/ отвечает 404, листинг — одна страница.
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl + "/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl + "/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/${query.trim().replace(' ', '+')}/", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    private fun listParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val animes = document.select("a[href*=\"/video/\"]").mapNotNull { el ->
            val href = el.attr("abs:href")
            if (!href.contains("/video/")) return@mapNotNull null
            val img = el.selectFirst("img")
            val cardTitle = el.attr("title")
                .ifEmpty { img?.attr("alt").orEmpty() }
                .ifEmpty { el.attr("data-title").ifEmpty { el.text().trim() } }
            if (cardTitle.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                url = href.removePrefix(baseUrl)
                title = cardTitle.trim()
                thumbnail_url = img?.let { it.attr("data-src").ifEmpty { it.attr("src") }.takeIf { t -> t.startsWith("http") } }
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = document.select("a[href*=\"tag\"], a[href*=\"categor\"]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it.length in 2..40 }
                .distinct().take(15).joinToString(", ")
            status = SAnime.COMPLETED
            description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = response.request.url.encodedPath
            name = "Видео"
            episode_number = 1f
        },
    )

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val page = network.client.newCall(GET(baseUrl + episode.url, headers)).execute().body.string()

        val main = VIDEO_URL_REGEX.find(page)?.groupValues?.get(1)
        val alts = VIDEO_ALT_REGEX.findAll(page).map { it.groupValues[1] }.distinct().toList()
        val videos = (listOfNotNull(main) + alts)
            .distinct()
            .map { raw -> videoOf(raw) }
            .filterNotNull()
        if (videos.isEmpty()) throw Exception("Ссылки на видео не найдены")
        return videos.sortedByDescending { it.videoTitle.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    }

    private fun videoOf(raw: String): Video? {
        val full = if (raw.startsWith("//")) "https:$raw" else raw
        if (!full.startsWith("http")) return null
        val height = Regex("([0-9]{3,4})[pP]").find(full)?.groupValues?.get(1)
        val container = if (full.contains(".m3u8")) "HLS" else "MP4"
        val label = if (height != null) "$container ${height}p" else "Видео"
        return Video(full, label, full, headers = headers)
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private val VIDEO_URL_REGEX = Regex("\"video_url\":\\s*\"([^\"]+)\"")
        private val VIDEO_ALT_REGEX = Regex("\"video_alt_url\\d*\":\\s*\"([^\"]+)\"")
    }
}
