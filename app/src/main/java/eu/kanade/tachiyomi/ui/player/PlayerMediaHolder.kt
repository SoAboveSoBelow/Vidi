package eu.kanade.tachiyomi.ui.player

// AM (SERVICE_OWNED_PLAYER) -->
import android.content.Context
import android.media.session.MediaSession
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Owns the single, long-lived [MPVPlayer] instance and its [MediaSession].
 *
 * [PlayerMediaHolder] is the future home for everything that currently lives on
 * [PlayerViewModel] and dies with it - the mpv instance, the MediaSession, and
 * current playback/episode state. It's constructed once by
 * [PlayerBackgroundPlaybackService] and outlives any single [PlayerActivity]
 * instance, so reattaching a new or recreated Activity never has to fight over
 * player identity.
 *
 * Ownership model: this holder does NOT construct its own [MPVPlayer]. Doing so
 * would require [PlayerViewModel] to read the player from here instead of owning
 * it directly - but PlayerViewModel's own init sequence touches `player` for
 * several synchronous, construction-time setups (event flow collection, etc.),
 * before the Service bind (which is always async) could possibly have completed.
 * Making `player` a "read from the Service" property would make those crash on
 * every single player open.
 *
 * Instead, [PlayerViewModel] keeps constructing its own [MPVPlayer] exactly as it
 * always has (zero timing risk, unchanged behavior), then hands it off via
 * [adopt] once the Service bind completes. The first instance to adopt "wins" and
 * becomes the canonical player for this holder's lifetime; a later instance
 * calling [adopt] (e.g. Activity recreation while this holder is still alive)
 * gets the existing player back instead of having its own accepted, avoiding two
 * live mpv instances backing the same session.
 *
 * De-duplicating away the second instance's now-orphaned player (i.e. actually
 * discarding/releasing it, rather than just not adopting it) is the attach/detach
 * contract's job, not this class's - see the migration plan's step 3.
 */
class PlayerMediaHolder(
    context: Context,
) {
    private var _player: MPVPlayer? = null
    val player: MPVPlayer
        get() = _player ?: error("PlayerMediaHolder.player accessed before any PlayerViewModel adopted into it")
    val mpv get() = player.mpv

    // AM (AUDIO_FOCUS_ORPHAN_FIX) -->
    /**
     * Must be checked by callers BEFORE calling adopt(), not after - adopt()
     * itself may mutate _player as a side effect of the very call being checked
     * against. PlayerViewModel.bindToService() uses this to know whether its own
     * adopt() call is resolving a genuinely first-ever adoption (audio focus
     * needs requesting) versus returning an already-established canonical player
     * from a prior session (which already holds focus from back when it was
     * originally constructed, and must not request it again).
     */
    val hasAdoptedPlayer: Boolean get() = _player != null
    // <-- AM (AUDIO_FOCUS_ORPHAN_FIX)

    var mediaSession: MediaSession? = null
        private set

    private val _state = MutableStateFlow(PlayerMediaState())
    val state = _state.asStateFlow()

    // AM (LIVE_POSITION_TRACKING) -->
    // Scoped to this holder, not any PlayerViewModel - cancelled in release() and
    // replaced with a fresh one on the next real adopt(). PlayerViewModel's own
    // position tracking (the per-second DB write in onSecondReached()) is launched
    // in viewModelScope, which gets cancelled the instant that ViewModel is
    // cleared - routine while this architecture is specifically designed to let
    // playback itself continue afterward via this Service-owned canonical player.
    // Any playback that happens after the old ViewModel dies but before a new one
    // attaches was going completely unobserved: never reflected here, and - since
    // the DB write lived in that same dead flow - never persisted either, leaving
    // episode.last_second_seen stale by however long the gap lasted. This observer
    // has zero business-logic dependencies (no DB, no repositories, no sync/tracker
    // logic) - it only keeps state.positionMs/durationMs current in memory, cheaply,
    // for as long as the player is alive, completely independent of any ViewModel.
    // PlayerViewModel.setVideo() prefers this over the DB value when resuming a
    // reattached live session - see its forceResumeFromLastPosition handling.
    // A var, not a val: release() can run on this same holder instance while it's
    // still alive and about to be reused by a fast reopen (see STALE_HOLDER_STATE_FIX
    // below) - reusing an already-cancelled scope there would silently no-op every
    // launchIn() in the next adopt() instead of throwing, so a fresh scope each real
    // adoption is what makes that reuse actually work rather than fail invisibly.
    private var holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // <-- AM (LIVE_POSITION_TRACKING)

    /**
     * Registers [existing] as this holder's player if none has been adopted yet, otherwise
     * returns the already-adopted one unchanged. Always returns the player callers should
     * actually use - callers must compare the result against what they passed in to detect
     * whether their own instance was accepted or discarded.
     */
    fun adopt(existing: MPVPlayer): MPVPlayer {
        if (_player == null) {
            _player = existing
            // AM (LIVE_POSITION_TRACKING) -->
            // Started only on the very first, real adoption - never for a later
            // duplicate instance's call, which just returns the already-adopted
            // player unchanged and shouldn't start a second observer against it.
            // Fresh scope in case a prior release() on this same holder instance
            // already cancelled the old one (see that var's doc comment).
            holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            existing.mpv.propFlow<Int>("time-pos").filterNotNull()
                .onEach { seconds -> updateState { it.copy(positionMs = seconds * 1000) } }
                .launchIn(holderScope)
            existing.mpv.propFlow<Int>("duration").filterNotNull()
                .onEach { seconds -> updateState { it.copy(durationMs = seconds * 1000) } }
                .launchIn(holderScope)
            // <-- AM (LIVE_POSITION_TRACKING)
        }
        return _player!!
    }

    /**
     * Creates this holder's MediaSession on first call; on every later call (a new or
     * recreated PlayerActivity instance re-binding), redirects the existing session's
     * callback to the one just passed in instead of creating a second session. Without
     * this redirect, media-button presses after a reattach would keep routing to
     * whichever PlayerViewModel happened to build the session first - which, per the
     * same dedup problem the player itself had, could be an already-orphaned instance.
     */
    fun ensureMediaSession(context: Context, callback: MediaSession.Callback): MediaSession {
        mediaSession?.let {
            it.setCallback(callback)
            return it
        }
        return MediaSession(context, "PlayerMediaHolder").apply {
            setCallback(callback)
            isActive = true
        }.also { mediaSession = it }
    }

    fun updateState(transform: (PlayerMediaState) -> PlayerMediaState) {
        _state.value = transform(_state.value)
    }

    /** Tears down the player and MediaSession. Only called when playback genuinely ends. */
    fun release() {
        mediaSession?.release()
        mediaSession = null
        _player?.isExiting = true
        // AM (STALE_HOLDER_STATE_FIX) -->
        // Clearing the player reference and session state here (not just marking
        // isExiting) matters because this holder can outlive any single PlayerActivity
        // instance and be reused by a fast reopen (e.g. tapping the always-on
        // notification) before the Service's own onDestroy() gets around to running -
        // stopService() from PlayerActivity.onDestroy() is asynchronous, so there's a
        // real window where a reopen binds to this same still-alive holder before it's
        // torn down. Without clearing _state here too, needsInit() on that reopen would
        // see the stale animeId/episodeId still "matching" and skip reinitializing
        // entirely, leaving playback permanently stuck against an already-released
        // player.
        _player = null
        _state.value = PlayerMediaState()
        // <-- AM (STALE_HOLDER_STATE_FIX)
        // AM (LIVE_POSITION_TRACKING) -->
        holderScope.cancel()
        // <-- AM (LIVE_POSITION_TRACKING)
    }

    /** Minimal state a reattaching [PlayerActivity] needs to reconstruct its UI on create/resume. */
    data class PlayerMediaState(
        val animeId: Long? = null,
        val episodeId: Long? = null,
        val paused: Boolean = true,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val animeTitle: String = "",
        val episodeTitle: String = "",
    )
}
// <-- AM (SERVICE_OWNED_PLAYER)
