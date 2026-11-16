package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import animiru.domain.player.model.ControlAction

// AM (CONTROL_BINDINGS) -->
/**
 * One icon per action, for every surface that draws a bound slot.
 *
 * Null where the action has no icon of its own: None is an empty slot, and auto-play
 * draws a switch rather than a button, so both are the caller's to handle.
 */
fun controlActionIcon(action: ControlAction): ImageVector? = when (action) {
    ControlAction.None -> null
    ControlAction.ToggleAutoPlay -> null
    ControlAction.PlayPause -> Icons.Filled.PlayCircleOutline
    ControlAction.SeekBackward -> Icons.Filled.Replay10
    ControlAction.SeekForward -> Icons.Filled.Forward10
    ControlAction.PreviousEpisode -> Icons.Filled.SkipPrevious
    ControlAction.NextEpisode -> Icons.Filled.SkipNext
    ControlAction.PreviousSource -> Icons.Filled.FastRewind
    ControlAction.NextSource -> Icons.Filled.FastForward
    ControlAction.Screenshot -> Icons.Filled.PhotoCamera
    ControlAction.Lock -> Icons.Filled.LockOpen
    ControlAction.CycleRotation -> Icons.Filled.ScreenRotation
    ControlAction.PlaybackSpeed -> Icons.Filled.Speed
    ControlAction.Chapters -> Icons.Filled.Bookmarks
    ControlAction.PictureInPicture -> Icons.Filled.PictureInPictureAlt
    ControlAction.AspectRatio -> Icons.Filled.AspectRatio
    ControlAction.Cast -> Icons.Filled.Cast
    ControlAction.SubtitleTracks -> Icons.Filled.Subtitles
    ControlAction.SubtitleSettings -> Icons.Filled.Subtitles
    ControlAction.AudioTracks -> Icons.Filled.Audiotrack
    ControlAction.AudioDelay -> Icons.Filled.Timer
    ControlAction.VideoQuality -> Icons.Filled.HighQuality
    ControlAction.VideoFilters -> Icons.Filled.Tune
    ControlAction.Overflow -> Icons.Filled.MoreVert
    ControlAction.PrimaryCustomButton -> Icons.Filled.TouchApp
    is ControlAction.CustomButton -> action.icon?.let(::controlButtonIcon)
        ?: ControlAction.nativeActionForButtonId(action.id)?.let {
            controlActionIcon(it) ?: Icons.Filled.PlayCircleOutline
        }
        ?: Icons.Filled.TouchApp
    is ControlAction.CustomKey -> Icons.Filled.TouchApp
}

data class ControlButtonIconChoice(
    val key: String,
    val action: ControlAction,
    val icon: ImageVector,
)

val controlButtonIconChoices: List<ControlButtonIconChoice> = ControlAction.selectable
    .filterNot { it == ControlAction.None }
    .map { action ->
        ControlButtonIconChoice(
            key = action.key,
            action = action,
            icon = controlActionIcon(action) ?: Icons.Filled.PlayCircleOutline,
        )
    }

fun controlButtonIcon(key: String): ImageVector? = ControlAction.fromKey(key)?.let { action ->
    controlActionIcon(action) ?: Icons.Filled.PlayCircleOutline
}
// <-- AM (CONTROL_BINDINGS)
