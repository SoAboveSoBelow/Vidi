package eu.kanade.tachiyomi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.recents.RecentsTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.core.common.Constants
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.NavigationRail
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    private val showBottomNavEvent = Channel<Boolean>()

    @Suppress("ConstPropertyName")
    private const val TabFadeDuration = 200

    @Suppress("ConstPropertyName")
    private const val TabNavigatorKey = "HomeTabs"

    private val TABS = listOf(
        LibraryTab,
        // AM (RECENTS) -->
        RecentsTab,
        // <-- AM (RECENTS)
        // AM (BROWSE) -->
        BrowseTab,
        // <-- AM (BROWSE)
        MoreTab,
    )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // AM (LAST_LOCATION) -->
        // Start on the last-visited tab instead of always LibraryTab. Previously,
        // reopening on Recents/Browse/More required an extra animated tab-switch
        // transition after the initial LibraryTab frame (on top of the AnimeScreen push
        // transition below) - visible as a "jump through" every intermediate screen when
        // reconstructing state after a lost back stack. Matching the initial tab up front
        // means the subsequent openTabEvent (fired from handleIntentAction) reassigns the
        // same tab object and triggers no extra transition.
        val initialTab = remember {
            when (Injekt.get<UiPreferences>().lastVisitedTabAction.get()) {
                Constants.SHORTCUT_UPDATES, Constants.SHORTCUT_HISTORY -> RecentsTab
                Constants.SHORTCUT_SOURCES, Constants.SHORTCUT_EXTENSIONS -> BrowseTab
                Constants.SHORTCUT_MORE -> MoreTab
                else -> LibraryTab
            }
        }
        TabNavigator(
            tab = initialTab,
            key = TabNavigatorKey,
        ) { tabNavigator ->
            // <-- AM (LAST_LOCATION)
            // AM (NAVIGATION_PILL) -->
            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                val currentTabIndex by remember {
                    // AM (RECENTS_FILTER_CHIP) -->
                    derivedStateOf { TABS.indexOfFirst { it::class == tabNavigator.current::class } }
                    // <-- AM (RECENTS_FILTER_CHIP)
                }

                var oldIndex by remember { mutableIntStateOf(currentTabIndex) }

                LaunchedEffect(currentTabIndex) {
                    oldIndex = currentTabIndex
                }

                // AM (LAST_LOCATION) -->
                // Persist which top-level tab is active so the notification reopen/
                // back-fallback deep link (Constants.SHORTCUT_ANIME handling in
                // MainActivity) can restore it instead of always defaulting to Library.
                // Only fires while HomeScreen itself is the composed top screen, so it
                // naturally reflects the last tab the user was actually looking at rather
                // than being overwritten while e.g. AnimeScreen is pushed on top.
                LaunchedEffect(tabNavigator.current) {
                    val action = when (tabNavigator.current) {
                        RecentsTab -> Constants.SHORTCUT_UPDATES
                        BrowseTab -> Constants.SHORTCUT_SOURCES
                        MoreTab -> Constants.SHORTCUT_MORE
                        else -> Constants.SHORTCUT_LIBRARY
                    }
                    Injekt.get<UiPreferences>().lastVisitedTabAction.set(action)
                }
                // <-- AM (LAST_LOCATION)

                Scaffold(
                    startBar = {
                        if (isTabletUi()) {
                            NavigationRail {
                                TABS.fastForEach {
                                    NavigationRailItem(it)
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!isTabletUi()) {
                            val bottomNavVisible by produceState(initialValue = true) {
                                showBottomNavEvent.receiveAsFlow().collectLatest { value = it }
                            }
                            AnimatedVisibility(
                                visible = bottomNavVisible,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                NavigationPill(
                                    tabs = TABS,
                                    labelFade = TabFadeDuration / 2,
                                )
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { contentPadding ->
                    Box(
                        modifier = Modifier
                            .padding(contentPadding)
                            .consumeWindowInsets(contentPadding),
                    ) {
                        AnimatedContent(
                            targetState = tabNavigator.current,
                            transitionSpec = {
                                materialFadeThroughIn(initialScale = 1f, durationMillis = TabFadeDuration) togetherWith
                                    materialFadeThroughOut(durationMillis = TabFadeDuration)
                            },
                            label = "tabContent",
                        ) {
                            tabNavigator.saveableState(key = "currentTab", it) {
                                it.Content()
                            }
                        }
                    }
                }
            }

            val goToLibraryTab = { tabNavigator.current = LibraryTab }

            BackHandler(enabled = tabNavigator.current != LibraryTab, onBack = goToLibraryTab)

            LaunchedEffect(Unit) {
                launch {
                    librarySearchEvent.receiveAsFlow().collectLatest {
                        goToLibraryTab()
                        LibraryTab.search(it)
                    }
                }
                launch {
                    openTabEvent.receiveAsFlow().collectLatest {
                        tabNavigator.current = when (it) {
                            is Tab.Library -> LibraryTab
                            // AM (RECENTS) -->
                            is Tab.Recents -> {
                                if (it.toHistory) {
                                    RecentsTab.showHistory()
                                }
                                RecentsTab
                            }
                            // <-- AM (RECENTS)
                            // AM (BROWSE) -->
                            is Tab.Browse -> BrowseTab
                            // <-- AM (BROWSE)
                            is Tab.More -> MoreTab
                        }

                        // AM (LAST_LOCATION) -->
                        // Generalized from the old Tab.Library-only check so the
                        // notification reopen/back-fallback deep link can land the anime
                        // screen on top of whichever tab was actually last visited, not
                        // just Library.
                        val animeIdToOpen = when (it) {
                            is Tab.Library -> it.animeIdToOpen
                            is Tab.Recents -> it.animeIdToOpen
                            is Tab.Browse -> it.animeIdToOpen
                            is Tab.More -> it.animeIdToOpen
                        }
                        if (animeIdToOpen != null) {
                            navigator.push(AnimeScreen(animeIdToOpen))
                        }
                        // <-- AM (LAST_LOCATION)
                        if (it is Tab.More && it.toDownloads) {
                            navigator.push(DownloadQueueScreen)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NavigationRailItem(tab: eu.kanade.presentation.util.Tab) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        NavigationRailItem(
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = { NavigationIconItem(tab) },
            label = {
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            alwaysShowLabel = true,
        )
    }

    @Composable
    private fun NavigationIconItem(tab: eu.kanade.presentation.util.Tab) {
        BadgedBox(
            badge = {
                when {
                    tab is RecentsTab -> {
                        val count by produceState(initialValue = 0) {
                            val pref = Injekt.get<LibraryPreferences>()
                            combine(
                                pref.newShowUpdatesCount.changes(),
                                pref.newUpdatesCount.changes(),
                            ) { show, count -> if (show) count else 0 }
                                .collectLatest { value = it }
                        }
                        if (count > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.notification_chapters_generic,
                                    count = count,
                                    count,
                                )
                                Text(
                                    text = count.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                    BrowseTab::class.isInstance(tab) -> {
                        val count by produceState(initialValue = 0) {
                            Injekt.get<SourcePreferences>().extensionUpdatesCount.changes()
                                .collectLatest { value = it }
                        }
                        if (count > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.update_check_notification_ext_updates,
                                    count = count,
                                    count,
                                )
                                Text(
                                    text = count.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                }
            },
        ) {
            Icon(
                painter = tab.options.icon!!,
                contentDescription = tab.options.title,
            )
        }
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    sealed interface Tab {
        data class Library(val animeIdToOpen: Long? = null) : Tab

        // AM (RECENTS) -->
        data class Recents(val toHistory: Boolean, val animeIdToOpen: Long? = null) : Tab

        // <-- AM (RECENTS)
        data class Browse(val toExtensions: Boolean = false, val animeIdToOpen: Long? = null) : Tab
        data class More(val toDownloads: Boolean, val animeIdToOpen: Long? = null) : Tab
    }
}
