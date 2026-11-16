package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import animiru.domain.player.model.ControlAction
import animiru.domain.player.model.ControlAnchor
import animiru.domain.player.model.ControlInput
import animiru.domain.player.model.ControlRegion
import animiru.domain.player.model.ControlSurface
import animiru.domain.player.model.CustomKeyCodes
import animiru.domain.player.service.ControlBindingPreferences
import animiru.domain.player.service.GesturePreferences
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.player.components.BottomPlayerButtonsDialog
import eu.kanade.presentation.more.settings.screen.player.components.bottomPlayerButtonsSubtitle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.WheelTextPicker
import tachiyomi.presentation.core.i18n.stringResource

object PlayerSettingsGesturesScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.pref_player_gestures

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val gesturePreferences = remember { context.appGraph.gesturePreferences }
        // AM (CONTROL_BINDINGS)
        val controlBindings = remember { context.appGraph.controlBindingPreferences }

        return listOf(
            getSlidersGroup(gesturePreferences = gesturePreferences),
            getSeekingGroup(gesturePreferences = gesturePreferences),
            getDoubleTapGroup(controlBindings = controlBindings),
            getLongPressGroup(controlBindings = controlBindings),
            getMediaControlsGroup(controlBindings = controlBindings),
            getBottomControlsGroup(controlBindings = controlBindings),
        )
    }

    @Composable
    private fun getSlidersGroup(gesturePreferences: GesturePreferences): Preference.PreferenceGroup {
        val enableVolumeBrightnessGestures = gesturePreferences.gestureVolumeBrightness
        val swapVol = gesturePreferences.swapVolumeBrightness
        // AM (SWIPE_SWITCH) -->
        val verticalSwipeSwitch = gesturePreferences.gestureVerticalSwipeSwitch
        // <-- AM (SWIPE_SWITCH)
        // AM (PINCH_ZOOM) -->
        val pinchZoom = gesturePreferences.gesturePinchZoom
        // <-- AM (PINCH_ZOOM)

        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_player_sliders),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = enableVolumeBrightnessGestures,
                    title = stringResource(AYMR.strings.enable_volume_brightness_gestures),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = swapVol,
                    title = stringResource(AYMR.strings.pref_controls_swap_vol_brightness),
                ),
                // AM (SWIPE_SWITCH) -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = verticalSwipeSwitch,
                    title = stringResource(AYMR.strings.pref_vertical_swipe_switch),
                    subtitle = stringResource(AYMR.strings.pref_vertical_swipe_switch_summary),
                ),
                // <-- AM (SWIPE_SWITCH)
                // AM (PINCH_ZOOM) -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = pinchZoom,
                    title = stringResource(AYMR.strings.pref_pinch_zoom),
                    subtitle = stringResource(AYMR.strings.pref_pinch_zoom_summary),
                ),
                // <-- AM (PINCH_ZOOM)
            ),
        )
    }

    @Composable
    private fun getSeekingGroup(gesturePreferences: GesturePreferences): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val enableHorizontalSeekGesture = gesturePreferences.gestureHorizontalSeek
        val showSeekbar = gesturePreferences.showSeekBar
        val defaultSkipIntroLength by gesturePreferences.defaultIntroLength.stateIn(scope).collectAsState()
        val skipLengthPreference = gesturePreferences.skipLengthPreference
        val playerSmoothSeek = gesturePreferences.playerSmoothSeek

        var showDialog by rememberSaveable { mutableStateOf(false) }
        if (showDialog) {
            SkipIntroLengthDialog(
                initialSkipIntroLength = defaultSkipIntroLength,
                onDismissRequest = { showDialog = false },
                onValueChanged = { skipIntroLength ->
                    gesturePreferences.defaultIntroLength.set(skipIntroLength)
                    showDialog = false
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_player_seeking),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = enableHorizontalSeekGesture,
                    title = stringResource(AYMR.strings.enable_horizontal_seek_gesture),
                    subtitle = stringResource(AYMR.strings.enable_horizontal_seek_gesture_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = showSeekbar,
                    title = stringResource(AYMR.strings.pref_show_seekbar),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(AYMR.strings.pref_default_intro_length),
                    subtitle = "${defaultSkipIntroLength}s",
                    onClick = { showDialog = true },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = skipLengthPreference,
                    entries = mapOf(
                        30 to stringResource(AYMR.strings.pref_skip_30),
                        20 to stringResource(AYMR.strings.pref_skip_20),
                        10 to stringResource(AYMR.strings.pref_skip_10),
                        5 to stringResource(AYMR.strings.pref_skip_5),
                        3 to stringResource(AYMR.strings.pref_skip_3),
                        0 to stringResource(AYMR.strings.pref_skip_disable),
                    ),
                    title = stringResource(AYMR.strings.pref_skip_length),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = playerSmoothSeek,
                    title = stringResource(AYMR.strings.pref_player_smooth_seek),
                    subtitle = stringResource(AYMR.strings.pref_player_smooth_seek_summary),
                ),
            ),
        )
    }

    // AM (CONTROL_BINDINGS) -->
    /**
     * The rows below write into the surface's layout record rather than into a key per
     * slot. Picking "Custom" keeps the key code the slot used to imply, so an existing
     * Lua binding keeps firing from the same place.
     */
    @Composable
    private fun actionEntries(vararg actions: ControlAction): Map<ControlAction, String> =
        actions.associateWith { stringResource(it.stringRes) }

    @Composable
    private fun getDoubleTapGroup(controlBindings: ControlBindingPreferences): Preference.PreferenceGroup {
        fun binding(anchor: ControlAnchor) =
            controlBindings.action(ControlSurface.Player, anchor, ControlInput.DoubleTap)

        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_double_tap),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = binding(ControlAnchor.ScreenLeft),
                    entries = actionEntries(
                        ControlAction.None,
                        ControlAction.SeekBackward,
                        ControlAction.PlayPause,
                        ControlAction.PreviousEpisode,
                        ControlAction.CustomKey(CustomKeyCodes.DoubleTapLeft.keyCode),
                    ),
                    title = stringResource(AYMR.strings.pref_left_double_tap),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = binding(ControlAnchor.ScreenCenter),
                    entries = actionEntries(
                        ControlAction.None,
                        ControlAction.PlayPause,
                        ControlAction.CustomKey(CustomKeyCodes.DoubleTapCenter.keyCode),
                    ),
                    title = stringResource(AYMR.strings.pref_center_double_tap),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = binding(ControlAnchor.ScreenRight),
                    entries = actionEntries(
                        ControlAction.None,
                        ControlAction.SeekForward,
                        ControlAction.PlayPause,
                        ControlAction.NextEpisode,
                        ControlAction.CustomKey(CustomKeyCodes.DoubleTapRight.keyCode),
                    ),
                    title = stringResource(AYMR.strings.pref_right_double_tap),
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(AYMR.strings.pref_double_tap_info),
                ),
            ),
        )
    }

    @Composable
    private fun getLongPressGroup(controlBindings: ControlBindingPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_long_press),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = controlBindings.action(
                        ControlSurface.Player,
                        ControlAnchor.ScreenCenter,
                        ControlInput.LongPress,
                    ),
                    entries = actionEntries(
                        ControlAction.None,
                        ControlAction.Screenshot,
                        ControlAction.PlayPause,
                        ControlAction.PrimaryCustomButton,
                    ).toPersistentMap(),
                    title = stringResource(AYMR.strings.pref_long_press),
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(AYMR.strings.pref_long_press_info),
                ),
            ),
        )
    }

    @Composable
    private fun getMediaControlsGroup(controlBindings: ControlBindingPreferences): Preference.PreferenceGroup {
        fun binding(anchor: ControlAnchor) =
            controlBindings.action(ControlSurface.Player, anchor, ControlInput.Tap)

        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_media_controls),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = binding(ControlAnchor.MediaPrevious),
                    entries = actionEntries(
                        ControlAction.None,
                        ControlAction.SeekBackward,
                        ControlAction.PlayPause,
                        ControlAction.PreviousEpisode,
                        ControlAction.CustomKey(CustomKeyCodes.MediaPrevious.keyCode),
                    ),
                    title = stringResource(AYMR.strings.pref_media_previous),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = binding(ControlAnchor.MediaPlayPause),
                    entries = actionEntries(
                        ControlAction.None,
                        ControlAction.PlayPause,
                        ControlAction.CustomKey(CustomKeyCodes.MediaPlay.keyCode),
                    ),
                    title = stringResource(AYMR.strings.pref_media_playpause),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = binding(ControlAnchor.MediaNext),
                    entries = actionEntries(
                        ControlAction.None,
                        ControlAction.SeekForward,
                        ControlAction.PlayPause,
                        ControlAction.NextEpisode,
                        ControlAction.CustomKey(CustomKeyCodes.MediaNext.keyCode),
                    ),
                    title = stringResource(AYMR.strings.pref_media_next),
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(AYMR.strings.pref_media_info),
                ),
            ),
        )
    }
    // <-- AM (CONTROL_BINDINGS)

    @Composable
    private fun getBottomControlsGroup(controlBindings: ControlBindingPreferences): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val bottomPlayerButtonsPref = remember {
            controlBindings.entries(ControlSurface.Player, ControlRegion.Bottom)
        }
        val selectedButtons by bottomPlayerButtonsPref.stateIn(scope).collectAsState()

        var showDialog by rememberSaveable { mutableStateOf(false) }
        if (showDialog) {
            BottomPlayerButtonsDialog(
                initialSelection = selectedButtons,
                onDismissRequest = { showDialog = false },
                onConfirm = { bottomPlayerButtonsPref.set(it) },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_bottom_controls),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(AYMR.strings.pref_bottom_player_buttons),
                    subtitle = bottomPlayerButtonsSubtitle(selectedButtons),
                    onClick = { showDialog = true },
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(AYMR.strings.pref_bottom_player_buttons_info),
                ),
            ),
        )
    }

    @Composable
    fun SkipIntroLengthDialog(
        initialSkipIntroLength: Int,
        onDismissRequest: () -> Unit,
        onValueChanged: (skipIntroLength: Int) -> Unit,
    ) {
        val skipIntroLengthValue by rememberSaveable { mutableStateOf(initialSkipIntroLength) }
        var newLength = 0
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(AYMR.strings.pref_intro_length)) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        WheelTextPicker(
                            modifier = Modifier.align(Alignment.Center),
                            items = remember { 0..255 }.map {
                                stringResource(
                                    MR.strings.seconds_short,
                                    it,
                                )
                            },
                            onSelectionChanged = {
                                newLength = it
                            },
                            startIndex = skipIntroLengthValue,
                        )
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = { onValueChanged(newLength) }) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
        )
    }
}
