// AM (CUSTOM_EPISODE_ORDER) -->
package eu.kanade.presentation.anime.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import aniyomi.domain.season.model.EntrySeason
import eu.kanade.presentation.anime.season.seasonLabel
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Picks the season to move the selection into. Picking applies immediately -
 * like every reorder edit it is written through, and "discard changes" is the
 * undo - so there is no separate confirm. New seasons are made in the season
 * manager, reached from the edit button, as with categories.
 */
@Composable
fun ChangeEpisodeSeasonDialog(
    seasons: List<EntrySeason>,
    onSeasonSelected: (Long) -> Unit,
    onEditSeasons: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onEditSeasons()
                },
            ) {
                Text(text = stringResource(MR.strings.action_edit))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(AMMR.strings.am_action_change_episode_season)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                seasons.forEach { season ->
                    SeasonOption(
                        label = seasonLabel(season.number, seasons),
                        onClick = {
                            onDismissRequest()
                            onSeasonSelected(season.number)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun SeasonOption(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}
// <-- AM (CUSTOM_EPISODE_ORDER)
