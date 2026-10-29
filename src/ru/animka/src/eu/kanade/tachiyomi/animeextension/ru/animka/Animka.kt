package eu.kanade.tachiyomi.animeextension.ru.animka

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class Animka :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animka"

    override val baseUrl = "https://animka.life"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val kodik by lazy { KodikExtractor(client, headers, baseUrl, preferences) }

    private val dateFormat by lazy { SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH) }

    @Volatile private var guardCheckedAt = 0L

    private val guardLock = Any()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // ============================== Защита сайта ==============================

    /** Сайт отдаёт JS-челлендж с proof-of-work: проходим его и держим cookie. */
    private fun ensureGuard(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - guardCheckedAt < GUARD_TTL_MS) return
        synchronized(guardLock) {
            if (!force && System.currentTimeMillis() - guardCheckedAt < GUARD_TTL_MS) return
            runCatching {
                var response = client.newCall(GET("$baseUrl/", headers)).execute()
                var hops = 0
                while (response.request.url.encodedPath.startsWith(CHALLENGE_PATH) && hops < 3) {
                    val challengeUrl = response.request.url.toString()
                    val body = response.body?.string().orEmpty()
                    response.close()
                    val token = TOKEN_REGEX.find(body)?.groupValues?.get(1) ?: break
                    solveChallenge(token, challengeUrl)
                    response = client.newCall(GET("$baseUrl/", headers)).execute()
                    hops++
                }
                response.close()
            }
            guardCheckedAt = System.currentTimeMillis()
        }
    }

    private fun solveChallenge(token: String, challengeUrl: String) {
        val (nonce, hash) = solveProofOfWork(token)
        val body = okhttp3.FormBody.Builder()
            .add("token", token)
            .add("mode", "modern")
            .add("workTime", "150")
            .add("iterations", nonce.toString())
            .add("hasCrypto", "1")
            .add("pow_nonce", nonce.toString())
            .add("pow_hash", hash)
            .add("webdriver", "0")
            .add("touch", "0")
            .add("screen_w", "1080")
            .add("screen_h", "2400")
            .add("screen_cd", "24")
            .add("tz", "-180")
            .add("dpr", "1")
            .add("cdp", "0")
            .add("cdpf", "")
            .build()
        val request = Request.Builder()
            .url("$baseUrl$VERIFY_PATH")
            .post(body)
            .headers(headersBuilder().set("Referer", challengeUrl).build())
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    private fun solveProofOfWork(token: String): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (nonce < MAX_POW_ITERATIONS) {
            val hash = digest.digest("$token:$nonce".toByteArray()).toHex()
            if (hash.startsWith(POW_PREFIX)) return nonce to hash
            nonce++
        }
        return nonce to digest.digest("$token:$nonce".toByteArray()).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // =============================== Каталог ===============================

    override fun popularAnimeRequest(page: Int): Request {
        ensureGuard()
        return GET("$baseUrl/anime/?sort=popular&page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = animeListParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        ensureGuard()
        return GET("$baseUrl/anime/?sort=new&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = animeListParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        ensureGuard()
        val params = AnimkaFilters.getSearchParameters(filters)
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
        } else {
            "$baseUrl/anime/".toHttpUrl().newBuilder().apply {
                params.genres.forEach { addQueryParameter("genres", it) }
                params.exclude.forEach { addQueryParameter("exclude", it) }
                if (params.type.isNotEmpty()) addQueryParameter("type", params.type)
                if (params.status.isNotEmpty()) addQueryParameter("status", params.status)
                if (params.sort.isNotEmpty()) addQueryParameter("sort", params.sort)
                if (params.year.isNotEmpty()) addQueryParameter("year", params.year)
                addQueryParameter("page", page.toString())
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = animeListParse(response)

    override fun getFilterList(): AnimeFilterList = AnimkaFilters.FILTER_LIST

    private fun animeListParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        val animes = document.select("article.anime-card").mapNotNull { it.toSAnimeOrNull() }
        val hasNext = document.selectFirst("a.page-link[rel=next]") != null
        return AnimesPage(animes, hasNext)
    }

    private fun Element.toSAnimeOrNull(): SAnime? {
        val link = selectFirst("a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (!href.contains("/anime/")) return null
        val slug = href.trim().trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null
        val title = selectFirst("h3.card-title")?.text()?.trim()
            ?: link.attr("title").trim()
        if (title.isBlank()) return null
        return SAnime.create().apply {
            url = slug
            this.title = title
            thumbnail_url = selectFirst("img")?.let { imageUrl(it) }
        }
    }

    // =============================== Детали ===============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        ensureGuard()
        return GET("$baseUrl/anime/${anime.url}/", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        return SAnime.create().apply {
            url = response.request.url.pathSegments.let { parts ->
                val index = parts.indexOf("anime")
                if (index >= 0 && parts.size > index + 1) parts[index + 1] else ""
            }
            title = document.selectFirst("h1.film-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("div.film-poster img")?.let { imageUrl(it) }
            description = document.selectFirst("section.film-description p")?.text()?.trim()
            genre = document.select("table.film-meta-table a[href*=genre], section.film-genres a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            status = convertStatus(filmMeta(document, "Статус"))
            author = filmMeta(document, "Студии")
            artist = filmMeta(document, "Режиссёр").ifBlank { null }
            initialized = true
        }
    }

    private fun filmMeta(document: Document, label: String): String {
        document.select("table.film-meta-table tr").forEach { row ->
            val cells = row.select("td")
            if (cells.size >= 2 && cells[0].text().trim().equals(label, ignoreCase = true)) {
                return cells[1].text().trim()
            }
        }
        return ""
    }

    private fun convertStatus(status: String): Int = when {
        status.contains("Выходит", true) -> SAnime.ONGOING
        status.contains("Вышел", true) -> SAnime.COMPLETED
        status.contains("Заморожен", true) -> SAnime.ON_HIATUS
        status.contains("Прекращ", true) -> SAnime.CANCELLED
        else -> SAnime.UNKNOWN
    }

    // =============================== Серии ===============================

    override fun episodeListRequest(anime: SAnime): Request {
        ensureGuard()
        return GET("$baseUrl/anime/${anime.url}/", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        val slug = response.request.url.pathSegments.let { parts ->
            val index = parts.indexOf("anime")
            if (index >= 0 && parts.size > index + 1) parts[index + 1] else ""
        }
        val seasons = document.select("details.ep-season[data-season]")
        val episodes = mutableListOf<SEpisode>()

        seasons.forEach { season ->
            val seasonNumber = season.attr("data-season").toIntOrNull() ?: return@forEach
            val rows = if (season.hasAttr("data-full")) {
                season.select("table.ep-table tbody tr")
            } else {
                runCatching {
                    val lazyUrl = "$baseUrl/anime/$slug/?ep_season=$seasonNumber"
                    val lazyResponse = client.newCall(GET(lazyUrl, headers)).execute()
                    val lazyDoc = Jsoup.parse(lazyResponse.bodyString(), baseUrl)
                    lazyResponse.close()
                    lazyDoc.select("details.ep-season[data-season=$seasonNumber] table.ep-table tbody tr")
                }.getOrNull().orEmpty().ifEmpty { season.select("table.ep-table tbody tr") }
            }
            val parsed = rows.mapNotNull { it.toSEpisodeOrNull(slug, seasonNumber, seasons.size) }
            episodes += parsed.reversed()
        }
        return episodes
    }

    private fun Element.toSEpisodeOrNull(slug: String, season: Int, seasonCount: Int): SEpisode? {
        val number = selectFirst("td.ep-num")?.text()?.trim()?.toFloatOrNull() ?: return null
        val title = selectFirst("td.ep-title")?.text()?.trim().orEmpty()
        val date = selectFirst("td.ep-date")?.text()?.trim().orEmpty()

        return SEpisode.create().apply {
            url = "$baseUrl/anime/$slug/?s=$season&e=${number.toInt()}"
            name = buildString {
                if (seasonCount > 1) append("$season сезон · ")
                append("Серия ${number.toInt()}")
                if (title.isNotEmpty() && !title.equals("TBA", true) && title != "-") {
                    append(" — ").append(title)
                }
            }
            episode_number = number
            date_upload = runCatching { dateFormat.parse(date)?.time }.getOrNull() ?: 0L
        }
    }

    // =============================== Видео ===============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        ensureGuard()
        val response = client.newCall(GET(episode.url, headers)).execute()
        val document = Jsoup.parse(response.bodyString(), baseUrl)
        response.close()

        val url = episode.url.toHttpUrl()
        val season = url.queryParameter("s")?.toIntOrNull() ?: 0
        val episodeNumber = url.queryParameter("e")?.toIntOrNull() ?: 0

        val section = document.selectFirst("section.player-section")
        val playerUrl = section?.attr("data-player-url")?.takeIf { it.isNotBlank() }
            ?: section?.attr("data-base")?.takeIf { it.isNotBlank() }
            ?: throw Exception("Не найден плеер на странице")

        if (!playerUrl.contains("kodik", true)) throw Exception("Неизвестный плеер: $playerUrl")

        val videos = kodik.videos(playerUrl, season, episodeNumber)
        if (videos.isEmpty()) throw Exception("Kodik не вернул видео")
        videos
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

    // ============================== Настройки ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = KodikExtractor.PREF_USE_MAX_QUALITY
            title = "Максимальное качество"
            summary = "Для каждой озвучки выбирается лучшее доступное качество"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(KodikExtractor.PREF_USE_MAX_QUALITY, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = KodikExtractor.PREF_QUALITY
            title = "Предпочитаемое качество"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(KodikExtractor.PREF_QUALITY, setOf(newValue as String)).apply()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = KodikExtractor.PREF_IGNORE_SUBS
            title = "Игнорировать субтитры"
            summary = "Показывать только озвучки"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(KodikExtractor.PREF_IGNORE_SUBS, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = KodikExtractor.PREF_DUB_TEAM
            title = "Предпочитаемые озвучки"
            summary = "Названия озвучек через запятую (пусто — все)"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(KodikExtractor.PREF_DUB_TEAM, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val CHALLENGE_PATH = "/_c"
        private const val VERIFY_PATH = "/_v"
        private const val POW_PREFIX = "00"
        private const val MAX_POW_ITERATIONS = 20_000_000L
        private const val GUARD_TTL_MS = 5 * 60 * 1000L
        private val TOKEN_REGEX = Regex("""token:\s*"([^"]+)"""")
    }
}
