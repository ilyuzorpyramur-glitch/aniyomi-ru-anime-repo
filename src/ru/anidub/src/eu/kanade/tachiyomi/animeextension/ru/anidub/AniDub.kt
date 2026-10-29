package eu.kanade.tachiyomi.animeextension.ru.anidub

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * AniDub — anidub.vip (DLE, utf-8).
 *
 * Каталог:  /anime/page/N/            (все)
 * Онгоинги: /anime/anime_ongoing/page/N/
 * Поиск:    /?do=search&subaction=search&story=<query>
 * Фильтры:  /xfsearch/genre/<жанр>/, /xfsearch/year/<год>/
 * Плеер:    kodikplayer.com/seria/<id>/<hash>/720p (ссылки в span[data] на странице)
 */
class AniDub :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniDub"

    override val baseUrl = "https://anidub.vip"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val kodik by lazy { KodikResolver(client, headers, preferences) }

    override fun headersBuilder() = super.headersBuilder()
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/1/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        val inCatalog = document.select("div#dle-content a.th-in[href], div.th-item a.th-in[href]")
            .mapNotNull { it.absUrl("href").ifBlank { it.attr("href") } }
            .toSet()
        val popular = document.select("#owl-popular a.th-in[href]")
            .mapNotNull { carouselItem(it, inCatalog) }
            .distinctBy { it.url }
        if (popular.isNotEmpty()) return AnimesPage(popular, false)
        return listParse(response)
    }

    private fun carouselItem(link: Element, allowed: Set<String>): SAnime? {
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank() || !href.contains("/anime/")) return null
        if (allowed.isNotEmpty() && href !in allowed) return null
        val title = link.selectFirst(".th-title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        return SAnime.create().apply {
            url = href.substringAfter(baseUrl)
            this.title = title
            thumbnail_url = link.selectFirst("img")?.let { imageUrl(it) }
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(searchUrl(query, filters), headers)

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = AniDubFilters.FILTER_LIST

    private fun searchUrl(query: String, filters: AnimeFilterList): String {
        val params = AniDubFilters.getSearchParameters(filters)
        if (query.isNotBlank()) {
            return "$baseUrl/?do=search&subaction=search&story=${query.encoded()}"
        }
        params.genres.firstOrNull()?.let { return "$baseUrl/xfsearch/genre/${it.encoded()}/" }
        params.years.firstOrNull()?.let { return "$baseUrl/xfsearch/year/$it/" }
        return popularAnimeRequest(1).url.toString()
    }

    // ============================ List parsing ============================

    private fun listParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        document.select("#owl-popular").remove()
        val cards = document.select("div.th-item a.th-in[href], a[href\$=.html]:has(.th-title)")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/anime/")) return@mapNotNull null
                val slug = href.substringAfterLast('/')
                if (EPISODE_PAGE_REGEX.containsMatchIn(slug)) return@mapNotNull null
                val title = link.selectFirst(".th-title")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    url = href.substringAfter(baseUrl)
                    this.title = title
                    thumbnail_url = (
                        link.selectFirst("img")
                            ?: link.parents().firstOrNull { it.hasClass("th-item") }?.selectFirst("img")
                        )
                        ?.let { imageUrl(it) }
                }
            }
            .distinctBy { it.url }

        val hasNext = cards.size >= PAGE_SIZE
        return AnimesPage(cards, cards.isNotEmpty() && hasNext)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.replace(Regex("""\s*\[\d+ из \d+]"""), "")?.trim().orEmpty()
            thumbnail_url = document.selectFirst("div.fposter img")?.let { imageUrl(it) }
                ?: document.selectFirst("img[itemprop=image]")?.let { imageUrl(it) }
            genre = document.select("ul.flist a[href*=/xfsearch/genre/]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()
            description = document.selectFirst("div.fdesc")?.let { block ->
                val copy = block.clone()
                copy.select("script, style, .text_spoiler").remove()
                copy.text().trim()
            }.orEmpty()
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        val tabs = document.select("span[data]")
            .mapNotNull { span ->
                val data = span.attr("data").trim()
                if (!data.contains("kodikplayer.com")) return@mapNotNull null
                val url = if (data.startsWith("//")) "https:$data" else data
                val label = span.text().trim()
                label to url
            }
            .distinctBy { it.second }

        return tabs.mapIndexed { index, (label, url) ->
            SEpisode.create().apply {
                this.url = url
                name = label.ifBlank { "Серия ${index + 1}" }
                episode_number = EPISODE_NUMBER_REGEX.find(label)?.value?.toFloatOrNull() ?: (index + 1f)
                scanlator = "AniDub"
            }
        }.sortedByDescending { it.episode_number }
    }

    // =============================== Video ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        kodik.videos(episode.url)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = listOf("Авто (720p)" to "720", "480p" to "480", "360p" to "360")
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Качество по умолчанию"
            entries = qualities.map { it.first }.toTypedArray()
            entryValues = qualities.map { it.second }.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private fun imageUrl(element: Element): String {
        val raw = element.attr("data-src").ifBlank {
            element.attr("data-original").ifBlank { element.attr("src") }
        }
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> baseUrl + raw
            else -> raw
        }
    }

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private const val PAGE_SIZE = 10

        private val EPISODE_PAGE_REGEX = Regex("""-\d+-iz-\d+\.html$""")
        private val EPISODE_NUMBER_REGEX = Regex("""\d+""")
    }
}
