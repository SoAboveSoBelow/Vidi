package aniyomi.ui.browse.scroller

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import aniyomi.source.js.JsProjectSource
import eu.kanade.presentation.anime.components.TagActionsMenu
import eu.kanade.presentation.anime.components.TagsChip
import eu.kanade.presentation.browse.components.InMergeBadge
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover

// AM (ENTRY_SCROLLER) -->
// Moved here from BrowseSourceScroller, which wrote it, unchanged but for the
// three parameters marked below. The entry screen's episode feed draws THIS
// card rather than one of its own, so the two feeds cannot look or behave
// differently - what differs between them is which card is which, and that is
// ScrollerCardRef's business, not the card's.
// <-- AM (ENTRY_SCROLLER)

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
fun ScrollerCard(
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
    // AM (BROWSE_SCROLLER_FRAME_WATCH)
    hasFrame: Boolean,
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
    // AM (ENTRY_SCROLLER) -->
    // Everything a feed other than a source's own listing needs this card to
    // do differently, and nothing else. Defaulted to what the listing feed
    // already passed, so that feed's call is unchanged.
    /**
     * What the header names this card, where that is not the entry's own
     * title: an episode feed's cards are all one entry, whose title would then
     * be the same on every card and say nothing.
     */
    title: String? = null,
    /** The long press a title carries where the feed makes it selectable. */
    onTitleLongClick: (() -> Unit)? = null,
    /**
     * Buttons for the header's action end, beside the favourite button. What
     * belongs there is the feed's business: a listing card has nothing to add,
     * an episode card its rename and download actions.
     */
    headerActions: @Composable () -> Unit = {},
    /**
     * The feed's key for this card, which is the entry's id only in a listing
     * feed - see [ScrollerCardRef].
     */
    cardId: Long = anime.id,
    cover: Any? = AnimeCover(
        animeId = anime.id,
        sourceId = anime.source,
        isAnimeFavorite = anime.favorite,
        url = anime.thumbnailUrl,
        lastModified = anime.coverLastModified,
    ),
    contentDescription: String? = title ?: anime.title,
    modifier: Modifier = Modifier,
    // <-- AM (ENTRY_SCROLLER)
) {
    // AM (ENTRY_SCROLLER): [modifier] outside fillMaxWidth, so a host that has
    // to widen the card past its own item width can.
    Column(modifier = Modifier.then(modifier).fillMaxWidth()) {
        ScrollerCardHeader(
            anime = anime,
            // AM (ENTRY_SCROLLER) -->
            title = title,
            onTitleLongClick = onTitleLongClick,
            headerActions = headerActions,
            // <-- AM (ENTRY_SCROLLER)
            // AM (MERGED_SOURCES)
            inMerge = inMerge,
            onTitleClick = onTitleClick,
            onTitleDoubleClick = onTitleDoubleClick,
            onFavoriteClick = onFavoriteClick,
            // AM (BROWSE_SCROLLER_CARD_ACTIONS)
            onFavoriteLongClick = onFavoriteLongClick,
            onCreatorSearch = onCreatorSearch,
        )

        ScrollerClipBox(
            // AM (ENTRY_SCROLLER)
            cardId = cardId,
            player = player,
            videoOutput = videoOutput,
            playback = playback,
            isFullscreen = isFullscreen,
            hasFrame = hasFrame,
            aspectRatio = aspectRatio,
            // AM (ENTRY_SCROLLER)
            cover = cover,
            contentDescription = contentDescription,
            isSelected = isSelected,
            onCoverAspectRatio = onCoverAspectRatio,
            onClipTap = onClipTap,
            onClipLongClick = onClipLongClick,
            onClipDoubleTap = onClipDoubleTap,
        )

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
    // AM (ENTRY_SCROLLER) -->
    title: String?,
    onTitleLongClick: (() -> Unit)?,
    headerActions: @Composable () -> Unit,
    // <-- AM (ENTRY_SCROLLER)
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
                // AM (ENTRY_SCROLLER)
                text = title ?: anime.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // Underlined so it reads as the link it is, now that tapping
                // the clip does not navigate.
                textDecoration = TextDecoration.Underline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // AM (ENTRY_SCROLLER): onLongPress added, for a feed whose
                // cards are selectable - the listing feed passes none.
                modifier = Modifier.pointerInput(onTitleClick, onTitleDoubleClick, onTitleLongClick) {
                    detectTapGestures(
                        onTap = { onTitleClick() },
                        onDoubleTap = onTitleDoubleClick?.let { handler -> { handler() } },
                        onLongPress = onTitleLongClick?.let { handler -> { handler() } },
                    )
                },
            )
        }

        // AM (MERGED_SOURCES): still a badge, because being a merge child is
        // a state rather than an action.
        InMergeBadge(enabled = !anime.favorite && inMerge)

        // AM (ENTRY_SCROLLER)
        headerActions()

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

// AM (ENTRY_SCROLLER) -->
/**
 * Draws this card [inset] wider on each side than the item it was given.
 *
 * A card's only horizontal margin is [ScrollerCardPadding], which is what the
 * browse feed looks like because its list has no horizontal content padding of
 * its own. The entry screen's grid insets every item by GRID_PADDING before a
 * card is reached, which would leave the video narrower there than in the feed
 * it is supposed to be - visibly a different card rather than the same one.
 *
 * Measured against widened constraints and placed back over the inset, since
 * Compose has no negative padding. The size reported to the grid is unchanged,
 * so nothing else in the list moves.
 */
fun Modifier.cancelHorizontalInset(inset: Dp) = this.layout { measurable, constraints ->
    val edge = inset.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = edge * 2))
    layout(placeable.width - edge * 2, placeable.height) {
        placeable.place(-edge, 0)
    }
}
// <-- AM (ENTRY_SCROLLER)

private val ScrollerHeaderTopPadding = 16.dp
private val ScrollerHeaderBottomPadding = 12.dp
private val ScrollerHeaderGap = 12.dp
private val ScrollerCreatorGap = 2.dp

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
