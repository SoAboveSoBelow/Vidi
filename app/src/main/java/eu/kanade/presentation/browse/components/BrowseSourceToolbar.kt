package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import aniyomi.domain.source.model.BrowseDisplayMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: AnimeSource?,
    // AM (BROWSE_SCROLLER)
    displayMode: BrowseDisplayMode,
    onDisplayModeChange: (BrowseDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalSource
    val isConfigurableSource = source is ConfigurableAnimeSource

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = buildList {
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_display_mode),
                            // AM (BROWSE_SCROLLER)
                            icon = when (displayMode) {
                                BrowseDisplayMode.Scroller -> Icons.Filled.ViewDay
                                BrowseDisplayMode.Standard(LibraryDisplayMode.List) ->
                                    Icons.AutoMirrored.Filled.ViewList
                                else -> Icons.Filled.ViewModule
                            },
                            onClick = { selectingDisplayMode = true },
                        ),
                    )
                    if (isLocalSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.label_help),
                                onClick = onHelpClick,
                            ),
                        )
                    } else {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = onWebViewClick,
                            ),
                        )
                    }
                    if (isConfigurableSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_settings),
                                onClick = onSettingsClick,
                            ),
                        )
                    }
                },
            )

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                // AM (BROWSE_SCROLLER): the three standard layouts now go
                // through BrowseDisplayMode.Standard, and the scroller joins
                // them as a fourth entry.
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == BrowseDisplayMode.Standard(LibraryDisplayMode.ComfortableGrid),
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(BrowseDisplayMode.Standard(LibraryDisplayMode.ComfortableGrid))
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == BrowseDisplayMode.Standard(LibraryDisplayMode.CompactGrid),
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(BrowseDisplayMode.Standard(LibraryDisplayMode.CompactGrid))
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == BrowseDisplayMode.Standard(LibraryDisplayMode.List),
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(BrowseDisplayMode.Standard(LibraryDisplayMode.List))
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(AMMR.strings.action_display_scroller)) },
                    isChecked = displayMode == BrowseDisplayMode.Scroller,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(BrowseDisplayMode.Scroller)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
