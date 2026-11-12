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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding
import animiru.domain.player.model.ControlSlotId
import animiru.domain.player.model.ControlRegion
import animiru.domain.player.model.freeSlots
import animiru.domain.player.model.slotCapacity
import dev.vivvvek.seeker.Segment
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.components.CurrentChapter
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.ControlSlotMove
import eu.kanade.tachiyomi.ui.player.controls.components.EmptySlotContent
import eu.kanade.tachiyomi.ui.player.controls.components.ReorderableControlRow
import eu.kanade.tachiyomi.ui.player.controls.components.ReorderableControlSlot
import eu.kanade.tachiyomi.ui.player.controls.components.controlActionIcon
import eu.kanade.tachiyomi.util.system.castIncluded
import tachiyomi.cast.CastButton
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * AM (CONTROL_BINDINGS): renders the Bottom region's ordered entries, split into a left
 * half and a right half. Each half packs its buttons tightly together; any slack from
 * empty entries or unused width collects as a single gap between the two halves instead
 * of being spread out as extra spacing between every icon.
 *
 * How many entries render is measured here rather than fixed. The stored list keeps its
 * full length either way - a narrow window shows fewer buttons, a wider one shows them
 * again, and nothing is written back.
 */
@Composable
fun BottomPlayerControls(
    entries: List<ControlBinding>,
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
    // AM (CONTROL_BINDINGS): editor mode, as in the other rows.
    onSlotEdit: ((Int) -> Unit)? = null,
    // AM (CONTROL_BINDINGS): hold-and-drag reorder; -1 or 1 per step.
    onSlotMove: ((List<ControlSlotMove>) -> Unit)? = null,
    onArrangeStart: ((Int) -> Unit)? = null,
    /** Opens the picker for a position that does not exist yet, inserting at it. */
    onSlotInsert: ((Int) -> Unit)? = null,
    /**
     * True while any row is being arranged. Every row reveals its free positions, not
     * just the one holding the slot - a control being moved is as likely to be wanted
     * in another bar as further along its own.
     */
    isArranging: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        // Held as a local because BoxWithConstraints' receiver is not in scope inside
        // with(density) - the Density receiver shadows it.
        val regionWidth = maxWidth
        val slotSpacing = MaterialTheme.padding.extraSmall
        val slotWidth = BOTTOM_ICON_SIZE + MaterialTheme.padding.medium * 2
        val sideCapacity = with(density) {
            slotCapacity(
                regionWidthPx = (regionWidth / 2).roundToPx(),
                anchorsWidthPx = 0,
                minSlotWidthPx = slotWidth.roundToPx(),
                slotSpacingPx = slotSpacing.roundToPx(),
            )
        }
        // Each half gets only the positions it can draw completely. Never constrain a
        // slot after measuring it: that produces the half-visible button this replaces.
        val visible = entries.withIndex().filter { (index, _) -> index / 2 < sideCapacity }
        val leftButtons = visible.filter { it.index % 2 == 0 }
        // Reverse the right half so positions count outward from the centre.
        val rightButtons = visible.filter { it.index % 2 == 1 }.reversed()

        fun freeOnSide(count: Int): Int = with(density) {
            val filledWidth = slotWidth * count + slotSpacing * (count - 1).coerceAtLeast(0)
            freeSlots(
                availableWidthPx = (regionWidth / 2).roundToPx(),
                filledWidthPx = filledWidth.roundToPx(),
                slotWidthPx = slotWidth.roundToPx(),
                slotSpacingPx = slotSpacing.roundToPx(),
            )
        }
        // Do not offer free positions over stored positions hidden by a narrow window.
        val allEntriesVisible = visible.size == entries.size
        val leftFreeCount = if (allEntriesVisible) freeOnSide(leftButtons.size) else 0
        val rightFreeCount = if (allEntriesVisible) freeOnSide(rightButtons.size) else 0
        val leftFree = (entries.size until entries.size + leftFreeCount * 2)
            .filter { it % 2 == 0 }
            .take(leftFreeCount)
        val rightFree = (entries.size until entries.size + rightFreeCount * 2)
            .filter { it % 2 == 1 }
            .take(rightFreeCount)

        val leftSlots = ButtonGroup(
            buttons = leftButtons,
            trailingFree = leftFree,
            onSlotEdit = onSlotEdit,
            onArrangeStart = onArrangeStart,
            onSlotInsert = onSlotInsert,
            isArranging = isArranging,
            slotWidth = slotWidth,
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
        val rightSlots = ButtonGroup(
            buttons = rightButtons,
            leadingFree = rightFree,
            onSlotEdit = onSlotEdit,
            onArrangeStart = onArrangeStart,
            onSlotInsert = onSlotInsert,
            isArranging = isArranging,
            slotWidth = slotWidth,
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
        val leftSlotIds = leftSlots.mapTo(mutableSetOf()) { it.id }
        ReorderableControlRow(
            slots = leftSlots + rightSlots,
            isEditing = onSlotEdit != null,
            isArranging = isArranging,
            modifier = Modifier.fillMaxWidth(),
            onMove = { moves ->
                onSlotMove?.invoke(moves)
            },
            horizontalArrangement = { displayedSlots ->
                splitBottomArrangement(displayedSlots.count { it.id in leftSlotIds }, slotSpacing)
            },
        )
    }
}

/**
 * The narrowest a slot can be and still hold a tappable icon: the Material minimum
 * touch target. Capacity is this and the region's width, nothing else.
 */
/**
 * What this row's buttons draw at - see ControlsButton's own default.
 *
 * The slot's width is this plus its horizontal padding on both sides; measuring a
 * bare touch target instead counted more slots than the row can actually lay out.
 */
private val BOTTOM_ICON_SIZE = 20.dp

/** Packs the left positions at the start and right positions at the end of one row. */
private fun splitBottomArrangement(split: Int, spacing: Dp): Arrangement.Horizontal =
    object : Arrangement.Horizontal {
        override val spacing: Dp = spacing

        override fun Density.arrange(
            totalSize: Int,
            sizes: IntArray,
            layoutDirection: LayoutDirection,
            outPositions: IntArray,
        ) {
            val gap = spacing.roundToPx()
            var position = 0
            for (index in 0 until minOf(split, sizes.size)) {
                outPositions[index] = position
                position += sizes[index] + gap
            }
            position = totalSize
            for (index in sizes.lastIndex downTo split) {
                position -= sizes[index]
                outPositions[index] = position
                position -= gap
            }
        }
    }

@Composable
private fun ButtonGroup(
    buttons: List<IndexedValue<ControlBinding>>,
    /** Free positions drawn after this group's buttons, nearest the centre gap. */
    trailingFree: List<Int> = emptyList(),
    /** Free positions drawn before them, for the group that packs to the right. */
    leadingFree: List<Int> = emptyList(),
    onSlotEdit: ((Int) -> Unit)?,
    onArrangeStart: ((Int) -> Unit)?,
    onSlotInsert: ((Int) -> Unit)?,
    isArranging: Boolean,
    slotWidth: Dp,
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
): List<ReorderableControlSlot> {
    val slots = buildList {
        fun freeSlot(index: Int): ReorderableControlSlot = ReorderableControlSlot(
            id = ControlSlotId(ControlRegion.Bottom, index),
            width = 0.dp,
            arrangingWidth = slotWidth,
            onEdit = onSlotInsert?.let { insert -> { insert(index) } },
            showBorder = isArranging,
        ) {
            if (isArranging) EmptySlotContent()
        }

        leadingFree.forEach { add(freeSlot(it)) }
        buttons.forEach { (index, entry) ->
            val button = entry.tap
            add(
                ReorderableControlSlot(
                    id = ControlSlotId(ControlRegion.Bottom, index),
                    width = if (button == ControlAction.None && !isArranging) 0.dp else slotWidth,
                    arrangingWidth = slotWidth,
                    onEdit = onSlotEdit?.let { edit -> { edit(index) } },
                    canDrag = onSlotEdit != null,
                    onArrangeStart = { onArrangeStart?.invoke(index) },
                ) {
                    when (button) {
                        ControlAction.None -> EmptySlotContent()
                        ControlAction.Lock -> ControlsButton(
                            Icons.Default.LockOpen,
                            onClick = onLockControls,
                        )
                        ControlAction.CycleRotation -> ControlsButton(
                            Icons.Default.ScreenRotation,
                            onClick = onCycleRotation,
                        )
                        ControlAction.PlaybackSpeed -> ControlsButton(
                            text = stringResource(AYMR.strings.player_speed, playbackSpeed),
                            onClick = { onPlaybackSpeedChange(if (playbackSpeed >= 2) 0.25f else playbackSpeed + 0.25f) },
                            onLongClick = { onOpenSheet(Sheets.PlaybackSpeed) },
                        )
                        ControlAction.Chapters -> if (showChapterIndicator && currentChapter != null) {
                            ChapterSlot(
                                showChapterIndicator = showChapterIndicator,
                                currentChapter = currentChapter,
                                onClick = { onOpenSheet(Sheets.Chapters) },
                            )
                        } else if (onSlotEdit != null) {
                            ControlsButton(icon = controlActionIcon(button)!!, onClick = {})
                        }
                        ControlAction.PictureInPicture -> if (isPipAvailable) {
                            ControlsButton(Icons.Default.PictureInPictureAlt, onClick = onPipClick)
                        } else if (onSlotEdit != null) {
                            ControlsButton(icon = controlActionIcon(button)!!, onClick = {})
                        }
                        ControlAction.AspectRatio -> ControlsButton(Icons.Default.AspectRatio, onClick = onAspectClick)
                        ControlAction.Screenshot -> ControlsButton(
                            Icons.Default.PhotoCamera,
                            onClick = { onOpenSheet(Sheets.Screenshot) },
                        )
                        ControlAction.Cast -> if (castIncluded && castEnabled) {
                            CastButton(loading = castLoading, error = castError)
                        } else if (onSlotEdit != null) {
                            ControlsButton(icon = controlActionIcon(button)!!, onClick = {})
                        }
                        is ControlAction.CustomButton -> if (
                            ControlAction.nativeActionForButtonId(button.id) == ControlAction.Cast &&
                            castIncluded && castEnabled
                        ) {
                            // MediaRouteButton is a platform view; retain its renderer for
                            // the seeded Cast button even though all definitions are Lua.
                            CastButton(loading = castLoading, error = castError)
                        } else {
                            controlActionIcon(button)?.let { icon ->
                                ControlsButton(icon = icon, onClick = {})
                            }
                        }
                        else -> controlActionIcon(button)?.let { icon ->
                            ControlsButton(icon = icon, onClick = {})
                        }
                    }
                },
            )
        }
        trailingFree.forEach { add(freeSlot(it)) }
    }
    return slots
}

@Composable
private fun ChapterSlot(
    showChapterIndicator: Boolean,
    currentChapter: Segment?,
    onClick: () -> Unit,
) {
    if (!showChapterIndicator || currentChapter == null) return
    // AM (CHAPTER_SLOT_OVERFLOW_FIX) -->
    // CurrentChapter's title Text had overflow = TextOverflow.Ellipsis, but
    // nothing constrained the slot's width for that to ever take effect -
    // Compose just let it grow to its full intrinsic width. With a long
    // chapter title that meant this slot could grow arbitrarily wide, pushing
    // every other tightly-packed button in the row (Lock, Screenshot, etc.)
    // out toward, and sometimes past, the screen edge. Dropping the title here
    // (showTitle = false, icon + timestamp only) removes the unbounded part
    // entirely instead of just capping it.
    CurrentChapter(
        chapter = currentChapter,
        showTitle = false,
        onClick = onClick,
    )
    // <-- AM (CHAPTER_SLOT_OVERFLOW_FIX)
}
