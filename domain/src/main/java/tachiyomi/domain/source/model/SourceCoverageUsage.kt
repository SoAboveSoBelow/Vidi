package tachiyomi.domain.source.model

// AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED) -->
/**
 * What one source has recorded about its listings' pages.
 *
 * The unit a user can act on: [SourcePageCoverage] is one page of one
 * ordering, which is the right grain for deciding whether to skip a page and
 * far too fine to show someone. A source is what they recognise and what they
 * would want rid of.
 *
 * @param pageCount rows recorded for this source, across every listing
 * @param listingCount distinct orderings recorded - a filter combination is its
 *   own ordering (see listingKeyOf), so this is how the page count is spread
 * @param estimatedBytes an over-estimate of the row payload, NOT of file size:
 *   deleting rows frees pages for reuse inside the database and the file
 *   shrinks only on a vacuum. See source_page_coverage.sq for how it is
 *   measured and why it is shown at all.
 */
data class SourceCoverageUsage(
    val sourceId: Long,
    val pageCount: Long,
    val listingCount: Long,
    val estimatedBytes: Long,
)
// <-- AM (A_RECORDED_SEQUENCES_CAN_BE_DUMPED)
