package eu.kanade.presentation.browse.components


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import aniyomi.ui.browse.scroller.ScrollerPlaybackState
import aniyomi.ui.browse.scroller.centreItem
import aniyomi.ui.browse.scroller.scrollerDrawAspect
import aniyomi.source.js.JsProjectSource
import aniyomi.ui.browse.scroller.ScrollerCard
import aniyomi.ui.browse.scroller.ScrollerCardSpacing
import aniyomi.ui.browse.scroller.ScrollerFullscreenPlayer
import aniyomi.ui.browse.scroller.rememberScrollSpeedTracker
import aniyomi.ui.browse.scroller.rememberScrollerPlayback
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime

// AM (BROWSE_SCROLLER) -->
/**
 * A card feed, the way Coub's own feed reads: a single column of cards, each
 * one a title line above a clip in its own rounded box, with dead space
 * between cards and tags below.
 *
 * Every card near the one being looked at has its own mpv instance, holding
 * its own video paused on the first frame (see [ScrollerPlayerPool]). So a
 * card shows its actual video from the moment it comes into view, and reaching
 * it is an unpause rather than a load - there is no thumbnail-to-video swap to
 * notice. A card's cover is only what sits there until its own instance has a
 * frame, and for cards outside the window.
 *
 * Nothing snaps: cards are smaller than the viewport, so there is no boundary
 * worth forcing a settle onto, and resting between two cards is a legitimate
 * place to stop.
 *
 * [onActiveIndexChange] reports the card being looked at, settled briefly so a
 * fling does not load everything it passes.
 */
@Composable
fun BrowseSourceScroller(
    animeList: LazyPagingItems<StateFlow<Anime>>,
    // AM (BROWSE_SCROLLER): null on screens that only render the layout
    // without playing anything.
    source: AnimeSource?,
    // AM (MERGED_SOURCES)
    mergeChildIds: Set<Long>,
    contentPadding: PaddingValues,
    onAnimeClick: (Anime) -> Unit,
    onAnimeLongClick: (Anime) -> Unit,
    // AM (DOUBLE_TAP_QUEUE)
    onAnimeDoubleClick: ((Anime) -> Unit)? = null,
    // AM (BROWSE_SELECTION)
    selection: Set<Long> = emptySet(),
    selectionMode: Boolean = false,
    // AM (BROWSE_SCROLLER): the card's own add-to-library button.
    onAnimeFavoriteClick: ((Anime) -> Unit)? = null,
    // AM (BROWSE_SCROLLER_CARD_ACTIONS) -->
    /** Long-pressing that button, which opens the category dialog instead. */
    onAnimeFavoriteLongClick: ((Anime) -> Unit)? = null,
    /** A tag's own actions, the entry screen's: search here, or everywhere. */
    onTagSearch: ((String) -> Unit)? = null,
    onTagGlobalSearch: ((String) -> Unit)? = null,
    /** The creator line, which searches the way the entry screen's does. */
    onCreatorSearch: ((String) -> Unit)? = null,
    // <-- AM (BROWSE_SCROLLER_CARD_ACTIONS)
    onActiveIndexChange: (Int) -> Unit = {},
) {
    val listState = rememberLazyListState()
    // AM (ENTRY_SCROLLER): for the expanded view's previous/next step.
    val scope = rememberCoroutineScope()
    var scrolledActiveIndex by remember { mutableIntStateOf(-1) }
    val playback = rememberScrollerPlayback(source = source)

    // AM (ENTRY_SCROLLER) -->
    // The expanded card is the one being watched, so it is the one the load
    // window centres on - the same rule setActive already follows
    // (AM (BROWSE_SCROLLER_FULLSCREEN_HOLDS_ACTIVE)), applied to loading too.
    //
    // Without it the window stays centred on whatever the viewport covers, so
    // stepping to the card after the last visible one steps to a card that is
    // not allowed to hold an instance: the expanded view sits on a spinner
    // and the button looks dead.
    val fullscreenIndex = remember(playback?.fullscreenAnimeId, animeList.itemCount) {
        playback?.fullscreenAnimeId?.let { id ->
            (0 until animeList.itemCount).firstOrNull { animeList.peek(it)?.value?.id == id }
        } ?: -1
    }
    val activeIndex = if (fullscreenIndex >= 0) fullscreenIndex else scrolledActiveIndex
    // <-- AM (ENTRY_SCROLLER)

    // AM (A_FEED_PLAYS_ONLY_ON_A_SCREEN_IN_FRONT) -->
    // Whether anything is in front of the feed, which only an activity change
    // answers: in-app navigation leaves this activity resumed, so a feed's
    // audio still survives a trip to an entry screen, while the player opened
    // over it - or any other app - stops it. Registering the observer reports
    // the lifecycle's current state immediately, so a composition that comes
    // back while covered starts out knowing it is.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(playback, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                playback?.setScreenInFront(true)
            }

            override fun onPause(owner: LifecycleOwner) {
                playback?.setScreenInFront(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Only the observer is removed. Leaving composition is in-app
        // navigation, which is deliberately not a reason to stop the feed.
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // <-- AM (A_FEED_PLAYS_ONLY_ON_A_SCREEN_IN_FRONT)

    // AM (A_THE_FEED_KEEPS_ITS_PLACE) -->
    /**
     * The entry being looked at, and the entries just after it, as of the last
     * time the active card settled.
     *
     * The feed supplies its own anchor rather than taking the top of the
     * viewport like the other layouts: it already knows which card is being
     * watched, and that is a better answer than whichever card the viewport
     * happens to start on. See BrowseListingAnchor for what is done with it.
     */
    var anchorTrail by remember { mutableStateOf<List<Long>>(emptyList()) }

    RestoreListingAnchor(
        animeList = animeList,
        anchorTrail = anchorTrail,
        firstVisibleItemIndex = { listState.firstVisibleItemIndex },
        leadingItemCount = { if (animeList.loadState.prepend is LoadState.Loading) 1 else 0 },
        scrollToItem = { index -> listState.scrollToItem(index) },
    )
    // <-- AM (A_THE_FEED_KEEPS_ITS_PLACE)

    // AM (SCROLLER_SPEED_GATE): how fast the feed is moving right now, which is
    // what decides whether anything new may be started - see the tracker.
    val speed = rememberScrollSpeedTracker(listState)

    val currentOnActiveIndexChange by rememberUpdatedState(onActiveIndexChange)

    // Keyed on listState and playback, not the callback. A process-background
    // rebuild replaces playback while retaining both listState and the current
    // selection; without restarting this collector, distinctUntilChanged()
    // suppresses that unchanged selection forever, leaving no active card to
    // autoplay or own audio. The callback itself remains safely updated through
    // rememberUpdatedState without restarting the settle timer on recomposition.
    LaunchedEffect(listState, playback) {
        // Whichever card covers most of the viewport right now, scrolling or
        // not: waiting for the scroll to stop meant the window could not even
        // start loading until then.
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val index = if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                // At the exact top, the first card is active even when a later
                // or taller card currently occupies more of the viewport.
                0.takeIf { animeList.itemCount > 0 }
            } else if (!listState.canScrollForward && animeList.itemCount > 0) {
                // AM (A_FEED_HAS_A_BOTTOM_AS_WELL_AS_A_TOP) -->
                // The same at the other end, which it did not have.
                //
                // The rule below is whichever card covers the most visible
                // pixels, and the last card in the feed is the one card that
                // can be prevented from ever winning it: the scroll stops with
                // it still sharing the viewport with the card above, so if it
                // is the shorter of the two - a portrait card under a landscape
                // one, which is most of them - no amount of scrolling can make
                // it dominant. It therefore never became active and never
                // played, however deliberately the user stopped on it.
                //
                // Keyed on the list being unable to scroll any further rather
                // than on a visibility fraction, for the same reason the top
                // case is: being at the end IS the statement that this is the
                // card the user stopped on, and a fraction only moves the
                // threshold that cannot be reached. An append still loading
                // leaves the feed scrollable, so this is the end of the
                // listing, not a pause part way down it.
                animeList.itemCount - 1
                // <-- AM (A_FEED_HAS_A_BOTTOM_AS_WELL_AS_A_TOP)
            } else {
                layoutInfo.visibleItemsInfo
                    // Visible pixels, not fraction of the viewport: the old
                    // ratio permanently disadvantaged short cards against
                    // taller neighbours, however central the short card was.
                    .maxByOrNull { info ->
                        val start = info.offset.coerceAtLeast(layoutInfo.viewportStartOffset)
                        val end = (info.offset + info.size).coerceAtMost(layoutInfo.viewportEndOffset)
                        (end - start).coerceAtLeast(0)
                    }
                    ?.index
            }
            index?.let { it to animeList[it]?.value?.id }
        }
            .distinctUntilChanged()
            // The entry id is part of the emission: replacing a feed can keep
            // the same index while putting a different card there, and that
            // replacement must become active instead of staying paused.
            .collectLatest { selection ->
                selection ?: return@collectLatest
                val (index, animeId) = selection
                delay(ACTIVE_CARD_SETTLE_MS)
                // AM (SCROLLER_SPEED_GATE) -->
                // The settle alone was not a statement about speed. A fling
                // decelerates through card after card, each of which stays
                // dominant for longer than the settle on the way past, so every
                // one of them became active in turn: the window moved with it,
                // four cards were acquired and loaded per step, and a five-slot
                // pool spent the fling reassigning instances. Waiting for the
                // feed to be moving slowly enough to be read makes the settle
                // what it was meant to be.
                //
                // snapshotFlow rather than a poll: isFast is snapshot state, so
                // this resumes on the frame it turns over, and collectLatest
                // cancels the wait outright if a different card takes over.
                snapshotFlow { speed.isFast }.first { !it }
                // <-- AM (SCROLLER_SPEED_GATE)
                scrolledActiveIndex = index
                playback?.setActive(animeId)
                // AM (A_THE_FEED_KEEPS_ITS_PLACE): where the user is, recorded
                // where the feed already works that out. Peeked rather than
                // read, so capturing it does not ask the pager for a page.
                anchorTrail = anchorTrailFrom(animeList, index)
                currentOnActiveIndexChange(index)
            }
    }

    // Geometry well ahead of the viewport. A LazyColumn only composes what is
    // about to be visible, so the per-card pass below can still land after a
    // card has appeared; this one runs on the index alone and reaches cards
    // the list has not composed yet. Cheap: resolution is cached and the probe
    // reads a header rather than decoding.
    LaunchedEffect(playback, activeIndex, animeList.itemCount) {
        if (playback != null && source != null) {
            // Centred on the top of the list before any card is active, so the
            // first screenful is measured during the initial load rather than
            // waiting for a scroll to start it.
            val centre = activeIndex.coerceAtLeast(0)
            for (offset in -GEOMETRY_BEHIND..GEOMETRY_AHEAD) {
                val index = centre + offset
                if (index !in 0 until animeList.itemCount) continue
                val anime = animeList[index]?.value ?: continue
                launch { playback.prepareGeometry(anime, source) }
            }
        }
    }

    // Draw immediately. Geometry probing continues above and refines each
    // card's ratio when it arrives; blocking the whole list on that optional
    // refinement made media loading freeze the user's ability to scroll.
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ScrollerCardSpacing),
        // AM (SCROLLER_SPEED_GATE): the scroll deltas the list actually
        // consumed are the only honest measure of how fast the feed is moving -
        // item indices are too coarse and cards are not all the same height.
        modifier = Modifier.nestedScroll(speed),
    ) {
        if (animeList.loadState.prepend is LoadState.Loading) {
            item { BrowseSourceLoadingItem() }
        }

        items(count = animeList.itemCount) { index ->
            val anime by animeList[index]?.collectAsState() ?: return@items

            // A card holds a player only while it is near the one being looked
            // at. The card owns that lifetime - it acquires when it comes into
            // range and releases when it goes out of range or leaves
            // composition - so a player can never be released from under a
            // card that is still drawing it.
            val distance = index - activeIndex
            val shouldLoad = source != null && activeIndex >= 0 &&
                distance in -WINDOW_BEHIND..WINDOW_AHEAD

            // AM (SCROLLER_PRELOAD_TIERS) -->
            // How much this card may pull, by how close it is to the one being
            // looked at. The ladder is Media3's DefaultPreloadManager guide's:
            // the cards either side of the playing one get a real range load,
            // the ones beyond them only enough to be ready. The card behind is
            // Near as well as the one ahead - it is what a scroll back lands
            // on, and it had been getting the same two seconds as the card two
            // ahead despite being one step away.
            val tier = when (distance) {
                in -1..1 -> MPVPlayer.CacheTier.Near
                else -> MPVPlayer.CacheTier.Far
            }
            // <-- AM (SCROLLER_PRELOAD_TIERS)

            // Geometry is worked out over a far wider range than playback.
            // Every card composed by the list gets measured, which is well
            // beyond the viewport, so a card's height is settled long before
            // it is looked at - rather than being its cover's shape until the
            // moment it is reached and then resizing.
            val hasPlayer = playback?.playerFor(anime.id) != null

            if (playback != null && source != null) {
                LaunchedEffect(playback, anime.id) {
                    playback.prepareGeometry(anime, source)
                }
                // Keyed on hasPlayer as well, so this heals itself. If a card
                // loses its instance for any reason while it still wants one -
                // the pool reassigning it under pressure, say - the key flips
                // and the card asks for another, rather than sitting blank
                // because its effect already ran once and will not run again.
                //
                // AM (CLIP_RELOAD_ONCE): this is also the whole of the retry.
                // A card whose file mpv reports as failed has its instance
                // released, which flips this key, and the card asks again
                // through exactly this call - so the retry cannot drift from
                // the ordinary load.
                //
                // AM (SCROLLER_SPEED_GATE): the release is NOT gated on speed.
                // A card leaving the window gives its instance back whatever
                // the feed is doing - retention keeps its file open either way,
                // and holding instances for cards nobody is near is how the
                // pool runs out of slots for the ones they are.
                LaunchedEffect(playback, anime.id, shouldLoad, hasPlayer, tier, speed.isFast) {
                    when {
                        !shouldLoad -> playback.release(anime.id)
                        // Nothing new is started mid-fling. This effect re-runs
                        // when isFast turns over, which is what picks the card
                        // up again once the feed has slowed down.
                        speed.isFast -> Unit
                        else -> playback.acquire(anime, source, tier)
                    }
                }
                LaunchedEffect(playback, anime.id, shouldLoad, speed.isFast) {
                    // AM (SCROLLER_SPEED_GATE): a round trip per card passed is
                    // the same waste as a load per card passed.
                    if (shouldLoad && !speed.isFast) playback.fetchDetails(anime, source)
                }
                DisposableEffect(playback, anime.id) {
                    onDispose { playback.release(anime.id) }
                }
            }

            ScrollerCard(
                anime = anime,
                // AM (MERGED_SOURCES)
                inMerge = anime.id in mergeChildIds,
                // AM (BROWSE_SELECTION)
                isSelected = anime.id in selection,
                // This card's own instance, if it is in the window. Null means
                // the card is too far away to be loaded and shows its cover.
                player = playback?.playerFor(anime.id),
                videoOutput = playback?.videoOutput,
                // AM (BROWSE_SCROLLER_CONTROLS): the card's controls, which are
                // the player's own - mute included, so nothing sits over the
                // clip permanently.
                playback = playback,
                // AM (BROWSE_SCROLLER_CONTROLS): while this card is expanded,
                // its surface belongs to the fullscreen host and the card
                // itself must not draw one - two views cannot hold the same
                // SurfaceTexture.
                isFullscreen = playback?.fullscreenAnimeId == anime.id,
                // AM (BROWSE_SCROLLER_FRAME_WATCH): whether this card's own
                // instance has rendered anything yet - see ScrollerCard.
                hasFrame = playback?.hasFrame(anime.id) == true,
                // Width is fixed at the screen's, so this decides height.
                aspectRatio = playback?.aspectRatios?.get(anime.id),
                onCoverAspectRatio = { ratio -> playback?.onCoverAspectRatio(anime.id, ratio) },
                onTitleClick = { onAnimeClick(anime) },
                // AM (DOUBLE_TAP_QUEUE): on the title, since a tap on the clip
                // now drives the player.
                onTitleDoubleClick = onAnimeDoubleClick?.let { handler -> { handler(anime) } },
                onFavoriteClick = onAnimeFavoriteClick?.let { handler -> { handler(anime) } },
                // AM (BROWSE_SCROLLER_CARD_ACTIONS) -->
                onFavoriteLongClick = onAnimeFavoriteLongClick?.let { handler -> { handler(anime) } },
                onTagSearch = onTagSearch,
                onTagGlobalSearch = onTagGlobalSearch,
                onCreatorSearch = onCreatorSearch,
                // <-- AM (BROWSE_SCROLLER_CARD_ACTIONS)
                feedActionSource = source as? JsProjectSource,
                // In selection mode a clip tap selects, as in every other
                // browse layout - wherever it lands. Otherwise where it landed
                // decides: see ScrollerPlaybackState.onTap.
                onClipTap = { onPlayPauseSpot ->
                    when {
                        selectionMode -> onAnimeClick(anime)
                        playback != null -> playback.onTap(anime.id, onPlayPauseSpot)
                    }
                },
                onClipLongClick = { onAnimeLongClick(anime) },
                onClipDoubleTap = playback?.let { state ->
                    { zone: ScrollerPlaybackState.TapZone -> state.onDoubleTap(anime.id, zone) }
                },
            )
        }

        if (animeList.loadState.refresh is LoadState.Loading || animeList.loadState.append is LoadState.Loading) {
            item { BrowseSourceLoadingItem() }
        }
    }

    // AM (BROWSE_SCROLLER_CONTROLS) -->
    // Hosted by the feed rather than by the card: a card is an item in a lazy
    // list and can be scrolled out of composition, which would take the
    // fullscreen view down with it. The instance it draws is the card's own.
    val fullscreenId = playback?.fullscreenAnimeId
    if (playback != null && fullscreenId != null) {
        val fullscreenPlayer = playback.playerFor(fullscreenId)
        // AM (ENTRY_SCROLLER): the expanded card's index, worked out above -
        // the player's previous/next step through the listing, which here is
        // simply the next and previous card in the feed.
        val fullscreenTitle = remember(fullscreenIndex) {
            animeList.peek(fullscreenIndex.coerceAtLeast(0))?.value?.title.orEmpty()
        }
        fun stepTarget(next: Boolean): Int? = (fullscreenIndex + if (next) 1 else -1)
            .takeIf { fullscreenIndex >= 0 && it >= 0 && it < animeList.itemCount }
        // <-- AM (ENTRY_SCROLLER)
        // AM (ENTRY_SCROLLER): closed when the card is no longer in the feed,
        // not when it is momentarily without an instance. Those used to be the
        // same test, which is why stepping to the next card closed the whole
        // expanded view: the step arrives before the card it moved to has been
        // given a player. A card still in the feed gets one back through its
        // own effects, and the expanded view shows the spinner until it does.
        if (fullscreenIndex < 0) {
            LaunchedEffect(fullscreenId) { playback.exitFullscreen() }
        } else {
            ScrollerFullscreenPlayer(
                playback = playback,
                player = fullscreenPlayer,
                animeId = fullscreenId,
                title = fullscreenTitle,
                videoOutput = playback.videoOutput,
                // AM (ENTRY_SCROLLER) -->
                aspect = scrollerDrawAspect(playback.aspectRatios[fullscreenId]),
                onStep = { next ->
                    val targetIndex = stepTarget(next) ?: return@ScrollerFullscreenPlayer
                    val targetId = animeList.peek(targetIndex)?.value?.id
                        ?: return@ScrollerFullscreenPlayer
                    // The step itself first, and NOT inside the coroutine: a
                    // scroll is a suspending, cancellable mutation, and with
                    // the move behind it a scroll that never settled was a
                    // button that did nothing at all. The scroll follows, to
                    // leave the feed where the user now is when the expanded
                    // view closes - and if it is cancelled, only that is lost.
                    playback.moveFullscreenTo(targetId)
                    // The list's own index, which is the paging index plus the
                    // prepend loading item when there is one - the same
                    // leadingItemCount the anchor restore uses. Scrolling to
                    // the paging index while that item is up lands a card
                    // short, which leaves the feed showing one card while
                    // another is the one playing.
                    val leading = if (animeList.loadState.prepend is LoadState.Loading) 1 else 0
                    scope.launch { listState.centreItem(targetIndex + leading) }
                },
                hasPrevious = stepTarget(next = false) != null,
                hasNext = stepTarget(next = true) != null,
                // <-- AM (ENTRY_SCROLLER)
            )
        }
    }
    // <-- AM (BROWSE_SCROLLER_CONTROLS)
}

/**
 * How long a card has to stay the dominant one before it is loaded. Long
 * enough that a fling does not load everything it passes, short enough that a
 * deliberate stop starts playing immediately.
 */
private const val ACTIVE_CARD_SETTLE_MS = 120L

// AM (A_WINDOW_IS_WHAT_THE_POOL_IS_SIZED_FOR) -->
/**
 * Cards kept loaded behind and ahead of the one being looked at.
 *
 * FIVE cards, and the count is load-bearing rather than descriptive:
 * ScrollerPlayerPool.CAPACITY has to be at least this window plus one, because
 * a pool smaller than the window evicts a card that is still inside it on every
 * step. The two constants move together or not at all.
 *
 * Symmetric at 2, tried at 1 and put back: two cards out is far enough that a
 * scroll of any pace lands on a card that already exists, and reaching one that
 * does not is noticeable in a way the saved memory did not pay for.
 *
 * Cheap rather than few is the bargain. The outer ring is not held at the same
 * cost as the inner one - the tier ladder above puts distance -1..1 on Near and
 * everything past it on Far, which is 8MiB either way and two seconds of
 * readahead (see MPVPlayer.applyScrollerCache). So widening the window by a
 * card adds a card that EXISTS, with a player and an open file, rather than one
 * holding content. That is what the ladder is for, and it is why the answer to
 * memory pressure here is the Far tier's byte limits rather than this number.
 *
 * Not for url resolution, which this is sometimes mistaken for: the 20-30s a
 * source can take to hand over a video url is covered by ClipVideoResolver,
 * which caches 128 resolved links and is driven speculatively across
 * GEOMETRY_BEHIND..GEOMETRY_AHEAD below - six cards ahead, further than any
 * pool reaches, and needing no decoder. What the window buys is a decoded first
 * frame.
 */
private const val WINDOW_BEHIND = 2
private const val WINDOW_AHEAD = 2
// <-- AM (A_WINDOW_IS_WHAT_THE_POOL_IS_SIZED_FOR)

/**
 * How far ahead and behind card heights are worked out. Far wider than the
 * players, because this costs no decoder - only a cached resolution and a
 * header read - and because a height that settles after a card is on screen is
 * a card that resizes under the user.
 */
private const val GEOMETRY_BEHIND = 3
private const val GEOMETRY_AHEAD = 6

// <-- AM (BROWSE_SCROLLER)
