package eu.kanade.presentation.more.settings.screen.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.screen.SettingsSearchScreen
import eu.kanade.presentation.more.settings.screen.player.custombutton.PlayerSettingsCustomButtonScreen
import eu.kanade.tachiyomi.ui.player.PlayerMediaHolder
import eu.kanade.presentation.more.settings.screen.player.editor.PlayerSettingsEditorScreen
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Magnet
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen

class PlayerSettingsMainScreen(private val mainSettings: Boolean) : Screen() {
    @Composable
    override fun Content() {
        Content(twoPane = false)
    }

    @Composable
    private fun getPalerSurface(): Color {
        val surface = MaterialTheme.colorScheme.surface
        val dark = isSystemInDarkTheme()
        return remember(surface, dark) {
            val arr = FloatArray(3)
            ColorUtils.colorToHSL(surface.toArgb(), arr)
            arr[2] = if (dark) {
                arr[2] - 0.05f
            } else {
                arr[2] + 0.02f
            }.coerceIn(0f, 1f)
            Color.hsl(arr[0], arr[1], arr[2])
        }
    }

    @Composable
    fun Content(twoPane: Boolean) {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val backPress = LocalBackPress.currentOrThrow
        val containerColor = if (twoPane) getPalerSurface() else MaterialTheme.colorScheme.surface
        val topBarState = rememberTopAppBarState()

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(
                        if (mainSettings) {
                            AYMR.strings.label_player
                        } else {
                            AYMR.strings.label_player_settings
                        },
                    ),
                    navigateUp = backPress::invoke,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_search),
                                    icon = Icons.Outlined.Search,
                                    onClick = { navigator.navigate(SettingsSearchScreen(true), twoPane) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = containerColor,
            content = { contentPadding ->
                val state = rememberLazyListState()
                val indexSelected = if (twoPane) {
                    // An item that launches an activity has no screen to match against,
                    // so it is never the selected pane.
                    items.indexOfFirst { it.screen?.let { screen -> screen::class } == navigator.items.first()::class }
                        .also {
                            LaunchedEffect(Unit) {
                                state.animateScrollToItem(it)
                                if (it > 0) {
                                    // Lift scroll
                                    topBarState.contentOffset = topBarState.heightOffsetLimit
                                }
                            }
                        }
                } else {
                    null
                }

                LazyColumn(
                    state = state,
                    contentPadding = contentPadding,
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.hashCode() },
                    ) { index, item ->
                        val selected = indexSelected == index
                        var modifier: Modifier = Modifier
                        var contentColor = LocalContentColor.current
                        if (twoPane) {
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .then(
                                    if (selected) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                            if (selected) {
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        }
                        CompositionLocalProvider(LocalContentColor provides contentColor) {
                            TextPreferenceWidget(
                                modifier = modifier,
                                title = stringResource(item.titleRes),
                                subtitle = item.formatSubtitle(),
                                icon = item.icon,
                                onPreferenceClick = {
                                    val screen = item.screen
                                    if (screen != null) {
                                        navigator.navigate(screen, twoPane)
                                    } else {
                                        item.launch?.invoke(context)
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    private fun Navigator.navigate(screen: VoyagerScreen, twoPane: Boolean) {
        if (twoPane) replaceAll(screen) else push(screen)
    }
}

private data class Item(
    val titleRes: StringResource,
    val subtitleRes: StringResource? = null,
    val formatSubtitle: @Composable () -> String? = { subtitleRes?.let { stringResource(it) } },
    val icon: ImageVector,
    val screen: VoyagerScreen? = null,
    // AM (CONTROL_BINDINGS): an item that does something rather than navigating - the
    // controls editor submits a playback request and the overlay host shows the
    // player, exactly as opening a video does.
    val launch: ((Context) -> Unit)? = null,
)

private val items = listOf(
    Item(
        titleRes = AYMR.strings.pref_player_internal,
        subtitleRes = AYMR.strings.pref_player_internal_summary,
        icon = Icons.Outlined.PlayCircleOutline,
        screen = PlayerSettingsPlayerScreen,
    ),
    Item(
        titleRes = AYMR.strings.pref_player_gestures,
        subtitleRes = AYMR.strings.pref_player_gestures_summary,
        icon = Icons.Outlined.Gesture,
        screen = PlayerSettingsGesturesScreen,
    ),
    Item(
        titleRes = AYMR.strings.pref_player_decoder,
        subtitleRes = AYMR.strings.pref_player_decoder_summary,
        icon = Icons.Outlined.Memory,
        screen = PlayerSettingsDecoderScreen,
    ),
    Item(
        titleRes = AYMR.strings.pref_player_subtitle,
        subtitleRes = AYMR.strings.pref_player_subtitle_summary,
        icon = Icons.Outlined.Subtitles,
        screen = PlayerSettingsSubtitleScreen,
    ),
    Item(
        titleRes = AYMR.strings.pref_player_audio,
        subtitleRes = AYMR.strings.pref_player_audio_summary,
        icon = Icons.Outlined.Audiotrack,
        screen = PlayerSettingsAudioScreen,
    ),
    // AM (CONTROL_BINDINGS): custom buttons are reached from inside the controls
    // editor now - they are one kind of thing a control can be bound to, not a
    // settings area of their own.
    Item(
        titleRes = AYMR.strings.pref_player_custom_button,
        subtitleRes = AYMR.strings.pref_player_custom_button_summary,
        icon = Icons.Outlined.Terminal,
        screen = PlayerSettingsCustomButtonScreen,
    ),
    Item(
        titleRes = AYMR.strings.pref_player_controls,
        subtitleRes = AYMR.strings.pref_player_controls_summary,
        icon = Icons.Outlined.Tune,
        // AM (CONTROL_BINDINGS): the same call opening a video makes. The overlay
        // host picks the request up and shows the player over whatever is on
        // screen - no Activity, so no second task and nothing to keep in step.
        launch = { PlayerMediaHolder.openLayoutEditor() },
    ),
    Item(
        titleRes = AYMR.strings.pref_player_editor,
        subtitleRes = AYMR.strings.pref_player_editor_summary,
        icon = Icons.Outlined.EditNote,
        screen = PlayerSettingsEditorScreen,
    ),
    Item(
        titleRes = AYMR.strings.pref_player_torrents,
        subtitleRes = AYMR.strings.pref_player_torrents_summary,
        icon = CustomIcons.Magnet,
        screen = PlayerSettingsTorrentScreen,
    ),
    Item(
        titleRes = AYMR.strings.pref_player_advanced,
        subtitleRes = AYMR.strings.pref_player_advanced_summary,
        icon = Icons.Outlined.Code,
        screen = PlayerSettingsAdvancedScreen,
    ),
)
