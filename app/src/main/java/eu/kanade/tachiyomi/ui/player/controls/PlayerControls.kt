package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlRegion
import animiru.domain.player.model.ControlSurface
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.PlayerViewModel.PlayerEvent
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessSlider
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.ControlSlotMove
import eu.kanade.tachiyomi.ui.player.controls.components.SeekbarWithTimers
import eu.kanade.tachiyomi.ui.player.controls.components.TextPlayerUpdate
import eu.kanade.tachiyomi.ui.player.controls.components.ThumbnailPreview
import eu.kanade.tachiyomi.ui.player.controls.components.VolumeSlider
import eu.kanade.tachiyomi.ui.player.controls.components.controlActionIcon
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Suppress("CompositionLocalAllowlist")
val LocalPlayerButtonsClickEvent = staticCompositionLocalOf { {} }

@Composable
fun PlayerControls(
    stateData: PlayerViewModel.PlayerStateData,
    uiData: PlayerViewModel.PlayerUiData,
    playbackData: PlayerViewModel.PlayerPlaybackData,
    onBack: () -> Unit,
    onPlayerEvent: (PlayerEvent) -> Unit,
    mpvVolume: Int?,
    readAhead: Float?,
    remaining: Int?,
    playbackSpeed: Float?,
    modifier: Modifier = Modifier,
    // AM (SYSTEM_BARS_DERIVED_FROM_CONTROLS) -->
    // Whether the OS status/nav bars are currently visible alongside the
    // controls - computed by the caller (controlsShown && the
    // showSystemStatusBar preference); uiData.statusBarShown no longer
    // exists as separate state. See PlayerViewModel's comment of the
    // same tag.
    // <-- AM (SYSTEM_BARS_DERIVED_FROM_CONTROLS)
    systemBarsVisible: Boolean = false,
    // AM (BROWSE_SCROLLER_CONTROLS) -->
    /**
     * False for a host with no track sheets, panels or auto-play to open -
     * the browse scroller's fullscreen card, which drives these controls over
     * one standalone mpv instance rather than a player session. The row is
     * still laid out (the top-left title is constrained against it), it just
     * has nothing in it.
     */
    showTrackControls: Boolean = true,
    /**
     * False where there is no previous or next to go to, as in a feed card.
     * The buttons would otherwise be drawn permanently disabled.
     */
    showMediaButtons: Boolean = true,
    // <-- AM (BROWSE_SCROLLER_CONTROLS)
    // AM (CONTROL_BINDINGS) -->
    /**
     * Non-null turns the whole surface into the layout editor: every slot reports the
     * position it occupies instead of running what it is bound to. The controls are
     * otherwise identical, which is the point - the editor is this screen, not a
     * drawing of it.
     */
    editSlot: ((ControlRegion, Int) -> Unit)? = null,
    /** Commits the final hold-and-drag placement within a region. */
    moveSlot: ((ControlRegion, List<ControlSlotMove>) -> Unit)? = null,
    /** A hold has picked a slot up, putting its region into arrange mode. */
    onArrangeStart: ((ControlRegion, Int) -> Unit)? = null,
    /** Opens the picker for a free position, inserting rather than overwriting. */
    insertSlot: ((ControlRegion, Int) -> Unit)? = null,
    /** Puts the held slot back down. */
    onArrangeEnd: (() -> Unit)? = null,
    /** Ordinary background taps retain the player's hide/reveal controls behavior. */
    onBackgroundTap: (() -> Unit)? = null,
    /**
     * The slot a hold picked up, or null when nothing is. Every region reveals its
     * free positions while one is held, not only the region holding it - a control
     * being moved is as likely to be wanted in another bar as further along its own.
     */
    arrangingRegion: ControlRegion? = null,
    // <-- AM (CONTROL_BINDINGS)
) {
    val transparentOverlay by animateFloatAsState(
        if (uiData.controlsShown && !uiData.isControlsLocked) .8f else 0f,
        animationSpec = playerControlsExitAnimationSpec(),
        label = "controls_transparent_overlay",
    )

    // AM (MEDIA_CONTROLS) -->
    val context = LocalContext.current
    // AM (CONTROL_BINDINGS): the icon follows whatever the slot is bound to, collected
    // so an edit to the binding is reflected without the player being rebuilt.
    val controlLayout by remember { context.appGraph.controlBindingPreferences.layout(ControlSurface.Player) }
        .collectAsState()
    // AM (CONTROL_BINDINGS): the one place an action becomes something the player does.
    // Every bound slot goes through here, so adding an action is one branch rather than
    // a new parameter on whichever control happens to host it. The view model keeps its
    // own dispatcher for the screen-zone gestures, which have no composable to route
    // through; the two converge once those gestures emit events too.
    val onControlAction: (ControlAction) -> Unit = { action ->
        // The editor previews bindings; it never executes playback actions.
        if (editSlot == null) when (action) {
            ControlAction.None -> Unit
            ControlAction.PlayPause -> onPlayerEvent(PlayerEvent.PlayPause)
            ControlAction.PreviousEpisode -> onPlayerEvent(PlayerEvent.NextEpisode(next = false))
            ControlAction.NextEpisode -> onPlayerEvent(PlayerEvent.NextEpisode(next = true))
            ControlAction.PreviousSource -> onPlayerEvent(PlayerEvent.NextSource(next = false))
            ControlAction.NextSource -> onPlayerEvent(PlayerEvent.NextSource(next = true))
            ControlAction.Lock -> onPlayerEvent(PlayerEvent.LockControls(lock = true))
            ControlAction.CycleRotation -> onPlayerEvent(PlayerEvent.CycleRotation)
            ControlAction.PictureInPicture -> onPlayerEvent(PlayerEvent.EnterPip)
            ControlAction.AspectRatio -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.Aspect))
            ControlAction.PlaybackSpeed -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.PlaybackSpeed))
            ControlAction.Chapters -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.Chapters))
            ControlAction.Screenshot -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.Screenshot))
            ControlAction.SubtitleTracks -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.SubtitleTracks))
            ControlAction.SubtitleSettings -> onPlayerEvent(PlayerEvent.SetPanel(Panels.SubtitleSettings))
            ControlAction.AudioTracks -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.AudioTracks))
            ControlAction.AudioDelay -> onPlayerEvent(PlayerEvent.SetPanel(Panels.AudioDelay))
            ControlAction.VideoQuality -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.QualityTracks))
            ControlAction.VideoFilters -> onPlayerEvent(PlayerEvent.SetPanel(Panels.VideoFilters))
            ControlAction.Overflow -> onPlayerEvent(PlayerEvent.SetSheet(Sheets.More))
            ControlAction.PrimaryCustomButton -> onPlayerEvent(PlayerEvent.ExecuteCustomButton(false))
            is ControlAction.CustomButton -> onPlayerEvent(
                PlayerEvent.ExecuteCustomButtonById(action.id, action.input),
            )
            // Rendered as a switch and as a button of its own respectively, neither of
            // which dispatches through here.
            ControlAction.ToggleAutoPlay -> Unit
            ControlAction.Cast -> Unit
            // Directional seeking and raw key codes are the view model's: both need the
            // player's own position and mpv handle, which this layer doesn't hold.
            ControlAction.SeekBackward -> Unit
            ControlAction.SeekForward -> Unit
            is ControlAction.CustomKey -> Unit
        }
    }

    // Episode boundaries are only relevant when the binding actually switches episodes;
    // seeking/play-pause/custom/screenshot aren't affected by which episode is playing.
    val previousEnabled = stateData.hasPreviousEpisode
    val nextEnabled = stateData.hasNextEpisode
    // <-- AM (MEDIA_CONTROLS)

    // AM (QUEUE_SOURCE_STEP) -->
    // Offered only when the queue actually holds more than one source. With
    // one, a double tap has nowhere to go, and a handler that does nothing
    // would still cost every single tap the double-tap timeout.
    val hasMultipleSources = remember(stateData.playlistBlockByEpisodeId) {
        stateData.playlistBlockByEpisodeId.values.distinct().size > 1
    }
    // <-- AM (QUEUE_SOURCE_STEP)

    // AM (CONTROL_BINDINGS): with nothing to step through, the source-step binding is
    // dropped rather than left bound to a no-op - a live double-tap handler would still
    // cost every single tap on that button the double-tap timeout.
    val middleEntries = remember(controlLayout, hasMultipleSources) {
        controlLayout.entries(ControlRegion.Middle).map { entry ->
            if (hasMultipleSources) {
                entry
            } else {
                when (entry.doubleTap) {
                    ControlAction.PreviousSource, ControlAction.NextSource -> entry.copy(doubleTap = null)
                    else -> entry
                }
            }
        }
    }

    // AM (SYSTEM_BAR_CONTROLS_INSET) -->
    // When "show system status bar with controls" is on, PlayerControls' edge-to-edge
    // ConstraintLayout otherwise draws the top settings row and bottom seekbar right
    // underneath the (now-visible) status/navigation bars. Only add the extra offset
    // while those bars are actually being shown alongside controls - the normal
    // immersive/hidden state shouldn't get any extra padding.
    val statusBarTopPadding = if (systemBarsVisible) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    } else {
        0.dp
    }
    val navigationBarBottomPadding = if (systemBarsVisible) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    } else {
        0.dp
    }
    // <-- AM (SYSTEM_BAR_CONTROLS_INSET)

    // AM (LANDSCAPE_NAV_BAR_INSET) -->
    // In landscape, 2/3-button navigation renders the nav bar as a side bar
    // (left or right) instead of along the bottom - calculateBottomPadding()
    // alone is 0 there, so the SYSTEM_BAR_CONTROLS_INSET block above (top/
    // bottom only) left every horizontal edge - the back button, the top-right
    // controls, and the seekbar (which is only bounded by this ConstraintLayout's
    // own horizontal padding below, having no explicit start/end constraints of
    // its own) - with no margin against it, so the side nav bar drew right over
    // them. LayoutDirection is pinned to Ltr just below, so start/end here are
    // unambiguously left/right, matching that pin.
    val navigationBarStartPadding = if (systemBarsVisible) {
        WindowInsets.navigationBars.asPaddingValues().calculateStartPadding(LayoutDirection.Ltr)
    } else {
        0.dp
    }
    val navigationBarEndPadding = if (systemBarsVisible) {
        WindowInsets.navigationBars.asPaddingValues().calculateEndPadding(LayoutDirection.Ltr)
    } else {
        0.dp
    }
    // <-- AM (LANDSCAPE_NAV_BAR_INSET)

    // The middle row is the one control group that can meet a landscape side cutout.
    // safeDrawing includes the camera hole; subtract the bars so a hidden system bar is
    // not mistaken for cutout space. Top and bottom already have their own edge rules.
    val density = LocalDensity.current
    val cutoutStartPadding = with(density) {
        (
            WindowInsets.safeDrawing.getLeft(this, LayoutDirection.Ltr) -
                WindowInsets.systemBarsIgnoringVisibility.getLeft(this, LayoutDirection.Ltr)
            ).coerceAtLeast(0).toDp()
    }
    val cutoutEndPadding = with(density) {
        (
            WindowInsets.safeDrawing.getRight(this, LayoutDirection.Ltr) -
                WindowInsets.systemBarsIgnoringVisibility.getRight(this, LayoutDirection.Ltr)
            ).coerceAtLeast(0).toDp()
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        ConstraintLayout(
            modifier = modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        Pair(0f, Color.Black),
                        Pair(.2f, Color.Transparent),
                        Pair(.7f, Color.Transparent),
                        Pair(1f, Color.Black),
                    ),
                    alpha = transparentOverlay,
                )
                .padding(
                    start = MaterialTheme.padding.medium + navigationBarStartPadding,
                    end = MaterialTheme.padding.medium + navigationBarEndPadding,
                ),
        ) {
            // AM (CONTROL_BINDINGS) -->
            // Declared first so it sits UNDER every control. Empty-space gestures never
            // compete with a slot's own tap or hold-and-drag recognizer: holding anywhere
            // that misses a control reveals the insertion positions, and tapping empty
            // space while arranging puts the held slot down.
            if (editSlot != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(arrangingRegion, onArrangeStart, onArrangeEnd, onBackgroundTap) {
                            detectTapGestures(
                                onLongPress = {
                                    if (arrangingRegion == null) {
                                        onArrangeStart?.invoke(ControlRegion.Middle, 0)
                                    }
                                },
                                onTap = {
                                    if (arrangingRegion != null) {
                                        onArrangeEnd?.invoke()
                                    } else {
                                        onBackgroundTap?.invoke()
                                    }
                                },
                            )
                        },
                )
            }
            // <-- AM (CONTROL_BINDINGS)

            val topBar = createRef()
            val (volumeSlider, brightnessSlider) = createRefs()
            val unlockControlsButton = createRef()
            val bottomControls = createRef()
            val centerControls = createRef()
            val thumbnail = createRef()
            val seekbar = createRef()
            val (playerUpdates) = createRefs()
            val skipButton = createRef()

            LaunchedEffect(playbackData.currentVolume, mpvVolume, uiData.isVolumeSliderShown) {
                delay(2.seconds)
                if (uiData.isVolumeSliderShown) onPlayerEvent(PlayerEvent.ShowVolumeSlider(false))
            }
            LaunchedEffect(playbackData.currentBrightness, uiData.isBrightnessSliderShown) {
                delay(2.seconds)
                if (uiData.isBrightnessSliderShown) onPlayerEvent(PlayerEvent.ShowBrightnessSlider(false))
            }
            AnimatedVisibility(
                visible = uiData.isBrightnessSliderShown,
                enter = if (!uiData.reduceMotion) {
                    slideInHorizontally(playerControlsEnterAnimationSpec()) {
                        if (uiData.swapVolumeAndBrightness) it else -it
                    } +
                        fadeIn(
                            playerControlsEnterAnimationSpec(),
                        )
                } else {
                    fadeIn(playerControlsEnterAnimationSpec())
                },
                exit = if (!uiData.reduceMotion) {
                    slideOutHorizontally(playerControlsExitAnimationSpec()) {
                        if (uiData.swapVolumeAndBrightness) it else -it
                    } +
                        fadeOut(
                            playerControlsExitAnimationSpec(),
                        )
                } else {
                    fadeOut(playerControlsExitAnimationSpec())
                },
                modifier = Modifier.constrainAs(brightnessSlider) {
                    if (uiData.swapVolumeAndBrightness) {
                        start.linkTo(parent.start, MaterialTheme.padding.medium)
                    } else {
                        end.linkTo(parent.end, MaterialTheme.padding.medium)
                    }
                    top.linkTo(parent.top)
                    bottom.linkTo(parent.bottom)
                },
            ) {
                BrightnessSlider(
                    brightness = playbackData.currentBrightness,
                    positiveRange = 0f..1f,
                    negativeRange = 0f..0.75f,
                )
            }

            AnimatedVisibility(
                visible = uiData.isVolumeSliderShown,
                enter = if (!uiData.reduceMotion) {
                    slideInHorizontally(playerControlsEnterAnimationSpec()) {
                        if (uiData.swapVolumeAndBrightness) it else -it
                    } +
                        fadeIn(
                            playerControlsEnterAnimationSpec(),
                        )
                } else {
                    fadeIn(playerControlsEnterAnimationSpec())
                },
                exit = if (!uiData.reduceMotion) {
                    slideOutHorizontally(playerControlsExitAnimationSpec()) {
                        if (uiData.swapVolumeAndBrightness) it else -it
                    } +
                        fadeOut(
                            playerControlsExitAnimationSpec(),
                        )
                } else {
                    fadeOut(playerControlsExitAnimationSpec())
                },
                modifier = Modifier.constrainAs(volumeSlider) {
                    if (uiData.swapVolumeAndBrightness) {
                        end.linkTo(parent.end, MaterialTheme.padding.medium)
                    } else {
                        start.linkTo(parent.start, MaterialTheme.padding.medium)
                    }
                    top.linkTo(parent.top)
                    bottom.linkTo(parent.bottom)
                },
            ) {
                VolumeSlider(
                    volume = playbackData.currentVolume,
                    mpvVolume = mpvVolume ?: 100,
                    range = 0..stateData.maxVolume,
                    boostRange = if (uiData.boostCap > 0) 0..uiData.boostCap else null,
                    displayAsPercentage = uiData.displayVolumeAsPercentage,
                )
            }

            LaunchedEffect(uiData.playerUpdate) {
                if (uiData.playerUpdate is PlayerUpdates.DoubleSpeed || uiData.playerUpdate is PlayerUpdates.None) {
                    return@LaunchedEffect
                }
                delay(2.seconds)
                onPlayerEvent(PlayerEvent.ShowPlayerUpdate(PlayerUpdates.None))
            }
            AnimatedVisibility(
                visible = uiData.playerUpdate !is PlayerUpdates.None,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.constrainAs(playerUpdates) {
                    linkTo(parent.start, parent.end)
                    linkTo(parent.top, parent.bottom, bias = 0.2f)
                },
            ) {
                when (uiData.playerUpdate) {
                    PlayerUpdates.None -> {}
                    PlayerUpdates.DoubleSpeed -> {}
                    is PlayerUpdates.AspectRatio -> TextPlayerUpdate(
                        stringResource(uiData.playerUpdate.aspect.titleRes),
                    )
                    is PlayerUpdates.ShowText -> TextPlayerUpdate(uiData.playerUpdate.value)
                    is PlayerUpdates.ShowTextResource -> TextPlayerUpdate(
                        stringResource(uiData.playerUpdate.textResource),
                    )
                }
            }

            AnimatedVisibility(
                visible = uiData.controlsShown && uiData.isControlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.constrainAs(unlockControlsButton) {
                    top.linkTo(parent.top, MaterialTheme.padding.medium)
                    start.linkTo(parent.start, MaterialTheme.padding.medium)
                },
            ) {
                ControlsButton(
                    Icons.Filled.Lock,
                    onClick = { onPlayerEvent(PlayerEvent.LockControls(false)) },
                )
            }

            // AM (PLAYING_IS_POSITION_ADVANCING) -->
            // One signal: the session says whether it is getting anywhere.
            // See PlayerViewModel's position watcher for why this is not read
            // off mpv's core-idle and paused-for-cache properties any more.
            val loading = playbackData.stalled
            // <-- AM (PLAYING_IS_POSITION_ADVANCING)
            AnimatedVisibility(
                visible = (
                    uiData.controlsShown && (!uiData.isControlsLocked || playbackData.gestureSeekAmount != null)
                    ) ||
                    loading ||
                    uiData.isLoadingEpisode,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.padding(
                    start = cutoutStartPadding,
                    end = cutoutEndPadding,
                ).constrainAs(centerControls) {
                    end.linkTo(parent.absoluteRight)
                    start.linkTo(parent.absoluteLeft)
                    top.linkTo(parent.top)
                    bottom.linkTo(parent.bottom)
                },
            ) {
                MiddlePlayerControls(
                    // AM (CONTROL_BINDINGS): the row's own entries. Source stepping is
                    // a double-tap binding now, dropped where the queue has nothing to
                    // step through so those buttons keep their immediate single tap.
                    entries = middleEntries,
                    hasPrevious = previousEnabled,
                    hasNext = nextEnabled,
                    // AM (BROWSE_SCROLLER_CONTROLS)
                    showMediaButtons = showMediaButtons,
                    onAction = onControlAction,
                    isStopped = stateData.isStopped,
                    // AM (CLIP_PLAYBACK)
                    isLoading = loading,
                    isLoadingEpisode = uiData.isLoadingEpisode,
                    controlsShown = uiData.controlsShown,
                    areControlsLocked = uiData.isControlsLocked,
                    showLoadingCircle = uiData.showLoadingCircle,
                    paused = playbackData.paused,
                    gestureSeekAmount = playbackData.gestureSeekAmount,
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    onSlotEdit = editSlot?.let { edit -> { index -> edit(ControlRegion.Middle, index) } },
                    onSlotMove = moveSlot?.let { move ->
                        { moves -> move(ControlRegion.Middle, moves) }
                    },
                    onArrangeStart = onArrangeStart?.let { start ->
                        { index -> start(ControlRegion.Middle, index) }
                    },
                    onSlotInsert = insertSlot?.let { insert ->
                        { index -> insert(ControlRegion.Middle, index) }
                    },
                    isArranging = arrangingRegion != null,
                )
            }
            AnimatedVisibility(
                visible = (uiData.controlsShown || uiData.seekBarShown) && !uiData.isControlsLocked,
                enter = if (!uiData.reduceMotion) {
                    slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                        fadeIn(playerControlsEnterAnimationSpec())
                } else {
                    fadeIn(playerControlsEnterAnimationSpec())
                },
                exit = if (!uiData.reduceMotion) {
                    slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                        fadeOut(playerControlsExitAnimationSpec())
                } else {
                    fadeOut(playerControlsExitAnimationSpec())
                },
                modifier = Modifier.constrainAs(seekbar) {
                    bottom.linkTo(parent.bottom, MaterialTheme.padding.medium + navigationBarBottomPadding)
                },
            ) {
                SeekbarWithTimers(
                    playerPosition = playbackData.position.toFloat(),
                    seekPosition = playbackData.seekPosition,
                    isGestureSeeking = playbackData.isGestureSeeking,
                    isSeeking = playbackData.isSeeking,
                    duration = playbackData.duration.toFloat(),
                    remaining = remaining?.toFloat() ?: 0f,
                    readAheadValue = readAhead ?: 0f,
                    onValueChange = { onPlayerEvent(PlayerEvent.Seek(it.roundToInt())) },
                    onValueChangeFinished = { onPlayerEvent(PlayerEvent.SeekFinished(it.roundToInt())) },
                    timersInverted = Pair(false, uiData.invertDuration),
                    durationTimerOnCLick = { onPlayerEvent(PlayerEvent.ToggleDurationTimer) },
                    positionTimerOnClick = { },
                    chapters = stateData.chapters,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.constrainAs(topBar) {
                    top.linkTo(parent.top, MaterialTheme.padding.medium + statusBarTopPadding)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                    width = Dimension.fillToConstraints
                },
            ) {
                AnimatedVisibility(
                    visible = uiData.controlsShown && !uiData.isControlsLocked,
                    enter = if (!uiData.reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!uiData.reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    TopLeftPlayerControls(
                        // AM (PLAYLIST_SOURCE_IDENTITY) -->
                        // The source the PLAYING episode came from, not the entry
                        // the session was opened on: a playlist can run across a
                        // merged entry's children and anything queued, and
                        // uiData.animeTitle is written once per load, so it named
                        // whichever entry started the session for the rest of it.
                        animeTitle = stateData.currentEpisode
                            ?.let(stateData::sourceTitleOf)
                            ?.takeIf { it.isNotBlank() }
                            ?: uiData.animeTitle.takeIf { it.isNotBlank() }
                            // AM (CONTROL_BINDINGS): the editor can be opened with nothing
                            // playing, and an empty title collapses the area it is supposed
                            // to be showing the size of.
                            ?: if (editSlot != null) stringResource(AYMR.strings.pref_player_controls_sample_title) else "",
                        // <-- AM (PLAYLIST_SOURCE_IDENTITY)
                        // AM (EPISODE_NAMES) -->
                        // Resolved here rather than stored, so it matches the
                        // episode list and follows a rename made while this
                        // session is alive.
                        mediaTitle = stateData.currentEpisode
                            ?.let(stateData::displayNameOf)
                            ?.takeIf { it.isNotBlank() }
                            // AM (CONTROL_BINDINGS)
                            ?: if (editSlot != null) stringResource(AYMR.strings.pref_player_controls_sample_episode) else "",
                        // <-- AM (EPISODE_NAMES)
                        onTitleClick = { onPlayerEvent(PlayerEvent.ShowEpisodeDialog) },
                        onBackClick = onBack,
                        // AM (CONTROL_BINDINGS)
                        showStaticSlots = editSlot != null,
                    )
                }

                AnimatedVisibility(
                    visible = uiData.controlsShown && !uiData.isControlsLocked,
                    enter = if (!uiData.reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!uiData.reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                ) {
                    // AM (BROWSE_SCROLLER_CONTROLS)
                    if (showTrackControls) {
                        TopRightPlayerControls(
                            entries = controlLayout.entries(ControlRegion.TopBar),
                            customButtons = uiData.customButtons,
                            customToggleStates = uiData.customToggleStates,
                            isEpisodeOnline = stateData.isEpisodeOnline,
                            onAction = onControlAction,
                            onSlotEdit = editSlot?.let { edit -> { index -> edit(ControlRegion.TopBar, index) } },
                            onSlotMove = moveSlot?.let { move ->
                                { moves -> move(ControlRegion.TopBar, moves) }
                            },
                            onArrangeStart = onArrangeStart?.let { start ->
                                { index -> start(ControlRegion.TopBar, index) }
                            },
                            onSlotInsert = insertSlot?.let { insert ->
                                { index -> insert(ControlRegion.TopBar, index) }
                            },
                            isArranging = arrangingRegion != null,
                        )
                    }
                }

            }

            AnimatedVisibility(
                visible = uiData.controlsShown && !uiData.isControlsLocked &&
                    (uiData.skipIntroText != null || uiData.primaryButton != null),
                enter = if (!uiData.reduceMotion) {
                    slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                        fadeIn(playerControlsEnterAnimationSpec())
                } else {
                    fadeIn(playerControlsEnterAnimationSpec())
                },
                exit = if (!uiData.reduceMotion) {
                    slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                        fadeOut(playerControlsExitAnimationSpec())
                } else {
                    fadeOut(playerControlsExitAnimationSpec())
                },
                modifier = Modifier.constrainAs(skipButton) {
                    end.linkTo(seekbar.end, MaterialTheme.padding.extraSmall)
                    bottom.linkTo(bottomControls.top, MaterialTheme.padding.large)
                },
            ) {
                SkipIntroControls(
                    customButton = uiData.primaryButton,
                    customButtonTitle = uiData.primaryButtonTitle,
                    skipIntroButton = uiData.skipIntroText,
                    onPressSkipIntroButton = { onPlayerEvent(PlayerEvent.SkipIntro) },
                    onCustomButtonClick = { onPlayerEvent(PlayerEvent.ExecuteCustomButton(false)) },
                    onCustomButtonLongClick = { onPlayerEvent(PlayerEvent.ExecuteCustomButton(true)) },
                )
            }

            AnimatedVisibility(
                visible = uiData.controlsShown && !uiData.isControlsLocked,
                enter = if (!uiData.reduceMotion) {
                    slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                        fadeIn(playerControlsEnterAnimationSpec())
                } else {
                    fadeIn(playerControlsEnterAnimationSpec())
                },
                exit = if (!uiData.reduceMotion) {
                    slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                        fadeOut(playerControlsExitAnimationSpec())
                } else {
                    fadeOut(playerControlsExitAnimationSpec())
                },
                modifier = Modifier.constrainAs(bottomControls) {
                    bottom.linkTo(seekbar.top)
                    start.linkTo(seekbar.start)
                    end.linkTo(seekbar.end)
                    width = Dimension.fillToConstraints
                },
            ) {
                BottomPlayerControls(
                    entries = controlLayout.entries(ControlRegion.Bottom),
                    playbackSpeed = playbackSpeed ?: uiData.playerSpeedPref,
                    showChapterIndicator = uiData.showChapterIndicator,
                    currentChapter = stateData.currentChapter,
                    isPipAvailable = stateData.isPipAvailable,
                    castEnabled = uiData.enableCast,
                    castLoading = stateData.isLoadingCasting,
                    castError = stateData.isErrorCasting,
                    onLockControls = { if (editSlot == null) onPlayerEvent(PlayerEvent.LockControls(true)) },
                    onCycleRotation = { if (editSlot == null) onPlayerEvent(PlayerEvent.CycleRotation) },
                    onPlaybackSpeedChange = { if (editSlot == null) onPlayerEvent(PlayerEvent.ChangeSpeed(it)) },
                    onOpenSheet = { if (editSlot == null) onPlayerEvent(PlayerEvent.SetSheet(it)) },
                    onPipClick = { if (editSlot == null) onPlayerEvent(PlayerEvent.EnterPip) },
                    // AM (ASPECT_OVERRIDE_PICKER): opens the picker rather than
                    // cycling blind. ChangeAspect still exists and still toggles
                    // fit/fill, for gestures and custom buttons bound to it.
                    onAspectClick = { if (editSlot == null) onPlayerEvent(PlayerEvent.SetSheet(Sheets.Aspect)) },
                    onSlotEdit = editSlot?.let { edit -> { index -> edit(ControlRegion.Bottom, index) } },
                    onSlotMove = moveSlot?.let { move ->
                        { moves -> move(ControlRegion.Bottom, moves) }
                    },
                    onArrangeStart = onArrangeStart?.let { start ->
                        { index -> start(ControlRegion.Bottom, index) }
                    },
                    onSlotInsert = insertSlot?.let { insert ->
                        { index -> insert(ControlRegion.Bottom, index) }
                    },
                    isArranging = arrangingRegion != null,
                )
            }

            ThumbnailPreview(
                visible = playbackData.isSeeking,
                image = playbackData.thumbnailImage,
                positionS = playbackData.seekPosition.toLong(),
                durationS = playbackData.duration.toLong(),
                chapters = stateData.chapters,
                modifier = Modifier.fillMaxWidth().constrainAs(thumbnail) {
                    bottom.linkTo(seekbar.top, MaterialTheme.padding.medium)
                },
            )
        }
    }
}

@Composable
@Preview(
    device = "spec:width=411dp,height=891dp,dpi=420,isRound=false,chinSize=0dp,orientation=landscape",
)
private fun PlayerControlsPreview() {
    MaterialTheme {
        PlayerControls(
            stateData = PlayerViewModel.PlayerStateData(
                maxVolume = 0,
                isEpisodeOnline = true,
                isPipAvailable = true,
            ),
            uiData = PlayerViewModel.PlayerUiData(
                animeTitle = "ef - a tale of memories.",
                playerUpdate = PlayerUpdates.DoubleSpeed,
            ),
            playbackData = PlayerViewModel.PlayerPlaybackData(
                currentVolume = 0,
                currentBrightness = 0f,
            ),
            onBack = { },
            onPlayerEvent = { },
            mpvVolume = 0,
            readAhead = 0f,
            remaining = 0,
            playbackSpeed = 1f,
        )
    }
}

fun <T> playerControlsExitAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 300,
    easing = FastOutSlowInEasing,
)

fun <T> playerControlsEnterAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 100,
    easing = LinearOutSlowInEasing,
)
