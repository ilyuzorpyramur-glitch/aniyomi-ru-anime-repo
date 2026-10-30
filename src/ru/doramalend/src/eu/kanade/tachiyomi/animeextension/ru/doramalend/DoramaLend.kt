package eu.kanade.tachiyomi.animeextension.ru.doramalend

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DoramaLend :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "DoramaLend"

    override val baseUrl = "https://s2.doramalend.tv"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = listRequest("/luchshie-doramy/", page)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest("/doramy-novye/", page)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        if (query.isNotBlank()) {
            // DLE search: page 1 posts to the site root, later pages to index.php.
            val body = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("story", query)
                .add("search_start", page.toString())
                .add("full_search", "0")
                .add("result_from", (((page - 1) * SEARCH_PAGE_SIZE) + 1).toString())
                .build()

            return POST("$baseUrl/index.php?do=search", headers, body)
        }

        return listRequest(DoramaLendFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = DoramaLendFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        val infoRows = document.select(".pmovie__list li")
        fun row(label: String): String? = infoRows
            .firstOrNull { it.selectFirst("span")?.text()?.trim()?.removeSuffix(":") == label }
            ?.text()
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            // Page titles carry an SEO tail — cut it off at the release year.
            title = document.selectFirst("h1")?.text()?.cleanTitle().orEmpty()
            thumbnail_url = document.selectFirst(".pmovie__img img[src]")?.absUrl("src")
            genre = row("Жанр")
            author = row("Режиссер")
            artist = row("В переводе")
            status = if (document.selectFirst(".ser-dropdown-options div[data-href]") == null) {
                SAnime.COMPLETED
            } else {
                SAnime.UNKNOWN
            }
            description = buildString {
                document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                listOf("Дата выхода", "Страна", "Жанр", "В качестве", "В переводе", "В ролях")
                    .forEach { label -> row(label)?.let { appendLine("$label: $it") } }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val titlePath = response.request.url.encodedPath

        val options = document.select(".ser-dropdown-options div[data-href]")

        // Movies and single-part releases have no episode dropdown: the title page is the player.
        if (options.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    url = titlePath
                    episode_number = 1f
                    name = "Фильм"
                },
            )
        }

        return options.mapNotNull { option ->
            val href = option.attr("data-href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)
            val label = option.text().trim()
            val number = EPISODE_NUMBER_REGEX.find(path)?.groupValues?.get(1)?.toFloatOrNull()
                ?: EPISODE_NUMBER_REGEX_TEXT.find(label)?.groupValues?.get(1)?.toFloatOrNull()
                ?: 1f

            SEpisode.create().apply {
                url = path
                episode_number = number
                name = label.ifBlank { "Серия ${number.toInt()}" }
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val document = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().useAsJsoup()

        val players = document.select("div.tabcontent iframe[src]")
            .mapNotNull { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() }?.toAbsoluteUrl()
                    ?: return@mapNotNull null
                Player(document.translationLabel(iframe), src)
            }
            .distinctBy { it.url }

        if (players.isEmpty()) throw Exception("Плеер не найден на странице серии")

        val videos = players.parallelCatchingFlatMap(::playerVideos)

        if (videos.isEmpty()) throw Exception("Не удалось получить ссылки на видео для этой серии")

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private class Player(val label: String, val url: String)

    /** The tab button that switches to this iframe carries the voice-over name. */
    private fun Document.translationLabel(iframe: Element): String {
        val tabId = iframe.parents().firstOrNull { it.hasClass("tabcontent") }?.id()
        val button = tabId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> select("button.tablinks").firstOrNull { it.attr("onclick").contains("'$id'") } }

        return button?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "Плеер"
    }

    private suspend fun playerVideos(player: Player): List<Video> {
        val page = client.newCall(GET(player.url, playerHeaders(player.url))).awaitSuccess().bodyString()

        val file = PLAYERJS_FILE_REGEX.find(page)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        // Playerjs accepts either a single playlist or a "[label]url,[label]url" list.
        return file.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .flatMap { entry ->
                val label = PLAYERJS_LABEL_REGEX.find(entry)?.groupValues?.get(1)?.trim().orEmpty()
                val url = entry.substringAfter(']').trim().toAbsoluteUrl()
                val name = listOf(player.label, label).filter { it.isNotBlank() }.joinToString(" - ")

                if (url.contains(".m3u8")) {
                    playlistUtils.extractFromHls(
                        playlistUrl = url,
                        referer = player.url,
                        videoNameGen = { quality -> "$name - $quality" },
                    ).ifEmpty { listOf(Video(url, name, url, headers = playerHeaders(player.url))) }
                } else {
                    listOf(Video(url, name, url, headers = playerHeaders(player.url)))
                }
            }
    }

    private fun playerHeaders(playerUrl: String) = headers.newBuilder()
        .set("Referer", playerUrl)
        .build()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Предпочитаемое качество",
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

    private fun listRequest(path: String, page: Int): Request {
        val cleanPath = path.ifBlank { "/doramy-novye/" }.removeSuffix("/")
        val url = if (page > 1) "$baseUrl$cleanPath/page/$page/" else "$baseUrl$cleanPath/"

        return GET(url, headers)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("div.poster a.poster__link")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        val current = PAGE_REGEX.find(response.request.url.encodedPath)?.groupValues?.get(1)?.toIntOrNull()
            ?: searchStart(response)
            ?: 1

        return AnimesPage(animes, document.hasPageAfter(current))
    }

    /** Search results are POSTed, so the current page lives in the request body. */
    private fun searchStart(response: Response): Int? {
        val body = response.request.body ?: return null
        if (body !is FormBody) return null

        return (0 until body.size)
            .firstOrNull { body.name(it) == "search_start" }
            ?.let { body.value(it).toIntOrNull() }
    }

    // Category pages link the pager by href (/page/N/); search results submit it with
    // list_submit(N). A next page exists only when one of them points past the current.
    private fun Document.hasPageAfter(current: Int): Boolean = select("#pagination a, .pagination a").any { link ->
        val page = PAGE_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            ?: LIST_SUBMIT_REGEX.find(link.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull()
        page != null && page > current
    }

    private fun Element.toSAnime(): SAnime? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val path = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)
        val container = parents().firstOrNull { it.hasClass("poster") }

        return SAnime.create().apply {
            url = path
            title = (selectFirst(".poster__title")?.text() ?: text()).cleanTitle()
            thumbnail_url = container?.selectFirst(".poster__img img[src]")
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
            genre = container?.selectFirst(".poster__subtitle")?.text()?.trim()
        }
    }

    private fun String.cleanTitle(): String = trim()
        .replace(TITLE_TAIL_REGEX, "")
        .trim()

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> "https://$this"
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val SEARCH_PAGE_SIZE = 10

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private val PAGE_REGEX = Regex("""/page/(\d+)""")
        private val LIST_SUBMIT_REGEX = Regex("""list_submit\((\d+)\)""")
        private val EPISODE_NUMBER_REGEX = Regex("""seriya-(\d+)""")
        private val EPISODE_NUMBER_REGEX_TEXT = Regex("""(\d+)""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val PLAYERJS_FILE_REGEX = Regex("""file\s*:\s*["'](.*?)["']""")
        private val PLAYERJS_LABEL_REGEX = Regex("""^\[(.*?)]""")
        private val TITLE_TAIL_REGEX = Regex("""\s*\(\d{4}\).*$|\s+дорама смотреть.*$""", RegexOption.IGNORE_CASE)
    }
}
