// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.domain.order.model

/**
 * One sparse override of an entry's episode order (see episode_order.sq).
 *
 * [hostAnimeId] is the entry being viewed through, which for a merged entry is
 * the merge parent rather than the child that owns the episode. Both override
 * fields are independently optional: a null one falls back to the host's
 * default ordering for that episode.
 */
data class EpisodeOrderOverride(
    val hostAnimeId: Long,
    val episodeId: Long,
    val seasonNumber: Long?,
    val sortKey: Double?,
)
// <-- AM (CUSTOM_EPISODE_ORDER)
