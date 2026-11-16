package eu.kanade.tachiyomi.ui.player

import animiru.domain.player.repository.SessionTempPositionRepository
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
 * Pruning is MRU by [SessionTempPositionRepository]/timestamp, not the old
 * playlist-index-distance scheme - simpler, and matches what
 * `recentEpisodePositionSlots` describes.
 *
 * AM (SESSION_KEYED_TEMP_POSITIONS): keyed by [SessionKey], not by episode id.
 * It was named for episodes because they were the only thing that could hold a
 * position, which made this the one storage layer a non-library session could
 * not use - the reason a feed card's progress had nowhere to go and was lost
 * the moment the real player asked for it. What a session is stays opaque here;
 * only storageKeyEpisodeId looks inside a key, and only because the media
 * cache is genuinely episode-scoped.
 */
@Inject
@SingleIn(AppScope::class)
class RecentPositionManager(
    private val repository: SessionTempPositionRepository,
    private val playerPreferences: PlayerPreferences,
) {

    private data class Entry(val positionMs: Long, val updatedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
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
                    cache.merge(saved.sessionKey, Entry(saved.positionMs, saved.updatedAt)) { existing, fromDb ->
                        if (existing.updatedAt >= fromDb.updatedAt) existing else fromDb
                    }
                }
                hydrated = true
            }
        }
    }

    // AM (MERGED_SOURCES) -->
    /**
     * Removes and returns the temp position for [key], if one exists -
     * consumed once, on the assumption the caller is about to resume live playback of
     * it, at which point live position-tracking takes over.
     *
     * A library key still holds both ids, but stores as the episode alone, so the same
     * episode reached through a merged entry and through one of its children keeps one
     * position rather than drifting between two - see [SessionKey.storageKey].
     */
    fun consume(key: SessionKey): Long? {
        val stored = key.storageKey
        val removed = cache.remove(stored)?.positionMs
        if (removed != null) {
            scope.launch { repository.delete(stored) }
        }
        return removed
    }


    // AM (ONE_DEFINITION_OF_THE_RESUME_POINT) -->
    /**
     * The temp position for [key] WITHOUT consuming it.
     *
     * [consume] removes what it returns, on the premise that the caller is about
     * to resume playback and live tracking takes over. A caller that only wants
     * to DISPLAY where the session will resume - the seekbar and the
     * notification, during the switch and before the load - must not take it
     * away from the load that follows.
     */
    fun peek(key: SessionKey): Long? = cache[key.storageKey]?.positionMs
    // <-- AM (ONE_DEFINITION_OF_THE_RESUME_POINT)

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
            // AM (BYTES_ARE_NOT_TIED_TO_TEMP_MEMORY)
            applyRetentionDisabled()
        }
    }
    // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
    // <-- AM (MERGED_SOURCES)

    /**
     * Remembers a temp position for [key] (see [consume] on the key), or clears
     * any existing one if
     * [positionMs] is at or past [durationMs] minus a one-second buffer. That buffer is
     * deliberate: the same tick that crosses the true final second is also the one
     * deciding whether to transition to the next episode, so wiping exactly on that
     * boundary races the transition. Settling the wipe a second early means it's always
     * done before a transition can even start.
     */
    fun remember(key: SessionKey, positionMs: Long, durationMs: Long) {
        val stored = key.storageKey
        if (durationMs > 0L && positionMs >= durationMs - 1000L) {
            if (cache.remove(stored) != null) {
                scope.launch { repository.delete(stored) }
            }
            return
        }
        if (positionMs <= 0L) return

        val updatedAt = System.currentTimeMillis()
        cache[stored] = Entry(positionMs, updatedAt)
        // AM (TRIM_TO_RESUME_WINDOW): the cache keeps the bytes around this
        // point when it cannot keep them all. Both numbers are passed because
        // turning a position into a byte offset needs the duration, and this is
        // the only place that has it next to the position. Nothing to do with
        // WHETHER bytes are kept - see BYTES_ARE_NOT_TIED_TO_TEMP_MEMORY - only
        // with which of them survive a trim.
        storageKeyEpisodeId(stored)?.let { episodeId ->
            MediaCache.peek()?.setResumePosition(episodeId, positionMs, durationMs)
        }
        scope.launch {
            repository.upsert(stored, positionMs, updatedAt)
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
        // AM (BYTES_ARE_NOT_TIED_TO_TEMP_MEMORY): a position slot being pruned
        // says nothing about whether cached bytes are worth keeping.
    }

    // AM (BYTES_ARE_NOT_TIED_TO_TEMP_MEMORY) -->
    /**
     * Applies the retention setting's OFF state to the media cache.
     *
     * Nothing else. This used to call retainOnly() with this table's keys on
     * every write and prune, which tied the lifetime of cached bytes to the
     * lifetime of a position slot: consume() deletes an episode's row the
     * moment that episode is resumed, so resuming an episode was what made its
     * own bytes evictable, and recentEpisodePositionSlots - a count of
     * POSITIONS - silently decided how many episodes could keep media at all.
     *
     * The two are unrelated. Cached bytes accumulate as they are read and are
     * bounded by their own ceiling, which evictIfNeeded enforces, oldest first,
     * trimming the newest to its resume window rather than deleting it. That is
     * the whole policy, and it needs no help from this table.
     *
     * A ceiling of zero is the setting being off, which is the one thing this
     * table's own preference observer is still the right place to act on: it is
     * what turns the feature off, and the disk should come back immediately
     * rather than at some later read.
     */
    private fun applyRetentionDisabled() {
        val mediaCache = MediaCache.peek() ?: return
        if (playerPreferences.retainRecentEpisodeMediaMaxBytes.get() <= 0) {
            mediaCache.retainOnly(emptySet())
        }
    }
    // <-- AM (BYTES_ARE_NOT_TIED_TO_TEMP_MEMORY)

    /**
     * Called when the user changes the "temporary position memory" setting, so a
     * reduced limit is enforced immediately rather than waiting for the next write.
     */
    fun onSlotsPreferenceChanged() {
        scope.launch { pruneToCurrentLimit() }
    }
}
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
