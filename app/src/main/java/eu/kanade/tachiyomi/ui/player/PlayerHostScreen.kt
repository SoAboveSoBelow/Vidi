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
// small size instead. The known tradeoff (mpv's real surface
// reconfiguration cost on every pinch frame) stays unresolved, in
// exchange for a version of this feature that reliably shows video at all.
//
// AM (DUMMY_PIP_REBUILD) -->
// The floating window itself was rebuilt as a self-contained component in
// DummyPipWindow.kt (Samsung-PiP behaviors: fling, edge stash, double-tap
// resize, enter/exit morph) - see that file's header. The real-size rule
// above still holds there: the morph animation's graphicsLayer transform
// is transient (exists only for the ~250ms animation, identity at rest),
// never a persistent wrap around a fullscreen-sized PlayerScreen.
// <-- AM (DUMMY_PIP_REBUILD)
// <-- AM (DUMMY_PIP_REAL_SIZE_REVERT)
// <-- AM (PLAYER_OVERLAY_MIGRATION)

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.ui.main.MainActivity
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
    val currentRequest = request
    if (currentRequest == null) {
        // AM (APP_WIDE_ORIENTATION_RESET_FIX) -->
        // The player locks requestedOrientation while it's up
        // (OrientationOverlay). With the persistent-host architecture
        // MainActivity outlives every player session, and nothing ever
        // reset it - the whole app stayed stuck in the player's last
        // orientation ("auto rotate broken app wide"). Reset to
        // UNSPECIFIED (system/user auto-rotate) whenever no player
        // session is live.
        val activity = LocalActivity.current
        LaunchedEffect(Unit) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // <-- AM (APP_WIDE_ORIENTATION_RESET_FIX)
        return
    }
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

// AM (DUMMY_PIP_REBUILD) -->
// All dummy-pip sizing constants moved into DummyPipWindow.kt with the
// rest of the implementation - including the AOSP-derived 0.6/0.3/1.0
// screen-width percentages (see DUMMY_PIP_REAL_AOSP_VALUES_FIX in git
// history for where those came from).
// <-- AM (DUMMY_PIP_REBUILD)

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

    // AM (DUMMY_PIP_CONTROLS_LEAK_FIX) -->
    // PlayerScreen is never disposed for the fullscreen<->dummy-pip
    // transition (that's the whole point of the overlay architecture), so
    // its own controls-visibility state carries straight through unchanged -
    // confirmed on-device, PlayerScreen's own controls (seek bar, big center
    // play/pause, etc.) stayed visible, tiny, underneath the dummy-pip's own
    // controls, both on first entry and again afterward (several
    // PlayerViewModel functions call showControls() as a side effect
    // unrelated to any tap). This actively fights back: any time controls
    // turn on while dummy pip is active, hide them again immediately.
    //
    // setControlsAndStatusBarShown(..., statusBarShown = true) instead of
    // hideControls() - see that function's own doc comment on PlayerViewModel
    // for why: hideControls() also hides the OS status/nav bars, which should
    // never happen just because the mini window is hiding its own controls.
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

    // AM (DUMMY_PIP_EXTERNAL_VOLUME_BAR) -->
    // Real PiP shows the volume UI OUTSIDE the small window. While the
    // dummy pip is active, intercept the in-player volume slider (which
    // would render inside the window, tiny and clipped): suppress it and
    // tick pipVolumeUiTick instead - DummyPipContainer shows its own
    // external volume bar for each tick.
    var pipVolumeUiTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(isDummyPipActive, currentViewModel) {
        if (isDummyPipActive) {
            currentViewModel.uiData.collect { uiData ->
                if (uiData.isVolumeSliderShown) {
                    currentViewModel.handlePlayerEvent(PlayerViewModel.PlayerEvent.ShowVolumeSlider(false))
                    pipVolumeUiTick++
                }
            }
        }
    }
    // <-- AM (DUMMY_PIP_EXTERNAL_VOLUME_BAR)

    // AM (DUMMY_PIP_REBUILD) -->
    // The entire floating-window implementation (gestures, animations,
    // controls, stash tab) now lives in DummyPipWindow.kt - see that file's
    // header for the full design. This screen only feeds it session state
    // and translates its callbacks into holder/viewModel mutations.
    val videoState by currentViewModel.stateData.collectAsStateWithLifecycle()
    val playbackData by currentViewModel.playbackData.collectAsStateWithLifecycle()
    val dummyPipController = rememberDummyPipController()

    val dummyPipActions = remember(currentViewModel) {
        DummyPipActions(
            onTogglePlayPause = {
                if (currentViewModel.playbackData.value.paused) {
                    currentViewModel.unpause()
                } else {
                    currentViewModel.pause()
                }
            },
            onSkipPrevious = { currentViewModel.nextEpisode(next = false) },
            onSkipNext = { currentViewModel.nextEpisode(next = true) },
            // Called by DummyPipContainer only AFTER its exit morph has
            // visually completed - flipping this any earlier would swap the
            // video Box back to fillMaxSize mid-animation.
            onExpand = { holder?.isDummyPipActive = false },
            // Same semantics as the old dismissAndPause(): pause and hide,
            // but keep the session (holder, service, notification) alive.
            onDismiss = {
                currentViewModel.pause()
                holder?.isDummyPipActive = false
                holder?.hasExternalScreenConsumer = false
                PlayerMediaHolder.clearPlaybackRequest()
            },
            // Same semantics as the old enterBackgroundPlay(): the
            // notification is already always-on while playing, so "background
            // play" is just clearing the UI without pausing or tearing down.
            onEnterBackground = {
                holder?.isDummyPipActive = false
                holder?.hasExternalScreenConsumer = false
                PlayerMediaHolder.clearPlaybackRequest()
            },
        )
    }

    DummyPipContainer(
        isPipRequested = isDummyPipActive,
        videoWidth = videoState.videoWidth,
        videoHeight = videoState.videoHeight,
        isPaused = playbackData.paused,
        actions = dummyPipActions,
        controller = dummyPipController,
        volume = playbackData.currentVolume,
        maxVolume = videoState.maxVolume,
        volumeUiTick = pipVolumeUiTick,
    ) {
        PlayerScreen(
            viewModel = currentViewModel,
            onBack = { PlayerMediaHolder.clearPlaybackRequest() },
            onEnterDummyPip = {
                if (holder?.isDummyPipActive == true) {
                    // Effectively unreachable: PlayerScreen's BackHandler
                    // is now DISABLED while the dummy pip is up (see
                    // DUMMY_PIP_BACK_PASSTHROUGH_FIX in PlayerScreen.kt), so
                    // system back presses fall through to the app pages
                    // underneath the pip instead of coming here. Kept as a
                    // harmless fallback for any non-BackHandler caller.
                    dummyPipController.requestExpand()
                } else {
                    // Hide PlayerScreen's own controls BEFORE flipping
                    // the flag, not after: the DUMMY_PIP_CONTROLS_LEAK_FIX
                    // collector only reacts once the flag is already true,
                    // so for a frame the player's full-size controls rode
                    // along inside the shrinking window - the visible
                    // entry flash.
                    currentViewModel.setControlsAndStatusBarShown(controlsShown = false, statusBarShown = true)
                    holder?.isDummyPipActive = true
                    // AM (DUMMY_PIP_STALE_AUTO_ENTER_FIX) -->
                    // Without this, the OS keeps whatever auto-enter
                    // registration was last pushed before this exact
                    // moment until the next pause state change happens
                    // to trigger a fresh one - which may never come
                    // before the user actually leaves the app.
                    (context as? MainActivity)?.updateAutoEnterPipParams()
                    // <-- AM (DUMMY_PIP_STALE_AUTO_ENTER_FIX)
                }
            },
        )
    }
    // <-- AM (DUMMY_PIP_REBUILD)
}
