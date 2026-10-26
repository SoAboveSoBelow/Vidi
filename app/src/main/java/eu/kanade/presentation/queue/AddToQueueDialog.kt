// AM (QUEUE_WATCH_FILTER) -->
package eu.kanade.presentation.queue

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.AlertDialog
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource

/** The choice offered when a source contains both watched and unwatched episodes. */
@Composable
fun AddToQueueDialog(
    anime: Anime,
    onAddUnwatched: () -> Unit,
    onAddAll: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(AMMR.strings.am_action_add_to_queue)) },
        text = { Text(text = anime.title) },
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
