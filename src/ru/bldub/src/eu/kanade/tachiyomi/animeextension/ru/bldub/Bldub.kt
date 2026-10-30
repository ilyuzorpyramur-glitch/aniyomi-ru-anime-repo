package eu.kanade.tachiyomi.animeextension.ru.bldub

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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Bldub :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "BLDUB"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36",
        )

    /** The whole site is a React app talking to this one endpoint; `a` selects the action. */
    private fun apiUrl(action: String) = "$baseUrl/api/v3/index.php".toHttpUrl().newBuilder()
        .addQueryParameter("a", action)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = titlesRequest(page, "", BldubFilters.SearchParams(sort = "5"))

    override fun popularAnimeParse(response: Response): AnimesPage = titlesParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = titlesRequest(page, "", BldubFilters.SearchParams(sort = "1"))

    override fun latestUpdatesParse(response: Response): AnimesPage = titlesParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = titlesRequest(page, query, filters.toParams())

    override fun searchAnimeParse(response: Response): AnimesPage = titlesParse(response)

    override fun getFilterList(): AnimeFilterList = BldubFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = idRequest("GetTitleDetail", anime.titleId)

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/title/${anime.titleId}"

    override fun animeDetailsParse(response: Response): SAnime {
        val detail = response.parseAs<TitleDetail>()

        return SAnime.create().apply {
            url = "/title/${detail.id}"
            title = detail.title
            thumbnail_url = detail.poster
            genre = (detail.genres + listOfNotNull(detail.ratingType?.takeIf { it.isNotBlank() }))
                .distinct()
                .joinToString(", ")
            status = when (detail.status) {
                "Завершён", "Завершен" -> SAnime.COMPLETED
                "Выходит" -> SAnime.ONGOING
                "Скоро" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                detail.description?.stripHtml()?.takeIf { it.isNotBlank() }?.let {
                    appendLine(it)
                    appendLine()
                }
                detail.altTitle?.takeIf { it.isNotBlank() }?.let { appendLine("Другие названия: $it") }
                listOfNotNull(detail.country, detail.year?.toString())
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { appendLine("Страна и год: ${it.joinToString(", ")}") }
                detail.type?.takeIf { it.isNotBlank() }?.let { appendLine("Тип: $it") }
                detail.episodes?.takeIf { it.isNotBlank() }?.let { appendLine("Серии: $it") }
                detail.couple?.takeIf { it.isNotBlank() }?.let { appendLine("Пара: $it") }
                detail.translations.takeIf { it.isNotEmpty() }
                    ?.let { appendLine("Переводы: ${it.joinToString(", ")}") }
                detail.rating?.takeIf { it > 0 }?.let { appendLine("Рейтинг: $it") }
                detail.access?.takeIf { it.isNotBlank() }?.let { appendLine("Доступ: $it") }
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = idRequest("GetTitleEpisodes", anime.titleId)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val items = response.parseAs<List<EpisodeItem>>()
        val hidePremium = preferences.getBoolean(PREF_HIDE_PREMIUM_KEY, PREF_HIDE_PREMIUM_DEFAULT)

        // One episode number can have several rows — one per translation; merge them
        // into a single entry so every voice-over shows up as a video choice.
        return items
            .groupBy { it.episode }
            .map { (number, rows) ->
                val usable = rows.filterNot { hidePremium && it.premium == 1 }.ifEmpty { rows }
                val isPremium = usable.all { it.premium == 1 }

                SEpisode.create().apply {
                    url = usable.joinToString(",") { "${it.id}|${it.translation}" }
                    episode_number = number.toFloat()
                    name = buildString {
                        append("$number серия")
                        if (isPremium) append(" 🔒")
                    }
                    scanlator = usable.joinToString(", ") { it.translation }
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val sources = episode.url.split(',')
            .mapNotNull { entry ->
                val id = entry.substringBefore('|').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to entry.substringAfter('|', "BLDUB")
            }

        val videos = sources.parallelCatchingFlatMap { (id, label) -> sourceVideos(id, label) }

        if (videos.isEmpty()) {
            throw Exception("Видео недоступно — серия платная или требует вход на сайте")
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.parseQuality() == preferredQuality }
                .thenByDescending { it.videoTitle.parseQuality() },
        )
    }

    private suspend fun sourceVideos(episodeId: String, label: String): List<Video> {
        val url = apiUrl("GetVideo").addQueryParameter("id", episodeId).build()
        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString().trim()

        // Paid or locked rows answer with a json error instead of a player url.
        if (!body.startsWith("http")) return emptyList()

        // The site's own player substitutes the key placeholder before loading the frame.
        val playerUrl = body.replace(KEY_PLACEHOLDER, "nokey")
        val playerHost = runCatching { playerUrl.toHttpUrl().host }.getOrNull() ?: return emptyList()

        val playerHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        val page = client.newCall(GET(playerUrl, playerHeaders)).awaitSuccess().bodyString()

        val file = PLAYERJS_FILE_REGEX.find(page)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        // Playerjs list: "[480p]url,[720p]url"; the host comes from window.location.
        return file.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .flatMap { entry ->
                val quality = PLAYERJS_LABEL_REGEX.find(entry)?.groupValues?.get(1)?.trim().orEmpty()
                val link = entry.substringAfter(']').trim().replace(HOSTNAME_PLACEHOLDER, playerHost)
                if (!link.startsWith("http")) return@flatMap emptyList()

                val name = listOf(label, quality).filter { it.isNotBlank() }.joinToString(" - ")
                val videoHeaders = headers.newBuilder()
                    .set("Referer", "https://$playerHost/")
                    .build()

                // Each quality is already a media playlist, so there is no master to expand.
                listOf(Video(link, name, link, headers = videoHeaders))
            }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            default = PREF_DOMAIN_DEFAULT,
            title = "Домен сайта",
            summary = "%s\nЗеркала для РФ — bldub.live. Требуется перезапуск приложения",
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_ENTRIES,
            restartRequired = true,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Предпочитаемое качество",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
        )

        screen.addSwitchPreference(
            key = PREF_HIDE_PREMIUM_KEY,
            default = PREF_HIDE_PREMIUM_DEFAULT,
            title = "Скрывать платные озвучки",
            summary = "Для платных серий остаются только бесплатные переводы",
        )
    }

    private val preferredQuality: Int
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: 720

    // =============================== Utils ================================

    private fun AnimeFilterList.toParams() = BldubFilters.getSearchParameters(this)

    private fun idRequest(action: String, id: String): Request = GET(apiUrl(action).addQueryParameter("id", id).build(), headers)

    private fun titlesRequest(page: Int, query: String, params: BldubFilters.SearchParams): Request {
        val builder = apiUrl("GetTitles")
            // The api pages from zero.
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("sort", params.sort)

        if (query.isNotBlank()) builder.addQueryParameter("text", query)

        val filter = buildList {
            params.genre.addTo(this, "genre")
            params.country.addTo(this, "country")
            params.type.addTo(this, "type")
            params.status.addTo(this, "status")
            params.year.addTo(this, "year")
            params.couple.addTo(this, "couple")
        }

        if (filter.isNotEmpty()) {
            builder.addQueryParameter("filter", filter.joinToString(",", "{", "}"))
        }

        return GET(builder.build(), headers)
    }

    private fun String.addTo(target: MutableList<String>, key: String) {
        if (isBlank()) return
        target.add("\"$key\":[\"${replace("\"", "")}\"]")
    }

    private fun titlesParse(response: Response): AnimesPage {
        val result = response.parseAs<TitlesResponse>()
        val animes = result.result.map { item ->
            SAnime.create().apply {
                url = "/title/${item.id}"
                title = item.data.name
                thumbnail_url = item.data.image
                genre = listOfNotNull(
                    item.data.country?.takeIf { it.isNotBlank() },
                    item.data.year?.toString(),
                    item.data.genre?.takeIf { it.isNotBlank() },
                ).joinToString(", ")
            }
        }

        val hasNext = animes.isNotEmpty() && (result.page + 1) * result.perpage < result.total

        return AnimesPage(animes.distinctBy { it.url }, hasNext)
    }

    private val SAnime.titleId: String
        get() = url.substringAfterLast('/')

    private fun String.stripHtml(): String = replace(HTML_TAG_REGEX, "\n")
        .replace(MULTI_NEWLINE_REGEX, "\n\n")
        .trim()

    private fun String.parseQuality(): Int = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://bldub.com"
        private val PREF_DOMAIN_ENTRIES = listOf(
            "https://bldub.com",
            "https://2.bldub.live",
            "https://3.bldub.live",
        )

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_HIDE_PREMIUM_KEY = "pref_hide_premium"
        private const val PREF_HIDE_PREMIUM_DEFAULT = false

        private const val KEY_PLACEHOLDER = "<KEY>"
        private const val HOSTNAME_PLACEHOLDER = "\${window.location.hostname}"

        private val PLAYERJS_FILE_REGEX = Regex("""file\s*:\s*[`"'](.*?)[`"']""")
        private val PLAYERJS_LABEL_REGEX = Regex("""^\[(.*?)]""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val HTML_TAG_REGEX = Regex("""</?br\s*/?>|<[^>]+>""")
        private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")
    }
}
