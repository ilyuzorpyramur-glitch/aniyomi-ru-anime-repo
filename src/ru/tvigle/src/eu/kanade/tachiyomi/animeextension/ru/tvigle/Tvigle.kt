package eu.kanade.tachiyomi.animeextension.ru.tvigle

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * Tvigle — легальный онлайн-кинотеатр (фильмы, сериалы, мультфильмы, дорамы).
 *
 * Сайт переехал на Next.js, а данные отдаёт закрытое REST API:
 *  - токен: GET /api/bff/updateAppToken/ → `{"token": "<jwt>"}`, живёт ~5 минут
 *    и передаётся заголовком `Authorization: Token <jwt>`;
 *  - каталог: /api/category/<id>/product/ (limit/offset, o, release_year, country);
 *  - поиск: /api/product/?q=;
 *  - серии: /api/product/<id>/video/.
 *
 * Видео зависит от правообладателя:
 *  - собственный контент (`content_provider` ≠ wink) отдаётся плеером
 *    cloud.tvigle.ru в виде прямых mp4 по качествам — их и играем;
 *  - контент Wink/START защищён (HLS с AES-ключом на закрытом хосте), поэтому
 *    для него отдаётся страница плеера, которую Tadami откроет в WebView.
 */
class Tvigle : AnimeHttpLegacySource() {

    override val name = "Tvigle"

    override val baseUrl = "https://www.tvigle.ru"

    override val lang = "ru"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json, text/plain, */*")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36",
        )

    // ============================== Token ================================

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenIssuedAt = 0L

    /**
     * Токен приложения живёт около пяти минут, поэтому кешируем его на
     * [TOKEN_TTL_MS] и обновляем по необходимости — иначе каждый запрос
     * каталога тянул бы за собой ещё один.
     */
    private suspend fun apiHeaders(): Headers {
        val token = cachedToken?.takeIf { System.currentTimeMillis() - tokenIssuedAt < TOKEN_TTL_MS }
            ?: client
                .newCall(GET("$apiUrl/bff/updateAppToken/", headers))
                .awaitSuccess()
                .parseAs<TokenDto>()
                .token
                .also {
                    cachedToken = it
                    tokenIssuedAt = System.currentTimeMillis()
                }

        return headers.newBuilder().set("Authorization", "Token $token").build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used.")

    override fun popularAnimeParse(response: Response): AnimesPage = response.toAnimesPage()

    override suspend fun getPopularAnime(page: Int): AnimesPage = catalog(
        page,
        TvigleFilters.SearchParams(sort = "kinopoisk_rating"),
    )

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used.")

    override fun latestUpdatesParse(response: Response): AnimesPage = response.toAnimesPage()

    // Порядок каталога по умолчанию — сначала новинки, отдельного ключа нет.
    override suspend fun getLatestUpdates(page: Int): AnimesPage = catalog(page, TvigleFilters.SearchParams())

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = throw UnsupportedOperationException("Not used.")

    override fun searchAnimeParse(response: Response): AnimesPage = response.toAnimesPage()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.isBlank()) return catalog(page, TvigleFilters.getSearchParameters(filters))

        val url = "$apiUrl/product/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("fields", CATALOG_FIELDS)
            .build()

        return client.newCall(GET(url, apiHeaders())).awaitSuccess().toAnimesPage()
    }

    override fun getFilterList(): AnimeFilterList = TvigleFilters.FILTER_LIST

    private suspend fun catalog(
        page: Int,
        params: TvigleFilters.SearchParams,
    ): AnimesPage {
        val url = "$apiUrl/category/${params.categoryId}/product/".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", PAGE_SIZE.toString())
            addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            addQueryParameter("fields", CATALOG_FIELDS)
            addQueryParameter("is_soon", "false")
            params.sort.takeIf { it.isNotBlank() }?.let { addQueryParameter("o", it) }
            params.countries.forEach { addQueryParameter("country", it.toString()) }
            params.years.forEach { addQueryParameter("release_year", it) }
        }.build()

        return client.newCall(GET(url, apiHeaders())).awaitSuccess().toAnimesPage()
    }

    private fun Response.toAnimesPage(): AnimesPage {
        val page = parseAs<PageDto<ProductDto>>()

        return AnimesPage(page.results.map { it.toSAnime() }, page.next != null)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/product${anime.url}", headers)

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/video${anime.url}"

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used.")

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val product = client
            .newCall(GET("$apiUrl/product${anime.url}", apiHeaders()))
            .awaitSuccess()
            .parseAs<ProductDto>()

        return product.toSAnime()
    }

    private fun ProductDto.toSAnime(): SAnime = SAnime.create().apply {
        url = "/$slug/"
        title = name
        thumbnail_url = poster ?: thumbnail
        genre = (genres + countries).filter { it.isNotBlank() }.distinct().joinToString(", ")
        status = when {
            kind == "series" && videosCount > 0 -> SAnime.ONGOING
            kind == "series" -> SAnime.UNKNOWN
            else -> SAnime.COMPLETED
        }
        description = buildString {
            this@toSAnime.description
                ?.stripHtml()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    appendLine(it)
                    appendLine()
                }
            releaseYear?.let { appendLine("Год выхода: $it") }
            countries.takeIf { it.isNotEmpty() }?.let { appendLine("Страна: ${it.joinToString(", ")}") }
            ageRestrictions?.takeIf { it.isNotBlank() }?.let { appendLine("Возраст: $it") }
            kinopoiskRating?.toRating()?.let { appendLine("Кинопоиск: $it") }
            imdbRating?.toRating()?.let { appendLine("IMDB: $it") }
            if (isPaid) appendLine("Требуется подписка Tvigle")
        }.trim()
    }

    /** API отдаёт рейтинг строкой с тремя знаками ("8.224") — округляем. */
    private fun String.toRating(): String? = toDoubleOrNull()
        ?.takeIf { it > 0 }
        ?.let { String.format("%.1f", it) }

    private fun String.stripHtml(): String = replace(BR_REGEX, "\n")
        .replace(TAG_REGEX, "")
        .replace("&nbsp;", " ")
        .replace("&laquo;", "«")
        .replace("&raquo;", "»")
        .replace("&mdash;", "—")
        .replace("&amp;", "&")
        .replace(BLANK_LINES_REGEX, "\n\n")
        .trim()

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException("Not used.")

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used.")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val product = client
            .newCall(GET("$apiUrl/product${anime.url}", apiHeaders()))
            .awaitSuccess()
            .parseAs<ProductDto>()

        val videos = fetchVideos(product.id)
        val multiSeason = videos.mapNotNull { it.season }.distinct().size > 1

        return videos
            .mapIndexed { index, item ->
                val season = item.season ?: 1
                val series = item.series ?: (index + 1)

                SEpisode.create().apply {
                    // content_id нужен плееру, id — для запроса метаданных.
                    url = "/${product.id}/${item.id}/${item.contentId ?: 0}"
                    name = buildString {
                        if (multiSeason) append("Сезон $season • ")
                        append("Серия $series")
                        item.name
                            .substringAfter("Серия $series", "")
                            .trim(' ', '.', '-', '—')
                            .takeIf { it.isNotBlank() }
                            ?.let { append(" — $it") }
                    }
                    episode_number = (season * EPISODE_SEASON_STEP + series).toFloat()
                }
            }.sortedByDescending { it.episode_number }
    }

    /** Серий у сериала бывают сотни — тянем каталог страницами до конца. */
    private suspend fun fetchVideos(productId: Int): List<VideoItemDto> {
        val result = mutableListOf<VideoItemDto>()
        var offset = 0

        while (true) {
            val url = "$apiUrl/product/$productId/video/".toHttpUrl().newBuilder()
                .addQueryParameter("limit", VIDEO_PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("fields", VIDEO_FIELDS)
                .build()

            val page = client.newCall(GET(url, apiHeaders())).awaitSuccess().parseAs<PageDto<VideoItemDto>>()
            result += page.results

            if (page.next == null || page.results.isEmpty() || result.size >= page.count) break
            offset += VIDEO_PAGE_SIZE
        }

        return result
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException("Not used.")

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException("Not used.")

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.trim('/').split('/')
        val contentId = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw Exception("Не удалось определить видео")

        val item = runCatching {
            client
                .newCall(GET("$CLOUD_URL/api/play/video/$contentId/?partner_id=$PARTNER_ID", cloudHeaders()))
                .awaitSuccess()
                .parseAs<CloudResponseDto>()
        }.getOrNull()
            ?.playlist
            ?.items
            ?.firstOrNull()

        item?.errorMessage?.takeIf { it.isNotBlank() }?.let { throw Exception(it.stripHtml()) }

        val videos = item?.videos
        // Контент Wink/START плеер cloud.tvigle.ru не отдаёт файлами: там HLS с
        // AES-ключом на закрытом хосте. Возвращать страницу плеера нельзя —
        // Tadami считает Video.videoUrl медиапотоком и падает с
        // «unrecognized file format», поэтому честно сообщаем причину.
        if (videos == null) {
            throw Exception("Видео защищено правообладателем (Wink/START) и не воспроизводится в приложении")
        }

        val subtitles = item.subtitlesUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(Track(it.toAbsoluteUrl(), "Русские")) }
            .orEmpty()

        val direct = (videos.hls + videos.mp4)
            .mapNotNull { (quality, link) ->
                val url = link.takeIf { it.isNotBlank() }?.toAbsoluteUrl() ?: return@mapNotNull null
                val height = QUALITY_REGEX.find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                height to Video(url, quality, url, headers = cloudHeaders(), subtitleTracks = subtitles)
            }.sortedByDescending { (height, _) -> height }
            .map { (_, video) -> video }

        if (direct.isEmpty()) throw Exception("Плеер не отдал ни одной ссылки на видео")

        return direct
    }

    private fun cloudHeaders() = headers
        .newBuilder()
        .set("Referer", "$CLOUD_URL/")
        .build()

    private fun String.toAbsoluteUrl(): String = if (startsWith("//")) "https:$this" else this

    companion object {
        private const val PAGE_SIZE = 24
        private const val VIDEO_PAGE_SIZE = 100
        private const val EPISODE_SEASON_STEP = 1000
        private const val TOKEN_TTL_MS = 3 * 60 * 1000L
        private const val PARTNER_ID = 24
        private const val CLOUD_URL = "https://cloud.tvigle.ru"

        private const val CATALOG_FIELDS =
            "id,slug,name,poster,thumbnail,genres,countries,release_year," +
                "age_restrictions,kinopoisk_rating,imdb_rating,is_paid,videos_count,kind"

        private const val VIDEO_FIELDS = "id,name,thumbnail,content_id,content_provider,is_paid,can_play,season,series"

        private val QUALITY_REGEX = Regex("""(\d+)""")
        private val BR_REGEX = Regex("""<br\s*/?>|</p>""", RegexOption.IGNORE_CASE)
        private val TAG_REGEX = Regex("""<[^>]+>""")
        private val BLANK_LINES_REGEX = Regex("""\n{3,}""")
    }
}
