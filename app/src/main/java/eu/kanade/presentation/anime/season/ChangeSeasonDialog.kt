// AM (NAMED_SEASONS) -->
package eu.kanade.presentation.anime.season

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.model.offeredNextSeason
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Picks a season - for the selected episodes in reorder mode, or for one source
 * of a merged entry in merge settings. One dialog for both so the two read the
 * same and gain new options together.
 *
 * The last row is the next season, which does not exist yet: picking it creates
 * it (immediately for episodes, on Save for a merge source). Naming, reordering
 * and deleting are still done in the season manager, reached from the edit
 * button, as with categories.
 *
 * What picking does is the caller's business: the episode side writes straight
 * through with "discard changes" as its undo, while merge settings stages the
 * pick until Save.
 *
 * A long list scrolls. There is no scrollbar, so past ten or so seasons it is
 * not obvious there is more below - worth revisiting if entries routinely run
 * that long.
 */
@Composable
fun ChangeSeasonDialog(
    seasons: List<EntrySeason>,
    onSeasonSelected: (Long) -> Unit,
    onCreateNextSeason: () -> Unit,
    onEditSeasons: () -> Unit,
    onDismissRequest: () -> Unit,
    title: String = stringResource(AMMR.strings.am_action_change_episode_season),
    /** Marked as the current one. Null where the pick spans seasons, as a multi-season selection does. */
    selectedSeason: Long? = null,
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
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                seasons.forEach { season ->
                    SeasonOption(
                        label = seasonLabel(season.number, seasons),
                        selected = season.number == selectedSeason,
                        onClick = {
                            onDismissRequest()
                            onSeasonSelected(season.number)
                        },
                    )
                }
                // The season after the last one, labelled like any other with a
                // + for what picking it does - rather than "Add Season 3",
                // which read as a different kind of thing than the rows above.
                val offered = offeredNextSeason(seasons)
                SeasonOption(
                    label = stringResource(AMMR.strings.am_merge_season_number, offered.labelPosition),
                    selected = false,
                    onClick = {
                        onDismissRequest()
                        onCreateNextSeason()
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(
                                AMMR.strings.am_entry_season_create_number,
                                offered.labelPosition,
                            ),
                        )
                    },
                )
            }
        },
    )
}

@Composable
private fun SeasonOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium.takeIf { selected },
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailingIcon?.invoke()
    }
}
// <-- AM (NAMED_SEASONS)
