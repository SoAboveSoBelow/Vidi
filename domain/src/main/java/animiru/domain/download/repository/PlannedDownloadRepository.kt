// AM (PLANNED_DOWNLOADS) -->
package animiru.domain.download.repository

import animiru.domain.download.model.PlannedDownloadEntry
import animiru.domain.download.model.PlannedDownloadOrigin
import kotlinx.coroutines.flow.Flow

/**
 * Episodes wanted but not queued (see planned_downloads.sq). Intent, as against the
 * download queue's execution.
 */
interface PlannedDownloadRepository {

    suspend fun getAll(): List<PlannedDownloadEntry>

    fun getAllAsFlow(): Flow<List<PlannedDownloadEntry>>

    fun countAsFlow(): Flow<Long>

    /** The planned episodes of one entry, for marking them up in its episode list. */
    fun getEpisodeIdsByAnimeIdAsFlow(animeId: Long): Flow<Set<Long>>

    suspend fun add(animeId: Long, episodeId: Long, origin: PlannedDownloadOrigin)

    /** Adds [episodeIdsByAnimeId] in one transaction; already-present rows take the new origin. */
    suspend fun addAll(episodeIdsByAnimeId: List<Pair<Long, Long>>, origin: PlannedDownloadOrigin)

    /**
     * Removes the row for [episodeId].
     *
     * Keyed by the episode alone, because that identifies it: a caller holding a
     * different anime object for the same episode - a merge parent rather than the
     * child that owns it - would otherwise delete nothing.
     */
    suspend fun remove(episodeId: Long)

    suspend fun removeAll(episodeIds: List<Long>)

    suspend fun removeByAnimeId(animeId: Long)

    suspend fun clear()
}
// <-- AM (PLANNED_DOWNLOADS)
