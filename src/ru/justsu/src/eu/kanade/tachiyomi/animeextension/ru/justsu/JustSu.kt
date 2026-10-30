package eu.kanade.tachiyomi.animeextension.ru.justsu

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

class JustSu :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "JustSu"

    override val baseUrl = "https://just-su.org"

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

    override fun popularAnimeRequest(page: Int): Request = listRequest("/vyshlo/", page)

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

        return listRequest(JustSuFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = JustSuFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        fun row(label: String): String? = document.select(".pmovie__anime, .pmovie__list li")
            .firstOrNull { it.text().trim().startsWith(label) }
            ?.text()
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = document.select(".pmovie__genres a, .pmovie__anime a")
                .map { it.text().trim() }
                .filterNot { it.isBlank() || it.toIntOrNull() != null }
                .distinct()
                .take(12)
                .joinToString()
            author = row("Студия")
            status = SAnime.UNKNOWN
            description = buildString {
                document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                document.select(".pmovie__original-title")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { appendLine("Другие названия: ${it.joinToString(" / ")}") }
                listOf("Год", "Эпизоды", "Студия", "Жанр", "Озвучка", "Страна", "Статус")
                    .forEach { label -> row(label)?.let { appendLine("$label: $it") } }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = runBlocking {
        val document = response.useAsJsoup()

        val hub = document.selectFirst("video-player[data-title-id]")?.let {
            HubParams(
                titleId = it.attr("data-title-id"),
                publisherId = it.attr("data-publisher-id").ifBlank { "1" },
                aggregator = it.attr("data-aggregator").ifBlank { "mali" },
            )
        }

        val playlist = hub?.let {
            runCatching {
                client.newCall(playlistRequest(it)).awaitSuccess().parseAs<PlaylistDto>()
            }.getOrNull()
        }

        // One episode appears once per voice-over; group them so each episode is a single row.
        val grouped = playlist?.items
            .orEmpty()
            .filter { !it.vkId.isNullOrBlank() }
            .groupBy { (it.season ?: 1) to (it.episode ?: 1f) }

        if (hub != null && grouped.isNotEmpty()) {
            val isSingle = grouped.size == 1

            return@runBlocking grouped.entries
                .sortedWith(
                    compareByDescending<Map.Entry<Pair<Int, Float>, List<PlaylistItemDto>>> { it.key.first }
                        .thenByDescending { it.key.second },
                )
                .map { (key, items) ->
                    val season = key.first
                    val number = key.second
                    val voices = items.map { it.voiceLabel }.distinct()
                    val itemName = items.first().name
                        ?.takeIf { it.isNotBlank() && it != playlist?.titleName }

                    SEpisode.create().apply {
                        url = "hub|${hub.titleId}|${hub.publisherId}|${hub.aggregator}|$season|${number.formatNumber()}"
                        episode_number = number
                        name = when {
                            isSingle -> "Фильм"
                            itemName != null -> itemName
                            else -> "Эпизод ${number.formatNumber()}"
                        }
                        scanlator = voices.take(4).joinToString().takeIf { it.isNotBlank() }
                    }
                }
        }

        // The CDN player answers with an empty body for some releases; those pages still
        // embed a Kodik iframe, so fall back to it instead of showing an empty episode list.
        val kodikUrl = document.kodikIframeUrl()
            ?: throw Exception("Плеер не найден на странице тайтла")

        val player = client.newCall(GET(kodikUrl, headers)).awaitSuccess().bodyString()
        val options = Jsoup.parse(player).select(".serial-series-box option")

        if (options.isEmpty()) {
            return@runBlocking listOf(
                SEpisode.create().apply {
                    url = "kodik|$kodikUrl|"
                    episode_number = 1f
                    name = "Фильм"
                },
            )
        }

        options.mapNotNull { option ->
            val value = option.attr("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            SEpisode.create().apply {
                url = "kodik|$kodikUrl|$value"
                episode_number = value.toFloatOrNull() ?: 1f
                name = option.attr("data-title").trim().ifBlank { "Серия $value" }
            }
        }.sortedByDescending { it.episode_number }
    }

    private fun Document.kodikIframeUrl(): String? = select("iframe[src*=kodikplayer]")
        .map { it.attr("src") }
        .firstOrNull { it.isNotBlank() }
        ?.toAbsoluteUrl()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split('|')

        val videos = when (parts.firstOrNull()) {
            "hub" -> hubVideos(parts)
            "kodik" -> kodikVideos(parts)
            else -> emptyList()
        }

        if (videos.isEmpty()) {
            throw Exception("Не удалось получить ссылки на видео для этой серии")
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private suspend fun hubVideos(parts: List<String>): List<Video> {
        if (parts.size < 6) return emptyList()

        val hub = HubParams(parts[1], parts[2], parts[3])
        val season = parts[4]
        val number = parts[5]

        val playlist = client.newCall(playlistRequest(hub)).awaitSuccess().parseAs<PlaylistDto>()

        val targets = playlist.items.filter {
            !it.vkId.isNullOrBlank() &&
                (it.season ?: 1).toString() == season &&
                (it.episode ?: 1f).formatNumber() == number
        }

        if (targets.isEmpty()) return emptyList()

        val ignoreDuplicates = preferences.getBoolean(PREF_ONE_PER_VOICE_KEY, PREF_ONE_PER_VOICE_DEFAULT)
        val chosen = if (ignoreDuplicates) targets.distinctBy { it.voiceLabel } else targets

        return chosen.parallelCatchingFlatMap { item -> itemVideos(item) }
    }

    private suspend fun itemVideos(item: PlaylistItemDto): List<Video> {
        val vkId = item.vkId ?: return emptyList()

        val video = client.newCall(GET("$API_URL/player/sv/video/$vkId", apiHeaders()))
            .awaitSuccess()
            .parseAs<VideoDto>()

        val hlsUrl = video.sources?.hlsUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val label = item.voiceLabel

        return playlistUtils.extractFromHls(
            playlistUrl = hlsUrl,
            referer = "$baseUrl/",
            videoNameGen = { "$label - $it" },
        ).ifEmpty {
            listOf(Video(hlsUrl, label, hlsUrl, headers = apiHeaders()))
        }
    }

    // ---------------------------- Kodik fallback ----------------------------

    private suspend fun kodikVideos(parts: List<String>): List<Video> {
        if (parts.size < 3) return emptyList()

        val iframeUrl = parts[1]
        val episodeValue = parts[2]

        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)
        val player = client.newCall(GET(iframeUrl, headers)).awaitSuccess().bodyString()

        // Every voice-over is a separate Kodik "media" with its own id, hash and episodes.
        val translations = Jsoup.parse(player)
            .select(".serial-translations-box option")
            .mapNotNull { option ->
                val mediaId = option.attr("data-media-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mediaHash = option.attr("data-media-hash").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mediaType = option.attr("data-media-type").ifBlank { "serial" }

                Translation(
                    title = option.attr("data-title").trim().ifBlank { "Kodik" },
                    isSubtitles = option.attr("data-translation-type") == "subtitles",
                    url = "https://kodikplayer.com/$mediaType/$mediaId/$mediaHash/720p",
                )
            }
            .filterNot { ignoreSubs && it.isSubtitles }
            .ifEmpty { listOf(Translation("Kodik", false, iframeUrl)) }

        return translations.parallelCatchingFlatMap { translation ->
            translationVideos(translation, episodeValue)
        }
    }

    private class Translation(val title: String, val isSubtitles: Boolean, val url: String)

    private suspend fun translationVideos(translation: Translation, episodeValue: String): List<Video> {
        val targetUrl = if (episodeValue.isBlank()) {
            translation.url
        } else {
            val page = client.newCall(GET(translation.url, headers)).awaitSuccess().bodyString()
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

        return kodikPlayerVideos(targetUrl, label)
    }

    private suspend fun kodikPlayerVideos(playerUrl: String, label: String): List<Video> {
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

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> "https://$this"
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
            key = PREF_ONE_PER_VOICE_KEY,
            default = PREF_ONE_PER_VOICE_DEFAULT,
            title = "Убирать дубликаты озвучек",
            summary = "Оставлять по одной записи на студию — сайт часто отдаёт повторы",
        )

        screen.addSwitchPreference(
            key = PREF_IGNORE_SUBS_KEY,
            default = PREF_IGNORE_SUBS_DEFAULT,
            title = "Скрывать переводы субтитрами",
            summary = "Действует для резервного плеера Kodik",
        )
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 1080

    // =============================== Utils ================================

    private class HubParams(val titleId: String, val publisherId: String, val aggregator: String)

    private fun playlistRequest(hub: HubParams): Request {
        val url = "$API_URL/player/sv/playlist".toHttpUrl().newBuilder()
            .addQueryParameter("pub", hub.publisherId)
            .addQueryParameter("id", hub.titleId)
            .addQueryParameter("aggr", hub.aggregator)
            .build()

        return GET(url, apiHeaders())
    }

    private fun apiHeaders(): Headers = Headers.Builder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )
        .build()

    private fun listRequest(path: String, page: Int): Request {
        val cleanPath = path.ifBlank { "/vyshlo/" }.removeSuffix("/")
        val url = if (page > 1) "$baseUrl$cleanPath/page/$page/" else "$baseUrl$cleanPath/"

        return GET(url, headers)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()

        // Only the main column is the catalog: the sidebar ("popular", "top") and the
        // related carousel reuse the same card markup and used to leak in as duplicates.
        val animes = document.select("main.col-main a.poster")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        val current = PAGE_REGEX.find(response.request.url.encodedPath)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

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

        return SAnime.create().apply {
            url = path
            title = attr("title").ifBlank { selectFirst(".poster__title, .popular__title, .top__title")?.text().orEmpty() }.trim()
            thumbnail_url = selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
        }
    }

    private fun Float.formatNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val API_URL = "https://plapi.cdnvideohub.com/api/v1"
        private const val SEARCH_PAGE_SIZE = 12

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_ONE_PER_VOICE_KEY = "pref_one_per_voice"
        private const val PREF_ONE_PER_VOICE_DEFAULT = true

        private const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        private const val PREF_IGNORE_SUBS_DEFAULT = false

        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'(.*?)'""")

        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val PAGE_REGEX = Regex("""/page/(\d+)""")
    }
}
