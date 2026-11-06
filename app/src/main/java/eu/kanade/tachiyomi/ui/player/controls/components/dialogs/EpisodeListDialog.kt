package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

// AM (QUEUE_DIALOG_LANDSCAPE_HEIGHT)
import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
// AM (PLAYLIST_ADD_TO_LIBRARY)
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
// AM (PLAYLIST_ADD_TO_LIBRARY)
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
// AM (PLAYLIST_ADD_TO_LIBRARY)
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material.icons.outlined.RemoveDone
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
// AM (QUEUE_DIALOG_LANDSCAPE_HEIGHT)
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.player.PlaylistSearchScope
import aniyomi.domain.season.model.EntrySeason
import eu.kanade.tachiyomi.ui.player.components.EpisodeListItem
import eu.kanade.tachiyomi.ui.player.components.MarkCycle
import eu.kanade.tachiyomi.ui.player.components.MarkTriState
import eu.kanade.tachiyomi.ui.player.components.MarkTriStateButton
import eu.kanade.tachiyomi.ui.player.components.MarkTriStateIcons
import eu.kanade.tachiyomi.ui.player.components.PlaylistExpansion
import eu.kanade.tachiyomi.ui.player.components.PlaylistGroupItem
// AM (PLAYLIST_ADD_TO_LIBRARY)
import eu.kanade.tachiyomi.ui.player.components.PlaylistGroupSelection
import eu.kanade.tachiyomi.ui.player.components.PlaylistRow
import eu.kanade.tachiyomi.ui.player.components.PlaylistSeasons
import eu.kanade.tachiyomi.ui.player.components.buildPlaylistRows
import eu.kanade.tachiyomi.ui.player.components.contains
import eu.kanade.tachiyomi.ui.player.components.playingSourceKey
import eu.kanade.tachiyomi.ui.player.components.rememberEpisodeMarks
import eu.kanade.tachiyomi.ui.player.components.rememberPlaylistReorder
import eu.kanade.tachiyomi.util.lang.toRelativeString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.domain.library.model.search.QueryNode
import mihon.feature.library.EpisodeSearchFields
import mihon.feature.library.matches
import tachiyomi.domain.anime.model.Anime
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.VerticalFastScroller
// AM (PLAYLIST_ADD_TO_LIBRARY)
import tachiyomi.presentation.core.components.material.padding
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
    // AM (QUEUE_SOURCE_TOTALS)
    /** The id of that entry, for totalling a source's episodes across its runs. */
    sourceIdOf: (Episode) -> Long?,
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
    // AM (PLAYBACK_SEEN_REACHES_PLAYLIST)
    /** Episodes playback itself finished - see PlayerStateData.playlistPlaybackSeen. */
    playbackSeen: Set<Long>,
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
    // AM (OPEN_ENTRY_ON_DOUBLE_TAP) -->
    /**
     * Opens the entry a source or season row stands for - the merge parent on
     * a merged entry, since that is the entry those rows describe.
     *
     * Null where the player cannot get out of the way to show it.
     */
    onOpenSource: ((blockId: Long) -> Unit)?,
    /**
     * Opens the entry an episode actually came from - the merge CHILD on a
     * merged entry, which is the source that episode is.
     */
    onOpenEpisodeEntry: ((episodeId: Long) -> Unit)?,
    // <-- AM (OPEN_ENTRY_ON_DOUBLE_TAP)
    // AM (PLAYLIST_ADD_TO_LIBRARY) -->
    /**
     * Library membership for the selection, from the selection bar.
     *
     * Takes the blocks whose header was selected and the episodes selected on
     * their own, separately, because on a merged entry those name different
     * entries - see PlayerViewModel.changePlaylistSelectionCategories. Null
     * where the player cannot raise the dialog it leads to.
     */
    onChangeCategories: ((blockIds: List<Long>, episodeIds: List<Long>) -> Unit)?,
    // <-- AM (PLAYLIST_ADD_TO_LIBRARY)
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
    // AM (PLAYLIST_ADD_TO_LIBRARY) -->
    // Which group rows were selected AS groups, by row key.
    //
    // The selection itself is a set of episode ids, which is all the episode
    // actions need - but it cannot say whether the user picked a source header
    // or happened to pick every episode under it, and on a merged entry those
    // two name different entries (the merge, or the child an episode came
    // from). Inferring it from "all of them are selected" would answer wrongly
    // for a child that holds a single episode, so the intent is recorded where
    // it is expressed instead. Each record still has to hold up: a group whose
    // episodes are no longer all selected does not count, so deselecting one
    // episode out of a selected source puts the rest back to being episodes.
    var selectedGroups by remember { mutableStateOf(emptyMap<String, PlaylistGroupSelection>()) }
    val clearSelection = {
        selectedIds = emptySet()
        selectedGroups = emptyMap()
    }
    // <-- AM (PLAYLIST_ADD_TO_LIBRARY)
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
    // AM (PLAYLIST_ADD_TO_LIBRARY) -->
    // The two ways a group row enters the selection, recording the row as a
    // group as they go. Both defer to the plain episode-id selection for the
    // selection itself; only the record is new.
    val toggleGroup: (String, Long, List<Long>) -> Unit = { key, blockId, ids ->
        val wasSelected = ids.isNotEmpty() && ids.all(selectedIds::contains)
        toggleSelection(ids)
        selectedGroups = if (wasSelected) {
            selectedGroups - key
        } else {
            selectedGroups + (key to PlaylistGroupSelection(blockId, ids))
        }
    }
    val selectGroup: (String, Long, List<Long>) -> Unit = { key, blockId, ids ->
        selectedIds = selectedIds + ids
        selectedGroups = selectedGroups + (key to PlaylistGroupSelection(blockId, ids))
    }
    // <-- AM (PLAYLIST_ADD_TO_LIBRARY)
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
        // AM (PLAYBACK_SEEN_REACHES_PLAYLIST)
        playbackSeen = playbackSeen,
    )
    // <-- AM (PLAYER_MARK_BADGES)
    // AM (PLAYLIST_MARK_TRISTATE) -->
    // One cycle per mark, reading and writing through the mark state so a tap
    // redraws the rows as well as the button. Each reads the selection live
    // rather than capturing it, so growing the selection mid-cycle acts on
    // what is selected now.
    val currentSelection = rememberUpdatedState(selectedEpisodes)
    val seenCycle = remember(marks) {
        MarkCycle<Long>(
            idsOf = { currentSelection.value.mapNotNull { it.id } },
            markedOf = { id: Long ->
                currentSelection.value.firstOrNull { it.id == id }?.let { marks.marksOf(it).seen } == true
            },
            setMark = { id: Long, value: Boolean -> marks.setSeen(id, value) },
        )
    }
    val bookmarkCycle = remember(marks) {
        MarkCycle<Long>(
            idsOf = { currentSelection.value.mapNotNull { it.id } },
            markedOf = { id: Long ->
                currentSelection.value.firstOrNull { it.id == id }?.let { marks.marksOf(it).bookmarked } == true
            },
            setMark = { id: Long, value: Boolean -> marks.setBookmark(id, value) },
        )
    }
    val fillermarkCycle = remember(marks) {
        MarkCycle<Long>(
            idsOf = { currentSelection.value.mapNotNull { it.id } },
            markedOf = { id: Long ->
                currentSelection.value.firstOrNull { it.id == id }?.let { marks.marksOf(it).fillermarked } == true
            },
            setMark = { id: Long, value: Boolean -> marks.setFillermark(id, value) },
        )
    }
    // A changed selection is a different question, so the cycles start over
    // rather than restoring a snapshot taken of other rows.
    LaunchedEffect(selectedIds) {
        seenCycle.reset()
        bookmarkCycle.reset()
        fillermarkCycle.reset()
    }
    // <-- AM (PLAYLIST_MARK_TRISTATE)

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
    // AM (QUEUE_EXPAND_PER_RUN): the RUN holding the playing episode, not its
    // block - a split source has several runs, and only the one being played
    // should open.
    var expansion by remember(currentEpisodeId) {
        val playingSource = playingSourceKey(orderedEpisodeList, blockOf, currentEpisodeId)
        mutableStateOf(PlaylistExpansion(sources = setOfNotNull(playingSource)))
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
                // AM (QUEUE_SOURCE_TOTALS): the entry an episode was queued as,
                // which is what makes a split source still count as one.
                sourceIdOf = sourceIdOf,
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
    // AM (QUEUE_EXPAND_PER_RUN): tracked by source row key, like the expansion
    // itself, and the season to open is found among the rows now on screen -
    // the season rows for an expanded source exist by the time this runs, and
    // the target episode falls in exactly one of them.
    var seasonAutoExpanded by remember(currentEpisodeId) { mutableStateOf(emptySet<String>()) }
    // AM (QUEUE_HOVER_EXPAND): groups opened by a hover rather than by hand,
    // so they can be closed again when the drag moves out of them.
    var hoverExpanded by remember(currentEpisodeId) { mutableStateOf(emptySet<String>()) }
    val currentRows = rememberUpdatedState(baseRows)
    LaunchedEffect(expansion.sources, blockSeasons, currentEpisodeId) {
        val pending = expansion.sources - seasonAutoExpanded
        if (pending.isEmpty()) return@LaunchedEffect
        val rows = currentRows.value
        var seasons = expansion.seasons
        val resolved = mutableSetOf<String>()
        pending.forEach { sourceKey ->
            val sourceRow = rows.filterIsInstance<PlaylistRow.Source>()
                .firstOrNull { it.key == sourceKey }
                ?: return@forEach
            // Not resolved yet; the effect runs again when it lands.
            blockSeasons[sourceRow.blockId] ?: return@forEach
            resolved += sourceKey
            val target = sourceRow.episodeIds.firstOrNull { it == currentEpisodeId }
                ?: sourceRow.episodeIds.firstOrNull { it == blockLastPlayed[sourceRow.blockId] }
                ?: return@forEach
            val seasonRow = rows.filterIsInstance<PlaylistRow.Season>()
                .firstOrNull { target in it.episodeIds }
                ?: return@forEach
            seasons = seasons + seasonRow.key
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
        selectedIds = selectedIds,
        blockOf = blockOf,
        // AM (QUEUE_HOVER_EXPAND) -->
        // Resting the drag on a closed group opens it, so something can be
        // dropped inside a source without breaking the drag to go and open it
        // first. A source opens its seasons too - see the cascade below -
        // since a source that reveals only collapsed seasons is no more
        // droppable than it was shut.
        onHoverExpand = { key ->
            val row = baseRows.firstOrNull { it.key == key }
            val opened = when (row) {
                // One layer per hover: a source reveals its SEASONS and
                // stops. Opening its seasons as well dumped every episode of
                // the source into the list at once, which is a lot of rows to
                // appear under a moving finger - and the season the drop is
                // actually headed for gets its own hover.
                is PlaylistRow.Source -> (row.expandable && !row.expanded).also {
                    if (it) {
                        onExpandSource(row.blockId)
                        expansion = expansion.copy(sources = expansion.sources + key)
                    }
                }
                is PlaylistRow.Season -> (!row.expanded).also {
                    if (it) expansion = expansion.copy(seasons = expansion.seasons + key)
                }
                else -> false
            }
            if (row != null) {
                // Whatever the drag has moved on from closes again, so the
                // list does not fill up with everything hovered on the way.
                // Only the branch the drag is in stays open: a group holding
                // the row now under the finger is an ancestor of it, not
                // somewhere the drag has left.
                val keep = hoverExpanded.filterTo(mutableSetOf()) { openKey ->
                    openKey == key || baseRows.firstOrNull { it.key == openKey }.contains(row)
                }
                val closing = hoverExpanded - keep
                if (closing.isNotEmpty()) {
                    expansion = expansion.copy(
                        sources = expansion.sources - closing,
                        seasons = expansion.seasons - closing,
                    )
                }
                hoverExpanded = if (opened) keep + key else keep
            }
        },
        // <-- AM (QUEUE_HOVER_EXPAND)
        onMoveEpisodes = onMoveQueueEpisodes,
        onReorderBlocks = onReorderQueueBlocks,
    )
    val dateFormatter = remember(dateFormat) { UiPreferences.dateFormat(dateFormat) }

    // AM (QUEUE_DIALOG_LANDSCAPE_HEIGHT) -->
    // A list wants height, and landscape is where there is least of it - the
    // orientation the player is almost always in. One fraction for both left
    // the dialog with room to spare at the sides and a handful of rows in the
    // middle. Landscape takes nearly all of the height instead; portrait is
    // unchanged, where 0.8 of a tall screen is already a long list.
    // See PlayerSheet, which varies its width the same way.
    val heightFraction = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        0.95F
    } else {
        0.8F
    }
    // <-- AM (QUEUE_DIALOG_LANDSCAPE_HEIGHT)

    PlayerDialog(
        title = stringResource(AYMR.strings.episodes),
        // AM (QUEUE_DIALOG_LANDSCAPE_HEIGHT)
        modifier = Modifier.fillMaxHeight(fraction = heightFraction).fillMaxWidth(fraction = 0.8F),
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
                // AM (PLAYLIST_ADD_TO_LIBRARY) -->
                // Group records that still hold, and the episodes left over
                // once their episodes are accounted for - the two halves the
                // merge-aware resolution needs.
                val liveGroups = remember(selectedGroups, selectedIds) {
                    selectedGroups.values.filter { group ->
                        group.episodeIds.isNotEmpty() && group.episodeIds.all(selectedIds::contains)
                    }
                }
                val groupBlockIds = remember(liveGroups) {
                    liveGroups.map { it.blockId }.distinct()
                }
                val looseEpisodeIds = remember(liveGroups, selectedIds) {
                    val claimed = liveGroups.flatMap { it.episodeIds }.toSet()
                    (selectedIds - claimed).toList()
                }
                // <-- AM (PLAYLIST_ADD_TO_LIBRARY)
                EpisodeListSelectionHeader(
                    selectedCount = selectedIds.size,
                    // AM (PLAYLIST_ADD_TO_LIBRARY) -->
                    // Clears the selection: the dialog that follows is about
                    // entries, and leaving a row selection standing behind it
                    // would say the episodes are still about to be acted on.
                    onAddToLibrary = onChangeCategories?.let { change ->
                        {
                            change(groupBlockIds, looseEpisodeIds)
                            clearSelection()
                        }
                    },
                    // <-- AM (PLAYLIST_ADD_TO_LIBRARY)
                    // AM (PLAYLIST_MARK_TRISTATE) -->
                    // The cycle keeps the selection: unlike download, which
                    // acts once and is done, these three are states the user
                    // may step through - clearing the selection under them
                    // would end the cycle after one tap.
                    seenState = seenCycle.observed.takeIf { offersSeen },
                    onCycleSeen = seenCycle::advance,
                    bookmarkState = bookmarkCycle.observed.takeIf { offersBookmark },
                    onCycleBookmark = bookmarkCycle::advance,
                    fillermarkState = fillermarkCycle.observed.takeIf { offersFillermark },
                    onCycleFillermark = fillermarkCycle::advance,
                    // <-- AM (PLAYLIST_MARK_TRISTATE)
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
                        // AM (QUEUE_SOURCE_TOTALS) -->
                        // A header that holds the whole source states its
                        // size; one that holds part of a split source states
                        // which part, since either number alone misreads - the
                        // run understates the source and the source overstates
                        // the run. Resolved off the context so both arms are
                        // one expression.
                        val groupSubtitle: (Int, Int) -> String = { inRun, inSource ->
                            if (inRun == inSource) {
                                context.pluralStringResource(AYMR.plurals.anime_num_episodes, inRun, inRun)
                            } else {
                                context.stringResource(AMMR.strings.am_queue_run_of_source, inRun, inSource)
                            }
                        }
                        // <-- AM (QUEUE_SOURCE_TOTALS)
                        val playFromGroup: (Long?) -> Unit = { id ->
                            if (!selectionMode && id != null) {
                                playlistAnimeId?.let { PlaylistSearchScope.submit(it, searchQuery) }
                                onEpisodeClicked(id)
                            }
                        }
                        // AM (OPEN_ENTRY_ON_DOUBLE_TAP) -->
                        // A parent row stands for the entry the block was
                        // queued as, so both source and season headers open
                        // that - the merge on a merged entry. Off while
                        // selecting or reordering, where a tap means something
                        // else entirely.
                        val openSource: ((Long) -> (() -> Unit))? = onOpenSource
                            ?.takeIf { !selectionMode && !isReordering }
                            ?.let { open -> { blockId: Long -> { open(blockId) } } }
                        // <-- AM (OPEN_ENTRY_ON_DOUBLE_TAP)
                        when (row) {
                            is PlaylistRow.Source -> {
                                if (row.singleEpisode == null) {
                                    PlaylistGroupItem(
                                        title = row.title,
                                        // AM (QUEUE_SOURCE_TOTALS)
                                        subtitle = groupSubtitle(row.episodeCount, row.sourceEpisodeCount),
                                        depth = 0,
                                        isCurrent = row.containsCurrent,
                                        isSelected = row.episodeIds.all(selectedIds::contains),
                                        expanded = row.expanded,
                                        onClick = {
                                            if (selectionMode) {
                                                // AM (PLAYLIST_ADD_TO_LIBRARY)
                                                toggleGroup(row.key, row.blockId, row.episodeIds)
                                            } else {
                                                playFromGroup(row.playEpisodeId)
                                            }
                                        },
                                        // AM (PLAYLIST_ADD_TO_LIBRARY)
                                        onLongClick = { selectGroup(row.key, row.blockId, row.episodeIds) },
                                        // AM (OPEN_ENTRY_ON_DOUBLE_TAP)
                                        onDoubleClick = openSource?.invoke(row.blockId),
                                        reorderHandleModifier = reorderHandleModifier,
                                        // AM (QUEUE_HOVER_EXPAND): live while
                                        // reordering too - hovering opens
                                        // groups, so there has to be a way to
                                        // shut them without leaving the view.
                                        onToggle = if (row.expandable) {
                                            {
                                                // AM (QUEUE_EXPAND_PER_RUN)
                                                expansion = if (row.key in expansion.sources) {
                                                    expansion.copy(sources = expansion.sources - row.key)
                                                } else {
                                                    // The seasons are asked for on the
                                                    // way open, not with the playlist -
                                                    // see loadBlockSeasons.
                                                    onExpandSource(row.blockId)
                                                    expansion.copy(sources = expansion.sources + row.key)
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
                                // AM (QUEUE_EXPAND_PER_RUN)
                                val seasonKey = row.key
                                PlaylistGroupItem(
                                    title = row.label,
                                    // AM (QUEUE_SOURCE_TOTALS)
                                    subtitle = groupSubtitle(row.episodeCount, row.sourceEpisodeCount),
                                    depth = 1,
                                    isCurrent = row.containsCurrent,
                                    isSelected = row.episodeIds.all(selectedIds::contains),
                                    expanded = row.expanded,
                                    onClick = {
                                        if (selectionMode) {
                                            // AM (PLAYLIST_ADD_TO_LIBRARY)
                                            toggleGroup(row.key, row.blockId, row.episodeIds)
                                        } else {
                                            playFromGroup(row.playEpisodeId)
                                        }
                                    },
                                    // AM (PLAYLIST_ADD_TO_LIBRARY)
                                    onLongClick = { selectGroup(row.key, row.blockId, row.episodeIds) },
                                    // AM (OPEN_ENTRY_ON_DOUBLE_TAP): the season of
                                    // a merge is still the merge, not the child
                                    // whichever of its episodes happens to be first.
                                    onDoubleClick = openSource?.invoke(row.blockId),
                                    reorderHandleModifier = reorderHandleModifier,
                                    // AM (QUEUE_HOVER_EXPAND): live while
                                    // reordering, as the source chevron is.
                                    onToggle = {
                                        expansion = if (seasonKey in expansion.seasons) {
                                            expansion.copy(seasons = expansion.seasons - seasonKey)
                                        } else {
                                            expansion.copy(seasons = expansion.seasons + seasonKey)
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

                        // AM (QUEUE_SINGLE_EPISODE_SOURCE_TITLE) -->
                        // The source's name above the episode's, which is what
                        // tells a one-episode run apart from the rows of the
                        // source above it - unless the two are the same name, as
                        // they are for a film or any other entry whose single
                        // episode is titled after it. Saying it twice is not
                        // provenance, it is a row with its date pushed out by a
                        // repeat of its own title, so the duplicate goes and the
                        // row reads like any other episode: title, then date.
                        val groupTitle = (row as? PlaylistRow.Source)?.title
                            ?.takeIf { !it.trim().equals(title.trim(), ignoreCase = true) }
                        // <-- AM (QUEUE_SINGLE_EPISODE_SOURCE_TITLE)

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
                            // AM (OPEN_ENTRY_ON_DOUBLE_TAP) -->
                            // An episode opens the entry it actually came
                            // from, which on a merged entry is the child - the
                            // source this episode is - rather than the parent
                            // its headers describe.
                            onDoubleClick = onOpenEpisodeEntry
                                ?.takeIf { !selectionMode && !isReordering }
                                ?.let { open -> episode.id?.let { id -> { open(id) } } },
                            // <-- AM (OPEN_ENTRY_ON_DOUBLE_TAP)
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
        // AM (OPEN_ENTRY_ON_DOUBLE_TAP): the open-entry eye is gone from here.
        // Every row can be opened by double tapping it, and which entry that
        // means is a property of the row rather than of the list, so a single
        // header button could only ever offer one of the answers.
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

        // AM (OPEN_ENTRY_ON_DOUBLE_TAP): the add-to-library heart is gone too.
        // Opening an entry is now a double tap away, and adding it to the
        // library is something its own screen already does - with its
        // categories, its duplicate check and its cover in front of the user.
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
    // AM (PLAYLIST_MARK_TRISTATE) -->
    /**
     * One cycling button per mark, null where that mark is already a swipe
     * action. Replaces the add/remove pair each one needed.
     */
    seenState: MarkTriState?,
    onCycleSeen: () -> Unit,
    bookmarkState: MarkTriState?,
    onCycleBookmark: () -> Unit,
    fillermarkState: MarkTriState?,
    onCycleFillermark: () -> Unit,
    // <-- AM (PLAYLIST_MARK_TRISTATE)
    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /** Null when the playlist has no entry to download from - see the dialog. */
    onDownload: (() -> Unit)?,
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)
    // AM (PLAYLIST_ADD_TO_LIBRARY)
    onAddToLibrary: (() -> Unit)?,
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

        // AM (PLAYLIST_ADD_TO_LIBRARY) -->
        // First of the actions, and on its own: the three that follow are
        // marks on the episodes selected, while this one is about the entries
        // those episodes belong to - a different object with a different
        // lifetime, which is worth a gap rather than a fourth button in the
        // same run of them.
        if (onAddToLibrary != null) {
            IconButton(onClick = onAddToLibrary) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(MR.strings.add_to_library),
                )
            }
            Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
        }
        // <-- AM (PLAYLIST_ADD_TO_LIBRARY)
        // AM (PLAYLIST_MARK_TRISTATE) -->
        if (seenState != null) {
            MarkTriStateButton(
                state = seenState,
                // Two checks for all of them, one for some, the struck-through
                // pair for none.
                icons = MarkTriStateIcons(
                    all = Icons.Outlined.DoneAll,
                    partial = Icons.Outlined.Done,
                    none = Icons.Outlined.RemoveDone,
                ),
                contentDescription = stringResource(AYMR.strings.action_mark_as_seen),
                onClick = onCycleSeen,
            )
        }
        // <-- AM (PLAYLIST_MARK_TRISTATE)
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
        // AM (PLAYLIST_MARK_TRISTATE) -->
        if (bookmarkState != null) {
            MarkTriStateButton(
                state = bookmarkState,
                // AM (PLAYLIST_MARK_TRISTATE): one family - the same bookmark
                // carrying +, = or - in the badge corner.
                icons = MarkTriStateIcons(
                    all = Icons.Outlined.BookmarkAdd,
                    partial = ImageVector.vectorResource(R.drawable.ic_bookmark_mixed_24dp),
                    none = Icons.Outlined.BookmarkRemove,
                ),
                contentDescription = stringResource(AYMR.strings.action_bookmark_episode),
                onClick = onCycleBookmark,
            )
        }
        if (fillermarkState != null) {
            MarkTriStateButton(
                state = fillermarkState,
                // AM (PLAYLIST_MARK_TRISTATE): likewise - the add label's own
                // shape, with its "+" replaced by "=" and "-". Material has no
                // label_remove or label_mixed, so both are drawn from
                // new_label's geometry; see those drawables.
                icons = MarkTriStateIcons(
                    all = Icons.Outlined.NewLabel,
                    partial = ImageVector.vectorResource(R.drawable.ic_label_mixed_24dp),
                    none = ImageVector.vectorResource(R.drawable.ic_label_remove_24dp),
                ),
                contentDescription = stringResource(AYMR.strings.action_fillermark_episode),
                onClick = onCycleFillermark,
            )
        }
        // <-- AM (PLAYLIST_MARK_TRISTATE)
    }
}
// <-- AM (PLAYER_EPISODE_LIST_SELECTION)
