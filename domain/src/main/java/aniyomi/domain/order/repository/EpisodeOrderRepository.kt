// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.domain.order.repository

import aniyomi.domain.order.model.EpisodeOrderOverride
import kotlinx.coroutines.flow.Flow

interface EpisodeOrderRepository {

    /** Overrides for [hostAnimeId]. Empty for an entry the user hasn't reordered. */
    suspend fun getByHostAnimeId(hostAnimeId: Long): List<EpisodeOrderOverride>

    // AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP): every entry's overrides in one
    // read - see episode_order.sq. Keyed the same way as the season equivalent,
    // so both structure reads hand the caller the same shape.
    suspend fun getAllByHostAnimeId(): Map<Long, List<EpisodeOrderOverride>>

    fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<List<EpisodeOrderOverride>>

    suspend fun upsert(hostAnimeId: Long, episodeId: Long, seasonNumber: Long?, sortKey: Double?)

    suspend fun delete(hostAnimeId: Long, episodeId: Long)

    /** Drops the overrides for [episodeIds], returning just those to their default position and season. */
    suspend fun deleteAll(hostAnimeId: Long, episodeIds: List<Long>)

    /** Drops every override for [hostAnimeId], restoring its default order. */
    suspend fun deleteByHostAnimeId(hostAnimeId: Long)

    /** Upserts [overrides] in one transaction - a multi-episode move is one edit. */
    suspend fun upsertAll(overrides: List<EpisodeOrderOverride>)

    /**
     * Makes [overrides] the complete set for [hostAnimeId], in one transaction.
     * Used to restore a snapshot: a partial restore would leave an order that
     * never existed.
     */
    suspend fun replaceAll(hostAnimeId: Long, overrides: List<EpisodeOrderOverride>)
}
// <-- AM (CUSTOM_EPISODE_ORDER)
