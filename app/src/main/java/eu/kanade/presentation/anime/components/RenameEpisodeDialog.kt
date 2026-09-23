// AM (EPISODE_NAMES) -->
package eu.kanade.presentation.anime.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Renames one episode for display. The name is global to the episode and shows
 * everywhere it appears, including the player; leaving it blank restores the
 * source's own name.
 */
@Composable
fun RenameEpisodeDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm(name)
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = { Text(text = stringResource(AMMR.strings.am_action_rename_episode)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(text = stringResource(AMMR.strings.am_rename_episode_hint)) },
            )
        },
    )
}
// <-- AM (EPISODE_NAMES)
