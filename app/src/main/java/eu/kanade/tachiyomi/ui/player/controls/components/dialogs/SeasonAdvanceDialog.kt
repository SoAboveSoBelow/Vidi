// AM (CUSTOM_EPISODE_ORDER) -->
package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import aniyomi.domain.season.model.EntrySeason
import eu.kanade.presentation.anime.season.seasonLabel
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shown when autoplay reaches the end of a season: the playlist continues into
 * the next season, but only once the user says so. Same shell as PlayerDialog,
 * with the decision's own wording instead of OK/Cancel.
 *
 * Dismissing (back, tapping outside, "Not now") leaves playback stopped at the
 * end of the finished episode; the next episode is still one tap away on the
 * normal next control.
 */
@Composable
fun SeasonAdvanceDialog(
    prompt: PlayerViewModel.SeasonAdvancePrompt,
    // AM (NAMED_SEASONS) -->
    seasons: List<EntrySeason>,
    // <-- AM (NAMED_SEASONS)
    onContinue: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    // AM (NAMED_SEASONS) -->
    val finished = seasonLabel(prompt.finishedSeason, seasons)
    val next = seasonLabel(prompt.nextSeason, seasons)
    // <-- AM (NAMED_SEASONS)

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(AMMR.strings.am_season_advance_title, finished),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(AMMR.strings.am_season_advance_message, next),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(AMMR.strings.am_season_advance_stay))
                    }
                    TextButton(onClick = onContinue) {
                        Text(stringResource(AMMR.strings.am_season_advance_continue))
                    }
                }
            }
        }
    }
}
// <-- AM (CUSTOM_EPISODE_ORDER)
