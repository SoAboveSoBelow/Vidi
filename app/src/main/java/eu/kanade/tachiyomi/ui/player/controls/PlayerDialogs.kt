package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.kanade.presentation.anime.DuplicateAnimeDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.components.PlaylistSeasons
import eu.kanade.tachiyomi.ui.player.controls.components.dialogs.EpisodeListDialog
import eu.kanade.tachiyomi.ui.player.controls.components.dialogs.IntegerPickerDialog
import mihon.feature.library.EpisodeSearchFields
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PlayerDialogs(
    dialogShown: Dialogs,

    // Episode list
    // AM (PLAYBACK_QUEUE)
    episodeDisplayModeOf: (Episode) -> Long?,
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
    // AM (PLAYLIST_LIBRARY_BADGE)
    isFromLibraryEntry: (Episode) -> Boolean,
    // AM (QUEUE_GROUPED_PLAYLIST) -->
    blockOf: (Episode) -> Long?,
    sourceTitleOf: (Episode) -> String?,
    // AM (QUEUE_SOURCE_TOTALS)
    sourceIdOf: (Episode) -> Long?,
    blockSeasons: Map<Long, PlaylistSeasons>,
    blockLastPlayed: Map<Long, Long>,
    // AM (PLAYLIST_SWIPE_ACTIONS) -->
    swipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    swipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    downloadStates: Map<Long, Download.State>,
    onSeenClicked: (List<Episode>, Boolean) -> Unit,
    // AM (PLAYBACK_SEEN_REACHES_PLAYLIST)
    playbackSeen: Set<Long>,
    // AM (PLAYLIST_ADD_TO_LIBRARY)
    onChangeCategories: ((blockIds: List<Long>, episodeIds: List<Long>) -> Unit)?,
    onDownloadClicked: (Episode) -> Unit,
    // <-- AM (PLAYLIST_SWIPE_ACTIONS)
    onExpandSource: (Long) -> Unit,
    // AM (QUEUE_REORDER)
    onMoveQueueEpisodes: (List<Long>, Long?) -> Unit,
    onReorderQueueBlocks: (List<Long>) -> Unit,
    // <-- AM (QUEUE_GROUPED_PLAYLIST)
    // <-- AM (EPISODE_TAG_SEARCH)
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    playlistAnimeId: Long?,
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    // AM (PLAYER_EPISODE_LIST_SELECTION) -->
    // AM (OPEN_ENTRY_ON_DOUBLE_TAP)
    onOpenSource: ((Long) -> Unit)?,
    onOpenEpisodeEntry: ((Long) -> Unit)?,
    // AM (PLAYER_ADD_TO_LIBRARY) -->
    // AM (OPEN_ENTRY_ON_DOUBLE_TAP): isFavorited/onAddToLibrary are gone with
    // the playlist header's heart. The dialogs below are still here because
    // the add-to-library flow itself is unchanged; nothing in the player opens
    // them now.
    /** The duplicate dialog's "add anyway" - the same call with the check off. */
    // AM (PLAYLIST_ADD_TO_LIBRARY): the entry the prompt is about, which is
    // not always the one playing once a selection can raise it.
    onAddToLibraryAnyway: (Anime) -> Unit,
    onOpenAnime: (Anime) -> Unit,
    onMigrate: (Anime) -> Unit,
    onEditCategories: () -> Unit,
    // AM (PLAYLIST_ADD_TO_LIBRARY): exclude is no longer dropped - with more
    // than one entry it is what makes a mixed category clearable.
    onConfirmCategories: (List<Anime>, List<Long>, List<Long>) -> Unit,
    onDeleteDownloads: (Anime) -> Unit,
    // <-- AM (PLAYER_ADD_TO_LIBRARY)
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /** Null hides the download action - see EpisodeListDialog. */
    onDownloadEpisodes: ((List<Episode>) -> Unit)?,
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)

    onDismissRequest: () -> Unit,

    // AM (EXTERNAL_FOLDER_GRANT) -->
    /** Whether asking for folder access would do anything - see PlayerViewModel. */
    canGrantExternalFolder: Boolean = false,
    /** Launches the folder picker; null where no launcher is wired up. */
    onRequestExternalFolder: (() -> Unit)? = null,
    // <-- AM (EXTERNAL_FOLDER_GRANT)
) {
    when (dialogShown) {
        Dialogs.None -> {}
        // AM (EXTERNAL_FOLDER_GRANT) -->
        Dialogs.ExternalFolder -> {
            ExternalFolderDialog(
                canGrantAccess = canGrantExternalFolder,
                onGrantAccess = onRequestExternalFolder,
                onDismissRequest = onDismissRequest,
            )
        }
        // <-- AM (EXTERNAL_FOLDER_GRANT)
        Dialogs.EpisodeList -> {
            EpisodeListDialog(
                displayModeOf = episodeDisplayModeOf,
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
                // AM (PLAYLIST_LIBRARY_BADGE)
                isFromLibraryEntry = isFromLibraryEntry,
                // AM (QUEUE_GROUPED_PLAYLIST) -->
                blockOf = blockOf,
                sourceTitleOf = sourceTitleOf,
                // AM (QUEUE_SOURCE_TOTALS)
                sourceIdOf = sourceIdOf,
                blockSeasons = blockSeasons,
                blockLastPlayed = blockLastPlayed,
                // AM (PLAYLIST_SWIPE_ACTIONS) -->
                swipeStartAction = swipeStartAction,
                swipeEndAction = swipeEndAction,
                downloadStates = downloadStates,
                onSeenClicked = onSeenClicked,
                // AM (PLAYBACK_SEEN_REACHES_PLAYLIST)
                playbackSeen = playbackSeen,
                // AM (PLAYLIST_ADD_TO_LIBRARY)
                onChangeCategories = onChangeCategories,
                onDownloadClicked = onDownloadClicked,
                // <-- AM (PLAYLIST_SWIPE_ACTIONS)
                onExpandSource = onExpandSource,
                // AM (QUEUE_REORDER)
                onMoveQueueEpisodes = onMoveQueueEpisodes,
                onReorderQueueBlocks = onReorderQueueBlocks,
                // <-- AM (QUEUE_GROUPED_PLAYLIST)
                // <-- AM (EPISODE_TAG_SEARCH)
                // AM (PLAYLIST_SEARCH_SCOPE) -->
                playlistAnimeId = playlistAnimeId,
                // <-- AM (PLAYLIST_SEARCH_SCOPE)
                // AM (PLAYER_EPISODE_LIST_SELECTION) -->
                // AM (OPEN_ENTRY_ON_DOUBLE_TAP)
                onOpenSource = onOpenSource,
                onOpenEpisodeEntry = onOpenEpisodeEntry,
                // AM (PLAYER_ADD_TO_LIBRARY)
                // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
                // AM (LOCAL_SOURCE_DOWNLOAD) -->
                onDownloadEpisodes = onDownloadEpisodes,
                // <-- AM (LOCAL_SOURCE_DOWNLOAD)
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
                // AM (PLAYLIST_ADD_TO_LIBRARY)
                onConfirm = { onAddToLibraryAnyway(dialogShown.anime) },
                onOpenAnime = onOpenAnime,
                onMigrate = onMigrate,
            )
        }
        is Dialogs.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialogShown.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = onEditCategories,
                // AM (PLAYLIST_ADD_TO_LIBRARY)
                onConfirm = { include, exclude ->
                    onConfirmCategories(dialogShown.animeList, include, exclude)
                },
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

// AM (EXTERNAL_FOLDER_GRANT) -->
/**
 * What the episode-list button opens for an externally-opened file.
 *
 * A file handed to Vidi from another app comes with access to that one file and
 * nothing around it, so there is no folder to list and no next/previous to
 * offer until the user says which folder this is. This is where they say it -
 * an explicit action rather than a prompt on open, which would be asking for
 * access before knowing whether anything needs it.
 *
 * Once a folder has been granted the action is gone and this reports the queue
 * is in place, because asking again would do nothing.
 */
@Composable
private fun ExternalFolderDialog(
    canGrantAccess: Boolean,
    onGrantAccess: (() -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(AMMR.strings.external_folder_title)) },
        text = {
            Text(
                stringResource(
                    if (canGrantAccess) {
                        AMMR.strings.external_folder_request
                    } else {
                        AMMR.strings.external_folder_granted
                    },
                ),
            )
        },
        confirmButton = {
            if (canGrantAccess && onGrantAccess != null) {
                TextButton(onClick = onGrantAccess) {
                    Text(stringResource(AMMR.strings.external_folder_action))
                }
            } else {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_ok))
                }
            }
        },
        dismissButton = {
            if (canGrantAccess) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}
// <-- AM (EXTERNAL_FOLDER_GRANT)
