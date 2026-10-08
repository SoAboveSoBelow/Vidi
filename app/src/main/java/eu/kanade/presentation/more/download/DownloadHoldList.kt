// AM (PLANNED_DOWNLOADS) -->
package eu.kanade.presentation.more.download

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import animiru.domain.download.model.PlannedDownloadEntry
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.anime.components.AnimeEpisodeListItem
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.download.HeldDownloadGroup
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The on-hold list: episodes wanted but not queued.
 *
 * Deliberately the same rows as [DownloadQueueList], grouped by source the same way,
 * with the same long-press menu - only the actions differ, and there is no drag
 * handle because a hold list is not being worked through in order. Every row shows
 * as not-downloaded, which is what being here means.
 */
@Composable
fun DownloadHoldList(
    groups: List<HeldDownloadGroup>,
    contentPadding: PaddingValues,
    // AM (EPISODE_NAMES): as in the queue - resolved titles, episode name on a miss.
    displayNames: Map<Long, String>,
    onDownload: (PlannedDownloadEntry) -> Unit,
    onDownloadSeries: (PlannedDownloadEntry) -> Unit,
    onDismiss: (PlannedDownloadEntry) -> Unit,
    onDismissSeries: (PlannedDownloadEntry) -> Unit,
    onOpenEntry: (PlannedDownloadEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        groups.forEach { group ->
            item(key = "hold-source-${group.sourceId}") {
                Text(
                    text = group.sourceName,
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
            }

            items(items = group.entries, key = { "hold-episode-${it.episodeId}" }) { entry ->
                HoldRow(
                    entry = entry,
                    title = displayNames[entry.episodeId] ?: entry.episodeName,
                    onDownload = { onDownload(entry) },
                    onDownloadSeries = { onDownloadSeries(entry) },
                    onDismiss = { onDismiss(entry) },
                    onDismissSeries = { onDismissSeries(entry) },
                    onOpenEntry = { onOpenEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun HoldRow(
    entry: PlannedDownloadEntry,
    title: String,
    onDownload: () -> Unit,
    onDownloadSeries: () -> Unit,
    onDismiss: () -> Unit,
    onDismissSeries: () -> Unit,
    onOpenEntry: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        AnimeEpisodeListItem(
            title = title,
            date = null,
            watchProgress = null,
            // Suppressed by minimalView; passed for when it is not.
            scanlator = entry.episodeScanlator,
            summary = null,
            previewUrl = entry.displayImageUrl,
            // Seen state and marks are entry-page concerns.
            seen = false,
            bookmark = false,
            fillermark = false,
            selected = false,
            isAnyEpisodeSelected = false,
            downloadIndicatorEnabled = true,
            // Nothing here is downloaded or downloading - the reconciler drops rows
            // that are already on disk, and queueing moves them to the other tab.
            downloadStateProvider = { Download.State.NOT_DOWNLOADED },
            downloadProgressProvider = { 0 },
            episodeSwipeStartAction = LibraryPreferences.EpisodeSwipeAction.Disabled,
            episodeSwipeEndAction = LibraryPreferences.EpisodeSwipeAction.Disabled,
            onLongClick = { menuExpanded = true },
            onClick = onOpenEntry,
            // The indicator's own tap is the obvious way to fetch one episode.
            onDownloadClick = { onDownload() },
            onEpisodeSwipe = {},
            fileSize = null,
            minimalView = true,
        )

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            HoldMenuItem(MR.strings.action_download, { menuExpanded = false }, onDownload)
            HoldMenuItem(AMMR.strings.action_download_all_for_series, { menuExpanded = false }, onDownloadSeries)
            HoldMenuItem(AMMR.strings.action_remove_from_hold, { menuExpanded = false }, onDismiss)
            HoldMenuItem(
                AMMR.strings.action_remove_all_for_series_from_hold,
                { menuExpanded = false },
                onDismissSeries,
            )
        }
    }
}

@Composable
private fun HoldMenuItem(
    label: StringResource,
    onDismissMenu: () -> Unit,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismissMenu()
            onClick()
        },
    )
}
// <-- AM (PLANNED_DOWNLOADS)
