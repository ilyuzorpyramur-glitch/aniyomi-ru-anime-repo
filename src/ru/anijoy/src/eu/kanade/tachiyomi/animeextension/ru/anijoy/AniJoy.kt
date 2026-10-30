package eu.kanade.tachiyomi.animeextension.ru.anijoy

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AniJoy (anijoy.ru) — сгенерировано extension-forge по карте JSON API.
 *
 * Каталог, детали и серии берутся из JSON, плеер — kodik.
 */
class AniJoy :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniJoy"

    override val baseUrl = "https://anijoy.ru"

    override val lang = "ru"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val kodik by lazy { KodikExtractor(client, headers, baseUrl, preferences) }

    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    /** Связка «адрес плеера → (id аниме, номер серии)» для выбора озвучки. */
    private val episodeMeta = mutableMapOf<String, Pair<String, Float>>()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // =============================== Каталог =============================== 
    private fun catalogUrl(page: Int, sort: String, query: String?): String {
        val builder = "$apiUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PER_PAGE.toString())
            .addQueryParameter("sort", sort)
        if (!query.isNullOrBlank()) builder.addQueryParameter("search", query)
        return builder.build().toString()
    }

    override fun popularAnimeRequest(page: Int): Request = GET(catalogUrl(page, DEFAULT_SORT, null), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, LATEST_SORT, null), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalog(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var sort = DEFAULT_SORT
        filters.forEach { filter ->
            if (filter is SortFilter) sort = filter.value
        }
        return GET(catalogUrl(page, sort, query), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    private fun parseCatalog(response: Response): AnimesPage {
        val root = runCatching { JSONObject(response.bodyString()) }.getOrNull()
            ?: return AnimesPage(emptyList(), false)
        val items = dig(root, ITEMS_PATH) as? JSONArray
            ?: return AnimesPage(emptyList(), false)

        val animes = (0 until items.length()).mapNotNull { index ->
            (items.opt(index) as? JSONObject)?.toSAnime()
        }

        val pagination = dig(root, PAGINATION_PATH) as? JSONObject
        val currentPage = pagination?.optInt(PAGE_FIELD, 1) ?: 1
        val totalPages = pagination?.optInt(PAGES_FIELD, 1) ?: 1
        return AnimesPage(animes, currentPage < totalPages)
    }

    private fun JSONObject.toSAnime(): SAnime = SAnime.create().apply {
        url = opt(ID_FIELD)?.toString().orEmpty()
        title = optString(TITLE_FIELD).ifBlank { optString(TITLE_ALT_FIELD) }
        thumbnail_url = optString(POSTER_FIELD).takeIf { it.isNotBlank() }?.let(::absoluteUrl)
        initialized = false
    }

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> "$baseUrl/uploads/$raw"
    }

    /** Достаёт вложенное поле по пути вида data.items. */
    private fun dig(node: JSONObject, path: String): Any? {
        var current: Any? = node
        for (part in path.split(".")) {
            current = (current as? JSONObject)?.opt(part) ?: return null
        }
        return current
    }

    // =============================== Детали =============================== 
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$apiUrl/anime/{id}".replace("{id}", anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val root = runCatching { JSONObject(response.bodyString()) }.getOrNull()
            ?: return SAnime.create()
        val data = dig(root, DETAILS_DATA) as? JSONObject ?: return SAnime.create()

        return data.toSAnime().apply {
            description = data.optString(DESC_FIELD).takeIf { it.isNotBlank() }
            genre = genresOf(data)
            status = statusOf(data.optString(STATUS_FIELD))
            initialized = true
        }
    }

    private fun genresOf(data: JSONObject): String? {
        val array = data.optJSONArray(GENRES_FIELD) ?: return null
        val names = (0 until array.length()).mapNotNull { index ->
            when (val item = array.opt(index)) {
                is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }
                is String -> item
                else -> null
            }
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun statusOf(raw: String): Int = when (raw.lowercase()) {
        "ongoing", "онгоинг" -> SAnime.ONGOING
        "finished", "released", "completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // =============================== Серии ================================ 
    override fun episodeListRequest(anime: SAnime): Request =
        GET("$apiUrl/anime/{id}/episodes".replace("{id}", anime.url), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val segments = response.request.url.pathSegments
        val animeId = segments.getOrNull(segments.indexOf("anime") + 1).orEmpty()

        val root = runCatching { JSONObject(response.bodyString()) }.getOrNull()
            ?: return emptyList()
        val items = dig(root, EPISODES_ITEMS) as? JSONArray ?: return emptyList()

        return (0 until items.length()).mapNotNull { index ->
            val item = items.opt(index) as? JSONObject ?: return@mapNotNull null
            val number = item.opt(EP_NUMBER)?.toString()?.toFloatOrNull() ?: 0f
            val player = item.optString(EP_PLAYER).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = absoluteUrl(player)
            episodeMeta[url] = animeId to number

            SEpisode.create().apply {
                this.url = url
                name = item.optString(EP_TITLE).ifBlank { "Серия " + number.toInt() }
                episode_number = number
                date_upload = parseDate(item.optString(EP_DATE))
            }
        }
    }

    private fun parseDate(raw: String): Long = runCatching {
        dateFormat.parse(raw.take(10))?.time
    }.getOrNull() ?: 0L

    // =============================== Видео ================================ 
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val number = episodeMeta[episode.url]?.second ?: episode.episode_number
        val videos = kodik.videos(episode.url, 1, number.toInt())
        if (videos.isEmpty()) throw Exception("Плеер не вернул видео")
        videos
    }

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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(SortFilter())

    private class SortFilter :
        AnimeFilter.Select<String>(
            "Сортировка",
            arrayOf("По популярности", "По рейтингу", "По дате", "По обновлению", "По просмотрам"),
        ) {
        val value: String
            get() = arrayOf("popular", "rating", "date", "updated", "views")[state]
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val PER_PAGE = 24
        private const val DEFAULT_SORT = "popular"
        private const val LATEST_SORT = "updated"

        private const val ITEMS_PATH = "data.items"
        private const val PAGINATION_PATH = "data.pagination"
        private const val PAGE_FIELD = "current_page"
        private const val PAGES_FIELD = "total_pages"

        private const val ID_FIELD = "id"
        private const val TITLE_FIELD = "title_ru"
        private const val TITLE_ALT_FIELD = "title_original"
        private const val POSTER_FIELD = "poster"

        private const val DETAILS_DATA = "data"
        private const val DESC_FIELD = "description"
        private const val GENRES_FIELD = "genres"
        private const val STATUS_FIELD = "status"

        private const val EPISODES_ITEMS = "data.episodes"
        private const val EP_NUMBER = "number"
        private const val EP_TITLE = "title"
        private const val EP_PLAYER = "player_url"
        private const val EP_DATE = "release_date"
    }
}
