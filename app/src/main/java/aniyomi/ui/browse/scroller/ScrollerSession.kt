package aniyomi.ui.browse.scroller

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

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
 */
object ScrollerSession {

    /**
     * Whether the user has unmuted the feed. Survives leaving the browse
     * screen, deliberately: having to unmute again after opening one entry is
     * the kind of thing that makes a feed tiring to use.
     */
    var isMuted: Boolean = true

    /** Learned media aspect ratios by entry id - see [ScrollerPlaybackState.aspectRatios]. */
    val aspectRatios = mutableMapOf<Long, Float>()

    /**
     * Playback positions by entry id.
     *
     * By entry, not by url: a url goes through MediaProxyServer and carries a
     * fresh random registration key every time it is registered, so a position
     * stored against one would never be found again.
     */
    val positions = mutableMapOf<Long, Double>()

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

    fun startObserving() {
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    clear()
                }
            },
        )
    }

    private fun clear() {
        isMuted = true
        aspectRatios.clear()
        positions.clear()
        firstFrames.clear()
    }

}
// <-- AM (BROWSE_SCROLLER)
