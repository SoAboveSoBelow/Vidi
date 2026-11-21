package tachiyomi.domain.source.repository

import tachiyomi.domain.anime.model.Anime
// AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
import tachiyomi.domain.source.model.SourceCoverageUsage
import tachiyomi.domain.source.model.SourceListingHealth
import tachiyomi.domain.source.model.SourcePageCoverage

// AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED) -->
/**
 * Records what a listing's pages held, and reads it back.
 *
 * BaseSourcePagingSource both writes these and reads them: it records every page
 * it loads, and uses what was recorded on an earlier walk to skip a page it can
 * prove is already covered. See A_COVERED_PAGE_NEED_NOT_BE_FETCHED there for
 * what "prove" means, which is the whole of the design.
 */
interface SourceCoverageRepository {

    /**
     * Records one page as loaded.
     *
     * Takes the entries rather than a viewed count: which of them are viewed is
     * a fact about `history`, not something the caller should have to decide, and
     * resolving it here keeps the browse paths from carrying a predicate they
     * would only be passing through. [entries] must be in the order the source
     * returned them, because the first and last are what a later skip verifies
     * a region against.
     */
    suspend fun record(
        sourceId: Long,
        listingKey: String,
        page: Long,
        entries: List<Anime>,
        hasNextPage: Boolean,
    )

    suspend fun getByListing(sourceId: Long, listingKey: String): List<SourcePageCoverage>

    /**
     * This listing's ordering-stability record, or null if it has never failed.
     *
     * Checked before coverage is read OR written: see [SourceListingHealth].
     */
    suspend fun health(sourceId: Long, listingKey: String): SourceListingHealth?

    /** Counts one ordering shift against this listing. */
    suspend fun noteShift(sourceId: Long, listingKey: String)

    /**
     * Forgets this listing's failures, because a walk just verified it.
     *
     * Reset rather than decremented: a clean verification is direct evidence
     * about the listing as it behaves now, which is the only thing the count is
     * trying to answer.
     */
    suspend fun noteStable(sourceId: Long, listingKey: String)

    /**
     * Forgets one listing's coverage, because its ordering moved.
     *
     * Called when a loaded page does not match what was recorded for it: the
     * records describe an ordering that no longer exists, and a shift moves
     * entries across page boundaries, so none of the listing's rows survive it.
     */
    suspend fun deleteByListing(sourceId: Long, listingKey: String)

    /** Coverage assumes viewed never regresses, so a history clear voids all of it. */
    suspend fun deleteAll()

    // AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
    /**
     * What every source has recorded, for showing and dropping per source -
     * see [SourceCoverageUsage].
     *
     * A one-shot read, not a flow: this is read when a screen that exists to
     * clear these rows is opened, and the only thing that changes them while it
     * is open is that screen itself.
     */
    suspend fun getRecordedUsage(): List<SourceCoverageUsage>

    /**
     * Forgets everything recorded for one source, across all its listings.
     *
     * Nothing is lost that cannot be re-learned: the rows are observations of
     * ordinary browsing and are recorded again as a side effect of the next
     * walk. What it costs is the requests that walk no longer gets to skip.
     *
     * The source's listing-health rows are deliberately left alone - see
     * source_page_coverage.sq's deleteBySource for why.
     */
    suspend fun deleteBySource(sourceId: Long)
    // <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
}
// <-- AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED)
