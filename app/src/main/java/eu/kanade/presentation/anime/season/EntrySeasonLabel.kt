// AM (NAMED_SEASONS) -->
package eu.kanade.presentation.anime.season

import androidx.compose.runtime.Composable
import aniyomi.domain.season.model.EntrySeason
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The label every surface uses for a season: its name, or "Season N" by its
 * 1-based position in [seasons] (the entry's full display order). Positional
 * rather than by number, so unnamed seasons renumber themselves as they're
 * reordered and the switcher, dialogs, manager and player all agree.
 */
@Composable
fun seasonLabel(seasonNumber: Long, seasons: List<EntrySeason>): String {
    val index = seasons.indexOfFirst { it.number == seasonNumber }
    val name = seasons.getOrNull(index)?.name
    return name ?: stringResource(AMMR.strings.am_merge_season_number, (index + 1).coerceAtLeast(1))
}
// <-- AM (NAMED_SEASONS)
