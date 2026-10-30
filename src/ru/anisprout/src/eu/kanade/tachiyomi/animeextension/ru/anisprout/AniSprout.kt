package eu.kanade.tachiyomi.animeextension.ru.anisprout

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AniSprout :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniSprout"

    override val baseUrl = "https://anisprout.com"

    override val lang = "ru"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api/"

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

    override fun popularAnimeRequest(page: Int): Request = listRequest("/top/", page)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest("/last/", page)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        if (query.isNotBlank()) return apiRequest("get_search_results", "string" to query)

        return listRequest(AniSproutFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (!response.request.url.toString().startsWith(apiUrl)) return listParse(response)

        val html = response.parseAs<ApiResponse>().data ?: return AnimesPage(emptyList(), false)
        val animes = Jsoup.parseBodyFragment(html, baseUrl)
            .select("p.card-title")
            .mapNotNull { it.toSAnimeFromSearch() }

        return AnimesPage(animes, false)
    }

    override fun getFilterList(): AnimeFilterList = AniSproutFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        fun meta(label: String): String? = document.select(".meta-block .meta-col")
            .firstOrNull { it.selectFirst("dt")?.text()?.trim()?.removeSuffix(":") == label }
            ?.selectFirst("dd")
            ?.text()
            ?.trim()

        val episodesInfo = meta("Эпизодов")

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = document.select("ul.tag-list .tag-link").joinToString { it.text().trim() }
            status = when {
                episodesInfo == null -> SAnime.UNKNOWN
                episodesInfo.substringBefore(" из ") == episodesInfo.substringAfter("из ").trim() -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }
            description = buildString {
                document.selectFirst(".expanded-area[itemprop=description]")?.text()?.trim()
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                document.select(".expand-list-content span")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { appendLine("Другие названия: ${it.joinToString(" / ")}") }
                listOf("Год", "Сезон", "Эпизодов", "Страна", "Озвучка").forEach { label ->
                    meta(label)?.let { appendLine("$label: $it") }
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()

        val animeId = document.selectFirst("title-player")?.attr("aid")
            ?: return emptyList()

        val buttons = document.select(".chapters-select-list .btn--select-video")
        if (buttons.isEmpty()) return emptyList()

        val isSingle = buttons.size == 1

        return buttons.mapIndexed { index, button ->
            val sid = button.attr("data-sid")
            val label = button.text().trim()

            SEpisode.create().apply {
                url = "$animeId|$sid"
                episode_number = NUMBER_REGEX.find(label)?.value?.toFloatOrNull()
                    ?: (index + 1).toFloat()
                name = when {
                    isSingle -> "Фильм"
                    label.isNotBlank() -> label
                    else -> "Эпизод ${index + 1}"
                }
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val animeId = episode.url.substringBefore('|')
        val sid = episode.url.substringAfter('|', "")
        if (sid.isEmpty()) return emptyList()

        val videos = mutableListOf<Video>()

        // The site's own player hands out direct HLS links — no decoding needed.
        runCatching { mainPlayerVideos(animeId, sid) }.getOrNull()?.let(videos::addAll)

        if (preferences.getBoolean(PREF_USE_KODIK_KEY, PREF_USE_KODIK_DEFAULT)) {
            runCatching { kodikPlayerVideos(animeId, sid) }.getOrNull()?.let(videos::addAll)
        }

        if (videos.isEmpty()) {
            throw Exception("Не удалось получить ссылки на видео для этой серии")
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private suspend fun mainPlayerVideos(animeId: String, sid: String): List<Video> {
        val payload = playerView(animeId, sid, "main") ?: return emptyList()

        // "[360p]https://host/..., [720p]https://host/..."
        return PLAYER_LINK_REGEX.findAll(payload).toList().flatMap { match ->
            val quality = match.groupValues[1]
            val url = match.groupValues[2].trim().trimEnd(',')

            if (url.contains(".m3u8")) {
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    videoNameGen = { "AniSprout - $it" },
                ).ifEmpty {
                    listOf(Video(url, "AniSprout - $quality", url, headers = videoHeaders()))
                }
            } else {
                listOf(Video(url, "AniSprout - $quality", url, headers = videoHeaders()))
            }
        }
    }

    private suspend fun kodikPlayerVideos(animeId: String, sid: String): List<Video> {
        val payload = playerView(animeId, sid, "kodik") ?: return emptyList()

        val iframe = Jsoup.parseBodyFragment(payload).selectFirst("iframe")?.attr("src")
            ?: return emptyList()
        val playerUrl = iframe.toAbsoluteUrl()

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
                    videoNameGen = { "Kodik - $it" },
                ).ifEmpty {
                    listOf(Video(playlistUrl, "Kodik - ${quality}p", playlistUrl))
                }
            }
    }

    private suspend fun playerView(animeId: String, sid: String, type: String): String? {
        val response = client.newCall(
            apiRequest(
                "get_player_view",
                "aid" to animeId,
                "type" to type,
                "sid" to sid,
            ),
        ).awaitSuccess().parseAs<ApiResponse>()

        if (!response.success) return null

        return response.data
            ?.takeIf { it.isNotBlank() && !it.contains("intro.mp4") }
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
            key = PREF_USE_KODIK_KEY,
            default = PREF_USE_KODIK_DEFAULT,
            title = "Добавлять ссылки из плеера Kodik",
            summary = "Резервный источник с другими озвучками. Замедляет загрузку списка видео.",
        )
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 1080

    // =============================== Utils ================================

    private fun listRequest(path: String, page: Int): Request {
        val resolved = path.ifBlank { DEFAULT_PATH }
        val builder = (baseUrl + resolved).toHttpUrl().newBuilder()

        // Only the paginated sections understand ?p=; the others always answer with the same list.
        if (resolved.isPaginated()) builder.addQueryParameter("p", page.toString())

        return GET(builder.build(), headers)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()

        // Scope to the catalog container: the page also carries a schedule popup,
        // a sidebar block and a "related" carousel built from the same card markup.
        val animes = document.select("div.title-list card-title.card")
            .mapNotNull { it.toSAnimeFromCard() }
            .distinctBy { it.url }

        val paginated = response.request.url.encodedPath.isPaginated()

        return AnimesPage(animes, paginated && animes.size >= PAGE_SIZE)
    }

    private fun String.isPaginated(): Boolean = SINGLE_PAGE_PATHS.none { startsWith(it) }

    private fun Element.toSAnimeFromCard(): SAnime? {
        val link = selectFirst("a.card-link") ?: return null
        val path = link.attr("href").takeIf { it.isNotBlank() } ?: return null

        return SAnime.create().apply {
            url = path
            title = link.text().unquote()
            thumbnail_url = selectFirst("img.card-image")?.absUrl("src")?.fullSizePoster()?.takeIf { it.isNotBlank() }
                ?: parent()?.selectFirst("img.card-image")?.absUrl("src")?.fullSizePoster()?.takeIf { it.isNotBlank() }
        }
    }

    private fun Element.toSAnimeFromSearch(): SAnime? {
        val link = selectFirst("a.card-link") ?: return null
        val path = link.attr("href").takeIf { it.isNotBlank() } ?: return null

        return SAnime.create().apply {
            url = path
            title = link.text().unquote()
            thumbnail_url = parent()?.parent()?.selectFirst("img.card-image")
                ?.absUrl("src")
                ?.fullSizePoster()
                ?.takeIf { it.isNotBlank() }
        }
    }

    /** Cards point at the 178x256 resize; dropping that segment yields the full poster. */
    private fun String.fullSizePoster(): String = RESIZE_SEGMENT.replace(this, "/")

    private fun apiRequest(action: String, vararg fields: Pair<String, String>): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", action)
            .apply { fields.forEach { (key, value) -> addFormDataPart(key, value) } }
            .build()

        return POST(apiUrl, headers, body)
    }

    private fun videoHeaders(): Headers = Headers.Builder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .build()

    private fun String.unquote(): String = replace('\u00AB', ' ')
        .replace('\u00BB', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> "https://$this"
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        /** Card images are served through a `/<width>-<height>/` resize segment. */
        private val RESIZE_SEGMENT = Regex("/\\d{2,4}-\\d{2,4}/")

        private const val DEFAULT_PATH = "/top/"
        private const val PAGE_SIZE = 48
        private val SINGLE_PAGE_PATHS = listOf("/top/", "/last/", "/announcement/")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_USE_KODIK_KEY = "pref_use_kodik"
        private const val PREF_USE_KODIK_DEFAULT = false

        private val PLAYER_LINK_REGEX = Regex("""\[(\d+p)\]\s*(\S+)""")
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'(.*?)'""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val NUMBER_REGEX = Regex("""\d+(\.\d+)?""")
    }
}
