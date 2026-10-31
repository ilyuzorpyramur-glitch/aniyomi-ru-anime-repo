package eu.kanade.tachiyomi.animeextension.ru.shiza

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Shiza Project (shiza-project.com) — сгенерировано extension-forge.
 *
 * Каталог и детали разбираются из HTML, плеер берётся через relay сайта
 * и передаётся в KodikExtractor.
 */
class ShizaProject :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Shiza Project"

    override val baseUrl = "https://shiza-project.com"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val kodik by lazy { KodikExtractor(client, headers, baseUrl, preferences) }

    /** id тайтла на сайте, по которому relay отдаёт плеер. */
    private var currentAnimeId: String = ""

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // =============================== Каталог ===============================

    private fun pageUrl(path: String, page: Int): String {
        if (page <= 1) return "$baseUrl$path"
        return "$baseUrl$path" + PAGE_SUFFIX.replace("{page}", page.toString())
    }

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl(CATALOG_PATH, page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(LATEST_PATH, page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList): Request {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl" + SEARCH_PATH.replace("{query}", encoded)
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val html = response.bodyString()
        val linkFilter = Regex(LINK_FILTER)
        val animes = ANCHOR_REGEX.findAll(html)
            .mapNotNull { match ->
                val href = match.groupValues[1]
                if (!linkFilter.containsMatchIn(href)) return@mapNotNull null
                val path = href.substringAfter(baseUrl, href)
                val body = match.groupValues[2]
                SAnime.create().apply {
                    url = path
                    title = cleanTitle(textOf(body)).ifBlank { altOf(body) }
                    thumbnail_url = imageOf(body)?.let(::absoluteUrl)
                    initialized = false
                }
            }
            .filter { it.title.isNotBlank() && !it.title.equals("Подробнее", true) }
            .distinctBy { it.url }
            .toList()

        val nextPage = html.contains("${PAGE_MARKER}${response.request.url.toString().let { pageOf(it) + 1 }}")
        return AnimesPage(animes, nextPage)
    }

    private fun pageOf(url: String): Int = Regex("""(?:page/|page=|/)(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun textOf(fragment: String): String = Jsoup.parse(fragment).text()

    private fun altOf(fragment: String): String = Regex("""alt="([^"]*)"""").find(fragment)?.groupValues?.get(1).orEmpty()

    private fun imageOf(fragment: String): String? = Regex("""<img[^>]*src="([^"]+)"""").find(fragment)?.groupValues?.get(1)

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s*смотреть онлайн.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+аниме$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> "$baseUrl/$raw"
    }

    // =============================== Детали ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.bodyString()
        val doc = Jsoup.parse(html, baseUrl)

        currentAnimeId = Regex("""data-player-anime-id="(\d+)"""").find(html)?.groupValues?.get(1).orEmpty()

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = cleanTitle(doc.selectFirst(DETAILS_TITLE)?.text().orEmpty()).ifBlank { "Без названия" }
            thumbnail_url = (metaOf(doc, "og:image") ?: doc.selectFirst(POSTER_SELECTOR)?.absUrl("src"))
                ?.let(::absoluteUrl)
            description = metaOf(doc, "og:description")
                ?: doc.selectFirst(DESC_SELECTOR)?.text()?.takeIf { it.length > 80 }
            genre = doc.select(GENRE_SELECTOR).map { it.text().trim() }
                .filter { it.isNotEmpty() && it.length < 24 }
                .distinct()
                .take(12)
                .joinToString(", ")
                .ifBlank { null }
            initialized = true
        }
    }

    private fun metaOf(doc: Document, property: String): String? {
        val byProperty = doc.selectFirst("""meta[property="$property"]""")?.attr("content")
        if (!byProperty.isNullOrBlank()) return byProperty
        return doc.selectFirst("""meta[name="$property"]""")?.attr("content")?.takeIf { it.isNotBlank() }
    }

    // =============================== Серии ================================

    override fun episodeListRequest(anime: SAnime): Request {
        if (PLAYER_MODE == "iframe") return GET(baseUrl + anime.url, headers)
        val id = currentAnimeId.ifBlank { relayId(anime.url) }
        return GET(relayUrl(id, 0), headers)
    }

    private fun relayUrl(id: String, slot: Int): String = baseUrl + RELAY_URL.replace("{id}", id).replace("{slot}", slot.toString())

    private fun relayId(path: String): String = runCatching {
        val html = client.newCall(GET(baseUrl + path, headers)).execute().use { it.bodyString() }
        Regex("""data-player-anime-id="(\d+)"""").find(html)?.groupValues?.get(1).orEmpty()
    }.getOrDefault("")

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (PLAYER_MODE == "iframe") {
            // Плеер лежит прямо на странице тайтла: серии отдельными ссылками
            // такие сайты не отдают, поэтому показываем одну серию.
            val html = runCatching { response.bodyString() }.getOrNull() ?: return emptyList()
            val iframe = IFRAME_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
            return listOf(
                SEpisode.create().apply {
                    url = absoluteUrl(iframe)
                    name = "Серия 1"
                    episode_number = 1f
                },
            )
        }

        // Сайт выдаёт разным сессиям разные плееры и раскладывает их по слотам,
        // поэтому перебираем слоты и берём Kodik, если он вообще предложен.
        val players = mutableListOf<String>()
        for (slot in 0 until MAX_SLOTS) {
            val src = playerAt(currentAnimeId, slot) ?: continue
            players += src
            if (src.contains("kodik", ignoreCase = true)) break
        }

        val kodikPlayer = players.firstOrNull { it.contains("kodik", ignoreCase = true) }
        if (kodikPlayer == null) {
            // Другой плеер (Alloha и подобные): отдаём одну серию, видео достанет getVideoList.
            val other = players.firstOrNull() ?: return emptyList()
            Log.e("Amedia", "не-Kodik плеер: " + other.take(80))
            return listOf(
                SEpisode.create().apply {
                    url = other
                    name = "Серия 1"
                    episode_number = 1f
                },
            )
        }

        val page = runCatching {
            client.newCall(GET(kodikPlayer, playerHeaders())).execute()
                .use { it.bodyString() }
        }.onFailure { Log.e("Amedia", "player page error", it) }.getOrNull() ?: return emptyList()

        Log.e("Amedia", "page=" + page.length + " options=" + EPISODE_OPTION_REGEX.findAll(page).count())

        return EPISODE_OPTION_REGEX.findAll(page).mapNotNull { match ->
            val attrs = attrsOf(match.value)
            val number = attrs["value"]?.toFloatOrNull() ?: return@mapNotNull null
            val title = attrs["data-title"].orEmpty()
            SEpisode.create().apply {
                url = "$kodikPlayer#e=" + number.toEpisodeNumber()
                name = title.ifBlank { "Серия " + number.toEpisodeNumber() }
                episode_number = number
            }
        }
            .distinctBy { it.episode_number }
            .sortedByDescending { it.episode_number }
            .toList()
    }

    private fun Float.toEpisodeNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun playerAt(id: String, slot: Int): String? {
        val body = runCatching {
            client.newCall(GET(relayUrl(id, slot), headers)).execute().use { it.bodyString() }
        }.getOrNull() ?: return null
        return runCatching { JSONObject(body).optJSONObject("data")?.optString("src") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** Kodik отдаёт разметку только браузерному User-Agent. */
    private fun playerHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Origin", "https://kodikplayer.com")
        .build()

    private fun attrsOf(tag: String): Map<String, String> = Regex("""([a-z-]+)="([^"]*)"""").findAll(tag)
        .associate { it.groupValues[1] to it.groupValues[2] }

    // =============================== Видео ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val number = Regex("""#e=(\d+)""").find(episode.url)?.groupValues?.get(1)?.toFloatOrNull()
            ?: episode.episode_number
        val player = episode.url.substringBefore("#")
        val videos = if (player.contains("kodik", ignoreCase = true)) {
            kodik.videos(player, 1, number.toInt())
        } else {
            directVideos(player)
        }
        if (videos.isEmpty()) throw Exception("Плеер не вернул видео")
        videos
    }

    /** Не-Kodik плееры (Alloha и подобные) обычно кладут ссылку прямо в разметку. */
    private fun directVideos(player: String): List<Video> = runCatching {
        val page = client.newCall(GET(player, playerHeaders())).execute().use { it.bodyString() }
        val decoded = ATOB_REGEX.find(page)?.groupValues?.get(1)?.let(::decodePlaylist).orEmpty()
        val plain = Regex("""https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*""")
            .findAll(page)
            .map { it.value.replace("""\/""", "/") }
            .toList()
        val links = (decoded + plain.mapIndexed { index, url ->
            Video(url, "Видео " + (index + 1), url, headers = playerHeaders())
        })
            .distinctBy { it.videoUrl }
        Log.e("Amedia", "direct links=" + links.size)
        links
    }.getOrElse {
        Log.e("Amedia", "direct error", it)
        emptyList()
    }

    /** intrdb и подобные прячут список качеств в base64: «[480p]url,[720p]url». */
    private fun decodePlaylist(encoded: String): List<Video> = runCatching {
        val text = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        text.split(",").mapNotNull { part ->
            val match = PLAYLIST_ITEM_REGEX.find(part.trim()) ?: return@mapNotNull null
            val quality = match.groupValues[1]
            val url = match.groupValues[2].replace("""\/""", "/")
            Video(url, quality, url, headers = playerHeaders())
        }
    }.getOrDefault(emptyList())

    // ============================== Настройки =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = KodikExtractor.PREF_QUALITY
            title = "Качество видео"
            entries = arrayOf("Максимальное", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("max", "1080", "720", "480", "360")
            summary = "%s"
            setDefaultValue("max")
        }.let(screen::addPreference)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val CATALOG_PATH = "/"
        private const val LATEST_PATH = "/"
        private const val SEARCH_PATH = "/search/?q={query}"
        private const val PAGE_SUFFIX = "page/{page}/"
        private const val PAGE_MARKER = "page/"
        private const val LINK_FILTER = """/releases/[^"']+"""

        private const val DETAILS_TITLE = "h1"
        private const val POSTER_SELECTOR = "img"
        private const val DESC_SELECTOR = "div"
        private const val GENRE_SELECTOR = "a[href*='/genre/']"

        private const val RELAY_URL = "/engine/ajax/controller.php?mod=player&id={id}&slot={slot}"
        private const val MAX_SLOTS = 4
        private const val PLAYER_MODE = "iframe"
        private val IFRAME_REGEX = Regex("""<iframe[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val ATOB_REGEX = Regex("""file:\s*atob\("([^"]+)"\)""")
        private val PLAYLIST_ITEM_REGEX = Regex("""\[([^\]]+)](https?://\S+)""")

        private val ANCHOR_REGEX = Regex(
            """<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val EPISODE_OPTION_REGEX = Regex("""<option[^>]*data-hash="[^"]+"[^>]*>""", RegexOption.IGNORE_CASE)
    }
}
