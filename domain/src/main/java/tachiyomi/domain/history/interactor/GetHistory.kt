package tachiyomi.domain.history.interactor

import androidx.paging.PagingSource
import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

@Inject
class GetHistory(
    private val repository: HistoryRepository,
) {

    suspend fun await(animeId: Long): List<History> {
        return repository.getHistoryByAnimeId(animeId)
    }

    // AM (HISTORY_ORIGIN): playerOnly hides rows the browse feed wrote.
    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): named for what it returns. A
    // paging source is consumed once by a Pager, not subscribed to, and it is
    // the caller that decides the page size and where the data is cached.
    // AM (A_A_GLANCE_IS_NOT_A_VIEWING): minSecondsSeen is forwarded, not
    // interpreted. 0 is off.
    fun pagingSource(
        query: String,
        playerOnly: Boolean = false,
        minSecondsSeen: Int = 0,
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
        watchedOnly: Boolean = false,
    ): PagingSource<Long, HistoryWithRelations> {
        return repository.getHistoryPagingSource(query, playerOnly, minSecondsSeen, watchedOnly)
    }
}
