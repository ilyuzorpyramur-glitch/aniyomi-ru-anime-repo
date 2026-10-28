package eu.kanade.tachiyomi.animeextension.ru.kodikdreamcast

import kotlinx.serialization.Serializable

@Serializable
class KodikListResponse(
    val total: Int = 0,
    val results: List<KodikTitle> = emptyList(),
    val next_page: String? = null,
)

@Serializable
class KodikTitle(
    val id: String = "",
    val type: String = "",
    val link: String = "",
    val title: String = "",
    val title_orig: String = "",
    val other_title: String = "",
    val year: Int? = null,
    val last_season: Int? = null,
    val last_episode: Int? = null,
    val episodes_count: Int? = null,
    val quality: String? = null,
    val translation: KodikTranslation? = null,
    val material_data: KodikMaterial? = null,
    val seasons: Map<String, KodikSeason> = emptyMap(),
)

@Serializable
class KodikTranslation(
    val id: Int = 0,
    val title: String = "",
    val type: String = "",
)

@Serializable
class KodikMaterial(
    val title: String? = null,
    val anime_title: String? = null,
    val description: String? = null,
    val anime_description: String? = null,
    val poster_url: String? = null,
    val anime_poster_url: String? = null,
    val genres: List<String> = emptyList(),
    val anime_genres: List<String> = emptyList(),
    val year: Int? = null,
    val anime_status: String? = null,
    val anime_kind: String? = null,
    val kinopoisk_rating: Double? = null,
    val imdb_rating: Double? = null,
)

@Serializable
class KodikSeason(
    val link: String = "",
    val episodes: Map<String, String> = emptyMap(),
)

@Serializable
class KodikFtorResponse(
    val links: Map<String, List<KodikLink>> = emptyMap(),
)

@Serializable
class KodikLink(
    val src: String = "",
    val type: String? = null,
)

@Serializable
class KodikRefResponse(
    val results: List<KodikRefItem> = emptyList(),
)

@Serializable
class KodikRefItem(
    val title: String = "",
    val count: Int = 0,
)

@Serializable
class KodikYearsResponse(
    val results: List<KodikYear> = emptyList(),
)

@Serializable
class KodikYear(
    val year: Int = 0,
    val count: Int = 0,
)
