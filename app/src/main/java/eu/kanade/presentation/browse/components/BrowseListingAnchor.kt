package eu.kanade.presentation.browse.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.anime.model.Anime

// AM (A_THE_FEED_KEEPS_ITS_PLACE) -->
/**
 * Keeping a browse listing on the entry the user was looking at when the
 * entries beneath them change.
 *
 * The hide-viewed toggle is what this is for. The trim runs downstream of the
 * pager's cache, so nothing is refetched and no scroll position is touched -
 * but a scroll position is an INDEX, and dropping entries from above the user
 * moves every index below them. Ten pages in, that reads as being thrown back
 * towards the start; unhiding throws you forward instead, as far as the
 * restored entries reach.
 *
 * Anchored on the entry rather than on an offset because the entry is what the
 * user is looking at, and it is the only thing the two listings have in common
 * - an index means something different either side of the change, and a pixel
 * offset means nothing at all once item heights differ.
 *
 * Split into recording and restoring because the four layouts do not agree on
 * what the anchor is. The scroller already knows which card is being watched
 * and supplies that; a grid or a list has no such notion and uses whatever is
 * at the top of the viewport. Both then restore the same way.
 */
@Composable
internal fun rememberAnchorTrail(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    firstVisibleItemIndex: () -> Int,
    leadingItemCount: () -> Int,
): List<Long> {
    var trail by remember(animeList) { mutableStateOf<List<Long>>(emptyList()) }

    LaunchedEffect(animeList) {
        snapshotFlow { firstVisibleItemIndex() }
            .distinctUntilChanged()
            // Settled, so a fling records where it stopped rather than every
            // row it went past. collectLatest cancels the wait when the index
            // moves on, which is what makes the delay a settle and not a lag.
            .collectLatest { lazyIndex ->
                delay(ANCHOR_SETTLE_MS)
                trail = trailFrom(animeList, lazyIndex - leadingItemCount())
            }
    }

    return trail
}

/**
 * Puts the listing back on [anchorTrail]'s first surviving entry whenever the
 * item count changes.
 *
 * Appends cost nothing: a page arriving below the anchor leaves its index
 * alone, so the anchor is found where it already is and nothing scrolls.
 *
 * @param anchorTrail the anchor entry followed by the entries just after it.
 * The entries after it are carried because the anchor itself may be what was
 * removed - hiding viewed entries while sitting on one takes it with it - and
 * the first survivor is then the next thing the user had not reached, which is
 * where hide-viewed is trying to get them anyway.
 * @param leadingItemCount lazy items drawn before the paged ones, which the
 * layouts do not agree on either: a loading row above the first entry shifts
 * every lazy index by one without changing any paging index.
 */
@Composable
internal fun RestoreListingAnchor(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    anchorTrail: List<Long>,
    firstVisibleItemIndex: () -> Int,
    leadingItemCount: () -> Int,
    scrollToItem: suspend (index: Int) -> Unit,
) {
    LaunchedEffect(animeList) {
        snapshotFlow { animeList.itemCount }
            .distinctUntilChanged()
            .collect {
                if (anchorTrail.isEmpty()) return@collect
                // peek, not get: this walks the loaded pages looking for an id
                // and must not ask the pager to load anything while doing it.
                val present = buildMap {
                    for (index in 0 until animeList.itemCount) {
                        animeList.peek(index)?.value?.id?.let { putIfAbsent(it, index) }
                    }
                }
                val target = anchorTrail.firstNotNullOfOrNull { present[it] } ?: return@collect
                val lead = leadingItemCount()
                if (target + lead != firstVisibleItemIndex()) scrollToItem(target + lead)
            }
    }
}

/**
 * [ANCHOR_TRAIL_LENGTH] ids from [start], as far as the loaded pages reach.
 *
 * Internal as well as used here, because the scroller records its own anchor
 * from the card it is playing and should not have its own idea of how long a
 * trail is.
 */
internal fun anchorTrailFrom(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    start: Int,
): List<Long> = trailFrom(animeList, start)

private fun trailFrom(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    start: Int,
): List<Long> {
    val from = start.coerceAtLeast(0)
    return (from until (from + ANCHOR_TRAIL_LENGTH))
        .filter { it < animeList.itemCount }
        .mapNotNull { animeList.peek(it)?.value?.id }
}

/**
 * How many entries from the anchor forward are remembered as the place to
 * return to.
 *
 * Only ever used when the anchor itself is removed, and what it has to cover is
 * a run of consecutive entries disappearing at once - which is what turning
 * hide-viewed on where the user has been watching does. Twenty is most of a
 * source's page (23 and 24 measured on the two listings in SourcePageCoverage's
 * notes), so a run long enough to exhaust it is a whole page of viewed entries,
 * where landing on the next page's first survivor is the honest answer anyway.
 */
private const val ANCHOR_TRAIL_LENGTH = 20

/**
 * How long the top of the viewport has to hold still before it counts as where
 * the user is.
 */
private const val ANCHOR_SETTLE_MS = 150L
// <-- AM (A_THE_FEED_KEEPS_ITS_PLACE)
