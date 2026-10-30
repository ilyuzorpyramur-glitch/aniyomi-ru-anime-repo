package eu.kanade.tachiyomi.animeextension.ru.jutsunet

import kotlinx.serialization.Serializable

@Serializable
class AjaxResponse(
    val success: Boolean = false,
    val data: String? = null,
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

@Serializable
class PlayerResponse(
    val status: Boolean = false,
    val data: PlayerData? = null,
)

@Serializable
class PlayerData(
    val kind: String? = null,
    val src: String? = null,
    val name: String? = null,
)
