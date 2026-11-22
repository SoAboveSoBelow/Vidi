package tachiyomi.data.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.awaitList
import tachiyomi.domain.anime.model.Anime
// AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
import tachiyomi.domain.source.model.SourceCoverageUsage
import tachiyomi.domain.source.model.SourceListingHealth
import tachiyomi.domain.source.model.SourcePageCoverage
import tachiyomi.domain.source.repository.SourceCoverageRepository
import kotlin.time.Clock

// AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceCoverageRepositoryImpl(
    private val database: Database,
) : SourceCoverageRepository {

    override suspend fun record(
        sourceId: Long,
        listingKey: String,
        page: Long,
        entries: List<Anime>,
        hasNextPage: Boolean,
    ) {
        // A page that arrived empty says nothing about coverage, and has no
        // head or tail to anchor a region to.
        if (entries.isEmpty()) return

        // AM (A_AN_UNVIEWED_TAIL_IS_A_STOPPING_POINT): the ids, not just how
        // many - whether the LAST entry is among them is what says the walk
        // reached the end of this page rather than stopping inside it.
        val viewedIds = database.historyQueries
            .getViewedAnimeIdsAmong(entries.map { it.id })
            .awaitList()
            .toHashSet()

        database.source_page_coverageQueries.upsert(
            source = sourceId,
            listingKey = listingKey,
            page = page,
            entryCount = entries.size.toLong(),
            viewedCount = viewedIds.size.toLong(),
            headUrl = entries.first().url,
            tailUrl = entries.last().url,
            tailViewed = entries.last().id in viewedIds,
            hasNext = hasNextPage,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    override suspend fun getByListing(sourceId: Long, listingKey: String): List<SourcePageCoverage> {
        return database.source_page_coverageQueries
            .getByListing(sourceId, listingKey) {
                    source,
                    key,
                    page,
                    entryCount,
                    viewedCount,
                    headUrl,
                    tailUrl,
                    tailViewed,
                    hasNext,
                    updatedAt,
                ->
                SourcePageCoverage(
                    sourceId = source,
                    listingKey = key,
                    page = page,
                    entryCount = entryCount.toInt(),
                    viewedCount = viewedCount.toInt(),
                    headUrl = headUrl,
                    tailUrl = tailUrl,
                    tailViewed = tailViewed,
                    hasNext = hasNext,
                    updatedAt = updatedAt,
                )
            }
            .awaitList()
    }

    // AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED) -->
    override suspend fun health(sourceId: Long, listingKey: String): SourceListingHealth? {
        return database.source_listing_healthQueries
            .get(sourceId, listingKey) { source, key, failures, updatedAt ->
                SourceListingHealth(
                    sourceId = source,
                    listingKey = key,
                    failures = failures.toInt(),
                    updatedAt = updatedAt,
                )
            }
            .awaitList()
            .firstOrNull()
    }

    override suspend fun noteShift(sourceId: Long, listingKey: String) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.source_listing_healthQueries.noteShift(
                source = sourceId,
                listingKey = listingKey,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    override suspend fun noteStable(sourceId: Long, listingKey: String) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.source_listing_healthQueries.clear(sourceId, listingKey)
        }
    }
    // <-- AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED)

    override suspend fun deleteByListing(sourceId: Long, listingKey: String) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.source_page_coverageQueries.deleteByListing(sourceId, listingKey)
        }
    }

    override suspend fun deleteAll() {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.source_page_coverageQueries.deleteAll()
        }
    }

    // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
    override suspend fun getRecordedUsage(): List<SourceCoverageUsage> {
        return withIOContext {
            database.source_page_coverageQueries
                .getRecordedUsage { source, pageCount, listingCount, estimatedBytes ->
                    SourceCoverageUsage(
                        sourceId = source,
                        pageCount = pageCount,
                        listingCount = listingCount,
                        // SUM over an aggregate SQLDelight types as nullable,
                        // because it is null for an empty group - which GROUP BY
                        // cannot produce here, so the row always has a figure.
                        estimatedBytes = estimatedBytes ?: 0L,
                    )
                }
                .awaitList()
        }
    }

    override suspend fun deleteBySource(sourceId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.source_page_coverageQueries.deleteBySource(sourceId)
        }
    }
    // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
}
