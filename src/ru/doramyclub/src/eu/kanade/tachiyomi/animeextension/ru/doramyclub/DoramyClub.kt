package eu.kanade.tachiyomi.animeextension.ru.doramyclub

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

class DoramyClub :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "DoramyClub"

    override val baseUrl = "https://doramyclub.media"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )

    private fun ajaxHeaders() = headers.newBuilder()
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Accept", "application/json, text/plain, */*")
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = filterRequest(page, DoramyClubFilters.SearchParams(sort = "reads"))

    override fun popularAnimeParse(response: Response): AnimesPage = filterParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = filterRequest(page, DoramyClubFilters.SearchParams(sort = "date"))

    override fun latestUpdatesParse(response: Response): AnimesPage = filterParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        if (query.isNotBlank()) {
            // The site's own suggest endpoint returns the same cards as the filter, JSON and unpaged.
            val url = "$baseUrl/engine/ajax/controller.php".toHttpUrl().newBuilder()
                .addQueryParameter("mod", "anime_search")
                .addQueryParameter("q", query)
                .build()

            return GET(url, ajaxHeaders())
        }

        return filterRequest(page, DoramyClubFilters.getSearchParameters(filters))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.queryParameter("mod") == "anime_search") {
            val result = response.parseAs<SearchResponse>()
            return AnimesPage(result.items.map { it.toSAnime() }.distinctBy { it.url }, false)
        }

        return filterParse(response)
    }

    override fun getFilterList(): AnimeFilterList = DoramyClubFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        fun row(label: String): String? = document.select(".post-singl table tr")
            .firstOrNull { it.selectFirst("td")?.text()?.trim()?.removeSuffix(":")?.equals(label, true) == true }
            ?.select("td")
            ?.last()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.cleanTitle().orEmpty()
            thumbnail_url = document.selectFirst("img.s-poster[src]")?.absUrl("src")
            genre = document.select(".table-tag a[href*=/xtags/genre/]").joinToString(", ") { it.text() }
            author = row("Режиссер")
            artist = row("Озвучка")
            status = when (document.selectFirst(".status")?.text()?.trim()) {
                "Вышел", "Завершён", "Завершен" -> SAnime.COMPLETED
                "Выходит", "Онгоинг" -> SAnime.ONGOING
                "Анонс" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                document.selectFirst(".description .infotext")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        appendLine(it)
                        appendLine()
                    }
                document.selectFirst(".post-singl em")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appendLine("Оригинальное название: $it") }
                document.selectFirst(".table-tag u")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appendLine("Страна и год: $it") }
                document.selectFirst(".vozrast")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { appendLine(it) }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()

        val playerSrc = document.selectFirst("iframe[data-player-src]")?.attr("data-player-src")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Плеер не найден на странице тайтла")

        val episodes = document.select("a.ep-dc[href]")

        // Films have no episode strip: the title page player is the only video.
        if (episodes.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    url = "$playerSrc|"
                    episode_number = 1f
                    name = "Фильм"
                },
            )
        }

        return episodes.mapIndexed { index, element ->
            val number = element.text().trim().toFloatOrNull()
                ?: EPISODE_NUMBER_REGEX.find(element.attr("href"))?.groupValues?.get(1)?.toFloatOrNull()
                ?: (index + 1).toFloat()

            SEpisode.create().apply {
                url = "$playerSrc|${number.toInt()}"
                episode_number = number
                name = "${number.toInt()} серия"
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val playerUrl = episode.url.substringBefore('|').toAbsoluteUrl()
        val episodeValue = episode.url.substringAfter('|', "")

        val page = client.newCall(GET(playerUrl, headers)).awaitSuccess().bodyString()
        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)

        // Every voice-over is a separate Kodik "media" with its own id/hash.
        val translations = Jsoup.parse(page)
            .select(".serial-translations-box option")
            .mapNotNull { option ->
                val mediaId = option.attr("data-media-id").takeIf { it.isNotBlank() }
                val mediaHash = option.attr("data-media-hash").takeIf { it.isNotBlank() }
                val mediaType = option.attr("data-media-type").ifBlank { "serial" }
                if (mediaId == null || mediaHash == null) return@mapNotNull null

                Translation(
                    title = option.attr("data-title").trim().ifBlank { "Kodik" },
                    isSubtitles = option.attr("data-translation-type") == "subtitles",
                    url = "https://kodikplayer.com/$mediaType/$mediaId/$mediaHash/720p",
                )
            }
            .filterNot { ignoreSubs && it.isSubtitles }
            .ifEmpty { listOf(Translation("Kodik", false, playerUrl)) }

        val videos = translations.parallelCatchingFlatMap { translation ->
            translationVideos(translation, episodeValue)
        }

        if (videos.isEmpty()) throw Exception("Не удалось получить ссылки на видео для этой серии")

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private class Translation(val title: String, val isSubtitles: Boolean, val url: String)

    private suspend fun translationVideos(translation: Translation, episodeValue: String): List<Video> {
        val page = client.newCall(GET(translation.url, headers)).awaitSuccess().bodyString()

        val targetUrl = if (episodeValue.isBlank()) {
            translation.url
        } else {
            val option = Jsoup.parse(page)
                .select(".serial-series-box option")
                .firstOrNull { it.attr("value") == episodeValue }
                ?: return emptyList()

            val id = option.attr("data-id").takeIf { it.isNotBlank() } ?: return emptyList()
            val hash = option.attr("data-hash").takeIf { it.isNotBlank() } ?: return emptyList()
            "https://kodikplayer.com/seria/$id/$hash/720p"
        }

        val label = buildString {
            append(translation.title)
            if (translation.isSubtitles) append(" (субтитры)")
        }

        return kodikVideos(targetUrl, label)
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

    /** The catalog is rendered client-side; this is the endpoint its own filter calls. */
    private fun filterRequest(page: Int, params: DoramyClubFilters.SearchParams): Request {
        val url = "$baseUrl/engine/ajax/controller.php".toHttpUrl().newBuilder()
            .addQueryParameter("mod", "anime_filter")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("genre", params.genre)
            .addQueryParameter("country", params.country)
            .addQueryParameter("type", params.type)
            .addQueryParameter("status", params.status)
            .addQueryParameter("year", params.year)
            .addQueryParameter("sort", params.sort)
            .build()

        return GET(url, ajaxHeaders())
    }

    private fun filterParse(response: Response): AnimesPage {
        val result = response.parseAs<FilterResponse>()

        return AnimesPage(result.items.map { it.toSAnime() }.distinctBy { it.url }, result.hasMore)
    }

    private fun FilterItem.toSAnime(): SAnime = SAnime.create().apply {
        url = runCatching { this@toSAnime.url.toHttpUrl().encodedPath }.getOrDefault(this@toSAnime.url)
        title = this@toSAnime.title.cleanTitle()
        thumbnail_url = poster?.takeIf { it.isNotBlank() }
        genre = subtitle.takeIf { it.isNotBlank() }
    }

    private fun String.cleanTitle(): String = trim()
        .replace(TITLE_TAIL_REGEX, "")
        .trim()

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> "https://$this"
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        private const val PREF_IGNORE_SUBS_DEFAULT = false

        private val EPISODE_NUMBER_REGEX = Regex("""episode-(\d+)""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'(.*?)'""")

        // Some listings carry a typo'd or ranged year, e.g. "(20265)" / "(2024-2025)".
        private val TITLE_TAIL_REGEX = Regex("""\s*\(\d{4}\S*\)\s*$""")
    }
}
