package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.ui.player.Dialogs
import mihon.feature.library.EpisodeSearchFields
import eu.kanade.tachiyomi.ui.player.controls.components.dialogs.EpisodeListDialog
import eu.kanade.tachiyomi.ui.player.controls.components.dialogs.IntegerPickerDialog

@Composable
fun PlayerDialogs(
    dialogShown: Dialogs,

    // Episode list
    episodeDisplayMode: Long?,
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    currentEpisodeId: Long?,
    episodeList: List<Episode>,
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    dateRelativeTime: Boolean,
    dateFormat: String,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    // AM (EPISODE_NAMES) -->
    displayNameOf: (Episode) -> String,
    // <-- AM (EPISODE_NAMES)
    // AM (EPISODE_TAG_SEARCH) -->
    searchFieldsOf: (Episode) -> EpisodeSearchFields?,
    // <-- AM (EPISODE_TAG_SEARCH)
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    playlistAnimeId: Long?,
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    // AM (PLAYER_EPISODE_LIST_SELECTION) -->
    onOpenEntryClicked: ((Long?) -> Unit)?,
    onOpenPlaylistEntry: (() -> Unit)?,
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)

    onDismissRequest: () -> Unit,
) {
    when (dialogShown) {
        Dialogs.None -> {}
        Dialogs.EpisodeList -> {
            EpisodeListDialog(
                displayMode = episodeDisplayMode,
                // AM (PLAYLIST_SEARCH_SCOPE) -->
                currentEpisodeId = currentEpisodeId,
                episodeList = episodeList,
                // <-- AM (PLAYLIST_SEARCH_SCOPE)
                dateRelativeTime = dateRelativeTime,
                dateFormat = dateFormat,
                onBookmarkClicked = onBookmarkClicked,
                onFillermarkClicked = onFillermarkClicked,
                onEpisodeClicked = onEpisodeClicked,
                // AM (EPISODE_NAMES) -->
                displayNameOf = displayNameOf,
                // <-- AM (EPISODE_NAMES)
                // AM (EPISODE_TAG_SEARCH) -->
                searchFieldsOf = searchFieldsOf,
                // <-- AM (EPISODE_TAG_SEARCH)
                // AM (PLAYLIST_SEARCH_SCOPE) -->
                playlistAnimeId = playlistAnimeId,
                // <-- AM (PLAYLIST_SEARCH_SCOPE)
                // AM (PLAYER_EPISODE_LIST_SELECTION) -->
                onOpenEntryClicked = onOpenEntryClicked,
                onOpenPlaylistEntry = onOpenPlaylistEntry,
                // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
                onDismissRequest = onDismissRequest,
            )
        }
        is Dialogs.IntegerPicker -> {
            IntegerPickerDialog(
                defaultValue = dialogShown.defaultValue,
                minValue = dialogShown.minValue,
                maxValue = dialogShown.maxValue,
                step = dialogShown.step,
                nameFormat = dialogShown.nameFormat,
                title = dialogShown.title,
                onChange = dialogShown.onChange,
                onDismissRequest = dialogShown.onDismissRequest,
            )
        }
    }
}
