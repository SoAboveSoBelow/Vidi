package eu.kanade.tachiyomi.ui.player.components

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.Surface
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cafe.adriel.voyager.navigator.currentOrThrow

// AM (PIP_FAKE_SYSTEM_BAR) -->
// Replaces SystemBarOverlay's real-system-bar behavior when active: the real
// bars stay hidden (immersive) regardless of the "show system status bar"
// setting, and this draws a lookalike nav bar instead. The Recents button
// here is the actual fix - it calls onRecentsClick (wired to the same
// reliable PlayerEvent.EnterPip mechanism the explicit PIP button already
// uses, confirmed reliable all night) instead of ever touching the real
// system Recents gesture, which is the one confirmed-unreliable trigger on
// this device. Home and Back call moveTaskToBack()/onBack directly - both
// already reliable through their real system paths, no fake handling needed
// for those, just a lookalike button that triggers the real thing.
//
// AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_FIX) -->
// Was always a horizontal bar pinned to the bottom, regardless of rotation -
// confirmed against AOSP's own NavigationBarView.java that this isn't
// actually how the real nav bar behaves. The real one maintains two
// genuinely separate layouts (mHorizontal/mVertical) and switches based on
// rotation (mIsVertical), including rotating the button icons 90 degrees to
// match (orientHomeButton). FakeNavBarSide below mirrors that: Bottom for
// portrait, Left/Right for the two landscape rotations. The
// ROTATION_90 -> left / ROTATION_270 -> right mapping was confirmed by
// direct on-device comparison against the real gesture menu (an earlier
// guess had this backwards - see rememberFakeNavBarSide's own history).
// <-- AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_FIX)
// AM (PIP_FAKE_SYSTEM_BAR_REAL_DIMENS_FIX) -->
// Sourced from AOSP's packages/SystemUI/res/values/dimens.xml -
// NAV_BAR_THICKNESS_FALLBACK matches navigation_bar_size (references the
// standard, well-documented 48dp bar thickness on phones) - used only if
// this device's real thickness can't be measured (see
// FakeSystemBarOverlay's own realNavBarThickness). NAV_KEY_SIZE (70dp) and
// NAV_SIDE_PADDING (36dp) are AOSP's own per-button width and outer-edge
// padding, expressed as proportions of NAV_BAR_THICKNESS_FALLBACK and then
// scaled against whatever this device's real, measured thickness turns out
// to be - there's no public API on any device/OEM to query button
// width/spacing directly, so this is the best available approximation.
// <-- AM (PIP_FAKE_SYSTEM_BAR_REAL_DIMENS_FIX)
private val NAV_BAR_THICKNESS = 48.dp
private val NAV_KEY_SIZE = 70.dp
private val NAV_SIDE_PADDING = 36.dp
private val STATUS_BAR_HEIGHT_FALLBACK = 24.dp

enum class FakeNavBarSide { Bottom, Left, Right }

@Composable
fun rememberFakeNavBarSide(): FakeNavBarSide {
    val activity = LocalActivity.currentOrThrow
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        @Suppress("DEPRECATION")
        when (activity.windowManager.defaultDisplay.rotation) {
            // AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_MAPPING_FIX) -->
            // Confirmed correct as originally written (Right for ROTATION_90,
            // Left for ROTATION_270) - the side placement itself wasn't the
            // problem, the icon rotation direction was. See FakeNavIcon's own
            // rotated-icon handling for the actual fix.
            // <-- AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_MAPPING_FIX)
            Surface.ROTATION_90 -> FakeNavBarSide.Right
            Surface.ROTATION_270 -> FakeNavBarSide.Left
            else -> FakeNavBarSide.Bottom
        }
    }
}

@Composable
fun rememberRealStatusBarHeight(): Dp {
    // AM (PIP_FAKE_TOP_BAR) -->
    // Same fix as rememberRealNavBarThickness(), for the status bar instead -
    // PlayerControls' own statusBarTopPadding was measuring
    // WindowInsets.statusBars, which also now reports zero once the real bar
    // gets hidden here. Captured once, before hiding, shared by both this
    // file's own fake top bar and PlayerControls' top-row reservation.
    // <-- AM (PIP_FAKE_TOP_BAR)
    val realInsets = WindowInsets.statusBars.asPaddingValues()
    return remember {
        val measured = realInsets.calculateTopPadding()
        if (measured > 0.dp) measured else STATUS_BAR_HEIGHT_FALLBACK
    }
}

@Composable
fun rememberRealNavBarThickness(): Dp {
    // AM (PIP_FAKE_SYSTEM_BAR_REAL_MEASURED_THICKNESS_FIX) -->
    // AOSP's own default navigation_bar_height (48dp) was confirmed, by
    // direct on-device comparison, not to match this OEM's actual nav bar -
    // no library needed for this, WindowInsets already reports the real,
    // live, device-specific bar thickness (not a generic default) - it just
    // has to be read before the real bar gets hidden (FakeSystemBarOverlay's
    // own effect), since insets report zero for a bar that isn't currently
    // shown. Captured once here, shared by both FakeSystemBarOverlay (the
    // bar's own size) and PlayerControls (the space it reserves for it), so
    // both always agree on the same real number rather than each measuring
    // independently and risking drift between them.
    val realInsets = WindowInsets.navigationBars.asPaddingValues()
    return remember {
        val measured = maxOf(
            realInsets.calculateBottomPadding(),
            realInsets.calculateLeftPadding(LayoutDirection.Ltr),
            realInsets.calculateRightPadding(LayoutDirection.Ltr),
        )
        if (measured > 0.dp) measured else NAV_BAR_THICKNESS
    }
    // <-- AM (PIP_FAKE_SYSTEM_BAR_REAL_MEASURED_THICKNESS_FIX)
}

@SuppressLint("WrongConstant")
@Composable
fun BoxScope.FakeTopBar(showBar: Boolean) {
    // AM (PIP_FAKE_TOP_BAR) -->
    // Mirrors FakeSystemBarOverlay's approach for the bottom/side bar, scoped
    // down to just the one thing asked for - a battery/power indicator - not
    // a full status bar replica (clock, signal, notification icons). Reads
    // the real battery level via ACTION_BATTERY_CHANGED, the same broadcast
    // the real status bar itself is driven by, so this always shows the
    // device's actual current charge, not a static or stale value. Only
    // shown when the real status bar would otherwise be hidden - same
    // showBar gate as the nav bar, both driven by the "show system status
    // bar with controls" setting.
    // <-- AM (PIP_FAKE_TOP_BAR)
    if (!showBar) return

    val context = LocalContext.current
    var batteryPercent by remember { mutableIntStateOf(-1) }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryPercent = (level * 100) / scale
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        sticky?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }

    if (batteryPercent < 0) return

    val statusBarHeight = rememberRealStatusBarHeight()
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxWidth()
            .height(statusBarHeight)
            .background(Color.Black)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$batteryPercent%",
            color = Color.White,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp)) {
            drawBattery(percent = batteryPercent, charging = isCharging)
        }
    }
}

private fun DrawScope.drawBattery(percent: Int, charging: Boolean) {
    // AM (PIP_FAKE_TOP_BAR_REAL_STYLING_FIX) -->
    // Proportions derived from AOSP's own current, real battery vector
    // (core/res/res/drawable/ic_battery.xml) rather than an invented shape -
    // that one's vertical (portrait), so these are its measurements rotated
    // 90 degrees for the status bar's horizontal orientation: rounded
    // corners (the real one uses a ~1.33/10 corner radius ratio, not sharp
    // corners), and a cap sized to roughly 40% of the body's cross-axis
    // dimension, not 50%, positioned centered.
    // <-- AM (PIP_FAKE_TOP_BAR_REAL_STYLING_FIX)
    val strokeWidth = 1.5.dp.toPx()
    val capWidth = size.width * 0.12f
    val bodyWidth = size.width - capWidth
    val cornerRadius = CornerRadius(bodyWidth * 0.13f, bodyWidth * 0.13f)

    // Outer body outline, rounded corners.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(0f, 0f),
        size = Size(bodyWidth, size.height),
        cornerRadius = cornerRadius,
        style = Stroke(width = strokeWidth),
    )
    // Terminal cap - roughly 40% of the body's height, centered.
    val capHeight = size.height * 0.4f
    drawRect(
        color = Color.White,
        topLeft = Offset(bodyWidth, (size.height - capHeight) / 2f),
        size = Size(capWidth, capHeight),
    )
    // Fill proportional to charge level.
    val inset = strokeWidth * 1.5f
    val fillableWidth = bodyWidth - inset * 2
    val fillWidth = (fillableWidth * (percent / 100f)).coerceIn(0f, fillableWidth)
    val fillColor = if (charging) Color(0xFF4CAF50) else Color.White
    drawRoundRect(
        color = fillColor,
        topLeft = Offset(inset, inset),
        size = Size(fillWidth, size.height - inset * 2),
        cornerRadius = CornerRadius((cornerRadius.x - inset).coerceAtLeast(0f), (cornerRadius.y - inset).coerceAtLeast(0f)),
    )
}

@SuppressLint("WrongConstant")
@Composable
fun BoxScope.FakeSystemBarOverlay(
    showBar: Boolean,
    onRecentsClick: () -> Unit,
    onHomeClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val activity = LocalActivity.currentOrThrow
    val side = rememberFakeNavBarSide()
    val realNavBarThickness = rememberRealNavBarThickness()
    // AM (PIP_FAKE_SYSTEM_BAR_ICON_OVERLAP_FIX) -->
    // Was scaling keySize/sidePadding by the same ratio as the measured bar
    // thickness - confirmed to cause overlapping icons, most likely because
    // the vertical/landscape measurement came out inflated (WindowInsets is
    // already documented, by other independent libraries hitting the same
    // problem, to report inconsistent values in vertical/gesture-nav
    // configurations specifically). Scaling amplified whatever error was in
    // that measurement rather than correcting for it. The bar's own overall
    // thickness still uses the real measured value (the one thing
    // WindowInsets is actually meant to report), but the individual button
    // size and padding go back to AOSP's fixed proportions, unscaled.
    // <-- AM (PIP_FAKE_SYSTEM_BAR_ICON_OVERLAP_FIX)
    val keySize = NAV_KEY_SIZE
    val sidePadding = NAV_SIDE_PADDING

    // Real system bars always stay hidden here - the fake bar below is what
    // the user actually sees and interacts with instead.
    LaunchedEffect(Unit) {
        val window = activity.window
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    DisposableEffect(Unit) {
        onDispose {
            val window = activity.window
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    if (!showBar) return

    // AM (PIP_FAKE_SYSTEM_BAR_GESTURE_EXCLUSION_FIX) -->
    // Tapping the fake Recents button was reported to still trigger the
    // real, broken Recents behavior - the touch was landing in Android's own
    // reserved gesture-detection zone, which stays physically active
    // regardless of whether the real system bars are visually hidden. This
    // explicitly asks the system to relax its own gesture recognition over
    // this bar's exact bounds, without moving the bar itself at all.
    // <-- AM (PIP_FAKE_SYSTEM_BAR_GESTURE_EXCLUSION_FIX)
    val commonModifier = Modifier
        .background(Color.Black)
        .systemGestureExclusion()

    when (side) {
        FakeNavBarSide.Bottom -> {
            Row(
                modifier = commonModifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(realNavBarThickness)
                    // AM (PIP_FAKE_SYSTEM_BAR_REAL_DIMENS_FIX) -->
                    // Matches AOSP's own proportions (navigation_side_padding
                    // at the outer edges, each button a fixed
                    // navigation_key_width, not evenly stretched) - scaled
                    // against this device's actually-measured bar thickness
                    // rather than AOSP's flat defaults, which were confirmed
                    // not to match this OEM's real dimensions.
                    .padding(horizontal = sidePadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                // <-- AM (PIP_FAKE_SYSTEM_BAR_REAL_DIMENS_FIX)
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FakeNavIcon(
                    onClick = onRecentsClick,
                    rotated = false,
                    modifier = Modifier.fillMaxHeight().width(keySize),
                    draw = { c, s -> drawRecents(c, s) },
                )
                FakeNavIcon(
                    onClick = onHomeClick,
                    rotated = false,
                    modifier = Modifier.fillMaxHeight().width(keySize),
                    draw = { c, s -> drawHome(c, s) },
                )
                FakeNavIcon(
                    onClick = onBackClick,
                    rotated = false,
                    modifier = Modifier.fillMaxHeight().width(keySize),
                    draw = { c, s -> drawBack(c, s) },
                )
            }
        }
        FakeNavBarSide.Left, FakeNavBarSide.Right -> {
            // AM (PIP_FAKE_SYSTEM_BAR_HEIGHT_FIX) -->
            // Was fillMaxHeight() - my own icons were reported clustering
            // together in one section of the bar instead of spreading across
            // it, which points at this not actually resolving to the true
            // full screen height in this nested context. Using the screen's
            // own known height explicitly instead, so SpaceBetween has an
            // unambiguous, correct distance to spread the three icons across
            // regardless of how fillMaxHeight() was resolving here.
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            // <-- AM (PIP_FAKE_SYSTEM_BAR_HEIGHT_FIX)
            Column(
                modifier = commonModifier
                    .align(if (side == FakeNavBarSide.Left) Alignment.CenterStart else Alignment.CenterEnd)
                    .height(screenHeight)
                    .width(realNavBarThickness)
                    .padding(vertical = sidePadding),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_FIX) -->
                // Top-to-bottom order flips between the two side placements,
                // matching how the real nav bar's button order flips between
                // the two landscape rotations rather than being a fixed copy
                // of the portrait left-to-right order.
                // <-- AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_FIX)
                // AM (PIP_FAKE_SYSTEM_BAR_ICON_ROTATION_FIX) -->
                // Was rotated=true for every icon - confirmed wrong by direct
                // on-device comparison (the back arrow showed up pointing "^"
                // instead of staying "<" like the real one). Matches AOSP's
                // actual per-icon behavior: orientHomeButton() explicitly
                // rotates 90 degrees for vertical, but orientBackButton() has
                // no such vertical-specific case at all, only RTL/alt-back
                // handling - so only home rotates here, back and recents
                // stay unrotated.
                // <-- AM (PIP_FAKE_SYSTEM_BAR_ICON_ROTATION_FIX)
                // AM (PIP_FAKE_SYSTEM_BAR_MIRROR_FIX) -->
                // Was Recents-Home-Back top-to-bottom for Right, Back-Home-
                // Recents for Left - confirmed backwards by direct comparison
                // against the real gesture menu: the two rotations are
                // themselves mirror images of each other, and the order this
                // used didn't match that reflection. Swapped which list goes
                // with which side - the side placement itself was already
                // correct and untouched here.
                // <-- AM (PIP_FAKE_SYSTEM_BAR_MIRROR_FIX)
                val iconModifier = Modifier.fillMaxWidth().height(keySize)
                if (side == FakeNavBarSide.Right) {
                    FakeNavIcon(onClick = onBackClick, rotated = false, modifier = iconModifier, draw = { c, s -> drawBack(c, s) })
                    FakeNavIcon(onClick = onHomeClick, rotated = true, modifier = iconModifier, draw = { c, s -> drawHome(c, s) })
                    FakeNavIcon(onClick = onRecentsClick, rotated = false, modifier = iconModifier, draw = { c, s -> drawRecents(c, s) })
                } else {
                    FakeNavIcon(onClick = onRecentsClick, rotated = false, modifier = iconModifier, draw = { c, s -> drawRecents(c, s) })
                    FakeNavIcon(onClick = onHomeClick, rotated = true, modifier = iconModifier, draw = { c, s -> drawHome(c, s) })
                    FakeNavIcon(onClick = onBackClick, rotated = false, modifier = iconModifier, draw = { c, s -> drawBack(c, s) })
                }
            }
        }
    }
}

private fun DrawScope.drawRecents(color: Color, stroke: Float) {
    val barWidth = size.width / 7f
    val gap = barWidth
    val startX = (size.width - (3 * barWidth + 2 * gap)) / 2f
    repeat(3) { i ->
        drawLine(
            color = color,
            start = Offset(startX + i * (barWidth + gap), size.height * 0.2f),
            end = Offset(startX + i * (barWidth + gap), size.height * 0.8f),
            strokeWidth = stroke,
        )
    }
}

private fun DrawScope.drawHome(color: Color, stroke: Float) {
    drawCircle(
        color = color,
        radius = size.minDimension / 3f,
        style = Stroke(width = stroke),
    )
}

private fun DrawScope.drawBack(color: Color, stroke: Float) {
    val path = Path().apply {
        moveTo(size.width * 0.65f, size.height * 0.2f)
        lineTo(size.width * 0.35f, size.height * 0.5f)
        lineTo(size.width * 0.65f, size.height * 0.8f)
    }
    drawPath(path = path, color = color, style = Stroke(width = stroke))
}

@Composable
private fun FakeNavIcon(
    onClick: () -> Unit,
    rotated: Boolean,
    modifier: Modifier = Modifier,
    draw: DrawScope.(color: Color, stroke: Float) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        // AM (PIP_FAKE_SYSTEM_BAR_REAL_DIMENS_FIX) -->
        // The full touch target now matches AOSP's real per-button
        // dimensions (see NAV_KEY_SIZE's own doc comment) - the drawn icon
        // itself stays a fixed, reasonable 24dp regardless, same as before.
        modifier = modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false),
                onClick = onClick,
            ),
        // <-- AM (PIP_FAKE_SYSTEM_BAR_REAL_DIMENS_FIX)
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            // AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_FIX) -->
            // Matches orientHomeButton()'s own 90-degree rotation for the
            // vertical layout - the icons themselves rotate to match the bar,
            // the same as the real nav bar's icons do.
            if (rotated) {
                rotate(90f) { draw(Color.White, 3f) }
            } else {
                draw(Color.White, 3f)
            }
            // <-- AM (PIP_FAKE_SYSTEM_BAR_ORIENTATION_FIX)
        }
    }
}
// <-- AM (PIP_FAKE_SYSTEM_BAR)
