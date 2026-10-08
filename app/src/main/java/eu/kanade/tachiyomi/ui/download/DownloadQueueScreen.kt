package eu.kanade.tachiyomi.ui.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.more.download.DownloadHoldList
import eu.kanade.presentation.more.download.DownloadQueueList
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

object DownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<DownloadQueueViewModel>()
        val downloadList by viewModel.state.collectAsStateWithLifecycle()
        val downloadCount by remember {
            derivedStateOf { downloadList.sumOf { it.downloads.size } }
        }

        // AM (PLANNED_DOWNLOADS) -->
        val holdViewModel = metroViewModel<DownloadHoldViewModel>()
        val holdList by holdViewModel.state.collectAsState()
        val holdCount by remember {
            derivedStateOf { holdList.sumOf { it.entries.size } }
        }
        val pagerState = rememberPagerState { 2 }
        val onQueueTab by remember { derivedStateOf { pagerState.currentPage == QUEUE_PAGE } }
        val tabScope = rememberCoroutineScope()
        // <-- AM (PLANNED_DOWNLOADS)

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            // All this lines just for fab state :/
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    // AM (PLANNED_DOWNLOADS): one title for both tabs; the counts
                    // live on the tabs themselves, where they say which list they
                    // belong to.
                    titleContent = {
                        Text(
                            text = stringResource(AMMR.strings.label_downloads),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        // AM (PLANNED_DOWNLOADS): sorting is a queue concept - the
                        // hold list has no order being worked through.
                        if (onQueueTab && downloadList.isNotEmpty()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = onDismissRequest,
                            ) {
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_newest)) },
                                            onClick = {
                                                viewModel.reorderQueue(
                                                    { it.episode.dateUpload },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                            onClick = {
                                                viewModel.reorderQueue(
                                                    { it.episode.dateUpload },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                                NestedMenuItem(
                                    text = { Text(text = stringResource(AYMR.strings.action_order_by_episode_number)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                                            onClick = {
                                                viewModel.reorderQueue(
                                                    { it.episode.episodeNumber },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                                            onClick = {
                                                viewModel.reorderQueue(
                                                    { it.episode.episodeNumber },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                            }

                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = Icons.AutoMirrored.Outlined.Sort,
                                        onClick = { sortExpanded = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = { viewModel.clearQueue() },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = onQueueTab && downloadList.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    val isRunning by viewModel.isDownloaderRunning.collectAsStateWithLifecycle()
                    SmallExtendedFloatingActionButton(
                        text = {
                            // AY -->
                            val id = if (isRunning) {
                                AYMR.strings.action_stop
                            } else {
                                AYMR.strings.action_continue
                            }
                            // <-- AY
                            Text(text = stringResource(id))
                        },
                        icon = {
                            val icon = if (isRunning) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            }
                            Icon(imageVector = icon, contentDescription = null)
                        },
                        onClick = {
                            if (isRunning) {
                                viewModel.pauseDownloads()
                            } else {
                                viewModel.startDownloads()
                            }
                        },
                        expanded = fabExpanded,
                    )
                }
            },
        ) { contentPadding ->
            val downloadStates by viewModel.downloadStates.collectAsState()
            // AM (EPISODE_NAMES) -->
            val queueDisplayNames by viewModel.displayNames.collectAsState()
            val holdDisplayNames by holdViewModel.displayNames.collectAsState()
            // <-- AM (EPISODE_NAMES)

            // AM (PLANNED_DOWNLOADS) -->
            // PrimaryTabRow and HorizontalPager directly, rather than the shared
            // TabbedScreen: that component owns its own toolbar and has no slot for
            // this screen's FAB or its sort menu, so using it would have meant
            // extending a component the app's root tabs depend on in order to host
            // one pushed screen.
            Column(
                modifier = Modifier.padding(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                ),
            ) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    modifier = Modifier.zIndex(1f),
                ) {
                    Tab(
                        selected = pagerState.currentPage == QUEUE_PAGE,
                        onClick = { tabScope.launch { pagerState.animateScrollToPage(QUEUE_PAGE) } },
                        text = {
                            TabText(
                                text = stringResource(AMMR.strings.tab_download_queue),
                                badgeCount = downloadCount.takeIf { it > 0 },
                            )
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    Tab(
                        selected = pagerState.currentPage == HOLD_PAGE,
                        onClick = { tabScope.launch { pagerState.animateScrollToPage(HOLD_PAGE) } },
                        text = {
                            TabText(
                                text = stringResource(AMMR.strings.tab_download_hold),
                                badgeCount = holdCount.takeIf { it > 0 },
                            )
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }

                val pagePadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())

                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    when (page) {
                        QUEUE_PAGE -> if (downloadList.isEmpty()) {
                            EmptyScreen(
                                stringRes = MR.strings.information_no_downloads,
                                modifier = Modifier.padding(pagePadding),
                            )
                        } else {
                            DownloadQueueList(
                                groups = downloadList,
                                downloadStates = downloadStates,
                                displayNames = queueDisplayNames,
                                contentPadding = pagePadding,
                                onReorder = viewModel::reorderTo,
                                onMoveToTop = { viewModel.moveToTop(it.episodeId) },
                                onMoveToBottom = { viewModel.moveToBottom(it.episodeId) },
                                onMoveSeriesToTop = { viewModel.moveSeriesToTop(it.animeId) },
                                onMoveSeriesToBottom = { viewModel.moveSeriesToBottom(it.animeId) },
                                onCancel = { viewModel.cancel(it.episodeId) },
                                onCancelSeries = { viewModel.cancelSeries(it.animeId) },
                                onOpenEntry = { navigator.push(AnimeScreen(it.animeId)) },
                                modifier = Modifier.nestedScroll(nestedScrollConnection),
                            )
                        }

                        else -> if (holdList.isEmpty()) {
                            EmptyScreen(
                                stringRes = AMMR.strings.information_no_held_downloads,
                                modifier = Modifier.padding(pagePadding),
                            )
                        } else {
                            DownloadHoldList(
                                groups = holdList,
                                contentPadding = pagePadding,
                                displayNames = holdDisplayNames,
                                onDownload = holdViewModel::download,
                                onDownloadSeries = { holdViewModel.downloadSeries(it.animeId) },
                                onDismiss = holdViewModel::dismiss,
                                onDismissSeries = { holdViewModel.dismissSeries(it.animeId) },
                                onOpenEntry = { navigator.push(AnimeScreen(it.animeId)) },
                                modifier = Modifier.nestedScroll(nestedScrollConnection),
                            )
                        }
                    }
                }
            }
            // <-- AM (PLANNED_DOWNLOADS)
        }
    }
}

// AM (PLANNED_DOWNLOADS) -->
private const val QUEUE_PAGE = 0
private const val HOLD_PAGE = 1
// <-- AM (PLANNED_DOWNLOADS)
