package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.kanade.presentation.anime.DuplicateAnimeDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.controls.components.dialogs.EpisodeListDialog
import eu.kanade.tachiyomi.ui.player.controls.components.dialogs.IntegerPickerDialog
import mihon.feature.library.EpisodeSearchFields
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

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
    // AM (PLAYER_ADD_TO_LIBRARY) -->
    isFavorited: Boolean,
    onAddToLibrary: () -> Unit,
    /** The duplicate dialog's "add anyway" - the same call with the check off. */
    onAddToLibraryAnyway: () -> Unit,
    onOpenAnime: (Anime) -> Unit,
    onMigrate: (Anime) -> Unit,
    onEditCategories: () -> Unit,
    onConfirmCategories: (Anime, List<Long>) -> Unit,
    onDeleteDownloads: (Anime) -> Unit,
    // <-- AM (PLAYER_ADD_TO_LIBRARY)
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
                // AM (PLAYER_ADD_TO_LIBRARY)
                isFavorited = isFavorited,
                onAddToLibrary = onAddToLibrary,
                // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
                onDismissRequest = onDismissRequest,
            )
        }
        // AM (PLAYER_ADD_TO_LIBRARY) -->
        // The entry screen's own dialogs, over the player, so adding from here
        // asks what adding there asks.
        is Dialogs.DuplicateAnime -> {
            DuplicateAnimeDialog(
                duplicates = dialogShown.duplicates,
                onDismissRequest = onDismissRequest,
                onConfirm = onAddToLibraryAnyway,
                onOpenAnime = onOpenAnime,
                onMigrate = onMigrate,
            )
        }
        is Dialogs.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialogShown.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = onEditCategories,
                onConfirm = { include, _ -> onConfirmCategories(dialogShown.anime, include) },
            )
        }
        is Dialogs.DeleteDownloadsAfterRemoval -> {
            val anime = dialogShown.anime
            AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(text = stringResource(AYMR.strings.delete_downloads_for_anime)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            onDeleteDownloads(anime)
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        // <-- AM (PLAYER_ADD_TO_LIBRARY)
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
