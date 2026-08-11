package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.components.DotSeparatorText
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.util.lang.toRelativeString
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.showSoftKeyboard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun EpisodeListDialog(
    displayMode: Long?,
    currentEpisodeIndex: Int,
    episodeList: List<Episode>,
    dateRelativeTime: Boolean,
    dateFormat: String,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf<String?>(null) }

    val reversedEpisodeList = remember(episodeList) { episodeList.reversed() }
    val filteredEpisodeList = remember(reversedEpisodeList, searchQuery) {
        val query = searchQuery
        if (query.isNullOrBlank()) {
            reversedEpisodeList
        } else {
            reversedEpisodeList.filter { episode ->
                episode.name.contains(query, ignoreCase = true) ||
                    formatEpisodeNumber(episode.episode_number.toDouble()).contains(query, ignoreCase = true)
            }
        }
    }

    val itemScrollIndex = (episodeList.size - currentEpisodeIndex) - 1
    val episodeListState = rememberLazyListState(initialFirstVisibleItemIndex = itemScrollIndex)
    val dateFormatter = remember(dateFormat) { UiPreferences.dateFormat(dateFormat) }

    PlayerDialog(
        title = stringResource(AYMR.strings.episodes),
        modifier = Modifier.fillMaxHeight(fraction = 0.8F).fillMaxWidth(fraction = 0.8F),
        onDismissRequest = onDismissRequest,
        titleContent = {
            EpisodeListDialogHeader(
                title = stringResource(AYMR.strings.episodes),
                searchQuery = searchQuery,
                onChangeSearchQuery = { searchQuery = it },
            )
        },
    ) {
        VerticalFastScroller(
            listState = episodeListState,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                state = episodeListState,
            ) {
                items(
                    items = filteredEpisodeList,
                    key = { "episode-${it.id}" },
                    contentType = { "episode" },
                ) { episode ->

                    val isCurrentEpisode = episode.id == episodeList[currentEpisodeIndex].id

                    val title = if (displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                        stringResource(
                            AYMR.strings.display_mode_episode,
                            formatEpisodeNumber(episode.episode_number.toDouble()),
                        )
                    } else {
                        episode.name
                    }

                    val date = episode.date_upload
                        .takeIf { it > 0L }
                        ?.let {
                            LocalDate.ofInstant(
                                Instant.ofEpochMilli(it),
                                ZoneId.systemDefault(),
                            ).toRelativeString(
                                context = context,
                                relative = dateRelativeTime,
                                dateFormat = dateFormatter,
                            )
                        } ?: ""

                    EpisodeListItem(
                        episode = episode,
                        isCurrentEpisode = isCurrentEpisode,
                        title = title,
                        date = date,
                        onBookmarkClicked = onBookmarkClicked,
                        onFillermarkClicked = onFillermarkClicked,
                        onEpisodeClicked = onEpisodeClicked,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeListDialogHeader(
    title: String,
    searchQuery: String?,
    onChangeSearchQuery: (String?) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchQuery == null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = { onChangeSearchQuery("") }) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(MR.strings.action_search),
                )
            }
        } else {
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            val clearFocus: () -> Unit = {
                focusManager.clearFocus()
                keyboardController?.hide()
                focusManager.moveFocus(FocusDirection.Next)
            }

            BasicTextField(
                value = searchQuery,
                onValueChange = onChangeSearchQuery,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .runOnEnterKeyPressed(action = clearFocus)
                    .showSoftKeyboard(remember { searchQuery.isEmpty() })
                    .clearFocusOnSoftKeyboardHide(),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Normal,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { clearFocus() }),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                decorationBox = { innerTextField ->
                    TextFieldDefaults.DecorationBox(
                        value = searchQuery,
                        innerTextField = innerTextField,
                        enabled = true,
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        interactionSource = remember { MutableInteractionSource() },
                        placeholder = {
                            Text(
                                modifier = Modifier.secondaryItemAlpha(),
                                text = stringResource(MR.strings.action_search_hint),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Normal,
                                ),
                            )
                        },
                        container = {},
                    )
                },
            )

            IconButton(onClick = { onChangeSearchQuery(null) }) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_reset),
                )
            }
        }
    }
}

@Composable
private fun EpisodeListItem(
    episode: Episode,
    isCurrentEpisode: Boolean,
    title: String,
    date: String?,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onFillermarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
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
            .clickable(onClick = { onEpisodeClicked(episode.id) })
            .padding(vertical = MaterialTheme.padding.extraSmall),
    ) {
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
