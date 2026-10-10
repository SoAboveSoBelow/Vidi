// AM (ICON_ACTION_LABELS) -->
package eu.kanade.presentation.anime.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.AlertDialog
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Asks before taking an episode out of a merged entry's order.
 *
 * The action used to be a text button reading "Remove from merge" and is now an
 * icon, which is easier to hit by accident, and it is the only thing in the
 * quick-edit strip that changes the entry rather than flipping a flag. The
 * question and the two buttons are the whole dialog - what removal does is not
 * in doubt, and a paragraph explaining it would only be read once.
 */
@Composable
fun ConfirmRemoveFromMergeDialog(
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(AMMR.strings.am_confirm_remove_from_merge)) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
    )
}
// <-- AM (ICON_ACTION_LABELS)
