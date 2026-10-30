package eu.kanade.tachiyomi.animeextension.ru.animego

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class AjaxResponse(
    val status: String? = null,
    val data: AjaxData? = null,
)

@Serializable
class AjaxData(
    val content: String? = null,
)

@Serializable
class KodikPlayerData(
    val voices: List<TranslationDto> = emptyList(),
    val subtitles: List<TranslationDto> = emptyList(),
    @SerialName("default_translation") val defaultTranslation: String? = null,
    @SerialName("episodes_meta") val episodesMeta: Map<String, EpisodeMetaDto> = emptyMap(),
) {
    fun allTranslations(): List<TranslationDto> = voices + subtitles
}

@Serializable
class TranslationDto(
    val id: String? = null,
    val title: String? = null,
    val type: String? = null,
    val quality: String? = null,
    val link: String? = null,
    val episodes: Map<String, String> = emptyMap(),
) {
    val isSubtitles: Boolean get() = type == "subtitles"
}

@Serializable
class EpisodeMetaDto(
    val title: String? = null,
    val released: String? = null,
    val aired: Boolean? = null,
)

@Serializable
class KodikUrlParams(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
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

// --- Kodik (формат из yuzono/anime-extensions) ---

@Serializable
class KodikFormData(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
class KodikVideoInfo(val src: String)

@Serializable
class KodikVideoQuality(
    @SerialName("360") val ugly: List<KodikVideoInfo> = emptyList(),
    @SerialName("480") val bad: List<KodikVideoInfo> = emptyList(),
    @SerialName("720") val good: List<KodikVideoInfo> = emptyList(),
)

@Serializable
class KodikData(val links: KodikVideoQuality)
