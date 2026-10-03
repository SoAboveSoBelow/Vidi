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
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.SelectAll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.DownloadAction
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DownloadDropdownMenu
import tachiyomi.domain.anime.model.EpisodeViewMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha

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
    // AM (MERGE_SETTINGS) -->
    onClickMergeSettings: (() -> Unit)?,
    // <-- AM (MERGE_SETTINGS)
    // AM (EPISODE_VIEW_MODE) -->
    episodeViewMode: EpisodeViewMode,
    onEpisodeViewModeSelected: (EpisodeViewMode) -> Unit,
    // <-- AM (EPISODE_VIEW_MODE)

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    // AM (CUSTOM_EPISODE_ORDER) -->
    isReordering: Boolean,
    onToggleReorder: (() -> Unit)?,
    // <-- AM (CUSTOM_EPISODE_ORDER)

    // AM (EPISODE_SEARCH) -->
    // AM (ALWAYS_OPEN_EPISODE_SEARCH) -->
    // Plain String, not String?: null used to mean "field closed", and there is
    // no closed state left, so empty is the only "not filtering" there is.
    episodeSearchQuery: String,
    onEpisodeSearchQueryChange: (String) -> Unit,
    // <-- AM (ALWAYS_OPEN_EPISODE_SEARCH)
    // AM (EPISODE_SEARCH_MIN_COUNT) -->
    // The unfiltered episode count, so the search action can be left out of
    // entries small enough to scan by eye.
    episodeCount: Int,
    // <-- AM (EPISODE_SEARCH_MIN_COUNT)
    // <-- AM (EPISODE_SEARCH)

    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    // AM (CUSTOM_EPISODE_ORDER) -->
    // Reorder mode keeps the action bar up with nothing selected: it is its
    // own mode, not a function of the selection count.
    val isActionMode = actionModeCounter > 0 || isReordering
    // <-- AM (CUSTOM_EPISODE_ORDER)
    // AM (ALWAYS_OPEN_EPISODE_SEARCH) -->
    // The field has no open/closed state any more, so there is nothing for the
    // user to toggle: it is shown whenever the entry is long enough to warrant
    // searching at all. A state that only existed before its first use was the
    // inconsistency - once opened it never closed again, so "closed" really
    // meant "not used yet".
    val isSearchVisible = episodeCount >= MIN_EPISODES_FOR_SEARCH
    // <-- AM (ALWAYS_OPEN_EPISODE_SEARCH)
    // AM (EPISODE_VIEW_MODE) -->
    var episodeViewModeDialogShown by remember { mutableStateOf(false) }
    // <-- AM (EPISODE_VIEW_MODE)
    val searchFocusRequester = remember { FocusRequester() }
    // AM (PLAYLIST_SEARCH_SCOPE) -->
    // Back has to put the keyboard away without touching the query, because
    // filtering is live: the results the user is typing toward are behind the
    // keyboard, so the gesture that reveals them cannot also be the one that
    // discards them. Clearing on back was the original behaviour and is wrong
    // for the same reason.
    var isSearchFocused by remember { mutableStateOf(false) }
    val searchKeyboardController = LocalSoftwareKeyboardController.current
    val searchFocusManager = LocalFocusManager.current
    // Typed, not inferred: hide() on a nullable controller is the last
    // expression, so an unannotated lambda infers () -> Unit? and will not fit
    // navigateUp's (() -> Unit)?.
    val closeSearchKeyboard: () -> Unit = {
        searchFocusManager.clearFocus()
        searchKeyboardController?.hide()
    }
    // <-- AM (PLAYLIST_SEARCH_SCOPE)
    AppBar(
        titleContent = {
            // AM (CUSTOM_EPISODE_ORDER) -->
            if (isReordering && actionModeCounter == 0) {
                AppBarTitle(stringResource(AMMR.strings.am_action_reorder_episodes))
            } else if (isActionMode) {
                // <-- AM (CUSTOM_EPISODE_ORDER)
                AppBarTitle(actionModeCounter.toString())
            } else if (!isSearchVisible) {
                AppBarTitle(title, modifier = Modifier.alpha(titleAlphaProvider()))
            } else {
                // AM (PLAYLIST_SEARCH_SCOPE) -->
                // The IME's own search key and the hardware enter key land
                // here, and both should do exactly what back does: put the
                // keyboard away and leave the query alone.
                //
                // No moveFocus: this field is the only focusable thing in the
                // bar, so advancing focus handed it straight back, and a
                // BasicTextField raises the keyboard on gaining focus - which
                // is why the search key looked like it did nothing.
                val clearFocus = closeSearchKeyboard
                // <-- AM (PLAYLIST_SEARCH_SCOPE)

                BasicTextField(
                    value = episodeSearchQuery,
                    onValueChange = onEpisodeSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester)
                        // AM (PLAYLIST_SEARCH_SCOPE) -->
                        .onFocusChanged { isSearchFocused = it.isFocused }
                        // <-- AM (PLAYLIST_SEARCH_SCOPE)
                        .runOnEnterKeyPressed(action = clearFocus)
                        // AM (ALWAYS_OPEN_EPISODE_SEARCH) -->
                        // No showSoftKeyboard here. It requests focus on first
                        // composition, which was right when the field appeared
                        // because the user had just pressed search - now the
                        // field is simply part of the bar, and grabbing focus
                        // would throw the keyboard up on entering every entry.
                        // <-- AM (ALWAYS_OPEN_EPISODE_SEARCH)
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
                            value = episodeSearchQuery,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = remember { MutableInteractionSource() },
                            // AM (ALWAYS_OPEN_EPISODE_SEARCH) -->
                            // The generic hint, not the entry name. Carrying
                            // the title here was only ever compensation for the
                            // slot the field took over, and the title earned
                            // little: it appeared solely once the header had
                            // scrolled away, by which point the user has been
                            // looking at the entry they opened. A field that
                            // says what it is beats one that says where you
                            // are.
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
                            // <-- AM (ALWAYS_OPEN_EPISODE_SEARCH)
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
        // AM (PLAYLIST_SEARCH_SCOPE) -->
        // Hides the keyboard while the field has focus, and leaves the entry
        // otherwise - so the first back reveals the filtered list and the
        // second one exits. The query is never cleared here; the X does that,
        // and clearing it is what unscopes the playlist.
        navigateUp = if (isSearchFocused) closeSearchKeyboard else navigateUp,
        // <-- AM (PLAYLIST_SEARCH_SCOPE)
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
                actions = buildList {
                    if (isActionMode) {
                        // AM (CUSTOM_EPISODE_ORDER) -->
                        // AM (EPISODE_NAMES) -->
                        // Rename lives in the bottom bar's overflow instead of
                        // here: it only applies to a single selection, and an
                        // action that appears and disappears with the selection
                        // count reflows the width of every other action in this
                        // bar as it does so.
                        // <-- AM (EPISODE_NAMES)
                        // A true toggle, tinted while on. Turning it off returns
                        // to plain selection mode with the selection kept; X or
                        // back leave entirely. Either way edits were already
                        // written as they were made, so there's no "done".
                        if (onToggleReorder != null) {
                            add(
                                AppBar.Action(
                                    title = stringResource(AMMR.strings.am_action_reorder_episodes),
                                    icon = Icons.Outlined.SwapVert,
                                    iconTint = if (isReordering) MaterialTheme.colorScheme.active else null,
                                    onClick = onToggleReorder,
                                ),
                            )
                        }
                        // <-- AM (CUSTOM_EPISODE_ORDER)
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
                        return@buildList
                    }
                    // AM (EPISODE_SEARCH) -->
                    // AM (ALWAYS_OPEN_EPISODE_SEARCH) -->
                    // No search action - the field is already there. Only the
                    // reset remains, and it no longer requests focus: that kept
                    // a just-opened field from losing focus, and now it would
                    // raise the keyboard back over the very results clearing is
                    // meant to reveal.
                    if (episodeSearchQuery.isNotEmpty()) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_reset),
                                icon = Icons.Outlined.Close,
                                onClick = { onEpisodeSearchQueryChange("") },
                            ),
                        )
                    }
                    // <-- AM (ALWAYS_OPEN_EPISODE_SEARCH)
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
                    // AM (MERGE_SETTINGS) -->
                    if (onClickMergeSettings != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(AMMR.strings.am_action_merge_settings),
                                onClick = onClickMergeSettings,
                            ),
                        )
                    }
                    // <-- AM (MERGE_SETTINGS)
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
                },
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

// AM (EPISODE_SEARCH_MIN_COUNT) -->
/** Below this many episodes the list is short enough to scan without searching. */
internal const val MIN_EPISODES_FOR_SEARCH = 10
// <-- AM (EPISODE_SEARCH_MIN_COUNT)
