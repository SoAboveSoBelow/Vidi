package eu.kanade.tachiyomi.ui.player

// AM (SERVICE_OWNED_PLAYER) -->
import android.content.Context
import android.media.session.MediaSession
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * discarding/releasing it, rather than just not adopting it) is
 * PlayerViewModel.bindToService()'s job, not this class's.
 */
class PlayerMediaHolder(
    context: Context,
) {
    private var _player: MPVPlayer? = null
    val player: MPVPlayer
        get() = _player ?: error("PlayerMediaHolder.player accessed before any PlayerViewModel adopted into it")
    val mpv get() = player.mpv

    var mediaSession: MediaSession? = null
        private set

    private val _state = MutableStateFlow(PlayerMediaState())
    val state = _state.asStateFlow()

    /**
     * Registers [existing] as this holder's player if none has been adopted yet, otherwise
     * returns the already-adopted one unchanged. Always returns the player callers should
     * actually use - callers must compare the result against what they passed in to detect
     * whether their own instance was accepted or discarded.
     */
    fun adopt(existing: MPVPlayer): MPVPlayer {
        if (_player == null) {
            _player = existing
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
