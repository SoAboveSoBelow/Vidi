// AM (NAMED_SEASONS) -->
package aniyomi.domain.season.repository

import aniyomi.domain.season.model.EntrySeason
import kotlinx.coroutines.flow.Flow

interface EntrySeasonRepository {

    suspend fun getByHostAnimeId(hostAnimeId: Long): List<EntrySeason>

    fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<List<EntrySeason>>

    /**
     * Writes [seasons] as the entry's rows, in one transaction. Each must have a
     * sortOrder. Used for every edit, so a reorder or rename lands atomically.
     */
    suspend fun upsertAll(hostAnimeId: Long, seasons: List<EntrySeason>)

    suspend fun delete(hostAnimeId: Long, seasonNumber: Long)
}
// <-- AM (NAMED_SEASONS)
