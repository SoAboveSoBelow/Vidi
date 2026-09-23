// AM (MERGED_SOURCES) -->
package aniyomi.domain.merge.repository

import aniyomi.domain.anime.model.SeasonAnime
import aniyomi.domain.merge.model.MergeChildOrdering
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime

/**
 * Membership of merged entries (see merge_children.sq). Many-to-many: one
 * entry may contribute to several merges, and being a merge child never
 * touches the entry's own season parent or favorite state.
 */
interface MergeChildRepository {

    /** Children of [mergeParentId], ordered by merge priority ascending. */
    suspend fun getChildrenByMergeParentId(mergeParentId: Long): List<Anime>

    /** All merge parents [animeId] contributes to (usually zero or one). */
    suspend fun getMergeParentsByAnimeId(animeId: Long): List<Anime>

    /** Child anime id -> the merged entry's internal season number for it. */
    suspend fun getChildSeasonsByMergeParentId(mergeParentId: Long): Map<Long, Long>

    // AM (CUSTOM_EPISODE_ORDER) -->
    /** Per-child (season, priority) - the stable inputs to the merged default order. */
    suspend fun getChildOrderingByMergeParentId(mergeParentId: Long): List<MergeChildOrdering>

    /**
     * Reactive [getChildOrderingByMergeParentId]. Emits on any membership or
     * season/priority change, which is what lets an open merged entry pick up
     * a child being added or moved between seasons without reopening.
     */
    fun getChildOrderingByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<MergeChildOrdering>>
    // <-- AM (CUSTOM_EPISODE_ORDER)

    // AM (MERGE_SEASONS) -->
    suspend fun setSeasonNumber(mergeParentId: Long, animeId: Long, seasonNumber: Long)
    // <-- AM (MERGE_SEASONS)

    // AM (MERGE_SETTINGS) -->
    /** Makes [orderedChildIds] the merge's priority order (first = highest), in one transaction. */
    suspend fun setPriorities(mergeParentId: Long, orderedChildIds: List<Long>)
    // <-- AM (MERGE_SETTINGS)

    // AM (NAMED_SEASONS) -->
    suspend fun moveChildrenBetweenSeasons(mergeParentId: Long, fromSeason: Long, toSeason: Long)
    // <-- AM (NAMED_SEASONS)

    /** Children with per-entry stats, rendered as the merged entry's seasons. */
    suspend fun getSeasonsByMergeParentId(mergeParentId: Long): List<SeasonAnime>

    fun getSeasonsByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<SeasonAnime>>

    suspend fun addChild(mergeParentId: Long, animeId: Long, priority: Long, seasonNumber: Long)

    suspend fun removeChild(mergeParentId: Long, animeId: Long)

    suspend fun removeAllChildren(mergeParentId: Long)

    /**
     * Ids of every anime belonging to at least one merge. Lets callers that
     * delete anime rows (Clear database) avoid destroying a row a live merged
     * entry still depends on.
     */
    suspend fun getAllChildAnimeIds(): Set<Long>

    /** Highest priority currently used in [mergeParentId], or null if empty. */
    suspend fun maxPriority(mergeParentId: Long): Long?

    /** Highest season number currently used in [mergeParentId], or null if empty. */
    suspend fun maxSeasonNumber(mergeParentId: Long): Long?
}
// <-- AM (MERGED_SOURCES)
