// AM (NAMED_SEASONS) -->
package aniyomi.domain.season.repository

import aniyomi.domain.season.model.EntrySeason
import kotlinx.coroutines.flow.Flow

interface EntrySeasonRepository {

    suspend fun getByHostAnimeId(hostAnimeId: Long): List<EntrySeason>

    // AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP): every entry's seasons in one
    // read, for a caller that needs all of them at once - see entry_seasons.sq.
    // Grouped here rather than by the caller because EntrySeason deliberately
    // carries no host id: a season is scoped by whoever asked for it, and the
    // only layer that still has the column is the one that reads it.
    suspend fun getAllByHostAnimeId(): Map<Long, List<EntrySeason>>

    fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<List<EntrySeason>>

    /**
     * Writes [seasons] as the entry's rows, in one transaction. Each must have a
     * sortOrder. Used for every edit, so a reorder or rename lands atomically.
     */
    suspend fun upsertAll(hostAnimeId: Long, seasons: List<EntrySeason>)

    suspend fun delete(hostAnimeId: Long, seasonNumber: Long)
}
// <-- AM (NAMED_SEASONS)
