package eu.kanade.presentation.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
// AM (A_A_LIST_SWAPPED_OUT_FORGETS_WHERE_IT_WAS) -->
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
// <-- AM (A_A_LIST_SWAPPED_OUT_FORGETS_WHERE_IT_WAS)
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active

@Composable
fun HistoryScreen(
    state: HistoryViewModel.State,
    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME)
    history: LazyPagingItems<HistoryUiModel>,
    onSearchQueryChange: (String?) -> Unit,
    onClickCover: (animeId: Long) -> Unit,
    onClickResume: (animeId: Long, episodeId: Long) -> Unit,
    onClickFavorite: (animeId: Long) -> Unit,
    onDialogChange: (HistoryViewModel.Dialog?) -> Unit,
    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME)
    onLoadError: (Throwable) -> Unit,
    contentPadding: PaddingValues,
) {
    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME) -->
    // The three states the list had, read from the pager instead of from a
    // nullable list: nothing loaded yet and still loading is the spinner,
    // nothing loaded and not loading is empty, anything loaded is content.
    // Ordered that way deliberately - itemCount is 0 during the first load too,
    // so testing emptiness first would flash the empty screen on every open.
    val refresh = history.loadState.refresh

    // The Flow's catch used to raise this; a Pager's flow does not throw.
    LaunchedEffect(refresh) {
        if (refresh is LoadState.Error) onLoadError(refresh.error)
    }
    // <-- AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME)

    // AM (A_A_LIST_SWAPPED_OUT_FORGETS_WHERE_IT_WAS) -->
    // Held here rather than defaulted inside the list, because the list is not
    // always in the composition: the three-way branch below replaces it with
    // the spinner whenever itemCount is 0, and a LazyListState remembered
    // inside it dies with it. Coming back, the list was a new one scrolled to
    // the top - which is a scroll position lost for a frame of loading, with
    // no scroll having happened.
    //
    // itemCount reaches 0 whenever the pager is REPLACED rather than
    // invalidated - every filter change builds a new Pager through
    // flatMapLatest - and the window where a refresh has dropped the old pages
    // and not yet delivered new ones is the same shape. Up here the position
    // survives both, and Compose restores it against the item keys.
    val listState = rememberLazyListState()
    // <-- AM (A_A_LIST_SWAPPED_OUT_FORGETS_WHERE_IT_WAS)

    // AM (RECENTS_FILTER_CHIP) -->
    if (history.itemCount == 0 && refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
    } else if (history.itemCount == 0) {
        val msg = if (!state.searchQuery.isNullOrEmpty()) {
            MR.strings.no_results_found
        } else {
            AYMR.strings.information_no_recent_anime
        }
        EmptyScreen(
            stringRes = msg,
            modifier = Modifier.padding(contentPadding),
        )
    } else {
        HistoryScreenContent(
            history = history,
            // AM (A_A_LIST_SWAPPED_OUT_FORGETS_WHERE_IT_WAS)
            listState = listState,
            contentPadding = contentPadding,
            onClickCover = { history -> onClickCover(history.animeId) },
            onClickResume = { history -> onClickResume(history.animeId, history.episodeId) },
            onClickDelete = { item -> onDialogChange(HistoryViewModel.Dialog.Delete(item)) },
            onClickFavorite = { history -> onClickFavorite(history.animeId) },
        )
    }
}

@Composable
fun HistoryTopBar(
    state: HistoryViewModel.State,
    onSearchQueryChange: (String?) -> Unit,
    onDialogChange: (HistoryViewModel.Dialog?) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    SearchToolbar(
        titleContent = { AppBarTitle(stringResource(MR.strings.history)) },
        searchQuery = state.searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        actions = {
            AppBarActions(
                listOf(
                    // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS) -->
                    // The same icon, slot and active tint the Updates bar gives
                    // its filter, opening the same kind of sheet - this list is
                    // the other half of Recents, and the two should not need to
                    // be used differently.
                    AppBar.Action(
                        title = stringResource(MR.strings.action_filter),
                        icon = Icons.Outlined.FilterList,
                        iconTint = if (state.hasFilters) {
                            MaterialTheme.colorScheme.active
                        } else {
                            LocalContentColor.current
                        },
                        onClick = { onDialogChange(HistoryViewModel.Dialog.Filter) },
                    ),
                    // <-- AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
                    AppBar.Action(
                        title = stringResource(MR.strings.pref_clear_history),
                        icon = Icons.Outlined.DeleteSweep,
                        onClick = {
                            onDialogChange(HistoryViewModel.Dialog.DeleteAll)
                        },
                    ),
                ),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun HistoryScreenContent(
    history: LazyPagingItems<HistoryUiModel>,
    // AM (A_A_LIST_SWAPPED_OUT_FORGETS_WHERE_IT_WAS)
    listState: LazyListState,
    contentPadding: PaddingValues,
    onClickCover: (HistoryWithRelations) -> Unit,
    onClickResume: (HistoryWithRelations) -> Unit,
    onClickDelete: (HistoryWithRelations) -> Unit,
    onClickFavorite: (HistoryWithRelations) -> Unit,
) {
    FastScrollLazyColumn(
        // AM (A_A_LIST_SWAPPED_OUT_FORGETS_WHERE_IT_WAS)
        state = listState,
        contentPadding = contentPadding,
    ) {
        // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): by count, with itemKey and
        // itemContentType deriving both from the loaded item - so the keys below
        // are the same keys, computed from the same values. Placeholders are off
        // (see HistoryViewModel.historyItems), which is what makes that possible:
        // with them on an unloaded row has no item to key by.
        items(
            count = history.itemCount,
            // AM (HISTORY_STABLE_ITEM_KEY) -->
            // Was "history-${it.hashCode()}": the data class hash covers seenAt,
            // episodeId and episodeNumber, so every playback progress write gave
            // the currently-watched entry a NEW key - the LazyColumn saw it as a
            // removal plus an insertion and animateItem() faded it out and back in
            // on every save. Keyed by identity instead: the history query returns
            // exactly one row per anime (maxSeenAtEpisodeId), and separators are
            // only inserted between differing dates in a seenAt-sorted list, so
            // both are unique. An entry now updates in place, and only an actual
            // reorder animates (as a placement slide).
            key = history.itemKey {
                when (it) {
                    is HistoryUiModel.Header -> "history-header-${it.date}"
                    is HistoryUiModel.Item -> "history-item-${it.item.animeId}"
                }
            },
            // <-- AM (HISTORY_STABLE_ITEM_KEY)
            contentType = history.itemContentType {
                when (it) {
                    is HistoryUiModel.Header -> "header"
                    is HistoryUiModel.Item -> "item"
                }
            },
        ) { index ->
            when (val item = history[index]) {
                is HistoryUiModel.Header -> {
                    ListGroupHeader(
                        modifier = Modifier.animateItem(),
                        text = relativeDateText(item.date),
                    )
                }
                is HistoryUiModel.Item -> {
                    val value = item.item
                    HistoryItem(
                        modifier = Modifier.animateItem(),
                        history = value,
                        onClickCover = { onClickCover(value) },
                        onClickResume = { onClickResume(value) },
                        onClickDelete = { onClickDelete(value) },
                        onClickFavorite = { onClickFavorite(value) },
                    )
                }
                // Unreachable with placeholders off, and nothing to draw if it
                // ever is - a spacer here would be a row that is not there.
                null -> Unit
            }
        }
    }
}

sealed interface HistoryUiModel {
    data class Header(val date: LocalDate) : HistoryUiModel
    data class Item(val item: HistoryWithRelations) : HistoryUiModel
}

@PreviewLightDark
@Composable
internal fun HistoryScreenPreviews(
    @PreviewParameter(HistoryViewModelStateProvider::class)
    preview: HistoryPreview,
) {
    TachiyomiPreviewTheme {
        HistoryScreen(
            state = preview.state,
            // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): the provider carries a
            // PagingData now rather than a list on the state, including the
            // loading case - PagingData.empty takes the load states, so a
            // refresh that has not finished is expressible here.
            history = flowOf(preview.items).collectAsLazyPagingItems(),
            onSearchQueryChange = {},
            onClickCover = {},
            onClickResume = { _, _ -> run {} },
            onDialogChange = {},
            onClickFavorite = {},
            onLoadError = {},
            contentPadding = PaddingValues(),
        )
    }
}

/** AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): one preview case. */
internal data class HistoryPreview(
    val state: HistoryViewModel.State,
    val items: PagingData<HistoryUiModel>,
)
