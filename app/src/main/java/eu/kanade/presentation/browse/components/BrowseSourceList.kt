package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.AnimeListItem
import eu.kanade.presentation.library.components.CommonAnimeItemDefaults
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    // AM (MERGED_SOURCES)
    mergeChildIds: Set<Long>,
    // AY -->
    entries: Int,
    topBarHeight: Int,
    // <-- AY
    contentPadding: PaddingValues,
    onAnimeClick: (Anime) -> Unit,
    onAnimeLongClick: (Anime) -> Unit,
    // AM (BROWSE_SELECTION)
    selection: Set<Long> = emptySet(),
) {
    // AY -->
    val sourceListState = rememberLazyListState()
    BoxWithConstraints {
        val density = LocalDensity.current
        val containerHeightPx = with(density) { this@BoxWithConstraints.maxHeight.roundToPx() }
        // <-- AY

        LazyColumn(
            state = sourceListState,
            contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
        ) {
            item {
                if (animeList.loadState.prepend is LoadState.Loading) {
                    BrowseSourceLoadingItem()
                }
            }

            items(count = animeList.itemCount) { index ->
                val anime by animeList[index]?.collectAsState() ?: return@items
                BrowseSourceListItem(
                    anime = anime,
                    // AM (MERGED_SOURCES)
                    inMerge = anime.id in mergeChildIds,
                    onClick = { onAnimeClick(anime) },
                    onLongClick = { onAnimeLongClick(anime) },
                    // AM (BROWSE_SELECTION)
                    isSelected = anime.id in selection,
                    // AY -->
                    entries = entries,
                    containerHeight = containerHeightPx - topBarHeight,
                    // <-- AY
                )
            }

            item {
                if (animeList.loadState.refresh is LoadState.Loading ||
                    animeList.loadState.append is LoadState.Loading
                ) {
                    BrowseSourceLoadingItem()
                }
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    anime: Anime,
    // AM (MERGED_SOURCES)
    inMerge: Boolean,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    // AM (BROWSE_SELECTION)
    isSelected: Boolean = false,
    // AY -->
    entries: Int,
    containerHeight: Int,
    // <-- AY
) {
    AnimeListItem(
        title = anime.title,
        coverData = AnimeCover(
            animeId = anime.id,
            sourceId = anime.source,
            isAnimeFavorite = anime.favorite,
            url = anime.thumbnailUrl,
            lastModified = anime.coverLastModified,
        ),
        // AM (MERGED_SOURCES): dimmed for a merge child too - the cover is
        // how the eye reads "already have this" while scrolling, and the
        // badge is what says in which sense.
        coverAlpha = if (anime.favorite || inMerge) {
            CommonAnimeItemDefaults.BrowseFavoriteCoverAlpha
        } else {
            1f
        },
        badge = {
            InLibraryBadge(enabled = anime.favorite)
            // AM (MERGED_SOURCES)
            InMergeBadge(enabled = !anime.favorite && inMerge)
        },
        onLongClick = onLongClick,
        onClick = onClick,
        // AM (BROWSE_SELECTION)
        isSelected = isSelected,
        // AY -->
        entries = entries,
        containerHeight = containerHeight,
        // <-- AY
    )
}
