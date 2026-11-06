// AM (QUEUE_WATCH_FILTER) -->
package eu.kanade.presentation.queue

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.AlertDialog
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The choice offered when queueing something that holds both watched and
 * unwatched episodes.
 *
 * Asked once for the whole add, however many entries it covers: the question
 * is what to do with watched episodes, and the answer is the same for all of
 * them. A prompt per entry made a ten-entry selection ten dialogs, and - since
 * nothing could be queued until each was answered - decided the queue's order
 * by the order they were answered in rather than the order they were picked.
 *
 * [mixedTitle] names the single entry when exactly one is mixed, which is the
 * common case and the one where the title is worth more than a count.
 */
@Composable
fun AddToQueueDialog(
    mixedCount: Int,
    mixedTitle: String?,
    onAddUnwatched: () -> Unit,
    onAddAll: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(AMMR.strings.am_action_add_to_queue)) },
        text = {
            Text(
                text = mixedTitle
                    ?: pluralStringResource(AMMR.plurals.am_queue_mixed_entries, mixedCount, mixedCount),
            )
        },
        confirmButton = {
            TextButton(onClick = onAddUnwatched) {
                Text(text = stringResource(AMMR.strings.am_action_add_unwatched))
            }
        },
        dismissButton = {
            TextButton(onClick = onAddAll) {
                Text(text = stringResource(AMMR.strings.am_action_add_all))
            }
        },
    )
}
// <-- AM (QUEUE_WATCH_FILTER)
