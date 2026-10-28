// AM (QUEUE_REORDER) -->
package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.data.database.models.Episode
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Drag wiring shared by episode, season, and source rows. */
class PlaylistReorderRow(
    val state: ReorderableLazyListState,
    val onDragStarted: (rowKey: String) -> Unit,
    val onDragStopped: (rowKey: String) -> Unit,
)

class PlaylistReorderUi(
    val rows: List<PlaylistRow>,
    val state: ReorderableLazyListState,
    val row: PlaylistReorderRow?,
)

// AM (QUEUE_DRAG_SELECTION) -->
/** The episodes this row stands for - a parent's whole run, or the one episode. */
private fun PlaylistRow.episodeIdsOrEmpty(): List<Long> = when (this) {
    is PlaylistRow.Source -> episodeIds
    is PlaylistRow.Season -> episodeIds
    is PlaylistRow.EpisodeItem -> listOfNotNull(episode.id)
}

private fun PlaylistRow.selectedEpisodeIds(selected: Set<Long>): List<Long> =
    episodeIdsOrEmpty().filter(selected::contains)
// <-- AM (QUEUE_DRAG_SELECTION)

/**
 * Whether this row is nested inside [parent] as the flattened list has them.
 *
 * Positional rather than by id: a run's rows are contiguous and end at the
 * next row of the same kind or shallower, which holds both when seasons sit
 * under a source and when a single-source queue shows seasons at the top.
 */
private fun PlaylistRow.isDescendantOf(parent: PlaylistRow): Boolean = when (parent) {
    is PlaylistRow.Source -> this !is PlaylistRow.Source
    is PlaylistRow.Season -> this is PlaylistRow.EpisodeItem
    is PlaylistRow.EpisodeItem -> false
}

private class PlaylistDragSession(
    val startRows: List<PlaylistRow>,
    val groupKeys: Set<String>,
    val sourceDrag: Boolean,
    // AM (QUEUE_DRAG_SELECTION)
    val selectionDrag: Boolean,
)

/**
 * Local-buffer dragging for the queue, following the entry list's reorder model.
 *
 * A selected episode carries the selection. A season carries its episodes, and
 * a source carries its whole tree, expanded or not; visible descendants fold
 * into the dragged row until the drop. Only then is one queue write issued. Nothing here
 * resolves or edits an entry's own episode/season order.
 */
@Composable
fun rememberPlaylistReorder(
    enabled: Boolean,
    listState: LazyListState,
    rows: List<PlaylistRow>,
    selectedIds: Set<Long>,
    blockOf: (Episode) -> Long?,
    onMoveEpisodes: (episodeIds: List<Long>, afterEpisodeId: Long?) -> Unit,
    onReorderBlocks: (orderedBlockIds: List<Long>) -> Unit,
    // AM (QUEUE_HOVER_EXPAND) -->
    /**
     * Called when the dragged row has rested over [rowKey] long enough to mean
     * it: the caller opens that group so the drag can be dropped inside it.
     */
    onHoverExpand: (rowKey: String) -> Unit,
    // <-- AM (QUEUE_HOVER_EXPAND)
): PlaylistReorderUi {
    val localRows = remember { mutableStateListOf<PlaylistRow>() }
    var drag by remember { mutableStateOf<PlaylistDragSession?>(null) }

    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentBlockOf by rememberUpdatedState(blockOf)
    val currentOnMoveEpisodes by rememberUpdatedState(onMoveEpisodes)
    // AM (QUEUE_HOVER_EXPAND)
    val currentOnHoverExpand by rememberUpdatedState(onHoverExpand)
    val currentOnReorderBlocks by rememberUpdatedState(onReorderBlocks)

    // AM (QUEUE_HOVER_EXPAND) -->
    // The row the drag is currently over, and the row being dragged. Both are
    // keys rather than indices: the list grows under the drag when a hover
    // opens a group, and an index would stop meaning the same row.
    var hoveredKey by remember { mutableStateOf<String?>(null) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    // <-- AM (QUEUE_HOVER_EXPAND)

    val state = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = localRows.indexOfFirst { it.key == from.key }
        val toIndex = localRows.indexOfFirst { it.key == to.key }
        if (fromIndex != -1 && toIndex != -1) {
            localRows.add(toIndex, localRows.removeAt(fromIndex))
        }
        // AM (QUEUE_HOVER_EXPAND): passing over a row restarts its dwell, so
        // sweeping the length of the queue opens nothing on the way.
        hoveredKey = to.key as? String
    }

    // AM (QUEUE_HOVER_EXPAND) -->
    // The dwell. Keyed on the hovered row, so each new row cancels the
    // previous wait rather than queueing another expansion behind it.
    LaunchedEffect(hoveredKey) {
        val key = hoveredKey ?: return@LaunchedEffect
        delay(HOVER_EXPAND_DELAY)
        currentOnHoverExpand(key)
    }
    // <-- AM (QUEUE_HOVER_EXPAND)

    LaunchedEffect(rows) {
        if (!state.isAnyItemDragging) {
            localRows.clear()
            localRows.addAll(rows)
            return@LaunchedEffect
        }
        // AM (QUEUE_HOVER_EXPAND) -->
        // A hover opened a group mid-drag, so the rows the drag is working on
        // have to take in the revealed children - while keeping the dragged
        // row where the finger currently has it, and keeping the rest of its
        // own group folded into it.
        val session = drag ?: return@LaunchedEffect
        val dragged = draggedKey ?: return@LaunchedEffect
        val heldIndex = localRows.indexOfFirst { it.key == dragged }
        if (heldIndex == -1) return@LaunchedEffect
        val rebuilt = rows
            .filterNot { it.key != dragged && it.key in session.groupKeys }
            .toMutableList()
        val arrivedIndex = rebuilt.indexOfFirst { it.key == dragged }
        if (arrivedIndex == -1) return@LaunchedEffect
        rebuilt.add(heldIndex.coerceAtMost(rebuilt.size - 1), rebuilt.removeAt(arrivedIndex))
        localRows.clear()
        localRows.addAll(rebuilt)
        // <-- AM (QUEUE_HOVER_EXPAND)
    }

    if (!enabled) return PlaylistReorderUi(rows, state, null)

    return PlaylistReorderUi(
        rows = localRows,
        state = state,
        row = PlaylistReorderRow(
            state = state,
            onDragStarted = start@{ rowKey ->
                // AM (QUEUE_HOVER_EXPAND)
                draggedKey = rowKey
                hoveredKey = null
                val startRows = localRows.toList()
                val rowIndex = startRows.indexOfFirst { it.key == rowKey }
                if (rowIndex == -1) return@start
                val row = startRows[rowIndex]
                // AM (QUEUE_DRAG_SELECTION) -->
                // Dragging something that is part of the selection carries the
                // WHOLE selection, wherever the rest of it sits - which is
                // what a selection is for. Only an episode row used to do
                // this; a source or season header gathered its own run and
                // silently left the rest of the selection behind.
                val draggedIsSelected = row.selectedEpisodeIds(currentSelectedIds).isNotEmpty() &&
                    row.episodeIdsOrEmpty().all(currentSelectedIds::contains)
                val group = when {
                    draggedIsSelected -> startRows.filter { candidate ->
                        val ids = candidate.episodeIdsOrEmpty()
                        ids.isNotEmpty() && ids.all(currentSelectedIds::contains)
                    }
                    // THIS run's rows, taken by their extent in the flattened
                    // list rather than by matching ids across the whole queue.
                    // A source can appear as several runs once episodes are
                    // interleaved, so gathering every run of the block would
                    // move more than the row the user grabbed.
                    row is PlaylistRow.Source || row is PlaylistRow.Season ->
                        listOf(row) + startRows.drop(rowIndex + 1).takeWhile { it.isDescendantOf(row) }
                    else -> listOf(row)
                }
                // <-- AM (QUEUE_DRAG_SELECTION)
                val groupKeys = group.mapTo(mutableSetOf()) { it.key }
                drag = PlaylistDragSession(
                    startRows = startRows,
                    groupKeys = groupKeys,
                    sourceDrag = row is PlaylistRow.Source,
                    // AM (QUEUE_DRAG_SELECTION)
                    selectionDrag = draggedIsSelected,
                )
                localRows.removeAll { it.key != rowKey && it.key in groupKeys }
            },
            onDragStopped = stop@{ rowKey ->
                // AM (QUEUE_HOVER_EXPAND)
                draggedKey = null
                hoveredKey = null
                val session = drag ?: return@stop
                drag = null
                val endRows = localRows.toList()
                if (session.groupKeys.size == 1 && session.startRows == endRows) return@stop

                val position = endRows.indexOfFirst { it.key == rowKey }
                if (position == -1) return@stop
                val movingRows = session.startRows.filter { it.key in session.groupKeys }
                val reinserted = endRows.take(position) + movingRows + endRows.drop(position + 1)
                localRows.clear()
                localRows.addAll(reinserted)

                val draggedRow = session.startRows.firstOrNull { it.key == rowKey } ?: return@stop
                // AM (QUEUE_DRAG_SELECTION) -->
                // In play order, taken across every row the drag carries, so a
                // collapsed parent contributes the children it is hiding and
                // the same episode reached through two rows counts once.
                val movingIds = if (session.selectionDrag) {
                    movingRows.flatMap { it.episodeIdsOrEmpty() }.distinct()
                } else {
                    when (draggedRow) {
                        // The run's own play order, which the row already
                        // carries including children hidden while collapsed.
                        is PlaylistRow.Source -> draggedRow.episodeIds
                        is PlaylistRow.Season -> draggedRow.episodeIds
                        is PlaylistRow.EpisodeItem -> movingRows.filterIsInstance<PlaylistRow.EpisodeItem>()
                            .mapNotNull { it.episode.id }
                    }
                }
                // <-- AM (QUEUE_DRAG_SELECTION)
                // AM (QUEUE_SOURCE_INTERLEAVE) -->
                // A source with no episode rows at all is a block still
                // waiting on its fetch: there is nothing to move, so its slot
                // is the only thing that can be reordered.
                if (movingIds.isEmpty()) {
                    if (session.sourceDrag) {
                        currentOnReorderBlocks(
                            reinserted.filterIsInstance<PlaylistRow.Source>().map { it.blockId }.distinct(),
                        )
                    }
                    return@stop
                }
                // <-- AM (QUEUE_SOURCE_INTERLEAVE)
                val movingIdSet = movingIds.toSet()
                // AM (QUEUE_SOURCE_INTERLEAVE): a parent row's children may be
                // collapsed, so the header's own position is where its run lands.
                val firstPosition = if (draggedRow !is PlaylistRow.EpisodeItem) {
                    reinserted.indexOfFirst { it.key == rowKey }
                } else {
                    reinserted.indexOfFirst {
                        it is PlaylistRow.EpisodeItem && it.episode.id?.let(movingIdSet::contains) == true
                    }
                }
                if (firstPosition == -1) return@stop
                // The preceding PLAYABLE row is the global anchor, regardless
                // of which source owns it; unqueued host rows and moving rows
                // are skipped.
                //
                // AM (QUEUE_DROP_INTO_EXPANDED_PARENT): a parent counts only
                // while it is COLLAPSED, where its whole run is hidden behind
                // it and landing after the header means landing after all of
                // it. An EXPANDED parent is followed by its own episode rows,
                // so landing right after that header means landing BEFORE
                // them - it must contribute nothing and the walk carries on
                // past it. Treating both alike anchored such a drop after the
                // group's last episode instead of before its first, which is
                // most obvious on a one-episode season: there is no "between"
                // to aim at, so every drop inside it silently became a drop
                // after it, and the source never split.
                val afterEpisodeId = reinserted.take(firstPosition).asReversed().firstNotNullOfOrNull { row ->
                    when (row) {
                        is PlaylistRow.EpisodeItem -> row.episode.id?.takeIf {
                            it !in movingIdSet && currentBlockOf(row.episode) != null
                        }
                        is PlaylistRow.Season ->
                            if (row.expanded) null else row.episodeIds.lastOrNull { it !in movingIdSet }
                        is PlaylistRow.Source ->
                            if (row.expanded) null else row.episodeIds.lastOrNull { it !in movingIdSet }
                    }
                }
                currentOnMoveEpisodes(movingIds, afterEpisodeId)
            },
        ),
    )
}
// <-- AM (QUEUE_REORDER)

// AM (QUEUE_HOVER_EXPAND)
/** How long the drag must rest on a group before it opens. */
private val HOVER_EXPAND_DELAY = 450.milliseconds
