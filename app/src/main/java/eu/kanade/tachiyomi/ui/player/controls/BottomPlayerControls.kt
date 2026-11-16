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
import eu.kanade.tachiyomi.ui.player.controls.components.ControlAvailability
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
    /** Which bindings have something to act on for what is playing. */
    availability: ControlAvailability,
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
        // AM (CONTROL_BINDINGS): a binding with nothing to act on for what is
        // playing is not a position at all, so the ones behind it move up -
        // which is how chapters, picture-in-picture and cast behaved before the
        // row was rendered from stored bindings. The editor keeps every stored
        // position regardless; see ControlAvailability.isRendered.
        val isEditing = onSlotEdit != null || isArranging
        val renderable = entries.withIndex().filter { availability.isRendered(it.value, isEditing) }
        // Each half gets only the positions it can draw completely. Never constrain a
        // slot after measuring it: that produces the half-visible button this replaces.
        val leftButtons = renderable.filter { it.index % 2 == 0 }.take(sideCapacity)
        // Reverse the right half so positions count outward from the centre.
        val rightButtons = renderable.filter { it.index % 2 == 1 }.take(sideCapacity).reversed()

        fun freeOnSide(count: Int): Int = with(density) {
            val filledWidth = slotWidth * count + slotSpacing * (count - 1).coerceAtLeast(0)
            freeSlots(
                availableWidthPx = (regionWidth / 2).roundToPx(),
                filledWidthPx = filledWidth.roundToPx(),
                slotWidthPx = slotWidth.roundToPx(),
                slotSpacingPx = slotSpacing.roundToPx(),
            )
        }
        // AM (CHAPTER_SLOT_FILLS_THE_GAP) -->
        // What the row is not using, which is the gap between the two halves:
        // the left half packs to the start and the right half to the end (see
        // splitBottomArrangement), so every slot that is not drawn leaves its
        // width in the middle.
        //
        // Counted from the slots that actually take width - an unbound position
        // is 0.dp outside arrange mode - so this is the real gap rather than a
        // slot count times a width.
        val drawnCount = (leftButtons + rightButtons).count { it.value.tap != ControlAction.None }
        val usedWidth = slotWidth * drawnCount + slotSpacing * (drawnCount - 1).coerceAtLeast(0)
        // Editing and arranging keep every slot uniform: positions have to stay
        // the size they will be dragged between.
        val chapterExtraWidth = if (isEditing) {
            0.dp
        } else {
            (regionWidth - usedWidth - slotSpacing * 2).coerceAtLeast(0.dp)
        }
        // <-- AM (CHAPTER_SLOT_FILLS_THE_GAP)

        // Do not offer free positions over stored positions hidden by a narrow window.
        val allEntriesVisible = leftButtons.size + rightButtons.size == renderable.size
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
            // AM (CHAPTER_SLOT_FILLS_THE_GAP)
            chapterExtraWidth = chapterExtraWidth,
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
            // AM (CHAPTER_SLOT_FILLS_THE_GAP)
            chapterExtraWidth = chapterExtraWidth,
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

// AM (CHAPTER_SLOT_FILLS_THE_GAP) -->
/** Roughly what the chapter slot's icon and timestamp occupy on their own. */
private val CHAPTER_ICON_WIDTH = 76.dp

/** Below this much spare room a title is a couple of letters and an ellipsis. */
private val CHAPTER_TITLE_MIN_WIDTH = 72.dp

/** Whether this action is the chapter indicator, seeded default or not. */
private fun ControlAction.isChapters(): Boolean = when (this) {
    ControlAction.Chapters -> true
    is ControlAction.CustomButton -> ControlAction.nativeActionForButtonId(id) == ControlAction.Chapters
    else -> false
}
// <-- AM (CHAPTER_SLOT_FILLS_THE_GAP)

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
    // AM (CHAPTER_SLOT_FILLS_THE_GAP): the row's unused width, offered to the
    // chapter slot - it is the one control here with something to say rather
    // than just an icon to show.
    chapterExtraWidth: Dp = 0.dp,
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
            // AM (CHAPTER_SLOT_FILLS_THE_GAP)
            val extra = if (button.isChapters() && showChapterIndicator && currentChapter != null) {
                chapterExtraWidth
            } else {
                0.dp
            }
            add(
                ReorderableControlSlot(
                    id = ControlSlotId(ControlRegion.Bottom, index),
                    width = if (button == ControlAction.None && !isArranging) 0.dp else slotWidth + extra,
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
                                // AM (CHAPTER_SLOT_FILLS_THE_GAP)
                                width = slotWidth + extra,
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
                        is ControlAction.CustomButton -> when {
                            ControlAction.nativeActionForButtonId(button.id) == ControlAction.Cast &&
                                castIncluded && castEnabled -> {
                                // MediaRouteButton is a platform view; retain its renderer for
                                // the seeded Cast button even though all definitions are Lua.
                                CastButton(loading = castLoading, error = castError)
                            }
                            // AM (CHAPTER_SLOT_FILLS_THE_GAP) -->
                            // Same exception as Cast above, for the same reason: the
                            // chapter indicator is not an icon, it is a readout, and the
                            // seeded default is the only form it ever arrives in - so the
                            // ControlAction.Chapters branch further up never ran for it.
                            // It drew a plain icon that showed no chapter and, with
                            // onClick = {}, did not even open the chapter sheet.
                            button.isChapters() && showChapterIndicator && currentChapter != null -> {
                                ChapterSlot(
                                    showChapterIndicator = showChapterIndicator,
                                    currentChapter = currentChapter,
                                    width = slotWidth + extra,
                                    onClick = { onOpenSheet(Sheets.Chapters) },
                                )
                            }
                            // <-- AM (CHAPTER_SLOT_FILLS_THE_GAP)
                            else -> controlActionIcon(button)?.let { icon ->
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
    // AM (CHAPTER_SLOT_FILLS_THE_GAP): this slot's full width, icon plus
    // whatever the row had spare.
    width: Dp,
    onClick: () -> Unit,
) {
    if (!showChapterIndicator || currentChapter == null) return
    // AM (CHAPTER_SLOT_OVERFLOW_FIX) -->
    // CurrentChapter's title Text had overflow = TextOverflow.Ellipsis, but
    // nothing constrained the slot's width for that to ever take effect -
    // Compose just let it grow to its full intrinsic width. With a long
    // chapter title that meant this slot could grow arbitrarily wide, pushing
    // every other tightly-packed button in the row (Lock, Screenshot, etc.)
    // out toward, and sometimes past, the screen edge.
    //
    // AM (CHAPTER_SLOT_FILLS_THE_GAP): bounded rather than dropped. That fix
    // removed the title because nothing capped the width; the cap now exists -
    // this slot is given exactly the width the row is not using, so the
    // Ellipsis the title always had finally has something to ellipsize against
    // and no other button can be pushed anywhere. Below the threshold there is
    // not enough room for a title to read as anything but a few letters and an
    // ellipsis, so it stays icon and timestamp as before.
    // <-- AM (CHAPTER_SLOT_OVERFLOW_FIX)
    CurrentChapter(
        chapter = currentChapter,
        showTitle = width - CHAPTER_ICON_WIDTH >= CHAPTER_TITLE_MIN_WIDTH,
        onClick = onClick,
        modifier = Modifier.width(width),
    )
}
