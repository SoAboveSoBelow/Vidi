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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.service.GesturePreferences
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.PlayerViewModel.PlayerEvent
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessSlider
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.SeekbarWithTimers
import eu.kanade.tachiyomi.ui.player.controls.components.TextPlayerUpdate
import eu.kanade.tachiyomi.ui.player.controls.components.ThumbnailPreview
import eu.kanade.tachiyomi.ui.player.controls.components.VolumeSlider
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

// AM (MEDIA_CONTROLS) -->
/**
 * Maps a gesture selection to the icon shown on the in-player previous/next buttons,
 * so the button reflects what it will actually do. `null` means the gesture is
 * [SingleActionGesture.None] and the button should not be shown at all.
 */
private fun mediaGestureIcon(gesture: SingleActionGesture, isNext: Boolean): ImageVector? {
    return when (gesture) {
        SingleActionGesture.None -> null
        SingleActionGesture.Seek -> if (isNext) Icons.Filled.Forward10 else Icons.Filled.Replay10
        SingleActionGesture.PlayPause -> Icons.Filled.PlayCircleOutline
        SingleActionGesture.Switch -> if (isNext) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious
        SingleActionGesture.Custom -> Icons.Filled.TouchApp
        SingleActionGesture.Screenshot -> Icons.Filled.PhotoCamera
    }
}
// <-- AM (MEDIA_CONTROLS)

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
    pausedForCache: Boolean?,
    coreIdle: Boolean?,
    readAhead: Float?,
    remaining: Int?,
    playbackSpeed: Float?,
    modifier: Modifier = Modifier,
) {
    val transparentOverlay by animateFloatAsState(
        if (uiData.controlsShown && !uiData.isControlsLocked) .8f else 0f,
        animationSpec = playerControlsExitAnimationSpec(),
        label = "controls_transparent_overlay",
    )

    // AM (MEDIA_CONTROLS) -->
    val gesturePreferences = remember { Injekt.get<GesturePreferences>() }
    val mediaPreviousGesture = remember { gesturePreferences.mediaPreviousGesture.get() }
    val mediaNextGesture = remember { gesturePreferences.mediaNextGesture.get() }
    val previousIcon = remember(mediaPreviousGesture) { mediaGestureIcon(mediaPreviousGesture, isNext = false) }
    val nextIcon = remember(mediaNextGesture) { mediaGestureIcon(mediaNextGesture, isNext = true) }
    // Episode boundaries are only relevant when the gesture actually switches episodes;
    // seeking/play-pause/custom/screenshot aren't affected by which episode is playing.
    val previousEnabled = if (mediaPreviousGesture == SingleActionGesture.Switch) {
        stateData.hasPreviousEpisode
    } else {
        true
    }
    val nextEnabled = if (mediaNextGesture == SingleActionGesture.Switch) {
        stateData.hasNextEpisode
    } else {
        true
    }
    // <-- AM (MEDIA_CONTROLS)

    // AM (SYSTEM_BAR_CONTROLS_INSET) -->
    // When "show system status bar with controls" is on, PlayerControls' edge-to-edge
    // ConstraintLayout otherwise draws the top settings row and bottom seekbar right
    // underneath the (now-visible) status/navigation bars. Only add the extra offset
    // while those bars are actually being shown alongside controls - the normal
    // immersive/hidden state shouldn't get any extra padding.
    val statusBarTopPadding = if (uiData.statusBarShown) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    } else {
        0.dp
    }
    val navigationBarBottomPadding = if (uiData.statusBarShown) {
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
    val navigationBarStartPadding = if (uiData.statusBarShown) {
        WindowInsets.navigationBars.asPaddingValues().calculateStartPadding(LayoutDirection.Ltr)
    } else {
        0.dp
    }
    val navigationBarEndPadding = if (uiData.statusBarShown) {
        WindowInsets.navigationBars.asPaddingValues().calculateEndPadding(LayoutDirection.Ltr)
    } else {
        0.dp
    }
    // <-- AM (LANDSCAPE_NAV_BAR_INSET)

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
            val (topLeftControls, topRightControls) = createRefs()
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

            AnimatedVisibility(
                visible = (
                    uiData.controlsShown && (!uiData.isControlsLocked || playbackData.gestureSeekAmount != null)
                    ) ||
                    (pausedForCache == true || (coreIdle == true && !playbackData.paused)) ||
                    uiData.isLoadingEpisode,
                enter = fadeIn(playerControlsEnterAnimationSpec()),
                exit = fadeOut(playerControlsExitAnimationSpec()),
                modifier = Modifier.constrainAs(centerControls) {
                    end.linkTo(parent.absoluteRight)
                    start.linkTo(parent.absoluteLeft)
                    top.linkTo(parent.top)
                    bottom.linkTo(parent.bottom)
                },
            ) {
                MiddlePlayerControls(
                    // AM (MEDIA_CONTROLS) -->
                    previousIcon = previousIcon,
                    hasPrevious = previousEnabled,
                    onSkipPrevious = { onPlayerEvent(PlayerEvent.NextEpisode(false)) },
                    nextIcon = nextIcon,
                    hasNext = nextEnabled,
                    onSkipNext = { onPlayerEvent(PlayerEvent.NextEpisode(true)) },
                    // <-- AM (MEDIA_CONTROLS)
                    isStopped = stateData.isStopped,
                    isLoading = pausedForCache == true || (coreIdle == true && !playbackData.paused),
                    isLoadingEpisode = uiData.isLoadingEpisode,
                    controlsShown = uiData.controlsShown,
                    areControlsLocked = uiData.isControlsLocked,
                    showLoadingCircle = uiData.showLoadingCircle,
                    paused = playbackData.paused,
                    gestureSeekAmount = playbackData.gestureSeekAmount,
                    onPlayPauseClick = { onPlayerEvent(PlayerEvent.PlayPause) },
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
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
                modifier = Modifier.constrainAs(topLeftControls) {
                    top.linkTo(parent.top, MaterialTheme.padding.medium + statusBarTopPadding)
                    start.linkTo(parent.start)
                    width = Dimension.fillToConstraints
                    end.linkTo(topRightControls.start)
                },
            ) {
                TopLeftPlayerControls(
                    animeTitle = uiData.animeTitle,
                    mediaTitle = uiData.mediaTitle,
                    onTitleClick = { onPlayerEvent(PlayerEvent.ShowEpisodeDialog) },
                    onBackClick = onBack,
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
                modifier = Modifier.constrainAs(topRightControls) {
                    top.linkTo(parent.top, MaterialTheme.padding.medium + statusBarTopPadding)
                    end.linkTo(parent.end)
                },
            ) {
                TopRightPlayerControls(
                    autoPlayEnabled = uiData.autoPlayEnabled,
                    onToggleAutoPlay = { onPlayerEvent(PlayerEvent.SetAutoPlay(it)) },
                    onSubtitlesClick = { onPlayerEvent(PlayerEvent.SetSheet(Sheets.SubtitleTracks)) },
                    onSubtitlesLongClick = { onPlayerEvent(PlayerEvent.SetPanel(Panels.SubtitleSettings)) },
                    onAudioClick = { onPlayerEvent(PlayerEvent.SetSheet(Sheets.AudioTracks)) },
                    onAudioLongClick = { onPlayerEvent(PlayerEvent.SetPanel(Panels.AudioDelay)) },
                    onQualityClick = { onPlayerEvent(PlayerEvent.SetSheet(Sheets.QualityTracks)) },
                    isEpisodeOnline = stateData.isEpisodeOnline,
                    onMoreClick = { onPlayerEvent(PlayerEvent.SetSheet(Sheets.More)) },
                    onMoreLongClick = { onPlayerEvent(PlayerEvent.SetPanel(Panels.VideoFilters)) },
                )
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
                    selectedButtons = uiData.bottomPlayerButtons,
                    playbackSpeed = playbackSpeed ?: uiData.playerSpeedPref,
                    showChapterIndicator = uiData.showChapterIndicator,
                    currentChapter = stateData.currentChapter,
                    isPipAvailable = stateData.isPipAvailable,
                    castEnabled = uiData.enableCast,
                    castLoading = stateData.isLoadingCasting,
                    castError = stateData.isErrorCasting,
                    onLockControls = { onPlayerEvent(PlayerEvent.LockControls(true)) },
                    onCycleRotation = { onPlayerEvent(PlayerEvent.CycleRotation) },
                    onPlaybackSpeedChange = { onPlayerEvent(PlayerEvent.ChangeSpeed(it)) },
                    onOpenSheet = { onPlayerEvent(PlayerEvent.SetSheet(it)) },
                    onPipClick = { onPlayerEvent(PlayerEvent.EnterPip) },
                    onAspectClick = { onPlayerEvent(PlayerEvent.ChangeAspect) },
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
                mediaTitle = "Ep. 2 - Upon a Time",
                playerUpdate = PlayerUpdates.DoubleSpeed,
            ),
            playbackData = PlayerViewModel.PlayerPlaybackData(
                currentVolume = 0,
                currentBrightness = 0f,
            ),
            onBack = { },
            onPlayerEvent = { },
            mpvVolume = 0,
            pausedForCache = false,
            coreIdle = false,
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
