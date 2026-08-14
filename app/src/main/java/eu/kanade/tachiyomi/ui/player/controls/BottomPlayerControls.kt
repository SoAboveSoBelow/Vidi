/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import animiru.domain.player.model.BottomPlayerButton
import dev.vivvvek.seeker.Segment
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.components.CurrentChapter
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.util.system.castIncluded
import tachiyomi.cast.CastButton
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Renders the fixed-length (see [animiru.domain.player.service.GesturePreferences.MAX_BOTTOM_PLAYER_BUTTONS])
 * list of bottom-row positions, split into a left half (positions 1..half) and a right half
 * (the rest). Each half packs its buttons tightly together; any slack from empty positions
 * or unused width collects as a single gap between the two halves instead of being spread
 * out as extra spacing between every icon.
 */
@Composable
fun BottomPlayerControls(
    selectedButtons: List<BottomPlayerButton?>,
    playbackSpeed: Float,
    currentChapter: Segment?,
    showChapterIndicator: Boolean,
    isPipAvailable: Boolean,
    castEnabled: Boolean,
    castLoading: Boolean,
    castError: Boolean,
    onLockControls: () -> Unit,
    onCycleRotation: () -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    onOpenSheet: (Sheets) -> Unit,
    onPipClick: () -> Unit,
    onAspectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val half = (selectedButtons.size + 1) / 2
    val leftButtons = selectedButtons.take(half).filterNotNull()
    val rightButtons = selectedButtons.drop(half).filterNotNull()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ButtonGroup(
            buttons = leftButtons,
            playbackSpeed = playbackSpeed,
            currentChapter = currentChapter,
            showChapterIndicator = showChapterIndicator,
            isPipAvailable = isPipAvailable,
            castEnabled = castEnabled,
            castLoading = castLoading,
            castError = castError,
            onLockControls = onLockControls,
            onCycleRotation = onCycleRotation,
            onPlaybackSpeedChange = onPlaybackSpeedChange,
            onOpenSheet = onOpenSheet,
            onPipClick = onPipClick,
            onAspectClick = onAspectClick,
        )
        ButtonGroup(
            buttons = rightButtons,
            playbackSpeed = playbackSpeed,
            currentChapter = currentChapter,
            showChapterIndicator = showChapterIndicator,
            isPipAvailable = isPipAvailable,
            castEnabled = castEnabled,
            castLoading = castLoading,
            castError = castError,
            onLockControls = onLockControls,
            onCycleRotation = onCycleRotation,
            onPlaybackSpeedChange = onPlaybackSpeedChange,
            onOpenSheet = onOpenSheet,
            onPipClick = onPipClick,
            onAspectClick = onAspectClick,
        )
    }
}

@Composable
private fun ButtonGroup(
    buttons: List<BottomPlayerButton>,
    playbackSpeed: Float,
    currentChapter: Segment?,
    showChapterIndicator: Boolean,
    isPipAvailable: Boolean,
    castEnabled: Boolean,
    castLoading: Boolean,
    castError: Boolean,
    onLockControls: () -> Unit,
    onCycleRotation: () -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    onOpenSheet: (Sheets) -> Unit,
    onPipClick: () -> Unit,
    onAspectClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        buttons.forEach { button ->
            when (button) {
                BottomPlayerButton.Lock -> ControlsButton(
                    Icons.Default.LockOpen,
                    onClick = onLockControls,
                )
                BottomPlayerButton.Rotation -> ControlsButton(
                    icon = Icons.Default.ScreenRotation,
                    onClick = onCycleRotation,
                )
                BottomPlayerButton.Speed -> ControlsButton(
                    text = stringResource(AYMR.strings.player_speed, playbackSpeed),
                    onClick = { onPlaybackSpeedChange(if (playbackSpeed >= 2) 0.25f else playbackSpeed + 0.25f) },
                    onLongClick = { onOpenSheet(Sheets.PlaybackSpeed) },
                )
                BottomPlayerButton.Chapters -> ChapterSlot(
                    showChapterIndicator = showChapterIndicator,
                    currentChapter = currentChapter,
                    onClick = { onOpenSheet(Sheets.Chapters) },
                )
                BottomPlayerButton.PictureInPicture -> if (isPipAvailable) {
                    ControlsButton(
                        Icons.Default.PictureInPictureAlt,
                        onClick = onPipClick,
                    )
                }
                BottomPlayerButton.AspectRatio -> ControlsButton(
                    Icons.Default.AspectRatio,
                    onClick = onAspectClick,
                )
                BottomPlayerButton.Screenshot -> ControlsButton(
                    Icons.Default.PhotoCamera,
                    onClick = { onOpenSheet(Sheets.Screenshot) },
                )
                // AM (CAST) -->
                BottomPlayerButton.Cast -> if (castIncluded && castEnabled) {
                    CastButton(
                        loading = castLoading,
                        error = castError,
                        verticalSpacing = MaterialTheme.padding.small,
                    )
                }
                // <-- AM (CAST)
            }
        }
    }
}

@Composable
private fun ChapterSlot(
    showChapterIndicator: Boolean,
    currentChapter: Segment?,
    onClick: () -> Unit,
) {
    if (!showChapterIndicator || currentChapter == null) return
    CurrentChapter(
        chapter = currentChapter,
        onClick = onClick,
    )
}
