package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.ui.player.PlaylistSearchScope
import eu.kanade.tachiyomi.ui.player.components.EpisodeListItem
import eu.kanade.tachiyomi.util.lang.toRelativeString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.domain.library.model.search.QueryNode
import mihon.feature.library.EpisodeSearchFields
import mihon.feature.library.matches
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha
import kotlin.time.Instant

@Composable
fun EpisodeListDialog(
    displayMode: Long?,
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    /** The playing episode, identified rather than indexed - see itemScrollIndex. */
    currentEpisodeId: Long?,
    /** The unscoped list. Searching the scoped one could only ever narrow it further. */
    episodeList: List<Episode>,
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    dateRelativeTime: Boolean,
    dateFormat: String,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    // AM (EPISODE_NAMES) -->
    /** Resolves the name the episode list shows - see PlayerStateData.displayNameOf. */
    displayNameOf: (Episode) -> String,
    // <-- AM (EPISODE_NAMES)
    // AM (EPISODE_TAG_SEARCH) -->
    /** What search matches an episode against - see PlayerStateData.searchFieldsOf. */
    searchFieldsOf: (Episode) -> EpisodeSearchFields?,
    // <-- AM (EPISODE_TAG_SEARCH)
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    /** The entry this playlist belongs to, which the submitted search is keyed to. */
    playlistAnimeId: Long?,
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    // AM (PLAYER_EPISODE_LIST_SELECTION) -->
    /** Null hides the row's open-entry button - see EpisodeListItem. */
    onOpenEntryClicked: ((Long?) -> Unit)?,
    /** Opens the entry this playlist belongs to - the merge parent, or the entry itself. */
    onOpenPlaylistEntry: (() -> Unit)?,
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current

    // AM (ALWAYS_OPEN_EPISODE_SEARCH) -->
    // Plain String, not String?: the field has no closed state, so empty is
    // the only "not filtering" there is.
    //
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    // The draft, seeded from the submitted query so the field opens showing
    // what the playlist was actually built from. Edits live only here until a
    // tap submits them, so closing the dialog discards them and leaves the
    // playlist alone.
    val submittedScope by PlaylistSearchScope.submitted.collectAsState()
    var searchQuery by remember(playlistAnimeId) {
        mutableStateOf(playlistAnimeId?.let(PlaylistSearchScope::queryFor).orEmpty())
    }
    LaunchedEffect(submittedScope, playlistAnimeId) {
        searchQuery = playlistAnimeId?.let(PlaylistSearchScope::queryFor).orEmpty()
    }
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    // <-- AM (ALWAYS_OPEN_EPISODE_SEARCH)

    // AM (PLAYER_EPISODE_LIST_SELECTION) -->
    // Local to the dialog rather than player state: it is a property of this
    // list being open, and closing the dialog should end it. Keeping it here
    // means it cannot outlive the dialog or leak into a later session.
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    val selectedEpisodes = remember(selectedIds, episodeList) {
        episodeList.filter { it.id in selectedIds }
    }
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)

    val reversedEpisodeList = remember(episodeList) { episodeList.reversed() }
    val filteredEpisodeList = remember(reversedEpisodeList, searchQuery) {
        val query = searchQuery
        if (query.isBlank()) {
            reversedEpisodeList
        } else {
            // AM (EPISODE_TAG_SEARCH) -->
            // The same grammar the library search bar uses, matched against
            // the entry each episode belongs to. Falls back to the plain name
            // match only when the episode has no resolvable entry, which is
            // the window before the playlist's owners have loaded.
            val queryNode = QueryNode.from(query)
            reversedEpisodeList.filter { episode ->
                val fields = searchFieldsOf(episode)
                if (fields != null) {
                    queryNode.matches(fields)
                } else {
                    displayNameOf(episode).contains(query, ignoreCase = true) ||
                        formatEpisodeNumber(episode.episode_number.toDouble()).contains(query, ignoreCase = true)
                }
            }
            // <-- AM (EPISODE_TAG_SEARCH)
        }
    }

    // AM (NOW_PLAYING_INDICATOR) -->
    // Positioned by the playing episode's id rather than by an index into the
    // playlist: episodeList is now the unscoped list, so a playlist index no
    // longer addresses the same element.
    val itemScrollIndex = reversedEpisodeList
        .indexOfFirst { it.id != null && it.id == currentEpisodeId }
        .coerceAtLeast(0)
    // <-- AM (NOW_PLAYING_INDICATOR)
    val episodeListState = rememberLazyListState(initialFirstVisibleItemIndex = itemScrollIndex)
    val dateFormatter = remember(dateFormat) { UiPreferences.dateFormat(dateFormat) }

    PlayerDialog(
        title = stringResource(AYMR.strings.episodes),
        modifier = Modifier.fillMaxHeight(fraction = 0.8F).fillMaxWidth(fraction = 0.8F),
        // AM (PLAYER_EPISODE_LIST_SELECTION) -->
        // Back and outside-tap clear the selection first rather than closing
        // the dialog under it, matching how the entry list treats back while
        // its own selection is active.
        onDismissRequest = {
            if (selectionMode) selectedIds = emptySet() else onDismissRequest()
        },
        // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
        titleContent = {
            // AM (PLAYER_EPISODE_LIST_SELECTION) -->
            if (selectionMode) {
                EpisodeListSelectionHeader(
                    selectedCount = selectedIds.size,
                    // Offered only when it would change something: with every
                    // selected episode already bookmarked there is nothing for
                    // the add action to do, and the same in reverse.
                    onBookmark = { selectedEpisodes.forEach { onBookmarkClicked(it.id, true) } }
                        .takeIf { selectedEpisodes.any { !it.bookmark } },
                    onRemoveBookmark = { selectedEpisodes.forEach { onBookmarkClicked(it.id, false) } }
                        .takeIf { selectedEpisodes.any { it.bookmark } },
                    onFillermark = { selectedEpisodes.forEach { onFillermarkClicked(it.id, true) } }
                        .takeIf { selectedEpisodes.any { !it.fillermark } },
                    onRemoveFillermark = { selectedEpisodes.forEach { onFillermarkClicked(it.id, false) } }
                        .takeIf { selectedEpisodes.any { it.fillermark } },
                    onClearSelection = { selectedIds = emptySet() },
                )
            } else {
                EpisodeListDialogHeader(
                    searchQuery = searchQuery,
                    // AM (PLAYLIST_SEARCH_SCOPE) -->
                    // Emptying unscopes here too, so the dialog's X and the
                    // entry screen's field behave the same. A non-empty edit is
                    // still only a draft awaiting a tap.
                    //
                    // Gated on the transition to match the entry screen. The
                    // seeding effect above assigns searchQuery directly rather
                    // than through here, so nothing currently sends an empty
                    // value that is not the user clearing one - the gate keeps
                    // that from becoming load-bearing.
                    onChangeSearchQuery = { query ->
                        val hadText = searchQuery.isNotEmpty()
                        searchQuery = query
                        if (hadText && query.isEmpty()) {
                            playlistAnimeId?.let(PlaylistSearchScope::clearFor)
                        }
                    },
                    // <-- AM (PLAYLIST_SEARCH_SCOPE)
                    onOpenPlaylistEntry = onOpenPlaylistEntry,
                )
            }
            // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
        },
    ) {
        VerticalFastScroller(
            listState = episodeListState,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                state = episodeListState,
            ) {
                items(
                    items = filteredEpisodeList,
                    key = { "episode-${it.id}" },
                    contentType = { "episode" },
                ) { episode ->

                    // AM (NOW_PLAYING_INDICATOR) -->
                    val isCurrentEpisode = currentEpisodeId != null && episode.id == currentEpisodeId
                    // <-- AM (NOW_PLAYING_INDICATOR)

                    val title = if (displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                        stringResource(
                            AYMR.strings.display_mode_episode,
                            formatEpisodeNumber(episode.episode_number.toDouble()),
                        )
                    } else {
                        // AM (EPISODE_NAMES) -->
                        displayNameOf(episode)
                        // <-- AM (EPISODE_NAMES)
                    }

                    val date = episode.date_upload
                        .takeIf { it > 0L }
                        ?.let {
                            Instant.fromEpochMilliseconds(it)
                                .toLocalDateTime(TimeZone.currentSystemDefault())
                                .date
                                .toRelativeString(
                                    context = context,
                                    relative = dateRelativeTime,
                                    dateFormat = dateFormatter,
                                )
                        } ?: ""

                    EpisodeListItem(
                        episode = episode,
                        isCurrentEpisode = isCurrentEpisode,
                        title = title,
                        date = date,
                        onBookmarkClicked = onBookmarkClicked,
                        onFillermarkClicked = onFillermarkClicked,
                        // AM (PLAYER_EPISODE_LIST_SELECTION) -->
                        onEpisodeClicked = { id ->
                            when {
                                // AM (PLAYLIST_SEARCH_SCOPE) -->
                                // The confirmation. Submitted before the switch
                                // so the playlist this builds is the list the
                                // user was looking at when they tapped.
                                !selectionMode -> {
                                    playlistAnimeId?.let { PlaylistSearchScope.submit(it, searchQuery) }
                                    onEpisodeClicked(id)
                                }
                                // <-- AM (PLAYLIST_SEARCH_SCOPE)
                                id == null -> Unit
                                id in selectedIds -> selectedIds = selectedIds - id
                                else -> selectedIds = selectedIds + id
                            }
                        },
                        onOpenEntryClicked = onOpenEntryClicked,
                        // AM (OPEN_ENTRY_MERGED_ONLY) -->
                        // This list has a selection bar, so the marks live there.
                        marksInRow = false,
                        // <-- AM (OPEN_ENTRY_MERGED_ONLY)
                        onLongClick = {
                            episode.id?.let { selectedIds = selectedIds + it }
                        },
                        selectionMode = selectionMode,
                        isSelected = episode.id in selectedIds,
                        // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
                    )
                }
            }
        }
    }
}

// AM (ALWAYS_OPEN_EPISODE_SEARCH) -->
/**
 * The field is always open rather than hidden behind a magnifier, so the list
 * and the thing that narrows it are visible together - and there is no toggle
 * state to be in the wrong one of. No title: the list of episode names says
 * what this is.
 *
 * The eye leads the row so the field cannot cover it, and the field is not
 * auto-focused - it is present on every open, and raising the keyboard each
 * time would bury the list it filters.
 */
@Composable
private fun EpisodeListDialogHeader(
    searchQuery: String,
    onChangeSearchQuery: (String) -> Unit,
    onOpenPlaylistEntry: (() -> Unit)?,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val clearFocus: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Opens the entry the playlist itself belongs to, which on a merged
        // entry is the parent rather than whichever child the current episode
        // came from.
        if (onOpenPlaylistEntry != null) {
            IconButton(onClick = onOpenPlaylistEntry) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = stringResource(AMMR.strings.am_action_open_entry),
                )
            }
        }

        BasicTextField(
            value = searchQuery,
            onValueChange = onChangeSearchQuery,
            modifier = Modifier
                .weight(1f)
                .runOnEnterKeyPressed(action = clearFocus)
                .clearFocusOnSoftKeyboardHide(),
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { clearFocus() }),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            decorationBox = { innerTextField ->
                TextFieldDefaults.DecorationBox(
                    value = searchQuery,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = remember { MutableInteractionSource() },
                    placeholder = {
                        Text(
                            modifier = Modifier.secondaryItemAlpha(),
                            text = stringResource(MR.strings.action_search_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    },
                    container = {},
                )
            },
        )

        // Clearing empties the query rather than closing the field, since
        // there is no closed state to return to.
        if (searchQuery.isNotEmpty()) {
            IconButton(
                onClick = {
                    onChangeSearchQuery("")
                    clearFocus()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_reset),
                )
            }
        }
    }
}
// <-- AM (ALWAYS_OPEN_EPISODE_SEARCH)

// AM (PLAYER_EPISODE_LIST_SELECTION) -->
/**
 * Replaces the title and search row while a selection is active, the way the
 * entry list's toolbar does: the count on the left, the actions that would
 * change something on the right, and a close that clears the selection.
 *
 * A null action is omitted rather than disabled - with everything selected
 * already bookmarked there is nothing for "bookmark" to do, and an action that
 * is present but inert reads as broken.
 */
@Composable
private fun EpisodeListSelectionHeader(
    selectedCount: Int,
    onBookmark: (() -> Unit)?,
    onRemoveBookmark: (() -> Unit)?,
    onFillermark: (() -> Unit)?,
    onRemoveFillermark: (() -> Unit)?,
    onClearSelection: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(MR.strings.action_cancel),
            )
        }

        Text(
            text = selectedCount.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        if (onBookmark != null) {
            IconButton(onClick = onBookmark) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkAdd,
                    contentDescription = stringResource(AYMR.strings.action_bookmark_episode),
                )
            }
        }
        if (onRemoveBookmark != null) {
            IconButton(onClick = onRemoveBookmark) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkRemove,
                    contentDescription = stringResource(AYMR.strings.action_remove_bookmark_episode),
                )
            }
        }
        if (onFillermark != null) {
            IconButton(onClick = onFillermark) {
                Icon(
                    imageVector = Icons.Outlined.NewLabel,
                    contentDescription = stringResource(AYMR.strings.action_fillermark_episode),
                )
            }
        }
        if (onRemoveFillermark != null) {
            IconButton(onClick = onRemoveFillermark) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.LabelOff,
                    contentDescription = stringResource(AYMR.strings.action_remove_fillermark_episode),
                )
            }
        }
    }
}
// <-- AM (PLAYER_EPISODE_LIST_SELECTION)
