package eu.kanade.tachiyomi.ui.player.cast.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.ui.player.components.EpisodeListItem
import eu.kanade.tachiyomi.ui.player.components.rememberEpisodeMarks
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.GenericTracksSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.TrackSheetTitle
import eu.kanade.tachiyomi.util.lang.toRelativeString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Instant

@Composable
fun CastPlaylistSheet(
    displayMode: Long?,
    currentEpisodeIndex: Int,
    episodeList: List<Episode>,
    dateRelativeFormat: Boolean,
    dateFormat: String,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val dateFormatter = remember(dateFormat) { UiPreferences.dateFormat(dateFormat) }

    // AM (PLAYER_MARK_BADGES): same reason as the player's own episode list -
    // nothing here is told when a mark is written.
    val marks = rememberEpisodeMarks(
        episodes = episodeList,
        onBookmarkClicked = onBookmarkClicked,
        onFillermarkClicked = onFillermarkClicked,
    )

    GenericTracksSheet(
        tracks = episodeList,
        onDismissRequest = onDismissRequest,
        header = { TrackSheetTitle(stringResource(AYMR.strings.episodes)) },
        track = { episode ->
            val isCurrentEpisode = episode.id == episodeList[currentEpisodeIndex].id
            val title = if (displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                stringResource(
                    AYMR.strings.display_mode_episode,
                    formatEpisodeNumber(episode.episode_number.toDouble()),
                )
            } else {
                episode.name
            }

            val date = episode.date_upload
                .takeIf { it > 0L }
                ?.let {
                    Instant.fromEpochMilliseconds(it)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .date
                        .toRelativeString(
                            context = context,
                            relative = dateRelativeFormat,
                            dateFormat = dateFormatter,
                        )
                } ?: ""

            // AM (PLAYER_MARK_BADGES)
            val episodeMarks = marks.marksOf(episode)

            EpisodeListItem(
                episode = episode,
                isCurrentEpisode = isCurrentEpisode,
                title = title,
                date = date,
                // AM (PLAYER_MARK_BADGES) -->
                isBookmarked = episodeMarks.bookmarked,
                isFillermarked = episodeMarks.fillermarked,
                onBookmarkClicked = marks::setBookmark,
                onFillermarkClicked = marks::setFillermark,
                // <-- AM (PLAYER_MARK_BADGES)
                onEpisodeClicked = onEpisodeClicked,
            )
        },
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    )
}
