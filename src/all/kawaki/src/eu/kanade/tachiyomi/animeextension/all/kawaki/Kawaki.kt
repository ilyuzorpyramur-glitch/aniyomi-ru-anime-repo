package eu.kanade.tachiyomi.animeextension.all.kawaki

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class Kawaki :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Kawaki"

    override val baseUrl = "https://kawaki.ru"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(catalogUrl(page, null), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    // Сортировка по новизне задаётся параметром sort, порядок отличается от дефолтного каталога.
    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, "NEW"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) {
            val url = "$baseUrl/catalog".toHttpUrl().newBuilder()
                .apply {
                    KawakiFilters.genre(filters).takeIf { it.isNotEmpty() }?.let { addQueryParameter("genre", it) }
                    KawakiFilters.year(filters).takeIf { it.isNotEmpty() }?.let { addQueryParameter("year", it) }
                    KawakiFilters.status(filters).takeIf { it.isNotEmpty() }?.let { addQueryParameter("status", it) }
                    if (page > 1) addQueryParameter("page", page.toString())
                }
                .build()

            return GET(url, headers)
        }

        val url = "$baseUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = KawakiFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.parseDocument()

        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { doc.title().substringBefore("—").trim() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = doc.select("a[href*=/catalog?genre=]").map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(12)
                .joinToString(", ")
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url + "/watch/1", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val payload = response.bodyText().unescapeRsc()
        val animePath = response.request.url.encodedPath.substringBeforeLast("/watch/")
        val episodes = parseJsonArray(payload, EPISODES_KEY)

        val parsed = (0 until episodes.length()).mapNotNull { index ->
            val item = episodes.optJSONObject(index) ?: return@mapNotNull null
            val number = item.optInt("number", index + 1)
            if (number <= 0) return@mapNotNull null

            SEpisode.create().apply {
                url = "$animePath/watch/$number"
                name = item.optString("title").takeIf { it.isNotBlank() } ?: "Серия $number"
                episode_number = number.toFloat()
            }
        }.distinctBy { it.url }

        if (parsed.isNotEmpty()) return parsed.sortedBy { it.episode_number }

        return listOf(
            SEpisode.create().apply {
                url = "$animePath/watch/1"
                name = "Серия 1"
                episode_number = 1f
            },
        )
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val pageUrl = baseUrl + episode.url
        val page = client.newCall(GET(pageUrl, headers)).awaitSuccess().bodyText()

        if ("Just a moment" in page || "Cloudflare" in page) {
            throw Exception("Сайт отдал защиту Cloudflare вместо страницы — попробуйте позже")
        }

        val videoHeaders = headers.newBuilder().set("Referer", pageUrl).build()
        val payload = page.unescapeRsc()
        val videos = mutableListOf<Video>()

        val variants = parseJsonArray(payload, VARIANTS_KEY)
        for (index in 0 until variants.length()) {
            val variant = variants.optJSONObject(index) ?: continue
            val dub = variant.optString("label").takeIf { it.isNotBlank() }.orEmpty()
            val sources = variant.optJSONArray("sources") ?: continue

            for (sourceIndex in 0 until sources.length()) {
                val source = sources.optJSONObject(sourceIndex) ?: continue
                val link = source.optString("url").takeIf { it.contains(".m3u8") || it.contains(".mp4") }
                    ?: continue
                val quality = source.optString("label").takeIf { it.isNotBlank() } ?: "Видео"
                val name = listOf(dub, quality).filter { it.isNotEmpty() }.joinToString(" · ")

                videos += Video(link, name, link, headers = videoHeaders)
            }
        }

        // Часть озвучек в разметке ссылается на массив по указателю RSC — добираем оставшиеся
        // дорожки регуляркой, чтобы плеер не остался пустым.
        if (videos.isEmpty()) {
            FALLBACK_URL_REGEX.findAll(payload).forEach { match ->
                val link = match.groupValues[1]
                videos += Video(link, link.qualityLabel(), link, headers = videoHeaders)
            }
        }

        val unique = videos.distinctBy { it.videoUrl }
        if (unique.isEmpty()) throw Exception("Ссылки на видео не найдены")

        val preferred = preferredQuality
        return unique.sortedWith(
            compareByDescending<Video> { it.videoTitle.qualityValue() == preferred }
                .thenByDescending { it.videoTitle.qualityValue() },
        )
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

    private fun catalogUrl(page: Int, sort: String?): String {
        val url = "$baseUrl/catalog".toHttpUrl().newBuilder()
        if (page > 1) url.addQueryParameter("page", page.toString())
        if (!sort.isNullOrBlank()) url.addQueryParameter("sort", sort)
        return url.build().toString()
    }

    private fun listParse(response: Response): AnimesPage {
        val doc = response.parseDocument()
        val animes = doc.select("a[href^=/anime/]")
            .mapNotNull { it.attr("href").takeIf { href -> CARD_REGEX.matches(href) } }
            .distinct()
            .mapNotNull { href ->
                val card = doc.selectFirst("a[href=$href]") ?: return@mapNotNull null
                SAnime.create().apply {
                    url = href
                    title = card.selectFirst("h3")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: card.selectFirst("img")?.attr("alt").orEmpty()
                    thumbnail_url = card.selectFirst("img")?.let { img ->
                        img.attr("src").ifBlank { img.attr("data-src") }
                    }?.toAbsoluteUrl()
                }
            }

        return AnimesPage(animes, animes.size >= ITEMS_PER_PAGE)
    }

    /** Данные страницы лежат в RSC-потоке Next.js с экранированными кавычками. */
    private fun String.unescapeRsc(): String = replace("\\\"", "\"").replace("\\u0026", "&").replace("\\n", " ")

    private fun parseJsonArray(payload: String, key: String): JSONArray {
        val anchor = payload.indexOf("\"$key\":[")
        if (anchor < 0) return JSONArray()

        val start = payload.indexOf('[', anchor)
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until payload.length) {
            val char = payload[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '[' -> depth++
                char == ']' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONArray(payload.substring(start, index + 1)) }.getOrElse { JSONArray() }
                    }
                }
            }
        }

        return JSONArray()
    }

    private fun Response.parseDocument(): Document = Jsoup.parse(bodyText(), baseUrl)

    private fun Response.bodyText(): String {
        val bytes = peekBody(Long.MAX_VALUE).bytes()
        return String(bytes, Charsets.UTF_8)
    }

    private fun String.toAbsoluteUrl(): String = when {
        isEmpty() -> ""
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        else -> baseUrl + this
    }

    private fun String.qualityLabel(): String = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.let { "${it}p" } ?: "Видео"

    private fun String.qualityValue(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val ITEMS_PER_PAGE = 48

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val EPISODES_KEY = "episodes"
        private const val VARIANTS_KEY = "variants"

        private val CARD_REGEX = Regex("""^/anime/\d+-[^/?#]+$""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
        private val FALLBACK_URL_REGEX = Regex("""(https://[^"]+?/\d{3,4}\.mp4:hls:manifest\.m3u8)""")
    }
}
