package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
// AM (DOUBLE_TAP_QUEUE_TAIL_GUARD)
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
// AM (DOUBLE_TAP_QUEUE_TAIL_GUARD)
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.anime.components.AnimeCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.domain.anime.model.AnimeCover as AnimeCoverModel

// AM (DOUBLE_TAP_QUEUE_TAIL_GUARD) -->
/**
 * [combinedClickable] with the tail of a double tap absorbed.
 *
 * Compose resolves a triple tap as a double tap followed by a fresh single tap:
 * the third tap begins a new gesture and commits as [onClick] one double-tap
 * timeout later. On these items that means a slipped finger queues the entry
 * and then opens it, which is the pair of things the user did not ask for.
 *
 * The entry's episode list answers the same problem with a state test - by the
 * time its third tap lands, the rename dialog the double tap opened is in state
 * and the tap is dropped (see AnimeScreen). Queueing opens nothing, so there is
 * no such state to test against; what is left is the gesture's own fact, that a
 * tap arriving while the double tap it belongs to is still resolving is part of
 * that gesture rather than a click of its own. The window is the platform's
 * double-tap timeout doubled, which is what it takes to span a third tap landing
 * at the end of the timeout plus its own timeout before it commits - the same
 * reasoning behind DummyPipWindow's extra-tap suppression.
 *
 * [onClick] is left untouched where there is no [onDoubleClick]: with no double
 * tap there is no tail, and a single tap commits immediately.
 */
@Composable
internal fun Modifier.animeItemClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
): Modifier {
    if (onDoubleClick == null) {
        return combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    val tailWindowMs = LocalViewConfiguration.current.doubleTapTimeoutMillis * 2
    // A plain holder rather than a State: the guard is read inside the gesture
    // callbacks, never during composition, so writing it has nothing to redraw.
    val guard = remember { DoubleTapTailGuard() }
    return combinedClickable(
        onClick = {
            if (android.os.SystemClock.uptimeMillis() - guard.lastDoubleTapAt >= tailWindowMs) {
                onClick()
            }
        },
        onLongClick = onLongClick,
        onDoubleClick = {
            guard.lastDoubleTapAt = android.os.SystemClock.uptimeMillis()
            onDoubleClick()
        },
    )
}

private class DoubleTapTailGuard {
    var lastDoubleTapAt = 0L
}
// <-- AM (DOUBLE_TAP_QUEUE_TAIL_GUARD)

object CommonAnimeItemDefaults {
    val GridHorizontalSpacer = 4.dp
    val GridVerticalSpacer = 4.dp

    @Suppress("ConstPropertyName")
    const val BrowseFavoriteCoverAlpha = 0.34f
}

private val ContinueWatchingButtonSizeSmall = 28.dp
private val ContinueWatchingButtonSizeLarge = 32.dp

private val ContinueWatchingButtonIconSizeSmall = 16.dp
private val ContinueWatchingButtonIconSizeLarge = 20.dp

private val ContinueWatchingButtonGridPadding = 6.dp
private val ContinueWatchingButtonListSpacing = 8.dp

private const val GRID_SELECTED_COVER_ALPHA = 0.76f

/**
 * Layout of grid list item with title overlaying the cover.
 * Accepts null [title] for a cover-only view.
 */
@Composable
fun AnimeCompactGridItem(
    coverData: AnimeCoverModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    // AM (DOUBLE_TAP_QUEUE)
    onDoubleClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    title: String? = null,
    onClickContinueWatching: (() -> Unit)? = null,
    coverAlpha: Float = 1f,
    coverBadgeStart: @Composable (RowScope.() -> Unit)? = null,
    coverBadgeEnd: @Composable (RowScope.() -> Unit)? = null,
) {
    GridItemSelectable(
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        // AM (DOUBLE_TAP_QUEUE)
        onDoubleClick = onDoubleClick,
    ) {
        AnimeGridCover(
            cover = {
                AnimeCover.Book(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha),
                    data = coverData,
                )
            },
            badgesStart = coverBadgeStart,
            badgesEnd = coverBadgeEnd,
            content = {
                if (title != null) {
                    CoverTextOverlay(
                        title = title,
                        onClickContinueWatching = onClickContinueWatching,
                    )
                } else if (onClickContinueWatching != null) {
                    ContinueWatchingButton(
                        size = ContinueWatchingButtonSizeLarge,
                        iconSize = ContinueWatchingButtonIconSizeLarge,
                        onClick = onClickContinueWatching,
                        modifier = Modifier
                            .padding(ContinueWatchingButtonGridPadding)
                            .align(Alignment.BottomEnd),
                    )
                }
            },
        )
    }
}

/**
 * Title overlay for [AnimeCompactGridItem]
 */
@Composable
private fun BoxScope.CoverTextOverlay(
    title: String,
    onClickContinueWatching: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color(0xAA000000),
                ),
            )
            .fillMaxHeight(0.33f)
            .fillMaxWidth()
            .align(Alignment.BottomCenter),
    )
    Row(
        modifier = Modifier.align(Alignment.BottomStart),
        verticalAlignment = Alignment.Bottom,
    ) {
        GridItemTitle(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
            title = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = Color.White,
                shadow = Shadow(
                    color = Color.Black,
                    blurRadius = 4f,
                ),
            ),
            minLines = 1,
        )
        if (onClickContinueWatching != null) {
            ContinueWatchingButton(
                size = ContinueWatchingButtonSizeSmall,
                iconSize = ContinueWatchingButtonIconSizeSmall,
                onClick = onClickContinueWatching,
                modifier = Modifier.padding(
                    end = ContinueWatchingButtonGridPadding,
                    bottom = ContinueWatchingButtonGridPadding,
                ),
            )
        }
    }
}

/**
 * Layout of grid list item with title below the cover.
 */
@Composable
fun AnimeComfortableGridItem(
    coverData: AnimeCoverModel,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    // AM (DOUBLE_TAP_QUEUE)
    onDoubleClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    titleMaxLines: Int = 2,
    coverAlpha: Float = 1f,
    coverBadgeStart: (@Composable RowScope.() -> Unit)? = null,
    coverBadgeEnd: (@Composable RowScope.() -> Unit)? = null,
    onClickContinueWatching: (() -> Unit)? = null,
) {
    GridItemSelectable(
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        // AM (DOUBLE_TAP_QUEUE)
        onDoubleClick = onDoubleClick,
    ) {
        Column {
            AnimeGridCover(
                cover = {
                    AnimeCover.Book(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha),
                        data = coverData,
                    )
                },
                badgesStart = coverBadgeStart,
                badgesEnd = coverBadgeEnd,
                content = {
                    if (onClickContinueWatching != null) {
                        ContinueWatchingButton(
                            size = ContinueWatchingButtonSizeLarge,
                            iconSize = ContinueWatchingButtonIconSizeLarge,
                            onClick = onClickContinueWatching,
                            modifier = Modifier
                                .padding(ContinueWatchingButtonGridPadding)
                                .align(Alignment.BottomEnd),
                        )
                    }
                },
            )
            GridItemTitle(
                modifier = Modifier.padding(4.dp),
                title = title,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = titleMaxLines,
            )
        }
    }
}

/**
 * Common cover layout to add contents to be drawn on top of the cover.
 */
@Composable
private fun AnimeGridCover(
    modifier: Modifier = Modifier,
    cover: @Composable BoxScope.() -> Unit = {},
    badgesStart: (@Composable RowScope.() -> Unit)? = null,
    badgesEnd: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(AnimeCover.Book.ratio),
    ) {
        cover()
        content?.invoke(this)
        if (badgesStart != null) {
            BadgeGroup(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopStart),
                content = badgesStart,
            )
        }

        if (badgesEnd != null) {
            BadgeGroup(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopEnd),
                content = badgesEnd,
            )
        }
    }
}

@Composable
private fun GridItemTitle(
    title: String,
    style: TextStyle,
    minLines: Int,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

/**
 * Wrapper for grid items to handle selection state, click and long click.
 */
@Composable
private fun GridItemSelectable(
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    // AM (DOUBLE_TAP_QUEUE) -->
    /**
     * Null where the surface has nothing for a double tap, and deliberately so
     * rather than a no-op lambda: a non-null handler makes every single tap on
     * the item wait out the double-tap timeout before it opens anything, since
     * the gesture detector cannot know a second tap is not coming. Only the
     * surfaces that actually offer the gesture pay for it.
     */
    onDoubleClick: (() -> Unit)? = null,
    // <-- AM (DOUBLE_TAP_QUEUE)
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            // AM (DOUBLE_TAP_QUEUE) / AM (DOUBLE_TAP_QUEUE_TAIL_GUARD)
            .animeItemClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick,
            )
            .selectedOutline(isSelected = isSelected, color = MaterialTheme.colorScheme.secondary)
            .padding(4.dp),
    ) {
        val contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onSecondary
        } else {
            LocalContentColor.current
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * @see GridItemSelectable
 */
private fun Modifier.selectedOutline(
    isSelected: Boolean,
    color: Color,
) = drawBehind { if (isSelected) drawRect(color = color) }

/**
 * Layout of list item.
 */
@Composable
fun AnimeListItem(
    coverData: AnimeCoverModel,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    badge: @Composable (RowScope.() -> Unit),
    // AM (DOUBLE_TAP_QUEUE): see GridItemSelectable for why this is nullable.
    onDoubleClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    coverAlpha: Float = 1f,
    onClickContinueWatching: (() -> Unit)? = null,
    // AY -->
    entries: Int = 0,
    containerHeight: Int = 0,
    modifier: Modifier = Modifier,
    // <-- AY
) {
    Row(
        modifier = modifier
            .selectedBackground(isSelected)
            .height(
                // AY -->
                when (entries) {
                    0 -> 76.dp
                    else -> {
                        val density = LocalDensity.current
                        with(density) { (containerHeight / entries).toDp() } - (3 / entries).dp
                    }
                },
                // <-- AY
            )
            // AM (DOUBLE_TAP_QUEUE) / AM (DOUBLE_TAP_QUEUE_TAIL_GUARD)
            .animeItemClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick,
            )
            // AY -->
            .padding(horizontal = 16.dp, vertical = 3.dp),
        // <-- AY
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // AY -->
        AnimeCover.Book(
            // <-- AY
            modifier = Modifier
                .fillMaxHeight()
                .alpha(coverAlpha),
            data = coverData,
        )
        Text(
            text = title,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f),
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        BadgeGroup(content = badge)
        if (onClickContinueWatching != null) {
            ContinueWatchingButton(
                size = ContinueWatchingButtonSizeSmall,
                iconSize = ContinueWatchingButtonIconSizeSmall,
                onClick = onClickContinueWatching,
                modifier = Modifier.padding(start = ContinueWatchingButtonListSpacing),
            )
        }
    }
}

@Composable
private fun ContinueWatchingButton(
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
            ),
            modifier = Modifier.size(size),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(MR.strings.action_resume),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
