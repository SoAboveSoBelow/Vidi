package aniyomi.ui.browse.scroller

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import aniyomi.player.clip.ClipPlayback
import eu.kanade.tachiyomi.ui.player.RecentPositionManager
import eu.kanade.tachiyomi.ui.player.SessionKey
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlin.math.abs

// AM (BROWSE_SCROLLER) -->
/**
 * One card's playback, which is [ClipPlayback], plus the parts that are only
 * true of a card.
 *
 * Why not the canonical player: it is one real playback session with one
 * persistent SurfaceTexture, deliberately never recreated (see
 * MPVPlayer.persistentSurfaceTexture), so pointing it at feed cards would
 * destroy whatever the user had playing in the background - the one thing the
 * service-owned architecture exists to prevent. PlayerActivity is also still
 * the only path that can construct a session from scratch (see
 * PlayerMediaHolder's own notes), so a feed could not start one anyway.
 *
 * AM (CLIP_PLAYBACK): everything about HOW an entry is played - the separate
 * audio instance, the readiness gate, the loop, the buffer, mute and audio
 * focus - moved to [ClipPlayback], which the real player now plays the same
 * entries with. What is left here is what a card has and a player session does
 * not: a TextureView to read a placeholder frame out of.
 */
class ScrollerPlayer(
    videoPlayer: MPVPlayer,
    audioPlayer: MPVPlayer,
    // AM (SESSION_KEYED_TEMP_POSITIONS) -->
    /**
     * Where a card's position is written: the app's one temp-position store,
     * the same one the real player resumes from. Written to, never read -
     * see [loadPaused].
     *
     * It used to be a map on [ScrollerSession], which is why a card's progress
     * went no further than the feed - nothing outside this package could see
     * it, so opening the entry in the player started from zero however long the
     * card had been watched.
     */
    private val positions: RecentPositionManager,
    // <-- AM (SESSION_KEYED_TEMP_POSITIONS)
) {

    private val clip = ClipPlayback(
        videoPlayer,
        audioFactory = { audioPlayer },
        // The feed decides which card makes sound and holds focus for it.
        managesAudio = true,
    )

    /** The instance this card draws, for MpvSurface. */
    val player: MPVPlayer get() = clip.video

    val isMuted: Boolean get() = clip.isMuted
    val isLoaded: Boolean get() = clip.isLoaded
    val isPaused: Boolean get() = clip.isPaused
    val positionSeconds: Float get() = clip.positionSeconds
    val durationSeconds: Float get() = clip.durationSeconds
    val bufferedPositionSeconds: Float get() = clip.bufferedPositionSeconds

    private var loadedKey: Long? = null

    init {
        ScrollerSession.startObserving()
        // MPVPlayer's own init sets aid=no and lets PlayerViewModel pick an
        // audio track reactively. Nothing does that for this instance, so
        // without this a card plays silently however it is unmuted.
        player.mpv.setOptionString("aid", "auto")
        // Starts silent whatever the feed setting is: nothing is the active
        // card at construction, and only the active card makes sound.
        player.mpv.setPropertyBoolean("mute", true)
        // A feed item is a short clip meant to repeat. Set on the instance
        // rather than per load so a newly loaded card cannot briefly play
        // unlooped before the option lands.
        clip.prepare(looping = true)
        // Preloading is not free: a feed holds several instances open and
        // loads them before the user reaches them, so a card being approached
        // is capped well below what the card being watched is given.
        clip.applyPreloadBuffer()
        clip.setMuted(ScrollerSession.isMuted, mayHoldAudioFocus = false)
    }

    fun onSurfaceAttachedChanged(attached: Boolean) = clip.onSurfaceAttachedChanged(attached)

    // AM (SESSION_KEYED_TEMP_POSITIONS) -->
    /**
     * What this card's position is stored under: the episode it plays.
     *
     * Null until the entry's episodes are known - see
     * [ScrollerPlaybackState.resolveSessionKey], which looks locally at load
     * and fetches only once a card has actually been watched. A card with no
     * key holds no position, which is the honest state rather than a gap: the
     * feed is playing something it cannot yet name, and inventing a feed-local
     * identity for it is what kept this progress out of the player's reach.
     */
    var sessionKey: SessionKey? = null
        set(value) {
            // Arriving late: the card has been playing against no key, so what
            // it has reached would otherwise not be written until the next
            // pause or eviction.
            field = value
            if (value != null) rememberPosition()
        }
    // <-- AM (SESSION_KEYED_TEMP_POSITIONS)

    /**
     * Loads [url] and holds it on its first frame, from the beginning.
     *
     * Deliberately does not resume, though this card writes a position the
     * player does resume from. A card is something you come across, and
     * meeting it part way through is not what the feed is for; its own way
     * back to where you were is the pool holding the instance loaded (see
     * BROWSE_SCROLLER_RETENTION), which lasts exactly as long as a card is
     * worth keeping.
     */
    fun loadPaused(key: Long, url: String, audioUrl: String?, httpHeaderFields: String?) {
        if (loadedKey == key) return
        rememberPosition()
        loadedKey = key
        clip.loadPaused(
            key = key,
            url = url,
            audioUrl = audioUrl,
            httpHeaderFields = httpHeaderFields,
        )
    }

    fun play() = clip.play()

    fun pause() {
        rememberPosition()
        clip.pause()
    }

    fun togglePaused() = clip.togglePaused()

    fun seekBy(seconds: Int) = clip.seekBy(seconds)

    fun seekTo(seconds: Float) = clip.seekTo(seconds)

    fun setMuted(muted: Boolean, mayHoldAudioFocus: Boolean) = clip.setMuted(muted, mayHoldAudioFocus)

    fun hasRenderedFrame(): Boolean = clip.hasRenderedFrame()

    fun videoAspectRatio(): Float? = clip.videoAspectRatio()

    /**
     * The view this instance renders into, handed over by MpvSurface.
     *
     * The frame is read from here rather than from mpv: mpv's own
     * screenshot-raw reads the decoded frame, which a hardware decoder need
     * not expose at all, so with hwdec on it returns nothing. The TextureView
     * always holds the composited result.
     */
    private var textureView: TextureView? = null

    fun onViewAvailable(view: TextureView) {
        textureView = view
    }

    /**
     * The frame on screen, for use as this card's placeholder later.
     *
     * Two things this does not do, both of which were bugs.
     *
     * It does not read the view at full size. `TextureView.bitmap` allocates a
     * bitmap the size of the view, which for a tall video on a phone is around
     * eight megabytes - and with a cache of them that is an out-of-memory
     * crash, reached soonest by exactly the tall videos that make the views
     * biggest. A small bitmap is requested instead.
     *
     * And it does not keep the letterboxing. The view fills the card's box
     * while mpv renders the video fitted inside it, so a raw capture is the
     * video surrounded by bars. Drawn fitted into the box again, that reads as
     * two rectangles layered on each other - which is what it looked like.
     * [videoAspectRatio] says where the video actually is in that frame, so
     * the bars are cropped off and what is kept is just the video.
     */
    fun captureFrame(): ImageBitmap? {
        val view = textureView?.takeIf { it.isAvailable && it.width > 0 && it.height > 0 } ?: return null

        val viewRatio = view.width.toFloat() / view.height.toFloat()
        val scale = (CAPTURE_MAX_EDGE.toFloat() / maxOf(view.width, view.height)).coerceAtMost(1f)
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)

        val raw = runCatching { view.getBitmap(width, height) }.getOrNull() ?: return null

        val videoRatio = videoAspectRatio()
        val cropped = if (videoRatio == null || !videoRatio.isFinite() || videoRatio <= 0f) {
            raw
        } else {
            runCatching { cropToRatio(raw, viewRatio, videoRatio) }.getOrDefault(raw)
        }
        return cropped.asImageBitmap()
    }

    /** Takes the centre region of [source] whose shape is [videoRatio]. */
    private fun cropToRatio(source: Bitmap, viewRatio: Float, videoRatio: Float): Bitmap {
        // Within a tolerance there are no bars to remove, and cropping would
        // only lose a row of pixels.
        if (abs(viewRatio - videoRatio) < CROP_TOLERANCE) return source

        return if (videoRatio > viewRatio) {
            // Wider than the box: bars above and below.
            val height = (source.width / videoRatio).toInt().coerceIn(1, source.height)
            val top = ((source.height - height) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, 0, top, source.width, height)
        } else {
            // Taller than the box: bars left and right.
            val width = (source.height * videoRatio).toInt().coerceIn(1, source.width)
            val left = ((source.width - width) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, left, 0, width, source.height)
        }
    }

    /** Called while playing, so progress survives a kill mid-clip. */
    fun notePosition() = rememberPosition()

    // AM (SESSION_KEYED_TEMP_POSITIONS) -->
    /**
     * Writes this card's position to the app's temp-position store, under the
     * episode it plays.
     *
     * Nothing is written for a card with no [sessionKey]: that card has not
     * been watched long enough for its episodes to have been resolved, and the
     * store is pruned MRU to a slot count sized for episodes a person actually
     * watched. A feed would otherwise offer it a candidate for every card
     * dwelt on and evict real resume positions at the rate the user scrolls.
     *
     * Duration is handed over so the store can clear the slot on an entry that
     * ran to its end, exactly as it does for an episode.
     */
    private fun rememberPosition() {
        val key = sessionKey ?: return
        positions.remember(
            key,
            positionMs = (clip.positionSeconds * 1000f).toLong(),
            // Deliberately no duration. The store clears a slot whose position
            // has reached the end of the entry, which is right for an episode
            // that finished and wrong for a card: every card loops, so it sits
            // within a second of its own end for much of every revolution and
            // the slot was being wiped almost as fast as it was written.
            durationMs = 0L,
        )
    }
    // <-- AM (SESSION_KEYED_TEMP_POSITIONS)

    /**
     * Stops this instance and forgets what it was playing, keeping it alive to
     * be loaded with something else.
     *
     * This, not [release], is what happens when a card gives its instance
     * back. Destroying one per card is what met the teardown race described on
     * ScrollerPlayerPool.
     */
    fun reset() {
        rememberPosition()
        loadedKey = null
        // AM (SESSION_KEYED_TEMP_POSITIONS): with the file. This instance is
        // about to be loaded with a different entry, and a key left behind
        // would write that entry's position against the previous one's episode.
        sessionKey = null
        clip.reset()
    }

    /** Destroys the instance. Only the feed closing does this. */
    fun release() {
        rememberPosition()
        loadedKey = null
        sessionKey = null
        textureView = null
        clip.release()
    }
}

/**
 * Long edge of a captured placeholder. It stands in for a video at card size,
 * so it does not need the view's own resolution - and holding several at full
 * size is what runs the app out of memory.
 */
private const val CAPTURE_MAX_EDGE = 480

/** Ratios within this of each other are treated as the same shape. */
private const val CROP_TOLERANCE = 0.01f
// <-- AM (BROWSE_SCROLLER)
