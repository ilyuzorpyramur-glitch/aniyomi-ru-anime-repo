package eu.kanade.tachiyomi.animeextension.all.domanime

import android.net.Uri
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
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    // Каталог сайта: обычная лента с пагинацией /page/N/.
    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl("/", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    // Главная собирает курируемые подборки — порядок отличается от ленты каталога.
    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl("/page/", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return GET(pageUrl("/", page), headers)

        // DLE отвечает 307 на POST и отдаёт готовую страницу /search/{query}/.
        val url = "$baseUrl/search/${query.trim().replace(' ', '+')}/"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response, singlePage = true)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

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
            genre = doc.select("a[href*=/xfields/], .pmovie__genres a").map { it.text().trim() }
                .filter { it.isNotBlank() && it.length < 30 }
                .distinct()
                .take(10)
                .joinToString(", ")
                .ifBlank { null }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.parseDocument()
        val kodik = doc.kodikPayload()

        if (kodik != null) {
            val translators = kodik.optJSONObject("translators")
            val translation = translators?.keys()?.asSequence()?.firstOrNull { key ->
                translators.optJSONObject(key)
                    ?.optJSONObject("seasons")
                    ?.optJSONObject("1")
                    ?.optString("link")
                    ?.isNotBlank() == true
            }

            val season = translation?.let { translators?.optJSONObject(it)?.optJSONObject("seasons")?.optJSONObject("1") }
            val link = season?.optString("link")?.toAbsoluteUrl()
            val numbers = season?.optJSONArray("list")

            if (translation != null && link != null && numbers != null && numbers.length() > 0) {
                return (0 until numbers.length()).mapNotNull { index ->
                    val number = numbers.optInt(index, 0).takeIf { it > 0 } ?: return@mapNotNull null
                    createEpisode(translation, link, number)
                }.sortedBy { it.episode_number }
            }

            if (translation != null && link != null) {
                return listOf(createEpisode(translation, link, 1))
            }
        }

        // Страницы без Kodik: единственный ролик на странице тайтла.
        return listOf(
            SEpisode.create().apply {
                url = response.request.url.encodedPath
                name = "Серия 1"
                episode_number = 1f
            },
        )
    }

    private fun createEpisode(translation: String, link: String, number: Int): SEpisode = SEpisode.create().apply {
        url = "kodik|$translation|$link|$number"
        name = "Серия $number"
        episode_number = number.toFloat()
        scanlator = translation
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videos = when {
            episode.url.startsWith("kodik|") -> kodikVideos(episode.url.split("|"))
            else -> emptyList()
        }

        if (videos.isEmpty()) throw Exception("Не удалось получить ссылки на видео для этой серии")

        val preferred = preferredQuality
        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferred }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private suspend fun kodikVideos(parts: List<String>): List<Video> {
        if (parts.size < 4) return emptyList()

        val translation = parts[1]
        val seasonUrl = parts[2]
        val number = parts[3]

        val page = client.newCall(GET(seasonUrl, headers)).awaitSuccess().bodyText()
        val option = Jsoup.parse(page).select(".serial-series-box option")
            .firstOrNull { it.attr("value").toDoubleOrNull()?.toInt() == number.toIntOrNull() }
            ?: return emptyList()

        val id = option.attr("data-id").takeIf { it.isNotBlank() } ?: return emptyList()
        val hash = option.attr("data-hash").takeIf { it.isNotBlank() } ?: return emptyList()
        val playerUrl = "https://kodikplayer.com/seria/$id/$hash/720p"

        val playerPage = client.newCall(GET(playerUrl, headers)).awaitSuccess().bodyText()
        val params = URL_PARAMS_REGEX.find(playerPage)?.groupValues?.get(1)?.parseAs<KodikUrlParams>()
            ?: return emptyList()
        if (params.d_sign.isEmpty() || params.pd.isEmpty()) return emptyList()

        val segments = playerUrl.toHttpUrl().pathSegments
        if (segments.size < 3) return emptyList()

        val form = FormBody.Builder()
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

        val apiHeaders = Headers.Builder()
            .set("Referer", "$baseUrl/")
            .set("Origin", "https://${params.pd}")
            .set("User-Agent", UA)
            .build()

        val response = client.newCall(
            POST("https://${params.pd}/ftor", apiHeaders, form),
        ).awaitSuccess().bodyText()

        val links = runCatching { response.parseAs<LinksDto>().links }.getOrNull() ?: return emptyList()

        return links.entries
            .sortedByDescending { it.key.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            .flatMap { (quality, sources) ->
                sources.mapNotNull { source ->
                    val link = source.src.takeIf { it.isNotBlank() && it.startsWith("http") }
                        ?: return@mapNotNull null
                    Video(link, "$translation · $quality", link, headers = apiHeaders)
                }
            }
            .distinctBy { it.videoUrl }
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

    @Serializable
    private data class LinksDto(val links: Map<String, List<LinkDto>> = emptyMap())

    @Serializable
    private data class LinkDto(val src: String = "", val type: String? = null)

    @Serializable
    private data class KodikUrlParams(
        val d: String = "",
        val d_sign: String = "",
        val pd: String = "",
        val pd_sign: String = "",
        val ref: String = "",
        val ref_sign: String = "",
    )

    /** Kodik-конфиг сайта лежит в инлайн-скрипте: var kodik = {". */
    private fun Document.kodikPayload(): JSONObject? {
        val script = select("script:containsData(var kodik)").firstOrNull()?.data()
            ?: return null
        val start = script.indexOf('{')
        val end = script.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        return runCatching { JSONObject(script.substring(start, end + 1)) }.getOrNull()
    }

    private fun pageUrl(path: String, page: Int): String = when {
        page <= 1 && path.endsWith("page/") -> "$baseUrl/page/1/"
        page <= 1 -> "$baseUrl${path.removePrefix("/").let { if (it.isEmpty()) "" else it }}"
        else -> "$baseUrl${path.removePrefix("/").removeSuffix("/")}/page/$page/"
    }

    private fun listParse(response: Response, singlePage: Boolean = false): AnimesPage {
        val doc = response.parseDocument()
        val animes = doc.select("div.popular-item a[href]")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        return AnimesPage(animes, !singlePage && animes.size >= ITEMS_PER_PAGE)
    }

    private fun Element.toSAnime(): SAnime? {
        val href = attr("href").takeIf { POST_REGEX.containsMatchIn(it) } ?: return null
        val card = closest("div.popular-item") ?: this

        return SAnime.create().apply {
            url = href.substringAfter(baseUrl)
            title = card.selectFirst(".popular-item__title, .poster__title, h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("img")?.attr("alt").orEmpty()
            thumbnail_url = card.selectFirst("img")?.attr("src")?.toAbsoluteUrl()
        }
    }

    private fun Response.parseDocument(): Document = Jsoup.parse(bodyText(), baseUrl)

    private fun Response.bodyText(): String {
        val bytes = peekBody(Long.MAX_VALUE).bytes()
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
        private const val ITEMS_PER_PAGE = 10

        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private val WINDOWS_1251 = java.nio.charset.Charset.forName("windows-1251")

        private val POST_REGEX = Regex("""/\d+-[^/]+\.html""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*(\{[^}]+\})""")
    }
}
