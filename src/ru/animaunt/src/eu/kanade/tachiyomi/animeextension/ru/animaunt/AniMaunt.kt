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
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    // =============================== Последние обновления ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ongoing/page/$page/", headers)

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

    private fun parseAnimeList(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val items = doc.select(".short-item, .th-item")
        val animes = items.map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a")
                val img = element.selectFirst("img")
                title = element.select(".th-title, .short-title").text()
                url = link?.attr("abs:href") ?: ""
                thumbnail_url = img?.attr("abs:src") ?: img?.attr("abs:data-src") ?: ""
            }
        }
        val hasNext = doc.select(".pagi-next, .navigation a:contains(Вперед)").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Детали аниме ===============================
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        return SAnime.create().apply {
            val info = doc.select(".finfo")
            title = doc.select("h1").text()
            thumbnail_url = doc.select("meta[property=og:image]").attr("abs:content")
            description = doc.select(".ftext, .full-text").text()
            genre = doc.select(".f-genres a, a[href*='/anime/']").joinToString { it.text() }
            status = if (doc.select(".f-status").text().contains("завершен", true)) SAnime.COMPLETED else SAnime.ONGOING
            author = doc.select(".f-studio").text()
        }
    }

    // =============================== Список серий ===============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val episodes = mutableListOf<SEpisode>()

        // AniMaunt использует плееры (Kodik/Dzen/etc) во вкладках.
        // Обычно список серий зашит в самом плеере, но для структуры Aniyomi
        // мы создаем запись, которая ведет на страницу с плеером.
        val episode = SEpisode.create().apply {
            name = "Смотреть онлайн"
            episode_number = 1f
            url = response.request.url.toString()
            date_upload = 0L
        }
        episodes.add(episode)

        return episodes
    }

    // =============================== Видео (Плеер) ===============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val videos = mutableListOf<Video>()

        // Ищем iframe плееров
        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"

            if (src.contains("kodik") || src.contains("aniqit")) {
                videos.add(Video(src, "Kodik (External)", src))
            } else if (src.contains("dzen") || src.contains("vk.com")) {
                videos.add(Video(src, "Player (External)", src))
            }
        }

        // Если плееры скрыты в скриптах или data-атрибутах
        val scripts = doc.select("script").html()
        val kodikRegex = Regex("""https?://[^\s"']+(?:kodik|aniqit)[^\s"']+""")
        kodikRegex.findAll(scripts).forEach { match ->
            videos.add(Video(match.value, "Kodik (Found)", match.value))
        }

        return videos
    }

    override fun videoUrlParse(response: Response): String = ""

    override fun List<Video>.sort(): List<Video> = this
}
