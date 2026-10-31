package eu.kanade.tachiyomi.animeextension.ru.animespirit

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.AnimeHttpLegacySource
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Animespirit : AnimeHttpLegacySource() {

    override val name = "Animespirit"

    override val baseUrl = "https://animespirit.tv"

    override val lang = "ru"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")

    // =============================== Популярное (Главная) ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page == 1) baseUrl else "$baseUrl/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    // =============================== Последние обновления ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response)

    // =============================== Поиск ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.length < 3) throw Exception("Поиск требует минимум 3 символа")
        
        val formBody = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("search_start", page.toString())
            .add("full_search", "0")
            .add("result_from", ((page - 1) * 15 + 1).toString())
            .add("story", query)
            .build()

        return POST("$baseUrl/index.php?do=search", headers, formBody)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    // =============================== Парсинг списков ===============================

    private fun parseAnimeList(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val items = document.select("div.custom-poster, div.short-story")
        
        val animes = items.map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")
                url = link?.attr("href")?.removePrefix(baseUrl) ?: ""
                title = element.select("div.poster-title, h2").text().trim()
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.filter { it.url.isNotEmpty() }

        val hasNextPage = document.select("span.pnext, a:contains(Далее)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Детали аниме ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        return SAnime.create().apply {
            val info = document.select("div.full-story, div.main-content")
            title = document.select("h1").text().trim()
            thumbnail_url = info.select("div.poster img").attr("abs:src")
            description = info.select("div.full-text, div.description").text().trim()
            genre = info.select("a[href*=/genre/]").joinToString { it.text() }
            status = parseStatus(info.text())
        }
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("выход завершен", true) -> SAnime.COMPLETED
        text.contains("онгоинг", true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // =============================== Список серий ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val episodes = mutableListOf<SEpisode>()
        
        // Animespirit часто использует один плеер с переключателем серий внутри iframe
        // или список ссылок в описании. В legacy-варианте создаем одну запись, 
        // так как плеер сам управляет сериями.
        
        val ep = SEpisode.create().apply {
            name = "Смотреть онлайн"
            episode_number = 1f
            url = response.request.url.toString().removePrefix(baseUrl)
            date_upload = 0L
        }
        episodes.add(ep)
        
        return episodes
    }

    // =============================== Видео (Плееры) ===============================

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val videos = mutableListOf<Video>()

        // Поиск iframe плееров (Kodik, Sibnet, YouTube и т.д.)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotEmpty()) {
                videos.add(Video(src, "Player: ${src.substringBefore("?")}", src))
            }
        }

        // Поиск ссылок в скриптах или специальных блоках плеера
        val scripts = document.select("script").html()
        val videoRegex = Regex("""(?:file|video):"([^"]+)"""")
        videoRegex.findAll(scripts).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.startsWith("http")) {
                videos.add(Video(videoUrl, "Direct Link", videoUrl))
            }
        }

        return videos
    }

    override fun videoUrlParse(response: Response): String = throw Exception("Not used")

    override fun List<Video>.sort(): List<Video> = this
}
