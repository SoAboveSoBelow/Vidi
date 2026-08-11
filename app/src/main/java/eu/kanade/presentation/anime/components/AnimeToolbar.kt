package eu.kanade.presentation.anime.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.DownloadAction
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DownloadDropdownMenu
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.anime.model.EpisodeViewMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.showSoftKeyboard

@Composable
fun AnimeToolbar(
    title: String,
    hasFilters: Boolean,
    navigateUp: () -> Unit,
    onClickFilter: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    onClickEditCategory: (() -> Unit)?,
    onClickRefresh: () -> Unit,
    onClickMigrate: (() -> Unit)?,
    // AM (CLEAR_ANIME) -->
    onClickClearAnime: () -> Unit,
    // <-- AM (CLEAR_ANIME)
    // AY -->
    onClickSettings: (() -> Unit)?,
    onClickSkipIntro: (() -> Unit)?,
    // <-- AY
    onClickEditNotes: () -> Unit,
    // AM (CUSTOM_INFORMATION) -->
    onClickEditInfo: (() -> Unit)?,
    // <-- AM (CUSTOM_INFORMATION)
    // AM (EPISODE_VIEW_MODE) -->
    episodeViewMode: EpisodeViewMode,
    onEpisodeViewModeSelected: (EpisodeViewMode) -> Unit,
    // <-- AM (EPISODE_VIEW_MODE)

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,

    // AM (EPISODE_SEARCH) -->
    episodeSearchQuery: String?,
    onEpisodeSearchQueryChange: (String?) -> Unit,
    // <-- AM (EPISODE_SEARCH)

    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val isActionMode = actionModeCounter > 0
    val isSearching = episodeSearchQuery != null
    // AM (EPISODE_VIEW_MODE) -->
    var episodeViewModeDialogShown by remember { mutableStateOf(false) }
    // <-- AM (EPISODE_VIEW_MODE)
    val searchFocusRequester = remember { FocusRequester() }
    AppBar(
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else if (!isSearching) {
                AppBarTitle(title, modifier = Modifier.alpha(titleAlphaProvider()))
            } else {
                val keyboardController = LocalSoftwareKeyboardController.current
                val focusManager = LocalFocusManager.current

                val clearFocus: () -> Unit = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    focusManager.moveFocus(FocusDirection.Next)
                }

                BasicTextField(
                    value = episodeSearchQuery.orEmpty(),
                    onValueChange = onEpisodeSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester)
                        .runOnEnterKeyPressed(action = clearFocus)
                        .showSoftKeyboard(remember { episodeSearchQuery.isNullOrEmpty() })
                        .clearFocusOnSoftKeyboardHide(),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Normal,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { clearFocus() }),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    decorationBox = { innerTextField ->
                        TextFieldDefaults.DecorationBox(
                            value = episodeSearchQuery.orEmpty(),
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = remember { MutableInteractionSource() },
                            placeholder = {
                                Text(
                                    modifier = Modifier.secondaryItemAlpha(),
                                    text = stringResource(AMMR.strings.action_search_episodes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Normal,
                                    ),
                                )
                            },
                            container = {},
                        )
                    },
                )
            }
        },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isActionMode) 1f else backgroundAlphaProvider()),
        navigateUp = if (isSearching) {
            { onEpisodeSearchQueryChange(null) }
        } else {
            navigateUp
        },
        actions = {
            var downloadExpanded by remember { mutableStateOf(false) }
            if (onClickDownload != null) {
                val onDismissRequest = { downloadExpanded = false }
                DownloadDropdownMenu(
                    expanded = downloadExpanded,
                    onDismissRequest = onDismissRequest,
                    onDownloadClicked = onClickDownload,
                )
            }

            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder().apply {
                    if (isActionMode) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        )
                        return@apply
                    }
                    // AM (EPISODE_SEARCH) -->
                    if (!isSearching) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_search),
                                icon = Icons.Outlined.Search,
                                onClick = { onEpisodeSearchQueryChange("") },
                            ),
                        )
                    } else if (episodeSearchQuery.orEmpty().isNotEmpty()) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_reset),
                                icon = Icons.Outlined.Close,
                                onClick = {
                                    onEpisodeSearchQueryChange("")
                                    searchFocusRequester.requestFocus()
                                },
                            ),
                        )
                    }
                    // <-- AM (EPISODE_SEARCH)
                    if (onClickDownload != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.manga_download),
                                icon = Icons.Outlined.Download,
                                onClick = { downloadExpanded = !downloadExpanded },
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            iconTint = filterTint,
                            onClick = onClickFilter,
                        ),
                    )
                    // AY -->
                    if (onClickSkipIntro != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(AYMR.strings.action_change_intro_length),
                                onClick = onClickSkipIntro,
                            ),
                        )
                    }
                    // <-- AY
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_webview_refresh),
                            onClick = onClickRefresh,
                        ),
                    )
                    if (onClickEditCategory != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_edit_categories),
                                onClick = onClickEditCategory,
                            ),
                        )
                    }
                    if (onClickMigrate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_migrate),
                                onClick = onClickMigrate,
                            ),
                        )
                    }
                    // AM (EPISODE_VIEW_MODE) -->
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(AMMR.strings.am_pref_default_view),
                            onClick = { episodeViewModeDialogShown = true },
                        ),
                    )
                    // <-- AM (EPISODE_VIEW_MODE)
                    // AM (CLEAR_ANIME) -->
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(AMMR.strings.action_clear_anime),
                            onClick = onClickClearAnime,
                        ),
                    )
                    // <-- AM (CLEAR_ANIME)
                    if (onClickShare != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = onClickShare,
                            ),
                        )
                    }
                    // AM (CUSTOM_INFORMATION) -->
                    if (onClickEditInfo != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(AMMR.strings.action_edit_info),
                                onClick = onClickEditInfo,
                            ),
                        )
                    }
                    // <-- AM (CUSTOM_INFORMATION)
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_notes),
                            onClick = onClickEditNotes,
                        ),
                    )
                    // AY -->
                    if (onClickSettings != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(AYMR.strings.settings),
                                onClick = onClickSettings,
                            ),
                        )
                    }
                    // <-- AY
                }
                    .build(),
            )
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
    )

    // AM (EPISODE_VIEW_MODE) -->
    if (episodeViewModeDialogShown) {
        EpisodeViewModeDialog(
            selected = episodeViewMode,
            onDismissRequest = { episodeViewModeDialogShown = false },
            onSelected = {
                onEpisodeViewModeSelected(it)
                episodeViewModeDialogShown = false
            },
        )
    }
    // <-- AM (EPISODE_VIEW_MODE)
}

// AM (EPISODE_VIEW_MODE) -->
@Composable
private fun EpisodeViewModeDialog(
    selected: EpisodeViewMode,
    onDismissRequest: () -> Unit,
    onSelected: (EpisodeViewMode) -> Unit,
) {
    val entries = linkedMapOf(
        EpisodeViewMode.SIMPLIFIED to stringResource(AMMR.strings.am_pref_default_view_simplified),
        EpisodeViewMode.PREVIEW to stringResource(AMMR.strings.am_pref_default_view_preview),
        EpisodeViewMode.MINIMAL to stringResource(AMMR.strings.am_pref_default_view_minimal),
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(AMMR.strings.am_pref_default_view)) },
        text = {
            Column {
                entries.forEach { (mode, label) ->
                    val isSelected = mode == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .selectable(
                                selected = isSelected,
                                onClick = { if (!isSelected) onSelected(mode) },
                            )
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize(),
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge.merge(),
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
// <-- AM (EPISODE_VIEW_MODE)
