package eu.kanade.presentation.anime.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import aniyomi.ui.browse.scroller.ScrollSpeedTracker
import aniyomi.ui.browse.scroller.ScrollerCard
import aniyomi.ui.browse.scroller.ScrollerCardRef
import aniyomi.ui.browse.scroller.ScrollerCardSpacing
import aniyomi.ui.browse.scroller.ScrollerFullscreenPlayer
import aniyomi.ui.browse.scroller.centreItem
import aniyomi.ui.browse.scroller.scrollerDrawAspect
import aniyomi.ui.browse.scroller.ScrollerPlaybackState
import aniyomi.ui.browse.scroller.ScrollerSession
import aniyomi.ui.browse.scroller.cancelHorizontalInset
import aniyomi.ui.browse.scroller.rememberScrollSpeedTracker
import aniyomi.ui.browse.scroller.rememberScrollerPlayback
import eu.kanade.presentation.anime.AnimeScreenItem
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.ui.anime.EpisodeList
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.source.local.isLocal

// AM (ENTRY_SCROLLER) -->
/**
 * The entry screen's episode list, drawn as the scroller's own feed.
 *
 * The same feed the browse scroller draws, with one difference that matters:
 * a card here is an EPISODE of one entry rather than an entry of a listing.
 * Everything that makes the feed what it is - the instance per nearby card,
 * the window, the preload tiers, the speed gate, the controls, fullscreen,
 * progress and seen writing - is [ScrollerPlaybackState]'s and is shared
 * rather than reimplemented; see [ScrollerCardRef] for the whole of the
 * distinction between the two kinds of card.
 *
 * What an episode feed costs a source is the point of that sharing: the rows
 * are already in the database, so there is no episode-list request per card,
 * and a downloaded episode is answered off disk by EpisodeLoader without
 * touching the source at all. A card reached in a library entry asks the
 * source for one thing - its video url - and a downloaded one asks for
 * nothing.
 *
 * The cards are emitted into the entry screen's OWN grid rather than into a
 * list of their own, so the cover, description and the episode header above
 * them scroll exactly as they do in the other two views. That is also why the
 * active card is worked out from the grid's layout info here rather than from
 * a list state the feed owns.
 */
@Stable
class EpisodeScrollerState internal constructor(
    val playback: ScrollerPlaybackState?,
    val speed: ScrollSpeedTracker,
    val source: AnimeSource,
    val anime: Anime,
    private val sourceManager: SourceManager,
) {
    /** The card being looked at, as an index into the episode items. */
    internal var activeIndex by mutableIntStateOf(-1)

    /**
     * AM (MERGED_SOURCES): the source a card's own episode comes from, which
     * on a merged entry is the child's rather than the entry's. The hoster and
     * video lookups are that source's, so this cannot be the feed's source.
     */
    internal fun sourceFor(card: ScrollerCardRef): AnimeSource =
        if (card.anime.id == anime.id) source else sourceManager.getOrStub(card.anime.source)

    /**
     * The card the load window is centred on.
     *
     * The expanded card while one is expanded, whatever the viewport behind it
     * covers - the same rule setActive already follows
     * (AM (BROWSE_SCROLLER_FULLSCREEN_HOLDS_ACTIVE)), applied to loading too.
     * Without it, stepping to the card after the last visible one steps to a
     * card that is not allowed to hold an instance: the expanded view sits on
     * a spinner and the button looks dead.
     */
    internal fun windowCentreIndex(items: List<EpisodeList.Item>): Int {
        val fullscreenId = playback?.fullscreenAnimeId ?: return activeIndex
        return items.indexOfFirst { it.episode.id == fullscreenId }.takeIf { it >= 0 } ?: activeIndex
    }

    internal fun cardRef(item: EpisodeList.Item): ScrollerCardRef = ScrollerCardRef.ofEpisode(
        // AM (MERGED_SOURCES): a merged entry's episode belongs to its child,
        // and that is the entry its media and headers come from.
        anime = item.owner ?: anime,
        episode = item.episode,
        singleEpisodeSource = item.singleEpisodeSource,
    )
}

/**
 * Builds the feed's state for as long as the entry screen is in scroller view.
 *
 * Returns null when it is not, so the entry screen holds no players and starts
 * nothing while its episodes are drawn as rows.
 */
@Composable
fun rememberEpisodeScrollerState(
    enabled: Boolean,
    anime: Anime,
    source: AnimeSource,
    gridState: LazyGridState,
): EpisodeScrollerState? {
    // Composed unconditionally whatever [enabled] is: an early return before
    // these would change the call site's slot layout when the view mode is
    // switched from the toolbar.
    val playback = rememberScrollerPlayback(
        source = source.takeIf { enabled },
        feedKey = ScrollerSession.FeedKey.Entry(anime.id).takeIf { enabled },
    )
    val speed = rememberScrollSpeedTracker(gridState)
    val context = LocalContext.current

    // AM (A_FEED_PLAYS_ONLY_ON_A_SCREEN_IN_FRONT): the player opened over this
    // screen stops it; in-app navigation does not.
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sourceManager = remember { context.appGraph.sourceManager }
    val state = remember(playback, speed, source, anime.id, sourceManager) {
        EpisodeScrollerState(
            playback = playback,
            speed = speed,
            source = source,
            anime = anime,
            sourceManager = sourceManager,
        )
    }
    return state.takeIf { enabled }
}

/**
 * Keeps [EpisodeScrollerState.activeIndex] pointed at the card being looked at.
 *
 * Reads the grid rather than a list of its own, so the header items above the
 * feed are simply not candidates: a card is identified by its key, which is
 * the same `episode-<id>` key the rows use.
 */
@Composable
fun EpisodeScrollerActiveCardEffect(
    state: EpisodeScrollerState,
    gridState: LazyGridState,
    items: List<EpisodeList.Item>,
) {
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(state, gridState) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val dominant = layoutInfo.visibleItemsInfo
                .filter { (it.key as? String)?.startsWith(EPISODE_KEY_PREFIX) == true }
                // Visible pixels, not fraction of the viewport: cards are not
                // all the same height, and a fraction permanently disadvantages
                // the short ones however central they are.
                .maxByOrNull { info ->
                    val start = info.offset.y.coerceAtLeast(layoutInfo.viewportStartOffset)
                    val end = (info.offset.y + info.size.height).coerceAtMost(layoutInfo.viewportEndOffset)
                    (end - start).coerceAtLeast(0)
                }
            (dominant?.key as? String)?.removePrefix(EPISODE_KEY_PREFIX)?.toLongOrNull()
        }
            .distinctUntilChanged()
            .collectLatest { episodeId ->
                episodeId ?: return@collectLatest
                delay(ACTIVE_CARD_SETTLE_MS)
                // AM (SCROLLER_SPEED_GATE): nothing is started mid-fling.
                snapshotFlow { state.speed.isFast }.first { !it }
                state.activeIndex = currentItems.indexOfFirst { it.episode.id == episodeId }
                state.playback?.setActive(episodeId)
            }
    }

    // Geometry well ahead of the viewport, for the reason the browse feed runs
    // the same pass: the grid composes only what is about to be visible, so a
    // card measured on composition is a card that resizes under the user.
    // Cheap - a cached resolution and a header read, no decoder.
    LaunchedEffect(state, items) {
        snapshotFlow { state.activeIndex }.collectLatest { active ->
            val playback = state.playback ?: return@collectLatest
            val centre = active.coerceAtLeast(0)
            coroutineScope {
                for (offset in -GEOMETRY_BEHIND..GEOMETRY_AHEAD) {
                    val item = currentItems.getOrNull(centre + offset) ?: continue
                    val card = state.cardRef(item)
                    launch { playback.prepareGeometry(card, state.sourceFor(card)) }
                }
            }
        }
    }
}

/**
 * The card expanded to fill the screen, hosted by the entry screen rather than
 * by a card: a card is an item in a lazy grid and can be scrolled out of
 * composition, which would take the expanded view down with it.
 *
 * Previous and next step through the entry's episodes, which here IS the
 * playlist: the items the feed is drawing, in the order the entry screen
 * sorted and filtered them.
 */
@Composable
fun EpisodeScrollerFullscreenHost(
    state: EpisodeScrollerState,
    items: List<EpisodeList.Item>,
    gridState: LazyGridState,
) {
    val playback = state.playback ?: return
    val fullscreenId = playback.fullscreenAnimeId ?: return
    val scope = rememberCoroutineScope()
    val index = items.indexOfFirst { it.episode.id == fullscreenId }
    // Closed when the episode has left the feed - a filter change, say - and
    // not merely because its instance is momentarily gone. See the same rule
    // in BrowseSourceScroller for why those two must not be one test.
    if (index < 0) {
        LaunchedEffect(fullscreenId) { playback.exitFullscreen() }
        return
    }
    fun stepTarget(next: Boolean): Int? =
        (index + if (next) 1 else -1).takeIf { it >= 0 && it < items.size }

    ScrollerFullscreenPlayer(
        playback = playback,
        player = playback.playerFor(fullscreenId),
        animeId = fullscreenId,
        title = items[index].displayName,
        videoOutput = playback.videoOutput,
        aspect = scrollerDrawAspect(playback.aspectRatios[fullscreenId]),
        onStep = { next ->
            val targetIndex = stepTarget(next) ?: return@ScrollerFullscreenPlayer
            // The step itself first, and NOT inside the coroutine: a scroll is
            // a suspending, cancellable mutation, and with the move behind it
            // a scroll that never settled was a button that did nothing at
            // all. The scroll follows, to leave the entry screen where the
            // user now is when the expanded view closes - and if it is
            // cancelled, only that is lost.
            playback.moveFullscreenTo(items[targetIndex].episode.id)
            scope.launch {
                // The grid holds the cover, description and episode header
                // above the cards, so a card's grid index is not its index in
                // [items]. Read off whatever card is laid out rather than
                // counted: what sits above the feed is the entry screen's
                // business and changes with it.
                gridIndexOffset(gridState, items)?.let { offset ->
                    gridState.centreItem(offset + targetIndex)
                }
            }
        },
        hasPrevious = stepTarget(next = false) != null,
        hasNext = stepTarget(next = true) != null,
    )
}


/**
 * How far a card's index in [items] is from its index in the grid, read off a
 * card that is currently laid out. Null when none is.
 */
private fun gridIndexOffset(gridState: LazyGridState, items: List<EpisodeList.Item>): Int? =
    gridState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
        val id = (info.key as? String)
            ?.takeIf { it.startsWith(EPISODE_KEY_PREFIX) }
            ?.removePrefix(EPISODE_KEY_PREFIX)
            ?.toLongOrNull()
            ?: return@firstNotNullOfOrNull null
        items.indexOfFirst { it.episode.id == id }
            .takeIf { it >= 0 }
            ?.let { info.index - it }
    }

/**
 * The feed's cards, emitted into the entry screen's own grid in place of the
 * episode rows.
 */
fun LazyGridScope.episodeScrollerItems(
    state: EpisodeScrollerState,
    items: List<EpisodeList.Item>,
    anime: Anime,
    isAnyEpisodeSelected: Boolean,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onEpisodeRenameRequest: (EpisodeList.Item) -> Unit,
    onSubmitEpisodeSearch: () -> Unit,
    isDialogOpen: Boolean,
    /**
     * The horizontal content padding the hosting grid applies to every item,
     * which a card undoes so its margins are the feed's own - see
     * [cancelHorizontalInset].
     */
    horizontalInset: Dp,
) {
    itemsIndexed(
        items = items,
        key = { _, item -> EPISODE_KEY_PREFIX + item.id },
        contentType = { _, _ -> AnimeScreenItem.EPISODE_SCROLLER },
        span = { _, _ -> GridItemSpan(maxLineSpan) },
    ) { index, item ->
        val playback = state.playback
        val card = remember(item) { state.cardRef(item) }
        val source = remember(card) { state.sourceFor(card) }

        // A card holds a player only while it is near the one being looked at,
        // and owns that lifetime itself - see BrowseSourceScroller for why the
        // card rather than the feed decides it.
        val activeIndex = state.windowCentreIndex(items)
        val distance = index - activeIndex
        val shouldLoad = activeIndex >= 0 && distance in -WINDOW_BEHIND..WINDOW_AHEAD
        val tier = when (distance) {
            in -1..1 -> MPVPlayer.CacheTier.Near
            else -> MPVPlayer.CacheTier.Far
        }
        val hasPlayer = playback?.playerFor(card.id) != null

        if (playback != null) {
            LaunchedEffect(playback, card.id) {
                playback.prepareGeometry(card, source)
            }
            LaunchedEffect(playback, card.id, shouldLoad, hasPlayer, tier, state.speed.isFast) {
                when {
                    !shouldLoad -> playback.release(card.id)
                    state.speed.isFast -> Unit
                    else -> playback.acquire(card, source, tier)
                }
            }
            DisposableEffect(playback, card.id) {
                onDispose { playback.release(card.id) }
            }
        }

        EpisodeScrollerCard(
            item = item,
            anime = anime,
            card = card,
            playback = playback,
            isAnyEpisodeSelected = isAnyEpisodeSelected,
            isDialogOpen = isDialogOpen,
            onEpisodeClicked = { episode, alt ->
                onSubmitEpisodeSearch()
                onEpisodeClicked(episode, alt)
            },
            onEpisodeSelected = onEpisodeSelected,
            onDownloadEpisode = onDownloadEpisode,
            onEpisodeRenameRequest = onEpisodeRenameRequest,
            horizontalInset = horizontalInset,
        )
    }
}

/**
 * One card, drawn by the feed's own [ScrollerCard].
 *
 * Only what has to differ differs. The title names the EPISODE rather than the
 * entry, since the entry is the same on every card here. The row's own tap -
 * play this episode - moves to the title, because a tap on the clip drives the
 * player, and the row's double tap on the name stays what it is there: rename.
 * Long press selects, as it does on a row, and the download indicator is the
 * row's.
 *
 * A double tap in the middle of the clip expands the card, exactly as it does
 * in the browse feed, and expanding takes over the instance that is already
 * playing rather than loading anything.
 *
 * The add-to-library button is not drawn: every card here is the same entry,
 * which the entry screen's own toolbar already offers.
 */
@Composable
private fun EpisodeScrollerCard(
    item: EpisodeList.Item,
    anime: Anime,
    card: ScrollerCardRef,
    playback: ScrollerPlaybackState?,
    isAnyEpisodeSelected: Boolean,
    isDialogOpen: Boolean,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onEpisodeRenameRequest: (EpisodeList.Item) -> Unit,
    horizontalInset: Dp,
) {
    val haptic = LocalHapticFeedback.current
    val title = if (anime.displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
        stringResource(AYMR.strings.display_mode_episode, formatEpisodeNumber(item.episode.episodeNumber))
    } else {
        item.displayName
    }

    val onLongClick = {
        if (!isDialogOpen) {
            onEpisodeSelected(item, !item.selected, true)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val onOpen = {
        if (!isDialogOpen) {
            if (isAnyEpisodeSelected) {
                onEpisodeSelected(item, !item.selected, false)
            } else {
                onEpisodeClicked(item.episode, false)
            }
        }
    }

    ScrollerCard(
        anime = card.anime,
        title = title,
        headerActions = {
            // The row's own bookmark mark, which is episode state and has
            // nowhere else on the card to live.
            if (item.episode.bookmark) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            EpisodeDownloadIndicator(
                enabled = !isAnyEpisodeSelected && !anime.isLocal(),
                downloadStateProvider = { item.downloadState },
                downloadProgressProvider = { item.downloadProgress },
                onClick = { onDownloadEpisode?.invoke(listOf(item), it) },
                // AM (FILE_SIZE): the indicator draws a size line only when it
                // is given one, and the card header has no room for it.
                fileSize = null,
            )
        },
        modifier = Modifier
            // The grid's own item inset undone, and the gap the feed puts
            // between its cards put back: the grid stacks items flush.
            .cancelHorizontalInset(horizontalInset)
            .padding(bottom = ScrollerCardSpacing)
            .selectedBackground(item.selected),
        // AM (MERGED_SOURCES): the badge means this entry is a merge child,
        // which is the entry screen's own state rather than this card's.
        inMerge = false,
        isSelected = item.selected,
        player = playback?.playerFor(card.id),
        videoOutput = playback?.videoOutput,
        playback = playback,
        isFullscreen = playback?.fullscreenAnimeId == card.id,
        hasFrame = playback?.hasFrame(card.id) == true,
        aspectRatio = playback?.aspectRatios?.get(card.id),
        cardId = card.id,
        cover = AnimeCover(
            animeId = card.anime.id,
            sourceId = card.anime.source,
            isAnimeFavorite = card.anime.favorite,
            // The episode's own preview where it has one, the entry's cover
            // otherwise - the rule the rows already follow.
            url = item.thumbnailUrl ?: item.episode.previewUrl ?: card.anime.thumbnailUrl,
            lastModified = card.anime.coverLastModified,
        ),
        contentDescription = title,
        onCoverAspectRatio = { ratio -> playback?.onCoverAspectRatio(card.id, ratio) },
        onTitleClick = { onOpen() },
        onTitleLongClick = { onLongClick() },
        // The row's own double tap on the name, unchanged.
        onTitleDoubleClick = { if (!isDialogOpen) onEpisodeRenameRequest(item) },
        onFavoriteClick = null,
        onFavoriteLongClick = null,
        onTagSearch = null,
        onTagGlobalSearch = null,
        onCreatorSearch = null,
        feedActionSource = null,
        onClipTap = { onPlayPauseSpot ->
            when {
                isAnyEpisodeSelected -> onEpisodeSelected(item, !item.selected, false)
                playback != null -> playback.onTap(card.id, onPlayPauseSpot)
            }
        },
        onClipLongClick = { onLongClick() },
        onClipDoubleTap = playback?.let { feed ->
            { zone: ScrollerPlaybackState.TapZone -> feed.onDoubleTap(card.id, zone) }
        },
    )
}

/** Must match the key the rows use, so neither view resets the other's scroll. */
private const val EPISODE_KEY_PREFIX = "episode-"

/** The same settle and window the browse feed uses - see BrowseSourceScroller. */
private const val ACTIVE_CARD_SETTLE_MS = 120L
private const val WINDOW_BEHIND = 2
private const val WINDOW_AHEAD = 2
private const val GEOMETRY_BEHIND = 3
private const val GEOMETRY_AHEAD = 6

// <-- AM (ENTRY_SCROLLER)
