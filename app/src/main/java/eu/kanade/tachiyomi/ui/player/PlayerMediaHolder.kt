package eu.kanade.tachiyomi.ui.player

// AM (SERVICE_OWNED_PLAYER) -->
import android.content.Context
import android.media.session.MediaSession
import android.view.Surface
import animiru.domain.player.service.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
    decoderPreferences: DecoderPreferences = Injekt.get(),
) {
    private val videoOutput = if (decoderPreferences.gpuNext.get()) "gpu-next" else "gpu"

    private var _player: MPVPlayer? = null
    val player: MPVPlayer
        get() = _player ?: error("PlayerMediaHolder.player accessed before any PlayerViewModel adopted into it")
    val mpv get() = player.mpv

    var mediaSession: MediaSession? = null
        private set

    private val _state = MutableStateFlow(PlayerMediaState())
    val state = _state.asStateFlow()

    private var hasAttachedSurfaceBefore = false

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

    /**
     * Binds an already-running player to a freshly created/recreated Surface. Mirrors the
     * per-Composable attach logic in `MpvSurface.kt`, but against a player that isn't tied to
     * the caller's lifecycle - safe to call from any [PlayerActivity] instance, new or old.
     */
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        // Force lighter "gpu" (not gpu-next) on reattach to cut reconfig cost/audio blip;
        // use the user's pref only on the very first attach.
        mpv.setPropertyString("vo", if (hasAttachedSurfaceBefore) "gpu" else videoOutput)
        hasAttachedSurfaceBefore = true
        mpv.setOptionString("vid", "auto")
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    /** Detaches the surface without stopping playback - the player keeps running headless. */
    fun detachSurface() {
        mpv.setOptionString("hwdec", "no")
        mpv.setPropertyString("vo", "null")
        mpv.setPropertyString("force-window", "no")
        mpv.detachSurface()
    }

    fun updateState(transform: (PlayerMediaState) -> PlayerMediaState) {
        _state.value = transform(_state.value)
    }

    /** Tears down the player and MediaSession. Only called when playback genuinely ends. */
    fun release() {
        mediaSession?.release()
        mediaSession = null
        _player?.isExiting = true
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
