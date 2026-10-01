// AM (EPISODE_NAMES) -->
package eu.kanade.presentation.anime.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
    // AM (MERGE_EPISODE_EXCLUSION) -->
    /**
     * Takes this episode out of the merged entry's order. Null on an ordinary
     * entry, which has no merge to remove it from - the slot carries the
     * dialog's title there instead of standing empty.
     */
    onRemoveFromMerge: (() -> Unit)?,
    // <-- AM (MERGE_EPISODE_EXCLUSION)
) {
    // AM (RENAME_SELECTS_ALL) -->
    // Selects the whole name when the field is tapped, so typing replaces it -
    // renaming is almost always writing a new name rather than editing the
    // source's, and clearing it by hand first was the common first move.
    //
    // On the tap, not on opening: the dialog carries other actions (edit tags,
    // open entry, remove from merge) and should not steal focus and raise the
    // keyboard for someone who came for one of those. Tapping an
    // already-focused field still just moves the caret, since focus did not
    // change.
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentName))
    }
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // <-- AM (RENAME_SELECTS_ALL)
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
                            // AM (RENAME_SELECTS_ALL)
                            onConfirm(name.text)
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            }
        },
        title = {
            // AM (MERGE_EPISODE_EXCLUSION) -->
            // The old "Rename episode" heading is gone - the field's own label
            // already says that, and the heading only repeated it. The row is now
            // the dialog's top strip: Remove from merge hard left where the
            // heading was, or, with no merge to remove from, a plain title so the
            // strip isn't just a lone icon.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (onRemoveFromMerge != null) {
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            onRemoveFromMerge()
                        },
                    ) {
                        Text(text = stringResource(AMMR.strings.am_action_remove_from_merge))
                    }
                } else {
                    Text(text = stringResource(AMMR.strings.am_quick_edit))
                }
                // <-- AM (MERGE_EPISODE_EXCLUSION)
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
            // AM (RENAME_SELECTS_ALL) -->
            // The first tap is taken by the overlay rather than the field. Doing
            // this from onFocusChanged did not work: the same tap that focuses a
            // text field also places the caret where it landed, and that runs
            // after the focus callback, so the selection was set and then
            // immediately replaced by a cursor. Swallowing the tap means no
            // caret is ever placed - the overlay selects everything and asks for
            // focus itself. Once focused the overlay is gone, so tapping again
            // moves the caret as normal.
            Box {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(text = stringResource(AMMR.strings.am_rename_episode_hint)) },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                )
                if (!isFocused) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                name = name.copy(selection = TextRange(0, name.text.length))
                                focusRequester.requestFocus()
                            },
                    )
                }
            }
            // <-- AM (RENAME_SELECTS_ALL)
        },
    )
}

// <-- AM (EPISODE_NAMES)
