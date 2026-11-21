package aniyomi.ui.browse.scroller

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.app.di.appGraph

// AM (BROWSE_SCROLLER) -->
/**
 * The scroller's state that outlives any one browse screen but not the app.
 *
 * Everything here used to live on [ScrollerPlaybackState], which is remembered
 * per source and so was thrown away and rebuilt whenever the user opened an
 * entry and came back. That cost three things at once: the sound went back to
 * muted, every learned aspect ratio was forgotten so the cards resized and
 * shifted the list under the user, and playback positions were lost.
 *
 * Cleared when the process goes to the background, which is the same signal
 * SecureActivityDelegate's own lock already keys off - observed here directly
 * through ProcessLifecycleOwner rather than through the lock, so this behaves
 * the same whether or not the user has app locking turned on.
 *
 * AM (A_LEAVING_THE_FEED_IS_NOT_LOSING_IT): cleared AFTER a grace period
 * rather than on the stop itself - see [scheduleClear].
 */
object ScrollerSession {

    /**
     * Whether the user has unmuted the feed. Survives leaving the browse
     * screen, deliberately: having to unmute again after opening one entry is
     * the kind of thing that makes a feed tiring to use.
     */
    var isMuted: Boolean = true

    /** App-owned playback: navigation does not stop a feed's audio. */
    private val playbackStates = mutableMapOf<Long, ScrollerPlaybackState>()

    internal fun playback(sourceId: Long, create: () -> ScrollerPlaybackState): ScrollerPlaybackState =
        playbackStates.getOrPut(sourceId, create)

    // AM (BROWSE_SCROLLER_SESSION_INVALIDATION) -->
    /**
     * Bumped every time [clear] throws the playback away.
     *
     * [clear] runs on process background, which does NOT take the browse
     * screen's composition with it: the activity is stopped, not destroyed, so
     * a feed left on screen comes back holding the ScrollerPlaybackState it
     * remembered before - whose scope is cancelled, whose jobs are gone and
     * whose mpv instances have been destroyed. Every card then draws a surface
     * for a released player and nothing ever loads again, which is what
     * leaving and re-entering the app on the feed looked like.
     *
     * Compose-observable and read by [rememberScrollerPlayback], so the
     * invalidation reaches the compositions holding the thing being
     * invalidated instead of leaving them pointed at it. That is the fix:
     * nothing here tries to detect a dead state after the fact.
     */
    var revision by mutableIntStateOf(0)
        private set
    // <-- AM (BROWSE_SCROLLER_SESSION_INVALIDATION)

    /** Learned media aspect ratios by entry id - see [ScrollerPlaybackState.aspectRatios]. */
    val aspectRatios = mutableMapOf<Long, Float>()

    /**
     * The first frame each card rendered, so a card outside the loaded window
     * shows where its video begins rather than its cover.
     *
     * Bounded, oldest dropped: these are decoded bitmaps and a long feed would
     * otherwise hold one for every card ever seen.
     */
    private val firstFrames = LinkedHashMap<Long, ImageBitmap>()

    fun firstFrame(animeId: Long): ImageBitmap? = firstFrames[animeId]

    fun putFirstFrame(animeId: Long, frame: ImageBitmap) {
        firstFrames.remove(animeId)
        firstFrames[animeId] = frame
        while (firstFrames.size > MAX_FIRST_FRAMES) {
            val oldest = firstFrames.keys.firstOrNull() ?: break
            firstFrames.remove(oldest)
        }
    }

    /**
     * Deliberately small. Each of these is a decoded bitmap, and the cards
     * that benefit are the ones near where the user is - a long tail of them
     * is memory held for cards that will not be looked at again soon.
     */
    private const val MAX_FIRST_FRAMES = 6

    private var observing = false

    // AM (A_LEAVING_THE_FEED_IS_NOT_LOSING_IT) -->
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** The grace period in flight, if the app is currently in the background. */
    private var pendingClear: Job? = null

    /**
     * The application context, for reading the app-lock setting the grace
     * period ends at. Held as the application's, so this object outliving any
     * activity leaks nothing.
     */
    private var appContext: Context? = null

    fun startObserving(context: Context) {
        appContext = context.applicationContext
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                /** Back before the grace period ran out: nothing was lost. */
                override fun onStart(owner: LifecycleOwner) {
                    pendingClear?.cancel()
                    pendingClear = null
                }

                override fun onStop(owner: LifecycleOwner) {
                    scheduleClear()
                }
            },
        )
    }

    /**
     * Throws the feed away [BACKGROUND_GRACE_MS] from now, or when the app
     * lock falls due, whichever comes first.
     *
     * Leaving the app was treated as the end of the feed, and for the trip it
     * is usually made for - answering a message, following a link out and
     * coming straight back - that is the same loss the in-feed retention
     * exists to prevent, except total: every instance released, every captured
     * frame dropped, and the whole first screenful resolved and decoded again
     * on return.
     *
     * The lock is the other bound because what is being held is decoded video
     * of what the user was watching. Once the app is locked that is behind an
     * authentication prompt, so holding it past the lock would keep the one
     * thing the lock exists to put away. Read from the setting rather than
     * timed against SecurityPreferences.lastAppClosed: the stop that writes
     * that value is this same stop, so the delay from here is the whole of it,
     * and a lock that never arms (authenticator off, "never", or background
     * playback keeping the app alive - see SecureActivityDelegate) imposes no
     * bound at all.
     */
    private fun scheduleClear() {
        pendingClear?.cancel()
        val grace = graceMillis()
        if (grace <= 0L) {
            pendingClear = null
            clear()
            return
        }
        pendingClear = scope.launch {
            delay(grace)
            pendingClear = null
            clear()
        }
    }

    private fun graceMillis(): Long {
        val preferences = appContext?.appGraph?.securityPreferences ?: return BACKGROUND_GRACE_MS
        if (!preferences.useAuthenticator.get()) return BACKGROUND_GRACE_MS
        // The lock timer does not arm while PIP or notification playback keeps
        // the player alive, so neither does this bound.
        if (SecureActivityDelegate.isBackgroundPlaybackActive) return BACKGROUND_GRACE_MS
        return when (val lockAfterMinutes = preferences.lockAppAfter.get()) {
            -1 -> BACKGROUND_GRACE_MS // Never
            0 -> 0L // Always: locked the moment it is in the background
            else -> minOf(BACKGROUND_GRACE_MS, lockAfterMinutes * 60_000L)
        }
    }

    /**
     * How long a backgrounded feed is kept.
     *
     * The same figure as ScrollerPlayback's RETAIN_MS, and the same reasoning:
     * long enough to cover a trip out of the app and back, short enough that
     * an app left in the background overnight is not holding decoders. The two
     * are kept equal so that leaving the feed has one answer rather than one
     * per route out of it.
     */
    private const val BACKGROUND_GRACE_MS = 300_000L
    // <-- AM (A_LEAVING_THE_FEED_IS_NOT_LOSING_IT)

    private fun clear() {
        playbackStates.values.forEach { it.releaseAll() }
        playbackStates.clear()
        // Mute is a user preference, not decoder state. Rebuild the players
        // with the same choice instead of forcing a mute/unmute cycle whose
        // fresh instances may not have selected an audio track yet.
        // AM (BROWSE_SCROLLER_SESSION_INVALIDATION) -->
        // Decoders and bitmaps go, measurements stay. What makes holding a
        // backgrounded feed expensive is the mpv instances and the captured
        // frames; a map of floats is not, and dropping it meant every card
        // was its cover's shape again on return - so the feed redrew the
        // initial-geometry gate and then resized under the user, for nothing.
        // <-- AM (BROWSE_SCROLLER_SESSION_INVALIDATION)
        // AM (SESSION_KEYED_TEMP_POSITIONS): positions are no longer among the
        // things this has to decide about. They live in the app's temp-position
        // store (RecentPositionManager), which is DB-backed and outlives the
        // process entirely, rather than in a map that could only ever be as
        // durable as this object.
        firstFrames.clear()
        revision++
    }

}
// <-- AM (BROWSE_SCROLLER)
