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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.player.PlaylistSearchScope
import aniyomi.domain.season.model.EntrySeason
import eu.kanade.tachiyomi.ui.player.components.EpisodeListItem
import eu.kanade.tachiyomi.ui.player.components.PlaylistExpansion
import eu.kanade.tachiyomi.ui.player.components.PlaylistGroupItem
import eu.kanade.tachiyomi.ui.player.components.PlaylistRow
import eu.kanade.tachiyomi.ui.player.components.PlaylistSeasons
import eu.kanade.tachiyomi.ui.player.components.buildPlaylistRows
import eu.kanade.tachiyomi.ui.player.components.rememberEpisodeMarks
import eu.kanade.tachiyomi.ui.player.components.rememberPlaylistReorder
import eu.kanade.tachiyomi.util.lang.toRelativeString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.domain.library.model.search.QueryNode
import mihon.feature.library.EpisodeSearchFields
import mihon.feature.library.matches
import tachiyomi.domain.anime.model.Anime
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha
import sh.calvin.reorderable.ReorderableItem
import kotlin.time.Instant

@Composable
fun EpisodeListDialog(
    // AM (PLAYBACK_QUEUE): per episode, not per session - see
    // PlayerStateData.displayModeOf.
    displayModeOf: (Episode) -> Long?,
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
    // AM (PLAYLIST_LIBRARY_BADGE) -->
    /** Whether an episode's source is in the library - see PlayerStateData.isFromLibraryEntry. */
    isFromLibraryEntry: (Episode) -> Boolean,
    // <-- AM (PLAYLIST_LIBRARY_BADGE)
    // AM (QUEUE_GROUPED_PLAYLIST) -->
    /** Which queued source an episode belongs to - see PlayerStateData.playlistBlockByEpisodeId. */
    blockOf: (Episode) -> Long?,
    /** The title of the entry a source was queued as. */
    sourceTitleOf: (Episode) -> String?,
    /**
     * Seasons per source, by block id - absent until that source's resolve
     * lands (see loadBlockSeasons). A map rather than a lambda so the row list
     * can be remembered against it: a lambda returning something new is
     * invisible to remember, and the season layer would not appear until
     * something else happened to invalidate the list.
     */
    blockSeasons: Map<Long, PlaylistSeasons>,
    /** Where the user left each source, by block id - see QueueBlock.lastPlayedEpisodeId. */
    blockLastPlayed: Map<Long, Long>,
    // AM (PLAYLIST_SWIPE_ACTIONS) -->
    /** The user's episode swipe settings, the same pair the entry list obeys. */
    swipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    swipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    /** Download state per episode - see PlayerStateData.playlistDownloadStates. */
    downloadStates: Map<Long, Download.State>,
    onSeenClicked: (List<Episode>, Boolean) -> Unit,
    onDownloadClicked: (Episode) -> Unit,
    // <-- AM (PLAYLIST_SWIPE_ACTIONS)
    /** Asks for a source's seasons; called when its group is first expanded. */
    onExpandSource: (Long) -> Unit,
    // AM (QUEUE_REORDER)
    onMoveQueueEpisodes: (List<Long>, Long?) -> Unit,
    onReorderQueueBlocks: (List<Long>) -> Unit,
    // <-- AM (QUEUE_GROUPED_PLAYLIST)
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    /** The entry this playlist belongs to, which the submitted search is keyed to. */
    playlistAnimeId: Long?,
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    // AM (PLAYER_EPISODE_LIST_SELECTION) -->
    /** Null hides the row's open-entry button - see EpisodeListItem. */
    onOpenEntryClicked: ((Long?) -> Unit)?,
    /** Opens the entry this playlist belongs to - the merge parent, or the entry itself. */
    onOpenPlaylistEntry: (() -> Unit)?,
    // AM (PLAYER_ADD_TO_LIBRARY) -->
    /** Whether the playlist's entry is already in the library. */
    isFavorited: Boolean,
    onAddToLibrary: () -> Unit,
    // <-- AM (PLAYER_ADD_TO_LIBRARY)
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /**
     * Downloads the selection. Where the files land follows the entry, not this
     * action - see PlayerViewModel.downloadEpisodes. Null when this playlist has
     * nothing downloadable behind it, a local entry most obviously, in which
     * case the action is not offered.
     */
    onDownloadEpisodes: ((List<Episode>) -> Unit)?,
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)
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
    val clearSelection = { selectedIds = emptySet() }
    // A parent selects its episode run, so clicking it toggles exactly like an
    // episode and every existing bulk action works without a parent-only path.
    val toggleSelection: (List<Long>) -> Unit = { ids ->
        val selection = ids.toSet()
        selectedIds = if (selection.all(selectedIds::contains)) {
            selectedIds - selection
        } else {
            selectedIds + selection
        }
    }
    // Long-press selection is also queue reorder mode; there is no second
    // mode to enable before the drag handles appear.
    val selectionMode = selectedIds.isNotEmpty()
    val selectedEpisodes = remember(selectedIds, episodeList) {
        episodeList.filter { it.id in selectedIds }
    }
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)

    // AM (PLAYBACK_QUEUE) -->
    // This list used to be reversed for display, so the newest episode sat at
    // the top and `next` walked upwards. The queue made that unworkable:
    // queued episodes are appended to the end of the play order, and under a
    // reversal they appear at the TOP of the list the user is told is what
    // plays next.
    //
    // So the reversal is gone and the list reads top to bottom in play order,
    // queue or not - the entry's episodes in the order setupEpisodeList put
    // them (its sort mode, always ascending), then whatever is queued after
    // them, newest addition last. `next` always moves down. One rule, no
    // special case for a queue being present, and the order on screen is the
    // order things actually play in.
    val orderedEpisodeList = episodeList
    val canReorderQueue = orderedEpisodeList.any { blockOf(it) != null }
    val isReordering = selectionMode && canReorderQueue
    val filteredEpisodeList = remember(orderedEpisodeList, searchQuery, isReordering) {
        val query = searchQuery
        // Reorder edits the full queue, not the subset a search happens to show.
        if (query.isBlank() || isReordering) {
            orderedEpisodeList
        } else {
            // AM (EPISODE_TAG_SEARCH) -->
            // The same grammar the library search bar uses, matched against
            // the entry each episode belongs to. Falls back to the plain name
            // match only when the episode has no resolvable entry, which is
            // the window before the playlist's owners have loaded.
            val queryNode = QueryNode.from(query)
            orderedEpisodeList.filter { episode ->
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
    //
    // AM (QUEUE_GROUPED_PLAYLIST): the index is into the ROWS now, since a
    // group may sit between two episodes - see rowScrollIndex below.
    // <-- AM (NOW_PLAYING_INDICATOR)
    // AM (QUEUE_GROUPED_PLAYLIST) -->
    // The same label every other season surface shows, resolved off the
    // context rather than through the composable helper so the row list can
    // be built inside remember.
    val seasonLabels = remember(context) {
        { seasonNumber: Long, seasons: List<EntrySeason> ->
            val index = seasons.indexOfFirst { it.number == seasonNumber }
            seasons.getOrNull(index)?.name
                ?: context.stringResource(
                    AMMR.strings.am_merge_season_number,
                    (index + 1).coerceAtLeast(1),
                )
        }
    }
    // <-- AM (QUEUE_GROUPED_PLAYLIST)
    // AM (PLAYER_MARK_BADGES) -->
    // Marking goes through this rather than straight to the callbacks, so rows
    // redraw when the selection bar marks several at once - the playlist itself
    // is not refetched while the player lives, so nothing else would tell them.
    val marks = rememberEpisodeMarks(
        episodes = episodeList,
        onBookmarkClicked = onBookmarkClicked,
        onFillermarkClicked = onFillermarkClicked,
        // AM (PLAYLIST_SWIPE_ACTIONS): the write goes through the entry
        // screen's own interactor; this layer only keeps the rows in step with
        // it, since the playlist itself is never refetched.
        onSeenClicked = { episodeId, seen ->
            val episode = episodeList.firstOrNull { it.id != null && it.id == episodeId }
            if (episode != null) onSeenClicked(listOf(episode), seen)
        },
    )
    // <-- AM (PLAYER_MARK_BADGES)
    // AM (PLAYLIST_LIBRARY_BADGE) -->
    // Only drawn when the list actually holds both kinds. A badge on every row
    // distinguishes nothing and is read as decoration, and a playlist that is
    // one entry's episodes - or one queue of library entries - is exactly that
    // case. The moment something from outside the library is queued alongside
    // them, every row says which it is.
    val showLibraryBadges = remember(episodeList) {
        val states = episodeList.asSequence().map(isFromLibraryEntry).distinct().take(2).toList()
        states.size > 1
    }
    // <-- AM (PLAYLIST_LIBRARY_BADGE)
    // AM (QUEUE_GROUPED_PLAYLIST) -->
    // Local to the dialog, like the selection: which groups are open is a
    // property of this list being on screen, and reopening it should show the
    // queue the way a queue is read - sources, not several hundred episodes.
    // The source holding the playing episode starts open, so the list still
    // opens on what is playing.
    var expansion by remember(currentEpisodeId) {
        val playingBlock = orderedEpisodeList
            .firstOrNull { it.id != null && it.id == currentEpisodeId }
            ?.let(blockOf)
        mutableStateOf(PlaylistExpansion(sources = setOfNotNull(playingBlock)))
    }

    // Searching flattens the groups. A query is a search for episodes, and
    // answering it with a list of collapsed sources that might contain one
    // would make the user open each to find out.
    val isSearching = searchQuery.isNotBlank() && !isReordering
    val baseRows = remember(filteredEpisodeList, expansion, currentEpisodeId, isSearching, blockSeasons) {
        if (isSearching) {
            filteredEpisodeList.map { PlaylistRow.EpisodeItem(it, depth = 0) }
        } else {
            buildPlaylistRows(
                episodes = filteredEpisodeList,
                blockOf = blockOf,
                titleOf = { _, items -> items.firstNotNullOfOrNull(sourceTitleOf).orEmpty() },
                seasonsOf = blockSeasons::get,
                seasonLabelOf = seasonLabels,
                expansion = expansion,
                currentEpisodeId = currentEpisodeId,
                lastPlayedByBlockId = blockLastPlayed,
            )
        }
    }

    // Opening a source opens it all the way down to the season the user was
    // last in, so the row they left is on screen rather than behind one more
    // tap. Runs when a source is expanded and again when its seasons land,
    // since the resolve is lazy and usually finishes after the expand.
    //
    // Once per source: the user collapsing a season again must stick, and
    // re-expanding the source is them asking for it afresh.
    var seasonAutoExpanded by remember(currentEpisodeId) { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(expansion.sources, blockSeasons, currentEpisodeId) {
        val pending = expansion.sources - seasonAutoExpanded
        if (pending.isEmpty()) return@LaunchedEffect
        var seasons = expansion.seasons
        val resolved = mutableSetOf<Long>()
        pending.forEach { blockId ->
            val blockSeason = blockSeasons[blockId] ?: return@forEach
            resolved += blockId
            val target = orderedEpisodeList
                .filter { blockOf(it) == blockId }
                .let { items ->
                    items.firstOrNull { it.id != null && it.id == currentEpisodeId }
                        ?: items.firstOrNull { it.id != null && it.id == blockLastPlayed[blockId] }
                }
            val seasonNumber = target?.id?.let { blockSeason.seasonByEpisodeId[it] } ?: return@forEach
            seasons = seasons + (blockId to seasonNumber)
        }
        if (resolved.isEmpty()) return@LaunchedEffect
        seasonAutoExpanded = seasonAutoExpanded + resolved
        if (seasons != expansion.seasons) expansion = expansion.copy(seasons = seasons)
    }

    // The playing episode's own row when its group is open, otherwise the
    // group that holds it - either way the list opens on what is playing
    // rather than at the top of a queue it may be deep inside.
    val rowScrollIndex = baseRows
        .indexOfFirst { row ->
            when (row) {
                is PlaylistRow.EpisodeItem -> row.episode.id != null && row.episode.id == currentEpisodeId
                is PlaylistRow.Source -> row.containsCurrent
                is PlaylistRow.Season -> row.containsCurrent
            }
        }
        .coerceAtLeast(0)
    // <-- AM (QUEUE_GROUPED_PLAYLIST)
    val episodeListState = rememberLazyListState(initialFirstVisibleItemIndex = rowScrollIndex)
    val reorderUi = rememberPlaylistReorder(
        enabled = isReordering,
        listState = episodeListState,
        rows = baseRows,
        episodes = orderedEpisodeList,
        selectedIds = selectedIds,
        blockOf = blockOf,
        seasonOf = { episode ->
            val blockId = blockOf(episode)
            val episodeId = episode.id
            if (blockId != null && episodeId != null) {
                blockSeasons[blockId]?.seasonByEpisodeId?.get(episodeId)
            } else {
                null
            }
        },
        onMoveEpisodes = onMoveQueueEpisodes,
        onReorderBlocks = onReorderQueueBlocks,
    )
    val dateFormatter = remember(dateFormat) { UiPreferences.dateFormat(dateFormat) }

    PlayerDialog(
        title = stringResource(AYMR.strings.episodes),
        modifier = Modifier.fillMaxHeight(fraction = 0.8F).fillMaxWidth(fraction = 0.8F),
        // AM (PLAYER_EPISODE_LIST_SELECTION) -->
        // Back and outside-tap clear the selection first rather than closing
        // the dialog under it, matching how the entry list treats back while
        // its own selection is active.
        onDismissRequest = {
            if (selectionMode) {
                clearSelection()
            } else {
                onDismissRequest()
            }
        },
        // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
        titleContent = {
            // AM (PLAYER_EPISODE_LIST_SELECTION) -->
            if (selectionMode) {
                // AM (PLAYLIST_SWIPE_ACTIONS) -->
                // An action that is already a swipe is left off the bar. The
                // swipe reaches it in one gesture on any row, and a bar button
                // beside it is a second way to do the same thing that has to
                // be read past every time the bar is open. What is NOT a swipe
                // stays here, which is the only place it can be reached at
                // all - including marking seen, which the bar never offered
                // until the swipe settings could take it away again.
                val swipes = setOf(swipeStartAction, swipeEndAction)
                val offersSeen = LibraryPreferences.EpisodeSwipeAction.ToggleSeen !in swipes
                val offersBookmark = LibraryPreferences.EpisodeSwipeAction.ToggleBookmark !in swipes
                val offersFillermark = LibraryPreferences.EpisodeSwipeAction.ToggleFillermark !in swipes
                val offersDownload = LibraryPreferences.EpisodeSwipeAction.Download !in swipes
                // <-- AM (PLAYLIST_SWIPE_ACTIONS)
                EpisodeListSelectionHeader(
                    selectedCount = selectedIds.size,
                    // AM (PLAYLIST_SWIPE_ACTIONS) -->
                    onSeen = {
                        selectedEpisodes.forEach { marks.setSeen(it.id, true) }
                        clearSelection()
                    }.takeIf { offersSeen && selectedEpisodes.any { !marks.marksOf(it).seen } },
                    onUnseen = {
                        selectedEpisodes.forEach { marks.setSeen(it.id, false) }
                        clearSelection()
                    }.takeIf { offersSeen && selectedEpisodes.any { marks.marksOf(it).seen } },
                    // <-- AM (PLAYLIST_SWIPE_ACTIONS)
                    // AM (LOCAL_SOURCE_DOWNLOAD) -->
                    // Clears the selection, like the entry list's own download
                    // action: the selection described what to act on, and it has
                    // been acted on.
                    // AM (PLAYLIST_SWIPE_ACTIONS): and only when it is not a swipe.
                    onDownload = onDownloadEpisodes?.takeIf { offersDownload }?.let { download ->
                        {
                            download(selectedEpisodes)
                            clearSelection()
                        }
                    },
                    // <-- AM (LOCAL_SOURCE_DOWNLOAD)
                    // Offered only when it would change something: with every
                    // selected episode already bookmarked there is nothing for
                    // the add action to do, and the same in reverse.
                    // AM (PLAYER_MARK_BADGES): through the mark state, and the
                    // offered-only-when-useful checks read from it too, so they
                    // follow what the bar itself has just done.
                    onBookmark = { selectedEpisodes.forEach { marks.setBookmark(it.id, true) } }
                        .takeIf { offersBookmark && selectedEpisodes.any { !marks.marksOf(it).bookmarked } },
                    onRemoveBookmark = { selectedEpisodes.forEach { marks.setBookmark(it.id, false) } }
                        .takeIf { offersBookmark && selectedEpisodes.any { marks.marksOf(it).bookmarked } },
                    onFillermark = { selectedEpisodes.forEach { marks.setFillermark(it.id, true) } }
                        .takeIf { offersFillermark && selectedEpisodes.any { !marks.marksOf(it).fillermarked } },
                    onRemoveFillermark = { selectedEpisodes.forEach { marks.setFillermark(it.id, false) } }
                        .takeIf { offersFillermark && selectedEpisodes.any { marks.marksOf(it).fillermarked } },
                    onClearSelection = clearSelection,
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
                    // AM (PLAYER_ADD_TO_LIBRARY)
                    isFavorited = isFavorited,
                    onAddToLibrary = onAddToLibrary,
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
                    items = reorderUi.rows,
                    key = { it.key },
                    contentType = { row ->
                        when (row) {
                            is PlaylistRow.EpisodeItem -> "episode"
                            else -> "group"
                        }
                    },
                ) { row ->
                    ReorderableItem(
                        state = reorderUi.state,
                        key = row.key,
                        enabled = isReordering,
                    ) {
                        val canDragRow = when (row) {
                            is PlaylistRow.EpisodeItem -> blockOf(row.episode) != null
                            else -> true
                        }
                        val reorderHandleModifier = reorderUi.row?.takeIf { canDragRow }?.let { reorderRow ->
                            Modifier.draggableHandle(
                                onDragStarted = { reorderRow.onDragStarted(row.key) },
                                onDragStopped = { reorderRow.onDragStopped(row.key) },
                            )
                        }
                        // AM (QUEUE_GROUPED_PLAYLIST) -->
                        // A tap on a group plays; the chevron expands. Playing
                        // goes through the same submit-then-open path an episode
                        // row takes, since playing the first episode of a source
                        // IS opening that episode.
                        val playFromGroup: (Long?) -> Unit = { id ->
                            if (!selectionMode && id != null) {
                                playlistAnimeId?.let { PlaylistSearchScope.submit(it, searchQuery) }
                                onEpisodeClicked(id)
                            }
                        }
                        when (row) {
                            is PlaylistRow.Source -> {
                                if (row.singleEpisode == null) {
                                    PlaylistGroupItem(
                                        title = row.title,
                                        subtitle = pluralStringResource(
                                            AYMR.plurals.anime_num_episodes,
                                            row.episodeCount,
                                            row.episodeCount,
                                        ),
                                        depth = 0,
                                        isCurrent = row.containsCurrent,
                                        isSelected = row.episodeIds.all(selectedIds::contains),
                                        expanded = row.expanded,
                                        onClick = {
                                            if (selectionMode) {
                                                toggleSelection(row.episodeIds)
                                            } else {
                                                playFromGroup(row.playEpisodeId)
                                            }
                                        },
                                        onLongClick = { selectedIds = selectedIds + row.episodeIds },
                                        reorderHandleModifier = reorderHandleModifier,
                                        onToggle = if (row.expandable && !isReordering) {
                                            {
                                                expansion = if (row.blockId in expansion.sources) {
                                                    expansion.copy(sources = expansion.sources - row.blockId)
                                                } else {
                                                    // The seasons are asked for on the
                                                    // way open, not with the playlist -
                                                    // see loadBlockSeasons.
                                                    onExpandSource(row.blockId)
                                                    expansion.copy(sources = expansion.sources + row.blockId)
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                    return@ReorderableItem
                                }
                                // A one-episode source falls through to the same
                                // EpisodeListItem as an ungrouped episode below.
                            }
                            is PlaylistRow.Season -> {
                                val seasonKey = row.blockId to row.seasonNumber
                                PlaylistGroupItem(
                                    title = row.label,
                                    subtitle = pluralStringResource(
                                        AYMR.plurals.anime_num_episodes,
                                        row.episodeCount,
                                        row.episodeCount,
                                    ),
                                    depth = 1,
                                    isCurrent = row.containsCurrent,
                                    isSelected = row.episodeIds.all(selectedIds::contains),
                                    expanded = row.expanded,
                                    onClick = {
                                        if (selectionMode) {
                                            toggleSelection(row.episodeIds)
                                        } else {
                                            playFromGroup(row.playEpisodeId)
                                        }
                                    },
                                    onLongClick = { selectedIds = selectedIds + row.episodeIds },
                                    reorderHandleModifier = reorderHandleModifier,
                                    onToggle = if (isReordering) {
                                        null
                                    } else {
                                        {
                                            expansion = if (seasonKey in expansion.seasons) {
                                                expansion.copy(seasons = expansion.seasons - seasonKey)
                                            } else {
                                                expansion.copy(seasons = expansion.seasons + seasonKey)
                                            }
                                        }
                                    },
                                )
                                return@ReorderableItem
                            }
                            is PlaylistRow.EpisodeItem -> Unit
                        }
                        val episodeRow = when (row) {
                            is PlaylistRow.Source -> PlaylistRow.EpisodeItem(
                                episode = requireNotNull(row.singleEpisode),
                                depth = 0,
                            )
                            is PlaylistRow.EpisodeItem -> row
                            is PlaylistRow.Season -> return@ReorderableItem
                        }
                        val selectionIds = when (row) {
                            is PlaylistRow.Source -> row.episodeIds
                            else -> listOfNotNull(episodeRow.episode.id)
                        }
                        val groupTitle = (row as? PlaylistRow.Source)?.title
                        val episode = episodeRow.episode
                        // <-- AM (QUEUE_GROUPED_PLAYLIST)

                        // AM (NOW_PLAYING_INDICATOR) -->
                        val isCurrentEpisode = currentEpisodeId != null && episode.id == currentEpisodeId
                        // <-- AM (NOW_PLAYING_INDICATOR)

                        val title = if (displayModeOf(episode) == Anime.EPISODE_DISPLAY_NUMBER) {
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

                        // AM (PLAYER_MARK_BADGES)
                        val episodeMarks = marks.marksOf(episode)

                        EpisodeListItem(
                            episode = episode,
                            isCurrentEpisode = isCurrentEpisode,
                            title = title,
                            date = date,
                            groupTitle = groupTitle,
                            // AM (PLAYER_MARK_BADGES) -->
                            isBookmarked = episodeMarks.bookmarked,
                            isFillermarked = episodeMarks.fillermarked,
                            onBookmarkClicked = marks::setBookmark,
                            onFillermarkClicked = marks::setFillermark,
                            // <-- AM (PLAYER_MARK_BADGES)
                            // AM (PLAYLIST_LIBRARY_BADGE)
                            isFromLibraryEntry = showLibraryBadges && isFromLibraryEntry(episode),
                            // AM (PLAYLIST_SWIPE_ACTIONS)
                            isSeen = episodeMarks.seen,
                            // AM (QUEUE_GROUPED_PLAYLIST)
                            nestingDepth = episodeRow.depth,
                            // AM (PLAYLIST_SWIPE_ACTIONS) -->
                            swipeStartAction = if (isReordering) {
                                LibraryPreferences.EpisodeSwipeAction.Disabled
                            } else {
                                swipeStartAction
                            },
                            swipeEndAction = if (isReordering) {
                                LibraryPreferences.EpisodeSwipeAction.Disabled
                            } else {
                                swipeEndAction
                            },
                            downloadState = episode.id
                                ?.let { downloadStates[it] }
                                ?: Download.State.NOT_DOWNLOADED,
                            onSwipe = { action ->
                                when (action) {
                                    LibraryPreferences.EpisodeSwipeAction.ToggleSeen ->
                                        marks.setSeen(episode.id, !episodeMarks.seen)
                                    LibraryPreferences.EpisodeSwipeAction.ToggleBookmark ->
                                        marks.setBookmark(episode.id, !episodeMarks.bookmarked)
                                    LibraryPreferences.EpisodeSwipeAction.ToggleFillermark ->
                                        marks.setFillermark(episode.id, !episodeMarks.fillermarked)
                                    LibraryPreferences.EpisodeSwipeAction.Download ->
                                        onDownloadClicked(episode)
                                    LibraryPreferences.EpisodeSwipeAction.Disabled -> Unit
                                }
                            },
                            // <-- AM (PLAYLIST_SWIPE_ACTIONS)
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
                                    else -> toggleSelection(selectionIds)
                                }
                            },
                            onOpenEntryClicked = onOpenEntryClicked,
                            // AM (OPEN_ENTRY_MERGED_ONLY) -->
                            // This list has a selection bar, so the marks live there.
                            marksInRow = false,
                            // <-- AM (OPEN_ENTRY_MERGED_ONLY)
                            onLongClick = { selectedIds = selectedIds + selectionIds },
                            selectionMode = selectionMode,
                            isSelected = selectionIds.isNotEmpty() && selectionIds.all(selectedIds::contains),
                            // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
                            reorderHandleModifier = reorderHandleModifier,
                        )
                    }
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
    // AM (PLAYER_ADD_TO_LIBRARY) -->
    isFavorited: Boolean,
    onAddToLibrary: () -> Unit,
    // <-- AM (PLAYER_ADD_TO_LIBRARY)
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

        // AM (PLAYER_ADD_TO_LIBRARY) -->
        // The entry screen's heart, same filled/outlined pair and the same
        // behaviour: it opens the categories dialog, whose checked set decides
        // whether the entry is in the library. Live in both states - a filled
        // heart is how an entry gets taken back out.
        IconButton(onClick = onAddToLibrary) {
            Icon(
                imageVector = if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(
                    if (isFavorited) MR.strings.in_library else MR.strings.add_to_library,
                ),
                tint = if (isFavorited) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        // <-- AM (PLAYER_ADD_TO_LIBRARY)
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
    // AM (PLAYLIST_SWIPE_ACTIONS)
    onSeen: (() -> Unit)?,
    onUnseen: (() -> Unit)?,
    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /** Null when the playlist has no entry to download from - see the dialog. */
    onDownload: (() -> Unit)?,
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)
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

        // AM (PLAYLIST_SWIPE_ACTIONS) -->
        if (onSeen != null) {
            IconButton(onClick = onSeen) {
                Icon(
                    imageVector = Icons.Outlined.Done,
                    contentDescription = stringResource(AYMR.strings.action_mark_as_seen),
                )
            }
        }
        if (onUnseen != null) {
            IconButton(onClick = onUnseen) {
                Icon(
                    imageVector = Icons.Outlined.RemoveDone,
                    contentDescription = stringResource(AYMR.strings.action_mark_as_unseen),
                )
            }
        }
        // <-- AM (PLAYLIST_SWIPE_ACTIONS)
        // AM (LOCAL_SOURCE_DOWNLOAD) -->
        // Ahead of the mark actions: downloading is the one that fetches
        // something, and the marks are bookkeeping beside it.
        if (onDownload != null) {
            IconButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = stringResource(MR.strings.action_download),
                )
            }
        }
        // <-- AM (LOCAL_SOURCE_DOWNLOAD)
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
