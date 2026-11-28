package eu.kanade.tachiyomi.animeextension.ru.lakornland

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
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
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.catchingFlatMap
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class LakornLand :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Lakorn.land"

    override val baseUrl = "https://m.lakornland.top"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    /** Player pages are heavy and the episode list + videos both need them. */
    private val playlistCache = ConcurrentHashMap<String, Map<String, Map<String, List<Track>>>>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36",
        )

    // ============================== Popular ===============================

    /** The site's own curated "best of" page, the same one it links as Popular. */
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/top", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response, false)

    // =============================== Latest ===============================

    /** Fresh releases page: 2026 newcomers, different from the curated popular list. */
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/new-content", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response, false)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            // The site keeps the full result list behind /search/full, no paging there.
            val url = "$baseUrl/search/full".toHttpUrl().newBuilder()
                .addQueryParameter("text", query.trim())
                .build()

            return GET(url, headers)
        }

        val params = LakornLandFilters.getSearchParameters(filters)
        val builder = "$baseUrl/api/v1/cinema".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PER_PAGE.toString())
            .addQueryParameter("offset", ((page - 1) * PER_PAGE).toString())

        params.minYear?.let { builder.addQueryParameter("min_year", it.toString()) }
        params.maxYear?.let { builder.addQueryParameter("max_year", it.toString()) }
        params.minRating?.let { builder.addQueryParameter("min_rating", it.toString()) }
        params.countryId?.let { builder.addQueryParameter("country_id", it.toString()) }
        params.status?.let { builder.addQueryParameter("status", it.toString()) }
        params.genreId?.let { builder.addQueryParameter("genre_id", it.toString()) }
        params.soundId?.let { builder.addQueryParameter("sound_id", it.toString()) }
        params.typeId?.let { builder.addQueryParameter("type_id", it.toString()) }

        return GET(builder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // Filters and plain catalogue paging go through the site's own JSON listing.
        if (response.request.url.encodedPath.endsWith("/api/v1/cinema")) {
            val html = runCatching { JSONObject(response.bodyString()).optString("html") }.getOrDefault("")
            val animes = parseListCards(Jsoup.parse(html))
            return AnimesPage(animes, animes.size >= PER_PAGE)
        }

        val document = response.useAsJsoup()
        val animes = document.select("a.one-serials-horizontal")
            .mapNotNull { it.toSearchAnime() }
            .distinctBy { it.url }

        return AnimesPage(animes, false)
    }

    override fun getFilterList(): AnimeFilterList = LakornLandFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        val info = document.select(".lakorn-serial__info-list li")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val genres = document.select(".genres-list").text().trim()
        val countryYear = info.firstOrNull { INFO_YEAR_REGEX.containsMatchIn(it) }
        val statusLine = info.firstOrNull { it in STATUSES }
        val episodesLine = info.firstOrNull { INFO_EPISODES_REGEX.containsMatchIn(it) }
        val voicesLine = info.firstOrNull { line ->
            line != countryYear && line != statusLine && line != episodesLine && line != genres &&
                line.contains(',')
        }

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1")?.text()?.cleanTitle().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: document.selectFirst(".lakorn-serial__poster img")?.coverUrl()
            genre = genres.ifBlank { null }
            author = voicesLine
            status = when {
                statusLine?.startsWith("Заверш") == true -> SAnime.COMPLETED
                statusLine?.startsWith("Выходит") == true -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                document.select(".lakorn-serial__about-text-inner p").forEach { paragraph ->
                    val text = paragraph.text().trim()
                    if (text.isNotBlank()) appendLine(text).appendLine()
                }

                listOfNotNull(
                    countryYear?.let { "Страна и год: $it" },
                    statusLine?.let { "Статус: $it" },
                    episodesLine?.let { "Серии: $it" },
                    voicesLine?.let { "Озвучки: $it" },
                ).forEach { appendLine(it) }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Episodes come from the player playlist")

    /**
     * The serial page only embeds the player; the real season/episode grid lives in the
     * player's `#inputData` JSON, so the default one-request flow is not enough here.
     */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val playerUrl = resolvePlayerUrl(anime.url) ?: throw Exception("Плеер не найден на странице")
        val playlist = playlistFor(playerUrl)
        val seasons = playlist.keys.sortedBy { it.toIntOrNull() ?: 0 }
        val isFilm = seasons.size == 1 && playlist.values.firstOrNull()?.size == 1

        val episodes = buildList {
            seasons.forEach { season ->
                val episodesOfSeason = playlist[season].orEmpty()
                val numbers = episodesOfSeason.keys.sortedBy { it.toIntOrNull() ?: 0 }
                numbers.forEach { number ->
                    val tracks = episodesOfSeason[number].orEmpty()
                    if (tracks.isEmpty()) return@forEach

                    val seasonNumber = season.toIntOrNull() ?: 1
                    val episodeNumber = number.toFloatOrNull() ?: 1f
                    add(
                        SEpisode.create().apply {
                            url = episodeUrl(playerUrl, season, number)
                            episode_number = episodeNumber
                            name = when {
                                isFilm -> "Фильм"
                                seasons.size > 1 -> "Сезон $seasonNumber • $number серия"
                                else -> "$number серия"
                            }
                        },
                    )
                }
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (playerUrl, season, number) = decodeEpisodeUrl(episode.url)
        val warped = playlistFor(playerUrl)[season]?.get(number)
            ?: throw Exception("Серия не найдена в плеере")

        val hideSubtitles = preferences.getBoolean(PREF_HIDE_SUBTITLES_KEY, PREF_HIDE_SUBTITLES_DEFAULT)
        val tracks = warped.filterNot { hideSubtitles && it.isSubtitles }
            .ifEmpty { warped }

        val origin = originOf(playerUrl)
        // The player host is rate-limited, so voices are resolved one by one.
        val videos = tracks.catchingFlatMap { track ->
            videosFor(track, origin)
        }

        if (videos.isEmpty()) throw Exception("Не удалось получить ссылки на видео")

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private suspend fun videosFor(track: Track, origin: String): List<Video> {
        val request = GET("$origin/player/responce.php?video_id=${track.id}", headers)
        val body = client.newCall(request).awaitSuccess().bodyString()
        val src = runCatching { JSONObject(body).optString("src") }.getOrDefault("")
            .takeIf { it.startsWith("http") }
            ?: return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl = src,
            referer = "$origin/",
            videoNameGen = { quality -> "${track.name} - $quality" },
        ).ifEmpty {
            listOf(Video(src, track.name, src))
        }
    }

    // ============================== Player ================================

    private suspend fun resolvePlayerUrl(path: String): String? {
        val document = client.newCall(GET(baseUrl + path, headers)).awaitSuccess().useAsJsoup()
        val raw = document.selectFirst("#player[data-player]")?.attr("data-player")?.trim()
            ?: document.select("iframe[data-player]").firstOrNull()?.attr("data-player")?.trim()
            ?: return null

        return when {
            raw.isBlank() -> null
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> "$baseUrl$raw"
        }
    }

    private suspend fun playlistFor(playerUrl: String): Map<String, Map<String, List<Track>>> = playlistCache[playerUrl] ?: run {
        val page = client.newCall(GET(playerUrl, playerHeaders(playerUrl))).awaitSuccess().bodyString()
        val json = extractJson(page, "#inputData") ?: throw Exception("Плейлист плеера не найден")
        parsePlaylist(json).also { playlistCache[playerUrl] = it }
    }

    private fun playerHeaders(playerUrl: String) = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", originOf(playerUrl))
        .build()

    private fun parsePlaylist(json: String): Map<String, Map<String, List<Track>>> {
        val root = JSONObject(json)
        val result = LinkedHashMap<String, Map<String, List<Track>>>()

        root.keys().forEach { seasonKey ->
            val season = root.optJSONObject(seasonKey) ?: return@forEach
            val episodes = LinkedHashMap<String, List<Track>>()

            season.keys().forEach episode@{ episodeKey ->
                val tracks = season.optJSONArray(episodeKey) ?: return@episode
                val parsed = (0 until tracks.length()).mapNotNull track@{ index ->
                    val item = tracks.optJSONObject(index) ?: return@track null
                    val id = item.optString("video_id").takeIf { it.isNotBlank() } ?: return@track null

                    Track(
                        id = id,
                        name = item.optString("voice_name").trim().ifBlank { "Озвучка" },
                    )
                }

                if (parsed.isNotEmpty()) episodes[episodeKey] = parsed
            }

            if (episodes.isNotEmpty()) result[seasonKey] = episodes
        }

        return result
    }

    private fun episodeUrl(playerUrl: String, season: String, number: String): String = listOf(playerUrl, season, number).joinToString(EPISODE_SEPARATOR)

    private fun decodeEpisodeUrl(url: String): Triple<String, String, String> {
        val parts = url.split(EPISODE_SEPARATOR)
        if (parts.size < 3) throw Exception("Повреждённая ссылка на серию")

        val number = parts.last()
        val season = parts[parts.size - 2]
        val playerUrl = parts.dropLast(2).joinToString(EPISODE_SEPARATOR)

        return Triple(playerUrl, season, number)
    }

    private fun originOf(url: String): String = runCatching {
        val parsed = url.toHttpUrl()
        "${parsed.scheme}://${parsed.host}"
    }.getOrDefault("https://w.davidog.top")

    /**
     * The player keeps its whole playlist as one JSON text node inside `#inputData`;
     * it is pulled out by brace matching so string escaping never breaks parsing.
     */
    private fun extractJson(text: String, marker: String): String? {
        val markerIndex = text.indexOf(marker)
        if (markerIndex < 0) return null

        val start = text.indexOf('{', markerIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val char = text[index]

            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }

        return null
    }

    private data class Track(val id: String, val name: String) {
        val isSubtitles: Boolean
            get() = name.contains("Субтитр", ignoreCase = true) ||
                name.contains("Автосабы", ignoreCase = true) ||
                name.contains("субтитры", ignoreCase = true)
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
            key = PREF_HIDE_SUBTITLES_KEY,
            default = PREF_HIDE_SUBTITLES_DEFAULT,
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

    private fun listParse(response: Response, hasMore: Boolean): AnimesPage {
        val document = response.useAsJsoup()
        val animes = parseListCards(document)
        return AnimesPage(animes, hasMore && animes.isNotEmpty())
    }

    private fun parseListCards(document: Document): List<SAnime> = document
        .select("a.lakorn[href]")
        .mapNotNull { it.toSAnime() }
        .distinctBy { it.url }

    private fun Element.toSAnime(): SAnime? {
        val href = absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val name = selectFirst(".lakorn__name")?.text()?.trim()
        val title = name?.takeIf { it.isNotBlank() }
            ?: attr("title").removePrefix("Лакорн ").removePrefix("Дорама ").trim()

        if (title.isBlank()) return null

        return SAnime.create().apply {
            url = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)
            this.title = title
            thumbnail_url = selectFirst(".lakorn__cover img")?.coverUrl()
        }
    }

    private fun Element.toSearchAnime(): SAnime? {
        val href = absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".name span")?.text()?.trim()
            ?: selectFirst(".box-img")?.attr("title")?.trim()
            ?: return null

        if (title.isBlank()) return null

        return SAnime.create().apply {
            url = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)
            this.title = title
            thumbnail_url = selectFirst(".box-img img")?.coverUrl()
            genre = selectFirst(".description p:nth-of-type(2)")?.text()?.trim()?.ifBlank { null }
        }
    }

    /** Covers are lazy-loaded: the real url sits in `data-srcset`, the highest density wins. */
    private fun Element.coverUrl(): String? {
        val srcset = attr("data-srcset").ifBlank { attr("srcset") }
        val candidate = srcset.split(",")
            .map { it.trim().substringBefore(" ") }
            .lastOrNull { it.startsWith("http") }
            ?: attr("data-src").takeIf { it.startsWith("http") }
            ?: absUrl("src").takeIf { it.startsWith("http") }

        return candidate
    }

    private fun String.cleanTitle(): String {
        var title = substringBefore(" смотреть онлайн").trim()
        title = title.replace(SEO_TAIL_REGEX, "").trim()
        return title
    }

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val PER_PAGE = 30
        private const val EPISODE_SEPARATOR = "|"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_HIDE_SUBTITLES_KEY = "pref_hide_subtitles"
        private const val PREF_HIDE_SUBTITLES_DEFAULT = false

        private val STATUSES = setOf("Выходит", "Завершен", "Завершён", "В ожидании")
        private val INFO_YEAR_REGEX = Regex("""\d{4}\s*(?:год|–|-)\s*""")
        private val INFO_EPISODES_REGEX = Regex("""\d+\s*из\s*\d+\s*сери""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val SEO_TAIL_REGEX = Regex(
            """\s+(?:(?:лакорн|дорама|сериал|фильм)\s+)?\d{4}(?:[–-]\d{4})?\s*$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
