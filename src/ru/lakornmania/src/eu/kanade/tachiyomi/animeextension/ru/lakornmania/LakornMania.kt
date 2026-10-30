package eu.kanade.tachiyomi.animeextension.ru.lakornmania

import android.net.Uri
import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
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
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LakornMania :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "LakornMania"

    override val baseUrl = "https://lakornmania.ru"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = listRequest("", page)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest("/ongoing", page)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) return searchRequest(query, page)

        return listRequest(LakornManiaFilters.getSearchParameters(filters).path, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun getFilterList(): AnimeFilterList = LakornManiaFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        val info = document.infoMap()

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = info["Русское название"] ?: document.selectFirst("h1")?.text()?.cleanTitle().orEmpty()
            thumbnail_url = document.selectFirst(POSTER_SELECTOR)?.absUrl("src")
            author = info["Русская озвучка"]
            genre = document.select(".item__list a").joinToString(", ") { it.text().trim() }.ifBlank { null }
            status = if (info["Количество серий"]?.contains("серия") == true) SAnime.ONGOING else SAnime.COMPLETED
            description = buildString {
                document.selectFirst("meta[name=description]")?.attr("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                DETAIL_KEYS.forEach { key ->
                    info[key]?.takeIf { it.isNotBlank() }?.let { appendLine("$key: $it") }
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val links = document.select(".playlist-episodes a[href]")

        if (links.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    url = response.request.url.encodedPath
                    episode_number = 1f
                    name = "Фильм"
                },
            )
        }

        return links.mapIndexed { index, link ->
            val path = runCatching { link.absUrl("href").toHttpUrl().encodedPath }.getOrDefault(link.attr("href"))
            val number = EPISODE_NUMBER_REGEX.find(path)?.groupValues?.get(1)?.toFloatOrNull()
                ?: (index + 1).toFloat()

            SEpisode.create().apply {
                url = path
                episode_number = number
                name = link.text().trim().ifBlank { "${number.toInt()} серия" }
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val document = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().useAsJsoup()
        val playerUrl = document.playerUrl() ?: throw Exception("Плеер не найден на странице серии")
        val page = client.newCall(GET(playerUrl, headers)).awaitSuccess().bodyString()
        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)
        val season = playerUrl.toHttpUrl().queryParameter("season").orEmpty()
        val wanted = playerUrl.toHttpUrl().queryParameter("episode").orEmpty()

        // Every voice-over is a separate Kodik "media" with its own id/hash.
        val translations = Jsoup.parse(page)
            .select(".serial-translations-box option")
            .mapNotNull { option ->
                val mediaId = option.attr("data-media-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mediaHash = option.attr("data-media-hash").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mediaType = option.attr("data-media-type").ifBlank { "serial" }

                Translation(
                    title = option.attr("data-title").trim().ifBlank { "Kodik" },
                    isSubtitles = option.attr("data-translation-type") == "subtitles",
                    url = kodikUrl(mediaType, mediaId, mediaHash, season, wanted),
                )
            }
            .filterNot { ignoreSubs && it.isSubtitles }
            .ifEmpty { listOf(Translation("Kodik", false, playerUrl)) }

        val videos = translations.parallelCatchingFlatMap { translation ->
            translationVideos(translation, wanted)
        }

        if (videos.isEmpty()) throw Exception("Не удалось получить ссылки на видео")

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private class Translation(val title: String, val isSubtitles: Boolean, val url: String)

    private suspend fun translationVideos(translation: Translation, wanted: String): List<Video> {
        val page = client.newCall(GET(translation.url, headers)).awaitSuccess().bodyString()

        val targetUrl = if (wanted.isBlank()) {
            translation.url
        } else {
            val option = Jsoup.parse(page)
                .select(".serial-series-box option")
                .firstOrNull { it.attr("value") == wanted }
                ?: return kodikVideos(translation.url, translation.label())

            val id = option.attr("data-id").takeIf { it.isNotBlank() } ?: return emptyList()
            val hash = option.attr("data-hash").takeIf { it.isNotBlank() } ?: return emptyList()
            "https://kodikplayer.com/seria/$id/$hash/720p"
        }

        return kodikVideos(targetUrl, translation.label())
    }

    private fun Translation.label(): String = buildString {
        append(title)
        if (isSubtitles) append(" (субтитры)")
    }

    private suspend fun kodikVideos(playerUrl: String, label: String): List<Video> {
        val page = client.newCall(GET(playerUrl, headers)).awaitSuccess().bodyString()

        val params = URL_PARAMS_REGEX.find(page)?.groupValues?.get(1)?.parseAs<KodikUrlParams>()
            ?: return emptyList()
        if (params.d_sign.isEmpty() || params.pd.isEmpty()) return emptyList()

        val segments = playerUrl.toHttpUrl().pathSegments
        if (segments.size < 3) return emptyList()

        val formBody = FormBody.Builder()
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

        val ftorHeaders = Headers.Builder()
            .set("Referer", "$baseUrl/")
            .set("Origin", "https://${params.pd}")
            .set("User-Agent", "Mozilla/5.0 (Android)")
            .build()

        val ftor = client.newCall(POST("https://${params.pd}/ftor", ftorHeaders, formBody))
            .awaitSuccess()
            .parseAs<KodikFtorResponse>()

        return ftor.links.entries
            .sortedByDescending { it.key.toIntOrNull() ?: 0 }
            .flatMap { (quality, links) ->
                val encoded = links.firstOrNull()?.src ?: return@flatMap emptyList()
                val playlistUrl = decodeKodikSource(encoded) ?: return@flatMap emptyList()

                playlistUtils.extractFromHls(
                    playlistUrl = playlistUrl,
                    referer = "https://${params.pd}/",
                    videoNameGen = { "$label - $it" },
                ).ifEmpty {
                    listOf(Video(playlistUrl, "$label - ${quality}p", playlistUrl))
                }
            }
    }

    /**
     * Kodik obfuscates the playlist url with a rotating alphabet shift applied before
     * base64 encoding. The shift changes over time, so every variant is tried and the
     * one that decodes into a valid url wins — no JS engine needed.
     */
    private fun decodeKodikSource(encoded: String): String? {
        for (shift in 1..25) {
            val decoded = runCatching {
                String(Base64.decode(encoded.rotate(shift).padBase64(), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: continue

            if (decoded.startsWith("http") || decoded.startsWith("//")) {
                return decoded.toAbsoluteUrl()
            }
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

    private fun String.padBase64(): String {
        val remainder = length % 4
        return if (remainder == 0) this else this + "=".repeat(4 - remainder)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Предпочитаемое качество",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
        )

        screen.addSwitchPreference(
            key = PREF_IGNORE_SUBS_KEY,
            default = PREF_IGNORE_SUBS_DEFAULT,
            title = "Скрывать переводы субтитрами",
            summary = "Показывать только озвучки",
        )
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 720

    // =============================== Utils ================================

    /** Listing pages hang the number on the path tail: `/korea/page/2/`. */
    private fun listRequest(path: String, page: Int): Request {
        val clean = path.removeSuffix("/")
        val url = if (page > 1) "$baseUrl$clean/page/$page/" else "$baseUrl$clean/"

        return GET(url, headers)
    }

    /** Search is the stock DLE post; results are paged by `search_start`. */
    private fun searchRequest(query: String, page: Int): Request {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("full_search", "0")
            .add("search_start", page.toString())
            .add("result_from", ((page - 1) * PER_PAGE + 1).toString())
            .add("story", query.trim())
            .build()

        return POST("$baseUrl/index.php?do=search", headers, body)
    }

    private fun listParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("#dle-content .item-main")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        return AnimesPage(animes, animes.size >= PER_PAGE)
    }

    private fun Element.toSAnime(): SAnime? {
        val link = selectFirst(".item-main__title[href]") ?: return null
        val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return null

        return SAnime.create().apply {
            url = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)
            title = link.text().cleanTitle()
            thumbnail_url = selectFirst(".item-main__img img")?.absUrl("src")?.fullSizePoster()
            genre = selectFirst(".item__label")?.text()?.trim()
        }
    }

    /** Cards link the DLE thumbnail; the same file without `thumbs/` is the full poster. */
    private fun String.fullSizePoster(): String = replace(THUMBS_SEGMENT, "/")

    /** The player sits in the first iframe, either as `src` or lazily as `data-src`. */
    private fun Document.playerUrl(): String? = select("iframe")
        .firstNotNullOfOrNull { frame ->
            listOf(frame.attr("src"), frame.attr("data-src"))
                .firstOrNull { it.contains("kodik", ignoreCase = true) }
        }
        ?.toAbsoluteUrl()

    private fun Document.infoMap(): Map<String, String> = select(".item__list > li").mapNotNull { item ->
        val key = item.selectFirst("b")?.text()?.trim()?.removeSuffix(":") ?: return@mapNotNull null
        val value = item.text().substringAfter(':').trim()

        key to value
    }.toMap()

    private fun String.cleanTitle(): String = trim()
        .replace(TITLE_TAIL_REGEX, "")
        .trim()

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> "https://$this"
    }

    private fun kodikUrl(type: String, id: String, hash: String, season: String, episode: String): String {
        val builder = "https://kodikplayer.com/$type/$id/$hash/720p".toHttpUrl().newBuilder()
        if (season.isNotBlank()) builder.addQueryParameter("season", season)
        if (episode.isNotBlank()) builder.addQueryParameter("episode", episode)

        return builder.build().toString()
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val THUMBS_SEGMENT = "/thumbs/"

        private const val PER_PAGE = 18

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        private const val PREF_IGNORE_SUBS_DEFAULT = false

        private const val POSTER_SELECTOR = ".item-main__img img, .item-page__img img, .page__poster img"

        private val DETAIL_KEYS = listOf(
            "Все названия",
            "Год выпуска",
            "Количество серий",
            "Качество",
            "Русская озвучка",
        )

        private val EPISODE_NUMBER_REGEX = Regex("""(\d+)-seriya""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'(.*?)'""")

        // Some listings carry a typo'd or ranged year, e.g. "(20265)" / "(2024-2025)".
        private val TITLE_TAIL_REGEX = Regex("""\s*\(\d{4}\S*\)\s*$""")
    }
}
