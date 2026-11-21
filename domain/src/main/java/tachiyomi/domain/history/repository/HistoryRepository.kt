package tachiyomi.domain.history.repository

import androidx.paging.PagingSource
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.ViewedAnimeIds

interface HistoryRepository {

    // AM (HISTORY_ORIGIN): playerOnly hides rows the browse feed wrote.
    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): a paging source rather than a
    // Flow of the whole list - see historyView.sq for the cost that was. The
    // source invalidates itself when the tables it read change, so a history
    // write still refreshes the screen; it just refreshes the pages in view.
    // AM (A_A_GLANCE_IS_NOT_A_VIEWING): minSecondsSeen drops rows whose episode
    // was barely watched; 0 is off. See historyView.sq for what counts as
    // watched, which is not simply the stored position.
    fun getHistoryPagingSource(
        query: String,
        playerOnly: Boolean,
        minSecondsSeen: Int,
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): watched is the narrower
        // state - everything in history is viewed. The downloaded filter is not
        // here because it cannot be asked of the database.
        watchedOnly: Boolean,
    ): PagingSource<Long, HistoryWithRelations>

    suspend fun getLastHistory(): HistoryWithRelations?

    suspend fun getHistoryByAnimeId(animeId: Long): List<History>

    // AM (BROWSE_HIDE_VIEWED): every entry of one source that has been viewed
    // at all, as it stands now - see history.sq for what counts and why this is
    // a one-shot read rather than a flow.
    // AM (A_VIEWED_SET_IS_NOT_A_HASHSET): membership, not a HashSet of boxed
    // Longs - see ViewedAnimeIds. Same one-shot answer either way.
    suspend fun getViewedAnimeIdsBySource(sourceId: Long): ViewedAnimeIds

    suspend fun resetHistory(historyId: Long)

    suspend fun resetHistoryByAnimeId(animeId: Long)

    suspend fun deleteAllHistory(): Boolean

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)
}
