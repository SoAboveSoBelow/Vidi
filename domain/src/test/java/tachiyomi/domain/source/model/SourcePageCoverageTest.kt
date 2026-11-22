package tachiyomi.domain.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * AM (A_SKIP_IS_A_PROMISE_ABOUT_WHAT_THE_USER_SEES)
 *
 * Every case here is a way skipping was observed to go wrong, kept so it cannot
 * go wrong the same way twice. The names say which promise is being held.
 */
@Execution(ExecutionMode.CONCURRENT)
class SourcePageCoverageTest {

    private val pageSize = 24

    private fun coverage(
        page: Long = 2,
        viewed: Int = pageSize,
        tailViewed: Boolean = true,
        hasNext: Boolean = true,
        entryCount: Int = pageSize,
    ) = SourcePageCoverage(
        sourceId = 1L,
        listingKey = "popular|",
        page = page,
        entryCount = entryCount,
        viewedCount = viewed,
        headUrl = "/a/head",
        tailUrl = "/a/tail",
        tailViewed = tailViewed,
        hasNext = hasNext,
        updatedAt = 0L,
    )

    private fun walk(
        trimActive: Boolean = true,
        recordsTrusted: Boolean = true,
        verifiedThrough: Long = 1,
        positionedThrough: Long = verifiedThrough,
        listingPageSize: Int? = pageSize,
        // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
        coveredFraction: Float = SourcePageCoverage.COVERED_FRACTION,
    ) = WalkState(
        trimActive,
        recordsTrusted,
        verifiedThrough,
        positionedThrough,
        listingPageSize,
        coveredFraction,
    )

    @Test
    fun `a fully covered page with a verified neighbour is skipped`() {
        coverage().actionFor(2, walk()) shouldBe PageAction.Skip(nextPage = 3)
    }

    @Test
    fun `an unrecorded page is always loaded`() {
        val absent: SourcePageCoverage? = null
        absent.actionFor(2, walk()) shouldBe PageAction.Load
    }

    @Test
    fun `page 1 is never skipped, because it is what proves the rest`() {
        coverage(page = 1).actionFor(1, walk(verifiedThrough = 0)) shouldBe PageAction.Load
    }

    // Promise 1: the same visible entries.

    @Test
    fun `below the covered fraction the page is loaded`() {
        // 19 of 24 is 0.79.
        coverage(viewed = 19).actionFor(2, walk()) shouldBe PageAction.Load
    }

    @Test
    fun `at the covered fraction the page is skipped`() {
        // 21 of 24 is 0.875.
        coverage(viewed = 21).actionFor(2, walk()) shouldBe PageAction.Skip(nextPage = 3)
    }

    @Test
    fun `an unviewed tail is a stopping point, not a gap`() {
        // The same 0.875, but the unviewed entries are the ones never reached.
        coverage(viewed = 21, tailViewed = false).actionFor(2, walk()) shouldBe PageAction.Load
    }

    @Test
    fun `nothing is skipped while the trim is off`() {
        // Hide-viewed off means the viewed entries are wanted, so a skip is a hole.
        coverage().actionFor(2, walk(trimActive = false)) shouldBe PageAction.Load
    }

    // Promise 2: the same continuation.

    @Test
    fun `skipping the last page reports the end of the listing`() {
        coverage(hasNext = false).actionFor(2, walk()) shouldBe PageAction.Skip(nextPage = null)
    }

    // Promise 3: the same page.

    @Test
    fun `a page is loaded when the one before it has no position this walk`() {
        // Page 2 was neither loaded nor skipped, so nothing says page 3's record
        // still describes page 3.
        coverage(page = 3).actionFor(3, walk(verifiedThrough = 1, positionedThrough = 1)) shouldBe
            PageAction.Load
    }

    // AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED) -->

    @Test
    fun `a skipped page positions the one after it, so skips chain`() {
        coverage(page = 3).actionFor(3, walk(verifiedThrough = 1, positionedThrough = 2)) shouldBe
            PageAction.Skip(nextPage = 4)
    }

    @Test
    fun `the chain is bounded by the stride, however far it has been positioned`() {
        // Pages 2, 3 and 4 were skipped off page 1. Page 5 is the stride away
        // from the last LOADED page, so it is loaded even though page 4 is
        // positioned and its own record is perfectly skippable.
        coverage(page = 5).actionFor(5, walk(verifiedThrough = 1, positionedThrough = 4)) shouldBe
            PageAction.Load
    }

    @Test
    fun `the page just inside the stride is still skipped`() {
        coverage(page = 4).actionFor(4, walk(verifiedThrough = 1, positionedThrough = 3)) shouldBe
            PageAction.Skip(nextPage = 5)
    }

    @Test
    fun `a matched load re-plants the root and the chain runs again`() {
        coverage(page = 6).actionFor(6, walk(verifiedThrough = 5, positionedThrough = 5)) shouldBe
            PageAction.Skip(nextPage = 7)
    }

    @Test
    fun `the stride is measured from the last load, not from the last skip`() {
        // positionedThrough is one page behind, as a chain always leaves it, so
        // a stride measured from it would never expire.
        val run = (2L..6L).map { page ->
            coverage(page = page).actionFor(
                page,
                walk(verifiedThrough = 1, positionedThrough = page - 1),
            )
        }
        run shouldBe listOf(
            PageAction.Skip(nextPage = 3),
            PageAction.Skip(nextPage = 4),
            PageAction.Skip(nextPage = 5),
            PageAction.Load,
            PageAction.Load,
        )
    }

    // <-- AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED)

    @Test
    fun `a record of a different page size is not applied`() {
        coverage(entryCount = 20).actionFor(2, walk()) shouldBe PageAction.Load
    }

    @Test
    fun `nothing is skipped before a full page has established the page size`() {
        coverage().actionFor(2, walk(listingPageSize = null)) shouldBe PageAction.Load
    }

    // Promise 4: nothing known to be stale.

    @Test
    fun `no page is skipped once a record has been contradicted`() {
        coverage().actionFor(2, walk(recordsTrusted = false)) shouldBe PageAction.Load
    }

    @Test
    fun `viewedFraction is zero rather than undefined for an empty page`() {
        coverage(entryCount = 0, viewed = 0).viewedFraction shouldBe 0f
    }

    @Test
    fun `isSkippable requires both the fraction and the tail`() {
        val default = SourcePageCoverage.COVERED_FRACTION
        coverage(viewed = pageSize, tailViewed = true).isSkippable(default) shouldBe true
        coverage(viewed = pageSize, tailViewed = false).isSkippable(default) shouldBe false
        coverage(viewed = 19, tailViewed = true).isSkippable(default) shouldBe false
    }

    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS) -->
    @Test
    fun `the covered fraction comes from the walk rather than the record`() {
        // 21 of 24 is 0.875: skippable at the default 0.85, not at 0.9, and the
        // record is the same observation either way.
        val record = coverage(viewed = 21, tailViewed = true)
        record.isSkippable(0.85f) shouldBe true
        record.isSkippable(0.9f) shouldBe false
        record.actionFor(2, walk(coveredFraction = 0.85f)) shouldBe PageAction.Skip(nextPage = 3)
        record.actionFor(2, walk(coveredFraction = 0.9f)) shouldBe PageAction.Load
    }

    @Test
    fun `a threshold of one skips only a wholly viewed page`() {
        coverage(viewed = pageSize, tailViewed = true).isSkippable(1f) shouldBe true
        coverage(viewed = pageSize - 1, tailViewed = true).isSkippable(1f) shouldBe false
    }
    // <-- AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
}
