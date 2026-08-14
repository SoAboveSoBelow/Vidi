package eu.kanade.tachiyomi.ui.anime

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
        // A fresh shuffle order invalidates wherever the old order had gotten to, so
        // "Continue" should fall back to picking the first unseen episode in the new
        // order rather than resuming from a spot that no longer means anything.
        lastWatched(animeId).delete()
        lastWatchedPositionMs(animeId).delete()
    }

    fun disableShuffle(animeId: Long) {
        seed(animeId).set(0L)
    }

    fun toggleShuffle(animeId: Long) {
        if (isShuffleEnabled(animeId)) disableShuffle(animeId) else enableShuffle(animeId)
    }

    /**
     * The last episode watched from this anime's shuffled playlist, used so "Continue"
     * can resume right after it instead of jumping to whatever the first unseen episode
     * in shuffled order happens to be (which may not match what was actually last
     * watched, if episodes weren't watched strictly top-to-bottom). A value of 0L means
     * nothing has been recorded (or it was wiped by a reshuffle).
     *
     * Only meaningful while shuffle is enabled; ignored entirely otherwise.
     */
    fun lastWatched(animeId: Long): Preference<Long> {
        return preferenceStore.getLong("episode_shuffle_last_watched_$animeId", 0L)
    }

    /**
     * Live playback position (ms) within [lastWatched]'s episode. Tracked separately
     * from the episode's own last_second_seen, which gets reset to 0 on exit for any
     * episode that's already crossed the "seen" threshold (unless the user has opted
     * into preserveWatchingPosition) - exactly the case Continue's shuffle resume needs
     * to survive, since stopping partway through an episode often crosses that
     * threshold before you actually stop watching.
     */
    fun lastWatchedPositionMs(animeId: Long): Preference<Long> {
        return preferenceStore.getLong("episode_shuffle_last_watched_position_$animeId", 0L)
    }

    /**
     * Records [episodeId] as the last watched episode for [animeId]'s shuffled playlist.
     * No-ops if shuffle isn't enabled, or if the playlist only has a single entry (there's
     * nothing meaningful to "continue" from in that case).
     */
    fun recordLastWatched(animeId: Long, episodeId: Long, playlistSize: Int) {
        if (playlistSize <= 1) return
        if (!isShuffleEnabled(animeId)) return
        lastWatched(animeId).set(episodeId)
    }

    /**
     * Updates the live position for [episodeId], but only while it's still the
     * recorded [lastWatched] episode - avoids a stray write racing in right as a
     * different episode is being switched to.
     */
    fun recordLastWatchedPosition(animeId: Long, episodeId: Long, positionMs: Long) {
        if (!isShuffleEnabled(animeId)) return
        if (lastWatched(animeId).get() != episodeId) return
        lastWatchedPositionMs(animeId).set(positionMs)
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
