package tachiyomi.data.history

import androidx.paging.PagingSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.QueryPagingSource
import tachiyomi.data.awaitList
import tachiyomi.data.awaitOneOrNull
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.ViewedAnimeIds
import tachiyomi.domain.history.repository.HistoryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HistoryRepositoryImpl(
    private val database: Database,
) : HistoryRepository {

    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME) -->
    override fun getHistoryPagingSource(
        query: String,
        playerOnly: Boolean,
        // AM (A_A_GLANCE_IS_NOT_A_VIEWING)
        minSecondsSeen: Int,
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
        watchedOnly: Boolean,
    ): PagingSource<Long, HistoryWithRelations> {
        // The local QueryPagingSource, not the one in
        // app.cash.sqldelight:androidx-paging3-extensions: that factory takes a
        // Transacter and executes synchronously, which this module's
        // generateAsync queries are not. The local one awaits them, and it
        // reports itemsBefore/itemsAfter, which is what lets the count below be
        // used for bounds.
        return QueryPagingSource(
            countQuery = {
                // AM (HISTORY_ORIGIN): query first here. historyCount names it
                // before :playerOnly, which is what decides the generated
                // argument order - the opposite way round from historyPaged.
                // AM (A_A_GLANCE_IS_NOT_A_VIEWING): and :minSecondsSeen after
                // both, which is where the query names it.
                database.historyViewQueries.historyCount(
                    query,
                    if (playerOnly) 1L else 0L,
                    minSecondsSeen.toLong(),
                    // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): last, which is
                    // where both queries name it.
                    if (watchedOnly) 1L else 0L,
                )
            },
            queryProvider = { limit, offset ->
                // AM (A_HISTORY_PICKS_THE_LATEST_IN_ONE_PASS): playerOnly first
                // - the rewritten query names it inside its CTE.
                // AM (A_A_GLANCE_IS_NOT_A_VIEWING): and :minSecondsSeen next,
                // named in the same CTE just after it.
                database.historyViewQueries.historyPaged(
                    if (playerOnly) 1L else 0L,
                    minSecondsSeen.toLong(),
                    // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): named inside the
                    // CTE after :minSecondsSeen and before the outer :query.
                    if (watchedOnly) 1L else 0L,
                    query,
                    limit,
                    offset,
                    HistoryMapper::mapHistoryWithRelations,
                )
            },
        )
    }
    // <-- AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME)

    override suspend fun getLastHistory(): HistoryWithRelations? {
        return database.historyViewQueries.getLatestHistory(HistoryMapper::mapHistoryWithRelations)
            .awaitOneOrNull()
    }

    override suspend fun getHistoryByAnimeId(animeId: Long): List<History> {
        return database.historyQueries.getHistoryByAnimeId(animeId, HistoryMapper::mapHistory).awaitList()
    }

    // AM (BROWSE_HIDE_VIEWED) -->
    override suspend fun getViewedAnimeIdsBySource(sourceId: Long): ViewedAnimeIds {
        // AM (A_VIEWED_SET_IS_NOT_A_HASHSET): the query is unchanged - still the
        // DISTINCT read history.sq documents - and the list it returns is turned
        // into membership here rather than into a set of boxed ids.
        return ViewedAnimeIds.of(
            database.historyQueries.getViewedAnimeIdsBySource(sourceId).awaitList(),
        )
    }
    // <-- AM (BROWSE_HIDE_VIEWED)

    override suspend fun resetHistory(historyId: Long) {
        try {
            withIOContext {
                database.historyQueries.resetHistoryById(historyId)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByAnimeId(animeId: Long) {
        try {
            withIOContext {
                database.historyQueries.resetHistoryByAnimeId(animeId)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            withIOContext {
                database.historyQueries.removeAllHistory()
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        try {
            withIOContext {
                database.historyQueries.upsert(
                    historyUpdate.episodeId,
                    historyUpdate.seenAt,
                    // AM (HISTORY_ORIGIN)
                    historyUpdate.origin.value,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
