package eu.kanade.tachiyomi.ui.player

import android.media.session.MediaSession
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlAnchor
import animiru.domain.player.model.ControlInput
import animiru.domain.player.model.ControlSurface
import animiru.domain.player.service.ControlBindingPreferences

// AM (SESSION_OWNS_ITS_CONTROLS) -->
/**
 * What a MediaSession's transport controls act on.
 *
 * Extracted from PlayerViewModel.buildMediaSessionCallback(), which built the
 * callback as an anonymous object closing over the view model itself. That is
 * why the app has exactly one MediaSession callback and why only a
 * PlayerViewModel can install one - and in turn why the browse feed, which has
 * no view model and cannot construct one (PlayerActivity is the only path to a
 * session from scratch), could publish what it is playing to the lock screen
 * but never answer the buttons on it. A notification whose controls do nothing
 * is worse than no notification, so the feed has gone without.
 *
 * The operations below are exactly the ones the callback already performed,
 * named for what a session does rather than for what a player screen does -
 * [stepItem] rather than "next episode", because the feed's next item is the
 * next card. Nothing here is player-specific, so anything that owns playback
 * can implement it.
 */
interface MediaSessionTarget {

    /** Resume playback. */
    fun play()

    /** Hold playback where it is. */
    fun pause()

    /** Whichever of the two the current state calls for. */
    fun togglePause()

    /** Seek by the session's own step, forward or back. */
    fun seekStep(forward: Boolean)

    /** Move to the next or previous item this session is playing through. */
    fun stepItem(next: Boolean)

    /** Hand a key code to mpv, for a gesture configured as Custom. */
    fun keypress(code: String)

    /**
     * End the session outright - the notification's Stop, or an external
     * transport stop from Bluetooth or a system media widget.
     */
    fun stopSession()
}

/**
 * The app's MediaSession callback, over any [target].
 *
 * Moved out of PlayerViewModel with its behaviour unchanged, including the
 * branches that deliberately do nothing: an unbound control, or one bound to
 * something a transport button cannot express, stays a no-op rather than
 * becoming a default.
 *
 * AM (CONTROL_BINDINGS): the three transport buttons read the same anchors the
 * on-screen media buttons do, so a session button and its on-screen twin cannot
 * drift apart. Bindings are read per press rather than captured once, since the
 * session outlives any one visit to the editor.
 */
fun mediaSessionCallbackFor(
    target: MediaSessionTarget,
    controlBindingPreferences: ControlBindingPreferences,
): MediaSession.Callback {
    val layout = controlBindingPreferences.layout(ControlSurface.Player)

    fun actionFor(anchor: ControlAnchor): ControlAction = layout.get().action(anchor, ControlInput.Tap)

    /**
     * [playing] separates onPlay from onPause for the one action that cares.
     *
     * An opt-in list rather than an exhaustive one: a transport button is three
     * controls on a lock screen or a headset, and most actions - sheets, panels, screen
     * state, the row's own buttons - have nowhere to go on one. A new action should not
     * have to be considered here to compile, because the answer is almost always that
     * this surface ignores it.
     */
    fun run(action: ControlAction, playing: Boolean?) {
        when (action) {
            ControlAction.PlayPause -> when (playing) {
                true -> target.play()
                false -> target.pause()
                null -> target.togglePause()
            }
            ControlAction.SeekBackward -> target.seekStep(forward = false)
            ControlAction.SeekForward -> target.seekStep(forward = true)
            ControlAction.PreviousEpisode -> target.stepItem(next = false)
            ControlAction.NextEpisode -> target.stepItem(next = true)
            is ControlAction.CustomKey -> target.keypress(action.code)
            else -> Unit
        }
    }

    return object : MediaSession.Callback() {
        override fun onPlay() {
            val action = actionFor(ControlAnchor.MediaPlayPause)
            if (action == ControlAction.PlayPause) super.onPlay()
            run(action, playing = true)
        }

        override fun onPause() {
            val action = actionFor(ControlAnchor.MediaPlayPause)
            if (action == ControlAction.PlayPause) super.onPause()
            run(action, playing = false)
        }

        override fun onSkipToPrevious() {
            run(actionFor(ControlAnchor.MediaPrevious), playing = null)
        }

        override fun onSkipToNext() {
            run(actionFor(ControlAnchor.MediaNext), playing = null)
        }

        override fun onStop() {
            super.onStop()
            target.stopSession()
        }
    }
}
// <-- AM (SESSION_OWNS_ITS_CONTROLS)
