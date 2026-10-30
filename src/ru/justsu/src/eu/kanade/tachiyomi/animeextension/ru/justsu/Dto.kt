package eu.kanade.tachiyomi.animeextension.ru.justsu

import kotlinx.serialization.Serializable

@Serializable
class PlaylistDto(
    val titleName: String? = null,
    val isSerial: Boolean? = null,
    val items: List<PlaylistItemDto> = emptyList(),
)

@Serializable
class PlaylistItemDto(
    val cvhId: String? = null,
    val name: String? = null,
    val vkId: String? = null,
    val voiceStudio: String? = null,
    val voiceType: String? = null,
    val season: Int? = null,
    val episode: Float? = null,
) {
    val voiceLabel: String
        get() = listOfNotNull(
            voiceStudio?.takeIf { it.isNotBlank() },
            voiceType?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "JustSu" }
}

@Serializable
class VideoDto(
    val unitedVideoId: Long? = null,
    val duration: Int? = null,
    val failoverHost: String? = null,
    val sources: SourcesDto? = null,
)

@Serializable
class SourcesDto(
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
)

@Serializable
class KodikUrlParams(
    val d: String = "",
    val d_sign: String = "",
    val pd: String = "",
    val pd_sign: String = "",
    val ref: String = "",
    val ref_sign: String = "",
)

@Serializable
class KodikFtorResponse(
    val links: Map<String, List<KodikLinkDto>> = emptyMap(),
)

@Serializable
class KodikLinkDto(
    val src: String = "",
    val type: String? = null,
)
