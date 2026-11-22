package tachiyomi.domain.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED) */
@Execution(ExecutionMode.CONCURRENT)
class SourceListingHealthTest {

    private fun health(failures: Int, updatedAt: Long = 0L) = SourceListingHealth(
        sourceId = 1L,
        listingKey = "popular|",
        failures = failures,
        updatedAt = updatedAt,
    )

    @Test
    fun `one ordinary shift does not suppress a listing`() {
        // Something was published between two walks; that is not instability.
        health(failures = 1).isSuppressed(now = 0L) shouldBe false
    }

    @Test
    fun `suppression begins at the failure ceiling`() {
        health(failures = SourceListingHealth.MAX_FAILURES - 1).isSuppressed(0L) shouldBe false
        health(failures = SourceListingHealth.MAX_FAILURES).isSuppressed(0L) shouldBe true
    }

    @Test
    fun `suppression lifts once the retry window has passed`() {
        val h = health(failures = SourceListingHealth.MAX_FAILURES, updatedAt = 0L)
        h.isSuppressed(now = SourceListingHealth.RETRY_AFTER_MS - 1) shouldBe true
        h.isSuppressed(now = SourceListingHealth.RETRY_AFTER_MS) shouldBe false
    }

    @Test
    fun `a source that goes quiet is given another walk rather than written off`() {
        val h = health(failures = 99, updatedAt = 0L)
        h.isSuppressed(now = SourceListingHealth.RETRY_AFTER_MS * 2) shouldBe false
    }
}
