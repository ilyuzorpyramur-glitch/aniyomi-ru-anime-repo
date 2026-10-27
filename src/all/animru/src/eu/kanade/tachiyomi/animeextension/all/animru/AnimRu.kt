package eu.kanade.tachiyomi.animeextension.all.animru

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimRu :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Animeru"

    override val baseUrl = "https://anim-ru.net"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // ============================== Popular ===============================

    // У сайта нет сортировки «по популярности», поэтому собираем главную за несколько
    // страниц и сортируем карточки по рейтингу, который отдаёт сам сайт.
    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl("/home/", page * POPULAR_PAGES), headers)

    override suspend fun popularAnimeParse(response: Response): AnimesPage {
        val firstPage = response.parseDocument().cards()
        val collected = firstPage.toMutableList()

        val startPage = response.request.url.toString().pageNumber()
        if (firstPage.isNotEmpty()) {
            for (extra in 1 until POPULAR_PAGES) {
                val doc = client.newCall(GET(pageUrl("/home/", startPage + extra), headers))
                    .awaitSuccess()
                    .parseDocument()
                collected += doc.cards()
            }
        }

        val sorted = collected
            .distinctBy { it.url }
            .sortedWith(compareByDescending<Card> { it.rating })

        return AnimesPage(sorted.map { it.anime }, sorted.size >= ITEMS_PER_PAGE)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl("/novinki-anime-sdg3j34b6jd/", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) {
            return GET(pageUrl(AnimRuFilters.sectionUrl(filters), page), headers)
        }

        val url = "$baseUrl/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("do", "search")
            .addQueryParameter("subaction", "search")
            .addQueryParameter("story", query.trim())
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response, singlePage = true)

    override fun getFilterList(): AnimeFilterList = AnimRuFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.parseDocument()

        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.clone()?.apply { select("a").remove() }?.text().orEmpty()
                .ifBlank { doc.title().substringBefore("аниме").trim() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            description = doc.selectFirst("div.full_opisanie[itemprop=description]")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            genre = doc.selectFirst("span:contains(Жанры) ~ span")?.select("a")
                ?.map { it.text() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.take(10)
                ?.joinToString(", ")
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.parseDocument()
        val titlePath = response.request.url.encodedPath

        val episodes = doc.select("select.sel_page option[value*=/page-]").mapNotNull { option ->
            val path = option.attr("value").trim().takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val number = SERIES_REGEX.find(option.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0

            SEpisode.create().apply {
                // Страницы /page-1-*.html не существует: первая серия живёт по адресу тайтла,
                // но с отдельным url, чтобы приложение не путало её с самим тайтлом.
                url = if (number <= 1) "$titlePath?ep=1" else path
                name = option.text().trim()
                episode_number = if (number <= 1) 1f else number.toFloat()
            }
        }.distinctBy { it.url }

        if (episodes.isNotEmpty()) return episodes.sortedBy { it.episode_number }

        return listOf(
            SEpisode.create().apply {
                url = "$titlePath?ep=1"
                name = "Серия 1"
                episode_number = 1f
            },
        )
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = baseUrl + episode.url
        val page = client.newCall(GET(episodeUrl, headers)).awaitSuccess().bodyText()

        val playerUrl = findPlayerUrl(page)
            ?: fallbackPlayerUrl(episode)?.let { canonical ->
                client.newCall(GET(baseUrl + canonical, headers)).awaitSuccess().bodyText().let(::findPlayerUrl)
            }
            ?: throw Exception(
                if (isBlocked(page)) {
                    "Сайт отдал защиту Cloudflare вместо страницы — попробуйте позже"
                } else {
                    "На странице серии нет плеера"
                },
            )

        val playerHeaders = headers.newBuilder()
            .set("Referer", episodeUrl)
            .set("Origin", baseUrl)
            .build()
        val playerPage = client.newCall(GET(playerUrl, playerHeaders)).awaitSuccess().bodyText()

        val payload = ATOB_REGEX.find(playerPage)?.groupValues?.get(1)
            ?: throw Exception("Плеер не вернул список дорожек")

        val decoded = String(Base64.decode(payload, Base64.DEFAULT))
        val videoHeaders = headers.newBuilder().set("Referer", playerUrl).build()

        val videos = FILE_ENTRY_REGEX.findAll(decoded)
            .mapNotNull { match ->
                val label = match.groupValues[1].trim()
                val link = match.groupValues[2].trim().toAbsoluteUrl()
                if (link.isEmpty() || !link.contains(".m3u8")) return@mapNotNull null

                Video(link, label.toQualityLabel(), link, headers = videoHeaders)
            }
            .distinctBy { it.videoUrl }
            .toList()

        if (videos.isEmpty()) throw Exception("Ссылки на видео не найдены")

        val preferred = preferredQuality
        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferred }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Качество по умолчанию",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
        )
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 720

    // =============================== Utils ================================

    private data class Card(val anime: SAnime, val rating: Int)

    private fun Document.cards(): List<Card> = select("article").mapNotNull { it.toCard() }

    /** Рейтинг сайта хранится в ширине полоски: style="width:60%". */
    private fun Element.toCard(): Card? {
        if (hasClass("news")) return null

        val href = selectFirst("a[href]")?.attr("href")
            ?.takeIf { POST_REGEX.containsMatchIn(it) }
            ?: return null

        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim().orEmpty()

        val rating = selectFirst("li.current-rating")?.attr("style")
            ?.let { RATING_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 0

        val anime = SAnime.create().apply {
            url = href.substringAfter(baseUrl)
            this.title = title
            thumbnail_url = selectFirst("img")?.attr("src")?.toAbsoluteUrl()
        }

        return Card(anime, rating)
    }

    private fun findPlayerUrl(html: String): String? {
        val doc = Jsoup.parse(html, baseUrl)

        doc.selectFirst("iframe#activeplayer")?.let { frame ->
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }
            if (src.contains("/player/")) return src.toAbsoluteUrl()
        }

        doc.select("iframe[src*=/player/]").firstOrNull()?.let { return it.attr("src").toAbsoluteUrl() }

        return PLAYER_ANY_REGEX.find(html)?.groupValues?.get(1)?.toAbsoluteUrl()
    }

    private fun fallbackPlayerUrl(episode: SEpisode): String? =
        PAGE_SLUG_REGEX.find(episode.url)?.groupValues?.get(1)?.let { "/$it" }

    private fun isBlocked(html: String): Boolean =
        "Just a moment" in html || "Attention Required" in html || "cf-browser-verification" in html

    private fun pageUrl(path: String, page: Int): String = if (page <= 1) "$baseUrl$path" else "$baseUrl$path/page/$page/"

    private fun String.pageNumber(): Int = Regex("""/page/(\d+)/""").find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun listParse(response: Response, singlePage: Boolean = false): AnimesPage {
        val cards = response.parseDocument().cards()
        return AnimesPage(cards.map { it.anime }.distinctBy { it.url }, !singlePage && cards.size >= ITEMS_PER_PAGE)
    }

    private fun Response.parseDocument(): Document = Jsoup.parse(bodyText(), baseUrl)

    private fun Response.bodyText(): String {
        val bytes = peekBody(Long.MAX_VALUE).bytes()
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase()
        val charset = if ("charset=windows-1251" in head || "charset=cp1251" in head) WINDOWS_1251 else Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun String.toAbsoluteUrl(): String = when {
        isEmpty() -> ""
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        else -> baseUrl + this.removePrefix(".")
    }

    private fun String.toQualityLabel(): String = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.let { "${it}p" } ?: "Видео"

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val ITEMS_PER_PAGE = 18

        private const val POPULAR_PAGES = 3

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private val WINDOWS_1251 = java.nio.charset.Charset.forName("windows-1251")

        private val SERIES_REGEX = Regex("""(\d+)""")
        private val POST_REGEX = Regex("""^/\d+-[^/]+\.html""")
        private val RATING_REGEX = Regex("""width:\s*(\d+)%""")
        private val PLAYER_ANY_REGEX = Regex("""(https?://[a-zA-Z0-9.-]+/player/\d+[^\s"'<>]*)""")
        private val PAGE_SLUG_REGEX = Regex("""/page-\d+-(.+?)(\?|$)""")
        private val ATOB_REGEX = Regex("""atob\("([A-Za-z0-9+/=]+)"\)""")
        private val FILE_ENTRY_REGEX = Regex("""\[([^\]]+)\]([^,\[]+)""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
    }
}
