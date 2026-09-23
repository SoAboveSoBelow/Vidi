// AM (NAMED_SEASONS) -->
package eu.kanade.presentation.anime.season

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import aniyomi.domain.merge.model.MERGE_DEFAULT_SEASON_NUMBER
import aniyomi.domain.season.model.EntrySeason
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.components.AppBar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/**
 * Category-style management of one entry's seasons: drag to reorder, rename,
 * delete, add. The default season is marked and has no delete - it is where
 * everything falls back to - but can be renamed and moved like any other.
 *
 * Reordering commits once per drop rather than on every swap, so a long drag
 * is one write.
 */
@Composable
fun EntrySeasonsScreen(
    seasons: List<EntrySeason>,
    onClickCreate: () -> Unit,
    onClickRename: (EntrySeason) -> Unit,
    onClickDelete: (EntrySeason) -> Unit,
    onReorder: (List<Long>) -> Unit,
    navigateUp: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(AMMR.strings.am_entry_seasons_title),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            CategoryFloatingActionButton(lazyListState = lazyListState, onCreate = onClickCreate)
        },
    ) { paddingValues ->
        val localNumbers = remember { mutableStateListOf<Long>() }
        val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
            localNumbers.add(to.index, localNumbers.removeAt(from.index))
        }
        val sourceNumbers = remember(seasons) { seasons.map { it.number } }
        LaunchedEffect(sourceNumbers) {
            if (!reorderableState.isAnyItemDragging) {
                localNumbers.clear()
                localNumbers.addAll(sourceNumbers)
            }
        }
        val byNumber = seasons.associateBy { it.number }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = paddingValues +
                topSmallPaddingValues +
                PaddingValues(horizontal = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            items(items = localNumbers.mapNotNull { byNumber[it] }, key = { "season-${it.number}" }) { season ->
                ReorderableItem(reorderableState, "season-${season.number}") {
                    ElevatedCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onClickRename(season) }
                                .padding(vertical = MaterialTheme.padding.small)
                                .padding(start = MaterialTheme.padding.small, end = MaterialTheme.padding.medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DragHandle,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(MaterialTheme.padding.medium)
                                    .draggableHandle(onDragStopped = { onReorder(localNumbers.toList()) }),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = seasonLabel(season.number, seasons))
                                if (season.number == MERGE_DEFAULT_SEASON_NUMBER) {
                                    Text(
                                        text = stringResource(AMMR.strings.am_entry_season_default),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { onClickRename(season) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = stringResource(AMMR.strings.am_entry_season_rename),
                                )
                            }
                            if (season.number != MERGE_DEFAULT_SEASON_NUMBER) {
                                IconButton(onClick = { onClickDelete(season) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(AMMR.strings.am_entry_season_delete),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Create and rename share this. A blank name is allowed and means unnamed
 * ("Season N" by position); a name another season already uses is not.
 */
@Composable
fun EntrySeasonNameDialog(
    title: String,
    initialName: String,
    takenNames: Set<String>,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val isTaken = name.trim().let { it.isNotEmpty() && it in takenNames }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = !isTaken,
                onClick = {
                    onDismissRequest()
                    onConfirm(name)
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(text = stringResource(AMMR.strings.am_entry_season_name_hint)) },
                isError = isTaken,
                supportingText = if (isTaken) {
                    { Text(text = stringResource(AMMR.strings.am_entry_season_name_exists)) }
                } else {
                    null
                },
            )
        },
    )
}

@Composable
fun EntrySeasonDeleteDialog(
    seasonLabel: String,
    defaultSeasonLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(AMMR.strings.am_entry_season_delete))
            }
        },
        title = { Text(text = stringResource(AMMR.strings.am_entry_season_delete)) },
        text = {
            Text(text = stringResource(AMMR.strings.am_entry_season_delete_confirm, seasonLabel, defaultSeasonLabel))
        },
    )
}
// <-- AM (NAMED_SEASONS)
