package eu.kanade.tachiyomi.animeextension.all.domanime

import android.util.Base64
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

class Domanime :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "ДомАниме"

    override val baseUrl = "https://domanime.ru"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl("/f/sort=rating/", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl("/f/sort=date/", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return GET(pageUrl(DomanimeFilters.buildPath(filters), page), headers)

        return GET(pageUrl("/search/${query.trim().replace(' ', '+')}/", page), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = DomanimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.parseDocument()

        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.substringBefore(" аниме")?.trim().orEmpty()
                .ifBlank { doc.title().substringBefore(":").trim() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            // Жанры лежат в списке характеристик: <li><span>Жанр:</span> боевик, фэнтези</li>
            genre = doc.select("ul.inner-page__list > li")
                .firstOrNull { it.selectFirst("span")?.text()?.startsWith("Жанр") == true }
                ?.text()
                ?.substringAfter(":", "")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.take(10)
                ?.joinToString(", ")
                ?.ifBlank { null }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.parseDocument()
        val translators = doc.kodikPayload()?.optJSONObject("translators")
        val translator = translators?.keys()?.asSequence()?.firstOrNull()

        if (translator != null) {
            // seasons приходит и массивом, и объектом с ключами "1", "2", ...
            val seasons = translators.optJSONObject(translator)?.opt("seasons").toSeasonList()
            val episodes = mutableListOf<SEpisode>()
            var number = 0

            seasons.forEach { season ->
                val link = season.optString("link").toAbsoluteUrl().takeIf { it.startsWith("http") }
                    ?: return@forEach
                // list хранит номера серий внутри сезона, поэтому нумеруем подряд:
                // иначе серии разных сезонов получают одинаковые номера.
                season.optJSONArray("list").toNumbers().forEach { local ->
                    number += 1
                    episodes += SEpisode.create().apply {
                        url = "kodik|$translator|$link|$local"
                        episode_number = number.toFloat()
                        this.name = "Серия $number"
                        scanlator = translator
                    }
                }
            }

            if (episodes.isNotEmpty()) return episodes
        }

        return listOf(
            SEpisode.create().apply {
                url = "kodik|||1"
                name = "Серия 1"
                episode_number = 1f
            },
        )
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("|")
        val videos = if (parts.size >= 4) kodikVideos(parts[1], parts[2], parts[3]) else emptyList()

        if (videos.isEmpty()) throw Exception("Источник не отдал ссылки для этой серии")

        val preferred = preferredQuality
        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferred }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private suspend fun kodikVideos(translation: String, seasonUrl: String, number: String): List<Video> {
        if (seasonUrl.isEmpty()) return emptyList()

        val seasonPage = client.newCall(GET(seasonUrl, headers)).awaitSuccess().bodyText()
        val option = Jsoup.parse(seasonPage).select(".serial-series-box option")
            .firstOrNull { it.attr("value").toIntOrNull() == number.toIntOrNull() }
            ?: return emptyList()

        val id = option.attr("data-id").takeIf { it.isNotBlank() } ?: return emptyList()
        val hash = option.attr("data-hash").takeIf { it.isNotBlank() } ?: return emptyList()
        val playerUrl = "https://kodikplayer.com/seria/$id/$hash/720p"
        val playerPage = client.newCall(GET(playerUrl, headers)).awaitSuccess().bodyText()

        val params = URL_PARAMS_REGEX.find(playerPage)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyList()
        if (params.optString("pd").isEmpty()) return emptyList()

        val host = params.optString("pd")
        val form = FormBody.Builder()
            .add("d", params.optString("d"))
            .add("d_sign", params.optString("d_sign"))
            .add("pd", host)
            .add("pd_sign", params.optString("pd_sign"))
            // ref в urlParams лежит percent-encoded ("https%3A%2F%2Fdomanime.ru%2F").
            // FormBody кодирует повторно, поэтому сначала раскодируем сами.
            .add("ref", URLDecoder.decode(params.optString("ref"), "UTF-8"))
            .add("ref_sign", params.optString("ref_sign"))
            .add("type", "seria")
            .add("id", id)
            .add("hash", hash)
            .build()

        // Kodik сверяет Referer и Origin с доменом-источником. С собственным
        // Referer он отвечает 500 и текстом «данной страницы не существует».
        val apiHeaders = Headers.Builder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("User-Agent", UA)
            .build()

        val videoHeaders = Headers.Builder()
            .set("Referer", "https://$host/")
            .set("User-Agent", UA)
            .build()

        val body = client.newCall(POST("https://$host/ftor", apiHeaders, form)).awaitSuccess().bodyText()
        val links = runCatching { JSONObject(body).optJSONObject("links") }.getOrNull() ?: return emptyList()

        val result = mutableListOf<Video>()
        links.keys().asSequence().sortedByDescending { it.toIntOrNull() ?: 0 }.forEach { quality ->
            val array = links.optJSONArray(quality) ?: return@forEach
            for (index in 0 until array.length()) {
                val source = array.optJSONObject(index) ?: continue
                val link = decodeSource(source.optString("src")) ?: continue
                result += Video(link, "$translation · ${quality}p", link, headers = videoHeaders)
            }
        }

        return result.distinctBy { it.videoUrl }
    }

    /**
     * Kodik шифрует адрес сдвигом букв на 18 (Caesar) и base64. Результат — рабочий
     * плейлист вида {host}/s/m/{base64}/{hash}:{expiry}/{quality}.mp4:hls:manifest.m3u8,
     * его нельзя «разворачивать» в /useruploads/, иначе CDN отвечает 401.
     */
    private fun decodeSource(source: String): String? {
        if (source.startsWith("http")) return source

        val shifted = buildString {
            source.forEach { char ->
                if (char.isLetter()) {
                    val limit = if (char <= 'Z') 'Z'.code else 'z'.code
                    val code = char.code + 18
                    append((if (code <= limit) code else code - 26).toChar())
                } else {
                    append(char)
                }
            }
        }

        return runCatching { String(Base64.decode(shifted, Base64.DEFAULT)).trim() }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }
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

    private fun pageUrl(path: String, page: Int): String {
        if (page <= 1) return baseUrl + path
        return "$baseUrl${path.removeSuffix("/")}/page/$page/"
    }

    private fun listParse(response: Response): AnimesPage {
        val doc = response.parseDocument()
        val animes = doc.select("#dle-content div.movie-item")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        val current = PAGE_REGEX.find(response.request.url.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("#pagination a[href]").any { link ->
            (PAGE_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0) > current
        }

        return AnimesPage(animes, hasNext)
    }

    private fun Element.toSAnime(): SAnime? {
        val anchor = selectFirst("a.movie-item__link[href]") ?: return null
        val href = anchor.attr("href").takeIf { POST_REGEX.containsMatchIn(it) } ?: return null

        return SAnime.create().apply {
            url = href.substringAfter(baseUrl)
            title = selectFirst(".movie-item__title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("alt").orEmpty()
            thumbnail_url = selectFirst("img")?.attr("src")?.toAbsoluteUrl()
        }
    }

    private fun Document.kodikPayload(): JSONObject? {
        val script = select("script:containsData(var kodik)").firstOrNull()?.data() ?: return null
        val start = script.indexOf('{')
        if (start < 0) return null

        var depth = 0
        for (index in start until script.length) {
            when (script[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(script.substring(start, index + 1)) }.getOrNull()
                    }
                }
            }
        }

        return null
    }

    private fun Response.parseDocument(): Document = Jsoup.parse(bodyText(), baseUrl)

    private fun Any?.toSeasonList(): List<JSONObject> = when (this) {
        is JSONArray -> (0 until length()).mapNotNull { optJSONObject(it) }
        is JSONObject -> keys().asSequence()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { optJSONObject(it) }
            .toList()
        else -> emptyList()
    }

    private fun JSONArray?.toNumbers(): List<Int> = if (this == null) emptyList() else (0 until length()).mapNotNull { optInt(it).takeIf { n -> n > 0 } }

    private fun Response.bodyText(): String {
        val raw = peekBody(Long.MAX_VALUE).bytes()
        // Kodik на POST /ftor отдаёт gzip даже без Accept-Encoding. OkHttp такой
        // ответ не распаковывает сам, и JSON парсился из мусорных байтов.
        val gzipped = raw.size > 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()
        val bytes = if (gzipped) {
            runCatching { GZIPInputStream(raw.inputStream()).use { it.readBytes() } }.getOrDefault(raw)
        } else {
            raw
        }
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase()
        val charset = if ("charset=windows-1251" in head) WINDOWS_1251 else Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun String.toAbsoluteUrl(): String = when {
        isEmpty() -> ""
        startsWith("//") -> "https:$this"
        startsWith("http://") || startsWith("https://") -> this
        else -> baseUrl + this
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private val WINDOWS_1251 = java.nio.charset.Charset.forName("windows-1251")

        private val POST_REGEX = Regex("""/\d+-[^/]+\.html""")
        private val PAGE_REGEX = Regex("""/page/(\d+)/""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'(\{.*?\})'""", RegexOption.DOT_MATCHES_ALL)
    }
}
