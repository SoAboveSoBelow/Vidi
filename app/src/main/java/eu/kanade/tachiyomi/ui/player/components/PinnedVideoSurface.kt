package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// AM (ENTRY_SCROLLER) -->
/**
 * How a video surface is sized, shared by the player and the feeds.
 *
 * Written for PlayerScreen, where the two rules were worked out, and moved
 * here so a feed's surface follows them rather than rediscovering what they
 * are for:
 *
 * AM (FIXED_SURFACE_SIZE): the surface is the VIDEO's shape, never the
 * container's, and the picture is scaled into the container afterwards. mpv
 * draws into the buffer it is given and the view scales that buffer to its own
 * bounds, so a surface shaped like the container is a stretched picture.
 *
 * AM (PINNED_SURFACE_SURVIVES_PIP): its size does not follow the container.
 * Scaling a layer is free; resizing the buffer is a reallocation, and while it
 * happens the old buffer is drawn into the new bounds - which is the squash
 * seen on the way in and out of a size change.
 *
 * Together they are why expanding a card is a change of [PinnedSurfaceGeometry.fitScale]
 * and nothing else: the card and the expanded view draw one buffer at two
 * scales, with no reallocation between them.
 */
data class PinnedSurfaceGeometry(
    /** The surface's own size, in px - the video's shape, pinned. */
    val widthPx: Float,
    val heightPx: Float,
    /** What it is scaled by to fit the container it is drawn in. */
    val fitScale: Float,
)

/**
 * The player's own pin: the video's shape at the window's longest edge.
 *
 * Generous on purpose - it renders above the screen's resolution for a
 * portrait video and lets the system scale down - which one session can
 * afford and a feed cannot. See [feedPinnedSurfaceGeometry].
 */
fun pinnedSurfaceGeometry(
    aspect: Float,
    longEdgePx: Int,
    containerWidthPx: Float,
    containerHeightPx: Float,
): PinnedSurfaceGeometry = geometry(
    widthPx = longEdgePx.toFloat(),
    heightPx = longEdgePx / aspect,
    containerWidthPx = containerWidthPx,
    containerHeightPx = containerHeightPx,
)

/**
 * A feed's pin: the video's shape at the size it is drawn when it fills the
 * screen, which is the largest any card is ever drawn.
 *
 * Not the player's pin, for the one reason the player does not have: a feed
 * holds several instances at once. Pinning each to the window's long edge
 * gives a portrait clip a buffer taller than the screen - tens of MB a card,
 * several cards at a time - to be scaled down everywhere it is drawn. Fitting
 * to the screen is the same invariance (it depends on the clip and the screen,
 * never on the container) at the size that is actually needed.
 */
fun feedPinnedSurfaceGeometry(
    aspect: Float,
    screenWidthPx: Float,
    screenHeightPx: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
): PinnedSurfaceGeometry {
    val fullscreenScale = minOf(screenWidthPx / aspect, screenHeightPx)
    return geometry(
        widthPx = fullscreenScale * aspect,
        heightPx = fullscreenScale,
        containerWidthPx = containerWidthPx,
        containerHeightPx = containerHeightPx,
    )
}

private fun geometry(
    widthPx: Float,
    heightPx: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
) = PinnedSurfaceGeometry(
    widthPx = widthPx,
    heightPx = heightPx,
    fitScale = minOf(containerWidthPx / widthPx, containerHeightPx / heightPx),
)

/**
 * The screen's size in px, which a feed's pin is computed against.
 *
 * The screen rather than whatever is hosting the surface, so a card in a list
 * and that same card expanded over the whole screen are handed one number.
 */
@Composable
fun rememberScreenSizePx(): Pair<Float, Float> {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        with(density) {
            // The longer side as height: rotating must not re-pin the surface,
            // and a clip's fullscreen size is the same either way round.
            val widthPx = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.toPx()
            val heightPx = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.toPx()
            widthPx to heightPx
        }
    }
}

/**
 * A feed's video surface: the clip's shape at the pinned size, scaled to fit
 * wherever it is drawn.
 *
 * PlayerScreen does not use this - it needs the same geometry under its own
 * pinch-zoom layer and coverage reporting - but it computes its numbers with
 * [pinnedSurfaceGeometry], so there is one definition of the rule.
 */
@Composable
fun FeedVideoSurfaceBox(
    aspect: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val (screenWidthPx, screenHeightPx) = rememberScreenSizePx()
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val geometry = feedPinnedSurfaceGeometry(
            aspect = aspect,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            containerWidthPx = with(density) { maxWidth.toPx() },
            containerHeightPx = with(density) { maxHeight.toPx() },
        )
        Box(
            modifier = Modifier
                // requiredSize, not size: size is clamped by the incoming
                // constraints, which is the opposite of what a surface
                // deliberately larger than its container needs.
                .requiredSize(
                    width = with(density) { geometry.widthPx.toDp() },
                    height = with(density) { geometry.heightPx.toDp() },
                )
                .graphicsLayer {
                    scaleX = geometry.fitScale
                    scaleY = geometry.fitScale
                },
        ) {
            content()
        }
    }
}
// <-- AM (ENTRY_SCROLLER)
