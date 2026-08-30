package eu.kanade.tachiyomi.ui.player

import animiru.domain.player.repository.EpisodeTempPositionRepository
import animiru.domain.player.service.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

// AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
/**
 * Single app-process-wide temp-position cache, replacing what used to be two separate
 * in-memory maps (one on PlayerViewModel, one on PlayerMediaHolder) that had to be kept
 * in sync by hand. PlayerBackgroundPlaybackService runs in the same process as the
 * Activity/ViewModel (no android:process split in the manifest), so a genuine singleton
 * here - registered once via Injekt, shared by both - means there's only ever one copy
 * of this cache, not two that can drift apart.
 *
 * All reads/writes are synchronous, in-memory-only - callers get the same instant
 * behaviour the old plain map gave them. Every write also fires an async, non-blocking
 * save to [repository] so the cache survives process death; hydration from that table
 * back into memory starts immediately at construction (see init), racing the
 * async work a session already does before its first real resume lookup - in practice
 * that's enough of a head start, though it's a best-effort ordering, not a guarantee
 * (see consume()'s doc for what happens on the rare miss).
 *
 * Pruning is MRU by [EpisodeTempPositionRepository]/timestamp, not the old
 * playlist-index-distance scheme - simpler, and matches what
 * `recentEpisodePositionSlots` describes.
 */
class RecentEpisodePositionManager(
    private val repository: EpisodeTempPositionRepository,
    private val playerPreferences: PlayerPreferences,
) {

    private data class Entry(val positionMs: Long, val updatedAt: Long)

    private val cache = ConcurrentHashMap<Pair<Long, Long>, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hydrationMutex = Mutex()

    @Volatile
    private var hydrated = false

    init {
        // Kicked off immediately at construction (first Injekt.get() of this singleton,
        // typically during PlayerActivity/ViewModel startup), not lazily on first
        // consume()/remember() call - so it has as much of a head start as possible
        // against the async video/hoster-resolution work that happens before the first
        // real resume lookup. This is a best-effort ordering, not a hard guarantee: if a
        // resume lookup genuinely does race ahead of this, consume() just returns null
        // (same as "nothing cached"), falling through to the normal seen/DB-position
        // resume path - correct, just missing the temp-cache hit that one time.
        hydrate()
    }

    private fun hydrate() {
        if (hydrated) return
        scope.launch {
            hydrationMutex.withLock {
                if (hydrated) return@withLock
                repository.getAll().forEach { saved ->
                    // Don't clobber a newer in-memory entry with a stale DB row if
                    // something was already written (e.g. two rapid switches) before
                    // hydration finished.
                    cache.merge(saved.animeId to saved.episodeId, Entry(saved.positionMs, saved.updatedAt)) { existing, fromDb ->
                        if (existing.updatedAt >= fromDb.updatedAt) existing else fromDb
                    }
                }
                hydrated = true
            }
        }
    }

    /**
     * Removes and returns the temp position for (animeId, episodeId), if one exists -
     * consumed once, on the assumption the caller is about to resume live playback of
     * it, at which point live position-tracking takes over.
     */
    fun consume(animeId: Long, episodeId: Long): Long? {
        val removed = cache.remove(animeId to episodeId)?.positionMs
        if (removed != null) {
            scope.launch { repository.delete(animeId, episodeId) }
        }
        return removed
    }

    /**
     * Remembers a temp position for (animeId, episodeId), or clears any existing one if
     * [positionMs] is at or past [durationMs] minus a one-second buffer. That buffer is
     * deliberate: the same tick that crosses the true final second is also the one
     * deciding whether to transition to the next episode, so wiping exactly on that
     * boundary races the transition. Settling the wipe a second early means it's always
     * done before a transition can even start.
     */
    fun remember(animeId: Long, episodeId: Long, positionMs: Long, durationMs: Long) {
        val key = animeId to episodeId
        if (durationMs > 0L && positionMs >= durationMs - 1000L) {
            if (cache.remove(key) != null) {
                scope.launch { repository.delete(animeId, episodeId) }
            }
            return
        }
        if (positionMs <= 0L) return

        val updatedAt = System.currentTimeMillis()
        cache[key] = Entry(positionMs, updatedAt)
        scope.launch {
            repository.upsert(animeId, episodeId, positionMs, updatedAt)
            pruneToCurrentLimit()
        }
    }

    private suspend fun pruneToCurrentLimit() {
        val maxSlots = playerPreferences.recentEpisodePositionSlots.get()
        if (cache.size > maxSlots) {
            cache.entries
                .sortedByDescending { it.value.updatedAt }
                .drop(maxSlots)
                .forEach { cache.remove(it.key) }
        }
        repository.pruneToMostRecent(maxSlots)
    }

    /**
     * Called when the user changes the "temporary position memory" setting, so a
     * reduced limit is enforced immediately rather than waiting for the next write.
     */
    fun onSlotsPreferenceChanged() {
        scope.launch { pruneToCurrentLimit() }
    }
}
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
