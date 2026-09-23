// AM (CUSTOM_EPISODE_ORDER) -->
package eu.kanade.presentation.anime.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import aniyomi.domain.order.interactor.MoveEpisodesInOrder
import eu.kanade.tachiyomi.ui.anime.EpisodeList
import sh.calvin.reorderable.ReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** Everything the screen can ask of reorder mode, bundled to keep the layout signatures flat. */
@Immutable
data class EpisodeReorderActions(
    val onEnter: () -> Unit,
    /** Toggle off: back to plain selection mode, selection kept. */
    val onLeave: () -> Unit,
    /** X or back: out of selection mode entirely. */
    val onExit: () -> Unit,
    val onMove: (episodeIds: List<Long>, targetSeason: Long, placement: MoveEpisodesInOrder.Placement) -> Unit,
    val onChangeSeason: () -> Unit,
    val onDiscard: () -> Unit,
    val onReset: () -> Unit,
)

/**
 * Per-row drag wiring handed to sharedEpisodeItems while reordering.
 * [draggingGroupSize] is how many episodes the current drag carries (0 when
 * nothing is being dragged), for the count shown on the dragged row.
 */
class EpisodeReorderRow(
    val state: ReorderableLazyGridState,
    val draggingGroupSize: Int,
    val onDragStarted: (episodeId: Long) -> Unit,
    val onDragStopped: (episodeId: Long) -> Unit,
)

/** One drag in progress: what was on screen when it started, and what it carries. */
private class DragSession(
    val startIds: List<Long>,
    val group: Set<Long>,
)

/**
 * [items] in the order to render them, and the row wiring - null when not
 * reordering, in which case [items] is the input unchanged.
 */
class EpisodeReorderUi(
    val items: List<EpisodeList.Item>,
    val row: EpisodeReorderRow?,
)

/**
 * Drag handling for reorder mode.
 *
 * The reorder library needs the list to change the instant an item crosses
 * another, so dragging runs against a local buffer of ids rather than the
 * ViewModel's list. Nothing is written until the drop; then the move is sent
 * once, as a placement ("after episode X"). The buffer holds ids only, so rows
 * keep rendering current data (download progress and so on) mid-drag.
 *
 * Refilling from the ViewModel is keyed on the id list and skipped mid-drag -
 * the same pattern the category screen uses with this library - so a
 * download-progress update can't yank the list out from under a drag.
 *
 * Dragging a selected episode carries the whole selection: the rest of it is
 * pulled out of the list when the drag starts, so the group visibly travels as
 * one row, and on drop every member lands at the drop point in its existing
 * relative order. Members in other seasons aren't on screen but move all the
 * same, joining this season. The library keeps the dragged row under the
 * finger when rows above it disappear (its offset is initial position plus
 * drag delta, minus current layout position). On drop the buffer is set to the
 * expected result straight away - the group reinserted at the drop point -
 * rather than waiting for the database: if the stored order ends up with the
 * same ids in the same sequence, no refill would ever arrive to bring the
 * pulled-out rows back.
 *
 * [mirrored] is the descending toggle. The list is then the stored order
 * reversed, so the episode a drop lands after in stored order is the nearest
 * one visually BELOW it rather than above; with nothing below, it goes to the
 * start of the season.
 *
 * A single-episode drop back where it started writes nothing, which keeps the
 * stored order sparse. A group drop always writes, since it gathers the group
 * even when the dragged row itself didn't move.
 */
@Composable
fun rememberEpisodeReorder(
    enabled: Boolean,
    gridState: LazyGridState,
    items: List<EpisodeList.Item>,
    selectedIds: Set<Long>,
    mirrored: Boolean,
    seasonOf: (episodeId: Long) -> Long,
    onMove: (episodeIds: List<Long>, targetSeason: Long, placement: MoveEpisodesInOrder.Placement) -> Unit,
): EpisodeReorderUi {
    val sourceIds = remember(items) { items.map { it.id } }
    val localIds = remember { mutableStateListOf<Long>() }
    var drag by remember { mutableStateOf<DragSession?>(null) }

    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentMirrored by rememberUpdatedState(mirrored)
    val currentSeasonOf by rememberUpdatedState(seasonOf)
    val currentOnMove by rememberUpdatedState(onMove)

    val state = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromIndex = from.key.episodeIdOrNull()?.let(localIds::indexOf) ?: -1
        val toIndex = to.key.episodeIdOrNull()?.let(localIds::indexOf) ?: -1
        if (fromIndex != -1 && toIndex != -1) {
            localIds.add(toIndex, localIds.removeAt(fromIndex))
        }
    }

    LaunchedEffect(sourceIds) {
        if (!state.isAnyItemDragging) {
            localIds.clear()
            localIds.addAll(sourceIds)
        }
    }

    if (!enabled) return EpisodeReorderUi(items, null)

    val byId = items.associateBy { it.id }
    return EpisodeReorderUi(
        items = localIds.mapNotNull { byId[it] },
        row = EpisodeReorderRow(
            state = state,
            draggingGroupSize = drag?.group?.size ?: 0,
            onDragStarted = { draggedId ->
                val selected = currentSelectedIds
                val group = if (draggedId in selected) selected else setOf(draggedId)
                drag = DragSession(startIds = localIds.toList(), group = group)
                localIds.removeAll { it != draggedId && it in group }
            },
            onDragStopped = stop@{ draggedId ->
                val session = drag ?: return@stop
                drag = null
                val endIds = localIds.toList()
                val isGroup = session.group.size > 1
                if (!isGroup && session.startIds == endIds) return@stop

                val pos = endIds.indexOf(draggedId)
                if (pos == -1) return@stop
                val anchor = if (currentMirrored) {
                    endIds.subList(pos + 1, endIds.size).firstOrNull { it !in session.group }
                } else {
                    endIds.subList(0, pos).lastOrNull { it !in session.group }
                }

                val visibleGroup = session.startIds.filter { it in session.group }
                localIds.clear()
                localIds.addAll(endIds.subList(0, pos) + visibleGroup + endIds.subList(pos + 1, endIds.size))

                currentOnMove(
                    session.group.toList(),
                    currentSeasonOf(draggedId),
                    anchor?.let { MoveEpisodesInOrder.Placement.After(it) }
                        ?: MoveEpisodesInOrder.Placement.AtStart,
                )
            },
        ),
    )
}

private fun Any.episodeIdOrNull(): Long? {
    return (this as? String)?.takeIf { it.startsWith(EPISODE_KEY_PREFIX) }
        ?.removePrefix(EPISODE_KEY_PREFIX)
        ?.toLongOrNull()
}

/** Must match the lazy-grid key sharedEpisodeItems gives episode rows. */
private const val EPISODE_KEY_PREFIX = "episode-"
// <-- AM (CUSTOM_EPISODE_ORDER)
