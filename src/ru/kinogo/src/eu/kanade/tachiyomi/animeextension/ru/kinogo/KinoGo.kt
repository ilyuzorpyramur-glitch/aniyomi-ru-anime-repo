package eu.kanade.tachiyomi.animeextension.ru.kinogo

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
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
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KinoGo :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "KinoGo"

    override val baseUrl = "https://kinogo.ec"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // The site sits behind a Cloudflare managed challenge; the interceptor
    // solves it in a WebView and reuses the clearance cookies afterwards.
    // Built lazily: the interceptor touches android.webkit, which is absent
    // from the JVM the repository inspector runs sources under.
    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(network.client, USER_AGENT))
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val webView by lazy { KinoGoWebView() }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", USER_AGENT)

    // The site hides its web player behind the official Android app and serves
    // player tabs through a signed gateway. The signature is the SHA-256 of the
    // official APK signing certificate.
    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("X-App-Signature", APP_SIGNATURE)
            .build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = listRequest("/v1new", page)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest("", page)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) return searchRequest(query, page)

        return listRequest(KinoGoFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = KinoGoFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.postId() ?: return GET(baseUrl + anime.url, headers)
        val path = URLEncoder.encode(anime.url, "UTF-8")

        return GET("$GATEWAY_URL/v1/post/$id?path=$path", apiHeaders)
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        if (response.request.url.host == API_HOST) {
            return gatewayDetails(response)
        }

        val document = response.useAsJsoup()
        val info = document.infoMap()

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.cleanTitle().orEmpty()
            thumbnail_url = document.selectFirst(POSTER_SELECTOR)?.absUrl("src")
            genre = info["Жанры"]?.split('/')?.joinToString(", ") { it.trim() }
            author = info["Озвучки для вас"]
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst(".description__block")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                DETAIL_KEYS.forEach { key ->
                    info[key]?.takeIf { it.isNotBlank() }?.let { appendLine("$key: $it") }
                }
            }.trim()
        }
    }

    private fun gatewayDetails(response: Response): SAnime {
        val post = response.parseAs<PostResponse>().data
        val info = post.xfields

        return SAnime.create().apply {
            url = response.request.url.queryParameter("path") ?: "/${post.id}"
            title = post.title
            thumbnail_url = info["poster"]?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http")) it else "$POSTER_HOST$it"
            }
            genre = info["genre-doram"]?.split(',')?.joinToString(", ") { it.trim() }
                ?: post.category.joinToString(", ") { it.name }
            author = info["translator"]
            status = SAnime.COMPLETED
            description = buildString {
                (info["desc"] ?: post.short_story).takeIf { it.isNotBlank() }?.let {
                    appendLine(it)
                    appendLine()
                }
                listOf(
                    "Год" to info["year-teg-xfsearch"],
                    "Страна" to info["strana-xfsearch"],
                    "Время" to info["vremya"],
                    "Режиссёр" to info["director"],
                    "В ролях" to info["actors"],
                ).forEach { (key, value) ->
                    value?.takeIf { it.isNotBlank() }?.let { appendLine("$key: $it") }
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.postId() ?: return GET(baseUrl + anime.url, headers)

        return GET("$GATEWAY_URL/v1/post/$id/player", apiHeaders)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val player = runCatching {
            client.newCall(episodeListRequest(anime)).awaitSuccess().parseAs<PlayerResponse>().data
        }.getOrNull()

        if (player == null || !player.has_player) return listOf(singleEpisode(anime.url))

        for (embed in player.cinemarEmbeds()) {
            val entries = entriesFrom(embed)

            if (entries.isEmpty()) continue

            // Movies list voice-overs only; series repeat every voice-over per episode.
            val episodes = entries.mapNotNull { it.parseLocation() }
                .distinct()
                .sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })

            if (episodes.isEmpty()) return listOf(singleEpisode(anime.url))

            return episodes.map { (season, number) ->
                SEpisode.create().apply {
                    // Keep the title path: player links are short-lived and are
                    // resolved again right before playback.
                    url = "${anime.url}$LOCATION_SEPARATOR$season:$number"
                    episode_number = season * 1000f + number
                    name = "$season сезон, $number серия"
                }
            }
        }

        return listOf(singleEpisode(anime.url))
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used.")

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val path = episode.url.substringBefore(LOCATION_SEPARATOR)
        val target = episode.url.substringAfter(LOCATION_SEPARATOR, "")
        val id = path.postId() ?: throw Exception("Плеер не найден на странице")

        val player = client.newCall(GET("$GATEWAY_URL/v1/post/$id/player", apiHeaders))
            .awaitSuccess()
            .parseAs<PlayerResponse>()
            .data

        val embeds = player.cinemarEmbeds()
        if (embeds.isEmpty()) throw Exception("Плеер не найден на странице")

        var lastError: Exception? = null
        for (embed in embeds) {
            try {
                val videos = videosFrom(embed, target)
                if (videos.isNotEmpty()) return videos
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("Ссылки на видео не найдены")
    }

    private suspend fun entriesFrom(embed: String): List<Entry> {
        val remote = runCatching { webView.eval(embed, wantFiles = false) }.getOrNull()
        val entries = remote?.let { runCatching { it.parseAs<List<EntryDto>>() }.getOrNull() }
            .orEmpty()
            .map { Entry(it.title, it.title2, it.data) }

        if (entries.isNotEmpty()) return entries

        // Older mirrors still allow a plain HTTP client.
        return runCatching {
            client.newCall(GET(embed, headers)).awaitSuccess().bodyString().playlistEntries()
        }.getOrDefault(emptyList())
    }

    private suspend fun videosFrom(embed: String, target: String): List<Video> {
        val remote = runCatching { webView.eval(embed, wantFiles = true) }.getOrNull()
        val resolved = remote?.let { runCatching { it.parseAs<List<EntryDto>>() }.getOrNull() }.orEmpty()

        val videos = resolved
            .map { it to Entry(it.title, it.title2, it.data) }
            .filter { target.isBlank() || it.second.parseLocation()?.asTarget() == target }
            .flatMap { (dto, entry) ->
                val url = dto.file.takeIf { it.startsWith("http") } ?: return@flatMap emptyList()
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "${embed.origin()}/",
                    videoNameGen = { "${entry.title} - $it" },
                ).ifEmpty { listOf(Video(url, entry.title, url, headers = headers)) }
            }

        if (videos.isNotEmpty()) return sortedVideos(videos)

        // Fallback for hosts that still answer a plain HTTP client.
        val page = client.newCall(GET(embed, headers)).awaitSuccess().bodyString()
        val entries = page.playlistEntries()
            .filter { target.isBlank() || it.parseLocation()?.asTarget() == target }

        if (entries.isEmpty()) throw Exception("Озвучки для этой серии не найдены")

        val fallback = entries.parallelCatchingFlatMap { entry ->
            val playlist = loadPlaylist(entry.data, embed) ?: return@parallelCatchingFlatMap emptyList()

            playlistUtils.extractFromHls(
                playlistUrl = playlist,
                referer = "${embed.origin()}/",
                videoNameGen = { "${entry.title} - $it" },
            ).ifEmpty { listOf(Video(playlist, entry.title, playlist, headers = headers)) }
        }

        if (fallback.isEmpty()) throw Exception("Ссылки на видео не найдены")

        return sortedVideos(fallback)
    }

    private fun sortedVideos(videos: List<Video>): List<Video> = videos.sortedWith(
        compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
            .thenByDescending { it.videoTitle.parseQuality() },
    )

    /** The player exchanges an opaque per-track token for a ready hls url. */
    private suspend fun loadPlaylist(token: String, referer: String): String? {
        val body = "\"${token.replace("\\/", "/")}\"".toRequestBody(JSON_MEDIA_TYPE)
        val requestHeaders = headers.newBuilder().set("Referer", referer).build()
        val origin = referer.origin()

        val response = client.newCall(POST("$origin/api/playlist/load", requestHeaders, body))
            .awaitSuccess()
            .parseAs<PlaylistResponse>()

        return response.file.takeIf { it.startsWith("http") }
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
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 720

    // =============================== Utils ================================

    private class Entry(val title: String, val location: String, val data: String)

    private fun Entry.parseLocation(): Pair<Int, Int>? {
        val match = LOCATION_REGEX.find(location) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null

        return season to number
    }

    private fun Pair<Int, Int>.asTarget(): String = "$first:$second"

    /**
     * The embed hides its track list in a base64 blob padded with junk characters.
     * Dropping everything outside the base64 alphabet and decoding the longest
     * aligned prefix recovers the readable part — enough for the token list.
     */
    private fun String.playlistEntries(): List<Entry> {
        val raw = FILE_REGEX.find(this)?.groupValues?.get(1) ?: return emptyList()
        val start = raw.indexOf(PLAYLIST_MARKER).takeIf { it >= 0 } ?: return emptyList()
        val clean = raw.substring(start).filter { it in BASE64_ALPHABET }
        val aligned = clean.substring(0, clean.length / 4 * 4)

        val decoded = runCatching { String(Base64.decode(aligned, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()
            ?: return emptyList()

        return ENTRY_REGEX.findAll(decoded)
            .map { Entry(it.groupValues[1].unescape(), it.groupValues[2].unescape(), it.groupValues[3]) }
            .filter { it.data.isNotBlank() }
            .toList()
    }

    /** Listing pages hang the number on the path tail: `/boevik/page/2/`. */
    private fun listRequest(path: String, page: Int): Request {
        val clean = path.removeSuffix("/")
        val url = if (page > 1) "$baseUrl$clean/page/$page/" else "$baseUrl$clean/"

        return GET(url, headers)
    }

    /** Search is the stock DLE post; results are paged by `search_start`. */
    private fun searchRequest(query: String, page: Int): Request {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("full_search", "0")
            .add("search_start", page.toString())
            .add("result_from", ((page - 1) * PER_PAGE + 1).toString())
            .add("story", query.trim())
            .build()

        return POST("$baseUrl/index.php?do=search", headers, body)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select(".shortstory")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        return AnimesPage(animes, animes.size >= PER_PAGE)
    }

    private fun Element.toSAnime(): SAnime? {
        val link = selectFirst(".shortstory__title a[href]") ?: return null
        val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val name = link.text().cleanTitle()
        if (name.isBlank()) return null

        return SAnime.create().apply {
            url = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)
            title = name
            thumbnail_url = selectFirst(".shortstory__poster img")?.let { img ->
                img.attr("data-src").takeIf { it.isNotBlank() }?.let { baseUrl + it } ?: img.absUrl("src")
            }
        }
    }

    private fun singleEpisode(path: String): SEpisode = SEpisode.create().apply {
        url = path
        episode_number = 1f
        name = "Фильм"
    }

    private fun Document.infoMap(): Map<String, String> = select(".m_info > div").mapNotNull { row ->
        val key = row.selectFirst("b, strong")?.text()?.trim()?.removeSuffix(":") ?: return@mapNotNull null
        val value = row.text().substringAfter(':').trim()

        key to value
    }.toMap()

    private fun String.unescape(): String = UNICODE_REGEX.replace(this) { match ->
        match.groupValues[1].toInt(16).toChar().toString()
    }.replace("\\/", "/").replace('\u00a0', ' ').trim()

    private fun String.cleanTitle(): String = trim()
        .substringBefore(" смотреть онлайн")
        .replace(TITLE_TAIL_REGEX, "")
        .trim()

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun String.origin(): String = toHttpUrl().let { url -> "${url.scheme}://${url.host}" }

    private fun SAnime.postId(): String? = POST_ID_REGEX.find(url)?.groupValues?.get(1)

    private fun String.postId(): String? = POST_ID_REGEX.find(this)?.groupValues?.get(1)

    private fun PlayerData.cinemarEmbeds(): List<String> = (
        tabs.filter { it.balancer.equals("cinemar", ignoreCase = true) }.map { it.iframe_url } +
            mirrors.filter { it.provider == 0 }.map { it.iframe_url }
        )
        .filter { it.startsWith("http") }
        .distinct()

    companion object {
        private const val PER_PAGE = 10

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("2160p", "1080p", "720p", "480p", "360p")

        private const val GATEWAY_URL = "https://api.kinogo-10.biz/gateway"
        private const val API_HOST = "api.kinogo-10.biz"
        private const val POSTER_HOST = "https://kinogo-10.biz"
        private const val APP_SIGNATURE =
            "3245f97f56a9cff39b6be6a6729becfc127cbcd93d8e1ddcea0cf429f387379d"
        private const val PLAYLIST_MARKER = "W3si"
        private const val LOCATION_SEPARATOR = "#loc="
        private const val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private const val POSTER_SELECTOR = ".shortstory__poster img, .m_poster img, .fullstory img"

        private val DETAIL_KEYS = listOf("Вышел в", "Сняли в", "Длительность", "Лучшее качество")

        private val FILE_REGEX = Regex(""""file":"([^"]+)"""")
        private val ENTRY_REGEX = Regex(""""title":"(.*?)","title2":"(.*?)","data":"([^"]+)"""")
        private val LOCATION_REGEX = Regex("""(\d+)\s*сезон\s*(\d+)\s*серия""")
        private val UNICODE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val POST_ID_REGEX = Regex("""^/(\d+)(?:-|$)""")

        // Some listings carry a typo'd or ranged year, e.g. "(20265)" / "(2024-2025)".
        private val TITLE_TAIL_REGEX = Regex("""\s*\(\d{4}\S*\)\s*$""")
    }
}

@Serializable
private class PostResponse(val data: PostData = PostData())

@Serializable
private class PostData(
    val id: String = "",
    val title: String = "",
    val short_story: String = "",
    val xfields: Map<String, String> = emptyMap(),
    val category: List<PostCategory> = emptyList(),
)

@Serializable
private class PostCategory(val name: String = "")

@Serializable
private class PlayerResponse(val data: PlayerData = PlayerData())

@Serializable
private class PlayerData(
    val has_player: Boolean = false,
    val tabs: List<PlayerTab> = emptyList(),
    val mirrors: List<PlayerMirror> = emptyList(),
)

@Serializable
private class PlayerTab(
    val id: Int = 0,
    val title: String = "",
    val balancer: String = "",
    val iframe_url: String = "",
)

@Serializable
private class EntryDto(
    val title: String = "",
    val title2: String = "",
    val data: String = "",
    val file: String = "",
)

@Serializable
private class PlayerMirror(
    val provider: Int = -1,
    val iframe_url: String = "",
)
