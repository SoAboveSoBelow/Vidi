package eu.kanade.tachiyomi.ui.anime

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import kotlin.random.Random

/**
 * Stores a per-anime shuffle seed, keyed dynamically by anime id, the same
 * way anime.sorting/anime.unseenFilterRaw etc. are stored on the Anime DB
 * record itself rather than passed between screens. Any screen that knows
 * the anime id (the episode list, the player) can independently read the
 * same seed and derive an identical episode order without any explicit
 * wiring between them.
 *
 * A seed of 0L means shuffle is disabled for that anime. Any other value
 * means shuffle is enabled, and is used to deterministically scramble
 * episode order via [episodeShuffleSortKey] - re-enabling shuffle picks a
 * fresh random seed, producing a genuinely new order each time.
 */
@Inject
@SingleIn(AppScope::class)
class EpisodeShufflePreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun seed(animeId: Long): Preference<Long> {
        return preferenceStore.getLong("episode_shuffle_seed_$animeId", 0L)
    }

    fun isShuffleEnabled(animeId: Long): Boolean = seed(animeId).get() != 0L

    fun enableShuffle(animeId: Long) {
        // Avoid 0L, since that's the "disabled" sentinel.
        var newSeed = Random.nextLong()
        while (newSeed == 0L) {
            newSeed = Random.nextLong()
        }
        seed(animeId).set(newSeed)
    }

    fun disableShuffle(animeId: Long) {
        seed(animeId).set(0L)
    }

    fun toggleShuffle(animeId: Long) {
        if (isShuffleEnabled(animeId)) disableShuffle(animeId) else enableShuffle(animeId)
    }
}

/**
 * Deterministic pseudo-random sort key derived from a per-anime seed and an
 * episode id, using a splitmix64-style bit mixer. Stable for a given
 * (seed, episodeId) pair, so both the episode list and the player produce
 * the same order independently, without needing to share in-memory state.
 */
fun episodeShuffleSortKey(seed: Long, episodeId: Long): Long {
    var h = seed xor (episodeId * -0x61c8864680b583ebL)
    h = (h xor (h ushr 30)) * 0xbf58476d1ce4e5b9uL.toLong()
    h = (h xor (h ushr 27)) * 0x94d049bb133111ebuL.toLong()
    return h xor (h ushr 31)
}

// AM (SEASON_SCOPED_SHUFFLE) -->
/**
 * Shuffled order that keeps seasons in sequence: seasons stay in their display
 * order and only the episodes inside each one are scrambled.
 *
 * Shuffling the whole list flat made season boundaries meaningless - a shuffled
 * playlist crossed one on nearly every step - which is why the player had to
 * drop its season map and give up the next-season prompt entirely while
 * shuffle was on. Scoping the scramble to within a season keeps boundaries
 * monotonic, so the prompt fires exactly once per season again.
 *
 * [seasonRankOf] is a display rank, not the season number: season numbers are
 * user-managed and need not be ordered. Anything without a season sorts last
 * rather than being dropped.
 *
 * One definition for all three surfaces - the episode list, the player's
 * playlist, and the merged entry's next-unseen lookup. They derive their order
 * independently from the same seed, so a change to what "shuffled" means has
 * to be a change in one place or they silently disagree about what plays next.
 */
fun <T> List<T>.shuffledWithinSeasons(
    seed: Long,
    idOf: (T) -> Long,
    seasonRankOf: (T) -> Int?,
): List<T> = sortedWith(
    compareBy(
        { seasonRankOf(it) ?: Int.MAX_VALUE },
        { episodeShuffleSortKey(seed, idOf(it)) },
    ),
)
// <-- AM (SEASON_SCOPED_SHUFFLE)
