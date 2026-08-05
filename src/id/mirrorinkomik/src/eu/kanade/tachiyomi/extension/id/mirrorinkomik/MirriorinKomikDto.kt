package eu.kanade.tachiyomi.extension.id.mirrorinkomik

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VercelSearchResponse(
    val status: String,
    val total: Int,
    val data: List<VercelManga>
)

@Serializable
data class VercelManga(
    val title: String,
    val url: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null
)
