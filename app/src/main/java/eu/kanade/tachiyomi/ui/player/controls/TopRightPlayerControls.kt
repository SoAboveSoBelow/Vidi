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
import eu.kanade.tachiyomi.ui.player.controls.components.ControlAvailability
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
    // AM (CONTROL_BINDINGS): the seeded auto-play toggle reports the preference
    // the app already owns, not a state round-tripped through Lua - see
    // switchChecked.
    autoPlayEnabled: Boolean,
    /** Which bindings have something to act on for what is playing. */
    availability: ControlAvailability,
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
        // AM (CONTROL_BINDINGS): a binding with nothing to act on is not a slot,
        // so the window measures and fills from what is actually drawable and a
        // later stored control moves up into the space. The editor keeps every
        // stored position regardless - see ControlAvailability.isRendered.
        val isEditing = onSlotEdit != null || isArranging
        val renderable = entries.withIndex().filter { availability.isRendered(it.value, isEditing) }
        var filledWidthPx = 0
        val filled = renderable.takeWhile { (_, entry) ->
            val width = with(density) {
                if (entry.tap.drawsSwitch(customButtons)) {
                    switchWidth.roundToPx()
                } else {
                    slotWidth.roundToPx()
                }
            }
            (filledWidthPx + width <= availableWidthPx).also { fits ->
                if (fits) filledWidthPx += width
            }
        }
        // Never create free positions over stored positions hidden by this window.
        val free = if (filled.size == renderable.size) {
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
            filled.forEach { (index, entry) ->
                add(
                    ReorderableControlSlot(
                        id = ControlSlotId(ControlRegion.TopBar, index),
                        width = when {
                            entry.tap == ControlAction.None && !isArranging -> 0.dp
                            entry.tap.drawsSwitch(customButtons) -> switchWidth
                            else -> slotWidth
                        },
                        arrangingWidth = if (entry.tap.drawsSwitch(customButtons)) switchWidth else slotWidth,
                        onEdit = onSlotEdit?.let { edit -> { edit(index) } },
                        canDrag = onSlotEdit != null,
                        onArrangeStart = { onArrangeStart?.invoke(index) },
                    ) {
                        val action = entry.tap
                        when {
                            action == ControlAction.None -> EmptySlotContent(
                                horizontalSpacing = MaterialTheme.padding.mediumSmall,
                            )
                            action.drawsSwitch(customButtons) -> AutoPlaySwitch(
                                isChecked = switchChecked(action, autoPlayEnabled, customToggleStates),
                                onToggleAutoPlay = { onAction(action) },
                                modifier = Modifier
                                    .padding(vertical = MaterialTheme.padding.medium)
                                    .size(width = AUTO_PLAY_SWITCH_WIDTH, height = 24.dp),
                            )
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

/**
 * Whether this slot draws the switch rather than an icon button.
 *
 * The seeded auto-play default counts whether or not its Lua row is still there
 * to be read: it has no icon of its own (controlActionIcon returns null for it),
 * so an icon button would draw an empty slot. Its stored form is the only one a
 * region normally sees, but the unresolved action is matched too - a layout
 * written before the native defaults were seeded still holds it.
 */
private fun ControlAction.drawsSwitch(customButtons: List<CustomButton>): Boolean = when {
    this == ControlAction.ToggleAutoPlay -> true
    this is ControlAction.CustomButton ->
        ControlAction.nativeActionForButtonId(id) == ControlAction.ToggleAutoPlay ||
            customButtons.firstOrNull { it.id == id }?.isToggle == true
    else -> false
}

/**
 * AM (CONTROL_BINDINGS_AUTOPLAY_STATE): auto-play's checked state is the
 * preference, as it was before the port.
 *
 * The generic path for a user's Lua switch is [customToggleStates], written from
 * set_button_toggle, and that is still what a user-created toggle reports
 * through. Auto-play cannot use it: the app owns that preference, the seeded Lua
 * only asks the app to flip it, and the state Lua reports at startup is written
 * before anything is subscribed to hear it - so the switch opened every session
 * showing off while auto-play was on, and only looked right once tapped.
 */
private fun switchChecked(
    action: ControlAction,
    autoPlayEnabled: Boolean,
    customToggleStates: Map<Long, Boolean>,
): Boolean = when {
    action == ControlAction.ToggleAutoPlay -> autoPlayEnabled
    action is ControlAction.CustomButton ->
        if (ControlAction.nativeActionForButtonId(action.id) == ControlAction.ToggleAutoPlay) {
            autoPlayEnabled
        } else {
            customToggleStates[action.id] == true
        }
    else -> false
}

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
