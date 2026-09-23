// AM (CUSTOM_EPISODE_ORDER) -->
package eu.kanade.presentation.anime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import aniyomi.domain.season.model.EntrySeason
import eu.kanade.presentation.anime.season.seasonLabel
import tachiyomi.presentation.core.components.material.padding

/**
 * Chip row for scoping the episode list to one season, shown in place of the
 * native season grid. Switching only changes what's listed - the playlist is
 * the entry's whole order, so playback still runs across season boundaries.
 */
@Composable
fun EpisodeSeasonSwitcher(
    // AM (NAMED_SEASONS) -->
    seasons: List<EntrySeason>,
    // <-- AM (NAMED_SEASONS)
    seasonNumbers: List<Long>,
    activeSeasonNumber: Long?,
    onSeasonSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasonNumbers, key = { it }) { season ->
            FilterChip(
                selected = season == activeSeasonNumber,
                onClick = { onSeasonSelected(season) },
                // AM (NAMED_SEASONS) -->
                label = { Text(seasonLabel(season, seasons)) },
                // <-- AM (NAMED_SEASONS)
            )
        }
    }
}
// <-- AM (CUSTOM_EPISODE_ORDER)
