package eu.kanade.tachiyomi.animeextension.ru.animestars

import eu.kanade.tachiyomi.animeextension.ru.animestars.dto.*
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json

/**
 * Сгенерировано extension-forge.
 * Источник: https://animestars.org
 * 
 */
class  : ConfigurableAnimeSource, AnimeHttpSource() {

    private val preferences by getPreferencesLazy()

    // ==== Качество видео по умолчанию ====
    private val preferredQuality get() = preferences.getString("preferred_quality", "720")

    override fun popularAnimeRequest(page: Int): AnimeRequest =
        GET("$baseUrl/anime?page=$page", headers)

    override fun popularAnimeParse(response: AnimeResponse): AnimesPage {
        TODO("Реализуйте парсинг по декларации sites/animestars.yaml")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): AnimeRequest =
        GET("$baseUrl/search?q=$query&page=$page", headers)

    override fun searchAnimeParse(response: AnimeResponse): AnimesPage =
        popularAnimeParse(response)

    override fun animeDetailsParse(response: AnimeResponse): SAnime {
        TODO("Реализуйте по селекторам details из sites/animestars.yaml")
    }

    override fun episodeListParse(response: AnimeResponse): List<SEpisode> {
        TODO("Реализуйте по селекторам episodes из sites/animestars.yaml")
    }

    override fun videoListParse(response: AnimeResponse): List<SVideo> {
        TODO("Реализуйте; качества: []; выбор по умолчанию: $preferredQuality")
    }

    companion object {
        const val baseUrl = "https://animestars.org"
    }
}
