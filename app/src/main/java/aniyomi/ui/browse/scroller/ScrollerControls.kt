package aniyomi.ui.browse.scroller

import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.PlayerViewModel.PlayerEvent
import eu.kanade.tachiyomi.ui.player.components.MpvSurface
import eu.kanade.tachiyomi.ui.player.controls.LocalPlayerButtonsClickEvent
import eu.kanade.tachiyomi.ui.player.controls.MiddlePlayerControls
import eu.kanade.tachiyomi.ui.player.controls.PlayerControls
import eu.kanade.tachiyomi.ui.player.controls.components.SeekbarWithTimers
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// AM (BROWSE_SCROLLER_CONTROLS) -->
/**
 * The feed's transport controls, which are the player's own.
 *
 * Nothing here is a feed-shaped reimplementation of a seekbar or a play
 * button: [MiddlePlayerControls] and [SeekbarWithTimers] are the same
 * composables PlayerControls lays out, driven from [ScrollerPlaybackState]
 * instead of from a PlayerViewModel. A card and the real player therefore
 * cannot drift apart in look or in behaviour, and a change to either
 * component lands in both places at once.
 *
 * The mute toggle follows the sound rather than the controls:
 * while the feed is muted it stays on the card whether the controls are up or
 * not - a muted clip has to say so, and the button that fixes it has to be
 * reachable without a gesture first - and once the sound is on it hides with
 * everything else. It keeps its place at the bottom-end corner either way.
 */
@Composable
fun ScrollerCardControls(
    playback: ScrollerPlaybackState,
    animeId: Long,
    modifier: Modifier = Modifier,
) {
    val visible = playback.controlsForAnimeId == animeId

    // The same reset the real player uses: any button press restarts the
    // timeout rather than letting it run out under an active hand.
    var resetControls by remember { mutableStateOf(false) }

    LaunchedEffect(visible, playback.controlsPaused, playback.isSeeking, resetControls) {
        if (visible && !playback.controlsPaused && !playback.isSeeking) {
            delay(playback.controlsTimeToDisappearMs.milliseconds)
            playback.hideControls()
        }
    }

    CompositionLocalProvider(
        LocalPlayerButtonsClickEvent provides { resetControls = !resetControls },
        LocalContentColor provides Color.White,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.55f),
                                0.35f to Color.Transparent,
                                0.65f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                ) {
                    MiddlePlayerControls(
                        // AM (CONTROL_BINDINGS): the feed draws the centre only, so it
                        // passes the one entry rather than the player's Middle region.
                        entries = listOf(ControlBinding(tap = ControlAction.PlayPause)),
                        isStopped = false,
                        isLoading = false,
                        isLoadingEpisode = false,
                        controlsShown = visible,
                        areControlsLocked = false,
                        showLoadingCircle = false,
                        paused = playback.controlsPaused,
                        gestureSeekAmount = null,
                        hasPrevious = false,
                        hasNext = false,
                        showMediaButtons = false,
                        onAction = { playback.togglePlayPause(animeId) },
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // The sound button and the seekbar share one bottom-end stack, so
            // the button is in the same corner it has always been in when it
            // is the only thing showing, and sits directly above the seekbar
            // when the controls are up - rather than being somewhere else in
            // each of the two states.
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(),
            ) {
                AnimatedVisibility(
                    // A muted card always offers its sound button. In
                    // particular, do not depend on activeAnimeId: process
                    // recreation clears it before the viewport reports again,
                    // which used to remove the only direct way back to sound.
                    visible = visible || playback.isMuted,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    IconButton(
                        onClick = { playback.toggleMuted(animeId) },
                        modifier = Modifier.padding(ScrollerSoundButtonPadding),
                    ) {
                        Icon(
                            imageVector = if (playback.isMuted) {
                                Icons.AutoMirrored.Filled.VolumeOff
                            } else {
                                Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(ScrollerSoundIconSize),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ScrollerSeekbar(
                        playback = playback,
                        animeId = animeId,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** The sound button's own spacing, unchanged from where it has always sat. */
private val ScrollerSoundButtonPadding = 6.dp
private val ScrollerSoundIconSize = 22.dp

/**
 * The player's seekbar over one card's instance.
 *
 * Chapters are empty and the timers are never inverted: a feed clip has
 * neither, and passing the real player's own defaults keeps the component
 * call honest rather than inventing a feed variant of it.
 */
@Composable
private fun ScrollerSeekbar(
    playback: ScrollerPlaybackState,
    animeId: Long,
    modifier: Modifier = Modifier,
) {
    SeekbarWithTimers(
        playerPosition = playback.controlsPosition,
        seekPosition = playback.seekPosition,
        isGestureSeeking = false,
        isSeeking = playback.isSeeking,
        duration = playback.controlsDuration,
        remaining = (playback.controlsDuration - playback.controlsPosition).coerceAtLeast(0f),
        readAheadValue = playback.controlsReadAhead,
        onValueChange = { playback.onSeek(animeId, it) },
        onValueChangeFinished = { playback.onSeekFinished(animeId, it) },
        timersInverted = Pair(false, false),
        positionTimerOnClick = {},
        durationTimerOnCLick = {},
        chapters = emptyList(),
        modifier = modifier,
    )
}

/**
 * A card expanded to fullscreen, over the card's own mpv instance.
 *
 * The instance is not handed anywhere and nothing is reloaded: the card's
 * surface leaves composition and this one adopts the same SurfaceTexture,
 * which is exactly what MpvSurface's persistent-texture contract is for. The
 * clip goes on playing, from the same position, with its separate audio track
 * and its source's http headers intact - none of which survives a handoff to
 * PlayerActivity, whose requests carry an episode row or a bare uri and
 * nothing else.
 *
 * The controls are [PlayerControls] itself, driven from the feed's own state.
 * Track sheets, panels, speed, pip, rotation, aspect and episode navigation
 * are not shown, because this is one standalone instance rather than a player
 * session and there is nothing behind those buttons to open.
 */
@Composable
fun ScrollerFullscreenPlayer(
    playback: ScrollerPlaybackState,
    player: ScrollerPlayer,
    animeId: Long,
    title: String,
    videoOutput: String,
) {
    var resetControls by remember { mutableStateOf(false) }
    // AM (BROWSE_SCROLLER_CONTROLS): hidden to begin with, as the real player
    // opens - expanding a clip is to see the clip, and chrome over it on
    // arrival is something to dismiss before that can happen.
    var controlsShown by remember { mutableStateOf(false) }

    LaunchedEffect(controlsShown, playback.controlsPaused, playback.isSeeking, resetControls) {
        if (controlsShown && !playback.controlsPaused && !playback.isSeeking) {
            delay(playback.controlsTimeToDisappearMs.milliseconds)
            controlsShown = false
        }
    }

    Dialog(
        onDismissRequest = playback::exitFullscreen,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Immersive, like the player: the clip is the whole screen for as long
        // as this is up, and the bars come back when it closes.
        val view = LocalView.current
        LaunchedEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            MpvSurface(
                player = player.player,
                videoOutput = videoOutput,
                onSurfaceAttachedChanged = player::onSurfaceAttachedChanged,
                onViewAvailable = player::onViewAvailable,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(animeId) {
                        detectTapGestures(
                            onTap = { controlsShown = !controlsShown },
                            onDoubleTap = { offset ->
                                val third = size.width / 3f
                                val zone = when {
                                    offset.x < third -> ScrollerPlaybackState.TapZone.Left
                                    offset.x > third * 2f -> ScrollerPlaybackState.TapZone.Right
                                    else -> ScrollerPlaybackState.TapZone.Center
                                }
                                // Centre collapses rather than expanding
                                // again, so the gesture that opened this is
                                // also the one that closes it.
                                if (zone == ScrollerPlaybackState.TapZone.Center) {
                                    playback.exitFullscreen()
                                } else {
                                    playback.onDoubleTap(animeId, zone)
                                }
                            },
                        )
                    },
            )

            CompositionLocalProvider(
                LocalPlayerButtonsClickEvent provides { resetControls = !resetControls },
                LocalContentColor provides Color.White,
            ) {
                PlayerControls(
                    stateData = PlayerViewModel.PlayerStateData(
                        maxVolume = 0,
                        isEpisodeOnline = false,
                        isPipAvailable = false,
                    ),
                    uiData = PlayerViewModel.PlayerUiData(
                        animeTitle = title,
                        controlsShown = controlsShown,
                        seekBarShown = controlsShown,
                        showLoadingCircle = false,
                        playerTimeToDisappearMs = playback.controlsTimeToDisappearMs,
                    ),
                    playbackData = PlayerViewModel.PlayerPlaybackData(
                        paused = playback.controlsPaused,
                        position = playback.controlsPosition.toInt(),
                        duration = playback.controlsDuration.toInt(),
                        currentVolume = 0,
                        currentBrightness = 0f,
                        isSeeking = playback.isSeeking,
                        seekPosition = playback.seekPosition,
                    ),
                    onBack = playback::exitFullscreen,
                    onPlayerEvent = { event ->
                        when (event) {
                            is PlayerEvent.PlayPause -> playback.togglePlayPause(animeId)
                            is PlayerEvent.Seek -> playback.onSeek(animeId, event.position.toFloat())
                            is PlayerEvent.SeekFinished ->
                                playback.onSeekFinished(animeId, event.position.toFloat())
                            // Everything else addresses a player session -
                            // episodes, sheets, panels, pip, casting - which a
                            // feed card is not part of.
                            else -> Unit
                        }
                    },
                    mpvVolume = null,
                    readAhead = playback.controlsReadAhead,
                    remaining = (playback.controlsDuration - playback.controlsPosition)
                        .coerceAtLeast(0f)
                        .toInt(),
                    playbackSpeed = null,
                    showTrackControls = false,
                    showMediaButtons = false,
                )
            }
        }
    }
}
// <-- AM (BROWSE_SCROLLER_CONTROLS)
