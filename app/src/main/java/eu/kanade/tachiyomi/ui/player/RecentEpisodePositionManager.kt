package eu.kanade.tachiyomi.ui.player

import animiru.domain.player.repository.EpisodeTempPositionRepository
import animiru.domain.player.service.PlayerPreferences
import eu.kanade.tachiyomi.data.media.MediaCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
@Inject
@SingleIn(AppScope::class)
class RecentEpisodePositionManager(
    private val repository: EpisodeTempPositionRepository,
    private val playerPreferences: PlayerPreferences,
) {

    private data class Entry(val positionMs: Long, val updatedAt: Long)

    private val cache = ConcurrentHashMap<Long, Entry>()
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
                    cache.merge(saved.episodeId, Entry(saved.positionMs, saved.updatedAt)) { existing, fromDb ->
                        if (existing.updatedAt >= fromDb.updatedAt) existing else fromDb
                    }
                }
                hydrated = true
            }
        }
    }

    // AM (MERGED_SOURCES) -->
    /**
     * Removes and returns the temp position for [episodeId], if one exists -
     * consumed once, on the assumption the caller is about to resume live playback of
     * it, at which point live position-tracking takes over.
     *
     * Keyed on the episode alone, not (anime, episode): the same episode reached
     * through two different library entries - a merged entry and one of its children -
     * is the same episode, and used to keep two positions that reverted to a stale one
     * on switching back.
     */
    fun consume(episodeId: Long): Long? {
        val removed = cache.remove(episodeId)?.positionMs
        if (removed != null) {
            scope.launch { repository.delete(episodeId) }
        }
        return removed
    }

    // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
    /**
     * Called when the retention setting changes, so turning it off frees the disk
     * immediately rather than at the next prune.
     */
    fun onRetainMediaPreferenceChanged() {
        scope.launch {
            // AM (RETAIN_MEDIA_SIZE_LIMIT) -->
            MediaCache.peek()?.setMaxBytes(playerPreferences.retainRecentEpisodeMediaMaxBytes.get())
            // <-- AM (RETAIN_MEDIA_SIZE_LIMIT)
            syncRetainedMedia()
        }
    }
    // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
    // <-- AM (MERGED_SOURCES)

    /**
     * Remembers a temp position for [episodeId] (see [consume] on the key), or clears
     * any existing one if
     * [positionMs] is at or past [durationMs] minus a one-second buffer. That buffer is
     * deliberate: the same tick that crosses the true final second is also the one
     * deciding whether to transition to the next episode, so wiping exactly on that
     * boundary races the transition. Settling the wipe a second early means it's always
     * done before a transition can even start.
     */
    fun remember(episodeId: Long, positionMs: Long, durationMs: Long) {
        if (durationMs > 0L && positionMs >= durationMs - 1000L) {
            if (cache.remove(episodeId) != null) {
                scope.launch { repository.delete(episodeId) }
            }
            return
        }
        if (positionMs <= 0L) return

        val updatedAt = System.currentTimeMillis()
        cache[episodeId] = Entry(positionMs, updatedAt)
        scope.launch {
            repository.upsert(episodeId, positionMs, updatedAt)
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
        // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
        syncRetainedMedia()
        // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
    }

    // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
    /**
     * Keeps the media cache's contents in step with this table.
     *
     * This table is what "recent" means for the retention setting, so an episode
     * losing its slot - by being consumed, or pruned when the limit shrinks - is
     * precisely when its cached bytes stop being worth disk space. Doing it here
     * rather than on a timer or a size trigger means the two never disagree.
     *
     * Nothing happens when no media has been proxied yet (no cache exists), and an
     * entry being read right now is never dropped.
     */
    private fun syncRetainedMedia() {
        val mediaCache = MediaCache.peek() ?: return
        if (playerPreferences.retainRecentEpisodeMediaMaxBytes.get() <= 0) {
            mediaCache.retainOnly(emptySet())
            return
        }
        mediaCache.retainOnly(cache.keys.toSet())
    }
    // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)

    /**
     * Called when the user changes the "temporary position memory" setting, so a
     * reduced limit is enforced immediately rather than waiting for the next write.
     */
    fun onSlotsPreferenceChanged() {
        scope.launch { pruneToCurrentLimit() }
    }
}
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
