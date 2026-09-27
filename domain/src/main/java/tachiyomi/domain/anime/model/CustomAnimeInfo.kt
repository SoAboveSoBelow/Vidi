// AM (CUSTOM_INFORMATION) -->
package tachiyomi.domain.anime.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomAnimeInfo(
    val id: Long,
    val title: String?,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    // AM (TAG_LIMIT) -->
    /**
     * Tags the user added, kept apart from [genre] so they add to the entry's own
     * tags instead of replacing them. [genre] is a snapshot and wins outright,
     * which for a merged entry would freeze the ranked list its sources produce and
     * hide anything a newly added source brings in; these are unioned on top, so
     * refreshes keep working.
     */
    val addedGenre: List<String>? = null,
    // <-- AM (TAG_LIMIT)
    val status: Long? = null,
)
// <-- AM (CUSTOM_INFORMATION)
