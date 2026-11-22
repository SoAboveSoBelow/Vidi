package aniyomi.ui.browse.scroller

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.components.FeedVideoSurfaceBox
import eu.kanade.tachiyomi.ui.player.components.MpvSurface

// AM (ENTRY_SCROLLER) -->
/**
 * One card's clip: its own mpv surface, what stands in for it before there is
 * a frame, and the controls a tap raises.
 *
 * Lifted out of BrowseSourceScroller's own card unchanged, because the entry
 * screen's episode feed draws the same thing and every rule written into it -
 * the placeholder over the surface rather than behind it, black rather than a
 * cover for a card inside the load window, the tap zones, the aspect clamp -
 * is a rule about clips, not about listings. A second copy beside it is how
 * the two would drift.
 *
 * [cardId] is the FEED's key for this card: the entry's id in a listing feed,
 * the episode's in an entry feed. See ScrollerCardRef.
 */
@Composable
fun ScrollerClipBox(
    cardId: Long,
    player: ScrollerPlayer?,
    videoOutput: String?,
    playback: ScrollerPlaybackState?,
    isFullscreen: Boolean,
    hasFrame: Boolean,
    aspectRatio: Float?,
    /** The cover model drawn for a card too far away to hold an instance. */
    cover: Any?,
    contentDescription: String?,
    isSelected: Boolean,
    onCoverAspectRatio: (Float) -> Unit,
    /** True when the tap landed where the play/pause button sits. */
    onClipTap: (Boolean) -> Unit,
    onClipLongClick: () -> Unit,
    onClipDoubleTap: ((ScrollerPlaybackState.TapZone) -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScrollerCardPadding)
            // The width is the screen's, so the ratio is what decides how
            // tall this card's video is. Read off this card's own instance
            // as soon as its file is open - which happens while the card
            // is still being approached - from the cover before that, and
            // from a landscape default until the cover has loaded.
            // Clamped: a card taller than a few screens is unusable
            // however the media is shaped, and a zero or non-finite ratio
            // is an exception out of aspectRatio() rather than a layout.
            .aspectRatio(scrollerDrawAspect(aspectRatio))
            .clip(RoundedCornerShape(ScrollerClipCorner))
            .background(ScrollerDeadSpaceColor)
            // Not combinedClickable: a tap here has to know WHERE it
            // landed, which that gives no way to see. A double tap is
            // dispatched by thirds, the same zones the real player's own
            // double-tap gestures use; a single tap only asks whether it
            // landed on the play/pause button's own spot.
            .pointerInput(onClipDoubleTap, onClipTap, onClipLongClick) {
                detectTapGestures(
                    onTap = { offset ->
                        onClipTap(isOnPlayPauseSpot(offset, size.width, size.height))
                    },
                    onLongPress = { onClipLongClick() },
                    onDoubleTap = { offset ->
                        val handler = onClipDoubleTap ?: return@detectTapGestures
                        handler(tapZoneAt(offset.x, size.width))
                    },
                )
            },
    ) {
        // AM (BROWSE_SCROLLER_CONTROLS): not while this card is expanded -
        // the fullscreen host is drawing this instance.
        if (player != null && videoOutput != null && !isFullscreen) {
            // AM (ENTRY_SCROLLER): pinned rather than filling the card -
            // see FeedVideoSurfaceBox. The card's box is already the clip's
            // shape, so this looks the same here; what it buys is that
            // expanding the card does not resize the buffer.
            FeedVideoSurfaceBox(aspect = scrollerDrawAspect(aspectRatio)) {
                MpvSurface(
                    player = player.player,
                    videoOutput = videoOutput,
                    // Tells this card's instance when it can load with
                    // hardware decoding - see ScrollerPlayer.loadPaused.
                    onSurfaceAttachedChanged = player::onSurfaceAttachedChanged,
                    // Lets the instance read its own displayed frame, for use
                    // as this card's placeholder later.
                    onViewAvailable = player::onViewAvailable,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The placeholder is drawn OVER the surface, and only while this
        // card has no frame of its own.
        //
        // Under the surface was wrong in a way that looks like a stale
        // thumbnail: the surface fills the box but mpv letterboxes the
        // video inside it, so whatever sat behind showed through the bars
        // and stayed there for as long as the card was playing. Over the
        // surface, and simply not emitted once there is a frame, there is
        // nothing left to show through.
        // AM (BROWSE_SCROLLER_NO_FLASH) -->
        // Only for a card too far away to be loaded at all. A card in the
        // load window shows black until its own video is there.
        //
        // It used to show its cover, then its captured first frame, then
        // the video - which on a fast scroll is three different images in
        // quick succession per card, the grey placeholder behind a cover
        // still loading among them. Black and then the video is one
        // change instead of four.
        if (player == null) {
        // <-- AM (BROWSE_SCROLLER_NO_FLASH)
            AsyncImage(
                model = cover,
                placeholder = ColorPainter(ScrollerPlaceholderColor),
                error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
                contentDescription = contentDescription,
                // Fit, not Crop: the dead space inside the box is the honest
                // result for content that is not this shape, and cropping a
                // portrait cover into a landscape box throws most of it away.
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val image = state.result.image
                    if (image.height > 0) {
                        onCoverAspectRatio(image.width.toFloat() / image.height.toFloat())
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isSelected) SelectedItemAlpha else 1f),
            )
        }

        // AM (BROWSE_SCROLLER_FRAME_WATCH) -->
        // A card with an instance but no frame of its own yet is covered,
        // and this is what BROWSE_SCROLLER_NO_FLASH above assumed was
        // already happening: "a card in the load window shows black until
        // its own video is there". Nothing made it black. The surface was
        // composed the moment the card had a player, and a surface with
        // nothing of this card's decoded into it is not empty - the pool
        // reassigns instances between cards and MPVPlayer keeps ONE
        // SurfaceTexture for its whole life (PERSISTENT_SURFACE_ARCHITECTURE,
        // handed on to each new TextureView by MpvSurface), so what it still
        // holds is the last frame of whatever card had the instance before
        // this one. That is the frozen frame: not a stall in this card's
        // playback, another card's picture sitting in the texture it
        // inherited.
        //
        // The state behind this has existed since FRAME_WATCH - framesReady,
        // fed by PlaybackRestart with a poll behind it - and nothing outside
        // ScrollerPlaybackState ever read it. This is the reader.
        //
        // Black rather than the cover or the captured first frame, for the
        // reason NO_FLASH gives: black and then the video is one change
        // instead of several.
        if (player != null && !hasFrame) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScrollerDeadSpaceColor),
            )
        }
        // <-- AM (BROWSE_SCROLLER_FRAME_WATCH)
        // AM (BROWSE_SCROLLER_CONTROLS): raised by a tap on the clip, and
        // only on a card that has an instance to control.
        if (playback != null && player != null && !isFullscreen) {
            ScrollerCardControls(
                playback = playback,
                animeId = cardId,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// AM (ENTRY_SCROLLER) -->
/**
 * The shape a clip is drawn at, clamped, with the fallback for one that has
 * not been measured yet.
 *
 * One definition, because the card's box, the card's surface and the expanded
 * view's surface all have to agree: a surface pinned to one aspect and drawn
 * in a box built from another is the stretch the pinning exists to avoid.
 */
fun scrollerDrawAspect(aspectRatio: Float?): Float = aspectRatio
    ?.takeIf { it.isFinite() && it > 0f }
    ?.coerceIn(ScrollerMinAspectRatio, ScrollerMaxAspectRatio)
    ?: ScrollerFallbackAspectRatio
// <-- AM (ENTRY_SCROLLER)

/**
 * Whether [offset] landed on the play/pause button's own spot - the middle of
 * the clip, within [ScrollerPlayPauseSpotRadius] of it.
 *
 * AM (BROWSE_SCROLLER_CONTROLS): the single tap used to pause anywhere in the
 * middle third, which took the whole band across the card with it - including
 * the seekbar's own height, where a tap is someone wanting to see where they
 * are rather than to stop the clip. A radius around where the button actually
 * sits is the smallest region that still makes pausing a one-tap gesture.
 *
 * In pixels against the card's own size, because this is answering a question
 * about a touch on that card rather than laying anything out.
 */
private fun PointerInputScope.isOnPlayPauseSpot(offset: Offset, width: Int, height: Int): Boolean {
    val radius = ScrollerPlayPauseSpotRadius.toPx()
    val dx = offset.x - width / 2f
    val dy = offset.y - height / 2f
    return dx * dx + dy * dy <= radius * radius
}

/**
 * Which third of a card's clip [x] fell in, the same split the real player's
 * double-tap gestures use.
 */
private fun tapZoneAt(x: Float, width: Int): ScrollerPlaybackState.TapZone {
    val third = width / 3f
    return when {
        x < third -> ScrollerPlaybackState.TapZone.Left
        x > third * 2f -> ScrollerPlaybackState.TapZone.Right
        else -> ScrollerPlaybackState.TapZone.Center
    }
}

/** Used only until a card's cover has loaded and can be measured. */
const val ScrollerFallbackAspectRatio = 16f / 9f

/** Tallest and widest a card may be, whatever the media says. */
const val ScrollerMinAspectRatio = 0.4f
const val ScrollerMaxAspectRatio = 3f

/**
 * How far from the middle of a card a tap still counts as the play/pause
 * button's.
 *
 * Comfortably larger than the 96.dp button the controls draw there, so the
 * gesture does not demand precision, and far short of the seekbar along the
 * bottom or the sound button in the corner.
 */
private val ScrollerPlayPauseSpotRadius = 72.dp

/** The dead space between cards. */
val ScrollerCardSpacing = 24.dp

val ScrollerCardPadding = 16.dp
val ScrollerClipCorner = 12.dp

const val SelectedItemAlpha = 0.76f

/** Behind the clip, where content of another shape leaves a gap. */
val ScrollerDeadSpaceColor = Color.Black
val ScrollerPlaceholderColor = Color(0x1F888888)
// <-- AM (ENTRY_SCROLLER)
