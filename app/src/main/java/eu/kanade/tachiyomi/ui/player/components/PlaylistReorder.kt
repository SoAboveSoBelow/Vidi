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

private class PlaylistDragSession(
    val startRows: List<PlaylistRow>,
    val groupKeys: Set<String>,
    val sourceDrag: Boolean,
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
    /** Full play order, including descendants hidden by collapsed groups. */
    episodes: List<Episode>,
    selectedIds: Set<Long>,
    blockOf: (Episode) -> Long?,
    seasonOf: (Episode) -> Long?,
    onMoveEpisodes: (episodeIds: List<Long>, afterEpisodeId: Long?) -> Unit,
    onReorderBlocks: (orderedBlockIds: List<Long>) -> Unit,
): PlaylistReorderUi {
    val localRows = remember { mutableStateListOf<PlaylistRow>() }
    var drag by remember { mutableStateOf<PlaylistDragSession?>(null) }

    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentEpisodes by rememberUpdatedState(episodes)
    val currentBlockOf by rememberUpdatedState(blockOf)
    val currentSeasonOf by rememberUpdatedState(seasonOf)
    val currentOnMoveEpisodes by rememberUpdatedState(onMoveEpisodes)
    val currentOnReorderBlocks by rememberUpdatedState(onReorderBlocks)

    val state = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = localRows.indexOfFirst { it.key == from.key }
        val toIndex = localRows.indexOfFirst { it.key == to.key }
        if (fromIndex != -1 && toIndex != -1) {
            localRows.add(toIndex, localRows.removeAt(fromIndex))
        }
    }

    LaunchedEffect(rows) {
        if (!state.isAnyItemDragging) {
            localRows.clear()
            localRows.addAll(rows)
        }
    }

    if (!enabled) return PlaylistReorderUi(rows, state, null)

    return PlaylistReorderUi(
        rows = localRows,
        state = state,
        row = PlaylistReorderRow(
            state = state,
            onDragStarted = start@{ rowKey ->
                val startRows = localRows.toList()
                val rowIndex = startRows.indexOfFirst { it.key == rowKey }
                if (rowIndex == -1) return@start
                val row = startRows[rowIndex]
                val group = when (row) {
                    is PlaylistRow.Source -> startRows.filter {
                        when (it) {
                            // A source may have several runs after episode
                            // interleaving. Source reorder gathers all of them.
                            is PlaylistRow.Source -> it.blockId == row.blockId
                            is PlaylistRow.Season -> it.blockId == row.blockId
                            is PlaylistRow.EpisodeItem -> currentBlockOf(it.episode) == row.blockId
                        }
                    }
                    is PlaylistRow.Season -> startRows.filter {
                        when (it) {
                            is PlaylistRow.Source -> false
                            is PlaylistRow.Season ->
                                it.blockId == row.blockId && it.seasonNumber == row.seasonNumber
                            is PlaylistRow.EpisodeItem ->
                                currentBlockOf(it.episode) == row.blockId &&
                                    currentSeasonOf(it.episode) == row.seasonNumber
                        }
                    }
                    is PlaylistRow.EpisodeItem -> {
                        val episodeId = row.episode.id
                        if (episodeId != null && episodeId in currentSelectedIds) {
                            startRows.filter {
                                it is PlaylistRow.EpisodeItem &&
                                    it.episode.id?.let(currentSelectedIds::contains) == true &&
                                    currentBlockOf(it.episode) != null
                            }
                        } else {
                            listOf(row)
                        }
                    }
                }
                val groupKeys = group.mapTo(mutableSetOf()) { it.key }
                drag = PlaylistDragSession(
                    startRows = startRows,
                    groupKeys = groupKeys,
                    sourceDrag = row is PlaylistRow.Source,
                )
                localRows.removeAll { it.key != rowKey && it.key in groupKeys }
            },
            onDragStopped = stop@{ rowKey ->
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

                if (session.sourceDrag) {
                    currentOnReorderBlocks(
                        reinserted.filterIsInstance<PlaylistRow.Source>().map { it.blockId }.distinct(),
                    )
                    return@stop
                }

                val draggedRow = session.startRows.firstOrNull { it.key == rowKey } ?: return@stop
                val movingIds = when (draggedRow) {
                    // A collapsed season has no visible child rows to carry.
                    // Resolve the same children from the full play order instead.
                    is PlaylistRow.Season -> currentEpisodes.filter {
                        currentBlockOf(it) == draggedRow.blockId &&
                            currentSeasonOf(it) == draggedRow.seasonNumber
                    }.mapNotNull { it.id }
                    else -> movingRows.filterIsInstance<PlaylistRow.EpisodeItem>()
                        .mapNotNull { it.episode.id }
                }
                if (movingIds.isEmpty()) return@stop
                val movingIdSet = movingIds.toSet()
                val firstPosition = if (draggedRow is PlaylistRow.Season) {
                    reinserted.indexOfFirst { it.key == rowKey }
                } else {
                    reinserted.indexOfFirst {
                        it is PlaylistRow.EpisodeItem && it.episode.id?.let(movingIdSet::contains) == true
                    }
                }
                if (firstPosition == -1) return@stop
                // The preceding PLAYABLE row is the global anchor, regardless
                // of which source owns it. Collapsed parents supply their own
                // hidden run; unqueued host rows and moving rows are skipped.
                val afterEpisodeId = reinserted.take(firstPosition).asReversed().firstNotNullOfOrNull { row ->
                    when (row) {
                        is PlaylistRow.EpisodeItem -> row.episode.id?.takeIf {
                            it !in movingIdSet && currentBlockOf(row.episode) != null
                        }
                        is PlaylistRow.Season -> row.episodeIds.lastOrNull { it !in movingIdSet }
                        is PlaylistRow.Source -> row.episodeIds.lastOrNull { it !in movingIdSet }
                    }
                }
                currentOnMoveEpisodes(movingIds, afterEpisodeId)
            },
        ),
    )
}
// <-- AM (QUEUE_REORDER)
