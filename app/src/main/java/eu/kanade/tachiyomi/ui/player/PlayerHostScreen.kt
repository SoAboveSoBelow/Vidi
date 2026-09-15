package eu.kanade.tachiyomi.ui.player

// AM (PLAYER_HOST_SCREEN) -->
// The real cutover of MainActivity.startPlayerActivity()'s internal path -
// see vidi-single-activity-scoping.md work item 2. Built from what
// PlayerFreshStartScreenSpike and PlayerVoyagerScreenSpike already proved
// on-device this session, plus a direct port of PlayerActivity.onNewIntent()'s
// three-case live-reuse logic (see that function's own LIVE_REDELIVERY_TRUST_FIX
// doc comment for the un-simplified version) - same anime+episode is a no-op,
// same anime different episode calls the existing, already-safe
// viewModel.changeEpisode() directly, a different anime entirely tears down
// and starts fresh (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX's reasoning: hot-swap
// caused flash/bleed).
//
// NOT yet done, deliberately, same as every prior step in this session:
// NotificationReceiver/DeepLinkScreen (still target PlayerActivity directly -
// only MainActivity.startPlayerActivity()'s internal branch is cut over here).
// <-- AM (PLAYER_HOST_SCREEN)

// AM (PLAYER_OVERLAY_MIGRATION) -->
// No longer a Voyager Screen. Every previous attempt at the dummy-pip video
// freeze tried to patch how a SECOND MpvSurface/TextureView reattached the
// same persistent SurfaceTexture when dummy pip took over - invalidate(),
// requestLayout(), toggling hwdec, a full re-attachSurface() sequence, none
// of it held up, because none of it addressed the actual problem: two
// different Views were involved at all. Real apps with a floating mini-player
// don't do that - they keep ONE video surface alive for the whole session and
// just resize/reposition it; fullscreen and mini-player are layout states of
// the same surface, not two surfaces with a handoff between them.
//
// This is that: PlayerHostScreen is now a persistent composable, hosted once
// via PlayerOverlayHost() at MainActivity's root (outside the Navigator
// entirely - Voyager can't show two screens at once, and dummy pip needs the
// library/episode list underneath to stay visible and interactive, so the
// player can't be "the active Navigator screen" for either mode anymore).
// Keyed on PlayerMediaHolder.PlaybackRequest, so it's created fresh for a
// genuinely new anime/episode (matching the old push-a-new-Screen-instance
// behavior) but survives, completely unchanged, across every fullscreen<->
// dummy-pip toggle for the SAME session - PlayerScreen's own MpvSurface call
// is never disposed, never recreated, for that transition.
//
// AM (DUMMY_PIP_REAL_SIZE_REVERT) -->
// Dummy pip is NOT a graphicsLayer scale/translate wrapped around a
// fullscreen-sized PlayerScreen - that structure has broken video
// rendering outright twice in this exact codebase (this session alone),
// for two different specific reasons each time, despite being fixed and
// re-verified in between. PlayerScreen renders directly at its own real,
// small size instead - see DUMMY_PIP_REAL_SIZE_REVERT further down for the
// full reasoning. The known tradeoff (mpv's real surface reconfiguration
// cost on every pinch frame) stays unresolved, in exchange for a version
// of this feature that reliably shows video at all.
// <-- AM (DUMMY_PIP_REAL_SIZE_REVERT)
// <-- AM (PLAYER_OVERLAY_MIGRATION)

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Hosted once, at MainActivity's Compose root. Renders the persistent
 * PlayerHostScreen for whatever PlayerMediaHolder.playbackRequestFlow
 * currently holds, or nothing at all if there's no live request.
 */
@Composable
fun PlayerOverlayHost() {
    val request by PlayerMediaHolder.playbackRequestFlow.collectAsStateWithLifecycle()
    val currentRequest = request ?: return
    key(currentRequest) {
        PlayerHostScreen(
            animeId = currentRequest.animeId,
            episodeId = currentRequest.episodeId,
            hosterList = currentRequest.hosterList,
            hosterIndex = currentRequest.hosterIndex,
            videoIndex = currentRequest.videoIndex,
            forceResume = currentRequest.forceResume,
        )
    }
}

private val DUMMY_PIP_EDGE_MARGIN = 16.dp
// AM (DUMMY_PIP_REAL_ASPECT_RATIO_FIX) -->
// Was a hardcoded top-level constant (16f/9f), used for every video
// regardless of its own actual shape - confirmed wrong: real PIP adapts
// its window to the actual video's aspect ratio (same
// stateData.value.videoWidth/videoHeight MainActivity's own real-PIP
// builder already reads for exactly this). Kept here now only as the
// fallback when real dimensions aren't available yet (matching that same
// MainActivity fallback) - the real, used value is computed reactively
// inside the composable itself, from the actual video, not a constant.
private const val DUMMY_PIP_FALLBACK_ASPECT_RATIO = 16f / 9f
// <-- AM (DUMMY_PIP_REAL_ASPECT_RATIO_FIX)
// AM (DUMMY_PIP_ICON_SIZE_FIX) -->
// Was a magic 28 scattered across every icon's own .size() call and every
// hit-test region calculation - confirmed on-device the icons themselves
// looked oversized relative to the window, especially now that the window
// can be meaningfully smaller than before this pass (DUMMY_PIP_MIN_SIZE_FIX/
// DUMMY_PIP_SCREEN_RELATIVE_SIZE_FIX). One named constant so the visual
// size and every hit-test region that depends on it can never drift out of
// sync with each other the way separate magic numbers could.
private const val DUMMY_PIP_ICON_SIZE_DP = 22
// <-- AM (DUMMY_PIP_ICON_SIZE_FIX)
// AM (DUMMY_PIP_CONTROLS_INSET_FIX) -->
// Was flush with the window's own edges (0 padding on every side) -
// confirmed on-device this looked wrong compared to real PIP, whose own
// controls sit inset a bit rather than touching the edges. Applied
// consistently everywhere a control is positioned - the visual padding
// below AND the hit-test math further down, which has to account for the
// same inset or taps would land where the icon used to be, not where it
// actually is now.
private val DUMMY_PIP_CONTROLS_INSET = 6.dp
// <-- AM (DUMMY_PIP_CONTROLS_INSET_FIX)
// AM (DUMMY_PIP_SCREEN_RELATIVE_SIZE_FIX) -->
// Was a fixed 160dp base width with min/max as flat multipliers of it
// (108dp min, up to 2.5x = 400dp max) - confirmed wrong: real PIP's own
// sizing (config_pictureInPictureDefaultSizePercent, source.android.com/
// docs/core/display/pip) is a PERCENTAGE of screen width combined with
// aspect ratio, not a fixed dp value at all - AOSP's 108dp
// (default_minimal_size_pip_resizable_task) is only the documented FLOOR
// that computed percentage-based size must not go below, not the typical
// operating size on a real device. That's why this read smaller than real
// PIP on-device: a flat 160dp/400dp doesn't scale up on a bigger screen
// the way a percentage-of-width does.
//
// These percentages are a reasoned approximation, not AOSP's own exact
// numbers - couldn't retrieve the literal default percentage value from
// source, so this needs real on-device tuning against how real PIP
// actually looks/feels on the same device, same as other first-pass
// constants in this feature. The ratios below (min/max relative to base)
// are what actually matter and stay constant regardless of screen size,
// since both sides of each ratio are percentages of the same
// screenWidthPx - only the resulting absolute pixel sizes now scale with
// the screen, which is the actual fix.
//
// AM (DUMMY_PIP_REAL_AOSP_VALUES_FIX) -->
// The earlier percentages here were a reasoned but ultimately wrong
// approximation - couldn't retrieve AOSP's actual numbers at the time.
// Found them this pass, straight from AOSP source, not estimated:
// frameworks/base/libs/WindowManager/Shell/res/values/config.xml (fetched
// directly via git clone, not a search snippet) defines
// config_pipSystemPreferredDefaultSizePercent = 0.6 (the current, real
// default PIP size - 60% of the max of screen width/height) and
// config_pipSystemPreferredMinimumSizePercent = 0.5, which the file's own
// comment says is applied AS A FRACTION OF THE DEFAULT SIZE ("Min is
// config_pipSystemPreferredMinimumSizePercent of it") - so the real
// minimum is 0.5 * 0.6 = 0.3, not a separately-tuned number. (There's also
// an older config_pictureInPictureDefaultSizePercent = 0.23 in the same
// file, explicitly marked "legacy spec, use
// config_pipSystemPreferredDefaultSizePercent instead" - the values below
// are the current ones, not the legacy ones.) No published max percent
// found in this same source - the existing
// (screenWidthPx - 2*edgeMarginPx)/baseWidthPx clamp further down remains
// what actually stops this from exceeding true screen width.
// <-- AM (DUMMY_PIP_REAL_AOSP_VALUES_FIX)
private const val DUMMY_PIP_BASE_WIDTH_PERCENT = 0.6f
private const val DUMMY_PIP_MIN_WIDTH_PERCENT = 0.3f
private const val DUMMY_PIP_MAX_WIDTH_PERCENT = 1f
private const val MIN_SIZE_SCALE = DUMMY_PIP_MIN_WIDTH_PERCENT / DUMMY_PIP_BASE_WIDTH_PERCENT
private const val MAX_SIZE_SCALE = DUMMY_PIP_MAX_WIDTH_PERCENT / DUMMY_PIP_BASE_WIDTH_PERCENT
// <-- AM (DUMMY_PIP_SCREEN_RELATIVE_SIZE_FIX)

private fun coerceInSafe(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max.coerceAtLeast(min))

@Composable
fun PlayerHostScreen(
    animeId: Long,
    episodeId: Long,
    hosterList: List<Hoster>? = null,
    hosterIndex: Int = -1,
    videoIndex: Int = -1,
    forceResume: Boolean = false,
) {
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    val scope = rememberCoroutineScope()

    var viewModel by remember { mutableStateOf<PlayerViewModel?>(null) }

    // AM (PLAYER_HOST_SCREEN) -->
    // Runs once, at this composable instance's first composition - the
    // key(currentRequest) wrapper in PlayerOverlayHost is what makes "once
    // per distinct request" hold, the same way Voyager's fresh-instance-
    // per-push used to.
    // <-- AM (PLAYER_HOST_SCREEN)
    DisposableEffect(Unit) {
        val existingHolder = PlayerMediaHolder.current
        val existingViewModel = existingHolder?.takeIf { it.hasAdoptedPlayer }?.viewModel
        val existingState = existingViewModel?.stateData?.value

        if (existingViewModel != null && existingState?.currentAnime?.id == animeId) {
            // AM (PLAYER_HOST_SCREEN_EPISODE_SWITCH_FIX) -->
            // Was missing entirely - adopted whatever session was live
            // unconditionally, with no comparison against this screen's
            // own target episodeId at all. Confirmed live on-device
            // (2026-09-06): tapping a different episode after backing
            // out to the episode list just reopened the same episode that
            // was already playing, because nothing here ever called
            // changeEpisode(). Same same-anime/different-episode case
            // PlayerActivity.onNewIntent() already handles - see that
            // function's own LIVE_REDELIVERY_TRUST_FIX doc comment.
            if (existingState.currentEpisode?.id != episodeId) {
                logcat(LogPriority.INFO) {
                    "PLAYER_HOST_SCREEN_EPISODE_SWITCH_FIX changeEpisode() " +
                        "from=${existingState.currentEpisode?.id} to=$episodeId"
                }
                existingViewModel.changeEpisode(episodeId)
            }
            // <-- AM (PLAYER_HOST_SCREEN_EPISODE_SWITCH_FIX)
            logcat(LogPriority.INFO) {
                "PLAYER_HOST_SCREEN reusing existing viewModel=${System.identityHashCode(existingViewModel)} " +
                    "for animeId=$animeId episodeId=$episodeId"
            }
            existingHolder.hasExternalScreenConsumer = true
            // AM (DUMMY_PIP_STALE_ON_REOPEN_FIX) -->
            // This composable's own request superseding any previous one
            // (a new episode opened) always supersedes any dummy pip
            // currently up for this holder too - opening an episode should
            // always land in fullscreen, regardless of what mode the
            // previous session was left in.
            existingHolder.isDummyPipActive = false
            // <-- AM (DUMMY_PIP_STALE_ON_REOPEN_FIX)
            viewModel = existingViewModel
            // AM (PLAYER_OVERLAY_MIGRATION) -->
            // Unconditional now, unlike the old Screen-based version's own
            // guarded form - this composable is never disposed just for
            // entering dummy pip anymore (it stays mounted, visually
            // transformed instead), so onDispose here only ever fires for a
            // genuine end-of-session, exactly like the original pre-dummy-
            // pip behavior this is restoring.
            onDispose { existingHolder.hasExternalScreenConsumer = false }
            // <-- AM (PLAYER_OVERLAY_MIGRATION)
        } else {
            if (existingHolder != null) {
                logcat(LogPriority.INFO) {
                    "PLAYER_HOST_SCREEN tearing down stale holder=${System.identityHashCode(existingHolder)} " +
                        "before originating a fresh session"
                }
                existingHolder.release()
                context.stopService(PlayerBackgroundPlaybackService.newIntent(context))
            }

            val vm = graph.playerViewModelFactory.create(SavedStateHandle())
            logcat(LogPriority.INFO) {
                "PLAYER_HOST_SCREEN constructed fresh viewModel=${System.identityHashCode(vm)} " +
                    "animeId=$animeId episodeId=$episodeId"
            }

            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    val binder = service as? PlayerBackgroundPlaybackService.LocalBinder ?: return
                    val holder = binder.getMediaHolder()
                    holder.hasExternalScreenConsumer = true
                    holder.isDummyPipActive = false
                    vm.bindToService(holder)

                    // AM (PLAYER_HOST_SCREEN_NOTIFICATION_FIX) -->
                    // onStopRequested clears the playback request instead of
                    // PlayerActivity's finish() or the old navigator.pop() -
                    // same intent (ending the whole session), adapted to
                    // this composable's own lifecycle.
                    binder.getService().start(
                        isPlaying = !vm.playbackData.value.paused,
                        mediaSessionToken = holder.mediaSession?.sessionToken,
                        onTogglePlayPause = {
                            if (vm.playbackData.value.paused) vm.unpause() else vm.pause()
                            binder.getService().updatePlaybackState(!vm.playbackData.value.paused)
                        },
                        onStopRequested = {
                            vm.pause()
                            context.stopService(PlayerBackgroundPlaybackService.newIntent(context))
                            PlayerMediaHolder.clearPlaybackRequest()
                        },
                    )
                    // <-- AM (PLAYER_HOST_SCREEN_NOTIFICATION_FIX)

                    scope.launch {
                        val (initResult, loadResult) = vm.init(
                            animeId = animeId,
                            initialEpisodeId = episodeId,
                            hostList = hosterList?.let { it.serialize() } ?: "",
                            hostIndex = hosterIndex,
                            vidIndex = videoIndex,
                        )
                        logcat(LogPriority.INFO) {
                            "PLAYER_HOST_SCREEN init() returned initResult=$initResult loadResult=$loadResult"
                        }
                        vm.loadHosters(
                            hosterList = initResult.hosterList ?: emptyList(),
                            hosterIndex = initResult.videoIndex.first,
                            videoIndex = initResult.videoIndex.second,
                        )
                    }

                    viewModel = vm
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    logcat(LogPriority.WARN) { "PLAYER_HOST_SCREEN onServiceDisconnected" }
                }
            }

            context.startService(PlayerBackgroundPlaybackService.newIntent(context))
            context.bindService(
                PlayerBackgroundPlaybackService.newIntent(context),
                connection,
                android.content.Context.BIND_AUTO_CREATE,
            )

            onDispose { context.unbindService(connection) }
        }
    }

    val currentViewModel = viewModel
    if (currentViewModel == null) {
        LoadingScreen()
        return
    }

    // AM (PIP_AVAILABILITY_MISSING_FIX) -->
    // Same device/preference capability check PlayerActivity does, run once
    // per viewModel identity.
    LaunchedEffect(currentViewModel) {
        currentViewModel.updateHasPip(
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                graph.playerPreferences.enablePip.get(),
        )
    }
    // <-- AM (PIP_AVAILABILITY_MISSING_FIX)

    // AM (SELF_PIP_AUTO_ENTER_FIX) -->
    // Keeps MainActivity.updateAutoEnterPipParams() fresh whenever pause
    // state changes.
    DisposableEffect(currentViewModel) {
        val mainActivity = context as? MainActivity
        val job = scope.launch {
            currentViewModel.playbackData.collect {
                mainActivity?.updateAutoEnterPipParams()
            }
        }
        onDispose { job.cancel() }
    }
    // <-- AM (SELF_PIP_AUTO_ENTER_FIX)

    val holder = PlayerMediaHolder.current
    val isDummyPipActive by (holder?.isDummyPipActiveFlow ?: remember { MutableStateFlow(false) })
        .collectAsStateWithLifecycle()

    val density = LocalDensity.current

    // AM (DUMMY_PIP_CONTROLS_LEAK_FIX) -->
    // PlayerScreen is never disposed for this transition (that's the whole
    // point), so its own controls-visibility state carries straight through
    // unchanged - confirmed on-device, PlayerScreen's own controls (seek
    // bar, big center play/pause, etc.) stayed visible, tiny, underneath
    // the dummy-pip's own controls, both on first entry (whatever
    // uiData.controlsShown happened to be at the moment back was pressed)
    // and again afterward (several PlayerViewModel functions -
    // setSheet/setPanel/setDialog with None, likely others - call
    // showControls() as a side effect unrelated to any tap on the now-tiny
    // fullscreen controls specifically). A single hideControls() call on
    // entry only handles the first case. This actively fights back for the
    // second: any time controls turn back on while dummy pip is active,
    // hide them again immediately.
    //
    // setControlsAndStatusBarShown(..., statusBarShown = true) instead of
    // hideControls() - see that new function's own doc comment on
    // PlayerViewModel for why: hideControls() also hides the OS status/nav
    // bars, which should never happen just because the mini window is
    // hiding its own controls.
    LaunchedEffect(isDummyPipActive, currentViewModel) {
        if (isDummyPipActive) {
            currentViewModel.uiData.collect { uiData ->
                if (uiData.controlsShown || !uiData.statusBarShown) {
                    currentViewModel.setControlsAndStatusBarShown(controlsShown = false, statusBarShown = true)
                }
            }
        }
    }
    // <-- AM (DUMMY_PIP_CONTROLS_LEAK_FIX)

    // AM (DUMMY_PIP_REAL_SIZE_FIX) -->
    // BoxWithConstraints instead of LocalConfiguration.current.screenWidthDp/
    // screenHeightDp - confirmed on-device that using the raw device
    // dimensions broke BOTH fullscreen (stopped actually filling the real
    // screen) and dummy pip (video didn't render at all). Device-level
    // configuration dimensions aren't guaranteed to match the actual
    // available layout space at this exact point in the tree (insets,
    // padding, anything a plain fillMaxSize() would have adapted to
    // automatically) - BoxWithConstraints reports the real constraints
    // Compose itself is using here, the same ones fillMaxSize() would fill,
    // so there's no guessed-vs-actual mismatch.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        // <-- AM (DUMMY_PIP_REAL_SIZE_FIX)
        val edgeMarginPx = with(density) { DUMMY_PIP_EDGE_MARGIN.toPx() }
        // AM (DUMMY_PIP_SCREEN_RELATIVE_SIZE_FIX) -->
        // Was DUMMY_PIP_BASE_WIDTH.toPx() (a fixed 160dp) - see this fix's
        // own doc comment above for why that's wrong. screenWidthPx is
        // already available here (BoxWithConstraints above).
        val baseWidthPx = screenWidthPx * DUMMY_PIP_BASE_WIDTH_PERCENT
        // <-- AM (DUMMY_PIP_SCREEN_RELATIVE_SIZE_FIX)
        val navigationBarHeightPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        // AM (DUMMY_PIP_REAL_ASPECT_RATIO_FIX) -->
        // Same stateData.value.videoWidth/videoHeight MainActivity's own
        // real-PIP aspect-ratio builder already reads - see this fix's own
        // doc comment on the fallback constant above for why a hardcoded
        // 16:9 was wrong. Falls back the same way that code does when
        // dimensions aren't known yet.
        val videoState by currentViewModel.stateData.collectAsStateWithLifecycle()
        val targetDummyPipWindowAspectRatio = if (videoState.videoWidth > 0 && videoState.videoHeight > 0) {
            videoState.videoWidth.toFloat() / videoState.videoHeight.toFloat()
        } else {
            DUMMY_PIP_FALLBACK_ASPECT_RATIO
        }
        // AM (DUMMY_PIP_ASPECT_RATIO_ANIMATE_FIX) -->
        // Animated, not applied directly - real dimensions can arrive after
        // the fallback has already been shown for a moment (video metadata
        // loading), and the aspect ratio can genuinely change between
        // episodes/videos of different shapes. Either way, snapping the
        // window's shape instantly reads as a jarring pop; animating it
        // means both cases settle smoothly from whatever shape was showing
        // into the new one, rather than needing to gate entry on
        // dimensions being known up front (which would just trade one kind
        // of visible discontinuity for a different one - the window not
        // appearing yet at all).
        val dummyPipWindowAspectRatio by animateFloatAsState(
            targetValue = targetDummyPipWindowAspectRatio,
            label = "dummyPipWindowAspectRatio",
        )
        // <-- AM (DUMMY_PIP_ASPECT_RATIO_ANIMATE_FIX)
        // <-- AM (DUMMY_PIP_REAL_ASPECT_RATIO_FIX)

        // AM (DUMMY_PIP_REAL_SIZE_REVERT) -->
        // Back to rendering PlayerScreen at its own real, small size
        // directly - no graphicsLayer scale, no requiredSize/clip trick.
        // That approach has now broken video rendering outright twice in
        // this exact codebase (this session alone), for two different
        // specific reasons each time, despite being fixed and re-verified
        // in between - a real, repeated instability, not two unlucky
        // guesses. Across every single attempt this whole feature has ever
        // made, real-size rendering is the ONLY approach that has actually
        // rendered video correctly every time it's been tried. The known,
        // honest tradeoff this carries - mpv's real surface reconfiguration
        // cost on every pinch frame - stays unresolved, but a working
        // feature with an imperfect resize is a better place to stand than
        // a broken one, and every other gesture-tracking fix found since
        // (anchor compensation, coordinate-space correction, fraction
        // clamping, lag-free positioning, consume ordering) still applies
        // regardless of which rendering approach sits underneath them.
        var sizeScale by remember(currentViewModel) { mutableFloatStateOf(1f) }
        val dummyPipWidthPx = baseWidthPx * sizeScale
        val dummyPipHeightPx = dummyPipWidthPx / dummyPipWindowAspectRatio
        // <-- AM (DUMMY_PIP_REAL_SIZE_REVERT)

        var offsetX by remember(currentViewModel) { mutableFloatStateOf(screenWidthPx - dummyPipWidthPx - edgeMarginPx) }
        // AM (DUMMY_PIP_NAV_BAR_OVERLAP_FIX) -->
        // Was screenHeightPx - dummyPipHeightPx - edgeMarginPx - didn't
        // account for navigationBarHeightPx at all, so the window's
        // initial position placed it right at the physical bottom edge of
        // the screen, behind the nav bar/gesture pill rather than above it.
        // The dismiss-threshold check elsewhere in this file already
        // correctly subtracts navigationBarHeightPx - this is the same
        // adjustment, just applied to where the window starts, not just
        // where it's allowed to be dragged down to.
        var offsetY by remember(currentViewModel) {
            mutableFloatStateOf(screenHeightPx - dummyPipHeightPx - edgeMarginPx - navigationBarHeightPx)
        }
        // <-- AM (DUMMY_PIP_NAV_BAR_OVERLAP_FIX)
        var dragOffsetY by remember(currentViewModel) { mutableFloatStateOf(0f) }
        // AM (DUMMY_PIP_DRAG_RESPONSIVENESS_FIX) -->
        // See this flag's own use in the animateFloatAsState calls below for
        // why it exists.
        var isDragging by remember(currentViewModel) { mutableStateOf(false) }
        // <-- AM (DUMMY_PIP_DRAG_RESPONSIVENESS_FIX)

        // AM (DUMMY_PIP_DRAG_RESPONSIVENESS_FIX) -->
        // Confirmed on-device: animating position continuously during an
        // active drag (retargeting 60+ times a second) produced a visibly
        // damped, laggy trajectory that read as "can't move it" - not a
        // functional break, just too much animation smoothing fighting a
        // live finger. snap() while isDragging is true makes these track
        // the raw value immediately (1:1 with the finger) during an active
        // gesture, and only actually animates for discrete moments - mode
        // entry/exit and settling into place once the gesture ends.
        // AM (DUMMY_PIP_FLOATY_SPRING_FIX) -->
        // Default spring<Float>() (critically damped, medium stiffness) felt
        // abrupt/jarring snapping to an edge, compared to YT's softer,
        // bouncier settle. Lower stiffness (slower) + a bit of bounce
        // (dampingRatio below 1) - tune further on-device against an actual
        // thumb, this is a first-pass feel, not a measured match.
        val positionAnimationSpec = if (isDragging) {
            snap<Float>()
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        }
        // <-- AM (DUMMY_PIP_FLOATY_SPRING_FIX)
        val animatedOffsetX by animateFloatAsState(
            targetValue = if (isDummyPipActive) offsetX else 0f,
            animationSpec = positionAnimationSpec,
            label = "dummyPipOffsetX",
        )
        val animatedOffsetY by animateFloatAsState(
            targetValue = if (isDummyPipActive) offsetY + dragOffsetY else 0f,
            animationSpec = positionAnimationSpec,
            label = "dummyPipOffsetY",
        )
        // AM (DUMMY_PIP_DRAG_LAG_FIX) -->
        // animateFloatAsState - even with snap() as its spec - still takes
        // one frame to propagate a changed target into its displayed value;
        // that's fine for a single discrete change, but wrong for a value
        // being retargeted continuously, every frame, during an active
        // gesture - each new target arrives before the previous one even
        // finishes "snapping", so the lag compounds into a constant,
        // perceptible one-frame-behind trail rather than 1:1 tracking.
        // Confirmed on-device: the window visibly moved slower than the
        // actual pinch/drag midpoint. displayOffsetX/Y read the raw,
        // un-animated value directly while isDragging is true (zero
        // latency - no animation step involved at all), and fall back to
        // the animated value only once the gesture ends, for the settle-
        // into-place transition. All three .offset{} call sites in this
        // file use this, not animatedOffsetX/Y directly, so they stay
        // consistent with each other either way - that part of an earlier
        // attempt at this (git history) was correct, just paired with the
        // wrong value source.
        val displayOffsetX = if (isDragging) offsetX else animatedOffsetX
        val displayOffsetY = if (isDragging) offsetY + dragOffsetY else animatedOffsetY
        // <-- AM (DUMMY_PIP_DRAG_LAG_FIX)
        // <-- AM (DUMMY_PIP_DRAG_RESPONSIVENESS_FIX)

        // AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX) -->
        // Hoisted above the video Box (was declared further down, where it's
        // still also used) - both the video Box and the controls Box below
        // need to read it for the reveal-grow effect, so it has to be
        // visible to both rather than scoped inside just one of them.
        //
        // Real PIP grows the window a bit when its controls are revealed if
        // it's currently small - this is a purely visual, paint-time
        // graphicsLayer scale applied to BOTH boxes (never a real .size()
        // change) specifically because this can fire on every single tap,
        // not just during a deliberate resize gesture - a real layout
        // change that often would be a far worse, far more frequent cost
        // than anything the resize gesture itself pays. sizeScale itself
        // never changes here; both boxes just paint themselves slightly
        // larger, still positioned/sized identically to each other since
        // they share the same source values.
        var dummyPipControlsShown by remember(currentViewModel) { mutableStateOf(true) }
        // AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX) -->
        // Setting dummyPipControlsShown = true again while it's already
        // true doesn't restart the auto-hide LaunchedEffect below, since
        // its key value hasn't actually changed - a tap while controls are
        // already shown wouldn't reset the 3-second timer without this.
        // Incremented on every reveal/refresh (see that LaunchedEffect and
        // the tap-resolution logic further down), used purely as an extra
        // key to force the effect to restart.
        var controlsShownRevision by remember(currentViewModel) { mutableIntStateOf(0) }
        // <-- AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX)
        // AM (DUMMY_PIP_REVEAL_OFFSCREEN_FIX) -->
        // Confirmed real bug on-device: growing centered (graphicsLayer's
        // default TransformOrigin) pushes the window half-off whichever
        // edge it's already sitting flush against, since the default
        // starting position is a screen corner - growth in every direction
        // from a box already touching an edge has nowhere to go but past
        // it. Anchoring growth at whichever edge the window's own center is
        // currently closest to means it only ever grows back toward the
        // screen's center, never further past the edge it's already near -
        // not a perfect guarantee for every possible position, but correct
        // for the common case (a corner-docked window, which is the
        // default and the position dragging naturally settles to).
        val revealTransformOriginX = if (offsetX + dummyPipWidthPx / 2f > screenWidthPx / 2f) 1f else 0f
        val revealTransformOriginY = if (offsetY + dummyPipHeightPx / 2f > screenHeightPx / 2f) 1f else 0f
        // <-- AM (DUMMY_PIP_REVEAL_OFFSCREEN_FIX)
        val controlsRevealTargetScale = if (isDummyPipActive && dummyPipControlsShown && sizeScale < 1f) {
            // AM (DUMMY_PIP_REVEAL_OFFSCREEN_FIX) -->
            // Cap lowered from 1.3x to 1.15x - a smaller bump is less likely
            // to overflow even with the edge-anchoring above, and reduces
            // how jarring the grow itself looks regardless.
            (1f / sizeScale).coerceAtMost(1.15f)
            // <-- AM (DUMMY_PIP_REVEAL_OFFSCREEN_FIX)
        } else {
            1f
        }
        val animatedControlsRevealScale by animateFloatAsState(
            targetValue = controlsRevealTargetScale,
            label = "dummyPipControlsRevealScale",
        )
        // <-- AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX)

        // AM (DUMMY_PIP_REAL_SIZE_REVERT) -->
        // No scale transform, no requiredSize/clip trick, no crop math -
        // see this fix's own doc comment higher up for why. The window is
        // just a real, small, positioned Box, and PlayerScreen renders
        // directly inside it at that real size.
        Box(
            modifier = if (isDummyPipActive) {
                Modifier
                    .size(
                        width = with(density) { dummyPipWidthPx.toDp() },
                        height = with(density) { dummyPipHeightPx.toDp() },
                    )
                    .offset { IntOffset(displayOffsetX.roundToInt(), displayOffsetY.roundToInt()) }
                    // AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX) -->
                    // Before clip, not after - graphicsLayer here wraps
                    // clip in the modifier chain, so the clip's own rounded-
                    // corner region gets scaled up together with the
                    // content inside it, rather than clipping the grown
                    // content back down to the original small bounds (which
                    // is what happens if clip sits outside/after a scale
                    // instead - confirmed the hard way earlier in this
                    // feature's history, a different but related mistake).
                    .graphicsLayer {
                        scaleX = animatedControlsRevealScale
                        scaleY = animatedControlsRevealScale
                        transformOrigin = TransformOrigin(revealTransformOriginX, revealTransformOriginY)
                    }
                    // <-- AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX)
                    .clip(RoundedCornerShape(8.dp))
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            PlayerScreen(
                viewModel = currentViewModel,
                onBack = { PlayerMediaHolder.clearPlaybackRequest() },
                onEnterDummyPip = {
                    holder?.isDummyPipActive = true
                    // AM (DUMMY_PIP_STALE_AUTO_ENTER_FIX) -->
                    // Without this, the OS keeps whatever auto-enter
                    // registration was last pushed before this exact
                    // moment until the next pause state change happens
                    // to trigger a fresh one - which may never come
                    // before the user actually leaves the app.
                    (context as? MainActivity)?.updateAutoEnterPipParams()
                    // <-- AM (DUMMY_PIP_STALE_AUTO_ENTER_FIX)
                },
            )
        }
        // <-- AM (DUMMY_PIP_REAL_SIZE_REVERT)

        // AM (DUMMY_PIP) -->
        // Controls + gesture layer for dummy pip, stacked on top of the
        // (real size, otherwise completely unchanged and still live)
        // PlayerScreen above. Consuming touches here shadows PlayerScreen's
        // own now-tiny controls from ever receiving them - deliberate, not
        // an oversight: dummy pip needs its own appropriately-sized
        // controls, not a miniature of the fullscreen ones.
        // <-- AM (DUMMY_PIP)
        if (isDummyPipActive) {
            val playbackData by currentViewModel.playbackData.collectAsStateWithLifecycle()
            val isPaused = playbackData.paused

            // AM (DUMMY_PIP_AUTO_HIDE_CONTROLS_FIX) -->
            // Real PIP shows its controls briefly then hides them, regardless
            // of playback state - shown on entry and on any touch (see the
            // gesture loop's touch-down handling below), hidden after a few
            // seconds of no interaction either way.
            //
            // AM (DUMMY_PIP_HIDE_WHILE_PAUSED_FIX) -->
            // Was gated behind !isPaused - controls stayed on screen
            // indefinitely whenever paused instead of auto-hiding like real
            // PIP does regardless of playback state. Removed the gate
            // entirely rather than special-case it.
            //
            // AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX) -->
            // dummyPipControlsShown itself is now declared above the video
            // Box, not here - see that fix's own doc comment for why.
            // <-- AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX)
            LaunchedEffect(dummyPipControlsShown, controlsShownRevision) {
                if (dummyPipControlsShown) {
                    delay(3000)
                    dummyPipControlsShown = false
                }
            }
            // <-- AM (DUMMY_PIP_HIDE_WHILE_PAUSED_FIX)
            // <-- AM (DUMMY_PIP_AUTO_HIDE_CONTROLS_FIX)

            // AM (DUMMY_PIP_DISMISS_SEMANTICS_FIX) -->
            // Was two different behaviors (X = full release()+stopService(),
            // swipe = leave playing) - neither was actually what was wanted.
            // Both the X button and swipe-to-dismiss now do the same thing:
            // pause and hide the floating window, but keep the session
            // (holder, service, notification) alive and resumable - a real,
            // explicit stop was never the intent for either one, same
            // reasoning as the original X/headphones scoping (this isn't a
            // media-app-style "close means done" surface).
            fun dismissAndPause() {
                currentViewModel.pause()
                holder?.isDummyPipActive = false
                holder?.hasExternalScreenConsumer = false
                PlayerMediaHolder.clearPlaybackRequest()
            }
            // <-- AM (DUMMY_PIP_DISMISS_SEMANTICS_FIX)

            fun expandToFullscreen() {
                holder?.isDummyPipActive = false
            }

            // AM (DUMMY_PIP_HEADPHONES_FIX) -->
            // Same as dismissAndPause() minus the pause - confirmed against
            // this codebase's own existing architecture (PlayerActivity's
            // startBackgroundPlayback()/its own comments: the notification
            // is already always-on while playing, bound to the session for
            // its whole lifetime regardless of any UI's visibility), not
            // guessed blind: clearing the dummy-pip UI while leaving
            // playback running is already exactly what "background play"
            // means in this app - there's no separate mechanism to trigger,
            // just not tearing down what's already there. Deliberately not
            // using moveTaskToBack() - see this codebase's own history
            // (DUMMY_PIP_STUCK_FRAME investigation) for why that was
            // confirmed unsafe while PIP-pinned.
            fun enterBackgroundPlay() {
                holder?.isDummyPipActive = false
                holder?.hasExternalScreenConsumer = false
                PlayerMediaHolder.clearPlaybackRequest()
            }
            // <-- AM (DUMMY_PIP_HEADPHONES_FIX)

            Box(
                modifier = Modifier
                    // AM (DUMMY_PIP_LIVE_SCALE_FIX) -->
                    // sizeScale is the one, direct, live value now (no more
                    // pending/committed split - see its own doc comment
                    // higher up for why updating it every frame is cheap
                    // again under this structure). This box hosts the
                    // gesture below, so its own bounds need to track that
                    // live value exactly, same reasoning as before this
                    // fix, just against a simpler source.
                    .size(
                        width = with(density) { dummyPipWidthPx.toDp() },
                        height = with(density) { dummyPipHeightPx.toDp() },
                    )
                    // <-- AM (DUMMY_PIP_LIVE_SCALE_FIX)
                    .offset { IntOffset(displayOffsetX.roundToInt(), displayOffsetY.roundToInt()) }
                    // AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX) -->
                    // Same scale as the video Box, so both stay visually
                    // aligned with each other - see that Box's own doc
                    // comment for the full reasoning. Note this is a purely
                    // visual/paint-time transform - it does NOT change this
                    // Box's own hit-testing bounds, which the tap-detection
                    // further down still measures against. The bump is
                    // modest (capped at 1.3x) so this should stay close
                    // enough to line up in practice, but exact alignment
                    // during the brief grown state is unverified without a
                    // device.
                    .graphicsLayer {
                        scaleX = animatedControlsRevealScale
                        scaleY = animatedControlsRevealScale
                        transformOrigin = TransformOrigin(revealTransformOriginX, revealTransformOriginY)
                    }
                    // <-- AM (DUMMY_PIP_CONTROLS_REVEAL_GROW_FIX)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Transparent)
                    .pointerInput(currentViewModel) {
                        val touchSlop = viewConfiguration.touchSlop
                        val closeSizePx = with(density) { DUMMY_PIP_ICON_SIZE_DP.dp.toPx() }
                        // AM (DUMMY_PIP_CONTROLS_INSET_FIX) -->
                        // The same inset applied to every control's own
                        // visual padding - see that fix's own doc comment
                        // higher up. Every hit-test region below shifts by
                        // this amount on whichever side(s) it borders the
                        // window's own edge, matching where the icon
                        // actually is now, not where it used to sit flush.
                        val insetPx = with(density) { DUMMY_PIP_CONTROLS_INSET.toPx() }
                        // <-- AM (DUMMY_PIP_CONTROLS_INSET_FIX)
                        // AM (DUMMY_PIP_CONTROLS_SPACING_FIX) -->
                        // Widened to account for the new spacedBy(16.dp)
                        // between the three bottom-row icons - was a plain
                        // icon-size*3 assuming zero gap between them.
                        val bottomRowHeightPx = with(density) { (DUMMY_PIP_ICON_SIZE_DP + 2).dp.toPx() }
                        val bottomRowWidthPx = with(density) { (DUMMY_PIP_ICON_SIZE_DP * 3 + 16 * 2).dp.toPx() }
                        // <-- AM (DUMMY_PIP_CONTROLS_SPACING_FIX)
                        // AM (DUMMY_PIP_HEADPHONES_FIX) -->
                        // The expand+close Row's own total width - both
                        // icons now share the TopEnd corner instead of
                        // expand sitting alone on TopStart.
                        val topEndRowWidthPx = with(density) { (DUMMY_PIP_ICON_SIZE_DP * 2 + 4).dp.toPx() }
                        // <-- AM (DUMMY_PIP_HEADPHONES_FIX)

                        awaitEachGesture {
                            var isResizing = false
                            var hasExceededSlop = false
                            var accumulatedMovement = 0f
                            var downPosition: Offset? = null
                            // AM (DUMMY_PIP_ZOOM_DEADZONE_FIX) -->
                            // Tracks the true, uncommitted zoom ratio every
                            // frame (cheap - just multiplication) separately
                            // from sizeScale, which only actually commits
                            // once the accumulated change since the last
                            // commit is large enough to matter - see this
                            // var's own use further down for why. Unlike the
                            // earlier pending/committed split this feature
                            // tried before (git history), nothing else reads
                            // this - the touch-region sizing and the video's
                            // real layout both still only ever look at
                            // sizeScale itself, so there's no separate state
                            // for them to fall out of sync with, just a
                            // deliberate delay on how often sizeScale itself
                            // changes.
                            var pendingZoomRatio = 1f
                            // <-- AM (DUMMY_PIP_ZOOM_DEADZONE_FIX)
                            dragOffsetY = 0f
                            isDragging = true
                            // AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX) -->
                            // Was dummyPipControlsShown = true here,
                            // unconditionally, on every touch-down -
                            // meaning controls appeared for a drag or the
                            // start of a resize too, not just an actual
                            // tap/click. Real PIP only reveals controls on
                            // a genuine tap. Captured here, before anything
                            // in this gesture can change it, so the
                            // tap-resolution logic further down can tell
                            // whether controls were already visible before
                            // this specific gesture, or need to be revealed
                            // as this gesture's own result.
                            val controlsWereAlreadyShown = dummyPipControlsShown
                            // <-- AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX)

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }

                                // AM (DUMMY_PIP_SIBLING_GESTURE_LEAK_FIX) -->
                                // Consumed unconditionally, from the very
                                // first event of the gesture, single- or
                                // multi-pointer - not gated behind touch slop
                                // like the earlier PIP_CONTROLS_TOUCH_STEAL_FIX
                                // version. That version was fixing a real bug
                                // (this box's own pointerInput, an ancestor of
                                // its own IconButtons, was consuming before
                                // those children got a chance) by deferring
                                // consumption - but PlayerScreen's own
                                // GestureHandler (swipe-to-seek/volume/
                                // brightness) sits in a completely separate
                                // sibling subtree underneath this one, not a
                                // child of it, and independently sees the same
                                // raw, unconsumed events during that deferred
                                // window regardless of what this box does
                                // later - confirmed on-device, its swipe
                                // gestures fired while dragging the dummy pip.
                                // Consuming late can't retroactively stop a
                                // sibling that already reacted. Button taps
                                // are hit-tested manually below, on release,
                                // instead of relying on IconButton's own click
                                // detection ever seeing an unconsumed event -
                                // there's no timing that gives both this box's
                                // buttons AND blocks the sibling at once, since
                                // consumption is a single shared flag every
                                // reader sees identically.
                                //
                                // AM (DUMMY_PIP_CONSUME_ORDER_FIX) -->
                                // Moved to the END of this iteration, after
                                // both branches below have read whatever they
                                // need - PointerInputChange.positionChange()
                                // returns Offset.Zero once a change has
                                // already been consumed. Consuming at the TOP
                                // of the loop (the original version) meant
                                // change.positionChange() in the single-
                                // pointer branch always read back zero,
                                // every single time - confirmed on-device:
                                // accumulatedMovement never grew, hasExceededSlop
                                // never flipped, so every drag attempt fell
                                // through to the tap-to-expand branch on
                                // release instead. Consuming late still fully
                                // blocks the sibling (nothing reads this same
                                // PointerInputChange after this point in the
                                // frame), it just has to happen after this
                                // box's own reads, not before them.
                                // <-- AM (DUMMY_PIP_CONSUME_ORDER_FIX)
                                // <-- AM (DUMMY_PIP_SIBLING_GESTURE_LEAK_FIX)

                                if (pressed.size >= 2) {
                                    // AM (DUMMY_PIP_HIDE_ON_RESIZE_FIX) -->
                                    // Real PIP hides its controls the moment
                                    // a resize starts. isResizing is still
                                    // false on the very first frame a second
                                    // pointer lands (only flips true right
                                    // below), so this only fires once, right
                                    // as resizing genuinely begins - not on
                                    // every subsequent resize frame.
                                    if (!isResizing) {
                                        dummyPipControlsShown = false
                                    }
                                    // <-- AM (DUMMY_PIP_HIDE_ON_RESIZE_FIX)
                                    isResizing = true
                                    hasExceededSlop = true

                                    // AM (DUMMY_PIP_OFFICIAL_GESTURE_MATH_FIX) -->
                                    // Was hand-rolled: distance = hypot(p1-p2),
                                    // centroid = average of exactly 2 points,
                                    // manually tracked previousPinchDistance/
                                    // previousCentroid to compute deltas frame
                                    // to frame. That reimplementation is
                                    // exactly what these three calls already
                                    // do, correctly, as documented, official
                                    // Compose APIs (developer.android.com's
                                    // own "Understand gestures" page names
                                    // them specifically as the sanctioned
                                    // tool for this exact situation - needing
                                    // raw pointer access for tap/dismiss
                                    // logic alongside transform math, where
                                    // detectTransformGestures alone doesn't
                                    // give enough control). They read each
                                    // PointerInputChange's own previous vs.
                                    // current position internally - Compose
                                    // already tracks that per change - so
                                    // there's no separate "previous" state to
                                    // maintain by hand at all, and no
                                    // coordinate-space or centroid-math bugs
                                    // to reintroduce, since this is the same
                                    // code every standard Compose pinch-zoom
                                    // implementation already relies on.
                                    val centroid = event.calculateCentroid()
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()

                                    // AM (DUMMY_PIP_ZOOM_DEADZONE_FIX) -->
                                    // Accumulates every frame regardless
                                    // (cheap), but the actual real-size
                                    // commit below (the expensive part - a
                                    // genuine relayout + mpv surface
                                    // reconfiguration) only happens once the
                                    // accumulated change since the last
                                    // commit exceeds 1.5% - small enough
                                    // that individual size steps should
                                    // still read as smooth, large enough to
                                    // meaningfully cut how often that real
                                    // cost is paid during a continuous
                                    // pinch. This exact number needs real
                                    // on-device tuning against an actual
                                    // thumb, same as every first-pass
                                    // constant in this feature - it is a
                                    // starting point, not a measured answer.
                                    if (zoom != 1f) {
                                        pendingZoomRatio *= zoom
                                    }
                                    if (abs(pendingZoomRatio - 1f) >= 0.015f) {
                                        val committedZoom = pendingZoomRatio
                                        pendingZoomRatio = 1f
                                        // <-- AM (DUMMY_PIP_ZOOM_DEADZONE_FIX)
                                        val maxAllowedSizeScale = minOf(
                                            MAX_SIZE_SCALE,
                                            (screenWidthPx - 2 * edgeMarginPx) / baseWidthPx,
                                        )

                                        // AM (DUMMY_PIP_PINCH_ANCHOR_FIX) -->
                                        // Confirmed on-device: resizing alone
                                        // (without this) visibly "jumped" the
                                        // window relative to the fingers,
                                        // read as jitter/not-following-the-
                                        // midpoint - because the size change
                                        // itself was never compensated for.
                                        // Standard zoom-anchored-at-a-point
                                        // math: capture where the centroid
                                        // currently sits as a fraction of the
                                        // window's own size BEFORE resizing,
                                        // then reposition after resizing so
                                        // that same fraction still lands
                                        // under the centroid. centroid here
                                        // is already correctly box-local
                                        // (calculateCentroid() operates on
                                        // this box's own PointerInputChanges),
                                        // and already clamped against the
                                        // exact bug this feature hit before
                                        // (a natural pinch on a window this
                                        // small often has fingers spread
                                        // wider than the box itself).
                                        val oldWidthPx = baseWidthPx * sizeScale
                                        val oldHeightPx = oldWidthPx / dummyPipWindowAspectRatio
                                        val fractionX = if (oldWidthPx > 0f) {
                                            (centroid.x / oldWidthPx).coerceIn(0f, 1f)
                                        } else {
                                            0.5f
                                        }
                                        val fractionY = if (oldHeightPx > 0f) {
                                            (centroid.y / oldHeightPx).coerceIn(0f, 1f)
                                        } else {
                                            0.5f
                                        }

                                        sizeScale = coerceInSafe(sizeScale * committedZoom, MIN_SIZE_SCALE, maxAllowedSizeScale)

                                        val newWidthPx = baseWidthPx * sizeScale
                                        val newHeightPx = newWidthPx / dummyPipWindowAspectRatio
                                        offsetX -= fractionX * (newWidthPx - oldWidthPx)
                                        offsetY -= fractionY * (newHeightPx - oldHeightPx)
                                        // <-- AM (DUMMY_PIP_PINCH_ANCHOR_FIX)
                                    }

                                    // AM (DUMMY_PIP_PAN_WHILE_RESIZE_FIX) -->
                                    // On top of the anchor compensation above
                                    // (which only keeps the window's own
                                    // growth from drifting relative to the
                                    // fingers), this is the actual
                                    // repositioning from the user moving both
                                    // fingers together - pan is already the
                                    // correct per-frame delta (see this
                                    // fix's own doc comment above), so no
                                    // separate previousCentroid bookkeeping
                                    // is needed to compute it.
                                    offsetX += pan.x
                                    offsetY += pan.y
                                    // <-- AM (DUMMY_PIP_PAN_WHILE_RESIZE_FIX)
                                    // <-- AM (DUMMY_PIP_OFFICIAL_GESTURE_MATH_FIX)
                                    pressed.forEach { it.consume() }
                                } else if (pressed.size == 1 && !isResizing) {
                                    val change = pressed[0]
                                    if (downPosition == null) downPosition = change.position
                                    val delta = change.positionChange()
                                    if (!hasExceededSlop) {
                                        accumulatedMovement += hypot(delta.x, delta.y)
                                        if (accumulatedMovement > touchSlop) hasExceededSlop = true
                                    }
                                    if (hasExceededSlop) {
                                        // AM (DUMMY_PIP_LIVE_CLAMP_FIX) -->
                                        // Left unclamped during the live drag
                                        // - it can go off-screen while
                                        // dragging, same as real PIP - and the
                                        // end-of-gesture snap/clamp below
                                        // pulls it back once the finger lifts,
                                        // instead of fighting the drag itself.
                                        offsetX += delta.x
                                        // <-- AM (DUMMY_PIP_LIVE_CLAMP_FIX)
                                        dragOffsetY += delta.y
                                    }
                                    change.consume()
                                }

                                if (event.changes.none { it.pressed }) break
                            }

                            // AM (DUMMY_PIP_ZOOM_DEADZONE_FIX) -->
                            // Unconditional final flush - the gesture always
                            // ends at the true, precise pinch result the
                            // user actually released at, never up to 1.5%
                            // short because the last bit of movement never
                            // crossed the dead-zone threshold above.
                            if (isResizing && pendingZoomRatio != 1f) {
                                val maxAllowedSizeScale = minOf(
                                    MAX_SIZE_SCALE,
                                    (screenWidthPx - 2 * edgeMarginPx) / baseWidthPx,
                                )
                                sizeScale = coerceInSafe(sizeScale * pendingZoomRatio, MIN_SIZE_SCALE, maxAllowedSizeScale)
                                pendingZoomRatio = 1f
                            }
                            // <-- AM (DUMMY_PIP_ZOOM_DEADZONE_FIX)

                            val liveWidthPx = baseWidthPx * sizeScale
                            val liveHeightPx = liveWidthPx / dummyPipWindowAspectRatio

                            // AM (DUMMY_PIP_SIBLING_GESTURE_LEAK_FIX) -->
                            // Manual tap hit-testing, since button taps can no
                            // longer rely on IconButton's own click detection
                            // ever seeing an unconsumed event (see this fix's
                            // own doc comment above).
                            val tapPosition = downPosition
                            isDragging = false
                            if (!isResizing && !hasExceededSlop && tapPosition != null) {
                                // AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX) -->
                                // If controls weren't already visible before
                                // this exact tap, this tap's only job is to
                                // reveal them - never also fire whatever
                                // button happens to be underneath where the
                                // (invisible, until this tap) icon would be.
                                // Confirmed real bug on-device: buttons were
                                // hit-tested unconditionally regardless of
                                // dummyPipControlsShown, so a first tap that
                                // happened to land on, say, the close
                                // button's region would both reveal controls
                                // AND dismiss the window in the same tap.
                                if (!controlsWereAlreadyShown) {
                                    dummyPipControlsShown = true
                                    controlsShownRevision++
                                } else {
                                    when {
                                        // <-- AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX)
                                        // AM (DUMMY_PIP_HEADPHONES_FIX) -->
                                        // TopStart - headphones/background-play,
                                        // where the expand icon used to sit
                                        // alone.
                                        // AM (DUMMY_PIP_CONTROLS_INSET_FIX) -->
                                        // +insetPx on the far boundary to
                                        // match the icon's new inset
                                        // position - left generous on the
                                        // near-edge side rather than also
                                        // shrinking it, since a slightly
                                        // larger tap target near the edge
                                        // doesn't hurt.
                                        tapPosition.x <= closeSizePx + insetPx && tapPosition.y <= closeSizePx + insetPx -> {
                                            enterBackgroundPlay()
                                        }
                                        // <-- AM (DUMMY_PIP_CONTROLS_INSET_FIX)
                                        // <-- AM (DUMMY_PIP_HEADPHONES_FIX)
                                        // AM (DUMMY_PIP_TAP_TO_EXPAND_REMOVED_FIX) -->
                                        // Left half of the TopEnd icon Row - see
                                        // DUMMY_PIP_HEADPHONES_FIX's own doc
                                        // comment on why expand moved to share
                                        // this corner with close instead of
                                        // sitting on TopStart.
                                        tapPosition.x >= liveWidthPx - topEndRowWidthPx - insetPx &&
                                            tapPosition.x <= liveWidthPx - topEndRowWidthPx - insetPx + closeSizePx &&
                                            tapPosition.y <= closeSizePx + insetPx -> {
                                            expandToFullscreen()
                                        }
                                        // <-- AM (DUMMY_PIP_TAP_TO_EXPAND_REMOVED_FIX)
                                        tapPosition.x >= liveWidthPx - closeSizePx - insetPx && tapPosition.y <= closeSizePx + insetPx -> {
                                            dismissAndPause()
                                        }
                                        tapPosition.y >= liveHeightPx - bottomRowHeightPx - insetPx &&
                                            tapPosition.x >= (liveWidthPx - bottomRowWidthPx) / 2f &&
                                            tapPosition.x <= (liveWidthPx + bottomRowWidthPx) / 2f -> {
                                            val thirdWidthPx = bottomRowWidthPx / 3f
                                            val rowStartX = (liveWidthPx - bottomRowWidthPx) / 2f
                                            when (((tapPosition.x - rowStartX) / thirdWidthPx).toInt().coerceIn(0, 2)) {
                                                0 -> currentViewModel.nextEpisode(next = false)
                                                // AM (DUMMY_PIP_STALE_CLOSURE_FIX) -->
                                                // Was `if (isPaused) ...` - isPaused
                                                // is a val captured once by this
                                                // gesture coroutine's closure, which
                                                // (keyed stably on currentViewModel)
                                                // is NOT relaunched on every
                                                // recomposition, so that capture
                                                // never updated after the coroutine
                                                // first started. Confirmed on-device:
                                                // the pause button worked once, then
                                                // did nothing on subsequent taps,
                                                // since it kept reading the same
                                                // stale value forever. Reading the
                                                // live value directly at the moment
                                                // of the tap instead.
                                                1 -> if (currentViewModel.playbackData.value.paused) {
                                                    currentViewModel.unpause()
                                                } else {
                                                    currentViewModel.pause()
                                                }
                                                // <-- AM (DUMMY_PIP_STALE_CLOSURE_FIX)
                                                else -> currentViewModel.nextEpisode(next = true)
                                            }
                                        }
                                        // AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX) -->
                                        // A plain tap elsewhere while controls
                                        // were already shown just refreshes
                                        // the auto-hide timer (controlsShownRevision
                                        // forces LaunchedEffect to restart even
                                        // though dummyPipControlsShown's own
                                        // value isn't changing).
                                        else -> controlsShownRevision++
                                        // <-- AM (DUMMY_PIP_TAP_ONLY_CONTROLS_FIX)
                                    }
                                }
                                // <-- AM (DUMMY_PIP_TAP_TO_EXPAND_REMOVED_FIX)
                            } else if (
                                // AM (DUMMY_PIP_DISMISS_THRESHOLD_FIX) -->
                                // Was drag-distance/speed based (dragOffsetY
                                // past a fixed fraction of screen height) -
                                // meant a fast or far swipe anywhere could
                                // dismiss even nowhere near actually leaving
                                // the screen. Now position-based: only
                                // dismisses if the window's bottom edge, at
                                // release, has actually reached down into
                                // where the navigation bar area is - the
                                // system nav bar doesn't show while dummy pip
                                // is up, but its normal screen region still
                                // does, and that's the real "off the bottom
                                // of the screen" boundary a user would expect,
                                // not an arbitrary distance/speed threshold.
                                !isResizing && hasExceededSlop &&
                                (offsetY + dragOffsetY + liveHeightPx) >= (screenHeightPx - navigationBarHeightPx)
                                // <-- AM (DUMMY_PIP_DISMISS_THRESHOLD_FIX)
                            ) {
                                dismissAndPause()
                            } else if (isResizing || hasExceededSlop) {
                                val settledOffsetY = if (isResizing) offsetY else offsetY + dragOffsetY
                                val centerX = offsetX + liveWidthPx / 2f
                                offsetX = if (centerX < screenWidthPx / 2f) {
                                    edgeMarginPx
                                } else {
                                    screenWidthPx - liveWidthPx - edgeMarginPx
                                }
                                // AM (DUMMY_PIP_NAV_BAR_OVERLAP_FIX) -->
                                // Same navigationBarHeightPx subtraction as
                                // the initial position - without it, settling
                                // after a drag could still pull the window
                                // down behind the nav bar even though it
                                // never starts there now.
                                offsetY = coerceInSafe(
                                    settledOffsetY,
                                    edgeMarginPx,
                                    screenHeightPx - liveHeightPx - edgeMarginPx - navigationBarHeightPx,
                                )
                                // <-- AM (DUMMY_PIP_NAV_BAR_OVERLAP_FIX)
                            }
                            dragOffsetY = 0f
                        }
                    },
            ) {
                // AM (DUMMY_PIP_AUTO_HIDE_CONTROLS_FIX) -->
                // Visual only - hit-testing above stays active regardless,
                // so a tap while hidden still works and also resets
                // dummyPipControlsShown to true (touch-down handling further
                // up), matching real PIP's "tap reveals, then re-hides after
                // a few seconds" behavior.
                if (dummyPipControlsShown) {
                    // AM (DUMMY_PIP_HEADPHONES_FIX) -->
                    // Where the expand icon used to sit alone - see
                    // enterBackgroundPlay()'s own doc comment for what this
                    // actually does.
                    IconButton(
                        onClick = {},
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(DUMMY_PIP_CONTROLS_INSET)
                            .size(DUMMY_PIP_ICON_SIZE_DP.dp),
                    ) {
                        Icon(Icons.Filled.Headphones, contentDescription = null, tint = Color.White)
                    }
                    // <-- AM (DUMMY_PIP_HEADPHONES_FIX)

                    // AM (DUMMY_PIP_TAP_TO_EXPAND_REMOVED_FIX) -->
                    // Tapping anywhere used to expand to fullscreen - real
                    // PIP doesn't do this (its own single-tap behavior is
                    // "show controls, including a fullscreen toggle", not
                    // "expand immediately" - developer.android.com's own PIP
                    // docs confirm this directly). This icon is the
                    // explicit, deliberate expand action instead; tapping
                    // anywhere else just shows/keeps showing controls, same
                    // as this whole block already does on any touch-down.
                    // AM (DUMMY_PIP_HEADPHONES_FIX) -->
                    // Moved next to the close button (both now share the
                    // TopEnd corner in one Row) rather than sitting alone on
                    // the opposite corner, matching real PIP's own layout
                    // (its controls-reveal groups the fullscreen toggle and
                    // close button together) - freeing up TopStart for the
                    // headphones/background-play action above.
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).padding(DUMMY_PIP_CONTROLS_INSET),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier.size(DUMMY_PIP_ICON_SIZE_DP.dp),
                        ) {
                            Icon(Icons.Filled.OpenInFull, contentDescription = null, tint = Color.White)
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.size(DUMMY_PIP_ICON_SIZE_DP.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                        }
                    }
                    // <-- AM (DUMMY_PIP_HEADPHONES_FIX)
                    // <-- AM (DUMMY_PIP_TAP_TO_EXPAND_REMOVED_FIX)

                    // AM (DUMMY_PIP_CONTROLS_SPACING_FIX) -->
                    // Was a plain Row with no arrangement - icons sat
                    // shoulder to shoulder. Real PIP's own controls-reveal
                    // spaces its prev/play/next row out further than that;
                    // spacedBy(16.dp) matches the wider look, not a measured
                    // pixel match to a specific device.
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = DUMMY_PIP_CONTROLS_INSET),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // <-- AM (DUMMY_PIP_CONTROLS_SPACING_FIX)
                        IconButton(
                            onClick = {},
                            modifier = Modifier.size(DUMMY_PIP_ICON_SIZE_DP.dp),
                        ) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = Color.White)
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.size(DUMMY_PIP_ICON_SIZE_DP.dp),
                        ) {
                            Icon(
                                if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.size(DUMMY_PIP_ICON_SIZE_DP.dp),
                        ) {
                            Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Color.White)
                        }
                    }
                }
                // <-- AM (DUMMY_PIP_AUTO_HIDE_CONTROLS_FIX)
            }
        }
    }
}