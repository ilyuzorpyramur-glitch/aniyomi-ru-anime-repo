package eu.kanade.tachiyomi.animeextension.ru.animaunt

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AniMaunt : AnimeHttpLegacySource(), ConfigurableAnimeSource {

    override val name = "AniMaunt"

    override val baseUrl = "https://animaunt.org"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")

    // =============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/anime/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body!!.string())
        val items = document.select(".th-item")
        val animes = items.map { element ->
            SAnime.create().apply {
                val anchor = element.selectFirst("a.th-in")
                url = anchor?.attr("href")?.substringAfter(baseUrl) ?: ""
                title = element.select(".th-title").text()
                thumbnail_url = baseUrl + element.select("img").attr("src")
            }
        }
        val hasNextPage = document.select(".pagi-nav a:contains(Далее)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/ongoing/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.length < 3) throw Exception("Запрос слишком короткий")
        return POST(
            "$baseUrl/index.php?do=search",
            headers,
            FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("search_start", page.toString())
                .add("full_search", "0")
                .add("result_from", ((page - 1) * 10).toString())
                .add("story", query)
                .build()
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string())
        return SAnime.create().apply {
            title = document.select("h1").text()
            thumbnail_url = baseUrl + document.select(".fposter img").attr("src")
            description = document.select(".ftext.full-text").text()
            genre = document.select(".finfo li:contains(Жанр) a").joinToString { it.text() }
            status = parseStatus(document.select(".finfo li:contains(Статус)").text())
            author = document.select(".finfo li:contains(Студия) a").text()
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("выходит", true) -> SAnime.ONGOING
        status.contains("завершен", true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // =============================== Episodes ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string())
        val episodes = mutableListOf<SEpisode>()

        // AniMaunt uses a script-based player or tabs.
        // We extract the player URL which usually contains the episode list.
        val playerIframe = document.select("#video-player iframe").attr("src")

        // If no iframe, we look for data-attributes in the player div
        val playerDiv = document.select("#video-player")

        // Legacy sites often have a simple list or a script.
        // For AniMaunt, we'll assume the standard DLE structure where episodes are in the player.
        // Since we can't execute JS, we return a single entry pointing to the page if it's a movie,
        // or try to find the episode count from the info block.

        val epInfo = document.select(".finfo li:contains(Серии)").text()
        val totalEpisodes = Regex("(\\d+) из").find(epInfo)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        for (i in totalEpisodes downTo 1) {
            episodes.add(SEpisode.create().apply {
                name = "Серия $i"
                episode_number = i.toFloat()
                url = response.request.url.toString() + "?ep=$i"
            })
        }

        return episodes
    }

    // =============================== Video Links ===============================

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string())
        val videoList = mutableListOf<Video>()

        // AniMaunt typically uses Kodik or their own player.
        // We look for the iframe source.
        val iframeUrl = document.select(".video-inside iframe").attr("src").let {
            if (it.startsWith("//")) "https:$it" else it
        }

        if (iframeUrl.isNotEmpty()) {
            videoList.add(Video(iframeUrl, "Player", iframeUrl))
        }

        return videoList
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
```
