package eu.kanade.presentation.more.settings.screen.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding
import animiru.domain.player.model.ControlRegion
import eu.kanade.presentation.components.AlertDialog
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun BottomPlayerButtonsDialog(
    initialSelection: List<ControlBinding>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<ControlBinding>) -> Unit,
) {
    // AM (CONTROL_BINDINGS): one entry per bottom-row position, None meaning the position
    // is empty. Always exactly the region's hard cap so positions stay stable across saves
    // - no compacting. How many of them a given window actually shows is measured when the
    // row renders, not decided here.
    val slots = remember {
        val trimmed = initialSelection.take(ControlRegion.Bottom.hardCap).map { it.tap }
        (trimmed + List(ControlRegion.Bottom.hardCap - trimmed.size) { ControlAction.None })
            .toMutableStateList()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(AYMR.strings.pref_bottom_player_buttons)) },
        text = {
            LazyColumn {
                itemsIndexed(slots) { index, selected ->
                    BottomPlayerButtonSlot(
                        position = index + 1,
                        selected = selected,
                        onSelect = { chosen ->
                            // A button can only occupy one position - if it's already
                            // assigned elsewhere, move it here instead of duplicating it.
                            if (chosen != ControlAction.None) {
                                val existingIndex = slots.indexOf(chosen)
                                if (existingIndex != -1 && existingIndex != index) {
                                    slots[existingIndex] = ControlAction.None
                                }
                            }
                            slots[index] = chosen
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(slots.map { ControlBinding(tap = it) })
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun BottomPlayerButtonSlot(
    position: Int,
    selected: ControlAction,
    onSelect: (ControlAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(AYMR.strings.pref_bottom_player_button_slot, position),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(selected.stringRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected != ControlAction.None) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BOTTOM_SLOT_ACTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(option.stringRes)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * What a bottom slot can be set to. Every action the row knows how to draw, plus None
 * for an empty position - the playback actions have no button in that row yet, so
 * offering them here would bind something invisible.
 */
private val BOTTOM_SLOT_ACTIONS = listOf(
    ControlAction.None,
    ControlAction.Lock,
    ControlAction.CycleRotation,
    ControlAction.PlaybackSpeed,
    ControlAction.Chapters,
    ControlAction.PictureInPicture,
    ControlAction.AspectRatio,
    ControlAction.Screenshot,
    ControlAction.Cast,
)

@Composable
fun bottomPlayerButtonsSubtitle(selection: List<ControlBinding>): String {
    val names = selection.map { it.tap }.filter { it != ControlAction.None }
    if (names.isEmpty()) return stringResource(MR.strings.none)
    return names.map { stringResource(it.stringRes) }.joinToString()
}
