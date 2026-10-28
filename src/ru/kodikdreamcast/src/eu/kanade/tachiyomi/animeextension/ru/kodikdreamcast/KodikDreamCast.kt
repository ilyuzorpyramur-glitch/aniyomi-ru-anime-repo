package eu.kanade.tachiyomi.animeextension.ru.kodikdreamcast

import android.util.Base64
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
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * Dream Cast (Kodik) as a standalone source.
 *
 * The app-side parser `kdk_dream_cast` only wraps the public Kodik API with a
 * fixed `translation_id`, so the extension talks to the API directly:
 *
 *   /list    -> catalog of anime/anime-serial for translation 1978
 *   /search  -> title search and full card data (seasons + episodes)
 *   /ftor    -> per-episode playlist links, obfuscated with a rotating shift
 *
 * The playlist `src` is base64 after a Caesar shift inside each alphabet; the
 * shift changes, so every candidate is tried until a URL decodes (see
 * [decodeSource]).
 */
class KodikDreamCast :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Kodik: Dream Cast"

    override val baseUrl = "https://kodik-api.com"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val token: String
        get() = preferences.getString(PREF_TOKEN, DEFAULT_TOKEN).orEmpty().trim()
            .ifBlank { DEFAULT_TOKEN }

    private val playerHeaders = Headers.Builder()
        .set("Referer", "$PLAYER_HOST/")
        .set("Origin", PLAYER_HOST)
        .build()

    private val genres: List<String> by lazy {
        val items = fetchRefItems("genres")
        if (items.isEmpty()) {
            KodikDreamCastFilters.FALLBACK_GENRES
        } else {
            items.sortedByDescending { it.count }
                .map { it.title }
                .filter { it.isNotBlank() }
                .take(MAX_GENRES)
        }
    }

    private val countries: List<String> by lazy {
        fetchRefItems("countries")
            .sortedByDescending { it.count }
            .map { it.title }
            .filter { it.isNotBlank() }
            .take(MAX_COUNTRIES)
    }

    private val years: List<Int> by lazy {
        runCatching {
            runBlocking {
                client.newCall(GET(refUrl("years"), headers)).awaitSuccess()
                    .parseAs<KodikYearsResponse>().results.map { it.year }
            }
        }.getOrElse { emptyList() }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = listRequest(page, "kinopoisk_rating", KodikDreamCastFilters.SearchParams())

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest(page, "updated_at", KodikDreamCastFilters.SearchParams())

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("search")
                .addQueryParameter("token", token)
                .addQueryParameter("title", query)
                .addQueryParameter("translation_id", TRANSLATION_ID)
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
                .addQueryParameter("strict", "false")
                .addQueryParameter("full_match", "false")
                .addQueryParameter("with_material_data", "true")
                .build()
            return GET(url, headers)
        }

        val params = KodikDreamCastFilters.getSearchParameters(filters)
        return listRequest(page, params.sort, params)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = KodikDreamCastFilters.getFilterList(genres, years, countries)

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = detailRequest(anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        val title = response.parseAs<KodikListResponse>().results.firstOrNull()
            ?: throw Exception("Тайтл не найден в Kodik")
        return title.toSAnime().apply { initialized = true }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = detailRequest(anime.url)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val title = response.parseAs<KodikListResponse>().results.firstOrNull() ?: return emptyList()
        val seasons = title.seasons.entries.sortedBy { it.key.toIntOrNull() ?: 0 }
        val multiSeason = seasons.size > 1
        val episodes = mutableListOf<SEpisode>()

        seasons.forEach { (seasonKey, season) ->
            val seasonNumber = seasonKey.toIntOrNull() ?: 0
            season.episodes.entries
                .sortedBy { it.key.toIntOrNull() ?: 0 }
                .forEach { (episodeKey, link) ->
                    if (link.isBlank()) return@forEach
                    val number = episodeKey.toIntOrNull() ?: (episodes.size + 1)
                    episodes += SEpisode.create().apply {
                        url = link
                        episode_number = number.toFloat()
                        name = buildString {
                            if (multiSeason) append("Сезон $seasonNumber, ")
                            append("Серия $number")
                        }
                        scanlator = title.translation?.title ?: "Dream Cast"
                    }
                }
        }

        return episodes
    }

    // =============================== Video ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val playerUrl = if (episode.url.startsWith("//")) "https:${episode.url}" else episode.url
        val segments = playerUrl.toHttpUrl().pathSegments
        val type = segments.getOrNull(0) ?: "seria"
        val id = segments.getOrNull(1) ?: return emptyList()
        val hash = segments.getOrNull(2) ?: return emptyList()

        val request = GET(
            "$PLAYER_HOST/ftor".toHttpUrl().newBuilder()
                .addQueryParameter("type", type)
                .addQueryParameter("id", id)
                .addQueryParameter("hash", hash)
                .build(),
            playerHeaders,
        )

        val body = client.newCall(request).awaitSuccess().parseAs<KodikFtorResponse>()
        return body.links.entries
            .sortedByDescending { it.key.toIntOrNull() ?: 0 }
            .mapNotNull { (quality, links) ->
                val source = links.firstOrNull()?.src?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val playlist = decodeSource(source) ?: return@mapNotNull null
                Video(playlist, "${quality}p", playlist, headers = playerHeaders)
            }
    }

    // ============================== Requests ==============================

    private fun listRequest(
        page: Int,
        defaultSort: String,
        params: KodikDreamCastFilters.SearchParams,
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("list")
            .addQueryParameter("token", token)
            .addQueryParameter("translation_id", TRANSLATION_ID)
            .addQueryParameter("types", "anime,anime-serial")
            .addQueryParameter("with_material_data", "true")
            .addQueryParameter("with_seasons", "false")
            .addQueryParameter("with_episodes", "false")
            .addQueryParameter("with_page_links", "false")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("sort", params.sort.ifBlank { defaultSort })
            .addQueryParameter("order", params.order.ifBlank { "desc" })
            .apply {
                params.genres.forEach { addQueryParameter("genres", it) }
                if (params.year > 0) addQueryParameter("year", params.year.toString())
                if (params.country.isNotBlank()) addQueryParameter("countries", params.country)
            }
            .build()
        return GET(url, headers)
    }

    private fun detailRequest(id: String): Request = GET(
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("token", token)
            .addQueryParameter("id", id)
            .addQueryParameter("translation_id", TRANSLATION_ID)
            .addQueryParameter("limit", "1")
            .addQueryParameter("with_seasons", "true")
            .addQueryParameter("with_episodes", "true")
            .addQueryParameter("with_material_data", "true")
            .build(),
        headers,
    )

    private fun refUrl(path: String) = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment(path)
        .addQueryParameter("token", token)
        .build()

    private fun fetchRefItems(path: String): List<KodikRefItem> = runCatching {
        runBlocking {
            client.newCall(GET(refUrl(path), headers)).awaitSuccess()
                .parseAs<KodikRefResponse>().results
        }
    }.getOrElse { emptyList() }

    // ============================== Parsing ===============================

    private fun listParse(response: Response): AnimesPage {
        val body = response.parseAs<KodikListResponse>()
        val animes = body.results.map { it.toSAnime() }
        return AnimesPage(animes, animes.size >= PAGE_SIZE)
    }

    private fun KodikTitle.toSAnime(): SAnime = SAnime.create().apply {
        url = id
        title = material_data?.title ?: material_data?.anime_title ?: title
        thumbnail_url = material_data?.poster_url ?: material_data?.anime_poster_url
        description = material_data?.anime_description ?: material_data?.description
        genre = (material_data?.anime_genres ?: emptyList())
            .ifEmpty { material_data?.genres ?: emptyList() }
            .joinToString()
        status = when (material_data?.anime_status) {
            "released" -> SAnime.COMPLETED
            "ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================ Deobfuscation ===========================

    /**
     * The player returns `src` as a Caesar-shifted base64 string. The shift
     * rotates over time, so candidates are tried until one decodes into a URL.
     */
    private fun decodeSource(encoded: String): String? {
        for (shift in SHIFT_ORDER) {
            val rotated = encoded.rotate(shift)
            val padded = rotated.padBase64()
            val decoded = DECODE_FLAGS.mapNotNull { flags ->
                runCatching { String(Base64.decode(padded, flags), Charsets.UTF_8) }.getOrNull()
            }
            decoded.firstOrNull { it.startsWith("http") || it.startsWith("//") }?.let { return it }
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

    private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_TOKEN,
            default = DEFAULT_TOKEN,
            title = "Токен Kodik API",
            summary = "Токен приложения, снятый с AniLabX. Меняйте, если API начнёт отвечать ошибкой.",
            dialogMessage = "Пустое поле возвращает встроенный токен.",
            restartRequired = true,
        )
    }

    companion object {
        private const val TRANSLATION_ID = "1978"
        private const val PAGE_SIZE = 30
        private const val PLAYER_HOST = "https://kodikplayer.com"
        private const val PREF_TOKEN = "kodik_api_token"
        private const val DEFAULT_TOKEN = "REMOVED"
        private const val MAX_GENRES = 48
        private const val MAX_COUNTRIES = 30
        private val SHIFT_ORDER = listOf(18) + (1..25).filter { it != 18 }
        private val DECODE_FLAGS = listOf(Base64.DEFAULT, Base64.URL_SAFE)
    }
}
