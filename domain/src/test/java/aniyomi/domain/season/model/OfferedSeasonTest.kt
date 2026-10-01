// AM (NAMED_SEASONS) -->
package aniyomi.domain.season.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class OfferedSeasonTest {

    @Test
    fun `Offered season is labelled by the position it will occupy`() {
        offeredNextSeason(emptyList()).labelPosition shouldBe 1
        offeredNextSeason(seasons(1)).labelPosition shouldBe 2
        offeredNextSeason(seasons(1, 2)).labelPosition shouldBe 3
    }

    @Test
    fun `Offered season is positional, so gaps left by deletions do not show`() {
        offeredNextSeason(seasons(1, 5, 9)).labelPosition shouldBe 4
    }

    @Test
    fun `Provisional numbers are distinct and never collide with real ones`() {
        provisionalSeasonNumber(0) shouldBe -1L
        provisionalSeasonNumber(1) shouldBe -2L
        provisionalSeasonNumber(0).isProvisionalSeason() shouldBe true
        provisionalSeasonNumber(9).isProvisionalSeason() shouldBe true
        1L.isProvisionalSeason() shouldBe false
        10L.isProvisionalSeason() shouldBe false
    }

    private fun seasons(vararg numbers: Long) = numbers.mapIndexed { index, number ->
        EntrySeason(number = number, name = null, sortOrder = index.toLong())
    }
}
// <-- AM (NAMED_SEASONS)
