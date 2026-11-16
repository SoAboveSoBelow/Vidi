package eu.kanade.tachiyomi.ui.player.controls.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlBinding
import animiru.domain.player.model.ControlRegion
import eu.kanade.tachiyomi.ui.player.controls.components.controlActionIcon
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.i18n.MR
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

/** Selecting an entry immediately assigns it to the slot and closes the dialog. */
@Composable
fun SlotEditDialog(
    binding: ControlBinding?,
    customButtons: List<CustomButton>,
    onDismissRequest: () -> Unit,
    onConfirm: (ControlBinding) -> Unit,
) {
    val selected = binding?.button ?: binding?.tap

    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .heightIn(min = 280.dp, max = 480.dp)
                    .padding(vertical = 8.dp),
            ) {
                item(key = "none") {
                    ButtonChoice(
                        name = stringResource(MR.strings.none),
                        action = ControlAction.None,
                        selected = selected == null || selected == ControlAction.None,
                        onClick = { onConfirm(ControlBinding()) },
                    )
                }
                items(customButtons, key = { it.id }) { button ->
                    val action = ControlAction.CustomButton(button.id, icon = button.icon)
                    ButtonChoice(
                        name = button.name,
                        action = action,
                        selected = (selected as? ControlAction.CustomButton)?.id == button.id,
                        onClick = { onConfirm(ControlBinding(tap = action, button = action)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ButtonChoice(
    name: String,
    action: ControlAction,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        if (action == ControlAction.None) {
            Spacer(Modifier.size(20.dp))
        } else {
            controlActionIcon(action)?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.size(24.dp),
        )
    }
}
// <-- AM (CONTROL_BINDINGS)
