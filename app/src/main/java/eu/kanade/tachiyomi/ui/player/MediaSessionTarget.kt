package eu.kanade.tachiyomi.ui.player

import android.media.session.MediaSession
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.service.GesturePreferences
import animiru.domain.player.model.CustomKeyCodes

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
 * branches that deliberately do nothing: a gesture configured as None, Seek on
 * play/pause, or Screenshot on a transport button are all no-ops, and they stay
 * no-ops here rather than becoming a default.
 */
fun mediaSessionCallbackFor(
    target: MediaSessionTarget,
    gesturePreferences: GesturePreferences,
): MediaSession.Callback {
    val previousAction = gesturePreferences.mediaPreviousGesture.get()
    val playAction = gesturePreferences.mediaPlayPauseGesture.get()
    val nextAction = gesturePreferences.mediaNextGesture.get()

    return object : MediaSession.Callback() {
        override fun onPlay() {
            when (playAction) {
                SingleActionGesture.None -> {}
                SingleActionGesture.Seek -> {}
                SingleActionGesture.PlayPause -> {
                    super.onPlay()
                    target.play()
                }
                SingleActionGesture.Custom -> {
                    target.keypress(CustomKeyCodes.MediaPlay.keyCode)
                }

                SingleActionGesture.Switch -> {}
                SingleActionGesture.Screenshot -> {}
            }
        }

        override fun onPause() {
            when (playAction) {
                SingleActionGesture.None -> {}
                SingleActionGesture.Seek -> {}
                SingleActionGesture.PlayPause -> {
                    super.onPause()
                    target.pause()
                }
                SingleActionGesture.Custom -> {
                    target.keypress(CustomKeyCodes.MediaPlay.keyCode)
                }

                SingleActionGesture.Switch -> {}
                SingleActionGesture.Screenshot -> {}
            }
        }

        override fun onSkipToPrevious() {
            when (previousAction) {
                SingleActionGesture.None -> {}
                SingleActionGesture.Seek -> {
                    target.seekStep(forward = false)
                }
                SingleActionGesture.PlayPause -> {
                    target.togglePause()
                }
                SingleActionGesture.Custom -> {
                    target.keypress(CustomKeyCodes.MediaPrevious.keyCode)
                }

                SingleActionGesture.Switch -> target.stepItem(next = false)
                SingleActionGesture.Screenshot -> {}
            }
        }

        override fun onSkipToNext() {
            when (nextAction) {
                SingleActionGesture.None -> {}
                SingleActionGesture.Seek -> {
                    target.seekStep(forward = true)
                }
                SingleActionGesture.PlayPause -> {
                    target.togglePause()
                }
                SingleActionGesture.Custom -> {
                    target.keypress(CustomKeyCodes.MediaNext.keyCode)
                }

                SingleActionGesture.Switch -> target.stepItem(next = true)
                SingleActionGesture.Screenshot -> {}
            }
        }

        override fun onStop() {
            super.onStop()
            target.stopSession()
        }
    }
}
// <-- AM (SESSION_OWNS_ITS_CONTROLS)
