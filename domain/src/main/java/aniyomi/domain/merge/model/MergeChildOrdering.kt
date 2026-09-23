// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.domain.merge.model

/**
 * The stable ordering inputs for one child of a merged entry: which internal
 * season it sits in, and its merge-order position within that season.
 */
data class MergeChildOrdering(
    val animeId: Long,
    val seasonNumber: Long,
    val priority: Long,
)
// <-- AM (CUSTOM_EPISODE_ORDER)
