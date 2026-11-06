package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
// AM (PLAYLIST_ROW_STEADY_HEIGHT)
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
// AM (LIBRARY_HEART_ICON)
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.components.DotSeparatorText
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.anime.components.SwipeBox
import eu.kanade.presentation.anime.components.getSwipeAction
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.database.models.EpisodeImpl
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.nowPlayingBackground
import tachiyomi.presentation.core.util.selectedBackground

// AM (PLAYLIST_ROW_STEADY_HEIGHT)
/**
 * The drag handle's touch height. Deliberately below the height of the row's
 * own two lines of text, so the handle can never be what decides how tall a
 * row is.
 */
private val DRAG_HANDLE_HEIGHT = 32.dp


@Composable
fun EpisodeListItem(
    episode: Episode,
    isCurrentEpisode: Boolean,
    title: String,
    date: String?,
    /** Optional parent label; the rest remains the ordinary episode row. */
    groupTitle: String? = null,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    // AM (PLAYER_EPISODE_LIST_SELECTION) -->
    // All four default to the row's previous behaviour, so the cast playlist
    // sheet - the other caller of this component - is untouched.
    //
    // AM (OPEN_ENTRY_ON_DOUBLE_TAP) -->
    /**
     * Opens the entry this episode came from - the merge child on a merged
     * entry. Replaces the open-entry eye this row used to carry: the eye cost
     * a permanent slot on every row to offer something only a merged entry
     * could use, and a double tap costs none.
     *
     * Nullable rather than a no-op: a non-null handler delays every single tap
     * by the double-tap timeout, and the single tap here plays the episode.
     */
    onDoubleClick: (() -> Unit)? = null,
    // <-- AM (OPEN_ENTRY_ON_DOUBLE_TAP)
    // AM (OPEN_ENTRY_MERGED_ONLY) -->
    /**
     * Whether the bookmark/fillermark buttons belong in the row. False for the
     * player's episode list, whose selection bar owns them; the cast playlist
     * sheet - the other caller - has no selection bar and keeps them here.
     */
    marksInRow: Boolean = true,
    // <-- AM (OPEN_ENTRY_MERGED_ONLY)
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
    // AM (PLAYER_MARK_BADGES) -->
    /**
     * The marks to draw. Passed in rather than read off [episode] and kept in
     * local state, which is what made a row ignore any marking it did not do
     * itself - the selection bar's above all. See EpisodeMarkState.
     */
    isBookmarked: Boolean = episode.bookmark,
    isFillermarked: Boolean = episode.fillermark,
    // AM (PLAYLIST_SWIPE_ACTIONS): passed in for the same reason as the two
    // above - the playlist is a snapshot, so a row told to mark itself watched
    // has to be told it worked rather than re-reading a stale Episode.
    isSeen: Boolean = episode.seen,
    // <-- AM (PLAYER_MARK_BADGES)
    // AM (PLAYLIST_LIBRARY_BADGE) -->
    /**
     * Whether this row's source is one the library holds, drawn as a badge of
     * its own. Off by default, and the list decides when to ask for it at all -
     * see the dialog: a badge every row carries says nothing.
     */
    isFromLibraryEntry: Boolean = false,
    // <-- AM (PLAYLIST_LIBRARY_BADGE)
    // AM (QUEUE_GROUPED_PLAYLIST) -->
    /**
     * How deep inside its groups the row sits, drawn as a rule rather than as
     * indent - see playlistNesting. Zero for an ungrouped list, which is what
     * the cast playlist sheet and a one-source queue both are.
     */
    nestingDepth: Int = 0,
    // <-- AM (QUEUE_GROUPED_PLAYLIST)
    // AM (PLAYLIST_SWIPE_ACTIONS) -->
    /**
     * The user's own episode swipe settings, the same two the entry list
     * obeys. Disabled by default, which is what the cast playlist sheet wants:
     * it has no handler to act on a swipe.
     */
    swipeStartAction: LibraryPreferences.EpisodeSwipeAction = LibraryPreferences.EpisodeSwipeAction.Disabled,
    swipeEndAction: LibraryPreferences.EpisodeSwipeAction = LibraryPreferences.EpisodeSwipeAction.Disabled,
    downloadState: Download.State = Download.State.NOT_DOWNLOADED,
    onSwipe: (LibraryPreferences.EpisodeSwipeAction) -> Unit = {},
    // <-- AM (PLAYLIST_SWIPE_ACTIONS)
    // AM (QUEUE_REORDER): supplied by the row's ReorderableItem scope; replaces
    // the open-entry eye while active, just as the entry list's handle does.
    reorderHandleModifier: Modifier? = null,
) {
    var textHeight by remember { mutableStateOf(0) }

    val defaultColor = MaterialTheme.colorScheme.onSurface
    val bookmarkAlpha = if (isBookmarked) 1f else DISABLED_ALPHA
    val bookmarkColor = if (isBookmarked) MaterialTheme.colorScheme.primary else defaultColor
    val fillermarkAlpha = if (isFillermarked) 1f else DISABLED_ALPHA
    val fillermarkColor = if (isFillermarked) MaterialTheme.colorScheme.tertiary else defaultColor
    // AM (PLAYLIST_LIBRARY_BADGE) -->
    // The row's text is no longer tinted by its marks. A mark is already drawn
    // as a badge, so the tint said the same thing twice - and said it badly,
    // since one colour cannot show both marks at once.
    //
    // Where an episode's source sits is a badge too rather than the freed-up
    // tint: the row already carries two states in colour and weight - seen
    // dims it, playing makes it bold italic - so a third meaning in the text
    // colour reads as "this row is odd somehow" rather than as one fact, and
    // it is the one fact here that is about something OUTSIDE the row. A badge
    // says it in the same vocabulary as the marks beside it.
    val episodeColor = defaultColor
    // <-- AM (PLAYLIST_LIBRARY_BADGE)
    // AM (PLAYLIST_SWIPE_ACTIONS)
    val textAlpha = if (isSeen) DISABLED_ALPHA else 1f
    val textWeight = if (isCurrentEpisode) FontWeight.Bold else FontWeight.Normal
    val textStyle = if (isCurrentEpisode) FontStyle.Italic else FontStyle.Normal


    // AM (PLAYLIST_SWIPE_ACTIONS) -->
    // The entry list's own swipe resolution and its own box, so the two lists
    // obey one pair of settings and behave identically under the finger. The
    // box renders nothing extra when both actions are Disabled, which is what
    // keeps the cast sheet's rows as they were.
    val swipeBackground = MaterialTheme.colorScheme.primaryContainer
    val startSwipe = getSwipeAction(
        action = swipeStartAction,
        seen = isSeen,
        bookmark = isBookmarked,
        fillermark = isFillermarked,
        downloadState = downloadState,
        background = swipeBackground,
        onSwipe = { onSwipe(swipeStartAction) },
    )
    val endSwipe = getSwipeAction(
        action = swipeEndAction,
        seen = isSeen,
        bookmark = isBookmarked,
        fillermark = isFillermarked,
        downloadState = downloadState,
        background = swipeBackground,
        onSwipe = { onSwipe(swipeEndAction) },
    )

    SwipeBox(
        start = startSwipe,
        end = endSwipe,
        modifier = Modifier,
    ) {
        // <-- AM (PLAYLIST_SWIPE_ACTIONS)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // AM (PLAYER_EPISODE_LIST_SELECTION) -->
            .selectedBackground(isSelected)
            // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
            // AM (NOW_PLAYING_INDICATOR) -->
            // The same tint the entry's own episode list uses, in the same
            // position in the chain - drawn behind the whole row, under the
            // click ripple and outside the row's padding. The bold italic
            // below marks the same episode; this is the entry list's
            // treatment carried over so the two lists read alike.
            .nowPlayingBackground(isCurrentEpisode)
            // <-- AM (NOW_PLAYING_INDICATOR)
            // AM (PLAYER_EPISODE_LIST_SELECTION) -->
            .combinedClickable(
                onClick = { onEpisodeClicked(episode.id) },
                onLongClick = onLongClick,
                // AM (OPEN_ENTRY_ON_DOUBLE_TAP)
                onDoubleClick = onDoubleClick,
            )
            // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
            // AM (QUEUE_GROUPED_PLAYLIST)
            .playlistNesting(nestingDepth)
            // AM (PLAYLIST_ROW_STEADY_HEIGHT): a touch more than the rows used
            // to carry. The drag handle selection brings in needs somewhere to
            // sit, and a row that grows to make room for it makes the whole
            // list jump and shifts every row out from under the finger that
            // just long-pressed it. So the room is there from the start, and
            // the handle below is sized to fit inside it - half the height the
            // handle used to add, which is enough to hold it without stretching
            // the list out to where fewer rows fit on screen.
            .padding(vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // AM (PLAYER_EPISODE_LIST_SELECTION) -->
        // No checkbox: selection is shown by the row tint alone, the way the
        // entry's own episode list does it. The leading button stays put and
        // goes inert while selecting, mirroring how that list disables the
        // download indicator rather than swapping it out - a row that changes
        // shape on selection makes the list jump.
        if (reorderHandleModifier != null) {
            // AM (PLAYLIST_ROW_STEADY_HEIGHT): a box narrower than it is tall
            // rather than a 48dp square. The square was taller than the row's
            // own content, so selecting grew every row; this is shorter than
            // the content and so changes nothing about the row's height, while
            // keeping the full width to aim at - and a drag is a deliberate
            // press and slide, which does not need a 48dp target in the one
            // direction the row cannot spare.
            Box(
                modifier = reorderHandleModifier
                    .width(48.dp)
                    .height(DRAG_HANDLE_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = stringResource(AMMR.strings.am_episode_drag_handle),
                    modifier = Modifier.size(24.dp),
                )
            }
            // AM (OPEN_ENTRY_ON_DOUBLE_TAP) -->
            // Where the eye used to sit. The player's list leaves this slot
            // empty rather than falling back to the row marks: those live in
            // its selection bar, and putting them back here would duplicate
            // them and make the two lists read differently.
            // <-- AM (OPEN_ENTRY_ON_DOUBLE_TAP)
        } else if (marksInRow) {
            IconButton(onClick = { onBookmarkClicked(episode.id, !isBookmarked) }) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = bookmarkColor,
                    modifier = Modifier
                        .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp })
                        .alpha(bookmarkAlpha),
                )
            }

            IconButton(onClick = { onFillermarkClicked(episode.id, !isFillermarked) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Label,
                    contentDescription = null,
                    tint = fillermarkColor,
                    modifier = Modifier
                        .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp })
                        .alpha(fillermarkAlpha),
                )
            }
        }
        // <-- AM (PLAYER_EPISODE_LIST_SELECTION)

        Spacer(modifier = Modifier.width(2.dp))

        Column {
            if (groupTitle != null) {
                Text(
                    text = groupTitle,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = episodeColor,
                    modifier = Modifier.alpha(textAlpha),
                    fontWeight = textWeight,
                    fontStyle = textStyle,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = episodeColor,
                modifier = Modifier.alpha(textAlpha),
                onTextLayout = { textHeight = it.size.height },
                fontWeight = textWeight,
                fontStyle = textStyle,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (date != null) {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = episodeColor,
                        modifier = Modifier.alpha(textAlpha),
                        fontWeight = textWeight,
                        fontStyle = textStyle,
                    )
                    if (episode.scanlator != null) {
                        DotSeparatorText(
                            modifier = Modifier.alpha(textAlpha),
                        )
                    }
                }
                if (episode.scanlator != null) {
                    Text(
                        text = episode.scanlator!!,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = episodeColor,
                        modifier = Modifier.alpha(textAlpha),
                        fontWeight = textWeight,
                        fontStyle = textStyle,
                    )
                }
                // AM (PLAYER_MARK_BADGES) -->
                // After the timestamp rather than leading the line: a badge is
                // there only when the mark is, so leading with them moved the
                // date left and right from row to row. The date is on every
                // row, so anchoring to it keeps the line steady and the badges
                // line up down the list.
                if (isBookmarked) {
                    MarkBadge(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (isFillermarked) {
                    MarkBadge(
                        imageVector = Icons.AutoMirrored.Filled.Label,
                        contentDescription = stringResource(AYMR.strings.action_filter_fillermarked),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                // <-- AM (PLAYER_MARK_BADGES)
                // AM (PLAYLIST_LIBRARY_BADGE) -->
                // The same heart Browse marks a library result with, so "this
                // is in your library" looks the same wherever it is said - and
                // is not read as another mark on the episode, which the two
                // badges beside it are.
                if (isFromLibraryEntry) {
                    MarkBadge(
                        // AM (LIBRARY_HEART_ICON)
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = stringResource(MR.strings.in_library),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                // <-- AM (PLAYLIST_LIBRARY_BADGE)
            }
        }
    }
    // AM (PLAYLIST_SWIPE_ACTIONS)
    }
}

// AM (PLAYER_MARK_BADGES) -->
/** A small marked-state chip for the subtitle line. Icon only - the label is its description. */
@Composable
private fun MarkBadge(
    imageVector: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color,
) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(container)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(12.dp),
        )
    }
}
// <-- AM (PLAYER_MARK_BADGES)

@Composable
@PreviewLightDark
private fun EpisodeListItemPreview() {
    TachiyomiPreviewTheme {
        EpisodeListItem(
            episode = EpisodeImpl().also {
                it.name = "Open Fire"
                it.episode_number = 2f
                it.date_upload = 1191967200000
            },
            isCurrentEpisode = false,
            title = "Open Fire",
            date = "2007-10-10",
            onBookmarkClicked = { _, _ -> },
            onFillermarkClicked = { _, _ -> },
            onEpisodeClicked = { },
        )
    }
}
