package eu.kanade.tachiyomi.extension.id.cgbum

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CgbumSearchDto(
    val title: String? = "",
    val slug: String? = "",
    val type: String? = "",
    val chapter: String? = "",
    @SerialName("is_adult") val isAdult: Int = 0,
    val cover: String? = "",
    val url: String? = "",
)
