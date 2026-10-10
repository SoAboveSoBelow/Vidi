// AM (EPISODE_NAMES) -->
package eu.kanade.presentation.anime.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Visibility
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
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.components.LabeledIconButton
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
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
     * entry, which has no merge to remove it from - the strip carries the
     * dialog's title there instead of standing empty.
     */
    onRemoveFromMerge: (() -> Unit)?,
    // <-- AM (MERGE_EPISODE_EXCLUSION)
    // AM (ICON_ACTION_LABELS) -->
    /** Moves this one episode to another season of the entry. */
    onChangeSeason: () -> Unit,
    /** Flips this episode's fillermark. [isFillermarked] is what it is now. */
    onToggleFillermark: () -> Unit,
    isFillermarked: Boolean,
    // <-- AM (ICON_ACTION_LABELS)
) {
    // AM (RENAME_SELECTS_ALL) -->
    // The whole name is selected the first time the field is tapped, so typing
    // replaces it - renaming an episode is almost always writing a new name
    // rather than editing the source's, and clearing it by hand first was the
    // common first move.
    //
    // No LaunchedEffect focus request here any more: focusing on open put a
    // caret in the field and raised the keyboard over a dialog the user may
    // only have wanted to read. Selection now comes from the tap itself, below.
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
                // AM (ICON_ACTION_LABELS): no dismiss - the popout opens over
                // this dialog, so closing it comes back here.
                TextButton(onClick = onEditTags) {
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
            // the dialog's top strip: the actions that are about the episode
            // rather than its name.
            //
            // AM (ICON_ACTION_LABELS): every one of them is an icon, because the
            // strip now carries four and the words would not fit across a phone.
            // Holding any of them names it - see LabeledIconButton.
            //
            // Spread evenly rather than pushed to the two ends. Grouped, the
            // four read as two clumps with a gulf between them and no reason
            // for the split; evenly spaced they read as one strip of actions,
            // which is what they are. With no merge to remove from, the title
            // takes the first slot so the strip does not open on empty space.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (onRemoveFromMerge != null) {
                    LabeledIconButton(
                        // A minus on a list, not a bin: the episode is leaving
                        // this order, not existing. It is still on its source
                        // and the merge settings dialog can put it back.
                        icon = Icons.Outlined.PlaylistRemove,
                        label = stringResource(AMMR.strings.am_action_remove_from_merge),
                        // No dismiss: this one asks first, and the confirmation
                        // comes back here if it is declined.
                        onClick = onRemoveFromMerge,
                    )
                } else {
                    Text(text = stringResource(AMMR.strings.am_quick_edit))
                }
                // <-- AM (MERGE_EPISODE_EXCLUSION)
                // AM (ICON_ACTION_LABELS) -->
                LabeledIconButton(
                    // The same Layers the reorder bar's season action uses.
                    icon = Icons.Default.Layers,
                    label = stringResource(AMMR.strings.am_action_change_episode_season),
                    // Opened over this dialog rather than instead of it, so
                    // backing out of the picker lands back on the strip.
                    onClick = onChangeSeason,
                )
                // Stays open, like the actions above: fillermarking is a flag
                // the user may want to see take effect, and the icon flipping
                // is that confirmation.
                LabeledIconButton(
                    icon = if (isFillermarked) {
                        Icons.AutoMirrored.Outlined.LabelOff
                    } else {
                        Icons.Outlined.NewLabel
                    },
                    label = if (isFillermarked) {
                        stringResource(AYMR.strings.action_remove_fillermark_episode)
                    } else {
                        stringResource(AYMR.strings.action_fillermark_episode)
                    },
                    onClick = onToggleFillermark,
                )
                // <-- AM (ICON_ACTION_LABELS)
                // Pushed WITHOUT dismissing: the dialog is ViewModel state, so it is
                // still here on the way back, as the merge settings dialog does.
                LabeledIconButton(
                    icon = Icons.Outlined.Visibility,
                    label = stringResource(AMMR.strings.am_action_open_entry),
                    onClick = onOpenEntry,
                )
            }
        },
        // <-- AM (TAG_LIMIT)
        text = {
            // AM (RENAME_SELECTS_ALL) -->
            // The first tap is taken by the overlay rather than the field.
            // Setting the selection from onFocusChanged did not work: the same
            // tap that focuses a text field also places the caret where it
            // landed, and that runs after the focus callback, so the selection
            // was set and then immediately replaced by a cursor. Swallowing the
            // tap means no caret is ever placed - the overlay selects
            // everything and asks for focus itself. Once focused the overlay is
            // gone, so tapping again moves the caret as normal.
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
