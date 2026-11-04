package eu.kanade.presentation.browse.components

import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import aniyomi.ui.browse.scroller.ScrollerPlaybackState
import aniyomi.source.js.JsProjectSource
import aniyomi.ui.browse.scroller.ScrollerCardControls
import aniyomi.ui.browse.scroller.ScrollerFeedAction
import aniyomi.ui.browse.scroller.ScrollerFullscreenPlayer
import aniyomi.ui.browse.scroller.ScrollerPlayer
import aniyomi.ui.browse.scroller.rememberScrollerPlayback
import coil3.compose.AsyncImage
import eu.kanade.presentation.anime.components.TagActionsMenu
import eu.kanade.presentation.anime.components.TagsChip
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.ui.player.components.MpvSurface
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover

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
    var activeIndex by remember { mutableIntStateOf(-1) }

    val playback = rememberScrollerPlayback(source = source)

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
                activeIndex = index
                playback?.setActive(animeId)
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
            val shouldLoad = source != null && activeIndex >= 0 &&
                (index - activeIndex) in -WINDOW_BEHIND..WINDOW_AHEAD

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
                LaunchedEffect(playback, anime.id, shouldLoad, hasPlayer) {
                    if (shouldLoad) playback.acquire(anime, source) else playback.release(anime.id)
                }
                LaunchedEffect(playback, anime.id, shouldLoad) {
                    if (shouldLoad) playback.fetchDetails(anime, source)
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
        val fullscreenTitle = remember(fullscreenId, animeList.itemCount) {
            (0 until animeList.itemCount)
                .firstNotNullOfOrNull { animeList.peek(it)?.value?.takeIf { a -> a.id == fullscreenId } }
                ?.title
                .orEmpty()
        }
        if (fullscreenPlayer == null) {
            // Its instance went away underneath it - there is nothing left to
            // show at full size.
            LaunchedEffect(fullscreenId) { playback.exitFullscreen() }
        } else {
            ScrollerFullscreenPlayer(
                playback = playback,
                player = fullscreenPlayer,
                animeId = fullscreenId,
                title = fullscreenTitle,
                videoOutput = playback.videoOutput,
            )
        }
    }
    // <-- AM (BROWSE_SCROLLER_CONTROLS)
}

/**
 * One card: creator and title at the top, its clip, tags at the bottom.
 *
 * The header and footer are generously padded on purpose. Action buttons are
 * going into this card next, and laying it out tight now would mean re-spacing
 * the whole thing when they arrive.
 *
 * [player] is this card's own mpv instance when the card is near enough to be
 * loaded, and null otherwise. The clip box hosts that instance's surface
 * directly, so the card draws its own video and never borrows another card's.
 */
@Composable
private fun ScrollerCard(
    anime: Anime,
    // AM (MERGED_SOURCES)
    inMerge: Boolean,
    // AM (BROWSE_SELECTION)
    isSelected: Boolean,
    player: ScrollerPlayer?,
    videoOutput: String?,
    // AM (BROWSE_SCROLLER_CONTROLS)
    playback: ScrollerPlaybackState?,
    isFullscreen: Boolean,
    aspectRatio: Float?,
    onCoverAspectRatio: (Float) -> Unit,
    onTitleClick: () -> Unit,
    onTitleDoubleClick: (() -> Unit)?,
    onFavoriteClick: (() -> Unit)?,
    // AM (BROWSE_SCROLLER_CARD_ACTIONS) -->
    onFavoriteLongClick: (() -> Unit)?,
    onTagSearch: ((String) -> Unit)?,
    onTagGlobalSearch: ((String) -> Unit)?,
    onCreatorSearch: ((String) -> Unit)?,
    // <-- AM (BROWSE_SCROLLER_CARD_ACTIONS)
    feedActionSource: JsProjectSource?,
    /** True when the tap landed where the play/pause button sits. */
    onClipTap: (Boolean) -> Unit,
    onClipLongClick: () -> Unit,
    onClipDoubleTap: ((ScrollerPlaybackState.TapZone) -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ScrollerCardHeader(
            anime = anime,
            // AM (MERGED_SOURCES)
            inMerge = inMerge,
            onTitleClick = onTitleClick,
            onTitleDoubleClick = onTitleDoubleClick,
            onFavoriteClick = onFavoriteClick,
            // AM (BROWSE_SCROLLER_CARD_ACTIONS)
            onFavoriteLongClick = onFavoriteLongClick,
            onCreatorSearch = onCreatorSearch,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScrollerCardPadding)
                // The width is the screen's, so the ratio is what decides how
                // tall this card's video is. Read off this card's own instance
                // as soon as its file is open - which happens while the card
                // is still being approached - from the cover before that, and
                // from a landscape default until the cover has loaded.
                // Clamped: a card taller than a few screens is unusable
                // however the media is shaped, and a zero or non-finite ratio
                // is an exception out of aspectRatio() rather than a layout.
                .aspectRatio(
                    aspectRatio
                        ?.takeIf { it.isFinite() && it > 0f }
                        ?.coerceIn(ScrollerMinAspectRatio, ScrollerMaxAspectRatio)
                        ?: ScrollerFallbackAspectRatio,
                )
                .clip(RoundedCornerShape(ScrollerClipCorner))
                .background(ScrollerDeadSpaceColor)
                // Not combinedClickable: a tap here has to know WHERE it
                // landed, which that gives no way to see. A double tap is
                // dispatched by thirds, the same zones the real player's own
                // double-tap gestures use; a single tap only asks whether it
                // landed on the play/pause button's own spot.
                .pointerInput(onClipDoubleTap, onClipTap, onClipLongClick) {
                    detectTapGestures(
                        onTap = { offset ->
                            onClipTap(isOnPlayPauseSpot(offset, size.width, size.height))
                        },
                        onLongPress = { onClipLongClick() },
                        onDoubleTap = { offset ->
                            val handler = onClipDoubleTap ?: return@detectTapGestures
                            handler(tapZoneAt(offset.x, size.width))
                        },
                    )
                },
        ) {
            // AM (BROWSE_SCROLLER_CONTROLS): not while this card is expanded -
            // the fullscreen host is drawing this instance.
            if (player != null && videoOutput != null && !isFullscreen) {
                MpvSurface(
                    player = player.player,
                    videoOutput = videoOutput,
                    // Tells this card's instance when it can load with
                    // hardware decoding - see ScrollerPlayer.loadPaused.
                    onSurfaceAttachedChanged = player::onSurfaceAttachedChanged,
                    // Lets the instance read its own displayed frame, for use
                    // as this card's placeholder later.
                    onViewAvailable = player::onViewAvailable,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // The placeholder is drawn OVER the surface, and only while this
            // card has no frame of its own.
            //
            // Under the surface was wrong in a way that looks like a stale
            // thumbnail: the surface fills the box but mpv letterboxes the
            // video inside it, so whatever sat behind showed through the bars
            // and stayed there for as long as the card was playing. Over the
            // surface, and simply not emitted once there is a frame, there is
            // nothing left to show through.
            // AM (BROWSE_SCROLLER_NO_FLASH) -->
            // Only for a card too far away to be loaded at all. A card in the
            // load window shows black until its own video is there.
            //
            // It used to show its cover, then its captured first frame, then
            // the video - which on a fast scroll is three different images in
            // quick succession per card, the grey placeholder behind a cover
            // still loading among them. Black and then the video is one
            // change instead of four.
            if (player == null) {
            // <-- AM (BROWSE_SCROLLER_NO_FLASH)
                AsyncImage(
                    model = AnimeCover(
                        animeId = anime.id,
                        sourceId = anime.source,
                        isAnimeFavorite = anime.favorite,
                        url = anime.thumbnailUrl,
                        lastModified = anime.coverLastModified,
                    ),
                    placeholder = ColorPainter(ScrollerPlaceholderColor),
                    error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
                    contentDescription = anime.title,
                    // Fit, not Crop: the dead space inside the box is the honest
                    // result for content that is not this shape, and cropping a
                    // portrait cover into a landscape box throws most of it away.
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val image = state.result.image
                        if (image.height > 0) {
                            onCoverAspectRatio(image.width.toFloat() / image.height.toFloat())
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isSelected) SelectedItemAlpha else 1f),
                )
            }
            // AM (BROWSE_SCROLLER_CONTROLS): raised by a tap on the clip, and
            // only on a card that has an instance to control.
            if (playback != null && player != null && !isFullscreen) {
                ScrollerCardControls(
                    playback = playback,
                    animeId = anime.id,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        ScrollerCardFooter(
            anime = anime,
            feedActionSource = feedActionSource,
            // AM (BROWSE_SCROLLER_CARD_ACTIONS)
            onTagSearch = onTagSearch,
            onTagGlobalSearch = onTagGlobalSearch,
        )
    }
}

/**
 * Creator above, title below. Both come off the entry as the source already
 * gives them - author, falling back to artist, is what a source fills in for
 * the person who made the thing.
 */
@Composable
private fun ScrollerCardHeader(
    anime: Anime,
    // AM (MERGED_SOURCES)
    inMerge: Boolean,
    onTitleClick: () -> Unit,
    onTitleDoubleClick: (() -> Unit)?,
    onFavoriteClick: (() -> Unit)?,
    // AM (BROWSE_SCROLLER_CARD_ACTIONS)
    onFavoriteLongClick: (() -> Unit)?,
    onCreatorSearch: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScrollerHeaderGap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ScrollerCardPadding,
                end = ScrollerCardPadding,
                top = ScrollerHeaderTopPadding,
                bottom = ScrollerHeaderBottomPadding,
            ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ScrollerCreatorGap),
            modifier = Modifier.weight(1f),
        ) {
            val creator = anime.author?.takeIf { it.isNotBlank() }
                ?: anime.artist?.takeIf { it.isNotBlank() }
            if (creator != null) {
                Text(
                    text = creator,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // AM (BROWSE_SCROLLER_CARD_ACTIONS): the entry screen's
                    // own author behaviour - a tap searches everywhere for
                    // them, a long press copies the name.
                    modifier = if (onCreatorSearch == null) {
                        Modifier
                    } else {
                        Modifier.combinedClickable(
                            onClick = { onCreatorSearch(creator) },
                            onLongClick = { context.copyToClipboard(creator, creator) },
                        )
                    },
                )
            }

            Text(
                text = anime.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // Underlined so it reads as the link it is, now that tapping
                // the clip does not navigate.
                textDecoration = TextDecoration.Underline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.pointerInput(onTitleClick, onTitleDoubleClick) {
                    detectTapGestures(
                        onTap = { onTitleClick() },
                        onDoubleTap = onTitleDoubleClick?.let { handler -> { handler() } },
                    )
                },
            )
        }

        // AM (MERGED_SOURCES): still a badge, because being a merge child is
        // a state rather than an action.
        InMergeBadge(enabled = !anime.favorite && inMerge)

        if (onFavoriteClick != null) {
            // AM (BROWSE_SCROLLER_CARD_ACTIONS): long press opens the category
            // dialog, the same one the selection bar's action opens - a tap
            // still adds or removes outright.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onFavoriteClick,
                        onLongClick = onFavoriteLongClick ?: onFavoriteClick,
                    )
                    .padding(ScrollerFavoriteButtonPadding),
            ) {
                Icon(
                    imageVector = if (anime.favorite) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Outlined.FavoriteBorder
                    },
                    contentDescription = null,
                    tint = if (anime.favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * The entry's own genres, which is what a source fills in for tags. Scrolled
 * horizontally rather than wrapped so a heavily tagged entry cannot push the
 * next card off the screen, and so every card in the feed is the same height.
 */
@Composable
private fun ScrollerCardFooter(
    anime: Anime,
    feedActionSource: JsProjectSource?,
    // AM (BROWSE_SCROLLER_CARD_ACTIONS)
    onTagSearch: ((String) -> Unit)?,
    onTagGlobalSearch: ((String) -> Unit)?,
) {
    val actions = remember(anime.memo) { ScrollerFeedAction.from(anime) }
    val like = actions.firstOrNull { it.icon == "favorite" }
    val bookmark = actions.firstOrNull { it.icon == "bookmark" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ScrollerTagsVerticalPadding),
    ) {
        val tags = anime.genre?.filter { it.isNotBlank() }.orEmpty()
        // AM (BROWSE_SCROLLER_CARD_ACTIONS) -->
        // The entry screen's own tag menu, not a feed copy of it: tapping a
        // tag here offers exactly what tapping it in the entry offers, minus
        // the playlist search, which needs an episode list a feed card has
        // not got.
        val context = LocalContext.current
        var tagMenuShown by remember { mutableStateOf(false) }
        var tagSelected by remember { mutableStateOf("") }
        val tagsActionable = onTagSearch != null || onTagGlobalSearch != null
        // Boxed with the chips, as the entry screen boxes it with its own:
        // a DropdownMenu anchors to its parent, and anchored to the whole
        // footer it would open away from the tag that was tapped.
        Box {
            if (tagsActionable) {
                TagActionsMenu(
                    expanded = tagMenuShown,
                    canSearchPlaylist = false,
                    onDismissRequest = { tagMenuShown = false },
                    onSearch = { onTagSearch?.invoke(tagSelected) },
                    onGlobalSearch = { onTagGlobalSearch?.invoke(tagSelected) },
                    onEpisodeSearch = {},
                    onCopyToClipboard = { context.copyToClipboard(tagSelected, tagSelected) },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ScrollerTagGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ScrollerCardPadding),
            ) {
                tags.forEach { tag ->
                    TagsChip(
                        text = tag,
                        onClick = {
                            if (tagsActionable) {
                                tagSelected = tag
                                tagMenuShown = true
                            }
                        },
                    )
                }
            }
        }
        // <-- AM (BROWSE_SCROLLER_CARD_ACTIONS)

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ScrollerTagsRowHeight)
                .padding(horizontal = ScrollerCardPadding),
        ) {
            Box {
                if (like != null && feedActionSource != null) {
                    ScrollerFeedActionButton(anime, like, feedActionSource)
                }
            }
            Box {
                if (bookmark != null && feedActionSource != null) {
                    ScrollerFeedActionButton(anime, bookmark, feedActionSource)
                }
            }
        }
    }
}

@Composable
private fun ScrollerFeedActionButton(
    anime: Anime,
    action: ScrollerFeedAction,
    source: JsProjectSource,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var active by remember(anime.id, action.id, action.initiallyActive) {
        mutableStateOf(action.initiallyActive)
    }
    var pending by remember(anime.id, action.id) { mutableStateOf(false) }

    IconButton(
        enabled = !pending,
        onClick = {
            pending = true
            scope.launch {
                try {
                    active = source.performFeedAction(
                        url = anime.url,
                        action = action.id,
                        active = active,
                        target = action.target,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        action.label.ifBlank { "Action" } + " failed: " + e.message,
                        Toast.LENGTH_SHORT,
                    ).show()
                } finally {
                    pending = false
                }
            }
        },
    ) {
        Icon(
            imageVector = when (action.icon) {
                "bookmark" -> if (active) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
                else -> if (active) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
            },
            contentDescription = action.label,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Whether [offset] landed on the play/pause button's own spot - the middle of
 * the clip, within [ScrollerPlayPauseSpotRadius] of it.
 *
 * AM (BROWSE_SCROLLER_CONTROLS): the single tap used to pause anywhere in the
 * middle third, which took the whole band across the card with it - including
 * the seekbar's own height, where a tap is someone wanting to see where they
 * are rather than to stop the clip. A radius around where the button actually
 * sits is the smallest region that still makes pausing a one-tap gesture.
 *
 * In pixels against the card's own size, because this is answering a question
 * about a touch on that card rather than laying anything out.
 */
private fun PointerInputScope.isOnPlayPauseSpot(offset: Offset, width: Int, height: Int): Boolean {
    val radius = ScrollerPlayPauseSpotRadius.toPx()
    val dx = offset.x - width / 2f
    val dy = offset.y - height / 2f
    return dx * dx + dy * dy <= radius * radius
}

/**
 * Which third of a card's clip [x] fell in, the same split the real player's
 * double-tap gestures use.
 */
private fun tapZoneAt(x: Float, width: Int): ScrollerPlaybackState.TapZone {
    val third = width / 3f
    return when {
        x < third -> ScrollerPlaybackState.TapZone.Left
        x > third * 2f -> ScrollerPlaybackState.TapZone.Right
        else -> ScrollerPlaybackState.TapZone.Center
    }
}

/** Used only until a card's cover has loaded and can be measured. */
private const val ScrollerFallbackAspectRatio = 16f / 9f

/** Tallest and widest a card may be, whatever the media says. */
private const val ScrollerMinAspectRatio = 0.4f
private const val ScrollerMaxAspectRatio = 3f

/**
 * How long a card has to stay the dominant one before it is loaded. Long
 * enough that a fling does not load everything it passes, short enough that a
 * deliberate stop starts playing immediately.
 */
private const val ACTIVE_CARD_SETTLE_MS = 120L

/**
 * Cards kept loaded behind and ahead of the one being looked at. Three decoder
 * sessions in total, which is modest enough to be safe on real hardware while
 * still meaning both directions of a scroll land on something already loaded.
 */
private const val WINDOW_BEHIND = 1
private const val WINDOW_AHEAD = 2

/**
 * How far ahead and behind card heights are worked out. Far wider than the
 * players, because this costs no decoder - only a cached resolution and a
 * header read - and because a height that settles after a card is on screen is
 * a card that resizes under the user.
 */
private const val GEOMETRY_BEHIND = 3
private const val GEOMETRY_AHEAD = 6

/**
 * How far from the middle of a card a tap still counts as the play/pause
 * button's.
 *
 * Comfortably larger than the 96.dp button the controls draw there, so the
 * gesture does not demand precision, and far short of the seekbar along the
 * bottom or the sound button in the corner.
 */
private val ScrollerPlayPauseSpotRadius = 72.dp

/** The dead space between cards. */
private val ScrollerCardSpacing = 24.dp

private val ScrollerCardPadding = 16.dp
private val ScrollerHeaderTopPadding = 16.dp
private val ScrollerHeaderBottomPadding = 12.dp
private val ScrollerHeaderGap = 12.dp
private val ScrollerCreatorGap = 2.dp
private val ScrollerClipCorner = 12.dp
// AM (BROWSE_SCROLLER_CARD_ACTIONS): the add-to-library button is a Box with
// its own clickable now rather than an IconButton, so its touch target is
// padded here instead of by IconButton's own minimum size.
private val ScrollerFavoriteButtonPadding = 12.dp
private val ScrollerTagsVerticalPadding = 12.dp
private val ScrollerTagGap = 8.dp

/**
 * Reserved whether or not the entry has tags, so every card is the same height
 * and the feed does not jump as tagged and untagged entries scroll past. It is
 * also where the action buttons are going.
 */
private val ScrollerTagsRowHeight = 48.dp

private const val SelectedItemAlpha = 0.76f

/** Behind the clip, where content of another shape leaves a gap. */
private val ScrollerDeadSpaceColor = Color.Black
private val ScrollerPlaceholderColor = Color(0x1F888888)
// <-- AM (BROWSE_SCROLLER)
