package tachiyomi.domain.history.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * AM (A_VIEWED_SET_IS_NOT_A_HASHSET)
 *
 * The representation is private and deliberately so, which means the thing to
 * test is that it is INVISIBLE: whichever form is chosen, membership has to be
 * the same verdict the HashSet it replaced would have given. Each case below
 * pins one side of the crossover, so a change to the selection rule that breaks
 * either form shows up as a wrong answer rather than as a silent regression in
 * the one that is rarely taken.
 */
@Execution(ExecutionMode.CONCURRENT)
class ViewedAnimeIdsTest {

    /** The verdict the replaced Set<Long> would have given, for every id in range. */
    private fun assertAgreesWithSet(ids: List<Long>, probeUpTo: Long) {
        val expected = ids.toHashSet()
        val actual = ViewedAnimeIds.of(ids)
        for (id in -2L..probeUpTo) {
            (id in actual) shouldBe (id in expected)
        }
    }

    @Test
    fun `empty holds nothing`() {
        val ids = ViewedAnimeIds.of(emptyList())
        ids.size shouldBe 0
        (1L in ids) shouldBe false
        (0L in ids) shouldBe false
        (-1L in ids) shouldBe false
    }

    @Test
    fun `dense ids agree with the set they replaced`() {
        // maxId 500 against 500 ids: 63 bytes versus 4000, so this is the bitmap.
        assertAgreesWithSet((1L..500L).toList(), probeUpTo = 520L)
    }

    @Test
    fun `sparse ids agree with the set they replaced`() {
        // Ten ids spread over a million: the bitmap would pay for the gaps.
        assertAgreesWithSet((0..9).map { it * 100_000L + 7L }, probeUpTo = 200L)
    }

    @Test
    fun `ids either side of the crossover agree with the set`() {
        // The bitmap is smaller exactly while maxId < 64 * count.
        val count = 100
        val justDense = (1L..count.toLong()).toList() + listOf(64L * count - 1)
        val justSparse = (1L..count.toLong()).toList() + listOf(64L * count + 1024)
        assertAgreesWithSet(justDense, probeUpTo = 64L * count + 2)
        assertAgreesWithSet(justSparse, probeUpTo = 64L * count + 2)
    }

    @Test
    fun `a single absurd id does not force a large allocation to answer wrongly`() {
        // Past MAX_BITMAP_BYTES, so the array form carries it.
        val ids = listOf(1L, 2L, 3L, 90_000_000L)
        val actual = ViewedAnimeIds.of(ids)
        (1L in actual) shouldBe true
        (3L in actual) shouldBe true
        (4L in actual) shouldBe false
        (90_000_000L in actual) shouldBe true
        (89_999_999L in actual) shouldBe false
    }

    @Test
    fun `non-positive ids are carried, not dropped`() {
        // Not rowids, so the bitmap cannot hold them and the array must.
        val ids = listOf(-5L, 0L, 1L, 9L)
        assertAgreesWithSet(ids, probeUpTo = 12L)
        (-5L in ViewedAnimeIds.of(ids)) shouldBe true
        (0L in ViewedAnimeIds.of(ids)) shouldBe true
    }

    @Test
    fun `an id above the bitmap's last word is absent rather than out of bounds`() {
        val ids = ViewedAnimeIds.of((1L..100L).toList())
        (10_000L in ids) shouldBe false
        (Long.MAX_VALUE in ids) shouldBe false
    }

    @Test
    fun `an id whose word index truncates into range is still absent`() {
        // The louder half of the same defect throws; this is the quiet half. Of
        // ids 1..100 the bitmap is two words, and (1 shl 38) + 64 has word
        // index (1 shl 32) + 1, which narrows to 1 - in range, bit 0 set,
        // because id 64 is present. A wrong `true`, with nothing to notice.
        val ids = ViewedAnimeIds.of((1L..100L).toList())
        (64L in ids) shouldBe true
        ((1L shl 38) + 64L in ids) shouldBe false
    }

    @Test
    fun `duplicates are tolerated and size counts what was handed over`() {
        val ids = ViewedAnimeIds.of(listOf(1L, 1L, 2L, 2L, 2L))
        ids.size shouldBe 5
        (1L in ids) shouldBe true
        (2L in ids) shouldBe true
        (3L in ids) shouldBe false
    }

    @Test
    fun `a single id is held in either form`() {
        (7L in ViewedAnimeIds.of(listOf(7L))) shouldBe true
        (8L in ViewedAnimeIds.of(listOf(7L))) shouldBe false
    }
}
