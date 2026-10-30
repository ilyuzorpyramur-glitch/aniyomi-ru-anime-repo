package eu.kanade.tachiyomi.animeextension.ru.jutsunet

import android.net.Uri
import android.util.Base64
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
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JutSuNet :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Jut-su.net"

    override val baseUrl = "https://jut-su.net"

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

    override fun popularAnimeRequest(page: Int): Request = listRequest("/top100/", page)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest("/ongoing/", page)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        if (query.isNotBlank()) {
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

        return listRequest(JutSuNetFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = JutSuNetFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        val infoRows = document.select(".jutsu-page__info li")
        fun row(label: String): String? = infoRows
            .firstOrNull { it.selectFirst("span")?.text()?.trim()?.removeSuffix(":") == label }
            ?.text()
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = row("Жанр")
            author = row("Студия")
            status = when (row("Статус")?.substringBefore(" с ")?.trim()) {
                "Вышел" -> SAnime.COMPLETED
                "Онгоинг", "Выходит" -> SAnime.ONGOING
                "Анонс" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                document.selectFirst(".jutsu-page__original")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appendLine("Оригинальное название: $it") }
                listOf(
                    "Тип", "Аудитория", "Статус", "Длительность",
                    "Первоисточник", "Студия", "Возраст", "Тип перевода", "Озвучка от",
                ).forEach { label -> row(label)?.let { appendLine("$label: $it") } }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = runBlocking {
        val document = response.useAsJsoup()

        // Сайт перешёл на relay-плеер: id тайтла лежит в data-player-anime-id,
        // а ссылку на iframe Kodik отдаёт JSON-эндпоинт controller.php.
        val animeId = document.selectFirst("[data-player-anime-id]")
            ?.attr("data-player-anime-id")
            ?.takeIf { it.isNotBlank() }
            ?: document.select(".xfplayer[data-params]")
                .map { it.attr("data-params") }
                .firstNotNullOfOrNull { ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: throw Exception("Плеер не найден на странице тайтла")

        val iframeUrl = playerIframeUrl(animeId)
            ?: kodikIframeUrl(animeId)
            ?: throw Exception("Не удалось открыть плеер Kodik")

        val player = client.newCall(GET(iframeUrl, headers)).awaitSuccess().bodyString()
        val episodes = parseEpisodeOptions(player)

        if (episodes.isEmpty()) {
            // Movie / single-part release: the iframe itself is the only video.
            return@runBlocking listOf(
                SEpisode.create().apply {
                    url = "$iframeUrl|"
                    episode_number = 1f
                    name = "Фильм"
                },
            )
        }

        episodes
            .sortedByDescending { it.number }
            .map { option ->
                SEpisode.create().apply {
                    url = "$iframeUrl|${option.value}"
                    episode_number = option.number
                    name = option.title.ifBlank { "Серия ${option.value}" }
                }
            }
    }

    private class EpisodeOption(val value: String, val title: String) {
        val number: Float get() = value.toFloatOrNull() ?: 1f
    }

    private fun parseEpisodeOptions(playerHtml: String): List<EpisodeOption> = Jsoup.parse(playerHtml)
        .select(".serial-series-box option")
        .mapNotNull { option ->
            val value = option.attr("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EpisodeOption(value, option.attr("data-title").trim())
        }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val stored = episode.url.substringBefore('|')
        val episodeValue = episode.url.substringAfter('|', "")

        val iframeUrl = if (stored.startsWith("http")) {
            stored
        } else {
            kodikIframeUrl(stored) ?: return emptyList()
        }
        val player = client.newCall(GET(iframeUrl, headers)).awaitSuccess().bodyString()

        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)

        // Every voice-over is a separate Kodik "media" with its own id/hash.
        val translations = Jsoup.parse(player)
            .select(".serial-translations-box option")
            .mapNotNull { option ->
                val mediaId = option.attr("data-media-id").takeIf { it.isNotBlank() }
                val mediaHash = option.attr("data-media-hash").takeIf { it.isNotBlank() }
                val mediaType = option.attr("data-media-type").ifBlank { "serial" }
                if (mediaId == null || mediaHash == null) return@mapNotNull null

                Translation(
                    title = option.attr("data-title").trim().ifBlank { "Kodik" },
                    isSubtitles = option.attr("data-translation-type") == "subtitles",
                    url = "https://kodikplayer.com/$mediaType/$mediaId/$mediaHash/720p",
                )
            }
            .filterNot { ignoreSubs && it.isSubtitles }
            .ifEmpty {
                listOf(Translation("Kodik", false, iframeUrl))
            }

        val videos = translations.parallelCatchingFlatMap { translation ->
            translationVideos(translation, episodeValue)
        }

        if (videos.isEmpty()) {
            throw Exception("Не удалось получить ссылки на видео для этой серии")
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private class Translation(val title: String, val isSubtitles: Boolean, val url: String)

    private suspend fun translationVideos(translation: Translation, episodeValue: String): List<Video> {
        // Open this voice-over's player, then resolve the requested episode inside it.
        val page = client.newCall(GET(translation.url, headers)).awaitSuccess().bodyString()

        val targetUrl = if (episodeValue.isBlank()) {
            translation.url
        } else {
            val option = Jsoup.parse(page)
                .select(".serial-series-box option")
                .firstOrNull { it.attr("value") == episodeValue }
                ?: return emptyList()

            val id = option.attr("data-id").takeIf { it.isNotBlank() } ?: return emptyList()
            val hash = option.attr("data-hash").takeIf { it.isNotBlank() } ?: return emptyList()
            "https://kodikplayer.com/seria/$id/$hash/720p"
        }

        val label = buildString {
            append(translation.title)
            if (translation.isSubtitles) append(" (субтитры)")
        }

        return kodikVideos(targetUrl, label)
    }

    private suspend fun kodikVideos(playerUrl: String, label: String): List<Video> {
        val page = client.newCall(GET(playerUrl, headers)).awaitSuccess().bodyString()

        val params = URL_PARAMS_REGEX.find(page)?.groupValues?.get(1)?.parseAs<KodikUrlParams>()
            ?: return emptyList()
        if (params.d_sign.isEmpty() || params.pd.isEmpty()) return emptyList()

        val segments = playerUrl.toHttpUrl().pathSegments
        if (segments.size < 3) return emptyList()

        val formBody = FormBody.Builder()
            .add("d", params.d)
            .add("d_sign", Uri.decode(params.d_sign))
            .add("pd", params.pd)
            .add("pd_sign", Uri.decode(params.pd_sign))
            .add("ref", Uri.decode(params.ref))
            .add("ref_sign", Uri.decode(params.ref_sign))
            .add("type", segments[0])
            .add("id", segments[1])
            .add("hash", segments[2])
            .build()

        val ftorHeaders = Headers.Builder()
            .set("Referer", "$baseUrl/")
            .set("Origin", "https://${params.pd}")
            .set("User-Agent", "Mozilla/5.0 (Android)")
            .build()

        val ftor = client.newCall(POST("https://${params.pd}/ftor", ftorHeaders, formBody))
            .awaitSuccess()
            .parseAs<KodikFtorResponse>()

        return ftor.links.entries
            .sortedByDescending { it.key.toIntOrNull() ?: 0 }
            .flatMap { (quality, links) ->
                val encoded = links.firstOrNull()?.src ?: return@flatMap emptyList()
                val playlistUrl = decodeKodikSource(encoded) ?: return@flatMap emptyList()

                playlistUtils.extractFromHls(
                    playlistUrl = playlistUrl,
                    referer = "https://${params.pd}/",
                    videoNameGen = { "$label - $it" },
                ).ifEmpty {
                    listOf(Video(playlistUrl, "$label - ${quality}p", playlistUrl))
                }
            }
    }

    /** Ссылка на iframe Kodik из нового relay-плеера сайта. */
    private suspend fun playerIframeUrl(animeId: String): String? {
        val url = "$baseUrl/engine/ajax/controller.php".toHttpUrl().newBuilder()
            .addQueryParameter("mod", "player")
            .addQueryParameter("id", animeId)
            .addQueryParameter("slot", "0")
            .build()
        return runCatching {
            client.newCall(GET(url.toString(), headers))
                .awaitSuccess()
                .parseAs<PlayerResponse>()
                .data
                ?.src
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun kodikIframeUrl(newsId: String): String? {
        val url = "$baseUrl/engine/ajax/controller.php".toHttpUrl().newBuilder()
            .addQueryParameter("mod", "kodik-player")
            .addQueryParameter("url", "1")
            .addQueryParameter("action", "iframe")
            .addQueryParameter("id", newsId)
            .build()

        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client.newCall(GET(url, ajaxHeaders)).awaitSuccess().parseAs<AjaxResponse>()

        return response.data?.takeIf { it.isNotBlank() }?.toAbsoluteUrl()
    }

    /**
     * Kodik obfuscates the playlist url with a rotating alphabet shift applied before
     * base64 encoding. The shift changes over time, so every variant is tried and the
     * one that decodes into a valid url wins — no JS engine needed.
     */
    private fun decodeKodikSource(encoded: String): String? {
        for (shift in 1..25) {
            val decoded = runCatching {
                String(Base64.decode(encoded.rotate(shift).padBase64(), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: continue

            if (decoded.startsWith("http") || decoded.startsWith("//")) {
                return decoded.toAbsoluteUrl()
            }
        }
        return null
    }

    private fun String.rotate(shift: Int): String = map { char ->
        when {
            char in 'a'..'z' -> 'a' + (char - 'a' + shift) % 26
            char in 'A'..'Z' -> 'A' + (char - 'A' + shift) % 26
            else -> char
        }
    }.joinToString("")

    private fun String.padBase64(): String {
        val remainder = length % 4
        return if (remainder == 0) this else this + "=".repeat(4 - remainder)
    }

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

        screen.addSwitchPreference(
            key = PREF_IGNORE_SUBS_KEY,
            default = PREF_IGNORE_SUBS_DEFAULT,
            title = "Скрывать переводы субтитрами",
            summary = "Показывать только озвучки",
        )
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 720

    // =============================== Utils ================================

    private fun listRequest(path: String, page: Int): Request {
        val cleanPath = path.ifBlank { "/anime/" }.removeSuffix("/")

        // "ТОП 100" is a fixed single-page chart: /page/N there returns the same 100 rows.
        if (cleanPath in SINGLE_PAGE_PATHS) return GET("$baseUrl$cleanPath/", headers)

        val url = if (page > 1) "$baseUrl$cleanPath/page/$page/" else "$baseUrl$cleanPath/"

        return GET(url, headers)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("main.jutsu-main a.jutsu-item__title")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        val path = response.request.url.encodedPath
        if (SINGLE_PAGE_PATHS.any { path.startsWith(it) }) return AnimesPage(animes, false)

        val current = PAGE_REGEX.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        return AnimesPage(animes, document.hasPageAfter(current))
    }

    // DLE renders the pager as plain page links; a next page exists only when one of
    // them points past the page we are on.
    private fun Document.hasPageAfter(current: Int): Boolean = select("#pagination a[href], .pagination a[href]").any { link ->
        val page = PAGE_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        page != null && page > current
    }

    private fun Element.toSAnime(): SAnime? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val path = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)

        // The poster lives in a sibling block of the shared item container.
        val container = parent()?.parent()

        return SAnime.create().apply {
            url = path
            title = text().trim()
            thumbnail_url = container?.selectFirst(".jutsu-item__img img")
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> "https://$this"
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val SEARCH_PAGE_SIZE = 20

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        private const val PREF_IGNORE_SUBS_DEFAULT = false

        private val ID_REGEX = Regex("""id=(\d+)""")
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'(.*?)'""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val PAGE_REGEX = Regex("""/page/(\d+)""")
        private val SINGLE_PAGE_PATHS = listOf("/top100")
    }
}
