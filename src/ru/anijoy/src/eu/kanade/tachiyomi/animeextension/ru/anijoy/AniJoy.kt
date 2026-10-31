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

    /** Связка «адрес серии → (id аниме, номер)». */
    private val episodeMeta = mutableMapOf<String, Pair<String, Float>>()

    /** id аниме → адрес плеера: нужен, когда API отдал не все серии. */
    private val playerByAnime = mutableMapOf<String, String>()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // =============================== Каталог ===============================

    private fun catalogUrl(page: Int, sort: String, query: String?, genre: String?): String {
        val builder = "$apiUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PER_PAGE.toString())
            .addQueryParameter("sort", sort)
        if (!query.isNullOrBlank()) builder.addQueryParameter("search", query)
        if (!genre.isNullOrBlank()) builder.addQueryParameter("genre", genre)
        return builder.build().toString()
    }

    override fun popularAnimeRequest(page: Int): Request = GET(catalogUrl(page, DEFAULT_SORT, null, null), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, LATEST_SORT, null, null), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalog(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var sort = DEFAULT_SORT
        var genre = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.value
                is GenreFilter -> genre = filter.toUri()

                else -> {}
            }
        }
        return GET(catalogUrl(page, sort, query, genre), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    private fun parseCatalog(response: Response): AnimesPage {
        val root = runCatching { JSONObject(response.bodyString()) }.getOrNull()
            ?: return AnimesPage(emptyList(), false)
        val items = dig(root, ITEMS_PATH) as? JSONArray
            ?: return AnimesPage(emptyList(), false)

        val animes = (0 until items.length())
            .mapNotNull { index -> (items.opt(index) as? JSONObject)?.toSAnime() }
            .distinctBy { it.url }

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

    /** Kodik живёт на многих доменах; недоступный подменяем на рабочий. */
    private fun playerUrl(raw: String): String {
        val absolute = absoluteUrl(raw)
        if (!absolute.contains("kodik", ignoreCase = true)) return absolute
        return absolute.replace(KODIK_HOST_REGEX, "//$PLAYER_HOST/")
    }

    private fun dig(node: JSONObject, path: String): Any? {
        var current: Any? = node
        for (part in path.split(".")) {
            current = (current as? JSONObject)?.opt(part) ?: return null
        }
        return current
    }

    // =============================== Детали ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/{id}".replace("{id}", anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val root = runCatching { JSONObject(response.bodyString()) }.getOrNull()
            ?: return SAnime.create()
        val data = dig(root, DETAILS_DATA) as? JSONObject ?: return SAnime.create()

        data.optString(KODIK_FIELD).takeIf { it.isNotBlank() }?.let {
            playerByAnime[data.opt(ID_FIELD)?.toString().orEmpty()] = playerUrl(it)
        }

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

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/anime/{id}/episodes".replace("{id}", anime.url), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val segments = response.request.url.pathSegments
        val animeId = segments.getOrNull(segments.indexOf("anime") + 1).orEmpty()

        val root = runCatching { JSONObject(response.bodyString()) }.getOrNull()
        val items = root?.let { dig(it, EPISODES_ITEMS) as? JSONArray }

        val fromApi = items?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.opt(index) as? JSONObject ?: return@mapNotNull null
                val number = item.opt(EP_NUMBER)?.toString()?.toFloatOrNull() ?: 0f
                val player = item.optString(EP_PLAYER).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = playerUrl(player)
                episodeMeta[url] = animeId to number
                SEpisode.create().apply {
                    this.url = url
                    name = item.optString(EP_TITLE).ifBlank { "Серия " + number.toInt() }
                    episode_number = number
                    date_upload = parseDate(item.optString(EP_DATE))
                }
            }
        }.orEmpty()

        // Сайт часто отдаёт в API лишь часть серий: полный список лежит в плеере.
        val expected = expectedEpisodes(animeId)
        if (fromApi.size >= expected && fromApi.isNotEmpty()) return fromApi

        val fromPlayer = episodesFromPlayer(animeId)
        if (fromPlayer.isEmpty()) return fromApi
        return fromPlayer
    }

    private fun expectedEpisodes(animeId: String): Int {
        val root = runCatching {
            client.newCall(GET("$apiUrl/anime/{id}".replace("{id}", animeId), headers)).execute()
                .use { JSONObject(it.bodyString()) }
        }.getOrNull() ?: return 0
        val data = dig(root, DETAILS_DATA) as? JSONObject ?: return 0
        return data.optInt("episodes_count", 0)
    }

    /** Разбирает список серий из страницы плеера Kodik. */
    private fun episodesFromPlayer(animeId: String): List<SEpisode> {
        val player = playerByAnime[animeId] ?: run {
            expectedEpisodes(animeId)
            playerByAnime[animeId]
        } ?: return emptyList()

        val page = runCatching {
            client.newCall(GET(player, kodikHeaders())).execute().use { it.bodyString() }
        }.getOrNull() ?: return emptyList()

        val episodes = mutableListOf<SEpisode>()
        for (tag in EPISODE_TAG_REGEX.findAll(page)) {
            val value = VALUE_REGEX.find(tag.value)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val title = TITLE_REGEX.find(tag.value)?.groupValues?.get(1).orEmpty()
            val number = value.toFloat()
            val url = "$player#e=$value"
            episodeMeta[url] = animeId to number
            episodes += SEpisode.create().apply {
                this.url = url
                name = title.ifBlank { "Серия $value" }
                episode_number = number
            }
        }
        return episodes.sortedBy { it.episode_number }
    }

    private fun kodikHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", "https://$PLAYER_HOST")
        .build()

    private fun parseDate(raw: String): Long = runCatching {
        dateFormat.parse(raw.take(10))?.time
    }.getOrNull() ?: 0L

    // =============================== Видео ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val number = episodeMeta[episode.url]?.second
            ?: EPISODE_HASH_REGEX.find(episode.url)?.groupValues?.get(1)?.toFloatOrNull()
            ?: episode.episode_number
        val target = episode.url.substringBefore("#")
        val videos = kodik.videos(target, 1, number.toInt())
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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        AnimeFilter.Select<String>(
            "Сортировка",
            arrayOf("По популярности", "По рейтингу", "По дате", "По просмотрам"),
        ) {
        val value: String
            get() = arrayOf("popular", "rating", "date", "views")[state]
    }

    private class GenreFilter :
        AnimeFilter.Group<Genre>("Жанры", listOf(
            Genre(",", ","),
            Genre("А", "А"),
            Genre("Б", "Б"),
            Genre("В", "В"),
            Genre("Г", "Г"),
            Genre("Д", "Д"),
            Genre("И", "И"),
            Genre("К", "К"),
            Genre("М", "М"),
            Genre("П", "П"),
            Genre("Р", "Р"),
            Genre("С", "С"),
            Genre("Т", "Т"),
            Genre("У", "У"),
            Genre("Ф", "Ф"),
            Genre("Ш", "Ш"),
            Genre("Э", "Э"),
            Genre("а", "а"),
            Genre("б", "б"),
            Genre("в", "в"),
            Genre("г", "г"),
            Genre("д", "д"),
            Genre("е", "е"),
            Genre("ж", "ж"),
            Genre("з", "з"),
            Genre("и", "и"),
            Genre("й", "й"),
            Genre("к", "к"),
            Genre("л", "л"),
            Genre("м", "м"),
            Genre("н", "н"),
            Genre("о", "о"),
            Genre("п", "п"),
            Genre("р", "р"),
            Genre("с", "с"),
            Genre("т", "т"),
            Genre("у", "у"),
            Genre("ф", "ф"),
            Genre("х", "х"),
            Genre("ц", "ц"),
            Genre("ч", "ч"),
            Genre("ш", "ш"),
            Genre("ъ", "ъ"),
            Genre("ы", "ы"),
            Genre("ь", "ь"),
            Genre("э", "э"),
            Genre("ю", "ю"),
            Genre("я", "я"),
            Genre("ё", "ё"),
        )) {
        fun toUri(): String = state
            .filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            .joinToString(",") { it.id }
    }

    private class Genre(name: String, val id: String) : AnimeFilter.TriState(name)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val PLAYER_HOST = "kodikplayer.com"

        private val KODIK_HOST_REGEX = Regex("""//kodik[a-z0-9.-]*\.[a-z]{2,}/""")

        private val EPISODE_TAG_REGEX = Regex("""<option[^>]*data-hash="[^"]+"[^>]*>""", RegexOption.IGNORE_CASE)
        private val VALUE_REGEX = Regex("""value="(\d+)"""")
        private val TITLE_REGEX = Regex("""data-title="([^"]*)"""")
        private val EPISODE_HASH_REGEX = Regex("""#e=(\d+)""")

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
        private const val KODIK_FIELD = "kodik_url"

        private const val EPISODES_ITEMS = "data.episodes"
        private const val EP_NUMBER = "number"
        private const val EP_TITLE = "title"
        private const val EP_PLAYER = "player_url"
        private const val EP_DATE = "release_date"
    }
}
