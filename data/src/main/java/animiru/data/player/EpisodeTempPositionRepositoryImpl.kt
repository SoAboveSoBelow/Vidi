package animiru.data.player

import animiru.domain.player.model.EpisodeTempPosition
import animiru.domain.player.repository.EpisodeTempPositionRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database

// AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
class EpisodeTempPositionRepositoryImpl(
    private val database: Database,
) : EpisodeTempPositionRepository {

    override suspend fun getAll(): List<EpisodeTempPosition> {
        return database.episode_temp_positionsQueries.getAll { animeId, episodeId, positionMs, updatedAt ->
            EpisodeTempPosition(animeId, episodeId, positionMs, updatedAt)
        }.awaitAsList()
    }

    override suspend fun upsert(animeId: Long, episodeId: Long, positionMs: Long, updatedAt: Long) {
        try {
            database.episode_temp_positionsQueries.upsert(animeId, episodeId, positionMs, updatedAt)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun delete(animeId: Long, episodeId: Long) {
        try {
            database.episode_temp_positionsQueries.deleteByKey(animeId, episodeId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun pruneToMostRecent(keepCount: Int) {
        try {
            database.episode_temp_positionsQueries.pruneToMostRecent(keepCount.toLong())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAll() {
        try {
            database.episode_temp_positionsQueries.deleteAll()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
