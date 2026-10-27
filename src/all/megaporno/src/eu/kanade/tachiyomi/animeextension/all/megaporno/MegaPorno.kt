package eu.kanade.tachiyomi.animeextension.all.megaporno

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.Charset

class MegaPorno :
    AnimeHttpLegacySource() {

    override val name = "Mega Porno"

    override val baseUrl = "https://w.mega-porno.club"

    override val lang = "all"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(catalogUrl(page, MegaPornoFilters.DEFAULT_CATEGORY), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, MegaPornoFilters.DEFAULT_CATEGORY), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val search = query.trim()
        if (search.isEmpty()) {
            return GET(catalogUrl(page, MegaPornoFilters.getCategory(filters)), headers)
        }

        // У сайта нет постраничного поиска: форма отдаёт один список по параметру text.
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("text", search)
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response, singlePage = true)

    override fun getFilterList(): AnimeFilterList = MegaPornoFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.parseDocument()

        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("div.tags a").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED
            description = buildString {
                doc.selectFirst("meta[property=og:description]")?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { appendLine(it) }
                doc.selectFirst("div.video-tool-wrap span.medal")?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appendLine("Рейтинг: $it") }
                doc.selectFirst("div.video-tool-wrap span.views")?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appendLine("Просмотров: $it") }
                doc.selectFirst("div.video-tool-wrap span.time")?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appendLine("Длительность: $it") }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    // Каждая запись каталога — отдельный ролик, поэтому у «аниме» всегда один эпизод.
    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.parseDocument()
        val duration = doc.selectFirst("div.video-tool-wrap span.time")?.text()
            ?.replace("мин.", "")
            ?.trim()

        return listOf(
            SEpisode.create().apply {
                url = response.request.url.encodedPath
                name = listOfNotNull("Видео", duration?.takeIf { it.isNotEmpty() }).joinToString(" · ")
                episode_number = 1f
            },
        )
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val page = response.bodyText()

        // Плеер Playerjs: file = "[HD 720p]./common/mp4/video.mp4?q=720&v=… or ./common/mp4/video.mp4?…,[SD 360p]…"
        val raw = FILE_REGEX.find(page)?.groupValues?.get(1)
            ?: throw Exception("Не удалось найти плеер на странице видео")

        val videos = raw.split(",")
            .mapNotNull { chunk ->
                val entry = chunk.trim()
                val match = ENTRY_REGEX.find(entry) ?: return@mapNotNull null
                val label = match.groupValues[1].trim()
                val encoded = match.groupValues[2]

                val link = encoded.split(" or ")
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.toAbsoluteUrl()
                    ?: return@mapNotNull null

                // Вторая ссылка из пары "A or B" — запасной CDN того же качества.
                val alt = encoded.split(" or ")
                    .drop(1)
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.toAbsoluteUrl()

                listOfNotNull(
                    Video(link, label.toQualityLabel(), link, headers = headers),
                    alt?.let { Video(it, label.toQualityLabel(), it, headers = headers) },
                )
            }
            .flatten()
            .distinctBy { it.videoUrl }

        if (videos.isEmpty()) throw Exception("Ссылки на видео не найдены")

        return videos.sortedByDescending { it.videoTitle.parseQuality() }
    }

    // =============================== Utils ================================

    private fun catalogUrl(page: Int, category: String): String = if (page <= 1) {
        "$baseUrl/$category"
    } else {
        "$baseUrl/video/${category}_page-$page.html"
    }

    private fun listParse(response: Response, singlePage: Boolean = false): AnimesPage {
        val doc = response.parseDocument()
        val animes = doc.select("div.thumbnail").mapNotNull { it.toSAnime() }

        return AnimesPage(animes, !singlePage && animes.size >= ITEMS_PER_PAGE)
    }

    private fun Element.toSAnime(): SAnime? {
        val href = selectFirst("a[href*=/online/]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val path = href.removePrefix(baseUrl).let { if (it.startsWith("/")) it else "/$it" }

        return SAnime.create().apply {
            url = path
            title = selectFirst("div.caption a")?.text()?.takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("alt").orEmpty()
            thumbnail_url = selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } }
        }
    }

    /** Сайт отдаёт windows-1251 и не всегда указывает кодировку в заголовке. */
    private fun Response.parseDocument(): Document = Jsoup.parse(bodyText(), baseUrl)

    private fun Response.bodyText(): String {
        val bytes = body?.bytes() ?: return ""
        val charset = contentType()?.charset()?.takeIf { it.name() != "UTF-8" } ?: WINDOWS_1251
        return String(bytes, charset)
    }

    private fun String.toAbsoluteUrl(): String = when {
        isEmpty() -> ""
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("./") -> "$baseUrl/${substring(2)}"
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    private fun String.toQualityLabel(): String = QUALITY_REGEX.find(this)
        ?.groupValues
        ?.get(1)
        ?.let { "${it}p" }
        ?: "Видео"

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val ITEMS_PER_PAGE = 30

        private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

        private val FILE_REGEX = Regex("""var\s+file\s*=\s*"(.*?)";""")
        private val ENTRY_REGEX = Regex("""^\[([^\]]+)\](.+)$""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
    }
}
