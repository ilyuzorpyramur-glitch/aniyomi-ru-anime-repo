package eu.kanade.tachiyomi.animeextension.ru.animevost

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * animevost.org — у сайта есть собственный JSON-API (api.animevost.org/v1),
 * тот же, что использует официальное приложение.
 *
 * Раньше расширение разбирало HTML каталога, а там на страницу приходится ровно
 * десять карточек (div.shortstory) — сколько ни чини селекторы, больше десяти
 * из страницы не достать. API отдаёт тот же каталог пачками по сорок записей и
 * сразу со всеми полями, плюс прямые mp4 без веб-плееров.
 */
class AnimevostSource(override val name: String, override val baseUrl: String) :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page)

    override fun popularAnimeParse(response: Response): AnimesPage = catalogParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return catalogRequest(page)

        val body = FormBody.Builder()
            .add("name", query.trim())
            .build()

        return POST("$API_URL/search", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // Поиск отдаёт весь результат одной пачкой, без постраничности.
        val animes = response.parseAs<ApiList>().data.map { it.toSAnime() }

        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = infoRequest(anime.url.toId())

    override fun animeDetailsParse(response: Response): SAnime {
        val item = response.parseAs<ApiList>().data.firstOrNull() ?: return SAnime.create()

        return item.toSAnime()
    }

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/index.php?do=search&subaction=search&story=${anime.title}"

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.toId()
        val items = client.newCall(playlistRequest(id)).awaitSuccess().parseAs<List<ApiEpisode>>()

        return items
            .map { item ->
                val number = EPISODE_NUMBER_REGEX.find(item.name)?.value?.toFloatOrNull() ?: 0f
                SEpisode.create().apply {
                    url = "/episode/$id/${item.name}"
                    name = item.name
                    episode_number = number
                }
            }.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val id = episode.url.substringAfter("/episode/").substringBefore('/')
        val name = episode.url.substringAfter("/episode/$id/")

        val items = client.newCall(playlistRequest(id)).awaitSuccess().parseAs<List<ApiEpisode>>()
        val item = items.firstOrNull { it.name == name } ?: return emptyList()

        // hd есть не у всех тайтлов (у старых 404), поэтому берём только
        // непустые ссылки и проверять качество оставляем плееру.
        return listOfNotNull(
            item.hd?.takeIf { it.isNotBlank() }?.let { Video(it, "720p", it, headers = headers) },
            item.std?.takeIf { it.isNotBlank() }?.let { Video(it, "480p", it, headers = headers) },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: return this

        return sortedByDescending { it.videoTitle.contains(quality) }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Предпочитаемое качество",
            entries = listOf("720p", "480p"),
            entryValues = listOf("720", "480"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // =============================== Utils ================================

    private fun catalogRequest(page: Int): Request {
        val url = "$API_URL/last".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("quantity", PER_PAGE.toString())
            .build()

        return GET(url, headers)
    }

    private fun catalogParse(response: Response): AnimesPage {
        val result = response.parseAs<ApiList>()
        val animes = result.data.map { it.toSAnime() }
        val page = result.state?.page ?: 1
        val total = result.state?.count ?: 0

        return AnimesPage(animes, page * PER_PAGE < total)
    }

    private fun infoRequest(id: String): Request = POST(
        "$API_URL/info",
        headers,
        FormBody.Builder().add("id", id).build(),
    )

    private fun playlistRequest(id: String): Request = POST(
        "$API_URL/playlist",
        headers,
        FormBody.Builder().add("id", id).build(),
    )

    private fun String.toId(): String = trimEnd('/').substringAfterLast('/')

    private fun ApiAnime.toSAnime(): SAnime = SAnime.create().apply {
        url = "/anime/$id"
        title = this@toSAnime.title.replace(EPISODE_COUNT_REGEX, "").trim()
        // У static.openni.ru просрочен сертификат Let's Encrypt (до 21.10.2026),
        // поэтому https-картинки не загружаются. Тот же файл по http отдаётся
        // нормально — переключаем схему только для этого хоста.
        thumbnail_url = urlImagePreview?.let {
            if (it.contains("static.openni.ru")) it.replaceFirst("https://", "http://") else it
        }
        genre = this@toSAnime.genre
        author = director
        description = buildString {
            this@toSAnime.description
                ?.replace(BR_REGEX, "\n")
                ?.replace(TAG_REGEX, "")
                ?.trim()
                ?.let { appendLine(it) }
            year?.takeIf { it.isNotBlank() }?.let { appendLine("\nГод выхода: $it") }
            type?.takeIf { it.isNotBlank() }?.let { appendLine("Тип: $it") }
        }.trim()
        // "[1-25 из 26]" — вышло меньше, чем заявлено, значит ещё выходит.
        status = STATUS_REGEX.find(this@toSAnime.title)?.let { match ->
            val aired = match.groupValues[2].toIntOrNull() ?: 0
            val total = match.groupValues[3].toIntOrNull() ?: 0
            if (total > aired) SAnime.ONGOING else SAnime.COMPLETED
        } ?: SAnime.COMPLETED
    }

    @Serializable
    private data class ApiList(
        val state: ApiState? = null,
        val data: List<ApiAnime> = emptyList(),
    )

    @Serializable
    private data class ApiState(
        val page: Int = 1,
        val count: Int = 0,
    )

    @Serializable
    private data class ApiAnime(
        val id: Long = 0,
        val title: String = "",
        val description: String? = null,
        val genre: String? = null,
        val year: String? = null,
        val type: String? = null,
        val director: String? = null,
        val urlImagePreview: String? = null,
    )

    @Serializable
    private data class ApiEpisode(
        val name: String = "",
        val hd: String? = null,
        val std: String? = null,
    )

    companion object {
        private const val API_URL = "https://api.animevost.org/v1"

        /** API отдаёт максимум сорок записей за запрос, сколько ни проси. */
        private const val PER_PAGE = 40

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"

        private val EPISODE_NUMBER_REGEX = Regex("""\d+""")

        /** Хвост вида "[1-25 из 26]" в названии — он же признак онгоинга. */
        private val EPISODE_COUNT_REGEX = Regex("""\s*\[[^\]]*]\s*$""")
        private val STATUS_REGEX = Regex("""\[(\d+)-(\d+)\s+из\s+(\d+)]""")

        private val BR_REGEX = Regex("""<br\s*/?>""")
        private val TAG_REGEX = Regex("""<[^>]+>""")
    }
}
