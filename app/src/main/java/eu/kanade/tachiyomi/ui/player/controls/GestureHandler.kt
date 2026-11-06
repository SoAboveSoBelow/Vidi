package eu.kanade.tachiyomi.ui.player.controls

import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.player.components.LeftSideOvalShape
import eu.kanade.presentation.player.components.RightSideOvalShape
import eu.kanade.presentation.theme.playerRippleConfiguration
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.controls.components.DoubleTapSeekTriangles
import eu.kanade.tachiyomi.ui.player.domain.BrightnessManager
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.time.Duration.Companion.milliseconds

// AM (DOUBLE_TAP_ZONE) -->
/** The side a double-tap seek runs on. The middle fifth is neither, hence null. */
private enum class SeekZone { Left, Right }
// <-- AM (DOUBLE_TAP_ZONE)

// AM (PINCH_ZOOM) -->
/**
 * What a drag has been committed to. [Undecided] lasts until the drag clears touch
 * slop; [Zoom] is entered the instant a second finger lands and is never left while
 * any finger is still down, so lifting one finger mid-pinch cannot hand the
 * remainder of the gesture to the seek bar. [Pan] is the one-finger drag that
 * replaces seek and the sliders while the video is zoomed in.
 */
private enum class DragMode { Undecided, Seek, Slider, SwitchEpisode, Pan, Zoom }

/** Zoom per unit of wheel travel, as an exponent. */
private const val SCROLL_ZOOM_SENSITIVITY = 0.15f

/** How long the wheel must be idle before the detent pull fires. */
private const val SCROLL_SETTLE_MS = 200L
// <-- AM (PINCH_ZOOM)

@Composable
fun GestureHandler(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel,
    interactionSource: MutableInteractionSource,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val playerPreferences = remember { context.appGraph.playerPreferences }
    val gesturePreferences = remember { context.appGraph.gesturePreferences }
    val audioPreferences = remember { context.appGraph.audioPreferences }
    val brightnessManager = remember { context.appGraph.brightnessManager }

    val allowGesturesInPanels by playerPreferences.allowGestures.collectAsState()
    val horizontalGesture by gesturePreferences.gestureHorizontalSeek.collectAsState()
    val gestureVolumeBrightness by gesturePreferences.gestureVolumeBrightness.collectAsState()
    val swapVolumeBrightness by gesturePreferences.swapVolumeBrightness.collectAsState()
    val showSeekbar by gesturePreferences.showSeekBar.collectAsState()
    val volumeBoostingCap by audioPreferences.volumeBoostCap.collectAsState()
    // AM (SWIPE_SWITCH) -->
    val verticalSwipeSwitch by gesturePreferences.gestureVerticalSwipeSwitch.collectAsState()
    // <-- AM (SWIPE_SWITCH)
    // AM (PINCH_ZOOM) -->
    val pinchZoom by gesturePreferences.gesturePinchZoom.collectAsState()
    // <-- AM (PINCH_ZOOM)

    val uiData by viewModel.uiData.collectAsStateWithLifecycle()
    val stateData by viewModel.stateData.collectAsStateWithLifecycle()
    val playbackData by viewModel.playbackData.collectAsStateWithLifecycle()

    val position by viewModel.propFlow<Int>("time-pos").collectAsStateWithLifecycle()
    val duration by viewModel.propFlow<Int>("duration").collectAsStateWithLifecycle()
    val currentMPVVolume by viewModel.propFlow<Int>("volume").collectAsStateWithLifecycle()

    var isDoubleTapSeeking by remember { mutableStateOf(false) }
    var isLongPressing by remember { mutableStateOf(false) }

    // AM (DOUBLE_TAP_ZONE) -->
    // The zone the running double-tap seek was started in, or null when no seek
    // is running. Without it, the repeat-tap branch in onPress dispatches every
    // tap by position across all three zones, so a seek started on the right
    // turns the whole screen live: a tap in the middle fires the centre gesture
    // (PlayPause by default) and a tap on the left seeks back, undoing the skip.
    // Only the zone that started the seek continues it.
    var doubleTapSeekZone by remember { mutableStateOf<SeekZone?>(null) }
    // <-- AM (DOUBLE_TAP_ZONE)

    LaunchedEffect(playbackData.doubleTapSeekAmount) {
        delay(800.milliseconds)
        isDoubleTapSeeking = true
        viewModel.updateSeekAmount(0)
        viewModel.updateSeekText(null)
        delay(100.milliseconds)
        viewModel.hideSeekBar()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeGestures)
            .pointerInput(Unit) {
                // AM (DOUBLE_TAP_ZONE): the zone split, in one place instead of
                // repeated as bare x comparisons in each handler.
                fun zoneOf(x: Float): SeekZone? = when {
                    x > size.width * 3 / 5 -> SeekZone.Right
                    x < size.width * 2 / 5 -> SeekZone.Left
                    else -> null
                }

                detectTapGestures(
                    onTap = {
                        if (isDoubleTapSeeking) return@detectTapGestures
                        if (uiData.controlsShown) viewModel.hideControls() else viewModel.showControls()
                    },
                    onDoubleTap = {
                        if (uiData.isControlsLocked || isDoubleTapSeeking) return@detectTapGestures
                        when (zoneOf(it.x)) {
                            SeekZone.Right -> {
                                if (!playbackData.isSeekingForwards) viewModel.updateSeekAmount(0)
                                viewModel.handleRightDoubleTap()
                                // AM (DOUBLE_TAP_ZONE)
                                doubleTapSeekZone = SeekZone.Right
                                isDoubleTapSeeking = true
                            }
                            SeekZone.Left -> {
                                if (playbackData.isSeekingForwards) viewModel.updateSeekAmount(0)
                                viewModel.handleLeftDoubleTap()
                                // AM (DOUBLE_TAP_ZONE)
                                doubleTapSeekZone = SeekZone.Left
                                isDoubleTapSeeking = true
                            }
                            // The centre gesture is a one-shot: it starts no seek,
                            // so it claims no zone either.
                            null -> viewModel.handleCenterDoubleTap()
                        }
                    },
                    onPress = {
                        if (uiData.panelShown != Panels.None && !allowGesturesInPanels) {
                            viewModel.setPanel(Panels.None)
                        }
                        val press = PressInteraction.Press(
                            it.copy(x = if (it.x > size.width * 3 / 5) it.x - size.width * 0.6f else it.x),
                        )
                        // AM (DOUBLE_TAP_ZONE): a repeat tap only continues the seek
                        // when it lands in the zone that started it. Anything else
                        // ends the seek mode and falls through to onTap, which then
                        // toggles the controls as an ordinary tap would.
                        val zone = zoneOf(it.x)
                        if (!uiData.isControlsLocked &&
                            isDoubleTapSeeking &&
                            playbackData.doubleTapSeekAmount != 0 &&
                            zone != null &&
                            zone == doubleTapSeekZone
                        ) {
                            when (zone) {
                                SeekZone.Right -> {
                                    if (!playbackData.isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleRightDoubleTap()
                                }
                                SeekZone.Left -> {
                                    if (playbackData.isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleLeftDoubleTap()
                                }
                            }
                        } else {
                            isDoubleTapSeeking = false
                            doubleTapSeekZone = null
                        }
                        interactionSource.emit(press)
                        tryAwaitRelease()
                        if (isLongPressing) {
                            isLongPressing = false
                        }
                        interactionSource.emit(PressInteraction.Release(press))
                    },
                    onLongPress = {
                        if (uiData.isControlsLocked) return@detectTapGestures
                        if (!isLongPressing) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isLongPressing = true
                            viewModel.handleLongPress()
                        }
                    },
                )
            }
            // AM (PINCH_ZOOM) -->
            // Scroll-wheel zoom, for the desktop-ish cases (ChromeOS, Waydroid, a
            // tablet with a mouse). Its own node rather than part of the arbiter
            // below: a wheel is not a pointer that goes down and up, so it has no
            // gesture to arbitrate and nothing to take away from the touch path.
            .pointerInput(uiData.isControlsLocked, pinchZoom) {
                if (uiData.isControlsLocked || !pinchZoom) return@pointerInput

                awaitPointerEventScope {
                    var scrolled = false
                    while (true) {
                        // The timeout is armed only after a scroll. A wheel has no
                        // release, so the detent pull needs "the wheel stopped" as its
                        // trigger - but waking every interval when nothing is
                        // happening would be a poll, so the plain await is used until
                        // there is actually something to settle.
                        val event = if (scrolled) {
                            withTimeoutOrNull(SCROLL_SETTLE_MS) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            scrolled = false
                            if (viewModel.settleVideoZoom()) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            continue
                        }
                        if (event.type != PointerEventType.Scroll) continue
                        val delta = event.changes.fold(0f) { total, change -> total + change.scrollDelta.y }
                        if (delta == 0f) continue
                        event.changes.forEach { it.consume() }
                        // Exponential so each notch is the same proportional step
                        // whatever the current zoom, the way a pinch already behaves.
                        viewModel.transformVideoBy(scaleFactor = exp(-delta * SCROLL_ZOOM_SENSITIVITY))
                        scrolled = true
                    }
                }
            }
            // <-- AM (PINCH_ZOOM)
            // AM (PINCH_ZOOM) -->
            // One arbiter for every drag gesture, replacing the separate horizontal
            // seek / vertical slider / swipe-switch detectors that used to sit here
            // as independent pointerInput nodes.
            //
            // They had to be merged to make pinch work at all. Each of those
            // detectors claims the gesture off the FIRST pointer down, before a
            // second finger exists; by the time a pinch is recognisable the seek
            // detector is already past slop and consuming, and a detectTransformGestures
            // node added alongside them never sees usable pointers. Checking a
            // pointer-count flag inside the old callbacks would not fix it either -
            // the flag can only be set after the seek has already committed and
            // moved the position, so every pinch would flash a seek first.
            //
            // Owning all the pointers in one loop is what makes "two fingers means
            // zoom" enforceable: the second down is observed directly and aborts the
            // single-finger gesture before it commits.
            .pointerInput(
                uiData.isControlsLocked,
                verticalSwipeSwitch,
                horizontalGesture,
                gestureVolumeBrightness,
                pinchZoom,
            ) {
                if (uiData.isControlsLocked) return@pointerInput

                val touchSlop = viewConfiguration.touchSlop
                val switchThreshold = size.height * 0.15f

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var mode = DragMode.Undecided
                    var pointerId = firstDown.id
                    var lastPosition = firstDown.position
                    var slopX = 0f
                    var slopY = 0f

                    // Seek
                    var startingPosition = 0
                    var startingX = 0f

                    // Volume / brightness
                    var startingY = 0f
                    var mpvVolumeStartingY = 0f
                    var originalVolume = playbackData.currentVolume
                    var originalMPVVolume = currentMPVVolume
                    var originalBrightness = playbackData.currentBrightness
                    val brightnessGestureSens = 0.001f
                    val volumeGestureSens = 0.001f * stateData.maxVolume
                    val mpvVolumeGestureSens = 0.001f * volumeBoostingCap

                    // Swipe to switch episode
                    var totalDragAmount = 0f

                    // Pinch
                    var previousCentroid = Offset.Zero
                    var previousSpread = 0f

                    val isIncreasingVolumeBoost: (Float) -> Boolean = {
                        volumeBoostingCap > 0 &&
                            playbackData.currentVolume == stateData.maxVolume &&
                            (currentMPVVolume ?: 100) - 100 < volumeBoostingCap &&
                            it < 0
                    }
                    val isDecreasingVolumeBoost: (Float) -> Boolean = {
                        volumeBoostingCap > 0 &&
                            playbackData.currentVolume == stateData.maxVolume &&
                            (currentMPVVolume ?: 100) - 100 in 1..volumeBoostingCap &&
                            it > 0
                    }

                    fun beginSlider(point: Offset) {
                        startingY = 0f
                        mpvVolumeStartingY = 0f
                        originalVolume = playbackData.currentVolume
                        originalMPVVolume = currentMPVVolume
                        originalBrightness = playbackData.currentBrightness
                            .takeUnless { it == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                            ?: brightnessManager.getCurrentBrightness()
                        lastPosition = point
                    }

                    fun dragSlider(point: Offset, amount: Float) {
                        val changeVolume: () -> Unit = {
                            if (isIncreasingVolumeBoost(amount) || isDecreasingVolumeBoost(amount)) {
                                if (mpvVolumeStartingY == 0f) {
                                    startingY = 0f
                                    originalVolume = playbackData.currentVolume
                                    mpvVolumeStartingY = point.y
                                }
                                viewModel.changeMPVVolumeTo(
                                    calculateNewVerticalGestureValue(
                                        originalMPVVolume ?: 100,
                                        mpvVolumeStartingY,
                                        point.y,
                                        mpvVolumeGestureSens,
                                    )
                                        .coerceIn(100..volumeBoostingCap + 100),
                                )
                            } else {
                                if (startingY == 0f) {
                                    mpvVolumeStartingY = 0f
                                    originalMPVVolume = currentMPVVolume
                                    startingY = point.y
                                }
                                viewModel.changeVolumeTo(
                                    calculateNewVerticalGestureValue(
                                        originalVolume,
                                        startingY,
                                        point.y,
                                        volumeGestureSens,
                                    ),
                                )
                            }
                            viewModel.displayVolumeSlider(true)
                        }
                        val changeBrightness: () -> Unit = {
                            if (startingY == 0f) startingY = point.y
                            viewModel.changeBrightnessTo(
                                calculateNewVerticalGestureValue(
                                    originalBrightness,
                                    startingY,
                                    point.y,
                                    brightnessGestureSens,
                                ),
                            )
                            viewModel.displayBrightnessSlider(true)
                        }
                        if (swapVolumeBrightness) {
                            if (point.x > size.width / 2) changeBrightness() else changeVolume()
                        } else {
                            if (point.x < size.width / 2) changeBrightness() else changeVolume()
                        }
                    }

                    fun dragSeek(point: Offset) {
                        val current = point.x
                        calculateNewHorizontalGestureValue(startingPosition, startingX, current, 0.15f).let {
                            viewModel.updateGestureSeekAmount(
                                Pair(
                                    startingPosition,
                                    (it - startingPosition)
                                        .coerceIn(0 - startingPosition, ((duration ?: 0) - startingPosition)),
                                ),
                            )
                            viewModel.updateSeekPos(it.toFloat().coerceIn(0f, duration?.toFloat() ?: 0f))
                        }
                        if (showSeekbar) viewModel.showSeekBar()
                    }

                    /** Drops an in-flight single-finger gesture WITHOUT committing it. */
                    fun abandonSingleFinger() {
                        if (mode == DragMode.Seek) {
                            viewModel.updateIsSeeking(false)
                            viewModel.updateGestureSeekAmount(null)
                            viewModel.hideSeekBar()
                        }
                        totalDragAmount = 0f
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        // A second finger commits the rest of this gesture to Zoom,
                        // whether or not zoom is enabled: falling back to seek
                        // mid-pinch is the behaviour worth preventing either way.
                        if (mode != DragMode.Zoom && pressed.size >= 2) {
                            abandonSingleFinger()
                            mode = DragMode.Zoom
                            previousCentroid = centroidOf(pressed)
                            previousSpread = spreadOf(pressed, previousCentroid)
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        if (mode == DragMode.Zoom) {
                            if (pinchZoom && pressed.size >= 2) {
                                val centroid = centroidOf(pressed)
                                val spread = spreadOf(pressed, centroid)
                                val panChange = centroid - previousCentroid
                                val scale = if (previousSpread > 0f && spread > 0f) {
                                    spread / previousSpread
                                } else {
                                    1f
                                }
                                if (size.width > 0 && size.height > 0) {
                                    viewModel.transformVideoBy(
                                        scaleFactor = scale,
                                        deltaXFraction = panChange.x / size.width,
                                        deltaYFraction = panChange.y / size.height,
                                    )
                                }
                                previousCentroid = centroid
                                previousSpread = spread
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val tracked = pressed.firstOrNull { it.id == pointerId }
                        if (tracked == null) {
                            val replacement = pressed.first()
                            pointerId = replacement.id
                            lastPosition = replacement.position
                            continue
                        }
                        val change = tracked
                        val delta = change.position - lastPosition
                        lastPosition = change.position

                        if (mode == DragMode.Undecided) {
                            slopX += delta.x
                            slopY += delta.y
                            if (abs(slopX) < touchSlop && abs(slopY) < touchSlop) continue
                            // Read straight off the flow, NOT collected into
                            // composition: the zoom changes on every pointer event, so
                            // subscribing here recomposed this composable at pointer
                            // rate for a value only the gesture loop reads.
                            if (viewModel.videoZoom.value > 1f) {
                                // Zoomed in, one finger moves the picture. Seek and the
                                // sliders come back when the zoom is released.
                                mode = DragMode.Pan
                            } else if (abs(slopX) > abs(slopY)) {
                                if (!horizontalGesture) continue
                                mode = DragMode.Seek
                                startingPosition = position ?: 0
                                startingX = change.position.x
                                viewModel.updateIsSeeking(true)
                            } else if (verticalSwipeSwitch) {
                                mode = DragMode.SwitchEpisode
                                totalDragAmount = 0f
                            } else {
                                if (!gestureVolumeBrightness) continue
                                mode = DragMode.Slider
                                beginSlider(change.position)
                            }
                            change.consume()
                            continue
                        }

                        when (mode) {
                            DragMode.Seek -> {
                                val currentPosition = position ?: 0
                                val atStart = currentPosition <= 0 && delta.x < 0
                                val atEnd = currentPosition >= (duration ?: 0) && delta.x > 0
                                if (!atStart && !atEnd) dragSeek(change.position)
                            }
                            DragMode.Slider -> dragSlider(change.position, delta.y)
                            DragMode.SwitchEpisode -> totalDragAmount += delta.y
                            DragMode.Pan -> if (size.width > 0 && size.height > 0) {
                                viewModel.transformVideoBy(
                                    deltaXFraction = delta.x / size.width,
                                    deltaYFraction = delta.y / size.height,
                                )
                            }
                            else -> Unit
                        }
                        change.consume()
                    }

                    when (mode) {
                        DragMode.Seek -> {
                            viewModel.updateIsSeeking(false)
                            viewModel.seekTo(playbackData.seekPosition.toInt().coerceIn(0, duration ?: 0))
                            viewModel.updateGestureSeekAmount(null)
                            viewModel.hideSeekBar()
                        }
                        DragMode.SwitchEpisode -> {
                            if (totalDragAmount <= -switchThreshold) {
                                viewModel.nextEpisode(next = true)
                            } else if (totalDragAmount >= switchThreshold) {
                                viewModel.nextEpisode(next = false)
                            }
                        }
                        // The pull onto a detent happens HERE, on release, not during
                        // the pinch - so the zoom is free to pass through fill on its
                        // way somewhere else, and only a release nearby is captured.
                        DragMode.Zoom, DragMode.Pan -> {
                            if (viewModel.settleVideoZoom()) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        else -> Unit
                    }
                }
            },
            // <-- AM (PINCH_ZOOM)
    )
}

@Composable
fun DoubleTapToSeekOvals(
    amount: Int,
    text: String?,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(if (amount == 0) 0f else 0.2f, label = "double_tap_animation_alpha")
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (amount > 0) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides playerRippleConfiguration,
        ) {
            if (amount != 0 || text != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.4f), // 2 fifths
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(if (amount > 0) RightSideOvalShape else LeftSideOvalShape)
                            .background(Color.White.copy(alpha))
                            .indication(interactionSource, ripple()),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DoubleTapSeekTriangles(isForward = amount > 0)
                        Text(
                            text = text ?: pluralStringResource(AYMR.plurals.seconds, amount, amount),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

fun calculateNewVerticalGestureValue(originalValue: Int, startingY: Float, newY: Float, sensitivity: Float): Int {
    return originalValue + ((startingY - newY) * sensitivity).toInt()
}

fun calculateNewVerticalGestureValue(originalValue: Float, startingY: Float, newY: Float, sensitivity: Float): Float {
    return originalValue + ((startingY - newY) * sensitivity)
}

fun calculateNewHorizontalGestureValue(originalValue: Int, startingX: Float, newX: Float, sensitivity: Float): Int {
    return originalValue + ((newX - startingX) * sensitivity).toInt()
}

fun calculateNewHorizontalGestureValue(originalValue: Float, startingX: Float, newX: Float, sensitivity: Float): Float {
    return originalValue + ((newX - startingX) * sensitivity)
}

// AM (PINCH_ZOOM) -->
/** Mean position of the pointers currently down. */
private fun centroidOf(changes: List<PointerInputChange>): Offset {
    if (changes.isEmpty()) return Offset.Zero
    var sum = Offset.Zero
    changes.forEach { sum += it.position }
    return sum / changes.size.toFloat()
}

/**
 * Mean distance of the pointers from [centroid] - the figure whose ratio between
 * two events is the pinch's scale factor.
 */
private fun spreadOf(changes: List<PointerInputChange>, centroid: Offset): Float {
    if (changes.isEmpty()) return 0f
    var total = 0f
    changes.forEach { total += (it.position - centroid).getDistance() }
    return total / changes.size
}
// <-- AM (PINCH_ZOOM)
