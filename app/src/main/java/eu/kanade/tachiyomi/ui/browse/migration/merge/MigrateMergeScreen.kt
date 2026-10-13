// AM (MERGED_SOURCES) -->
package eu.kanade.tachiyomi.ui.browse.migration.merge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.anime.components.BaseAnimeListItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.shouldExpandFAB

/**
 * Picks which of a merged entry's sources to migrate.
 *
 * Nothing migrates from here: the chosen children are handed to
 * [MigrationConfigScreen] exactly as the per-source migrate screen hands over
 * its selection, so the source picker, the search and the per-entry target
 * choice are the ones the user already knows.
 */
data class MigrateMergeScreen(
    private val mergeParentId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<MigrateMergeViewModel, MigrateMergeViewModel.Factory> {
            create(mergeParentId = mergeParentId)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        BackHandler(enabled = state.selectionMode) {
            viewModel.clearSelection()
        }

        val lazyListState = rememberLazyListState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = state.parentTitle.ifBlank { stringResource(AMMR.strings.am_migrate_merge_title) },
                    subtitle = stringResource(AMMR.strings.am_migrate_merge_title)
                        .takeIf { state.parentTitle.isNotBlank() },
                    navigateUp = navigator::pop,
                    actionModeCounter = state.selection.size,
                    onCancelActionMode = viewModel::clearSelection,
                    actionModeActions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_select_all),
                                    icon = Icons.Outlined.SelectAll,
                                    onClick = viewModel::selectAll,
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_select_inverse),
                                    icon = Icons.Outlined.FlipToBack,
                                    onClick = viewModel::invertSelection,
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                    icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                    onClick = {
                        val selection = state.selection
                        viewModel.clearSelection()
                        navigator.push(MigrationConfigScreen(selection))
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.selectionMode,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (state.isEmpty) {
                EmptyScreen(
                    message = stringResource(AMMR.strings.am_migrate_merge_empty),
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            MigrateMergeContent(
                lazyListState = lazyListState,
                contentPadding = contentPadding,
                state = state,
                onSourceFilterChange = viewModel::setSourceFilter,
                onClickItem = viewModel::toggleSelection,
                onClickCover = { navigator.push(AnimeScreen(it)) },
            )
        }
    }

    @Composable
    private fun MigrateMergeContent(
        lazyListState: LazyListState,
        contentPadding: PaddingValues,
        state: MigrateMergeViewModel.State,
        onSourceFilterChange: (Long?) -> Unit,
        onClickItem: (Long) -> Unit,
        onClickCover: (Long) -> Unit,
    ) {
        FastScrollLazyColumn(
            state = lazyListState,
            contentPadding = contentPadding,
        ) {
            // The controls ride in the list rather than in a fixed header, so a
            // merge with many sources scrolls as one thing - the same shape
            // merge settings uses for its own header row.
            item(key = "header") {
                MigrateMergeHeader(
                    state = state,
                    onSourceFilterChange = onSourceFilterChange,
                )
                HorizontalDivider()
            }

            items(state.shownItems, key = { it.anime.id }) { item ->
                BaseAnimeListItem(
                    modifier = Modifier.selectedBackground(item.anime.id in state.selection),
                    anime = item.anime,
                    onClickItem = { onClickItem(item.anime.id) },
                    onClickCover = { onClickCover(item.anime.id) },
                )
            }
        }
    }

    @Composable
    private fun MigrateMergeHeader(
        state: MigrateMergeViewModel.State,
        onSourceFilterChange: (Long?) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.extraSmall,
                ),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No "migrate all" button: selecting a source puts the bar into
            // action mode, where Select all and the FAB already do exactly
            // that, and a second route would only disagree with the filter.
            SourceFilterButton(state = state, onSourceFilterChange = onSourceFilterChange)
        }
    }

    @Composable
    private fun SourceFilterButton(
        state: MigrateMergeViewModel.State,
        onSourceFilterChange: (Long?) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        val filters = state.sourceFilters
        val label = filters.firstOrNull { it.first == state.sourceFilter }?.second
            ?: stringResource(AMMR.strings.am_migrate_merge_filter_all)

        Box {
            TextButton(onClick = { expanded = true }) {
                Text(text = label)
                Icon(imageVector = Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(AMMR.strings.am_migrate_merge_filter_all)) },
                    onClick = {
                        onSourceFilterChange(null)
                        expanded = false
                    },
                )
                filters.forEach { (sourceId, sourceName) ->
                    DropdownMenuItem(
                        text = { Text(sourceName) },
                        onClick = {
                            onSourceFilterChange(sourceId)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
// <-- AM (MERGED_SOURCES)
