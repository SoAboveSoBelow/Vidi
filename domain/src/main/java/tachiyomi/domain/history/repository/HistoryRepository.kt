package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations

interface HistoryRepository {

    // AM (HISTORY_ORIGIN): playerOnly hides rows the browse feed wrote.
    fun getHistory(query: String, playerOnly: Boolean): Flow<List<HistoryWithRelations>>

    suspend fun getLastHistory(): HistoryWithRelations?

    suspend fun getHistoryByAnimeId(animeId: Long): List<History>

    suspend fun resetHistory(historyId: Long)

    suspend fun resetHistoryByAnimeId(animeId: Long)

    suspend fun deleteAllHistory(): Boolean

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)
}
