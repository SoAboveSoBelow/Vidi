package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * AM (A_BROWSE_HAS_A_REQUEST_BUDGET)
 *
 * The budget is the GUARANTEE half of the browse design - page skipping is
 * best-effort, this is what is actually standing between a deep walk and a rate
 * limit - so the cases that matter are the ones where it would quietly stop
 * refusing: an off-by-one at the ceiling, one source spending another's
 * permits, or a reset that does not.
 *
 * Windows here are long enough that no test depends on time passing, except the
 * two that are about expiry and say so.
 */
@Execution(ExecutionMode.CONCURRENT)
class SourceRequestBudgetTest {

    private val window = 60.seconds

    @Test
    fun `permits are granted up to the ceiling and then refused`() {
        val budget = SourceRequestBudget()
        repeat(3) { budget.tryAcquire(1L, permits = 3, window = window) shouldBe true }
        budget.tryAcquire(1L, permits = 3, window = window) shouldBe false
        budget.tryAcquire(1L, permits = 3, window = window) shouldBe false
    }

    @Test
    fun `a zero ceiling refuses everything`() {
        SourceRequestBudget().tryAcquire(1L, permits = 0, window = window) shouldBe false
    }

    @Test
    fun `one source cannot spend another's permits`() {
        val budget = SourceRequestBudget()
        repeat(3) { budget.tryAcquire(1L, permits = 3, window = window) shouldBe true }
        budget.tryAcquire(1L, permits = 3, window = window) shouldBe false
        // A different source is a different window.
        budget.tryAcquire(2L, permits = 3, window = window) shouldBe true
    }

    @Test
    fun `timeUntilPermit is zero while the window has room`() {
        val budget = SourceRequestBudget()
        budget.timeUntilPermit(1L, permits = 3, window = window) shouldBe Duration.ZERO
        budget.tryAcquire(1L, permits = 3, window = window) shouldBe true
        budget.timeUntilPermit(1L, permits = 3, window = window) shouldBe Duration.ZERO
    }

    @Test
    fun `timeUntilPermit reports the wait once the window is full`() {
        val budget = SourceRequestBudget()
        repeat(3) { budget.tryAcquire(1L, permits = 3, window = window) }
        val wait = budget.timeUntilPermit(1L, permits = 3, window = window)
        (wait > Duration.ZERO) shouldBe true
        // Never longer than the window itself: it is measured from the oldest mark.
        (wait <= window) shouldBe true
    }

    @Test
    fun `timeUntilPermit is zero for a source that has never been seen`() {
        SourceRequestBudget().timeUntilPermit(999L, permits = 3, window = window) shouldBe Duration.ZERO
    }

    @Test
    fun `reset forgets a source's window`() {
        val budget = SourceRequestBudget()
        repeat(3) { budget.tryAcquire(1L, permits = 3, window = window) }
        budget.tryAcquire(1L, permits = 3, window = window) shouldBe false
        budget.reset(1L)
        budget.tryAcquire(1L, permits = 3, window = window) shouldBe true
    }

    @Test
    fun `reset affects only the source asked for`() {
        val budget = SourceRequestBudget()
        repeat(3) { budget.tryAcquire(1L, permits = 3, window = window) }
        repeat(3) { budget.tryAcquire(2L, permits = 3, window = window) }
        budget.reset(1L)
        budget.tryAcquire(1L, permits = 3, window = window) shouldBe true
        budget.tryAcquire(2L, permits = 3, window = window) shouldBe false
    }

    @Test
    fun `marks older than the window stop counting`() {
        val budget = SourceRequestBudget()
        val brief = 1.milliseconds
        repeat(3) { budget.tryAcquire(1L, permits = 3, window = brief) shouldBe true }
        budget.tryAcquire(1L, permits = 3, window = brief) shouldBe false
        Thread.sleep(20)
        // The window has passed, so the marks in it are no longer spent.
        budget.tryAcquire(1L, permits = 3, window = brief) shouldBe true
    }

    @Test
    fun `an expired window reports no wait`() {
        val budget = SourceRequestBudget()
        val brief = 1.milliseconds
        repeat(3) { budget.tryAcquire(1L, permits = 3, window = brief) }
        Thread.sleep(20)
        budget.timeUntilPermit(1L, permits = 3, window = brief) shouldBe Duration.ZERO
    }
}
