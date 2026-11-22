package animiru.data.player

import animiru.domain.player.model.SessionTempPosition
import animiru.domain.player.repository.SessionTempPositionRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.awaitList

// AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SessionTempPositionRepositoryImpl(
    private val database: Database,
) : SessionTempPositionRepository {

    override suspend fun getAll(): List<SessionTempPosition> {
        return database.session_temp_positionsQueries.getAll { sessionKey, positionMs, updatedAt ->
            SessionTempPosition(sessionKey, positionMs, updatedAt)
        }.awaitList()
    }

    override suspend fun upsert(sessionKey: String, positionMs: Long, updatedAt: Long) {
        try {
            withIOContext {
                database.session_temp_positionsQueries.upsert(sessionKey, positionMs, updatedAt)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun delete(sessionKey: String) {
        try {
            withIOContext {
                database.session_temp_positionsQueries.deleteByKey(sessionKey)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun pruneToMostRecent(keepCount: Int) {
        try {
            withIOContext {
                database.session_temp_positionsQueries.pruneToMostRecent(keepCount.toLong())
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAll() {
        try {
            withIOContext {
                database.session_temp_positionsQueries.deleteAll()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
