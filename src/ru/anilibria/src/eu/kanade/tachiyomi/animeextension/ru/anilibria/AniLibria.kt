package eu.kanade.tachiyomi.animeextension.ru.anilibria

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class AniLibria :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniLibria"

    override val baseUrl = "https://anilibria.top"

    override val lang = "ru"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api/v1"

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page, AniLibriaFilters.SearchParams())

    override fun popularAnimeParse(response: Response): AnimesPage = catalogParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, AniLibriaFilters.SearchParams(sorting = "FRESH_AT_DESC"))

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniLibriaFilters.getSearchParameters(filters)

        if (query.isBlank()) return catalogRequest(page, params)

        val url = "$apiUrl/app/search/releases".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // Catalog endpoint returns a paginated object, the search endpoint a bare array.
        if (response.request.url.encodedPath.endsWith("/app/search/releases")) {
            val releases = response.parseAs<List<ReleaseDto>>()
            return AnimesPage(releases.map { it.toSAnime(baseUrl) }.distinctBy { it.url }, false)
        }
        return catalogParse(response)
    }

    override fun getFilterList(): AnimeFilterList = AniLibriaFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/releases/${anime.url}", headers)

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/releases/release/${anime.url}"

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<ReleaseDto>().toSAnimeDetails(baseUrl)

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/anime/releases/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val release = response.parseAs<ReleaseDto>()
        val isMovie = release.type?.value == "MOVIE"

        return release.episodes.orEmpty()
            .filter { it.hlsList().isNotEmpty() }
            .map { episode ->
                val number = episode.ordinal ?: 1f
                SEpisode.create().apply {
                    url = "/anime/releases/episodes/${episode.id}"
                    episode_number = number
                    name = buildString {
                        if (isMovie) {
                            append("Фильм")
                        } else {
                            append("Серия ")
                            append(number.formatNumber())
                        }
                        val title = episode.name ?: episode.nameEnglish
                        if (!title.isNullOrBlank()) append(" - $title")
                    }
                    date_upload = episode.updatedAt.toTimestamp()
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(apiUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val episode = response.parseAs<EpisodeDto>()

        val videos = episode.hlsList().flatMap { (quality, url) ->
            playlistUtils.extractFromHls(
                playlistUrl = url,
                referer = "$baseUrl/",
                videoNameGen = { "AniLibria - $quality" },
            ).ifEmpty {
                listOf(Video(url, "AniLibria - $quality", url, headers = headers))
            }
        }

        return videos.sortedByDescending { it.videoTitle.quality() == preferredQuality }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Предпочитаемое качество",
            summary = "%s",
            entries = QUALITY_LIST,
            entryValues = QUALITY_LIST,
        )
    }

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    // =============================== Utils ================================

    private fun catalogRequest(page: Int, params: AniLibriaFilters.SearchParams): Request {
        val url = "$apiUrl/anime/catalog/releases".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("f[sorting]", params.sorting)
            .apply {
                params.genres.forEach { addQueryParameter("f[genres][]", it) }
                params.types.forEach { addQueryParameter("f[types][]", it) }
                params.seasons.forEach { addQueryParameter("f[seasons][]", it) }
                params.ageRatings.forEach { addQueryParameter("f[age_ratings][]", it) }
                params.years.minOrNull()?.let { addQueryParameter("f[years][from_year]", it) }
                params.years.maxOrNull()?.let { addQueryParameter("f[years][to_year]", it) }
            }
            .build()

        return GET(url, headers)
    }

    private fun catalogParse(response: Response): AnimesPage {
        val result = response.parseAs<PaginatedDto<ReleaseDto>>()
        val pagination = result.meta?.pagination
        val hasNextPage = pagination?.let {
            (it.currentPage ?: 1) < (it.totalPages ?: 1)
        } ?: false

        return AnimesPage(result.data.map { it.toSAnime(baseUrl) }.distinctBy { it.url }, hasNextPage)
    }

    private fun Float.formatNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun String.quality(): String? = QUALITY_REGEX.find(this)?.value

    private fun String?.toTimestamp(): Long {
        if (this.isNullOrBlank()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(substringBefore('.').removeSuffix("Z").substringBefore('+'))
                ?.time
        }.getOrNull() ?: 0L
    }

    companion object {
        private const val PAGE_LIMIT = 30
        private const val SEARCH_LIMIT = 50

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = listOf("1080p", "720p", "480p")

        private val QUALITY_REGEX = Regex("""\d+p""")
    }
}
