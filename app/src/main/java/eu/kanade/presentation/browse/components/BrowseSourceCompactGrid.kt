package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.AnimeCompactGridItem
import eu.kanade.presentation.library.components.CommonAnimeItemDefaults
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceCompactGrid(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    // AM (MERGED_SOURCES)
    mergeChildIds: Set<Long>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onAnimeClick: (Anime) -> Unit,
    onAnimeLongClick: (Anime) -> Unit,
    // AM (BROWSE_SELECTION)
    selection: Set<Long> = emptySet(),
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonAnimeItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonAnimeItemDefaults.GridHorizontalSpacer),
    ) {
        if (animeList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = animeList.itemCount) { index ->
            val anime by animeList[index]?.collectAsState() ?: return@items
            BrowseSourceCompactGridItem(
                anime = anime,
                // AM (MERGED_SOURCES)
                inMerge = anime.id in mergeChildIds,
                onClick = { onAnimeClick(anime) },
                onLongClick = { onAnimeLongClick(anime) },
                // AM (BROWSE_SELECTION)
                isSelected = anime.id in selection,
            )
        }

        if (animeList.loadState.refresh is LoadState.Loading || animeList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceCompactGridItem(
    anime: Anime,
    // AM (MERGED_SOURCES)
    inMerge: Boolean,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    // AM (BROWSE_SELECTION)
    isSelected: Boolean = false,
) {
    AnimeCompactGridItem(
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
        coverBadgeStart = {
            InLibraryBadge(enabled = anime.favorite)
            // AM (MERGED_SOURCES)
            InMergeBadge(enabled = !anime.favorite && inMerge)
        },
        onLongClick = onLongClick,
        onClick = onClick,
        // AM (BROWSE_SELECTION)
        isSelected = isSelected,
    )
}
