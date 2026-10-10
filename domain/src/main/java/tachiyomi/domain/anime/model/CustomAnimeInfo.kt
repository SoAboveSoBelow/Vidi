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
    // AM (TAG_BLACKLIST) -->
    /**
     * Tags the user has thrown away on this entry, dropped from [genre] wherever
     * it is read - the tag row, the popout, tag search, everything.
     *
     * Needed because a source's tag cannot simply be deleted: the next refresh
     * brings it back. Kept here rather than in a table of its own so the drop
     * happens in the one place the entry's tags are already assembled, which
     * costs no query and no join - on a merge of a hundred sources carrying a
     * thousand tags, that is the difference between the thrown-away ones never
     * being built and being built and then filtered at every reader.
     */
    val blacklistedGenre: List<String>? = null,
    // <-- AM (TAG_BLACKLIST)
    val status: Long? = null,
)
// <-- AM (CUSTOM_INFORMATION)
