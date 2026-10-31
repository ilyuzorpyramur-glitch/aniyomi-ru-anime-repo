package eu.kanade.tachiyomi.animeextension.ru.tvigle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ответ-страница закрытого API Tvigle: /api/product/, /api/category/<id>/product/ и т.д. */
@Serializable
class PageDto<T>(
    val count: Int = 0,
    val next: String? = null,
    val results: List<T> = emptyList(),
)

@Serializable
class TokenDto(
    val token: String = "",
)

@Serializable
class ProductDto(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
    val description: String? = null,
    val poster: String? = null,
    val thumbnail: String? = null,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    // API отдаёт и год, и диапазон («2022-2025»), поэтому строкой.
    @SerialName("release_year") val releaseYear: String? = null,
    // Год окончания есть только у завершённых сериалов — по нему и считаем статус.
    @SerialName("release_year_end") val releaseYearEnd: String? = null,
    @SerialName("release_year_start") val releaseYearStart: String? = null,
    val categories: List<CategoryDto> = emptyList(),
    @SerialName("age_restrictions") val ageRestrictions: String? = null,
    @SerialName("kinopoisk_rating") val kinopoiskRating: String? = null,
    @SerialName("imdb_rating") val imdbRating: String? = null,
    @SerialName("is_paid") val isPaid: Boolean = false,
    @SerialName("videos_count") val videosCount: Int = 0,
    val kind: String? = null,
)

@Serializable
class CategoryDto(
    val name: String = "",
)

@Serializable
class VideoItemDto(
    val id: Int = 0,
    val name: String = "",
    val thumbnail: String? = null,
    @SerialName("content_id") val contentId: Long? = null,
    @SerialName("content_provider") val contentProvider: String? = null,
    @SerialName("is_paid") val isPaid: Boolean = false,
    @SerialName("can_play") val canPlay: Boolean = true,
    val season: Int? = null,
    val series: Int? = null,
)

// ============================ cloud.tvigle.ru ============================

@Serializable
class CloudResponseDto(
    val playlist: CloudPlaylistDto? = null,
)

@Serializable
class CloudPlaylistDto(
    val items: List<CloudItemDto> = emptyList(),
)

@Serializable
class CloudItemDto(
    val videos: CloudVideosDto? = null,
    @SerialName("subtitlesURL") val subtitlesUrl: String? = null,
    @SerialName("errorMessage") val errorMessage: String? = null,
)

@Serializable
class CloudVideosDto(
    /** Качество ("720p") -> ссылка на mp4, иногда протокол-относительная. */
    val mp4: Map<String, String> = emptyMap(),
    val hls: Map<String, String> = emptyMap(),
)
