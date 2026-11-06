package animiru.domain.player.repository

import animiru.domain.player.model.SessionTempPosition

// AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
interface SessionTempPositionRepository {

    suspend fun getAll(): List<SessionTempPosition>

    suspend fun upsert(sessionKey: String, positionMs: Long, updatedAt: Long)

    suspend fun delete(sessionKey: String)

    /** Deletes everything except the [keepCount] most-recently-updated rows. */
    suspend fun pruneToMostRecent(keepCount: Int)

    suspend fun deleteAll()
}
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
