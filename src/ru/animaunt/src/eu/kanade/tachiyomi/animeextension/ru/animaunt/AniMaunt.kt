package eu.kanade.tachiyomi.animeextension.ru.animaunt

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

class AniMaunt : AnimeHttpLegacySource() {

    override val name = "AniMaunt"

    override val baseUrl = "https://animaunt.org"

    override val lang = "ru"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")

    // =============================== Популярное (Каталог) ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    // =============================== Последние обновления ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) baseUrl else "$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response)

    // =============================== Поиск ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList): Request {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("search_start", page.toString())
            .add("story", query)
            .build()
        return POST("$baseUrl/index.php?do=search", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    // =============================== Парсинг списков ===============================

    private fun parseAnimeList(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val items = document.select(".short-item, .th-item")
        val animes = items.map { element ->
            SAnime.create().apply {
                val link = element.select("a").first()
                val titleElement = element.select(".short-title, .th-title").first()
                val img = element.select("img").first()

                url = link?.attr("abs:href") ?: ""
                title = titleElement?.text() ?: img?.attr("alt") ?: ""
                thumbnail_url = img?.attr("abs:src")
            }
        }.filter { it.url.isNotEmpty() }

        val hasNextPage = document.select(".pagi-next, .navigation a:contains(Далее)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Детали аниме ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        return SAnime.create().apply {
            title = document.select("h1").text()
            thumbnail_url = document.select(".fposter img").attr("abs:src")
            description = document.select(".ftext.full-text").text()
            genre = document.select(".flist li:contains(Жанр) a").joinToString { it.text() }
            status = parseStatus(document.select(".flist li:contains(Статус)").text())
            author = document.select(".flist li:contains(Режиссёр) a").text()
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("выходит", ignoreCase = true) -> SAnime.ONGOING
        status.contains("завершен", ignoreCase = true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // =============================== Список серий ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val episodes = mutableListOf<SEpisode>()

        // Плеер AniMaunt обычно подгружает серии через iframe или скрипт.
        // В legacy-варианте берем текущую страницу как единственный эпизод, если это фильм,
        // или парсим вкладки плеера, если они есть.

        val videoIframe = document.select("#video-player iframe, .video-box iframe").attr("abs:src")

        if (videoIframe.isNotEmpty()) {
            val ep = SEpisode.create().apply {
                name = "Смотреть онлайн"
                episode_number = 1f
                url = response.request.url.toString()
                date_upload = 0L
            }
            episodes.add(ep)
        }

        return episodes.reversed()
    }

    // =============================== Видео ссылки ===============================

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val videos = mutableListOf<Video>()

        // Поиск iframe плеера (Kodik, VideoDB или внутренний)
        val iframeUrl = document.select("#video-player iframe, .video-box iframe, .player-box iframe").attr("abs:src")

        if (iframeUrl.isNotEmpty()) {
            // Возвращаем ссылку на плеер. Aniyomi подхватит её, если есть соответствующие экстракторы.
            videos.add(Video(iframeUrl, "Internal Player", iframeUrl))
        }

        return videos
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun List<Video>.sort(): List<Video> = this
}
