package tachiyomi.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.cash.sqldelight.Query
// AM (A_A_WRITE_IS_NOT_A_CHANGE) -->
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
// <-- AM (A_A_WRITE_IS_NOT_A_CHANGE)
import kotlin.properties.Delegates

// AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): no longer unused - HistoryRepositoryImpl
// builds one. See the note there for why this and not the SQLDelight paging
// extension factory.
class QueryPagingSource<RowType : Any>(
    val countQuery: () -> Query<Long>,
    val queryProvider: (Long, Long) -> Query<RowType>,
) : PagingSource<Long, RowType>(), Query.Listener {

    override val jumpingSupported: Boolean = true

    private var currentQuery: Query<RowType>? by Delegates.observable(null) { _, old, new ->
        old?.removeListener(this)
        new?.addListener(this)
    }

    // AM (A_A_WRITE_IS_NOT_A_CHANGE) -->
    /**
     * What each loaded page last contained, keyed by the limit and offset it
     * was loaded with, and the row count beside it.
     *
     * Kept so that a notification can be checked against the data instead of
     * taken at face value - see [queryResultsChanged]. Bounded, and dropped
     * whole the moment this source is invalidated, because a new source loads
     * its own pages.
     */
    private val deliveredPages = LinkedHashMap<Pair<Long, Long>, List<RowType>>()
    private var deliveredCount: Long? = null
    private val deliveredLock = Any()

    /**
     * The check in flight, if any. Replaced rather than queued: a second
     * notification makes the first one's answer stale, and what matters is
     * whether the data differs now.
     */
    private var verifyJob: Job? = null
    private val verifyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // <-- AM (A_A_WRITE_IS_NOT_A_CHANGE)

    init {
        registerInvalidatedCallback {
            currentQuery?.removeListener(this)
            currentQuery = null
            // AM (A_A_WRITE_IS_NOT_A_CHANGE)
            verifyJob?.cancel()
            synchronized(deliveredLock) {
                deliveredPages.clear()
                deliveredCount = null
            }
        }
    }

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, RowType> {
        try {
            val key = params.key ?: 0L
            val loadSize = params.loadSize
            val count = countQuery().awaitOne()

            val (offset, limit) = when (params) {
                is LoadParams.Prepend -> key - loadSize to loadSize.toLong()
                else -> key to loadSize.toLong()
            }

            val data = queryProvider(limit, offset)
                .also { currentQuery = it }
                .awaitList()

            val (prevKey, nextKey) = when (params) {
                is LoadParams.Append -> (offset - loadSize to offset + loadSize)
                else -> (offset to offset + loadSize)
            }

            // AM (A_A_WRITE_IS_NOT_A_CHANGE): remembered as delivered, so a
            // later notification can be compared against it.
            synchronized(deliveredLock) {
                deliveredCount = count
                deliveredPages[limit to offset] = data
                while (deliveredPages.size > MAX_TRACKED_PAGES) {
                    deliveredPages.remove(deliveredPages.keys.first())
                }
            }

            return LoadResult.Page(
                data = data,
                prevKey = if (offset <= 0L || prevKey < 0L) null else prevKey,
                nextKey = if (offset + loadSize >= count) null else nextKey,
                itemsBefore = maxOf(0L, offset).toInt(),
                itemsAfter = maxOf(0L, count - (offset + loadSize)).toInt(),
            )
        } catch (e: Exception) {
            return LoadResult.Error(throwable = e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, RowType>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }

    // AM (A_A_WRITE_IS_NOT_A_CHANGE) -->
    /**
     * Invalidates only when the rows this source actually projects differ.
     *
     * The callback's name overpromises: SQLDelight notifies a listener when a
     * TABLE its query reads is written, not when the query's results change.
     * For the history list those are wildly different events - its query joins
     * episodes, and every progress save while something is playing writes
     * last_second_seen, which the projection does not even select. So the list
     * was invalidated roughly once a second throughout an episode.
     *
     * An invalidation is not free and is not invisible: Paging refreshes from
     * getRefreshKey, which answers with the anchor page's key, and with
     * placeholders off - see HistoryViewModel's note on why they are - every
     * page before that one is dropped and re-indexed. The list moved under the
     * reader, once a second, for writes that changed nothing it was showing.
     *
     * So the claim is checked: re-run the pages that were delivered and the
     * count, and invalidate only if something came back different. Cheaper
     * than what it replaces even when it does invalidate - these are the same
     * LIMIT/OFFSET reads the refresh would run, without the rebuild - and it
     * is comparing the mapped rows, so a column that is written but never
     * selected cannot register as a change.
     *
     * Falls back to invalidating whenever it cannot be sure: nothing delivered
     * yet, or a query that failed.
     */
    override fun queryResultsChanged() {
        val (pages, count) = synchronized(deliveredLock) {
            deliveredPages.toMap() to deliveredCount
        }
        if (pages.isEmpty() || count == null) {
            invalidate()
            return
        }
        verifyJob?.cancel()
        verifyJob = verifyScope.launch {
            // AM (A_A_WRITE_IS_NOT_A_CHANGE_DIAGNOSTIC): says what it decided
            // and on what evidence, because "the list moved and nothing I can
            // see should have moved it" cannot be argued down from the source.
            // One line per actual invalidation, nothing on the common path.
            val reason = runCatching {
                val freshCount = countQuery().awaitOne()
                if (freshCount != count) return@runCatching "count $count -> $freshCount"
                pages.entries
                    .firstOrNull { (args, rows) -> queryProvider(args.first, args.second).awaitList() != rows }
                    ?.let { (args, _) -> "rows differ in page limit=${args.first} offset=${args.second}" }
            }.getOrElse { "verification failed: ${it.message}" }
            if (reason != null) invalidate()
        }
    }

    private companion object {
        /**
         * How many loaded pages are tracked for comparison.
         *
         * Paging drops pages behind the reader anyway, and the cost of this
         * being too small is only a wasted invalidation; too large and a
         * notification runs more queries than the refresh it is avoiding.
         */
        const val MAX_TRACKED_PAGES = 8
    }
    // <-- AM (A_A_WRITE_IS_NOT_A_CHANGE)
}
