package aniyomi.ui.browse.scroller

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

// AM (ENTRY_SCROLLER) -->
/**
 * Scrolls [index] to the middle of the scrollable area.
 *
 * `scrollToItem` puts an item's top edge at the top of that area, which for a
 * feed is the wrong place: a card is the thing being watched, not the next
 * thing in a list, and the one being watched belongs in the middle.
 *
 * The middle of the SCROLLABLE AREA rather than of the screen - viewport
 * start and end offsets already account for the list's content padding, so
 * whatever the screen puts above the feed (a top bar, an entry's cover and
 * description) is excluded instead of pushing the card low.
 *
 * Two steps, because a feed's cards are not all the same height and an item's
 * height is only known once it is laid out: scroll it to the top, then read
 * its size and nudge it down by half the space left over. The second step is
 * a scroll of a few hundred px with the item already composed, so there is
 * nothing to load and nothing to see between them.
 */
suspend fun LazyListState.centreItem(index: Int) {
    scrollToItem(index)
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val leftOver = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) - info.size
    if (leftOver > 0) scrollBy(-(leftOver / 2).toFloat())
}

/** The same, for a feed drawn into a grid - see [LazyListState.centreItem]. */
suspend fun LazyGridState.centreItem(index: Int) {
    scrollToItem(index)
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val leftOver = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) - info.size.height
    if (leftOver > 0) scrollBy(-(leftOver / 2).toFloat())
}
// <-- AM (ENTRY_SCROLLER)
