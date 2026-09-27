package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.components.DotSeparatorText
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.database.models.EpisodeImpl
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.nowPlayingBackground
import tachiyomi.presentation.core.util.selectedBackground

@Composable
fun EpisodeListItem(
    episode: Episode,
    isCurrentEpisode: Boolean,
    title: String,
    date: String?,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    // AM (PLAYER_EPISODE_LIST_SELECTION) -->
    // All four default to the row's previous behaviour, so the cast playlist
    // sheet - the other caller of this component - is untouched.
    //
    // A non-null onOpenEntryClicked swaps the two mark buttons for an open-
    // entry button: the marks move into the list's selection bar, where they
    // act on a selection rather than on one row. Passing null keeps the marks
    // in the row.
    onOpenEntryClicked: ((Long?) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
) {
    var isBookmarked by remember { mutableStateOf(episode.bookmark) }
    var isFillermarked by remember { mutableStateOf(episode.fillermark) }
    var textHeight by remember { mutableStateOf(0) }

    val defaultColor = MaterialTheme.colorScheme.onSurface
    val bookmarkAlpha = if (isBookmarked) 1f else DISABLED_ALPHA
    val bookmarkColor = if (isBookmarked) MaterialTheme.colorScheme.primary else defaultColor
    val fillermarkAlpha = if (isFillermarked) 1f else DISABLED_ALPHA
    val fillermarkColor = if (isFillermarked) MaterialTheme.colorScheme.tertiary else defaultColor
    val episodeColor = if (isBookmarked) {
        bookmarkColor
    } else if (isFillermarked) {
        fillermarkColor
    } else {
        defaultColor
    }
    val textAlpha = if (episode.seen) DISABLED_ALPHA else 1f
    val textWeight = if (isCurrentEpisode) FontWeight.Bold else FontWeight.Normal
    val textStyle = if (isCurrentEpisode) FontStyle.Italic else FontStyle.Normal

    val clickBookmark: (Boolean) -> Unit = { bookmarked ->
        episode.bookmark = bookmarked
        isBookmarked = bookmarked
        onBookmarkClicked(episode.id, bookmarked)
    }

    val clickFillermark: (Boolean) -> Unit = { fillermarked ->
        episode.fillermark = fillermarked
        isFillermarked = fillermarked
        onFillermarkClicked(episode.id, fillermarked)
    }

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
            )
            // <-- AM (PLAYER_EPISODE_LIST_SELECTION)
            .padding(vertical = MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // AM (PLAYER_EPISODE_LIST_SELECTION) -->
        // No checkbox: selection is shown by the row tint alone, the way the
        // entry's own episode list does it. The leading button stays put and
        // goes inert while selecting, mirroring how that list disables the
        // download indicator rather than swapping it out - a row that changes
        // shape on selection makes the list jump.
        if (onOpenEntryClicked != null) {
            IconButton(
                onClick = { onOpenEntryClicked(episode.id) },
                enabled = !selectionMode,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = stringResource(AMMR.strings.am_action_open_entry),
                )
            }
        } else {
            IconButton(onClick = { clickBookmark(!isBookmarked) }) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = bookmarkColor,
                    modifier = Modifier
                        .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp })
                        .alpha(bookmarkAlpha),
                )
            }

            IconButton(onClick = { clickFillermark(!isFillermarked) }) {
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
            }
        }
    }
}

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
