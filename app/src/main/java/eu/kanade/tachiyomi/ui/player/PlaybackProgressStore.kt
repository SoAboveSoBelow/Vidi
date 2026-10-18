package eu.kanade.tachiyomi.ui.player

import android.content.Context
import animiru.domain.player.service.PlayerPreferences
import eu.kanade.domain.connection.SyncPreferences
import eu.kanade.tachiyomi.data.connection.syncmiru.SyncDataJob
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import java.util.Date

// AM (CONTENT_AGNOSTIC_SESSION) -->
/**
 * Where a session's playback progress goes.
 *
 * One of the three things PlayerViewModel conflated into the Anime/Episode
 * pair: what to play, what plays next, and where progress is written. This is
 * the third. A library episode writes last_second_seen, seen, a history row and
 * a sync trigger; a video opened from a file manager has no library row for any
 * of that and needs its position kept somewhere else entirely. Both are
 * "remember where I got to", which is what this interface names.
 *
 * Bound to one session at construction rather than taking a [SessionKey] per
 * call: a store that already knows what it is writing for cannot be handed the
 * wrong key, and the per-session guards below ("history written yet?") become
 * plain fields instead of ids compared against the current episode on every
 * tick. [PlayerViewModel.progressStoreFor] owns the lifetime.
 */
interface PlaybackProgressStore {

    /** What this store writes progress for. */
    val sessionKey: SessionKey

    /**
     * Persists the current position.
     *
     * @param writeSeenState true only when the caller is itself intentionally
     * changing seen/bookmark - an episode naturally finishing. Routine ticks
     * pass false and own position only; see TICK_NEVER_OWNS_SEEN.
     */
    suspend fun savePosition(writeSeenState: Boolean)

    /** Records that this was watched, at most once per session. */
    suspend fun recordWatched()
}

/**
 * The library-episode store - every session that exists today.
 *
 * This is PlayerViewModel.saveEpisodeProgress/saveEpisodeHistory moved, not
 * rewritten: the version-staleness check (RECENT_EPISODE_POSITIONS), the
 * WIPE_POSITION_ON_SEEN interaction with it, the SAVE_ONLY_WHAT_CHANGED
 * null-coalescing and the two once-per-episode guards all behave exactly as
 * they did. The only structural change is that the guards are fields of a
 * per-episode object instead of episode ids held on the ViewModel and compared
 * each call, which says the same thing: a store exists for one episode, so
 * "already written" needs no id to check against.
 *
 * Mutates [episode] in place (seen/bookmark/version/last_second_seen), as the
 * original did - the rest of the ViewModel reads those fields back off the same
 * object. That coupling is the legacy `data.database.models.Episode` type's,
 * not this interface's, and unifying it with the domain Episode is its own
 * separate refactor (see PlayerMediaHolder.syncSessionState's note on the two
 * Episode types).
 */
class LibraryProgressStore(
    private val episode: Episode,
    override val sessionKey: SessionKey.Library,
    private val context: Context,
    private val getEpisode: GetEpisode,
    private val updateEpisode: UpdateEpisode,
    private val upsertHistory: UpsertHistory,
    private val playerPreferences: PlayerPreferences,
    private val syncPreferences: SyncPreferences,
    /** Read per call, not captured: both can change mid-session. */
    private val isIncognito: () -> Boolean,
    private val hasTrackers: () -> Boolean,
) : PlaybackProgressStore {

    /** Was syncRequestedForEpisodeId - see SYNC_ON_OPEN_RUNS_ONCE. */
    private var syncRequested = false

    /** Was historyWrittenForEpisodeId - see PERSIST_ON_CADENCE. */
    private var historyWritten = false

    /** Saves progress if not in incognito mode, or if there is at least one tracker. */
    override suspend fun savePosition(writeSeenState: Boolean) {
        if (isIncognito() && !hasTrackers()) return

        // AM (RECENT_EPISODE_POSITIONS) -->
        // currentPlaylist is loaded once per player session and never refreshed, so our
        // in-memory seen can go stale in EITHER direction if it's toggled elsewhere
        // while this player stays alive (e.g. from the anime screen during PIP). The
        // `version` column bumps on every real seen/bookmark/last_second_seen change
        // (see the episodes.sq trigger), so use it to detect a change we don't know
        // about and defer to the DB's seen instead of blindly overwriting it. This is
        // now purely for keeping local/UI state accurate during a live session - the
        // DB write itself no longer depends on it, see writeSeenState above.
        val fresh = getEpisode.await(episode.id!!)
        if (fresh != null && fresh.version != episode.version) {
            episode.seen = fresh.seen
            episode.bookmark = fresh.bookmark
            episode.version = fresh.version
            // AM (WIPE_POSITION_ON_SEEN) -->
            // If this refresh just picked up an external "marked as seen" that
            // happened while this episode was still actively playing, don't let
            // the position this tick already computed (from the pre-refresh,
            // stale `seen = false`) get written back below and reclobber the
            // wipe the external mark-as-seen action just did.
            if (episode.seen && !playerPreferences.preserveWatchingPosition.get()) {
                episode.last_second_seen = 0L
            }
            // <-- AM (WIPE_POSITION_ON_SEEN)
        }
        val willChangeTrackedFields = fresh != null && (
            (writeSeenState && episode.seen != fresh.seen) ||
                (writeSeenState && episode.bookmark != fresh.bookmark) ||
                episode.last_second_seen != fresh.lastSecondSeen
            )
        // <-- AM (RECENT_EPISODE_POSITIONS)
        // AM (SAVE_ONLY_WHAT_CHANGED) -->
        // Every field is sent as null unless it actually differs from the row,
        // and the write is skipped outright when nothing does. This path runs
        // on a cadence AND on pause, seek, episode change and teardown, so
        // saves that re-state the same position were routine - each one an
        // UPDATE that rewrote unchanged columns and, through the episodes.sq
        // version trigger, bumped `version` and woke every flow watching the
        // episode list for a change that was not one.
        val changedFillermark = episode.fillermark.takeIf { fresh == null || it != fresh.fillermark }
        val changedTotalSeconds = episode.total_seconds.takeIf { fresh == null || it != fresh.totalSeconds }
        val changedPosition = episode.last_second_seen.takeIf { fresh == null || it != fresh.lastSecondSeen }
        val changedSeen = episode.seen.takeIf { writeSeenState && (fresh == null || it != fresh.seen) }
        val changedBookmark = episode.bookmark.takeIf { writeSeenState && (fresh == null || it != fresh.bookmark) }
        val hasChanges = changedFillermark != null ||
            changedTotalSeconds != null ||
            changedPosition != null ||
            changedSeen != null ||
            changedBookmark != null
        if (hasChanges) {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episode.id!!,
                    seen = changedSeen,
                    bookmark = changedBookmark,
                    fillermark = changedFillermark,
                    lastSecondSeen = changedPosition,
                    totalSeconds = changedTotalSeconds,
                ),
            )
        }
        // <-- AM (SAVE_ONLY_WHAT_CHANGED)
        // AM (RECENT_EPISODE_POSITIONS) -->
        // Mirror the DB trigger's version bump locally so our next save's staleness
        // check compares against what the DB will actually have, not last tick's.
        if (willChangeTrackedFields) {
            episode.version = fresh!!.version + 1
        }
        // <-- AM (RECENT_EPISODE_POSITIONS)
        // AM (SYNC) -->
        // AM (SYNC_ON_OPEN_RUNS_ONCE) -->
        // "Sync on episode open" means once, on open. This ran on every
        // progress save instead - a WorkManager enqueue every few seconds for
        // the whole episode, plus two preference reads per save - because the
        // only condition was a position past the first second, which stays
        // true for the rest of the episode. Guarded per episode, like the
        // history write below.
        if (episode.last_second_seen >= 1L && !syncRequested) {
            if (syncPreferences.isSyncEnabled() && syncPreferences.getSyncTriggerOptions().syncOnEpisodeOpen) {
                syncRequested = true
                SyncDataJob.startNow(context.workManager)
            }
        }
        // <-- AM (SYNC_ON_OPEN_RUNS_ONCE)
        // <-- AM (SYNC)
    }

    /** Saves last-seen history if not in incognito mode. */
    override suspend fun recordWatched() {
        if (isIncognito()) return
        // AM (PERSIST_ON_CADENCE) -->
        // History records WHEN an episode was last watched, which changes once
        // per viewing, not continuously - but this ran on every progress save,
        // so it was upserting the same row with a slightly newer timestamp
        // over and over. Once per episode per session says the same thing; the
        // teardown save refreshes it on the way out.
        if (historyWritten) return
        historyWritten = true
        // <-- AM (PERSIST_ON_CADENCE)
        upsertHistory.await(HistoryUpdate(episode.id!!, Date()))
    }
}
// <-- AM (CONTENT_AGNOSTIC_SESSION)
