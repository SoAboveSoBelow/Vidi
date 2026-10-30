package aniyomi.ui.browse.scroller

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import eu.kanade.tachiyomi.ui.player.mpv.loadFileWithHwdecGuard
import kotlin.math.abs

// AM (BROWSE_SCROLLER) -->
/**
 * One card's own mpv instance, separate from the service-owned player in
 * PlayerMediaHolder.
 *
 * Why not the canonical player: it is one real playback session with one
 * persistent SurfaceTexture, deliberately never recreated (see
 * MPVPlayer.persistentSurfaceTexture), so pointing it at feed cards would
 * destroy whatever the user had playing in the background - the one thing the
 * service-owned architecture exists to prevent. PlayerActivity is also still
 * the only path that can construct a session from scratch (see
 * PlayerMediaHolder's own notes), so a feed could not start one anyway.
 *
 * Loads paused. A card's video is decoded and held on its first frame while
 * the card is still being approached, so reaching it is an unpause rather than
 * a load, and the card never shows anything that is not its own video.
 *
 * Starts muted and requests audio focus only when actually unmuted, so a feed
 * being scrolled never interrupts anything else playing.
 */
class ScrollerPlayer(
    val player: MPVPlayer,
) {

    private var loadedKey: Long? = null
    private var hasRequestedFocus = false

    /** True once a surface is attached, which is when loading can use hwdec. */
    private var surfaceReady = false
    private var pendingLoad: (() -> Unit)? = null

    /**
     * Carried over from whatever the feed last had rather than starting muted
     * every time a browse screen is built - see [ScrollerSession].
     */
    var isMuted: Boolean = ScrollerSession.isMuted
        private set

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
        player.mpv.setOptionString("loop-file", "inf")

        // Readahead capped, because preloading is not free.
        //
        // A feed holds several instances open at once and loads them before
        // the user reaches them, and a clip here is routinely tens of
        // megabytes - so on mpv's own defaults, three or four preloaded cards
        // can pull a lot of data for cards that may never be watched. This is
        // enough to decode the first frames and start instantly on arrival;
        // the card actually being watched gets a normal buffer in play().
        player.mpv.setOptionString("demuxer-max-bytes", PRELOAD_DEMUXER_BYTES)
        player.mpv.setOptionString("demuxer-readahead-secs", PRELOAD_READAHEAD_SECS)
    }

    /**
     * Called by the surface as it attaches and detaches.
     *
     * A load issued before a surface exists is forced to software decoding by
     * [loadFileWithHwdecGuard], so a load that arrives first is held here and
     * issued once there is something to decode into.
     */
    fun onSurfaceAttachedChanged(attached: Boolean) {
        surfaceReady = attached
        if (attached) {
            pendingLoad?.let { load ->
                pendingLoad = null
                load()
            }
        }
    }

    /**
     * Loads [url] and holds it on its first frame.
     *
     * Resumes from where this card left off if it has been played before this
     * session. Compared by [key] rather than by url: a url goes through
     * MediaProxyServer and carries a fresh random registration every time it
     * is built, so comparing urls would see every pass as a new file and
     * reload the card the user is already watching.
     */
    fun loadPaused(key: Long, url: String, audioUrl: String?, httpHeaderFields: String?) {
        if (loadedKey == key) return
        rememberPosition()
        loadedKey = key

        val issue = {
            // Set before the load, not after: mpv reads these when it opens
            // the stream, and a source needing a Referer fails the handshake
            // without them. Cleared rather than left behind when a card
            // carries none, so one card's headers never go to another's host.
            player.mpv.setOptionString("http-header-fields", httpHeaderFields ?: "")
            val resumeAt = ScrollerSession.positions[key]
            // pause=yes in the load's own options, so the file is never
            // playing even for a frame before being paused.
            val options = buildList {
                add("pause=yes")
                if (resumeAt != null) add("start=$resumeAt")
                // A source that serves audio separately - Coub serves a looped
                // mp3 alongside a muted mp4 - gets it added as an external
                // track rather than needing the two muxed into one file.
                if (audioUrl != null) add("audio-file=%${audioUrl.length}%$audioUrl")
            }.joinToString(",")
            player.mpv.loadFileWithHwdecGuard(
                url = url,
                options = options,
                hasAttachedSurface = player.hasAttachedSurfaceBefore,
            )
        }

        if (surfaceReady || player.hasAttachedSurfaceBefore) issue() else pendingLoad = issue
    }

    /** True once this instance has a file loaded, whether playing or held. */
    val isLoaded: Boolean get() = loadedKey != null

    fun play() {
        // The cap above is about not spending bandwidth on cards nobody has
        // reached. This one has been reached.
        player.mpv.setOptionString("demuxer-max-bytes", ACTIVE_DEMUXER_BYTES)
        player.mpv.setOptionString("demuxer-readahead-secs", ACTIVE_READAHEAD_SECS)
        player.mpv.setPropertyBoolean("pause", false)
    }

    fun pause() {
        rememberPosition()
        player.mpv.setPropertyBoolean("pause", true)
    }

    fun togglePaused() {
        if (isPaused) play() else pause()
    }

    val isPaused: Boolean
        get() = player.mpv.getPropertyBoolean("pause") ?: false

    /** Relative seek, clamped by mpv itself at both ends of the file. */
    fun seekBy(seconds: Int) {
        player.mpv.command("seek", seconds.toString(), "relative")
    }

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

    /**
     * True once mpv has a decoded frame to show.
     *
     * Read as a property rather than waited for as an event, as a backstop:
     * the event flow has no replay buffer and a missed event leaves a card
     * showing a placeholder over its own playing video.
     */
    fun hasRenderedFrame(): Boolean =
        (player.mpv.getPropertyInt("dwidth") ?: 0) > 0 &&
            (player.mpv.getPropertyInt("dheight") ?: 0) > 0

    /** The video's own aspect ratio, readable as soon as the file is open. */
    fun videoAspectRatio(): Float? =
        player.mpv.getPropertyDouble("video-params/aspect")
            ?.toFloat()
            ?.takeIf { it.isFinite() && it > 0f }

    /**
     * Mutes or unmutes this instance.
     *
     * [mayHoldAudioFocus] must be true for the card being looked at and false
     * for every other one. Audio focus is exclusive, and a focus request by
     * one instance hands the holder an AUDIOFOCUS_LOSS, which MPVPlayer
     * answers by pausing and only un-pausing on a later GAIN that never
     * arrives. With several feed instances alive, letting each request focus
     * meant whichever card was playing got paused by the next card's own
     * request and stayed paused - which is a card that plays for a few seconds
     * and then stops for good.
     */
    fun setMuted(muted: Boolean, mayHoldAudioFocus: Boolean) {
        isMuted = muted
        // Only the card being looked at makes sound, whatever the feed's own
        // mute setting says.
        val effectivelyMuted = muted || !mayHoldAudioFocus
        player.mpv.setPropertyBoolean("mute", effectivelyMuted)

        // Requested lazily and then kept: an unmute/mute/unmute cycle must not
        // re-request, which would make the app's own canonical player see a
        // focus loss (see MPVPlayer.requestAudioFocus's notes).
        if (!effectivelyMuted && !hasRequestedFocus) {
            player.requestAudioFocus()
            hasRequestedFocus = true
        }
    }

    /** Called while playing, so progress survives a kill mid-clip. */
    fun notePosition() = rememberPosition()

    private fun rememberPosition() {
        val key = loadedKey ?: return
        val position = player.mpv.getPropertyDouble("time-pos") ?: return
        if (position > 0.0) ScrollerSession.positions[key] = position
    }

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
        pendingLoad = null
        loadedKey = null
        runCatching { player.mpv.command("stop") }
    }

    /** Destroys the instance. Only the feed closing does this. */
    fun release() {
        rememberPosition()
        textureView = null
        pendingLoad = null
        loadedKey = null
        player.release()
    }
}

/**
 * What a preloaded card may buffer: enough for the first frames and an instant
 * start, not enough to fetch a clip nobody has watched.
 */
/**
 * Long edge of a captured placeholder. It stands in for a video at card size,
 * so it does not need the view's own resolution - and holding several at full
 * size is what runs the app out of memory.
 */
private const val CAPTURE_MAX_EDGE = 480

/** Ratios within this of each other are treated as the same shape. */
private const val CROP_TOLERANCE = 0.01f

private const val PRELOAD_DEMUXER_BYTES = "8MiB"
private const val PRELOAD_READAHEAD_SECS = "2"

/** What the card being watched may buffer. */
private const val ACTIVE_DEMUXER_BYTES = "64MiB"
private const val ACTIVE_READAHEAD_SECS = "20"
// <-- AM (BROWSE_SCROLLER)
