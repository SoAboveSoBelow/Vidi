// AM (TAG_LIMIT) -->
package animiru.domain.anime.model

/**
 * One sparse override of whether a tag is shown inline (see
 * anime_tag_visibility.sq).
 *
 * [tag] is the tag as the user saw it. There is no row for a tag the user has
 * not moved: that tag falls back to the split `LibraryPreferences.maxTagsShown`
 * produces.
 */
data class TagVisibility(
    val animeId: Long,
    val tag: String,
    val visible: Boolean,
)
// <-- AM (TAG_LIMIT)
