package aniyomi.ui.browse.scroller

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

// AM (ENTRY_SCROLLER): moved out of BrowseSourceScroller, which wrote it, so
// the entry screen's episode feed gates on the same measure rather than
// growing a second one beside it. Takes a ScrollableState rather than a
// LazyListState for the same reason: one feed is a list, the other a grid.
// AM (SCROLLER_SPEED_GATE) -->
/**
 * How fast the feed is being scrolled, as a state the composition can read.
 *
 * Measured from the deltas the list actually consumed rather than from item
 * indices: cards are not all the same height - the whole point of the aspect
 * work above - so an index rate says different things about the same gesture
 * on a feed of portrait clips and a feed of landscape ones. Pixels per second,
 * converted from a dp threshold so the answer is the same on every density.
 *
 * Two thresholds, not one. A single one chatters around its own value as a
 * fling decays, and every crossing restarts the effects keyed on it; engaging
 * high and releasing low means the gate opens once, when the feed has genuinely
 * slowed, and the smoothing below keeps a single jittery sample from opening it
 * early.
 */
@Stable
class ScrollSpeedTracker(
    private val engagePxPerSecond: Float,
    private val releasePxPerSecond: Float,
) : NestedScrollConnection {

    /** True while the feed is moving too fast for anything new to be worth starting. */
    var isFast by mutableStateOf(false)
        private set

    private var smoothed = 0f
    private var lastSampleNanos = 0L

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val now = System.nanoTime()
        val elapsed = (now - lastSampleNanos) / NANOS_PER_SECOND
        lastSampleNanos = now
        // A first sample has no interval, and a long gap is two gestures rather
        // than one slow one - neither says anything about the current speed.
        if (elapsed > 0f && elapsed <= MAX_SAMPLE_SECONDS) {
            val instant = abs(consumed.y) / elapsed
            smoothed += (instant - smoothed) * SPEED_SMOOTHING
            isFast = smoothed > if (isFast) releasePxPerSecond else engagePxPerSecond
        }
        return Offset.Zero
    }

    /** The feed has come to rest, whatever the last sample happened to read. */
    fun onScrollStopped() {
        smoothed = 0f
        lastSampleNanos = 0L
        isFast = false
    }
}

@Composable
fun rememberScrollSpeedTracker(scrollState: ScrollableState): ScrollSpeedTracker {
    val density = LocalDensity.current
    val tracker = remember(density) {
        with(density) {
            ScrollSpeedTracker(
                engagePxPerSecond = FastScrollEngageSpeed.toPx(),
                releasePxPerSecond = FastScrollReleaseSpeed.toPx(),
            )
        }
    }
    // The deltas stop arriving when the scroll does, so without this the last
    // sample of a fling would stand as the feed's speed for as long as it sat
    // still - which is the gate stuck shut.
    LaunchedEffect(tracker, scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .collectLatest { scrolling -> if (!scrolling) tracker.onScrollStopped() }
    }
    return tracker
}

/**
 * Above this the feed is being flung past rather than read, so nothing new is
 * started. Derived rather than felt: a card is around 600dp tall on a phone, and
 * a card passing in under a quarter of a second is one nobody is looking at.
 */
private val FastScrollEngageSpeed = 2_400.dp

/** And below this it is being read again - see ScrollSpeedTracker on the pair. */
private val FastScrollReleaseSpeed = 800.dp

/** How much of each new sample the running speed takes. */
private const val SPEED_SMOOTHING = 0.3f

/** Longer than this between samples is a new gesture, not a slow one. */
private const val MAX_SAMPLE_SECONDS = 0.25f

private const val NANOS_PER_SECOND = 1_000_000_000f
// <-- AM (SCROLLER_SPEED_GATE)
