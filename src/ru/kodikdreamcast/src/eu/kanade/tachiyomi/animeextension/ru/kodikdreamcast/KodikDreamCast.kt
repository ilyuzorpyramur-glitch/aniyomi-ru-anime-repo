package eu.kanade.tachiyomi.animeextension.ru.kodikdreamcast

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import android.util.Base64

/**
 * Dream Cast — официальный сайт озвучки.
 *
 * Каталог и поиск: POST /home (pageNumber, pageSize, search) → JSON {count, releases}.
 * Страница релиза: HTML + inline `new Playerjs("#2<base64>")`; внутри base64-блока
 * массив `file[]` с сериями, где на каждую серию есть DASH и HLS (master.m3u8).
 *
 * Токены не используются: всё берётся с сайта. Сайт доступен из РФ; из ЕС/США
 * отдаёт 404 (гео-блок), поэтому источник рассчитан на российские сети.
 */
class KodikDreamCast : AnimeHttpLegacySource() {

    override val name = "Dream Cast"

    override val baseUrl = "https://dreamerscast.com"

    override val lang = "ru"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )
        .set("Referer", "$baseUrl/home")
        .set("Origin", baseUrl)
        .set("X-Requested-With", "XMLHttpRequest")

    private val videoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .removeHeader("X-Requested-With")
            .build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page, "")

    override fun popularAnimeParse(response: Response): AnimesPage = catalogParse(response)

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, "")

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        catalogRequest(page, query)

    override fun searchAnimeParse(response: Response): AnimesPage = catalogParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    private fun catalogRequest(page: Int, query: String): Request {
        val body = FormBody.Builder()
            .add("pageNumber", page.toString())
            .add("pageSize", PAGE_SIZE.toString())
            .apply { if (query.isNotBlank()) add("search", query) }
            .build()
        return POST("$baseUrl/home", headers, body)
    }

    private fun catalogParse(response: Response): AnimesPage {
        val data = response.parseAs<HomeResponse>()
        val animes = data.releases.map { release ->
            SAnime.create().apply {
                url = release.url
                title = release.russian.ifBlank { release.original }
                thumbnail_url = release.image.toAbsolute()
                genre = release.genres
                description = buildString {
                    if (release.original.isNotBlank()) appendLine("Оригинал: ${release.original}")
                    if (release.dateissue.isNotBlank()) appendLine("Год: ${release.dateissue}")
                    if (release.type.isNotBlank()) appendLine("Тип: ${release.type}")
                    if (release.studio.isNotBlank()) appendLine("Студия: ${release.studio}")
                    if (release.dubbers.isNotBlank()) appendLine("Озвучивание: ${release.dubbers}")
                    if (release.series.isNotBlank()) appendLine("Эпизодов: ${release.currentSeries} из ${release.series}")
                }.trim()
            }
        }
        return AnimesPage(animes, animes.size >= PAGE_SIZE)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.bodyString().let(Jsoup::parse)
        val release = parseRelease(document)
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = release.title
            thumbnail_url = release.thumbnail
            description = release.description
            genre = release.genre
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.bodyString()
        val episodes = parsePlayerEpisodes(html)
        return episodes.mapIndexed { index, item ->
            SEpisode.create().apply {
                url = item.url
                episode_number = (index + 1).toFloat()
                name = item.title.ifBlank { "Серия ${index + 1}" }
                scanlator = "Dream Cast"
            }
        }
    }

    // =============================== Video ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val master = client.newCall(GET(episode.url, videoHeaders)).awaitSuccess().bodyString()
        val variants = parseMasterPlaylist(master, episode.url)
        if (variants.isNotEmpty()) return variants
        return listOf(Video(episode.url, "HLS", episode.url, headers = videoHeaders))
    }

    // ============================== Parsing ===============================

    private data class ReleaseInfo(
        val title: String,
        val thumbnail: String?,
        val description: String?,
        val genre: String?,
    )

    private fun parseRelease(document: org.jsoup.nodes.Document): ReleaseInfo {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim().orEmpty()

        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.toAbsolute()

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() && it.length > 40 }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val genre = document.select("a[href*=/home/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
            .ifBlank { null }

        return ReleaseInfo(title, thumbnail, description, genre)
    }

    private data class ParsedEpisode(val title: String, val url: String)

    /**
     * Playerjs config is a chain of independent base64 chunks (padding in the middle),
     * so decode chunk by chunk and concatenate the bytes.
     */
    private fun parsePlayerEpisodes(html: String): List<ParsedEpisode> {
        val encoded = PLAYERJS_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val bytes = decodeChunks(encoded)
        if (bytes.isEmpty()) return emptyList()

        val ascii = String(bytes, StandardCharsets.ISO_8859_1)
        val titles = TITLE_REGEX.findAll(ascii)
            .map { decodeMojibake(it.groupValues[1]) }
            .toList()

        val hlsUrls = HLS_REGEX.findAll(ascii)
            .map { it.value }
            .distinct()
            .toList()
            .ifEmpty {
                DASH_REGEX.findAll(ascii)
                    .map { it.value.replace("/dash/", "/hls/") + HLS_SUFFIX }
                    .distinct()
                    .toList()
            }

        return hlsUrls.mapIndexed { index, url ->
            ParsedEpisode(titles.getOrNull(index).orEmpty(), url)
        }
    }

    private fun decodeChunks(encoded: String): ByteArray {
        val out = ByteArrayOutputStream()
        var start = 0
        var i = 0
        while (i < encoded.length) {
            if (encoded[i] == '=') {
                var j = i
                while (j < encoded.length && encoded[j] == '=') j++
                val chunk = encoded.substring(start, j)
                runCatching { out.write(Base64.decode(chunk, Base64.DEFAULT)) }
                start = j
                i = j
            } else {
                i++
            }
        }
        if (start < encoded.length) {
            runCatching { out.write(Base64.decode(encoded.substring(start), Base64.DEFAULT)) }
        }
        return out.toByteArray()
    }

    private fun decodeMojibake(value: String): String = runCatching {
        String(value.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
    }.getOrDefault(value)

    private fun parseMasterPlaylist(master: String, fallback: String): List<Video> {
        val lines = master.lines()
        val videos = mutableListOf<Video>()
        var pendingQuality: String? = null
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingQuality = RESOLUTION_REGEX.find(trimmed)
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.let { "${it}p" }
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingQuality != null -> {
                    videos += Video(trimmed, pendingQuality!!, trimmed, headers = videoHeaders)
                    pendingQuality = null
                }
            }
        }
        return videos.ifEmpty {
            if (master.startsWith("#EXTM3U")) listOf(Video(fallback, "HLS", fallback, headers = videoHeaders)) else emptyList()
        }
    }

    private fun String.toAbsolute(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        else -> this
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val HLS_SUFFIX = "_,1080,720,low,aac,.mp4.urlset/master.m3u8"
        private val PLAYERJS_REGEX = Regex("""new Playerjs\("#2([A-Za-z0-9+/=]+)"""")
        private val TITLE_REGEX = Regex(""""title":"([^"]{1,60})"""")
        private val HLS_REGEX = Regex("""https://play\.dreamerscast\.com/hls/[A-Za-z0-9\-]+/[A-Za-z0-9\-]+""")
        private val DASH_REGEX = Regex("""https://play\.dreamerscast\.com/dash/[A-Za-z0-9\-]+/[A-Za-z0-9\-]+""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
    }
}
