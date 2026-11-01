package eu.kanade.tachiyomi.animeextension.ru.animaunt

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * AniMaunt (animaunt.org) — сгенерировано extension-forge.
 *
 * Каталог и детали разбираются из HTML, плеер берётся через relay сайта
 * и передаётся в KodikExtractor.
 */
class AniMaunt :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AniMaunt"

    override val baseUrl = "https://animaunt.org"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val kodik by lazy { KodikExtractor(client, headers, baseUrl, preferences) }

    /** id тайтла на сайте, по которому relay отдаёт плеер. */
    private var currentAnimeId: String = ""

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // =============================== Каталог ===============================

    private fun pageUrl(path: String, page: Int): String {
        if (page <= 1) return "$baseUrl$path"
        return "$baseUrl$path" + PAGE_SUFFIX.replace("{page}", page.toString())
    }

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl(CATALOG_PATH, page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(LATEST_PATH, page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun getFilterList() = eu.kanade.tachiyomi.animesource.model.AnimeFilterList(SectionsFilter())

    override fun searchAnimeRequest(page: Int, query: String, filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList): Request {
        val state = filters.filterIsInstance<SectionsFilter>().firstOrNull()?.state ?: 0
        val path = SECTIONS.getOrNull(state - 1)?.second ?: CATALOG_PATH
        if (query.isBlank()) return GET(pageUrl(path, page), headers)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl" + SEARCH_PATH.replace("{query}", encoded)
        return GET(url, headers)
    }

    /** Раздел каталога. Select абстрактный, поэтому подкласс, а не экземпляр. */
    private class SectionsFilter :
        eu.kanade.tachiyomi.animesource.model.AnimeFilter.Select<String>(
            "Раздел",
            SECTIONS.map { it.first }.toTypedArray(),
        )

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val html = response.bodyString()
        val linkFilter = Regex(LINK_FILTER)
        val animes = ANCHOR_REGEX.findAll(html)
            .mapNotNull { match ->
                val href = match.groupValues[1]
                if (!linkFilter.containsMatchIn(href)) return@mapNotNull null
                val path = href.substringAfter(baseUrl, href)
                val body = match.groupValues[2]
                SAnime.create().apply {
                    url = path
                    title = titleOfCard(body)
                    thumbnail_url = imageOf(body)?.let(::absoluteUrl)
                    initialized = false
                }
            }
            .filter { it.title.isNotBlank() && !it.title.equals("Подробнее", true) }
            .distinctBy { it.url }
            .toList()

        val nextPage = html.contains("${PAGE_MARKER}${response.request.url.toString().let { pageOf(it) + 1 }}")
        return AnimesPage(animes, nextPage)
    }

    private fun pageOf(url: String): Int = Regex("""(?:page/|page=|/)(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /** Заголовок карточки: alt картинки, вложенный .title, и только потом текст.
     *
     *  У DLE в тело ссылки попадают счётчики («14736 просмотров 28 9 12+»),
     *  поэтому текст берём последним и чистим от цифр статистики.
     */
    private fun titleOfCard(body: String): String {
        val alt = altOf(body).trim()
        if (alt.isNotEmpty()) return cleanTitle(alt)
        val nested = Regex(
            """<(?:h1|h2|h3|div|span)[^>]*class="[^"]*(?:title|name)[^"]*"[^>]*>(.*?)</""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(body)?.groupValues?.get(1)
        if (nested != null) {
            val clean = cleanTitle(textOf(nested))
            if (clean.isNotBlank()) return clean
        }
        return cleanTitle(textOf(body))
            .replace(Regex("""\s*\d+\s*просмотров.*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun textOf(fragment: String): String = Jsoup.parse(fragment).text()

    private fun altOf(fragment: String): String = Regex("""alt="([^"]*)"""").find(fragment)?.groupValues?.get(1).orEmpty()

    private fun imageOf(fragment: String): String? = Regex("""<img[^>]*src="([^"]+)"""").find(fragment)?.groupValues?.get(1)

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s*смотреть онлайн.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+аниме$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> "$baseUrl/$raw"
    }

    // =============================== Детали ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.bodyString()
        val doc = Jsoup.parse(html, baseUrl)

        currentAnimeId = Regex("""data-player-anime-id="(\d+)"""").find(html)?.groupValues?.get(1).orEmpty()

        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = cleanTitle(doc.selectFirst(DETAILS_TITLE)?.text().orEmpty()).ifBlank { "Без названия" }
            thumbnail_url = (metaOf(doc, "og:image") ?: doc.selectFirst(POSTER_SELECTOR)?.absUrl("src"))
                ?.let(::absoluteUrl)
            description = metaOf(doc, "og:description")
                ?: doc.selectFirst(DESC_SELECTOR)?.text()?.takeIf { it.length > 80 }
            // Своего поля «статус» у DLE нет, но есть «Эпизоды: 5 из 12».
            // Это факт со страницы: вышло меньше — онгоинг, все — завершён.
            val pageText = doc.text()
            val episodes = Regex("""Эпизоды:\s*(\d+)\s*из\s*(\d+)""").find(pageText)
            status = when {
                episodes == null -> SAnime.UNKNOWN
                episodes.groupValues[1] == episodes.groupValues[2] -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }
            genre = doc.select(GENRE_SELECTOR).map { it.text().trim() }
                .filter { it.isNotEmpty() && it.length < 24 }
                .distinct()
                .take(12)
                .joinToString(", ")
                .ifBlank { null }
            initialized = true
        }
    }

    private fun metaOf(doc: Document, property: String): String? {
        val byProperty = doc.selectFirst("""meta[property="$property"]""")?.attr("content")
        if (!byProperty.isNullOrBlank()) return byProperty
        return doc.selectFirst("""meta[name="$property"]""")?.attr("content")?.takeIf { it.isNotBlank() }
    }

    // =============================== Серии ================================

    override fun episodeListRequest(anime: SAnime): Request {
        if (PLAYER_MODE == "iframe") return GET(baseUrl + anime.url, headers)
        val id = currentAnimeId.ifBlank { relayId(anime.url) }
        return GET(relayUrl(id, 0), headers)
    }

    private fun relayUrl(id: String, slot: Int): String = baseUrl + RELAY_URL.replace("{id}", id).replace("{slot}", slot.toString())

    private fun relayId(path: String): String = runCatching {
        val html = client.newCall(GET(baseUrl + path, headers)).execute().use { it.bodyString() }
        Regex("""data-player-anime-id="(\d+)"""").find(html)?.groupValues?.get(1).orEmpty()
    }.getOrDefault("")

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (PLAYER_MODE == "iframe") {
            // Плеер лежит прямо на странице тайтла: серии отдельными ссылками
            // такие сайты не отдают, поэтому показываем одну серию.
            val html = runCatching { response.bodyString() }.getOrNull() ?: return emptyList()
            val iframe = IFRAME_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
            return listOf(
                SEpisode.create().apply {
                    url = absoluteUrl(iframe)
                    name = "Серия 1"
                    episode_number = 1f
                },
            )
        }

        // Сайт выдаёт разным сессиям разные плееры и раскладывает их по слотам,
        // поэтому перебираем слоты и берём Kodik, если он вообще предложен.
        val players = mutableListOf<String>()
        for (slot in 0 until MAX_SLOTS) {
            val src = playerAt(currentAnimeId, slot) ?: continue
            players += src
            if (src.contains("kodik", ignoreCase = true)) break
        }

        val kodikPlayer = players.firstOrNull { it.contains("kodik", ignoreCase = true) }
        if (kodikPlayer == null) {
            // Другой плеер (Alloha и подобные): отдаём одну серию, видео достанет getVideoList.
            val other = players.firstOrNull() ?: return emptyList()
            Log.e("Amedia", "не-Kodik плеер: " + other.take(80))
            // Playerjs перечисляет весь сезон на одной странице: у каждой серии
            // есть название и свой постер, поэтому список собираем из него.
            return playerjsEpisodes(other).ifEmpty {
                listOf(
                    SEpisode.create().apply {
                        url = other
                        name = "Серия 1"
                        episode_number = 1f
                    },
                )
            }
        }

        val page = runCatching {
            client.newCall(GET(kodikPlayer, playerHeaders())).execute()
                .use { it.bodyString() }
        }.onFailure { Log.e("Amedia", "player page error", it) }.getOrNull() ?: return emptyList()

        Log.e("Amedia", "page=" + page.length + " options=" + EPISODE_OPTION_REGEX.findAll(page).count())

        return EPISODE_OPTION_REGEX.findAll(page).mapNotNull { match ->
            val attrs = attrsOf(match.value)
            val number = attrs["value"]?.toFloatOrNull() ?: return@mapNotNull null
            val title = attrs["data-title"].orEmpty()
            SEpisode.create().apply {
                url = "$kodikPlayer#e=" + number.toEpisodeNumber()
                name = title.ifBlank { "Серия " + number.toEpisodeNumber() }
                episode_number = number
            }
        }
            .distinctBy { it.episode_number }
            .sortedByDescending { it.episode_number }
            .toList()
    }

    private fun Float.toEpisodeNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun playerAt(id: String, slot: Int): String? {
        val body = runCatching {
            client.newCall(GET(relayUrl(id, slot), headers)).execute().use { it.bodyString() }
        }.getOrNull() ?: return null
        return runCatching { JSONObject(body).optJSONObject("data")?.optString("src") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** Kodik отдаёт разметку только браузерному User-Agent. */
    private fun playerHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")
        .set("Origin", "https://kodikplayer.com")
        .build()

    /** Origin обязан совпадать с хостом плеера: иначе 403 и пустая страница. */
    private fun headersFor(url: String): Headers {
        val origin = runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull()
        return headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .set("Referer", "$baseUrl/")
            .apply { if (origin != null) set("Origin", origin) }
            .build()
    }

    private fun attrsOf(tag: String): Map<String, String> = Regex("""([a-z-]+)="([^"]*)"""").findAll(tag)
        .associate { it.groupValues[1] to it.groupValues[2] }

    // =============================== Видео ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val number = Regex("""#e=(\d+)""").find(episode.url)?.groupValues?.get(1)?.toFloatOrNull()
            ?: episode.episode_number
        val player = episode.url.substringBefore("#")
        val videos = if (player.contains("kodik", ignoreCase = true)) {
            kodik.videos(player, 1, number.toInt())
        } else {
            directVideos(player, number.toInt())
        }
        if (videos.isEmpty()) throw Exception("Плеер не вернул видео")
        videos
    }

    /** Не-Kodik плееры обычно кладут ссылки прямо в разметку.
     *
     *  Часто это строка вида «[HD (720p)]https://...mp4,[SD]https://...mp4»,
     *  поэтому берём пары «метка + адрес» и обрезаем адрес по расширению:
     *  иначе в url попадает «,[SD (480p)]https://...» и видео не играет.
     */
    private fun directVideos(player: String, number: Int): List<Video> = runCatching {
        val page = client.newCall(GET(player, headersFor(player))).execute().use { it.bodyString() }

        // Playerjs отдаёт сразу весь сезон: file:[{title,id,poster,file:"[320p]url,[480p]url"}…].
        // Берём объект нужной серии, иначе в список попадут качества всех серий подряд.
        playerjsEpisode(page, number)?.let { return@runCatching it }

        var found = mediaOf(page, player)
        if (found.isEmpty()) {
            // Плеер-обёртка: внутри ещё один iframe (openni, alloha и подобные).
            val nested = IFRAME_REGEX.find(page)?.groupValues?.get(1)
            if (nested != null) {
                val next = absoluteUrl(nested)
                val inner = client.newCall(GET(next, headersFor(next))).execute().use { it.bodyString() }
                found = mediaOf(inner, next)
            }
        }
        Log.e("Amedia", "direct links=" + found.size)
        found
    }.getOrElse {
        Log.e("Amedia", "direct error", it)
        emptyList()
    }

    /** Серии из Playerjs: название, номер и постер каждой лежат в разметке плеера. */
    private fun playerjsEpisodes(player: String): List<SEpisode> = runCatching {
        val page = client.newCall(GET(player, headersFor(player))).execute().use { it.bodyString() }
        val start = page.indexOf("file:[")
        if (start < 0) return emptyList()
        page.substring(start).split(OBJECT_SPLIT).mapIndexedNotNull { index, obj ->
            val stream = PLAYERJS_FILE.find(obj)?.groupValues?.get(1).orEmpty()
            if (stream.isBlank()) return@mapIndexedNotNull null
            SEpisode.create().apply {
                url = player + "#e=" + (index + 1)
                name = PLAYERJS_TITLE.find(obj)?.groupValues?.get(1)?.trim().orEmpty()
                    .ifBlank { "Серия " + (index + 1) }
                episode_number = (index + 1).toFloat()
            }
        }
    }.getOrDefault(emptyList())

    /** Серия из списка Playerjs: у каждой свой набор качеств и свой постер. */
    private fun playerjsEpisode(page: String, number: Int): List<Video>? {
        val start = page.indexOf("file:[")
        if (start < 0) return null
        val files = PLAYERJS_FILE.findAll(page.substring(start)).map { it.groupValues[1] }.toList()
        if (files.isEmpty()) return null
        // Номер серии у сайта начинается с единицы; если его нет — первая запись.
        val raw = files.getOrNull((number - 1).coerceAtLeast(0)) ?: files.first()
        return labeledOf(raw, page)
    }

    /** «[Mobile (320p)]url1 or url2,[HD (720p)]url3» → список видео. */
    private fun labeledOf(raw: String, referer: String): List<Video> {
        val text = raw.replace("""\/""", "/")
        return text.split(",").mapNotNull { part ->
            val match = LABELED_URL.find(part.trim()) ?: return@mapNotNull null
            val quality = match.groupValues[1].trim()
            val url = match.groupValues[2]
            Video(url, quality, url, headers = headersFor(referer))
        }.distinctBy { it.videoUrl }
    }

    /** Все медиа-ссылки страницы: base64-плейлист, метки качества, JSON-поля. */
    private fun mediaOf(page: String, referer: String): List<Video> {
        val decoded = ATOB_REGEX.find(page)?.groupValues?.get(1)?.let(::decodePlaylist).orEmpty()

        val labeled = LABELED_URL.findAll(page).map { match ->
            val url = match.groupValues[2]
            Video(url, match.groupValues[1].trim(), url, headers = headersFor(referer))
        }.toList()

        // playerjs кладёт ВСЕ качества одной строкой: file":"[Mobile (320p)]url,[SD (480p)]url"
        val fromJson = JSON_FILE_VALUE.findAll(page).flatMap { field ->
            val text = field.groupValues[1].replace("""\/""", "/")
            val pairs = LABELED_URL.findAll(text).map { match ->
                val url = match.groupValues[2]
                Video(url, match.groupValues[1].trim(), url, headers = headersFor(referer))
            }.toList()
            pairs.ifEmpty {
                PLAIN_URL.findAll(text).map { match ->
                    Video(match.value, "Видео", match.value, headers = headersFor(referer))
                }.toList()
            }
        }.toList()

        val plain = PLAIN_URL.findAll(page).map { match ->
            Video(match.value, "Видео", match.value, headers = headersFor(referer))
        }.toList()

        return (decoded + labeled + fromJson + plain).distinctBy { it.videoUrl }
    }

    /** intrdb и подобные прячут список качеств в base64: «[480p]url,[720p]url». */
    private fun decodePlaylist(encoded: String): List<Video> = runCatching {
        val text = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        text.split(",").mapNotNull { part ->
            val match = PLAYLIST_ITEM_REGEX.find(part.trim()) ?: return@mapNotNull null
            val quality = match.groupValues[1]
            val url = match.groupValues[2].replace("""\/""", "/")
            Video(url, quality, url, headers = playerHeaders())
        }
    }.getOrDefault(emptyList())

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

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val CATALOG_PATH = "/anime/"
        private const val LATEST_PATH = "/anime/"

        private val SECTIONS = listOf(
            "Апокалипсис" to "/anime/apokalipsis/",
            "Боевик" to "/anime/boevik/",
            "Боевые искусства" to "/anime/boevye-iskusstva/",
            "Вампиры" to "/anime/vampiry/",
            "Война" to "/anime/vojna/",
            "Гарем" to "/anime/garem/",
            "Детектив" to "/anime/detektiv/",
            "Детское" to "/anime/detskoe/",
            "Драма" to "/anime/drama/",
            "Игры" to "/anime/igry/",
            "Исторический" to "/anime/istoricheskij/",
            "Киберпанк" to "/anime/kiberpank/",
            "Комедия" to "/anime/komediya/",
            "Космос" to "/anime/kosmos/",
            "Магия" to "/anime/magia/",
            "Меха" to "/anime/meha/",
            "Мистика" to "/anime/mistika/",
            "Музыка" to "/anime/muzyka/",
            "Пародия" to "/anime/parodiya/",
            "Повседневность" to "/anime/povsednevnost/",
            "Приключения" to "/anime/priklyucheniya/",
            "Психология" to "/anime/psihologiya/",
            "Романтика" to "/anime/romantika/",
            "Сверхъестественное" to "/anime/sverhestestvennoe/",
        )

        private val GENRES = listOf(
            "Все" to "",
        )

        private const val SEARCH_PATH = "/?do=search&subaction=search&story={query}"
        private const val PAGE_SUFFIX = "page/{page}/"
        private const val PAGE_MARKER = "page/"
        private const val LINK_FILTER = """/\d+-[a-z0-9\-]+\.html"""

        private const val DETAILS_TITLE = "h1"
        private const val POSTER_SELECTOR = "img"
        private const val DESC_SELECTOR = ".fdesc.full-text"
        private const val GENRE_SELECTOR = "a[href*='/genres/']"

        private const val RELAY_URL = "/engine/ajax/controller.php?mod=player&id={id}&slot={slot}"
        private const val MAX_SLOTS = 4
        private const val PLAYER_MODE = "iframe"

        // Плеер лежит либо в <iframe src>, либо в data-iframe у кнопки серии.
        // «[HD (720p)]https://host/video/720/1.mp4» — метка и адрес.
        private val JSON_FILE_VALUE = Regex("""file"\s*:\s*"([^"]+)"""")

        private val JSON_MEDIA_URL = Regex(
            """(?:file|src|url)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4)[^"']*)""",
            RegexOption.IGNORE_CASE,
        )

        private val PLAYERJS_TITLE = Regex(""""?title"?\s*:\s*"([^"]*)""")
        private val OBJECT_SPLIT = Regex("""\}\s*,\s*\{""")
        private val PLAYERJS_FILE = Regex("""file"\s*:\s*"([^"]*)"""")
        private val LABELED_URL = Regex(
            """\[([^\]\[]{2,24})\]\s*(https?://[^"'\s,\]]+?\.(?:m3u8|mp4))""",
            RegexOption.IGNORE_CASE,
        )
        private val PLAIN_URL = Regex(
            """https?://[^"'\s,\]]+?\.(?:m3u8|mp4)""",
            RegexOption.IGNORE_CASE,
        )
        private val IFRAME_REGEX = Regex(
            """(?:<iframe[^>]*src="|data-iframe=")([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        private val ATOB_REGEX = Regex("""file:\s*atob\("([^"]+)"\)""")
        private val PLAYLIST_ITEM_REGEX = Regex("""\[([^\]]+)](https?://\S+)""")

        private val ANCHOR_REGEX = Regex(
            """<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val EPISODE_OPTION_REGEX = Regex("""<option[^>]*data-hash="[^"]+"[^>]*>""", RegexOption.IGNORE_CASE)
    }
}
