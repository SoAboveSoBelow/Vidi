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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding
import animiru.domain.player.model.ControlSlotId
import animiru.domain.player.model.ControlRegion
import animiru.domain.player.model.freeSlots
import eu.kanade.tachiyomi.ui.player.controls.components.AutoPlaySwitch
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.ControlSlotMove
import eu.kanade.tachiyomi.ui.player.controls.components.ReorderableControlRow
import eu.kanade.tachiyomi.ui.player.controls.components.ReorderableControlSlot
import eu.kanade.tachiyomi.ui.player.controls.components.StaticSlot
import eu.kanade.tachiyomi.ui.player.controls.components.EmptySlotContent
import eu.kanade.tachiyomi.ui.player.controls.components.controlActionIcon
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.presentation.core.components.material.padding

/**
 * AM (CONTROL_BINDINGS): the top bar's button cluster, rendered from the TopBar region
 * rather than from a fixed list of buttons with a parameter each.
 *
 * Every slot goes through the one handler: tap, long press and double tap are whatever
 * the binding says, and an unbound double tap stays null so the single tap still fires
 * immediately. How many slots render is measured, not fixed - the stored list keeps its
 * full length and a narrower window simply shows fewer of them.
 */
@Composable
fun TopRightPlayerControls(
    entries: List<ControlBinding>,
    customButtons: List<CustomButton>,
    customToggleStates: Map<Long, Boolean>,
    isEpisodeOnline: Boolean?,
    onAction: (ControlAction) -> Unit,
    modifier: Modifier = Modifier,
    // AM (CONTROL_BINDINGS): non-null puts the row in the editor, where a tap opens
    // the slot rather than running it and the unfilled positions become visible.
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
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // Held as a local because BoxWithConstraints' receiver is not in scope inside
        // with(density) - the Density receiver shadows it.
        val regionWidth = maxWidth
        val slotWidth = MaterialTheme.padding.mediumSmall * 2 + TOP_ICON_SIZE
        // The autoplay switch is the one control in this row that is not an icon, and
        // it is 28dp wider than one. Charging it as a normal slot is what let a bar
        // already holding its last control offer one more position: the row reported
        // room it did not have, by exactly the switch's excess.
        val switchWidth = MaterialTheme.padding.mediumSmall * 2 + AUTO_PLAY_SWITCH_WIDTH
        // Take the longest prefix whose REAL arranging widths fit. A minimum-width
        // slot count cannot account for the wider auto-play switch; clipping that
        // overflow only turns the measurement error into a half-visible slot.
        val availableWidthPx = with(density) { (regionWidth - slotWidth).roundToPx() }
        var filledWidthPx = 0
        val filled = entries.indices.takeWhile { index ->
            val width = with(density) {
                if (entries[index].tap.showsToggle(customButtons)) {
                    switchWidth.roundToPx()
                } else {
                    slotWidth.roundToPx()
                }
            }
            (filledWidthPx + width <= availableWidthPx).also { fits ->
                if (fits) filledWidthPx += width
            }
        }.count()
        // Never create free positions over stored positions hidden by this window.
        val free = if (filled == entries.size) {
            freeSlots(
                availableWidthPx = availableWidthPx,
                filledWidthPx = filledWidthPx,
                slotWidthPx = with(density) { slotWidth.roundToPx() },
            )
        } else {
            0
        }
        val slots = buildList {
            repeat(free) { position ->
                val virtualIndex = position - free
                add(
                    ReorderableControlSlot(
                        id = ControlSlotId(ControlRegion.TopBar, virtualIndex),
                        width = 0.dp,
                        arrangingWidth = slotWidth,
                        onEdit = onSlotInsert?.let { insert -> { insert(0) } },
                        content = { EmptySlotContent(horizontalSpacing = MaterialTheme.padding.mediumSmall) },
                    ),
                )
            }
            repeat(filled) { index ->
                val entry = entries[index]
                add(
                    ReorderableControlSlot(
                        id = ControlSlotId(ControlRegion.TopBar, index),
                        width = when {
                            entry.tap == ControlAction.None && !isArranging -> 0.dp
                            entry.tap.showsToggle(customButtons) -> switchWidth
                            else -> slotWidth
                        },
                        arrangingWidth = if (entry.tap.showsToggle(customButtons)) switchWidth else slotWidth,
                        onEdit = onSlotEdit?.let { edit -> { edit(index) } },
                        canDrag = onSlotEdit != null,
                        onArrangeStart = { onArrangeStart?.invoke(index) },
                    ) {
                        when (val action = entry.tap) {
                            ControlAction.None -> EmptySlotContent(
                                horizontalSpacing = MaterialTheme.padding.mediumSmall,
                            )
                            ControlAction.ToggleAutoPlay -> BoundControlsButton(entry, onAction)
                            is ControlAction.CustomButton -> if (action.showsToggle(customButtons)) {
                                AutoPlaySwitch(
                                    isChecked = customToggleStates[action.id] == true,
                                    onToggleAutoPlay = { onAction(action) },
                                    modifier = Modifier
                                        .padding(vertical = MaterialTheme.padding.medium)
                                        .size(width = AUTO_PLAY_SWITCH_WIDTH, height = 24.dp),
                                )
                            } else {
                                BoundControlsButton(entry, onAction)
                            }
                            ControlAction.VideoQuality -> {
                                if (isEpisodeOnline == true || onSlotEdit != null) {
                                    BoundControlsButton(entry, onAction)
                                }
                            }
                            else -> BoundControlsButton(entry, onAction)
                        }
                    },
                )
            }
        }
        val editableWidth = slots.fold(0.dp) { total, slot ->
            total + if (isArranging) slot.arrangingWidth else slot.width
        }
        Row(
            // The editor's parent supplies a fixed full-width boundary. Right-aligning
            // within it keeps every existing control stationary when blanks appear.
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Absolute.Right,
        ) {
            ReorderableControlRow(
                slots = slots,
                isEditing = onSlotEdit != null,
                isArranging = isArranging,
                modifier = Modifier.width(editableWidth).clipToBounds(),
                onMove = { moves ->
                    onSlotMove?.invoke(moves)
                },
                horizontalArrangement = { Arrangement.Absolute.Right },
            )
            // Same fixed top-bar pattern as back and queue: an ordinary button in a
            // static slot while editing, with exactly one slot width reserved above.
            val overflow: @Composable () -> Unit = {
                ControlsButton(
                    icon = Icons.Default.MoreVert,
                    onClick = { onAction(ControlAction.Overflow) },
                    horizontalSpacing = MaterialTheme.padding.mediumSmall,
                )
            }
            if (onSlotEdit != null) StaticSlot { overflow() } else overflow()
        }
    }
}

private fun ControlAction.showsToggle(customButtons: List<CustomButton>): Boolean =
    this is ControlAction.CustomButton && customButtons.firstOrNull { it.id == id }?.isToggle == true

@Composable
private fun BoundControlsButton(
    entry: ControlBinding,
    onAction: (ControlAction) -> Unit,
) {
    val icon = controlActionIcon(entry.tap) ?: return
    ControlsButton(
        icon = icon,
        onClick = { onAction(entry.tap) },
        onLongClick = { entry.longPress?.let(onAction) ?: Unit },
        onDoubleClick = entry.doubleTap?.let { action -> { onAction(action) } },
        horizontalSpacing = MaterialTheme.padding.mediumSmall,
    )
}

/** What this row's buttons draw at; its width is this plus padding on both sides. */
private val TOP_ICON_SIZE = 20.dp

/** The autoplay switch's track, named so the width math and the switch cannot disagree. */
private val AUTO_PLAY_SWITCH_WIDTH = 48.dp

/** Back button plus one full button-width clickable title area; titles take the rest. */
private val TOP_BAR_BACK_WIDTH = 52.dp
private val TOP_BAR_TITLE_MIN_WIDTH = 52.dp
internal val TOP_BAR_RESERVED_START = TOP_BAR_BACK_WIDTH + TOP_BAR_TITLE_MIN_WIDTH
