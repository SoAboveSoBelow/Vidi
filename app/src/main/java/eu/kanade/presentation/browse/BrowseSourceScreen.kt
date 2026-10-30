package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import aniyomi.domain.source.model.BrowseDisplayMode
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.browse.components.BrowseSourceScroller
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: AnimeSource?,
    animeList: LazyPagingItems<StateFlow<Anime>>,
    columns: GridCells,
    // AY -->
    entries: Int = 0,
    topBarHeight: Int = 0,
    // <-- AY
    // AM (BROWSE_SCROLLER)
    displayMode: BrowseDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    onAnimeClick: (Anime) -> Unit,
    onAnimeLongClick: (Anime) -> Unit,
    // AM (DOUBLE_TAP_QUEUE): null on the screens that pick a migration target,
    // where queueing a result means nothing - and where a non-null handler
    // would make every tap wait out the double-tap timeout for nothing.
    onAnimeDoubleClick: ((Anime) -> Unit)? = null,
    // AM (MERGED_SOURCES): ids of results that already belong to a merged
    // entry in the library. Defaulted so the screens that pick a migration
    // target, where merge membership says nothing useful, need not supply it.
    mergeChildIds: Set<Long> = emptySet(),
    // AM (BROWSE_SELECTION): defaulted for the same reason - a migration
    // target picker has nothing to select across.
    selection: Set<Long> = emptySet(),
    // AM (BROWSE_SCROLLER): the scroller needs to know whether a row tap
    // means "select" or nothing at all - unlike the grids, it does not treat a
    // row tap as "open". Defaulted for the screens that pick a migration
    // target, which have no selection mode.
    selectionMode: Boolean = false,
    // AM (BROWSE_SCROLLER): used only by the scroller, whose cards carry their
    // own add-to-library button.
    onAnimeFavoriteClick: ((Anime) -> Unit)? = null,
    // AM (BROWSE_SCROLLER): the settled item in the scroller layout, for
    // whoever wants to act on it. Ignored by every other layout.
    onActiveIndexChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current

    val errorState = animeList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: animeList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    LaunchedEffect(errorState) {
        if (animeList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> animeList.retry()
            }
        }
    }

    if (
        animeList.itemCount == 0 &&
        (
            animeList.loadState.refresh is LoadState.Loading ||
                animeList.loadState.append is LoadState.Loading ||
                animeList.loadState.prepend is LoadState.Loading
        )
    ) {
        // Paging can expose an empty item snapshot before a successful page
        // insertion. That is still loading, not an empty source.
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (animeList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource) {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = animeList::refresh,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.action_open_in_web_view,
                        icon = Icons.Outlined.Public,
                        onClick = onWebViewClick,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.label_help,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onHelpClick,
                    ),
                )
            },
        )

        return
    }

    // AM (BROWSE_SCROLLER) -->
    if (displayMode == BrowseDisplayMode.Scroller) {
        BrowseSourceScroller(
            animeList = animeList,
            source = source,
            // AM (MERGED_SOURCES)
            mergeChildIds = mergeChildIds,
            contentPadding = contentPadding,
            onAnimeClick = onAnimeClick,
            onAnimeLongClick = onAnimeLongClick,
            // AM (DOUBLE_TAP_QUEUE)
            onAnimeDoubleClick = onAnimeDoubleClick,
            // AM (BROWSE_SELECTION)
            selection = selection,
            selectionMode = selectionMode,
            onAnimeFavoriteClick = onAnimeFavoriteClick,
            onActiveIndexChange = onActiveIndexChange,
        )
        return
    }

    when (displayMode.standard) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                animeList = animeList,
                // AM (MERGED_SOURCES)
                mergeChildIds = mergeChildIds,
                columns = columns,
                contentPadding = contentPadding,
                onAnimeClick = onAnimeClick,
                onAnimeLongClick = onAnimeLongClick,
                // AM (DOUBLE_TAP_QUEUE)
                onAnimeDoubleClick = onAnimeDoubleClick,
                // AM (BROWSE_SELECTION)
                selection = selection,
            )
        }
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                animeList = animeList,
                // AM (MERGED_SOURCES)
                mergeChildIds = mergeChildIds,
                // AY -->
                entries = entries,
                topBarHeight = topBarHeight,
                // <-- AY
                contentPadding = contentPadding,
                onAnimeClick = onAnimeClick,
                onAnimeLongClick = onAnimeLongClick,
                // AM (DOUBLE_TAP_QUEUE)
                onAnimeDoubleClick = onAnimeDoubleClick,
                // AM (BROWSE_SELECTION)
                selection = selection,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                animeList = animeList,
                // AM (MERGED_SOURCES)
                mergeChildIds = mergeChildIds,
                columns = columns,
                contentPadding = contentPadding,
                onAnimeClick = onAnimeClick,
                onAnimeLongClick = onAnimeLongClick,
                // AM (DOUBLE_TAP_QUEUE)
                onAnimeDoubleClick = onAnimeDoubleClick,
                // AM (BROWSE_SELECTION)
                selection = selection,
            )
        }
    }
    // <-- AM (BROWSE_SCROLLER)
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
