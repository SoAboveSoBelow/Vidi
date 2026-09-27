// AM (EPISODE_NAMES) -->
package eu.kanade.presentation.anime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // AM (TAG_LIMIT) -->
    /**
     * Both act on the entry that owns this episode, which on a merged entry is the
     * child source rather than the merge - the tags shown and edited are that
     * source's, and Open entry goes to that source's screen.
     */
    onEditTags: () -> Unit,
    onOpenEntry: () -> Unit,
    // <-- AM (TAG_LIMIT)
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        // AM (TAG_LIMIT) -->
        // One row for every button rather than the dialog's dismiss and confirm
        // slots, which both sit at the end - the only way to put Edit tags hard left,
        // where it reads as being about the entry rather than a third answer to the
        // rename.
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        onEditTags()
                    },
                ) {
                    Text(text = stringResource(AMMR.strings.am_action_edit_tags))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            onConfirm(name)
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(AMMR.strings.am_action_rename_episode),
                    modifier = Modifier.weight(1f),
                )
                // Pushed WITHOUT dismissing: the dialog is ViewModel state, so it is
                // still here on the way back, as the merge settings dialog does.
                IconButton(onClick = onOpenEntry) {
                    Icon(
                        imageVector = Icons.Outlined.Visibility,
                        contentDescription = stringResource(AMMR.strings.am_action_open_entry),
                    )
                }
            }
        },
        // <-- AM (TAG_LIMIT)
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
