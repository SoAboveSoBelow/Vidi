package eu.kanade.tachiyomi.ui.player.controls.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding
import animiru.domain.player.model.ControlInput
import animiru.domain.player.model.ControlRegion
import eu.kanade.presentation.components.AlertDialog
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// AM (CONTROL_BINDINGS) -->
/** Which slot the editor currently has open. */
data class SlotEdit(
    val region: ControlRegion,
    val index: Int,
    /**
     * True when [index] is a position that does not exist yet, opened up by arranging.
     * Confirming inserts there rather than overwriting whatever currently sits at that
     * index - the distinction matters for the middle and bottom rows, whose free
     * positions appear BETWEEN existing controls rather than after them.
     */
    val isInsert: Boolean = false,
)

/**
 * Survives rotation, which the editor invites: a user turning the device to see their
 * landscape layout should not lose the slot they had open.
 */
val SlotEditSaver: Saver<SlotEdit?, Any> = Saver(
    // Null saves nothing, which restores as no dialog open - the Saver contract's own
    // way of saying "there was nothing here".
    save = { edit -> edit?.let { listOf(it.region.name, it.index, it.isInsert) } },
    restore = { saved ->
        (saved as? List<*>)?.let { stored ->
            val region = ControlRegion.entries.firstOrNull { it.name == stored[0] } ?: return@let null
            SlotEdit(region, stored[1] as Int, stored[2] as Boolean)
        }
    },
)

/**
 * One slot's three bindings, in one dialog.
 *
 * Tap, double tap and long press are all just bindings, so they are three rows of the
 * same kind rather than one real setting and two special cases. Clearing the tap empties
 * the slot, which is what None means everywhere else.
 */
@Composable
fun SlotEditDialog(
    region: ControlRegion,
    index: Int,
    binding: ControlBinding?,
    onDismissRequest: () -> Unit,
    onConfirm: (ControlBinding) -> Unit,
    onMove: (Int) -> Unit,
) {
    var working by remember { mutableStateOf(binding ?: ControlBinding()) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(AYMR.strings.pref_player_controls_slot, index + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                ControlInput.entries.forEach { input ->
                    BindingRow(
                        input = input,
                        selected = working.forInput(input),
                        options = ControlAction.selectable.filterNot {
                            (region == ControlRegion.TopBar && it == ControlAction.Overflow) ||
                                (region == ControlRegion.Middle && it == ControlAction.PlayPause)
                        },
                        onSelect = { working = working.withInput(input, it) },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(AYMR.strings.pref_player_controls_reorder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { onMove(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    IconButton(onClick = { onMove(1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working) }) {
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
private fun BindingRow(
    input: ControlInput,
    selected: ControlAction?,
    options: List<ControlAction>,
    onSelect: (ControlAction?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (input) {
        ControlInput.Tap -> AYMR.strings.pref_player_controls_tap
        ControlInput.DoubleTap -> AYMR.strings.pref_player_controls_double_tap
        ControlInput.LongPress -> AYMR.strings.pref_player_controls_long_press
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selected?.let { stringResource(it.stringRes) }
                        ?: stringResource(MR.strings.none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected != null && selected != ControlAction.None) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(option.stringRes)) },
                    onClick = {
                        expanded = false
                        // None on a secondary input is an unbound input rather than an
                        // action that does nothing, which is what keeps the single tap
                        // immediate on a slot with no double tap.
                        onSelect(
                            if (input != ControlInput.Tap && option == ControlAction.None) null else option,
                        )
                    },
                )
            }
        }
    }
}
// <-- AM (CONTROL_BINDINGS)
