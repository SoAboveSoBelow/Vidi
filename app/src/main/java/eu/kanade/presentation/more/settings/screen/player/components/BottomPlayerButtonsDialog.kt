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
import androidx.compose.material3.AlertDialog
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
import animiru.domain.player.model.BottomPlayerButton
import animiru.domain.player.service.GesturePreferences.Companion.MAX_BOTTOM_PLAYER_BUTTONS
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun BottomPlayerButtonsDialog(
    initialSelection: List<BottomPlayerButton?>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<BottomPlayerButton?>) -> Unit,
) {
    // One slot per bottom-row position; null means that position is empty. Always exactly
    // MAX_BOTTOM_PLAYER_BUTTONS entries so positions stay stable across saves - no compacting.
    val slots = remember {
        val trimmed = initialSelection.take(MAX_BOTTOM_PLAYER_BUTTONS)
        (trimmed + List(MAX_BOTTOM_PLAYER_BUTTONS - trimmed.size) { null }).toMutableStateList()
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
                            if (chosen != null) {
                                val existingIndex = slots.indexOf(chosen)
                                if (existingIndex != -1 && existingIndex != index) {
                                    slots[existingIndex] = null
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
                    onConfirm(slots.toList())
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
    selected: BottomPlayerButton?,
    onSelect: (BottomPlayerButton?) -> Unit,
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
                    text = selected?.let { stringResource(it.stringRes) } ?: stringResource(MR.strings.none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected != null) {
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
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.none)) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            BottomPlayerButton.entries.forEach { option ->
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

@Composable
fun bottomPlayerButtonsSubtitle(selection: List<BottomPlayerButton?>): String {
    val names = selection.filterNotNull()
    if (names.isEmpty()) return stringResource(MR.strings.none)
    return names.map { stringResource(it.stringRes) }.joinToString()
}
