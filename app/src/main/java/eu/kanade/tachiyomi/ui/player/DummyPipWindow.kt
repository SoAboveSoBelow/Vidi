package eu.kanade.tachiyomi.ui.player

// AM (DUMMY_PIP_REBUILD) -->
// Rebuild of the in-app floating mini-player ("dummy pip") to behave like
// Samsung's One UI PiP window. This file is deliberately self-contained:
// PlayerHostScreen only wires session state (isDummyPipActive, video
// dimensions, pause state) and action callbacks in - every gesture,
// animation, and control inside the floating window lives here.
//
// Behaviors implemented, matched against real Samsung PiP:
//  - Drag anywhere; on release the window glides (spring + fling velocity)
//    to the nearest left/right edge and clamps vertically on-screen.
//  - Stash: release the window with its center pushed against a screen
//    edge and it slides off-screen, leaving a small tab handle. Tap the
//    tab to pop the window back out; drag the tab to pull it out and
//    reposition in one motion. Playback keeps running while stashed.
//  - Double-tap toggles between the default size and the largest allowed
//    size, anchored on the window's center. Single-tap still reveals the
//    controls (delayed by the double-tap timeout, same trade-off the
//    system PiP makes).
//  - Enter/exit morph: entering pip visibly shrinks the fullscreen player
//    into the floating window; expanding grows it back. This is a
//    paint-time graphicsLayer scale/translate around the REAL small
//    layout - the transform only exists for the ~250ms of the animation
//    and returns to identity afterward, so it doesn't reintroduce the
//    persistent-graphicsLayer-wrap structure that broke video rendering
//    twice before (see DUMMY_PIP_REAL_SIZE_REVERT's history). At rest the
//    video still renders at its own real size, exactly as before.
//  - Tap reveals controls (scrim + Samsung-style layout: headphones
//    top-left, expand/close top-right, prev/play/next bottom-center),
//    auto-hiding after 3s regardless of playback state.
//  - Swipe the window down into the nav-bar region to dismiss (pause +
//    end the session UI), same semantics as the previous implementation.
//
// Position model: the window is tracked by its CENTER (centerX/centerY),
// not its top-left offset. Every positioning rule - edge snap, stash,
// center-anchored resize, pinch anchoring - is simpler and less
// error-prone in center space.
//
// State/animation split, forced by Compose's @RestrictsSuspension on
// AwaitPointerEventScope: awaitEachGesture's block may NOT call arbitrary
// suspend functions (no Animatable.snapTo/stop in there - confirmed at
// compile time). So:
//  - centerX/centerY/sizeScale are plain mutableFloatStateOf, written
//    DIRECTLY in the gesture loop (not suspend, zero-lag 1:1 tracking).
//  - Every programmatic movement (settle, stash, unstash, double-tap
//    resize) is a non-suspend starter that launches a Job animating those
//    same vars via androidx.compose.animation.core.animate. A new
//    touch-down cancels the in-flight job (Job.cancel() is not suspend,
//    so that's allowed inside the gesture scope too).
//  - Only the enter/exit morph keeps an Animatable (transition), driven
//    exclusively from LaunchedEffects/normal coroutines, never from
//    inside a gesture scope.
//
// The gesture overlay still consumes every pointer event from the first
// touch-down, and control buttons are still hit-tested manually on
// release - PlayerScreen's own GestureHandler (swipe-to-seek/volume/
// brightness) lives in a sibling subtree underneath and reacts to raw
// unconsumed events no matter when consumption happens, so IconButton's
// own click detection can never be relied on here. See the old
// DUMMY_PIP_SIBLING_GESTURE_LEAK_FIX / DUMMY_PIP_CONSUME_ORDER_FIX
// comments (git history) for the full investigation.
// <-- AM (DUMMY_PIP_REBUILD)

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Session-level callbacks the floating window needs. All session/holder
 * mutations stay in PlayerHostScreen - this component never touches
 * PlayerMediaHolder directly.
 */
class DummyPipActions(
    val onTogglePlayPause: () -> Unit,
    val onSkipPrevious: () -> Unit,
    val onSkipNext: () -> Unit,
    /** Called AFTER the exit morph has visually completed - flip isDummyPipActive false here. */
    val onExpand: () -> Unit,
    /** Pause + end the session UI (close button / swipe-down dismiss). */
    val onDismiss: () -> Unit,
    /** Hide the window, keep playback + notification alive (headphones button). */
    val onEnterBackground: () -> Unit,
)

/**
 * External handle for requesting an animated expand-to-fullscreen from
 * outside the window (e.g. PlayerHostScreen routing a back press while
 * pip is active - real Samsung PiP returns to the app on back).
 */
class DummyPipController {
    internal val expandRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun requestExpand() {
        expandRequests.tryEmit(Unit)
    }
}

@Composable
fun rememberDummyPipController(): DummyPipController = remember { DummyPipController() }

// AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX) -->
// The current corner radius the video's TextureView should clip itself to,
// in VIEW-local px (0 = no clip). Needed because neither Compose clip path
// can round the video during a resize: the layer's outline clip is applied
// to the TextureView in a space that doesn't scale with the layer (crops
// the pinch overshoot at the layout bounds - the reason clip turns off
// while pinching), and an offscreen DstIn content mask never touches the
// video at all (TextureView content is composited as its own hardware
// layer, outside the Compose layer's buffer). A View-level outline lives
// on the TextureView's OWN render node, so ancestor scale/tilt transforms
// it together with the video. Read the float ONLY in AndroidView's update
// block - the radius changes per frame mid-pinch, and reading it at
// composition scope would recompose the whole player subtree per frame.
// Null (unset) outside the dummy pip host: PlayerActivity's standalone
// player then never clips.
// <-- AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX)
val LocalDummyPipCornerRadiusPx = compositionLocalOf<MutableFloatState?> { null }

private enum class DummyPipMode { Fullscreen, AnimatingIn, Pip, StashedLeft, StashedRight, AnimatingOut }

private enum class PipHitRegion { Expand, Close, Headphones, Previous, PlayPause, Next, None }

// Sizing: AOSP's real PIP values (frameworks/base/libs/WindowManager/Shell/
// res/values/config.xml) - default 60% of screen width.
private const val PIP_BASE_WIDTH_PERCENT = 0.6f
private const val PIP_MAX_WIDTH_PERCENT = 1f
private const val MAX_SIZE_SCALE = PIP_MAX_WIDTH_PERCENT / PIP_BASE_WIDTH_PERCENT
private const val FALLBACK_ASPECT_RATIO = 16f / 9f
// Minimum size, modeled on AOSP's PipBoundsAlgorithm (NOT a fixed min
// width, and not total pixels either - the config comment calls it
// "adaptive size based loosely on area"): a minimum EDGE plus a constant
// DIAGONAL between two aspect-ratio limits. Min edge calibration
// history: 60dp produced "very small" windows; 120dp (derived from the
// "+50% over minimum" hint read as the double-tap max) pushed the
// landscape minimum to ~59% of screen width - ABOVE the ~47%
// controls-reveal expand-to size, so nothing could go below the
// expand-to size and the reveal-grow could never trigger. Real PiP's
// reveal-grow exists precisely because windows CAN be smaller than
// that size, so the minimum must sit below it. 80dp was still slightly
// larger than real PiP's floor ("real pip can still get a bit smaller");
// 70dp: landscape min width = 70*1.78 = ~125dp (~37% of screen),
// portrait min = 70dp (~23%) - still above the 60dp that read as
// "very small". 75dp: midpoint of the two on-device brackets (70 =
// "still smaller than real pip's min", 80 = "slightly above it").
private val PIP_MIN_EDGE = 75.dp
private const val PIP_ASPECT_LIMIT_FOR_MIN_SIZE = 1.777778f

private val PIP_EDGE_MARGIN = 16.dp
private val PIP_CORNER_RADIUS = 12.dp
// Icon sizes matched on-device against real Samsung PiP - 24/34dp read
// as clearly oversized inside the small window, and real PiP's
// play/pause button is the SAME size as the other buttons, not enlarged.
private const val PIP_ICON_SIZE_DP = 20
private const val PIP_PLAY_ICON_SIZE_DP = PIP_ICON_SIZE_DP
// Distance of the control icons from the window's edges - measured off
// real PiP screenshots: ~24dp from corner to icon CENTER, i.e. ~14dp
// edge inset with 20dp icons.
private val PIP_CONTROLS_INSET = 14.dp
// Touch zones, measured off real PiP's pressed-state ripple circles:
// bottom-row buttons get a BIG zone (~56dp diameter circle, so a 48dp
// tall hit band with midpoint-split horizontal zones), top-row buttons
// a small one (~28dp circle, icon + inset band). The same two sizes are
// used for the pressed-flash circle drawn on tap.
private val PIP_BOTTOM_HIT_BAND = 56.dp
// Extra slop added around every control's hit zone beyond the icon's own
// bounds - the visual icons stay small, but the tappable area around them
// shouldn't require pixel-precision on a window this size.
private val PIP_HIT_SLOP = 8.dp
private val PIP_TOP_FLASH_RADIUS = 15.dp
private val PIP_BOTTOM_FLASH_RADIUS = 28.dp
// The pressed circle is a PULSE on real PiP (grows + fades), not a
// static highlight. 300ms read as clearly faster than the real pip's
// pulse on-device; 500ms matches its cadence.
private const val PIP_FLASH_MS = 500
// Samsung tilt zooming: rotating the two pinch fingers (one up, one
// down) tilts the window, hard-capped at this angle. On release the
// window LEVELS back to 0 - real PiP never keeps a tilt. Cap measured
// off a max-tilt screenshot of real PiP: both full-width video edges
// sit at exactly -12.99 degrees (the original 30 was over double).
private const val MAX_PIP_TILT_DEG = 13f
// There is NO fixed max size mid-pinch: the window grows as far as the
// fingers spread (past the screen edge, like real PiP) and the size
// limit is enforced ONLY on release, by the animated pull-back. This
// multiplier is just a numeric safety net against degenerate scales,
// not a visible cap.
private const val PINCH_MAX_OVERSHOOT = 4f
// AOSP PipPinchResizingAlgorithm: the resize tracks the finger spread
// EXACTLY 1:1 while the desired size is within [min, max] - there is no
// in-range damping at all. Only the OVERSHOOT past the limits is damped,
// at OVERRESIZE_DAMP_FACTOR - that's the "variable dampener" feel: free
// 1:1 tracking in range, resistance only once you stretch past the edge.
private const val PINCH_OVERRESIZE_DAMP = 0.25f
// The overshoot pull-back on pinch release: essentially the expansion
// played in reverse - a smooth eased tween, NOT a spring, and run on
// the paint-time pinchScale (see the release handling).
private const val PULL_BACK_MS = 300
private val PIP_CENTER_ROW_SPACING = 28.dp
// Real PiP spaces its top-corner buttons wide apart (~15dp edge-to-edge
// measured off screenshots) - they read as distinct targets, not one
// squished cluster.
private val PIP_TOP_ROW_SPACING = 16.dp

// Stash triggers only when MORE THAN HALF the window has been dragged
// off a screen edge (real PiP behavior - a center-within-56dp-of-edge
// rule stashed far too eagerly). Center off-screen == over 50% hidden.

// Enter/exit morph + double-tap toggle speeds, relaxed after on-device
// comparison: 250/200ms read as "a lot faster than the real pip".
private const val ENTER_EXIT_MORPH_MS = 300
private const val TOGGLE_SIZE_MS = 350
// AM (DUMMY_PIP_UNIFIED_DISMISS_FADE) -->
// Real PiP's dismissal (X, swipe-to-dismiss) is a quick in-place FADE,
// ~150ms - no sliding, no morph. All three dummy-pip exits that tear
// the window down (close button, swipe-down, hide-to-background) use
// this one fade now.
// <-- AM (DUMMY_PIP_UNIFIED_DISMISS_FADE)
private const val PIP_DISMISS_FADE_MS = 150
private const val CONTROLS_REVEAL_GROW_MS = 300
private const val CONTROLS_AUTO_HIDE_MS = 3_000L
// Taps within this window after a double-tap are treated as finger-bounce
// from the same motion and ignored (notably: they don't reveal controls).
private const val DOUBLE_TAP_EXTRA_TAP_SUPPRESS_MS = 100L
// Taps within this window after a toggle-size resize FINISHES are also
// ignored - the finger that just double-tapped tends to linger/land once
// more, and real PiP doesn't pop controls up right after a resize either.
private const val POST_RESIZE_TAP_SUPPRESS_MS = 300L
// AM (DUMMY_PIP_SYSTEM_VOLUME_UI) -->
// The external volume pill was removed entirely: volume keys while the
// dummy pip is up now go straight to AudioManager.adjustStreamVolume(
// FLAG_SHOW_UI) in MainActivity.onKeyDown - the OS's own volume panel,
// exactly what real PiP shows, with no custom UI to keep in sync.
// <-- AM (DUMMY_PIP_SYSTEM_VOLUME_UI)
// Velocity cap for a release fling, px/s. 4000 clipped real fast
// diagonal swipes - on real PiP a small but fast swipe crosses corner
// to corner, which needs ~2x that headroom.
private const val MAX_FLING_VELOCITY = 9_000f
// How far ahead (in seconds of current velocity) a released fling is
// projected when picking the settle target - this is what lets a hard
// throw cross over to the far edge and lets the window keep gliding
// along the Y axis as it lands, instead of both axes stopping dead.
// 0.2s read as "momentum dull, side pull does little" on-device; 0.4s
// gives a fast swipe the travel distance real PiP shows.
private const val FLING_PROJECTION_SECONDS = 0.4f
// Movement physics, split per axis after on-device comparison against
// real Samsung PiP ("floaty", "bounces on both axes even when it hits a
// wall", "movement seems slow"). Real PiP: the axis that LANDS on a wall
// stops dead-on with no overshoot; only the axis gliding ALONG the wall
// keeps any momentum, and even that barely overshoots. So:
//  - PIP_WALL_SPEC: critically damped (dampingRatio 1 = zero bounce).
//    StiffnessLow, not MediumLow: with a fling's initial velocity a
//    critically damped spring's travel speed scales with stiffness, and
//    MediumLow bled the speed off too fast ("momentum feels dull").
//  - PIP_GLIDE_SPEC: for the along-wall axis when it does NOT hit a
//    corner - slight underdamping keeps the glide alive without a
//    visible bounce.
//  - PIP_SETTLE_SPEC: default for programmatic (non-fling) moves - kept
//    snappier, these never carry fling velocity.
private val PIP_WALL_SPEC: AnimationSpec<Float> = spring(
    dampingRatio = 1f,
    stiffness = Spring.StiffnessLow,
)
private val PIP_GLIDE_SPEC: AnimationSpec<Float> = spring(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessVeryLow,
)
private val PIP_SETTLE_SPEC: AnimationSpec<Float> = spring(
    dampingRatio = 1f,
    stiffness = Spring.StiffnessMediumLow,
)
// Fraction of the window's own height that must be dragged below the
// nav-bar line before release dismisses it - real PiP needs a deliberate
// push off the bottom, not just touching the edge.
private const val DISMISS_BELOW_NAV_FRACTION = 0.5f
// How much of the video stays visible at the screen edge while stashed -
// real Samsung PiP's stash keeps a live sliver of the playing video
// showing, not a fully hidden window.
private val PIP_STASH_PEEK = 32.dp
// The fixed size (in sizeScale units - 1f = the default 60%-of-screen
// width) the window temporarily grows TO when controls are revealed, if
// it's currently smaller than this. Not a multiplier: a window at any
// size below the target becomes exactly the target while controls are
// shown; a window already at/above it doesn't grow at all. Measured off
// real PiP screenshots: the revealed window is ~47% of screen width
// (505px of 1080), i.e. 0.467/0.6 = 0.78 of the default width.
private const val CONTROLS_REVEAL_TARGET_SCALE = 0.78f

private fun coerceInSafe(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max.coerceAtLeast(min))

// AM (DUMMY_PIP_PINCH_ROTATION_ANCHOR_FIX) -->
// Rotate a vector by the window's tilt. The pinch layer scales AND
// rotates (Samsung tilt) around the window center, so every local ->
// screen conversion during a pinch must include the rotation - doing
// only the scale (the old code) walked the window off the pinch
// midpoint whenever the gesture added a few degrees of tilt.
// <-- AM (DUMMY_PIP_PINCH_ROTATION_ANCHOR_FIX)
private fun Offset.rotatedByDegrees(degrees: Float): Offset {
    if (degrees == 0f) return this
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return Offset(x * c - y * s, x * s + y * c)
}

@Composable
fun DummyPipContainer(
    isPipRequested: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    isPaused: Boolean,
    actions: DummyPipActions,
    controller: DummyPipController = rememberDummyPipController(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Live LayoutCoordinates for the pinch gesture math: the overlay's
    // (captured at the pointerInput's position in the modifier chain,
    // used to map every finger position to screen space) and the root
    // container's (centerX/centerY live in container space; the gesture
    // math runs in screen space and this converts between the two).
    // Declared here, before BoxWithConstraints, so the container's own
    // modifier can reference pipRootCoords.
    var gestureOverlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pipRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { pipRootCoords = it }) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val edgeMarginPx = with(density) { PIP_EDGE_MARGIN.toPx() }
        // AM (DUMMY_PIP_ENTRY_NAV_BAR_FIX) -->
        // The STABLE nav bar height, not the visibility-aware one: plain
        // navigationBars reports 0 while the bar is hidden, and the
        // fullscreen player hides the system bars - so entering pip from
        // an immersive player computed every bottom clamp against a 0
        // inset and the window ended up partially BELOW the nav bar once
        // the bars reappeared alongside the pip. ignoringVisibility
        // reports the bar's size either way, which is also where real PiP
        // docks.
        // <-- AM (DUMMY_PIP_ENTRY_NAV_BAR_FIX)
        val navigationBarHeightPx = maxOf(
            WindowInsets.navigationBars.getBottom(density),
            WindowInsets.navigationBarsIgnoringVisibility.getBottom(density),
        ).toFloat()
        // AM (DUMMY_PIP_LANDSCAPE_NAV_BAR_FIX) -->
        // In LANDSCAPE the nav bar isn't at the bottom at all - it sits
        // on the left or right EDGE, where getBottom() reports 0. Every
        // X clamp/dock that ignored the side insets let the window slide
        // underneath the bar there. Same ignoringVisibility rule as the
        // bottom inset: the player hides the bars, but the pip docks
        // against where they ARE.
        // <-- AM (DUMMY_PIP_LANDSCAPE_NAV_BAR_FIX)
        val layoutDirection = LocalLayoutDirection.current
        val navigationBarLeftPx = maxOf(
            WindowInsets.navigationBars.getLeft(density, layoutDirection),
            WindowInsets.navigationBarsIgnoringVisibility.getLeft(density, layoutDirection),
        ).toFloat()
        val navigationBarRightPx = maxOf(
            WindowInsets.navigationBars.getRight(density, layoutDirection),
            WindowInsets.navigationBarsIgnoringVisibility.getRight(density, layoutDirection),
        ).toFloat()
        // AM (DUMMY_PIP_CUTOUT_AVOIDANCE_FIX) -->
        // The front camera hole. In portrait it lives inside the status
        // bar's top inset, but in LANDSCAPE it sits on a SIDE edge where
        // neither the status bar nor the nav bar insets cover it - the
        // pip could slide right underneath it. AOSP insets the pip's
        // movement bounds by the display cutout exactly like the system
        // bars, so the clamps below use the per-side UNION of both.
        // <-- AM (DUMMY_PIP_CUTOUT_AVOIDANCE_FIX)
        // safeDrawing = system bars + display cutout (WindowInsets
        // .displayCutout isn't available in this project's Compose
        // artifact), so the cutout is the part safeDrawing adds ON TOP
        // of the bars. Uses ignoringVisibility for the bars side of the
        // delta so a hidden bar isn't mistaken for cutout space.
        val safeDrawingInsets = WindowInsets.safeDrawing
        val systemBarsIgnoringVisibility = WindowInsets.systemBarsIgnoringVisibility
        val cutoutLeftPx = (
            safeDrawingInsets.getLeft(density, layoutDirection) -
                systemBarsIgnoringVisibility.getLeft(density, layoutDirection)
            ).coerceAtLeast(0).toFloat()
        val cutoutRightPx = (
            safeDrawingInsets.getRight(density, layoutDirection) -
                systemBarsIgnoringVisibility.getRight(density, layoutDirection)
            ).coerceAtLeast(0).toFloat()
        val cutoutTopPx = (
            safeDrawingInsets.getTop(density) -
                systemBarsIgnoringVisibility.getTop(density)
            ).coerceAtLeast(0).toFloat()
        // The status bar always draws OVER the window, so the window must
        // never slide underneath it - the top clamp is status-bar-aware
        // everywhere (drag settle, stash dock, IME push).
        // NOTE: it's a TOP inset - statusBars.getBottom() returns 0 (that
        // bug let the window rest underneath it). systemBars.getTop as a
        // fallback source, 24dp as a last resort if both report 0.
        val statusBarHeightPx = maxOf(
            WindowInsets.statusBars.getTop(density),
            WindowInsets.systemBars.getTop(density),
        ).toFloat().let { if (it > 0f) it else with(density) { 24.dp.toPx() } }
        // Live IME (keyboard) height - the window must never go below an
        // open keyboard, and gets pushed above it proactively (see the
        // LaunchedEffect further down).
        val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
        val baseWidthPx = screenWidthPx * PIP_BASE_WIDTH_PERCENT
        val cornerRadiusPx = with(density) { PIP_CORNER_RADIUS.toPx() }
        val elevationPx = with(density) { 8.dp.toPx() }
        val stashPeekPx = with(density) { PIP_STASH_PEEK.toPx() }
        val minEdgePx = with(density) { PIP_MIN_EDGE.toPx() }

        // Same live video aspect ratio MainActivity's real-PIP builder uses,
        // animated so late-arriving dimensions don't snap the window's shape.
        val targetAspectRatio = if (videoWidth > 0 && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight.toFloat()
        } else {
            FALLBACK_ASPECT_RATIO
        }
        val aspectRatio by animateFloatAsState(targetValue = targetAspectRatio, label = "dummyPipAspectRatio")

        // Latest-value refs for the long-lived gesture coroutine below -
        // pointerInput(Unit) never relaunches, so it must read these
        // rather than capturing stale first-composition values.
        val currentAspect = rememberUpdatedState(aspectRatio)
        val currentBaseWidth = rememberUpdatedState(baseWidthPx)
        val currentScreenWidth = rememberUpdatedState(screenWidthPx)
        val currentScreenHeight = rememberUpdatedState(screenHeightPx)
        val currentEdgeMargin = rememberUpdatedState(edgeMarginPx)
        val currentNavBarHeight = rememberUpdatedState(navigationBarHeightPx)
        val currentNavBarLeft = rememberUpdatedState(navigationBarLeftPx)
        val currentNavBarRight = rememberUpdatedState(navigationBarRightPx)
        val currentCutoutLeft = rememberUpdatedState(cutoutLeftPx)
        val currentCutoutRight = rememberUpdatedState(cutoutRightPx)
        val currentCutoutTop = rememberUpdatedState(cutoutTopPx)
        val currentStatusBarHeight = rememberUpdatedState(statusBarHeightPx)
        val currentImeBottom = rememberUpdatedState(imeBottomPx)
        val currentMinEdge = rememberUpdatedState(minEdgePx)
        val currentActions = rememberUpdatedState(actions)

        var mode by remember { mutableStateOf(if (isPipRequested) DummyPipMode.Pip else DummyPipMode.Fullscreen) }
        // False until the entry effect has placed the window at its default
        // bottom-right position - must start false even when isPipRequested
        // starts false, or the first later entry would never get positioned.
        var hasPositioned by remember { mutableStateOf(false) }
        // AM (DUMMY_PIP_ENTRY_NO_CONTROLS_REVEAL) -->
        // Starts false and stays false through entry: entering pip used to
        // reveal the controls immediately, which - for any window below the
        // reveal threshold - also triggered the controls-reveal GROW: the
        // window visibly expanded on every entry, then shrank back when the
        // controls auto-hid. Entry is now silent (real PiP's entry doesn't
        // grow the window either); the first TAP reveals controls + grow as
        // usual.
        // <-- AM (DUMMY_PIP_ENTRY_NO_CONTROLS_REVEAL)
        var controlsShown by remember { mutableStateOf(false) }
        // Extra key so re-tapping while controls are already visible still
        // restarts the auto-hide timer (re-setting true->true wouldn't).
        var controlsRevision by remember { mutableIntStateOf(0) }
        // Which control shows real PiP's pressed pulse right now (null =
        // none). flashTick re-triggers the pulse animation per tap.
        var flashRegion by remember { mutableStateOf<PipHitRegion?>(null) }
        var flashTick by remember { mutableIntStateOf(0) }
        val flashProgress = remember { Animatable(1f) }
        // Transient PAINT-TIME pinch state - the core fix for pinch
        // frame loss. Writing sizeScale mid-pinch changed the window's
        // real .size(), forcing a full relayout (incl. the video
        // surface) on every move event. Instead the pinch accumulates
        // into pinchScale (a graphicsLayer multiplier, zero layout cost)
        // and commits into sizeScale once, on release.
        var pinchScale by remember { mutableFloatStateOf(1f) }
        // Samsung tilt zooming, degrees, capped at MAX_PIP_TILT_DEG.
        var pipRotation by remember { mutableFloatStateOf(0f) }
        // Double-tap toggle bookkeeping: real PiP expands to max and
        // collapses back to the LAST USER-ADJUSTED size (a pinch sets
        // it, the expansion itself never overwrites it).
        var lastAdjustedScale by remember { mutableFloatStateOf(1f) }
        var toggledExpanded by remember { mutableStateOf(false) }
        // True while a pinch resize is in flight - drives the
        // full-screen touch blocker below (real PiP swallows touches to
        // everything behind the window during a resize).
        var resizeActive by remember { mutableStateOf(false) }
        // True while a one-finger drag owns the position - the
        // DUMMY_PIP_ENTRY_BOUNDS_FIX re-clamp must never yank the window
        // out from under a finger (a swipe-down dismiss drag legitimately
        // leaves the on-screen band).
        var dragActive by remember { mutableStateOf(false) }
        // The TextureView's own corner radius (see LocalDummyPipCornerRadiusPx):
        // 0 at rest (the layer clip handles corners then, as before), and
        // PIP_CORNER_RADIUS compensated by the live pinch scale while a
        // resize/tilt gesture runs, so the on-screen radius stays constant.
        // Written from the snapshotFlow effect below, read only in
        // MpvSurface's AndroidView update block.
        val pipViewCornerRadiusPx = remember { mutableFloatStateOf(0f) }
        // True while the overshoot pull-back is animating pinchScale
        // back down - if it gets cancelled mid-flight, cancelMove
        // commits the paint-time scale into the layout so the two never
        // disagree.
        var pullBackRunning by remember { mutableStateOf(false) }
        // True while a double-tap toggle-size is animating. The toggle
        // also runs paint-time on pinchScale now (see startToggleSize),
        // so cancelMove must commit it exactly like a pull-back.
        var toggleRunning by remember { mutableStateOf(false) }
        // When the last toggle-size resize ended (completion OR mid-flight
        // commit) - taps shortly after it are absorbed, see
        // POST_RESIZE_TAP_SUPPRESS_MS.
        var lastToggleEndTime by remember { mutableLongStateOf(0L) }

        // Plain state, written directly inside the gesture loop - see the
        // file header for why these are NOT Animatables.
        var sizeScale by remember { mutableFloatStateOf(1f) }
        var centerX by remember { mutableFloatStateOf(0f) }
        var centerY by remember { mutableFloatStateOf(0f) }
        // 0 = visually fullscreen, 1 = visually the floating window. Only
        // ever driven from LaunchedEffects/normal coroutines.
        val transition = remember { Animatable(if (isPipRequested) 1f else 0f) }
        // AM (DUMMY_PIP_UNIFIED_DISMISS_FADE) -->
        // In-place fade for every window teardown (close / swipe-down /
        // hide-to-background), matching real PiP's dismissal. 1 at rest;
        // runDismissMorph/runBackgroundMorph drive it to 0, the
        // entry/exit effect snaps it back for the next session.
        // <-- AM (DUMMY_PIP_UNIFIED_DISMISS_FADE)
        val dismissAlpha = remember { Animatable(1f) }
        // The in-flight programmatic movements (settle/stash/unstash/
        // double-tap resize), one job PER AXIS so a fling can land on a
        // wall with a dead-stop X while Y keeps gliding with its own
        // physics. Both are cancelled by any new touch-down.
        var moveJobX by remember { mutableStateOf<Job?>(null) }
        var moveJobY by remember { mutableStateOf<Job?>(null) }
        // Double-tap resize runs on its own job so it never cancels (or
        // gets cancelled by) the position jobs it runs alongside.
        var sizeJob by remember { mutableStateOf<Job?>(null) }
        // The tilt level-out on pinch release - tracked so a new touch
        // (via cancelMove) can re-grab the window mid-level.
        var rotationJob by remember { mutableStateOf<Job?>(null) }
        // Live velocity of an in-flight programmatic move, px/s. Real
        // PiP keeps a fling's momentum when you grab the window mid-
        // flight and pull it sideways; without seeding the drag's
        // velocity from these, touching mid-fling killed all momentum
        // ("side pull does very little to the pip while mid fling").
        var flightVelocityX = 0f
        var flightVelocityY = 0f
        // AM (DUMMY_PIP_STALE_SETTLE_FIX) -->
        // The last settle's TARGET. On a rotation the new screen size
        // lands a frame BEFORE the new WindowInsets, so a settle launched
        // right at the rotation computes its dock from the OLD bounds and
        // lands offset from the edge / over a bar - and the
        // ENTRY_BOUNDS_FIX clamp used to skip while ANY move was in
        // flight, so the stale landing was never corrected. The clamp
        // now validates this target against the CURRENT bounds and
        // re-settles if it no longer fits.
        // <-- AM (DUMMY_PIP_STALE_SETTLE_FIX)
        var settleTargetX by remember { mutableFloatStateOf(Float.NaN) }
        var settleTargetY by remember { mutableFloatStateOf(Float.NaN) }

        // AM (DUMMY_PIP_ROTATION_FIT_FIX) -->
        // AOSP PipBoundsAlgorithm.getSizeForAspectRatio() shrinks the
        // window when it wouldn't FIT the display bounds - the cap is on
        // BOTH dimensions. The old code only ever capped the WIDTH, so a
        // window sized in portrait (60% of the tall screen's width) ended
        // up TALLER than the screen after rotating to landscape, clipped
        // off the top/bottom. This is the AOSP fit: the largest width
        // whose height also clears the status bar above and the nav
        // bar/IME below.
        // The rect the window's EDGES must stay inside: per side, the
        // union of whichever bar/cutout actually occupies that edge (so
        // an offset only exists where something is really there), plus
        // the edge margin. Every dock/clamp below routes through these.
        fun boundLeftPx() = maxOf(currentNavBarLeft.value, currentCutoutLeft.value) + currentEdgeMargin.value
        fun boundRightPx() = currentScreenWidth.value -
            maxOf(currentNavBarRight.value, currentCutoutRight.value) - currentEdgeMargin.value
        fun boundTopPx() = maxOf(currentStatusBarHeight.value, currentCutoutTop.value) + currentEdgeMargin.value
        fun boundBottomPx() = currentScreenHeight.value -
            maxOf(currentNavBarHeight.value, currentImeBottom.value) - currentEdgeMargin.value

        fun maxWindowWidthPx(): Float {
            val maxW = boundRightPx() - boundLeftPx()
            val maxH = (boundBottomPx() - boundTopPx()) * currentAspect.value.coerceAtLeast(0.01f)
            return minOf(maxW, maxH)
        }

        // Coerced to the fit so EVERY consumer (settle/stash/toggle dock
        // math, the layout below) sees a size that can't clip, even in the
        // frames between a rotation and the sizeScale re-commit in the
        // rotation effect further down.
        fun windowWidthPx() = minOf(currentBaseWidth.value * sizeScale, maxWindowWidthPx())
        fun windowHeightPx() = windowWidthPx() / currentAspect.value

        // AOSP PipBoundsAlgorithm.getSizeForAspectRatio(): within the
        // aspect limits the window keeps a constant DIAGONAL (radius =
        // hypot(limit*minEdge, minEdge)); beyond them the SHORT edge is
        // pinned to minEdge. Returns the min WIDTH in px for the current
        // aspect ratio - portrait videos end up narrower than landscape.
        fun minWindowWidthPx(): Float {
            val minEdge = currentMinEdge.value
            val ar = currentAspect.value.coerceAtLeast(0.01f)
            return when {
                ar > PIP_ASPECT_LIMIT_FOR_MIN_SIZE -> minEdge * ar
                ar < 1f / PIP_ASPECT_LIMIT_FOR_MIN_SIZE -> minEdge
                else -> {
                    val radius = hypot(PIP_ASPECT_LIMIT_FOR_MIN_SIZE * minEdge, minEdge)
                    val h = sqrt(radius * radius / (ar * ar + 1f))
                    h * ar
                }
            }
        }

        fun cancelMove() {
            moveJobX?.cancel()
            moveJobX = null
            moveJobY?.cancel()
            moveJobY = null
            sizeJob?.cancel()
            sizeJob = null
            if (pullBackRunning || toggleRunning) {
                // A cancelled pull-back or toggle-size commits wherever it
                // got to, so the paint-time pinch and the real layout size
                // never disagree after the next gesture takes over.
                sizeScale *= pinchScale
                pinchScale = 1f
                if (toggleRunning) {
                    lastToggleEndTime = android.os.SystemClock.uptimeMillis()
                }
                pullBackRunning = false
                toggleRunning = false
            }
            // NOTE: rotationJob is deliberately NOT cancelled here - the
            // tilt level-out launches on pinch release, and the settle
            // that immediately follows routes through this function, so
            // cancelling here killed the level-out the frame it started
            // (tilt never returned to 0). It's cancelled on touch-down
            // instead, where a new grab should take over the tilt.
        }

        // Non-suspend starter: launches per-axis jobs animating centerX/
        // centerY to the given targets with optional fling velocity and
        // per-axis spring specs. Safe to call from inside the restricted
        // gesture scope (launch is not suspend).
        fun animatePosition(
            targetX: Float,
            targetY: Float,
            velocityX: Float = 0f,
            velocityY: Float = 0f,
            specX: AnimationSpec<Float> = PIP_SETTLE_SPEC,
            specY: AnimationSpec<Float> = specX,
        ) {
            cancelMove()
            moveJobX = scope.launch {
                animate(centerX, targetX, velocityX, specX) { v, vel ->
                    centerX = v
                    flightVelocityX = vel
                }
            }
            moveJobY = scope.launch {
                animate(centerY, targetY, velocityY, specY) { v, vel ->
                    centerY = v
                    flightVelocityY = vel
                }
            }
        }

        // widthPx/heightPx default to the CURRENT size but can be
        // overridden with the size the window is about to become: after
        // an overshot pinch the pull-back shrinks the window while the
        // settle runs, and docking for the overshot size left the
        // shrunken window hanging off the screen edge ("falls off screen
        // at max size, tilted, on release").
        fun settleToEdge(
            velocityX: Float = 0f,
            velocityY: Float = 0f,
            widthPx: Float = windowWidthPx(),
            heightPx: Float = windowHeightPx(),
        ) {
            // Project where the fling is carrying the window and settle
            // from the PROJECTED position, not the release position: a
            // hard throw toward the far edge crosses over instead of
            // bouncing back, and leftover Y velocity becomes a visible
            // glide along the edge (clamped on-screen) rather than
            // stopping the instant the finger lifts.
            val projectedX = centerX + velocityX * FLING_PROJECTION_SECONDS
            val projectedY = centerY + velocityY * FLING_PROJECTION_SECONDS
            val w = widthPx
            val h = heightPx
            val targetX = if (projectedX < currentScreenWidth.value / 2f) {
                boundLeftPx() + w / 2f
            } else {
                boundRightPx() - w / 2f
            }
            val minY = boundTopPx() + h / 2f
            val maxY = boundBottomPx() - h / 2f
            val targetY = coerceInSafe(projectedY, minY, maxY)
            // Per-axis physics (see the spec constants): X always lands on
            // a wall, so it gets the dead-stop spec. Y gets the glide spec
            // only when it's gliding ALONG the wall to a mid-screen rest;
            // if the fling slams it into the top/bottom bound too, that
            // axis dead-stops as well - no bouncing off walls on either
            // axis.
            val yHitsWall = projectedY < minY || projectedY > maxY
            settleTargetX = targetX
            settleTargetY = targetY
            animatePosition(
                targetX,
                targetY,
                velocityX,
                velocityY,
                specX = PIP_WALL_SPEC,
                specY = if (yHitsWall) PIP_WALL_SPEC else PIP_GLIDE_SPEC,
            )
        }

        fun stashToEdge(left: Boolean) {
            controlsShown = false
            mode = if (left) DummyPipMode.StashedLeft else DummyPipMode.StashedRight
            val w = windowWidthPx()
            val h = windowHeightPx()
            // NOT fully off-screen: a PIP_STASH_PEEK-wide sliver of the
            // still-playing video stays visible at the edge, like real
            // Samsung PiP's stash (and, practically, a partially visible
            // TextureView keeps rendering frames instead of freezing).
            // Stash sliver also clears a bar/cutout on that edge (the
            // camera hole is exactly where a left/right stash peeks).
            val targetX = if (left) {
                boundLeftPx() - w / 2f + stashPeekPx
            } else {
                boundRightPx() + w / 2f - stashPeekPx
            }
            // Dock wherever along the edge the user released - Samsung's
            // edge-panel handle is user-movable, so no fixed screen
            // region can avoid it (an earlier lower-half-only restriction
            // didn't actually solve anything and just fought the user's
            // placement). Only the status/nav bars bound the dock height.
            val targetY = coerceInSafe(
                centerY,
                boundTopPx() + h / 2f,
                boundBottomPx() - h / 2f,
            )
            animatePosition(
                targetX,
                targetY,
                specX = spring(stiffness = Spring.StiffnessMediumLow),
            )
        }

        fun unstash(animated: Boolean) {
            val wasLeft = mode == DummyPipMode.StashedLeft
            mode = DummyPipMode.Pip
            val w = windowWidthPx()
            val targetX = if (wasLeft) {
                boundLeftPx() + w / 2f
            } else {
                boundRightPx() - w / 2f
            }
            if (animated) {
                animatePosition(targetX, centerY, specX = spring(stiffness = Spring.StiffnessMediumLow))
                controlsShown = true
                controlsRevision++
            } else {
                cancelMove()
                centerX = targetX
            }
        }

        // Suspend - only ever called from normal coroutines (the deferred
        // tap job, the controller collector), never from a gesture scope.
        suspend fun runExitMorph() {
            when (mode) {
                DummyPipMode.Pip -> {
                    controlsShown = false
                    pipRotation = 0f
                    mode = DummyPipMode.AnimatingOut
                    transition.animateTo(0f, tween(ENTER_EXIT_MORPH_MS, easing = FastOutSlowInEasing))
                    // Flips isDummyPipActive false - the LaunchedEffect
                    // below then lands mode in Fullscreen.
                    currentActions.value.onExpand()
                }
                DummyPipMode.StashedLeft, DummyPipMode.StashedRight -> {
                    // Off-screen - a morph from here would look wrong, just expand.
                    currentActions.value.onExpand()
                }
                else -> Unit
            }
        }

        // AM (DUMMY_PIP_UNIFIED_DISMISS_FADE) -->
        // Suspend - same calling rules as runExitMorph. Real PiP's
        // dismissal is a quick in-place FADE (no slide, no morph), and
        // every window teardown routes through it: the close button and
        // the swipe-down dismiss (onDone = onDismiss), and
        // hide-to-background below (onDone = onEnterBackground). From a
        // stash the window is already off-screen - nothing to fade.
        // <-- AM (DUMMY_PIP_UNIFIED_DISMISS_FADE)
        suspend fun runDismissMorph(onDone: () -> Unit) {
            if (mode != DummyPipMode.Pip) {
                onDone()
                return
            }
            controlsShown = false
            dismissAlpha.animateTo(0f, tween(PIP_DISMISS_FADE_MS))
            onDone()
        }

        // Suspend - same calling rules as runDismissMorph.
        suspend fun runBackgroundMorph() {
            runDismissMorph { currentActions.value.onEnterBackground() }
        }

        fun startToggleSize() {
            // maxWindowWidthPx() already includes the width-only term
            // (screenWidth - 2*edgeMargin) AND the height cap.
            val maxScale = minOf(
                MAX_SIZE_SCALE,
                maxWindowWidthPx() / currentBaseWidth.value,
            )
            // Real PiP semantics: expand to max; collapse back to the
            // last user-adjusted size (pinch commits set it - the
            // expansion itself never does). Toggling mid-expansion
            // reverses back to that same remembered size.
            val targetScale: Float
            if (toggledExpanded) {
                targetScale = lastAdjustedScale
                toggledExpanded = false
            } else {
                targetScale = maxScale
                toggledExpanded = true
            }
            cancelMove()
            // Center-anchored: centerX/centerY stay put, the size change
            // expands/shrinks around them - then settle back fully
            // on-screen (via animatePosition, so the per-axis jobs are
            // tracked and cancellable like everything else).
            // Settle targets computed for the TARGET size (the resize and
            // the settle run concurrently, so windowWidthPx() - still the
            // old size - would aim at the wrong edge).
            val targetW = currentBaseWidth.value * targetScale
            val targetH = targetW / currentAspect.value
            val targetX = if (centerX < currentScreenWidth.value / 2f) {
                boundLeftPx() + targetW / 2f
            } else {
                boundRightPx() - targetW / 2f
            }
            val targetY = coerceInSafe(
                centerY,
                boundTopPx() + targetH / 2f,
                boundBottomPx() - targetH / 2f,
            )
            settleTargetX = targetX
            settleTargetY = targetY
            // Position first (its internal cancelMove clears stale jobs),
            // THEN the size job - launching it first would let
            // animatePosition's cancelMove kill it immediately.
            animatePosition(
                targetX,
                targetY,
                specX = spring(stiffness = Spring.StiffnessMediumLow),
            )
            // AM (DUMMY_PIP_TOGGLE_PAINT_TIME_FIX) -->
            // Was animate(sizeScale...): sizeScale is the LAYOUT size, so
            // every frame relaid out the embedded TextureView - the same
            // reallocation flashing the overshoot pull-back had before it
            // moved to paint-time (v18). The toggle now runs the same way:
            // pinchScale carries the growth visually (zero relayout, and
            // pinchScale != 1f marks the layer pinching, which correctly
            // drops the clip/shadow for the duration), then the layout
            // commits once at the identical final size.
            // <-- AM (DUMMY_PIP_TOGGLE_PAINT_TIME_FIX)
            toggleRunning = true
            val startScale = sizeScale
            sizeJob = scope.launch {
                animate(0f, 1f, 0f, tween(TOGGLE_SIZE_MS, easing = FastOutSlowInEasing)) { v, _ ->
                    pinchScale = lerp(startScale, targetScale, v) / startScale
                }
                sizeScale = targetScale
                pinchScale = 1f
                toggleRunning = false
                lastToggleEndTime = android.os.SystemClock.uptimeMillis()
            }
        }

        // Entry/exit driven by the external session flag. Entry morphs
        // fullscreen -> window; external flips to false (real-PIP restore,
        // new session, expand callback) land directly in Fullscreen.
        LaunchedEffect(isPipRequested) {
            if (isPipRequested) {
                if (!hasPositioned) {
                    val w = windowWidthPx()
                    val h = windowHeightPx()
                    centerX = boundRightPx() - w / 2f
                    // Bottom inset is max(navBar, ime), matching
                    // settleToEdge() - entering with the keyboard open
                    // must not dock the window underneath it either.
                    centerY = boundBottomPx() - h / 2f
                    hasPositioned = true
                }
                if (mode == DummyPipMode.Fullscreen) {
                    mode = DummyPipMode.AnimatingIn
                    // AM (DUMMY_PIP_ENTRY_NO_CONTROLS_REVEAL) -->
                    // No controlsShown=true here - see that flag's own
                    // comment. Entry plays the morph only.
                    // <-- AM (DUMMY_PIP_ENTRY_NO_CONTROLS_REVEAL)
                    pipRotation = 0f
                    pinchScale = 1f
                    dismissAlpha.snapTo(1f)
                    transition.animateTo(1f, tween(ENTER_EXIT_MORPH_MS, easing = FastOutSlowInEasing))
                    if (mode == DummyPipMode.AnimatingIn) mode = DummyPipMode.Pip
                }
            } else {
                dismissAlpha.snapTo(1f)
                transition.snapTo(0f)
                mode = DummyPipMode.Fullscreen
            }
        }

        LaunchedEffect(controller) {
            controller.expandRequests.collect { runExitMorph() }
        }

        // AM (DUMMY_PIP_IME_AVOIDANCE_FIX) -->
        // Keyboard avoidance, matching real PiP: an opening keyboard PUSHES
        // the window up above itself (never overlapping), and when the
        // keyboard closes again, a window we pushed up gets pulled back
        // down to its bottom resting position. pushedUpByIme is what makes
        // the pull-back selective - a window the user deliberately parked
        // higher up stays where it was. Drag/settle clamping against the
        // IME is handled separately inside clampedCenterY()/settleToEdge().
        var pushedUpByIme by remember { mutableStateOf(false) }
        LaunchedEffect(imeBottomPx, mode) {
            if (mode != DummyPipMode.Pip) return@LaunchedEffect
            val h = windowHeightPx()
            val imeOpen = imeBottomPx > navigationBarHeightPx + 1f
            if (imeOpen) {
                val imeTop = screenHeightPx - imeBottomPx
                if (centerY + h / 2f > imeTop - edgeMarginPx) {
                    pushedUpByIme = true
                    animatePosition(centerX, imeTop - edgeMarginPx - h / 2f)
                }
            } else if (pushedUpByIme) {
                pushedUpByIme = false
                animatePosition(
                    centerX,
                    boundBottomPx() - h / 2f,
                )
            }
        }
        // <-- AM (DUMMY_PIP_IME_AVOIDANCE_FIX)

        LaunchedEffect(controlsShown, controlsRevision, mode) {
            if (controlsShown && mode == DummyPipMode.Pip) {
                delay(CONTROLS_AUTO_HIDE_MS)
                controlsShown = false
            }
        }

        // Safety net: resizeActive drives the full-screen background
        // touch blocker. It's cleared at the end of every pinch gesture,
        // but if the gesture coroutine ever dies mid-pinch (the overlay
        // leaving composition cancels it), a stuck true would block every
        // background touch for the rest of the instance. dragActive (the
        // ENTRY_BOUNDS_FIX re-clamp guard) has the same lifecycle - a
        // stuck true would freeze the re-clamp instead.
        LaunchedEffect(mode) {
            if (mode != DummyPipMode.Pip) {
                resizeActive = false
                dragActive = false
            }
        }

        LaunchedEffect(flashTick) {
            if (flashTick > 0) {
                flashProgress.snapTo(0f)
                flashProgress.animateTo(1f, tween(PIP_FLASH_MS))
            }
        }

        // Fitted size (see maxWindowWidthPx): the layout itself never
        // exceeds the screen, even mid-rotation.
        val pipWidthPx = windowWidthPx()
        val pipHeightPx = windowHeightPx()

        // AM (DUMMY_PIP_ROTATION_FIT_FIX) -->
        // Device rotation with the window up. AOSP handles this two ways,
        // both mirrored here:
        //  1. PipBoundsState stores the window's position NORMALIZED
        //     (screen fractions), so a config change re-maps it onto the
        //     new display instead of clipping the old absolute px. Below:
        //     centerX/centerY are rescaled by new/old dimensions.
        //  2. PipBoundsAlgorithm.getSizeForAspectRatio() shrinks the
        //     window when it no longer fits the new bounds. Below: a
        //     stored sizeScale that now overflows the (shorter) height is
        //     committed down to the fit, and the double-tap collapse
        //     target follows so it can't re-grow past it either.
        // A stash rests legitimately OFF-screen, so the ENTRY_BOUNDS_FIX
        // clamp below ignores it - rotation instead re-docks the sliver
        // against the same edge explicitly (the old absolute X pointed
        // wherever the pre-rotation width put it).
        var prevScreenWidthPx by remember { mutableFloatStateOf(0f) }
        var prevScreenHeightPx by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(screenWidthPx, screenHeightPx) {
            val oldW = prevScreenWidthPx
            val oldH = prevScreenHeightPx
            prevScreenWidthPx = screenWidthPx
            prevScreenHeightPx = screenHeightPx
            if (oldW <= 0f || oldH <= 0f) return@LaunchedEffect
            if (!hasPositioned) return@LaunchedEffect
            // AM (DUMMY_PIP_REALPIP_SIZE_CLAMP_FIX) -->
            // Entering REAL PiP shrinks the activity's own window - the
            // "screen" sizes here collapse to the PiP window's dimensions,
            // and the fit-clamp below treated that transient tiny surface
            // like a rotation: it persisted a near-zero sizeScale AND
            // lastAdjustedScale, so a dummy pip re-entered afterward came
            // back far below its minimum size. A surface too small to
            // even host the minimum pip isn't a full-screen surface -
            // ignore it entirely (split-screen small windows too). The
            // real screen size restores on return and this re-runs.
            // Also skip Fullscreen outright: with no pip up there's
            // nothing to fit, and every sizeScale use clamps against the
            // live bounds anyway.
            // <-- AM (DUMMY_PIP_REALPIP_SIZE_CLAMP_FIX)
            if (minOf(screenWidthPx, screenHeightPx) < minWindowWidthPx()) return@LaunchedEffect
            if (mode == DummyPipMode.Fullscreen) return@LaunchedEffect
            // A gesture/animation owns the window right now - never yank
            // it. The ENTRY_BOUNDS_FIX clamp below re-runs on the same
            // size change and snaps whatever the gesture leaves behind.
            if (resizeActive || dragActive || toggleRunning || pullBackRunning) return@LaunchedEffect
            if (moveJobX?.isActive == true || moveJobY?.isActive == true) return@LaunchedEffect
            val fittedW = maxWindowWidthPx()
            if (currentBaseWidth.value * sizeScale > fittedW) {
                sizeScale = fittedW / currentBaseWidth.value
                if (lastAdjustedScale > sizeScale) lastAdjustedScale = sizeScale
            }
            when (mode) {
                DummyPipMode.Pip -> {
                    // Normalized-position remap (AOSP PipBoundsState keeps
                    // the position as screen fractions across config
                    // changes), then SETTLE to the nearest edge: real PiP
                    // never rests mid-screen, and without the re-dock the
                    // fraction remap alone leaves the window floating in
                    // the center of the new orientation.
                    centerX = centerX / oldW * screenWidthPx
                    centerY = centerY / oldH * screenHeightPx
                    settleToEdge()
                }
                DummyPipMode.AnimatingIn -> {
                    centerX = centerX / oldW * screenWidthPx
                    centerY = centerY / oldH * screenHeightPx
                }
                DummyPipMode.StashedLeft, DummyPipMode.StashedRight -> {
                    val w = windowWidthPx()
                    centerX = if (mode == DummyPipMode.StashedLeft) {
                        boundLeftPx() - w / 2f + stashPeekPx
                    } else {
                        boundRightPx() + w / 2f - stashPeekPx
                    }
                    centerY = centerY / oldH * screenHeightPx
                }
                else -> Unit
            }
        }
        // <-- AM (DUMMY_PIP_ROTATION_FIT_FIX)

        // AM (DUMMY_PIP_ENTRY_BOUNDS_FIX) -->
        // "Dummy pip enters partially below the nav bar": the entry
        // placement above runs exactly once (hasPositioned), with whatever
        // insets/aspect are known at that instant - WindowInsets can still
        // read 0 before the first dispatch, and the window's height
        // follows the ANIMATED aspect ratio, which can still be gliding
        // from the 16:9 fallback to the video's real ratio while the
        // placement happens. Either leaves the freshly entered window
        // hanging below the nav-bar line, and nothing ever re-checked.
        // Settle/stash always clamp into [min, max], so ANY out-of-band
        // resting position is a placement bug, never user intent - snap it
        // back whenever the placement inputs change. Snap, not animate:
        // animating would route through animatePosition -> cancelMove,
        // which commits an in-flight paint-time resize mid-gesture.
        // Stashed/animating-out modes are excluded on purpose: a stash
        // legitimately rests OFF-screen.
        LaunchedEffect(
            mode,
            hasPositioned,
            navigationBarHeightPx,
            navigationBarLeftPx,
            navigationBarRightPx,
            cutoutLeftPx,
            cutoutRightPx,
            cutoutTopPx,
            imeBottomPx,
            statusBarHeightPx,
            screenWidthPx,
            screenHeightPx,
            pipWidthPx,
            pipHeightPx,
        ) {
            if (!hasPositioned) return@LaunchedEffect
            if (mode != DummyPipMode.Pip && mode != DummyPipMode.AnimatingIn) return@LaunchedEffect
            // A drag/pinch/settle/toggle owns the position right now -
            // and a pinch's overshoot legitimately leaves the band
            // mid-gesture. Never fight those.
            if (resizeActive || dragActive || toggleRunning || pullBackRunning) return@LaunchedEffect
            val w = windowWidthPx()
            val h = windowHeightPx()
            val minX = boundLeftPx() + w / 2f
            val maxX = boundRightPx() - w / 2f
            val minY = boundTopPx() + h / 2f
            val maxY = boundBottomPx() - h / 2f
            if (moveJobX?.isActive == true || moveJobY?.isActive == true) {
                // AM (DUMMY_PIP_STALE_SETTLE_FIX) -->
                // Don't blindly trust an in-flight move: a settle launched
                // right at a rotation was aimed at the OLD bounds (the new
                // WindowInsets land a frame after the new size). "Valid"
                // means DOCKED, not just in-band: a settle always lands X
                // on a wall, so a target one stale-inset inside the edge
                // (landscape's side nav bar, still applied on the first
                // portrait frame) must fail this check and be re-settled
                // against the live bounds. NaN targets (IME push etc.)
                // get the same re-settle, which converges to the same
                // dock anyway.
                // <-- AM (DUMMY_PIP_STALE_SETTLE_FIX)
                val targetDocked = minX > maxX ||
                    settleTargetX <= minX + 1f || settleTargetX >= maxX - 1f
                val targetInBand = targetDocked && !settleTargetX.isNaN() && !settleTargetY.isNaN() &&
                    (minY > maxY || settleTargetY in minY..maxY)
                if (targetInBand) return@LaunchedEffect
                settleToEdge()
                return@LaunchedEffect
            }
            // A resting pip is ALWAYS docked to a side wall (every drag
            // release/settle lands there), so a mid-band X at rest is a
            // stale landing - snap it to the nearest wall, not just into
            // the band.
            val dockedX = if (centerX - minX <= maxX - centerX) minX else maxX
            val clampedX = coerceInSafe(
                if (centerX <= minX + 1f || centerX >= maxX - 1f) centerX else dockedX,
                minX,
                maxX,
            )
            val clampedY = coerceInSafe(
                centerY,
                minY,
                maxY,
            )
            if (clampedX != centerX || clampedY != centerY) {
                centerX = clampedX
                centerY = clampedY
            }
        }
        // <-- AM (DUMMY_PIP_ENTRY_BOUNDS_FIX)

        // AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX) -->
        // Feeds the TextureView's own outline radius - see the
        // LocalDummyPipCornerRadiusPx doc for why the view, not any
        // Compose clip, has to carry the corners mid-gesture. The outline
        // is ALWAYS ON in pip-like modes now (radius divided by the live
        // total layer scale, so it matches the layer clip's rect exactly
        // at rest): the old only-while-pinching gate had a clip HANDOFF
        // gap - the Compose layer clip drops the same frame pinchScale
        // leaves 1, but the view outline arrived 1-2 frames later via
        // this snapshotFlow + recomposition, and every resize started and
        // ended with clip-free frames = the reported square-corner flash.
        // With the outline permanently on, no frame ever has no clip:
        // pinch start just changes the radius, and at pinch end the layer
        // clip is already back before the outline's radius settles.
        // Moved BELOW the reveal-scale declaration: the radius formula
        // reads controlsRevealScaleAnim live.
        // snapshotFlow + a plain state write: pinchScale changes per frame
        // mid-pinch, and this keeps that per-frame traffic out of the
        // composition entirely - only MpvSurface's AndroidView update
        // block re-runs.

        // AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX) -->
        // Real PiP grows a small window when its controls are revealed so
        // they stay reachable. Expand-TO, not expand-BY (corrected from
        // the pre-rebuild implementation's capped multiplier, which grew
        // every small window by the same factor regardless of how small
        // it actually was): any window below CONTROLS_REVEAL_TARGET_SCALE
        // visually becomes exactly that size while controls are shown -
        // the paint-time scale is target/current, so the RESULT is the
        // same fixed size every time. A window already at/above the
        // target doesn't grow at all.
        //
        // Purely paint-time on the video box (graphicsLayer scale, never
        // a real .size() change), specifically because this fires on
        // every tap, not just during a deliberate resize. sizeScale
        // itself never changes here. The controls overlay does NOT use
        // this scale - it lays out at the grown bounds directly so the
        // icons stay the same pixel size (DUMMY_PIP_CONTROLS_SCALE_FIX).
        //
        // Growth is anchored at whichever screen edge the window's center
        // is closest to, so it grows back toward the screen's center
        // instead of pushing half-off the edge it's docked against
        // (confirmed real bug in the original: centered growth from a
        // corner-docked window overflows the screen).
        val revealScaleTarget = if (mode == DummyPipMode.Pip && controlsShown && sizeScale < CONTROLS_REVEAL_TARGET_SCALE) {
            CONTROLS_REVEAL_TARGET_SCALE / sizeScale
        } else {
            1f
        }
        // Growing animates; SHRINKING is instant (snapTo). An animated
        // shrink keeps running while a pinch starts (the pinch hides the
        // controls), visibly fighting the gesture - part of the reported
        // pinch "lag".
        val controlsRevealScaleAnim = remember { Animatable(1f) }
        LaunchedEffect(revealScaleTarget) {
            if (revealScaleTarget > controlsRevealScaleAnim.value) {
                controlsRevealScaleAnim.animateTo(revealScaleTarget, tween(CONTROLS_REVEAL_GROW_MS))
            } else {
                controlsRevealScaleAnim.snapTo(revealScaleTarget)
            }
        }
        val controlsRevealScale = controlsRevealScaleAnim.value
        // <-- AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX)

        LaunchedEffect(Unit) {
            snapshotFlow {
                val pipLike = mode == DummyPipMode.Pip ||
                    mode == DummyPipMode.StashedLeft ||
                    mode == DummyPipMode.StashedRight
                if (pipLike) {
                    cornerRadiusPx /
                        (pinchScale * controlsRevealScaleAnim.value).coerceAtLeast(0.01f)
                } else {
                    0f
                }
            }.collect { pipViewCornerRadiusPx.floatValue = it }
        }
        // <-- AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX)

        // AM (DUMMY_PIP_CONTROLS_AFTER_GROW_FIX) -->
        // Real PiP's controls don't slide around mid-growth: they appear
        // only once the reveal-grow has FULLY expanded the window. Gate
        // both the visuals and the hit-testing on the animation having
        // settled at its target.
        val controlsSettled = abs(controlsRevealScale - revealScaleTarget) < 0.01f
        // <-- AM (DUMMY_PIP_CONTROLS_AFTER_GROW_FIX)

        // ---- Background touch blocker (only while pinch-resizing) ----
        // Real PiP swallows every touch outside the window during a
        // resize - without this, a finger that lands off the window mid-
        // pinch drives the player gestures behind it (seek/brightness).
        // Declared BEFORE the video box / overlay so it sits BELOW them:
        // the window's own touches are consumed by its overlay as usual,
        // everything else dies here.
        if (resizeActive) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
            )
        }

        // ---- Video surface (same composable, same MpvSurface, both modes) ----
        Box(
            modifier = when (mode) {
                DummyPipMode.Fullscreen -> Modifier.fillMaxSize()
                DummyPipMode.AnimatingIn, DummyPipMode.AnimatingOut -> Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = transition.value
                        // Entry/exit morph on the FULLSCREEN layout: the
                        // TextureView keeps its fullscreen surface for the
                        // whole animation and only relayouts to the small
                        // window once, at t=1 (the flip to Pip swaps to an
                        // identical visual rect). The old small-layout
                        // morph resized the surface AT t=0 and the
                        // reallocation flashed through the entry.
                        //
                        // NOTE the per-axis scales DIFFER (screen and
                        // window have different aspect ratios) - the
                        // video would be squished mid-morph if the
                        // content weren't counter-scaled (see the inner
                        // content layer below).
                        scaleX = lerp(1f, pipWidthPx / screenWidthPx, t)
                        scaleY = lerp(1f, pipHeightPx / screenHeightPx, t)
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = lerp(0f, centerX - pipWidthPx / 2f, t)
                        translationY = lerp(0f, centerY - pipHeightPx / 2f, t)
                        clip = t > 0f
                        shape = RoundedCornerShape(cornerRadiusPx * t)
                        shadowElevation = elevationPx * t
                    }
                else ->
                Modifier
                    .size(
                        width = with(density) { pipWidthPx.toDp() },
                        height = with(density) { pipHeightPx.toDp() },
                    )
                    .offset {
                        IntOffset(
                            (centerX - pipWidthPx / 2f).roundToInt(),
                            (centerY - pipHeightPx / 2f).roundToInt(),
                        )
                    }
                    // ONE layer for everything: morph + reveal-grow +
                    // transient pinch zoom + Samsung tilt. A single layer
                    // is load-bearing, not a simplification: with two
                    // nested layers the embedded TextureView (AndroidView
                    // interop) got its clip applied in a space that cut
                    // off tilted corners and hid the pinch overshoot past
                    // the screen edge. In ONE layer the clip is in
                    // layer-local space, so the rounded-rect window
                    // rotates and scales as a whole - corners stay
                    // visible, overshoot renders past the screen edge.
                    //
                    // Origin juggling: the morph needs (0,0) while t<1;
                    // pinch/tilt needs center; the reveal-grow needs the
                    // nearest screen edge. These never overlap - pinch
                    // hides the controls (reveal scale snaps to 1), so
                    // whenever pinch/tilt is non-identity the reveal
                    // origin is irrelevant, and whenever the reveal-grow
                    // is animating, pinch/tilt is identity.
                    .graphicsLayer {
                        val t = transition.value
                        val pinching = pinchScale != 1f || pipRotation != 0f
                        alpha = dismissAlpha.value
                        scaleX = lerp(screenWidthPx / pipWidthPx, 1f, t) * controlsRevealScale * pinchScale
                        scaleY = lerp(screenHeightPx / pipHeightPx, 1f, t) * controlsRevealScale * pinchScale
                        rotationZ = pipRotation
                        transformOrigin = when {
                            t < 1f -> TransformOrigin(0f, 0f)
                            pinching -> TransformOrigin(0.5f, 0.5f)
                            else -> TransformOrigin(
                                if (centerX > screenWidthPx / 2f) 1f else 0f,
                                if (centerY > screenHeightPx / 2f) 1f else 0f,
                            )
                        }
                        translationX = lerp(-(centerX - pipWidthPx / 2f), 0f, t)
                        translationY = lerp(-(centerY - pipHeightPx / 2f), 0f, t)
                        // The layer's OUTLINE clip still comes OFF while
                        // pinching/tilted: with an embedded TextureView
                        // (AndroidView interop) the layer clip is applied
                        // to the view in a space that does NOT scale with
                        // the layer - it invisibly cropped the pinch
                        // overshoot at the layout bounds, so the window
                        // could never grow past the screen edge. The
                        // rounded corners no longer disappear with it,
                        // though - they're carried by an outline on the
                        // TextureView itself during the gesture (see
                        // DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX below and in
                        // MpvSurface.kt). An offscreen DstIn content mask
                        // was tried here first and did NOT work: a
                        // TextureView's content is composited as its own
                        // hardware layer and never lands in the Compose
                        // layer's offscreen buffer, so the mask simply
                        // never touches the video.
                        clip = t > 0f && !pinching
                        shape = RoundedCornerShape(cornerRadiusPx * t)
                        // The shadow is dropped while pinching/tilted:
                        // re-rendering the elevation shadow on a
                        // per-frame transforming TextureView layer was
                        // the visible tilt/pinch lag.
                        shadowElevation = if (t > 0f && !pinching) elevationPx * t else 0f
                    }
            },
        ) {
            if (mode == DummyPipMode.AnimatingIn || mode == DummyPipMode.AnimatingOut) {
                // AM (DUMMY_PIP_MORPH_LETTERBOX_FIX) -->
                // Aspect-preserving cover-scale, corrected. The old version
                // treated the WHOLE fullscreen surface as the content
                // (u = max(sx, sy)) - but mpv letterboxes the video inside
                // that surface, so mid-morph the transitioning window
                // (converging to the video's aspect) was mostly filled by
                // the surface's black bars while the actual video band
                // shrank ahead of the window: the visible "video gets
                // compressed/stretched mid-morph, snaps at settle" flicker.
                // Real PiP center-crops - the video FILLS the window at
                // every frame of the morph.
                //
                // This does that by morphing the EFFECTIVE content rect
                // from the full surface (t=0: exact identity, the
                // letterboxed fullscreen as-is) to the centered video band
                // (t=1: exact cover - pipW/pipH equals the video's aspect,
                // so cover == fit == the real Pip layout, and the mode flip
                // lands with zero snap), scaling the content so that rect
                // always covers the current window. Center-anchored like
                // before: band center and window center coincide through
                // the container's own transform, so scale alone suffices.
                // <-- AM (DUMMY_PIP_MORPH_LETTERBOX_FIX)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val t = transition.value
                            val sx = lerp(1f, pipWidthPx / screenWidthPx, t)
                            val sy = lerp(1f, pipHeightPx / screenHeightPx, t)
                            val videoAspect = pipWidthPx / pipHeightPx
                            val screenAspect = screenWidthPx / screenHeightPx
                            // The rect mpv actually draws the video into
                            // inside the fullscreen surface (fit = centered,
                            // letterboxed).
                            val bandW: Float
                            val bandH: Float
                            if (videoAspect >= screenAspect) {
                                bandW = screenWidthPx
                                bandH = screenWidthPx / videoAspect
                            } else {
                                bandW = screenHeightPx * videoAspect
                                bandH = screenHeightPx
                            }
                            val effW = lerp(screenWidthPx, bandW, t)
                            val effH = lerp(screenHeightPx, bandH, t)
                            val u = maxOf(sx * screenWidthPx / effW, sy * screenHeightPx / effH)
                            scaleX = u / sx
                            scaleY = u / sy
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        },
                ) {
                    content()
                }
            } else {
                // AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX) -->
                // Provides the live corner radius to the MpvSurface deep
                // inside content() - provider wraps the CALL, so the
                // player subtree's composition structure is unchanged and
                // the TextureView is never re-created.
                // <-- AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX)
                CompositionLocalProvider(LocalDummyPipCornerRadiusPx provides pipViewCornerRadiusPx) {
                    content()
                }
            }
        }

        // ---- Gesture + controls overlay (interactive only in Pip mode) ----
        // AM (DUMMY_PIP_CONTROLS_SCALE_FIX) -->
        // The reveal grow must enlarge the WINDOW but NOT the controls -
        // real PiP's icons stay the same pixel size when the window grows
        // (confirmed on-device: scaling the overlay's graphicsLayer made
        // the icons balloon). So this overlay is laid out at the GROWN
        // bounds directly (real .size()/.offset(), no graphicsLayer
        // scale): the video box keeps its paint-time grow, and this box
        // simply tracks the same grown rectangle with unscaled children.
        // As a bonus the gesture area now covers the grown bounds too, so
        // taps on icons in the grown-out region hit-test correctly, in
        // this box's own coordinate space, with no conversion.
        //
        // Grown-rect math: the video box scales by controlsRevealScale
        // around the edge-anchored transformOrigin (ox, oy), so its
        // visible top-left moves by (ox*w*(1-s), oy*h*(1-s)) and its
        // visible size is (w*s, h*s).
        if (mode == DummyPipMode.Pip) {
            val grownWidthPx = pipWidthPx * controlsRevealScale
            val grownHeightPx = pipHeightPx * controlsRevealScale
            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { grownWidthPx.toDp() },
                        height = with(density) { grownHeightPx.toDp() },
                    )
                    .offset {
                        val s = controlsRevealScale
                        val ox = if (centerX > screenWidthPx / 2f) 1f else 0f
                        val oy = if (centerY > screenHeightPx / 2f) 1f else 0f
                        IntOffset(
                            (centerX - pipWidthPx / 2f + ox * pipWidthPx * (1f - s)).roundToInt(),
                            (centerY - pipHeightPx / 2f + oy * pipHeightPx * (1f - s)).roundToInt(),
                        )
                    }
                    // AM (DUMMY_PIP_PINCH_UNTRANSFORMED_INPUT_FIX) -->
                    // pointerInput comes BEFORE the graphicsLayer so the
                    // gesture area receives positions in the space
                    // immediately outside the pinch/tilt layer; the
                    // gesture loop converts them through the overlay's
                    // own LayoutCoordinates.localToRoot (captured by the
                    // onGloballyPositioned immediately above, i.e. the
                    // SAME space), so the math below runs in true screen
                    // coordinates regardless of how the layer affects
                    // raw event positions.
                    // <-- AM (DUMMY_PIP_PINCH_UNTRANSFORMED_INPUT_FIX)
                    .onGloballyPositioned { gestureOverlayCoords = it }
                    .pointerInput(Unit) {
                        // Local position -> screen (root) position, using
                        // the exact same transform hit-testing used for
                        // this event.
                        fun Offset.toScreenCoords(): Offset =
                            gestureOverlayCoords
                                ?.takeIf { it.isAttached }
                                ?.localToRoot(this)
                                ?: this
                        val touchSlop = viewConfiguration.touchSlop
                        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                        val iconPx = with(density) { PIP_ICON_SIZE_DP.dp.toPx() }
                        val playIconPx = with(density) { PIP_PLAY_ICON_SIZE_DP.dp.toPx() }
                        val insetPx = with(density) { PIP_CONTROLS_INSET.toPx() }
                        val centerSpacingPx = with(density) { PIP_CENTER_ROW_SPACING.toPx() }
                        val topSpacingPx = with(density) { PIP_TOP_ROW_SPACING.toPx() }
                        val bottomBandPx = with(density) { PIP_BOTTOM_HIT_BAND.toPx() }
                        val hitSlopPx = with(density) { PIP_HIT_SLOP.toPx() }

                        var lastTapTime = 0L
                        // When the last double-tap fired - a tap landing
                        // within DOUBLE_TAP_EXTRA_TAP_SUPPRESS_MS of it is
                        // almost certainly the user's finger bouncing off
                        // the same double-tap motion, not a deliberate
                        // third tap. Absorbing it keeps the expansion from
                        // also popping the controls up (real PiP doesn't).
                        var lastDoubleTapTime = 0L
                        var pendingTapJob: Job? = null
                        // Double-tap needs POSITION proximity too, not
                        // just timing: two quick taps on DIFFERENT
                        // buttons used to register as a double-tap and
                        // fire the resize instead of either button
                        // ("controls sometimes don't detect the click").
                        var lastTapPosition = Offset.Unspecified
                        val doubleTapSlopPx = with(density) { 48.dp.toPx() }

                        // Samsung-style layout (matched against real PiP
                        // screenshots): headphones in the TOP-LEFT corner,
                        // expand just left of close in the TOP-RIGHT
                        // corner, prev/play/next centered along the
                        // BOTTOM edge. w/h are this overlay's own (grown)
                        // bounds.
                        //
                        // Touch zones match real PiP's pressed-state
                        // ripple circles: the TOP band stays small (icon
                        // + inset), while the BOTTOM band is a tall
                        // PIP_BOTTOM_HIT_BAND strip whose horizontal zones
                        // are split at the MIDPOINTS between the buttons -
                        // every tap in the strip hits a button, exactly
                        // like the big circles in the screenshots.
                        fun hitRegion(tap: Offset, w: Float, h: Float): PipHitRegion {
                            if (tap.y <= iconPx + insetPx + hitSlopPx) {
                                if (tap.x <= iconPx + insetPx + hitSlopPx) return PipHitRegion.Headphones
                                val closeLeft = w - insetPx - iconPx
                                if (tap.x >= closeLeft - topSpacingPx - hitSlopPx) return PipHitRegion.Close
                                if (tap.x >= closeLeft - topSpacingPx - iconPx - topSpacingPx - hitSlopPx) {
                                    return PipHitRegion.Expand
                                }
                            }
                            if (tap.y >= h - bottomBandPx) {
                                val headphonesCx = insetPx + iconPx / 2f
                                val playCx = w / 2f
                                val prevCx = playCx - (iconPx + centerSpacingPx)
                                val nextCx = playCx + (iconPx + centerSpacingPx)
                                return when {
                                    tap.x < (headphonesCx + prevCx) / 2f -> PipHitRegion.Headphones
                                    tap.x < (prevCx + playCx) / 2f -> PipHitRegion.Previous
                                    tap.x < (playCx + nextCx) / 2f -> PipHitRegion.PlayPause
                                    else -> PipHitRegion.Next
                                }
                            }
                            return PipHitRegion.None
                        }

                        awaitEachGesture {
                            // Mid-dismiss-fade: the window is on its way
                            // out - a touch mustn't grab it (or flash its
                            // controls) for the last ~150ms.
                            if (dismissAlpha.value != 1f) return@awaitEachGesture
                            // The down event itself is captured here - the
                            // loop below only sees LATER events, so the tap
                            // position and the velocity clock must start from
                            // this one, not from inside the loop.
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // NOTE: no cancelMove() here. Real PiP does
                            // NOT stop an in-flight expansion/settle just
                            // because a finger lands - a tap during the
                            // double-tap expansion lets it finish (the
                            // pre-v14 behavior cancelled everything on
                            // touch-down, freezing the window mid-growth,
                            // sometimes half off-screen). In-flight moves
                            // are cancelled when a gesture actually STARTS
                            // (slop exceeded / second finger) instead.
                            pendingTapJob?.cancel()

                            var isResizing = false
                            // AOSP-model pinch state (see
                            // PINCH_OVERRESIZE_DAMP): the finger distance
                            // and the window's visual scale at the moment
                            // the second finger landed - the per-event
                            // scale is computed ABSOLUTELY from these
                            // (dist/downDist), not accumulated per-event.
                            var pinchStartDistScreen = 0f
                            var pinchStartVisualScale = 1f
                            // AM (DUMMY_PIP_PINCH_REANCHOR_FIX) -->
                            // Finger count from the PREVIOUS event. AOSP
                            // PipPinchResizingAlgorithm re-anchors its
                            // down-distance whenever the pointer count
                            // changes; without that, lifting and
                            // re-touching a finger mid-pinch kept the
                            // STALE anchor - the window jumped or felt
                            // stuck until both fingers lifted.
                            // <-- AM (DUMMY_PIP_PINCH_REANCHOR_FIX)
                            var lastPressedCount = 1
                            // Previous event's pinch centroid / drag
                            // position, both in SCREEN space (via
                            // toScreenCoords) - the pan is the screen
                            // centroid delta; skipped on finger-count
                            // changes, where positions jump.
                            var prevPinchCentroid = Offset.Unspecified
                            var prevDragScreenPos = down.position.toScreenCoords()
                            var hasExceededSlop = false
                            var accumulatedMovement = 0f
                            val downPosition: Offset = down.position
                            var lastMoveTime = down.uptimeMillis
                            var velocityX = 0f
                            var velocityY = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                // Finger-count change mid-gesture: the
                                // absolute pinch anchor is re-seeded from
                                // the CURRENT fingers below (AOSP
                                // re-anchors on every pointer-count
                                // change), so a lifted/re-touched finger
                                // continues 1:1 instead of jumping.
                                val fingerCountChanged = pressed.size != lastPressedCount
                                lastPressedCount = pressed.size

                                if (pressed.size >= 2) {
                                    // Pinch-to-resize (zoom dead-zone +
                                    // centroid anchor compensation + pan),
                                    // accumulated into the PAINT-TIME
                                    // pinchScale - never sizeScale, so no
                                    // relayout happens mid-gesture (the
                                    // old sizeScale writes relaid out the
                                    // video surface every event = the
                                    // reported frame loss). Also Samsung
                                    // tilt zooming: two-finger rotation
                                    // tilts the window, capped.
                                    if (!isResizing) {
                                        // AM (DUMMY_PIP_PINCH_FROM_GROWN_FIX) -->
                                        // If the controls reveal-grow had
                                        // the window visually expanded
                                        // (pip smaller than the reveal
                                        // threshold), the pinch must start
                                        // from that GROWN size - hiding the
                                        // controls below snaps the reveal
                                        // scale back to 1, which used to
                                        // visibly shrink the window to its
                                        // small layout size the instant the
                                        // second finger landed. Fold the
                                        // live reveal scale into pinchScale
                                        // instead: reveal*pinch is identical
                                        // before and after the snap, and the
                                        // release commit (sizeScale *
                                        // pinchScale) naturally settles at
                                        // the grown-from size.
                                        // <-- AM (DUMMY_PIP_PINCH_FROM_GROWN_FIX)
                                        val revealNow = controlsRevealScaleAnim.value
                                        if (revealNow != 1f) {
                                            // The reveal-grow anchors at
                                            // the nearest screen corner
                                            // while pinch anchors at the
                                            // window center - shift the
                                            // center by the same offset so
                                            // the grown rect doesn't move
                                            // when the anchor swaps.
                                            val w0 = windowWidthPx()
                                            val h0 = windowHeightPx()
                                            val ox = if (centerX > currentScreenWidth.value / 2f) 1f else 0f
                                            val oy = if (centerY > currentScreenHeight.value / 2f) 1f else 0f
                                            centerX += (0.5f - ox) * w0 * (revealNow - 1f)
                                            centerY += (0.5f - oy) * h0 * (revealNow - 1f)
                                            pinchScale = revealNow
                                        }
                                        // Flipping this snaps the reveal
                                        // anim back to 1 via its
                                        // LaunchedEffect (shrink is
                                        // instant) in the same frame, so
                                        // reveal*pinch never double-scales.
                                        controlsShown = false
                                        // The pinch takes over from any
                                        // in-flight move / tilt level-out.
                                        cancelMove()
                                        rotationJob?.cancel()
                                        rotationJob = null
                                        // Block background touches for
                                        // the rest of the resize.
                                        resizeActive = true
                                    }
                                    // AM (DUMMY_PIP_AOSP_PINCH_DAMP_FIX) -->
                                    // Anchor the ABSOLUTE pinch model:
                                    // finger distance and the window's
                                    // visual scale at this moment. Every
                                    // later event computes desired scale as
                                    // startScale * dist/startDist, so
                                    // the tracking is exactly 1:1 in
                                    // range like real PiP - no
                                    // per-event accumulation, no fixed
                                    // damping eating the first part of
                                    // every gesture.
                                    //
                                    // Re-seeded not just at pinch start
                                    // but on EVERY finger-count change
                                    // (AOSP re-anchors the same way): a
                                    // lifted and re-touched finger used
                                    // to keep the stale start-distance,
                                    // jumping the window the moment it
                                    // landed again.
                                    // <-- AM (DUMMY_PIP_AOSP_PINCH_DAMP_FIX)
                                    val reseededPinch = !isResizing || fingerCountChanged
                                    if (reseededPinch) {
                                        // Screen-space anchor, re-seeded
                                        // at pinch start and on every
                                        // finger-count change (AOSP
                                        // re-anchors the same way).
                                        pinchStartDistScreen =
                                            (pressed[0].position.toScreenCoords() -
                                                pressed[1].position.toScreenCoords())
                                                .getDistance()
                                        pinchStartVisualScale = sizeScale * pinchScale
                                    }
                                    isResizing = true
                                    hasExceededSlop = true

                                    // AM (DUMMY_PIP_PINCH_SCREEN_SPACE_FIX) -->
                                    // All math in true SCREEN space: every
                                    // finger position is mapped through the
                                    // overlay's own LayoutCoordinates
                                    // (localToRoot - the exact transform
                                    // hit-testing used, whatever the live
                                    // pinch layer does to raw positions),
                                    // and the center is converted through
                                    // the root container's coordinates.
                                    //
                                    // One formula per event covers pan +
                                    // scale + tilt - keep the window-content
                                    // point under the centroid glued to the
                                    // centroid:
                                    //   C' = O - R(dTilt) * (O_prev - C) * (S'/S)
                                    // Pure pan (S'=S, dTilt=0) reduces to
                                    // C' = C + (O - O_prev); pure scale to a
                                    // focal zoom about the centroid. Fully
                                    // absolute per event - no deltas, no
                                    // accumulation, a stationary finger can
                                    // never push the window.
                                    // <-- AM (DUMMY_PIP_PINCH_SCREEN_SPACE_FIX)
                                    val rootOffset = pipRootCoords
                                        ?.takeIf { it.isAttached }
                                        ?.localToRoot(Offset.Zero)
                                        ?: Offset.Zero
                                    val s0 = pressed[0].position.toScreenCoords()
                                    val s1 = pressed[1].position.toScreenCoords()
                                    // Manual centroid of the two pinch
                                    // fingers - calculateCentroid() can
                                    // include non-pressed changes.
                                    val centroid = Offset(
                                        (s0.x + s1.x) / 2f,
                                        (s0.y + s1.y) / 2f,
                                    )
                                    val rotation = event.calculateRotation()

                                    if (rotation != 0f) {
                                        pipRotation = (pipRotation + rotation)
                                            .coerceIn(-MAX_PIP_TILT_DEG, MAX_PIP_TILT_DEG)
                                    }

                                    // AM (DUMMY_PIP_AOSP_PINCH_DAMP_FIX) -->
                                    // AOSP PipPinchResizingAlgorithm model:
                                    // the desired scale is recomputed
                                    // ABSOLUTELY every event as
                                    // startScale * (dist / downDist) -
                                    // exactly 1:1 with the fingers inside
                                    // [min, max] (real PiP applies NO
                                    // damping there; the old fixed 0.4
                                    // per-event damping is what made the
                                    // resize feel laggy). Only the
                                    // overshoot PAST a limit is damped,
                                    // by PINCH_OVERRESIZE_DAMP (AOSP
                                    // OVERRESIZE_DAMP_FACTOR = 0.25), so
                                    // pushing beyond the bounds gets
                                    // progressively stiffer - the
                                    // "variable dampener" feel. The
                                    // release commit below still clamps
                                    // to [min, max] with the paint-time
                                    // pull-back, unchanged.
                                    // <-- AM (DUMMY_PIP_AOSP_PINCH_DAMP_FIX)
                                    val maxScaleAbs = minOf(
                                        MAX_SIZE_SCALE,
                                        maxWindowWidthPx() / currentBaseWidth.value,
                                    )
                                    val minScaleAbs = minWindowWidthPx() / currentBaseWidth.value
                                    val distScreen = (s0 - s1).getDistance()
                                    val rawDesired =
                                        pinchStartVisualScale *
                                            (distScreen / pinchStartDistScreen.coerceAtLeast(1f))
                                    val clampedDesired = when {
                                        rawDesired > maxScaleAbs ->
                                            maxScaleAbs + (rawDesired - maxScaleAbs) * PINCH_OVERRESIZE_DAMP
                                        rawDesired < minScaleAbs ->
                                            minScaleAbs - (minScaleAbs - rawDesired) * PINCH_OVERRESIZE_DAMP
                                        else -> rawDesired
                                    }
                                    val newPinch = coerceInSafe(
                                        clampedDesired / sizeScale,
                                        // Allow the damped overshoot
                                        // below min / above max to be
                                        // VISIBLE mid-gesture; the
                                        // release commit pulls it back.
                                        (minWindowWidthPx() / windowWidthPx()) * 0.5f,
                                        maxScaleAbs * PINCH_MAX_OVERSHOOT / sizeScale,
                                    )

                                    // The unified pan/scale/tilt anchor.
                                    // Skipped on the event that re-seeded
                                    // the gesture anchor - the centroid
                                    // jumps discontinuously on finger-count
                                    // changes.
                                    if (!reseededPinch && prevPinchCentroid != Offset.Unspecified) {
                                        val centerScreen = Offset(centerX, centerY) + rootOffset
                                        var v = prevPinchCentroid - centerScreen
                                        if (rotation != 0f) {
                                            v = v.rotatedByDegrees(rotation)
                                        }
                                        val ratio = newPinch / pinchScale
                                        centerX = centroid.x - v.x * ratio - rootOffset.x
                                        centerY = centroid.y - v.y * ratio - rootOffset.y
                                    }
                                    prevPinchCentroid = centroid
                                    pinchScale = newPinch
                                    pressed.forEach { it.consume() }
                                } else if (pressed.size == 1) {
                                    // Also during a resize: lifting to one
                                    // finger mid-pinch becomes a DRAG
                                    // (real PiP does this - the remaining
                                    // finger keeps control 1:1). The old
                                    // !isResizing condition froze the
                                    // window until BOTH fingers lifted -
                                    // the "stuck" tracking feel.
                                    val change = pressed[0]
                                    // Screen-space delta via the same
                                    // localToRoot mapping as the pinch -
                                    // correct even mid-resize while the
                                    // window is zoomed/tilted (a raw
                                    // positionChange() would be in
                                    // whatever space the layer leaves
                                    // events in).
                                    val fingerScreen = change.position.toScreenCoords()
                                    val delta = if (fingerCountChanged) {
                                        // Just dropped to one finger - no
                                        // meaningful previous position.
                                        Offset.Zero
                                    } else {
                                        fingerScreen - prevDragScreenPos
                                    }
                                    prevDragScreenPos = fingerScreen
                                    if (!hasExceededSlop) {
                                        accumulatedMovement += hypot(delta.x, delta.y)
                                        if (accumulatedMovement > touchSlop) {
                                            hasExceededSlop = true
                                            dragActive = true
                                            // The drag takes over from any
                                            // in-flight move / tilt
                                            // level-out (deferred from
                                            // touch-down - see there).
                                            cancelMove()
                                            rotationJob?.cancel()
                                            rotationJob = null
                                            // Grabbed mid-fling: inherit
                                            // part of the in-flight
                                            // move's momentum so a pull
                                            // sideways DEFLECTS the flight
                                            // instead of stopping it dead
                                            // (real PiP behavior). The
                                            // smoothing below takes over
                                            // from this seed.
                                            velocityX = flightVelocityX * 0.5f
                                            velocityY = flightVelocityY * 0.5f
                                            flightVelocityX = 0f
                                            flightVelocityY = 0f
                                        }
                                    }
                                    if (hasExceededSlop) {
                                        centerX += delta.x
                                        centerY += delta.y
                                        val dt = (change.uptimeMillis - lastMoveTime).coerceAtLeast(1)
                                        // Smoothed instantaneous velocity,
                                        // px/second - feeds the release fling.
                                        velocityX = (velocityX * 0.6f + delta.x / dt * 1000f * 0.4f)
                                            .coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
                                        velocityY = (velocityY * 0.6f + delta.y / dt * 1000f * 0.4f)
                                            .coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
                                        lastMoveTime = change.uptimeMillis
                                    }
                                    change.consume()
                                }

                                if (event.changes.none { it.pressed }) break
                            }

                            // Gesture over - lift the background touch
                            // block before the release handling runs.
                            resizeActive = false
                            dragActive = false

                            // The size the window will REST at after a
                            // pinch release (post pull-back) - the settle
                            // below must dock for THIS size, not the
                            // overshot mid-gesture one.
                            var releaseRestWidthPx = 0f
                            var releaseRestHeightPx = 0f
                            var releaseVisualScale = 0f
                            var releasePullBackTarget = 0f
                            if (isResizing) {
                                // Commit the transient paint-time pinch
                                // into the real layout size EXACTLY ONCE
                                // (same visual size, so no jump), then
                                // pull back to the limit if the pinch
                                // overshot it mid-gesture.
                                val maxScale = minOf(
                                    MAX_SIZE_SCALE,
                                    maxWindowWidthPx() / currentBaseWidth.value,
                                )
                                val visualScale = sizeScale * pinchScale
                                val pullBackTarget = coerceInSafe(
                                    visualScale,
                                    minWindowWidthPx() / currentBaseWidth.value,
                                    maxScale,
                                )
                                releaseRestWidthPx = currentBaseWidth.value * pullBackTarget
                                releaseRestHeightPx = releaseRestWidthPx / currentAspect.value
                                releaseVisualScale = visualScale
                                releasePullBackTarget = pullBackTarget
                                if (pullBackTarget == visualScale) {
                                    // No overshoot - commit immediately
                                    // (one relayout, no animation).
                                    sizeScale = visualScale
                                    pinchScale = 1f
                                }
                                // ELSE: pinchScale KEEPS the overshot
                                // size paint-time and the pull-back
                                // (launched after the settle below)
                                // animates pinchScale, NOT sizeScale.
                                // Animating sizeScale resized the real
                                // layout - and with it the TextureView
                                // surface - on every frame of the return,
                                // which was the glitchy flashing. A
                                // paint-time return is the expansion
                                // played in reverse, exactly like real
                                // PiP; the layout commits once, at the
                                // same visual size, when it completes.
                                //
                                // NOTE: the pull-back is launched AFTER
                                // the settle below - settleToEdge routes
                                // through animatePosition -> cancelMove,
                                // which would kill a sizeJob launched
                                // here on its first frame.
                                // A pinch is a USER size adjustment: it
                                // becomes the size double-tap collapses
                                // back to, and clears the expanded flag.
                                lastAdjustedScale = pullBackTarget
                                toggledExpanded = false
                                // Real PiP never keeps a tilt: the window
                                // levels back to 0 degrees on release.
                                if (pipRotation != 0f) {
                                    rotationJob = scope.launch {
                                        animate(
                                            pipRotation,
                                            0f,
                                            0f,
                                            spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { v, _ -> pipRotation = v }
                                    }
                                }
                            }

                            val w = windowWidthPx()
                            val h = windowHeightPx()
                            // This overlay's own (grown) bounds, captured
                            // here in gesture scope - `size` doesn't exist
                            // inside the deferred tap job below.
                            val overlayW = size.width.toFloat()
                            val overlayH = size.height.toFloat()

                            when {
                                isResizing -> settleToEdge(
                                    widthPx = releaseRestWidthPx,
                                    heightPx = releaseRestHeightPx,
                                )
                                !hasExceededSlop -> {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    val nearLastTap = lastTapPosition != Offset.Unspecified &&
                                        (downPosition - lastTapPosition).getDistance() <= doubleTapSlopPx
                                    if (now - lastTapTime < doubleTapTimeout && nearLastTap) {
                                        pendingTapJob?.cancel()
                                        lastTapTime = 0L
                                        lastTapPosition = Offset.Unspecified
                                        lastDoubleTapTime = now
                                        // Real PiP doesn't keep its buttons
                                        // up through the resize: the
                                        // double-tap hides the controls
                                        // outright (snap, not fade - the
                                        // reveal-shrink is instant anyway)
                                        // and they stay hidden afterward
                                        // until a fresh tap reveals them.
                                        controlsShown = false
                                        startToggleSize()
                                    } else if (now - lastDoubleTapTime <= DOUBLE_TAP_EXTRA_TAP_SUPPRESS_MS) {
                                        // Finger-bounce right after a
                                        // double-tap (see lastDoubleTapTime):
                                        // swallow it whole - no controls
                                        // reveal, and it must NOT seed a new
                                        // lastTapTime/lastTapPosition pair
                                        // either, or the bounce itself could
                                        // pair with the NEXT tap into a
                                        // phantom double-tap.
                                    } else if (toggleRunning || now - lastToggleEndTime <= POST_RESIZE_TAP_SUPPRESS_MS) {
                                        // Mid-resize, or just after one:
                                        // same whole-swallow treatment as
                                        // the bounce above - the lingering
                                        // finger must neither reveal
                                        // controls nor seed a tap pair.
                                    } else {
                                        lastTapTime = now
                                        lastTapPosition = downPosition
                                        pendingTapJob = scope.launch {
                                            delay(doubleTapTimeout)
                                            // A tap landing mid-toggle does
                                            // nothing: the hit regions are
                                            // computed against the pre-
                                            // expansion layout size (the
                                            // visual size is paint-time
                                            // now), so buttons would
                                            // mis-fire, and real PiP ignores
                                            // taps during the resize anyway.
                                            if (toggleRunning) {
                                                return@launch
                                            }
                                            // Live check, not a value captured
                                            // at touch-down: if the controls
                                            // auto-hid during the double-tap
                                            // window, this tap's only job is
                                            // to reveal them - never fire a
                                            // button that isn't visible.
                                            if (!controlsShown) {
                                                controlsShown = true
                                                controlsRevision++
                                            } else {
                                                // Live settled check (see
                                                // DUMMY_PIP_CONTROLS_AFTER_GROW_FIX):
                                                // a tap landing mid-grow
                                                // restarts the timer but
                                                // never fires a button that
                                                // isn't on-screen yet.
                                                //
                                                // AM (DUMMY_PIP_GROWN_CONTROLS_DEAD_FIX):
                                                // this MUST read the Animatable
                                                // directly - the composable-scope
                                                // `controlsRevealScale` val is
                                                // captured BY VALUE by this
                                                // pointerInput(Unit) lambda at
                                                // first composition (always 1f),
                                                // so for any pip below the
                                                // reveal threshold the check
                                                // compared stale-1f against a
                                                // >1 target and NEVER settled:
                                                // every button tap mapped to
                                                // None once the reveal-grow
                                                // had expanded the window.
                                                val settledNow = abs(
                                                    controlsRevealScaleAnim.value -
                                                        (
                                                            if (sizeScale < CONTROLS_REVEAL_TARGET_SCALE) {
                                                                CONTROLS_REVEAL_TARGET_SCALE / sizeScale
                                                            } else {
                                                                1f
                                                            }
                                                            ),
                                                ) < 0.01f
                                                val region = if (settledNow) {
                                                    hitRegion(downPosition, overlayW, overlayH)
                                                } else {
                                                    PipHitRegion.None
                                                }
                                                if (region != PipHitRegion.None) {
                                                    flashRegion = region
                                                    flashTick++
                                                }
                                                when (region) {
                                                    PipHitRegion.Expand -> runExitMorph()
                                                    PipHitRegion.Close -> runDismissMorph { currentActions.value.onDismiss() }
                                                    PipHitRegion.Headphones -> runBackgroundMorph()
                                                    PipHitRegion.Previous -> {
                                                        currentActions.value.onSkipPrevious()
                                                        controlsRevision++
                                                    }
                                                    PipHitRegion.PlayPause -> {
                                                        currentActions.value.onTogglePlayPause()
                                                        controlsRevision++
                                                    }
                                                    PipHitRegion.Next -> {
                                                        currentActions.value.onSkipNext()
                                                        controlsRevision++
                                                    }
                                                    PipHitRegion.None -> controlsRevision++
                                                }
                                            }
                                        }
                                    }
                                }
                                // Swipe down to dismiss - but only once at
                                // least DISMISS_BELOW_NAV_FRACTION of the
                                // window's height is actually below the
                                // nav-bar line. Real PiP needs a deliberate
                                // push off the bottom; merely touching the
                                // nav-bar region settles back instead.
                                centerY + h / 2f - (currentScreenHeight.value - currentNavBarHeight.value) >=
                                    h * DISMISS_BELOW_NAV_FRACTION -> {
                                    // Restricted gesture scope - launch,
                                    // like the pull-back below.
                                    scope.launch { runDismissMorph { currentActions.value.onDismiss() } }
                                }
                                // Stash only once MORE THAN HALF the
                                // window is off the edge (center past the
                                // screen bounds) - anything less settles
                                // back onto the edge instead.
                                centerX <= 0f -> stashToEdge(left = true)
                                centerX >= currentScreenWidth.value -> stashToEdge(left = false)
                                else -> settleToEdge(velocityX, velocityY)
                            }

                            // Overshoot pull-back - launched AFTER the
                            // settle above on purpose (see the commit
                            // block for why). Runs entirely on the
                            // paint-time pinchScale (no relayout = no
                            // flashing), then commits the layout at the
                            // identical visual size.
                            if (isResizing && releasePullBackTarget != releaseVisualScale) {
                                pullBackRunning = true
                                val startPinch = pinchScale
                                val endPinch = releasePullBackTarget / sizeScale
                                sizeJob = scope.launch {
                                    animate(
                                        startPinch,
                                        endPinch,
                                        0f,
                                        tween(PULL_BACK_MS, easing = FastOutSlowInEasing),
                                    ) { v, _ -> pinchScale = v }
                                    sizeScale = releasePullBackTarget
                                    pinchScale = 1f
                                    pullBackRunning = false
                                }
                            }
                        }
                    }
                    // Same transient pinch/tilt transform as the video
                    // box, so the (empty-during-pinch) gesture area and
                    // the clip outline track the video exactly. The clip
                    // lives INSIDE this layer (not as a standalone
                    // Modifier.clip before it): clip+shape here apply in
                    // layer-local space, so the rounded outline rotates
                    // WITH the window instead of shearing its corners off
                    // axis-aligned. Must stay AFTER the pointerInput -
                    // see DUMMY_PIP_PINCH_UNTRANSFORMED_INPUT_FIX.
                    .graphicsLayer {
                        alpha = dismissAlpha.value
                        scaleX = pinchScale
                        scaleY = pinchScale
                        rotationZ = pipRotation
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        clip = true
                        shape = RoundedCornerShape(PIP_CORNER_RADIUS)
                    },
            ) {
                if (controlsShown && controlsSettled) {
                    // Scrim - real PiP darkens the video behind its controls.
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))

                    // Pressed-state pulse - the translucent circle real
                    // PiP blooms behind a tapped control: it grows and
                    // fades over PIP_FLASH_MS rather than blinking
                    // statically (small for the top row, big for the
                    // bottom row - the circle in the screenshots that
                    // made the buttons "feel" bigger).
                    if (flashProgress.value < 1f) flashRegion?.let { region ->
                        val iconPxF = with(density) { PIP_ICON_SIZE_DP.dp.toPx() }
                        val insetPxF = with(density) { PIP_CONTROLS_INSET.toPx() }
                        val centerSpacingPxF = with(density) { PIP_CENTER_ROW_SPACING.toPx() }
                        val topSpacingPxF = with(density) { PIP_TOP_ROW_SPACING.toPx() }
                        val topY = insetPxF + iconPxF / 2f
                        val bottomY = grownHeightPx - insetPxF - iconPxF / 2f
                        val (cx, cy, radius) = when (region) {
                            PipHitRegion.Headphones -> Triple(insetPxF + iconPxF / 2f, topY, PIP_TOP_FLASH_RADIUS)
                            PipHitRegion.Expand -> Triple(
                                grownWidthPx - insetPxF - iconPxF * 1.5f - topSpacingPxF,
                                topY,
                                PIP_TOP_FLASH_RADIUS,
                            )
                            PipHitRegion.Close -> Triple(grownWidthPx - insetPxF - iconPxF / 2f, topY, PIP_TOP_FLASH_RADIUS)
                            PipHitRegion.Previous -> Triple(
                                grownWidthPx / 2f - (iconPxF + centerSpacingPxF),
                                bottomY,
                                PIP_BOTTOM_FLASH_RADIUS,
                            )
                            PipHitRegion.PlayPause -> Triple(grownWidthPx / 2f, bottomY, PIP_BOTTOM_FLASH_RADIUS)
                            PipHitRegion.Next -> Triple(
                                grownWidthPx / 2f + (iconPxF + centerSpacingPxF),
                                bottomY,
                                PIP_BOTTOM_FLASH_RADIUS,
                            )
                            PipHitRegion.None -> return@let
                        }
                        // Pulse: blooms from half size to full while
                        // fading out.
                        val p = flashProgress.value
                        val pulsedRadius = radius * (0.5f + 0.9f * p)
                        val pulsedRadiusPx = with(density) { pulsedRadius.toPx() }
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(
                                        (cx - pulsedRadiusPx).roundToInt(),
                                        (cy - pulsedRadiusPx).roundToInt(),
                                    )
                                }
                                .size(pulsedRadius * 2)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.3f * (1f - p))),
                        )
                    }

                    // Samsung-style layout (matched against real PiP
                    // screenshots): headphones top-left corner, expand
                    // just left of close in the top-right corner,
                    // prev/play/next centered along the bottom edge.
                    //
                    // All icons are visual-only; taps are hit-tested
                    // manually in the gesture loop above (sibling
                    // GestureHandler shadowing - see file header). Plain
                    // Icon, NOT IconButton: IconButton enforces Material3's
                    // 48dp minimum touch target, which overflows/misaligns
                    // the intended layout inside a window this small
                    // (confirmed on-device as "controls not properly
                    // positioned").
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(PIP_CONTROLS_INSET)
                            .size(PIP_ICON_SIZE_DP.dp),
                    )

                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).padding(PIP_CONTROLS_INSET),
                        horizontalArrangement = Arrangement.spacedBy(PIP_TOP_ROW_SPACING),
                    ) {
                        Icon(
                            Icons.Filled.OpenInFull,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(PIP_ICON_SIZE_DP.dp),
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(PIP_ICON_SIZE_DP.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(PIP_CONTROLS_INSET),
                        horizontalArrangement = Arrangement.spacedBy(PIP_CENTER_ROW_SPACING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(PIP_ICON_SIZE_DP.dp),
                        )
                        Icon(
                            if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(PIP_PLAY_ICON_SIZE_DP.dp),
                        )
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(PIP_ICON_SIZE_DP.dp),
                        )
                    }
                }
            }
        }

        // ---- Stashed sliver gesture area (visible only while stashed) ----
        // AM (DUMMY_PIP_STASH_DIRECT_DRAG_FIX) -->
        // No pull-arrow tab: the visible video sliver itself is the
        // handle, like real PiP. Dragging it moves the window 1:1 under
        // the finger (no jump-to-edge first); releasing decides:
        // pulled far enough out -> unstash and settle with the drag's
        // velocity; barely moved -> slide back into the stash. A simple
        // tap unstashes with the slide-in animation.
        if (mode == DummyPipMode.StashedLeft || mode == DummyPipMode.StashedRight) {
            val isLeft = mode == DummyPipMode.StashedLeft
            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { pipWidthPx.toDp() },
                        height = with(density) { pipHeightPx.toDp() },
                    )
                    .offset {
                        IntOffset(
                            (centerX - pipWidthPx / 2f).roundToInt(),
                            (centerY - pipHeightPx / 2f).roundToInt(),
                        )
                    }
                    .pointerInput(isLeft) {
                        val touchSlop = viewConfiguration.touchSlop
                        val pullThresholdPx = with(density) { 48.dp.toPx() }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            cancelMove()
                            var dragged = false
                            var accumulated = 0f
                            var velocityX = 0f
                            var velocityY = 0f
                            var lastMoveTime = down.uptimeMillis
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed } ?: break
                                val delta = change.positionChange()
                                accumulated += hypot(delta.x, delta.y)
                                if (!dragged && accumulated > touchSlop) dragged = true
                                if (dragged) {
                                    // Physically drag the window out from
                                    // wherever it is - no repositioning,
                                    // the sliver stays under the finger.
                                    centerX += delta.x
                                    centerY += delta.y
                                    val dt = (change.uptimeMillis - lastMoveTime).coerceAtLeast(1)
                                    velocityX = (velocityX * 0.6f + delta.x / dt * 1000f * 0.4f)
                                        .coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
                                    velocityY = (velocityY * 0.6f + delta.y / dt * 1000f * 0.4f)
                                        .coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
                                    lastMoveTime = change.uptimeMillis
                                    change.consume()
                                }
                            }
                            if (dragged) {
                                val w = windowWidthPx()
                                val stashedX = if (isLeft) {
                                    boundLeftPx() - w / 2f + stashPeekPx
                                } else {
                                    boundRightPx() + w / 2f - stashPeekPx
                                }
                                if (abs(centerX - stashedX) > pullThresholdPx) {
                                    // Pulled away from the edge: genuinely
                                    // unstash and settle with momentum.
                                    mode = DummyPipMode.Pip
                                    settleToEdge(velocityX, velocityY)
                                } else {
                                    // Barely moved: slide back into the stash.
                                    stashToEdge(isLeft)
                                }
                            } else {
                                unstash(animated = true)
                            }
                        }
                    },
            )
        }
    }
}
