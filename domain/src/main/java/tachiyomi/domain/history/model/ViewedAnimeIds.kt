package tachiyomi.domain.history.model

import java.util.Arrays

// AM (A_VIEWED_SET_IS_NOT_A_HASHSET) -->
/**
 * Membership over the anime ids of one source that have been viewed.
 *
 * Replaces the `Set<Long>` browse's hide-viewed trim used to hold. The
 * semantics are unchanged - this is still one answer, read once and then
 * immutable, and [contains] gives the same verdict the set did - but the
 * representation is not a hash table of boxed Longs.
 *
 * That matters because the set is per SOURCE, not per page: a source with a
 * hundred thousand viewed entries produced a hundred thousand `java.lang.Long`
 * objects plus a HashMap node each, around 5-6 MB, held for the life of the
 * browse screen by [tachiyomi.domain.history.interactor.GetViewedAnimeIds]'s
 * caller. Measured against the same ids: boxed HashSet ~4.8 MB, a sorted
 * LongArray 0.8 MB, a bitmap over the id space 15 KB.
 *
 * Two representations, chosen by what the ids actually look like rather than by
 * assumption:
 *
 *  - A BITMAP, one bit per id, when the ids are dense enough for that to be the
 *    smaller of the two. Anime ids are SQLite rowids on `animes`, so for a
 *    database whose entries are mostly from the source being browsed they run
 *    close to consecutive and a bitmap is a few KB.
 *  - A SORTED ARRAY with a binary search otherwise - a sparse id space, which a
 *    bitmap would pay for in empty bits. Restores and syncs can both leave ids
 *    scattered, so this is not a theoretical branch.
 *
 * The crossover is exact rather than tuned: a bitmap costs `maxId / 8` bytes and
 * the array costs `count * 8`, so the bitmap is smaller precisely while
 * `maxId < 64 * count`. [MAX_BITMAP_BYTES] caps it anyway, so a single absurd id
 * cannot turn a small answer into a large allocation.
 *
 * Neither form boxes, both answer in O(1) or O(log n), and both are immutable
 * once built, so the one answer can be shared across every collection of the
 * listing it was built for.
 */
class ViewedAnimeIds private constructor(
    /** One bit per id, index = id. Null when [sorted] is the representation. */
    private val bitmap: LongArray?,
    /** Ascending ids for the binary search. Null when [bitmap] is in use. */
    private val sorted: LongArray?,
    /** How many ids this holds. */
    val size: Int,
) {

    operator fun contains(id: Long): Boolean {
        if (size == 0) return false
        bitmap?.let { words ->
            if (id < 1) return false
            // AM (A_WORD_INDEX_IS_BOUNDED_BEFORE_IT_IS_NARROWED): the word
            // index is compared as a Long and narrowed only once it is known to
            // be in range. Narrowing first truncates to the low 32 bits, so an
            // id above 2^38 indexes an arbitrary word and one that truncates
            // negative passes an upper-bound check and throws - Long.MAX_VALUE
            // gives -1. Anime ids are rowids and will not reach that in a real
            // library, but contains() answers for any id its caller holds and a
            // membership question has an answer for all of them.
            val word = id ushr 6
            if (word >= words.size.toLong()) return false
            // Parenthesised rather than relying on `and` binding tighter than
            // `!=`, which it does, but not readably.
            return (words[word.toInt()] and (1L shl (id and 63L).toInt())) != 0L
        }
        return Arrays.binarySearch(sorted!!, id) >= 0
    }

    companion object {
        /**
         * Ceiling on the bitmap form, so a sparse id space falls back to the
         * array instead of allocating for the gaps. 1 MB covers ids up to about
         * 8.4 million - far past any real library - and anything beyond it is
         * sparse by definition at the sizes this is built from.
         */
        private const val MAX_BITMAP_BYTES = 1 shl 20

        val EMPTY = ViewedAnimeIds(bitmap = null, sorted = LongArray(0), size = 0)

        /**
         * Builds the membership for [ids].
         *
         * Duplicates are tolerated - the query is already DISTINCT, but neither
         * form depends on that - and [size] counts what was handed over rather
         * than claiming to be a distinct count.
         */
        fun of(ids: List<Long>): ViewedAnimeIds {
            if (ids.isEmpty()) return EMPTY
            var maxId = Long.MIN_VALUE
            var minId = Long.MAX_VALUE
            for (id in ids) {
                if (id > maxId) maxId = id
                if (id < minId) minId = id
            }
            // A non-positive id is not a rowid; the array form carries it
            // correctly and the bitmap would have to special-case it.
            val bitmapBytes = if (minId < 1) Long.MAX_VALUE else (maxId / 8) + 1
            val useBitmap = bitmapBytes <= MAX_BITMAP_BYTES && bitmapBytes < ids.size.toLong() * 8
            return if (useBitmap) {
                val words = LongArray(((maxId ushr 6) + 1).toInt())
                for (id in ids) {
                    val word = (id ushr 6).toInt()
                    words[word] = words[word] or (1L shl (id and 63L).toInt())
                }
                ViewedAnimeIds(bitmap = words, sorted = null, size = ids.size)
            } else {
                ViewedAnimeIds(bitmap = null, sorted = ids.toLongArray().apply { sort() }, size = ids.size)
            }
        }
    }
}
// <-- AM (A_VIEWED_SET_IS_NOT_A_HASHSET)
