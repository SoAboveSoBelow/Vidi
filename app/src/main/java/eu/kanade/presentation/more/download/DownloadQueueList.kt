// AM (COMPOSE_DOWNLOAD_QUEUE) -->
package eu.kanade.presentation.more.download

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.anime.components.AnimeEpisodeListItem
import eu.kanade.presentation.anime.components.EpisodeDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.download.DownloadSourceGroup
import eu.kanade.tachiyomi.ui.download.QueuedDownloadRow
import eu.kanade.tachiyomi.ui.download.QueuedDownloadState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The download queue, drawn with the same episode rows as the rest of the app.
 *
 * Replaces a FlexibleAdapter whose rows could only ever look like themselves. Rows
 * are [AnimeEpisodeListItem], so thumbnails, spacing and the download indicator -
 * progress included - behave exactly as on an entry's page, and the hold list can be
 * built from this same component rather than a near-copy of it.
 *
 * Drag lives on the leading handle - where download_item.xml had it - and is confined
 * to one source's block, which is the rule the old adapter enforced in `shouldMove`.
 * Long-pressing a row opens the actions that have nowhere else to go; cancel and
 * start-now are already in the indicator's own menu.
 */
@Composable
fun DownloadQueueList(
    groups: List<DownloadSourceGroup>,
    downloadStates: Map<Long, QueuedDownloadState>,
    // AM (EPISODE_NAMES): resolved titles by episode id; a miss keeps the episode's own.
    displayNames: Map<Long, String>,
    contentPadding: PaddingValues,
    onReorder: (List<Long>) -> Unit,
    onMoveToTop: (QueuedDownloadRow) -> Unit,
    onMoveToBottom: (QueuedDownloadRow) -> Unit,
    onMoveSeriesToTop: (QueuedDownloadRow) -> Unit,
    onMoveSeriesToBottom: (QueuedDownloadRow) -> Unit,
    onCancel: (QueuedDownloadRow) -> Unit,
    onCancelSeries: (QueuedDownloadRow) -> Unit,
    onOpenEntry: (QueuedDownloadRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()

    // A flat local copy, so the library's from/to indices line up with what is drawn
    // and a drag reads smoothly instead of waiting for the queue to echo back.
    val localItems = remember { mutableStateListOf<QueueListItem>() }
    val reorderableState = rememberReorderableLazyListState(lazyListState, contentPadding) { from, to ->
        val fromItem = localItems.getOrNull(from.index)
        val toItem = localItems.getOrNull(to.index)
        // Headers do not move, and a row never leaves its own source's block.
        if (fromItem is QueueListItem.Episode &&
            toItem is QueueListItem.Episode &&
            fromItem.sourceId == toItem.sourceId
        ) {
            localItems.add(to.index, localItems.removeAt(from.index))
        }
    }

    val sourceItems = remember(groups) { groups.toListItems() }
    LaunchedEffect(sourceItems) {
        if (!reorderableState.isAnyItemDragging) {
            localItems.clear()
            localItems.addAll(sourceItems)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = contentPadding,
    ) {
        items(count = localItems.size, key = { index -> localItems[index].key }) { index ->
            when (val item = localItems.getOrNull(index)) {
                null -> Unit

                is QueueListItem.Header -> Text(
                    text = item.sourceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.small,
                        ),
                )

                is QueueListItem.Episode -> ReorderableItem(reorderableState, item.key) {
                    QueueRow(
                        row = item.row,
                        title = displayNames[item.row.episodeId] ?: item.row.episodeName,
                        downloadState = downloadStates[item.row.episodeId],
                        dragHandle = {
                            Icon(
                                imageVector = Icons.Outlined.DragHandle,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(MaterialTheme.padding.medium)
                                    .draggableHandle(
                                        onDragStopped = {
                                            onReorder(
                                                localItems
                                                    .filterIsInstance<QueueListItem.Episode>()
                                                    .map { it.row.episodeId },
                                            )
                                        },
                                    ),
                            )
                        },
                        onMoveToTop = { onMoveToTop(item.row) },
                        onMoveToBottom = { onMoveToBottom(item.row) },
                        onMoveSeriesToTop = { onMoveSeriesToTop(item.row) },
                        onMoveSeriesToBottom = { onMoveSeriesToBottom(item.row) },
                        onCancel = { onCancel(item.row) },
                        onCancelSeries = { onCancelSeries(item.row) },
                        onOpenEntry = { onOpenEntry(item.row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    row: QueuedDownloadRow,
    title: String,
    downloadState: QueuedDownloadState?,
    dragHandle: @Composable () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    onMoveSeriesToTop: () -> Unit,
    onMoveSeriesToBottom: () -> Unit,
    onCancel: () -> Unit,
    onCancelSeries: () -> Unit,
    onOpenEntry: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading, as it was in download_item.xml and as CategoryListItem places
            // it. The trailing edge belongs to the download indicator.
            dragHandle()

            AnimeEpisodeListItem(
                title = title,
                date = null,
                watchProgress = null,
                // Suppressed by minimalView; passed for when it is not.
                scanlator = row.episodeScanlator,
                summary = null,
                previewUrl = row.previewUrl,
                // Seen state and marks are entry-page concerns; a queue row is only
                // about fetching the file.
                seen = false,
                bookmark = false,
                fillermark = false,
                selected = false,
                isAnyEpisodeSelected = false,
                downloadIndicatorEnabled = true,
                // Queued until the queue says otherwise - everything in this list is
                // in it by definition.
                downloadStateProvider = { downloadState?.status ?: Download.State.QUEUE },
                downloadProgressProvider = { downloadState?.progress ?: 0 },
                // Swipe actions are seen/bookmark ones with no meaning here, and they
                // would fight the drag.
                episodeSwipeStartAction = LibraryPreferences.EpisodeSwipeAction.Disabled,
                episodeSwipeEndAction = LibraryPreferences.EpisodeSwipeAction.Disabled,
                onLongClick = { menuExpanded = true },
                onClick = onOpenEntry,
                onDownloadClick = { action ->
                    if (action == EpisodeDownloadAction.CANCEL) onCancel()
                },
                onEpisodeSwipe = {},
                fileSize = null,
                minimalView = true,
                modifier = Modifier.weight(1f),
            )
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            QueueMenuItem(MR.strings.action_move_to_top, { menuExpanded = false }, onMoveToTop)
            QueueMenuItem(MR.strings.action_move_to_top_all_for_series, { menuExpanded = false }, onMoveSeriesToTop)
            QueueMenuItem(MR.strings.action_move_to_bottom, { menuExpanded = false }, onMoveToBottom)
            QueueMenuItem(
                MR.strings.action_move_to_bottom_all_for_series,
                { menuExpanded = false },
                onMoveSeriesToBottom,
            )
            QueueMenuItem(MR.strings.action_cancel, { menuExpanded = false }, onCancel)
            QueueMenuItem(MR.strings.cancel_all_for_series, { menuExpanded = false }, onCancelSeries)
        }
    }
}

@Composable
private fun QueueMenuItem(
    label: StringResource,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismiss()
            onClick()
        },
    )
}

/** Flattened queue: a header per source, then that source's rows. */
private sealed interface QueueListItem {
    val key: String

    data class Header(val sourceId: Long, val sourceName: String) : QueueListItem {
        override val key: String get() = "source-$sourceId"
    }

    data class Episode(val sourceId: Long, val row: QueuedDownloadRow) : QueueListItem {
        override val key: String get() = "episode-${row.episodeId}"
    }
}

private fun List<DownloadSourceGroup>.toListItems(): List<QueueListItem> {
    return flatMap { group ->
        buildList {
            add(QueueListItem.Header(group.sourceId, group.sourceName))
            group.downloads.forEach { add(QueueListItem.Episode(group.sourceId, it)) }
        }
    }
}
// <-- AM (COMPOSE_DOWNLOAD_QUEUE)
