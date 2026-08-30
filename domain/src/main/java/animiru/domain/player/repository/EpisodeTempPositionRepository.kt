package animiru.domain.player.repository

import animiru.domain.player.model.EpisodeTempPosition

// AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
interface EpisodeTempPositionRepository {

    suspend fun getAll(): List<EpisodeTempPosition>

    suspend fun upsert(animeId: Long, episodeId: Long, positionMs: Long, updatedAt: Long)

    suspend fun delete(animeId: Long, episodeId: Long)

    /** Deletes everything except the [keepCount] most-recently-updated rows. */
    suspend fun pruneToMostRecent(keepCount: Int)

    suspend fun deleteAll()
}
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
