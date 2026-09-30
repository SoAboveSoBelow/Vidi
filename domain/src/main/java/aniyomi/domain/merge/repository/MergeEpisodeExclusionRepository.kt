// AM (MERGE_EPISODE_EXCLUSION) -->
package aniyomi.domain.merge.repository

import kotlinx.coroutines.flow.Flow

/**
 * Episodes removed from one merged entry's order. See
 * merge_episode_exclusions.sq for what is and isn't stored here.
 */
interface MergeEpisodeExclusionRepository {

    /** Episode ids removed from [hostAnimeId]. Empty for an entry nobody has edited. */
    suspend fun getByHostAnimeId(hostAnimeId: Long): Set<Long>

    fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<Set<Long>>

    suspend fun add(hostAnimeId: Long, episodeId: Long)

    suspend fun remove(hostAnimeId: Long, episodeId: Long)

    /** Drops the removals for [episodeIds], putting just those back into [hostAnimeId]'s order. */
    suspend fun removeAll(hostAnimeId: Long, episodeIds: List<Long>)
}
// <-- AM (MERGE_EPISODE_EXCLUSION)
