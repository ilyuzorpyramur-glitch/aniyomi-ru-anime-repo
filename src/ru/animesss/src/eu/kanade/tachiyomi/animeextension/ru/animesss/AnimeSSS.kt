package eu.kanade.tachiyomi.animeextension.ru.animesss

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

class AnimeSSS :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AnimeSSS"

    override val baseUrl = "https://animesss.tv"

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

    override fun popularAnimeRequest(page: Int): Request = listRequest("/aniserials/mnogoseriynye/", page)

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

        return listRequest(AnimeSSSFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeSSSFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        val rows = document.select(".pcoln__list li")
        fun row(label: String): String? = rows
            .firstOrNull { it.selectFirst("span")?.text()?.trim()?.removeSuffix(":") == label }
            ?.select("span")
            ?.getOrNull(1)
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.trim()?.removeSuffix(" аниме").orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = document.select(".pcoln__list-genres a")
                .map { it.text().trim() }
                .filterNot { it.isBlank() }
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
                listOf("Год выпуска", "Просмотр", "Студия", "Озвучка", "Страна", "Статус")
                    .forEach { label -> row(label)?.let { appendLine("$label: $it") } }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = runBlocking {
        val document = response.useAsJsoup()

        val newsId = document.selectFirst("#kodik_player_ajax")?.attr("data-news_id")
            ?: throw Exception("Плеер не найден на странице тайтла")

        val playlistHtml = playlistHtml(newsId)
        val hub = parseHubParams(playlistHtml)

        if (hub != null) {
            val playlist = client.newCall(hubPlaylistRequest(hub))
                .awaitSuccess()
                .parseAs<PlaylistDto>()

            // One episode appears once per voice-over; collapse them into one row.
            val grouped = playlist.items
                .filter { !it.vkId.isNullOrBlank() }
                .groupBy { (it.season ?: 1) to (it.episode ?: 1f) }

            if (grouped.isNotEmpty()) {
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

                        SEpisode.create().apply {
                            url = "hub|$newsId|${hub.titleId}|${hub.publisherId}|${hub.aggregator}|$season|${number.formatNumber()}"
                            episode_number = number
                            name = if (isSingle) "Фильм" else "Серия ${number.formatNumber()}"
                            scanlator = voices.take(4).joinToString().takeIf { it.isNotBlank() }
                        }
                    }
            }
        }

        // Fall back to the Kodik donor when the main player has nothing.
        val kodikLink = parseKodikLinks(playlistHtml).firstOrNull()?.second
            ?: throw Exception("Не удалось получить список серий")

        val player = client.newCall(GET(kodikLink.toAbsoluteUrl(), headers)).awaitSuccess().bodyString()
        val options = Jsoup.parse(player).select(".serial-series-box option")

        if (options.isEmpty()) {
            return@runBlocking listOf(
                SEpisode.create().apply {
                    url = "kodik|$newsId|"
                    episode_number = 1f
                    name = "Фильм"
                },
            )
        }

        options.mapNotNull { option ->
            val value = option.attr("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SEpisode.create().apply {
                url = "kodik|$newsId|$value"
                episode_number = value.toFloatOrNull() ?: 1f
                name = option.attr("data-title").trim().ifBlank { "Серия $value" }
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split('|')
        val videos = when (parts.firstOrNull()) {
            "hub" -> hubVideos(parts)
            "kodik" -> kodikDonorVideos(parts)
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
        if (parts.size < 7) return emptyList()

        val hub = HubParams(parts[2], parts[3], parts[4])
        val season = parts[5]
        val number = parts[6]

        val playlist = client.newCall(hubPlaylistRequest(hub)).awaitSuccess().parseAs<PlaylistDto>()

        val targets = playlist.items.filter {
            !it.vkId.isNullOrBlank() &&
                (it.season ?: 1).toString() == season &&
                (it.episode ?: 1f).formatNumber() == number
        }

        val onePerVoice = preferences.getBoolean(PREF_ONE_PER_VOICE_KEY, PREF_ONE_PER_VOICE_DEFAULT)
        val chosen = if (onePerVoice) targets.distinctBy { it.voiceLabel } else targets

        return chosen.parallelCatchingFlatMap { item -> hubItemVideos(item) }
    }

    private suspend fun hubItemVideos(item: PlaylistItemDto): List<Video> {
        val vkId = item.vkId ?: return emptyList()

        val video = client.newCall(GET("$HUB_API_URL/player/sv/video/$vkId", apiHeaders()))
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

    private suspend fun kodikDonorVideos(parts: List<String>): List<Video> {
        if (parts.size < 3) return emptyList()

        val newsId = parts[1]
        val episodeValue = parts[2]

        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)

        val translations = parseKodikLinks(playlistHtml(newsId))
            .filterNot { ignoreSubs && it.first.contains("subtitle", true) }

        return translations.parallelCatchingFlatMap { (title, link) ->
            val playerUrl = resolveKodikEpisode(link.toAbsoluteUrl(), episodeValue) ?: return@parallelCatchingFlatMap emptyList()
            kodikVideos(playerUrl, title)
        }
    }

    private suspend fun resolveKodikEpisode(playerUrl: String, episodeValue: String): String? {
        if (episodeValue.isBlank()) return playerUrl

        val page = client.newCall(GET(playerUrl, headers)).awaitSuccess().bodyString()
        val option = Jsoup.parse(page)
            .select(".serial-series-box option")
            .firstOrNull { it.attr("value") == episodeValue }
            ?: return null

        val id = option.attr("data-id").takeIf { it.isNotBlank() } ?: return null
        val hash = option.attr("data-hash").takeIf { it.isNotBlank() } ?: return null

        return "https://kodikplayer.com/seria/$id/$hash/720p"
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

    private suspend fun playlistHtml(newsId: String): String {
        val url = "$baseUrl/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("controller", "ajax")
            .addQueryParameter("mod", "animesss_playlist")
            .addQueryParameter("news_id", newsId)
            .build()

        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return client.newCall(GET(url, ajaxHeaders)).awaitSuccess().bodyString()
    }

    /** The main donor encodes its CDN Video Hub ids into a query string on each voice-over. */
    private fun parseHubParams(playlistHtml: String): HubParams? {
        val query = Jsoup.parse(playlistHtml)
            .select("[data-player-query]")
            .map { it.attr("data-player-query") }
            .firstOrNull { it.contains("title_id") }
            ?: return null

        val values = query.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (key.isBlank()) null else key to Uri.decode(value)
            }
            .toMap()

        val titleId = values["title_id"]?.takeIf { it.isNotBlank() } ?: return null

        return HubParams(
            titleId = titleId,
            publisherId = values["publisher_id"]?.takeIf { it.isNotBlank() } ?: "1",
            aggregator = values["aggregator"]?.takeIf { it.isNotBlank() } ?: "mali",
        )
    }

    private fun parseKodikLinks(playlistHtml: String): List<Pair<String, String>> = Jsoup.parse(playlistHtml)
        .select("[data-this-link]")
        .mapNotNull { element ->
            val link = element.attr("data-this-link").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            element.text().trim().ifBlank { "Kodik" } to link
        }

    private fun hubPlaylistRequest(hub: HubParams): Request {
        val url = "$HUB_API_URL/player/sv/playlist".toHttpUrl().newBuilder()
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
        val cleanPath = path.ifBlank { "/ongoing/" }.removeSuffix("/")
        val url = if (page > 1) "$baseUrl$cleanPath/page/$page/" else "$baseUrl$cleanPath/"

        return GET(url, headers)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()

        // Restrict to the main column and drop the "related" carousel: both reuse the
        // same card markup, which used to surface the same titles twice in one page.
        val animes = document.select("main.col-main a.poster")
            .filter { it.closest(".pmovie__related") == null }
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
        if (!href.endsWith(".html")) return null

        val url = runCatching { href.toHttpUrl() }.getOrNull()
        if (url != null && url.host != baseUrl.toHttpUrl().host) return null
        val path = url?.encodedPath ?: href

        val label = attr("title").ifBlank {
            selectFirst(".poster__title, .th-item__title, .short-item__title")?.text()
                ?: text()
        }.trim()

        if (label.isBlank()) return null

        return SAnime.create().apply {
            this.url = path
            title = label
            thumbnail_url = selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
                ?: parent()?.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
        }
    }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> "https://$this"
    }

    private fun Float.formatNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val HUB_API_URL = "https://plapi.cdnvideohub.com/api/v1"
        private const val SEARCH_PAGE_SIZE = 15

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
