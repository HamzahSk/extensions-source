package eu.kanade.tachiyomi.extension.id.mirrorinkomik

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VercelSearchResponse(
    val status: String = "",
    val pagination: VercelPagination? = null,
    val data: List<VercelManga> = emptyList(),
)

@Serializable
data class VercelPagination(
    val has_next_page: Boolean = false,
)

@Serializable
data class VercelManga(
    val title: String = "",
    val url: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)
