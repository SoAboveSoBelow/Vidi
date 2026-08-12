package eu.kanade.tachiyomi.ui.player.settings

import eu.kanade.tachiyomi.ui.player.BottomPlayerButton
import eu.kanade.tachiyomi.ui.player.SingleActionGesture
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSlots

class GesturePreferences(
    preferenceStore: PreferenceStore,
) {
    // Sliders
    val gestureVolumeBrightness: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_gesture_volume_brightness",
        true,
    )
    val swapVolumeBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_swap_volume_and_brightness", false)
    // AM (SWIPE_SWITCH) -->
    val gestureVerticalSwipeSwitch: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_gesture_vertical_swipe_switch",
        false,
    )
    // <-- AM (SWIPE_SWITCH)

    // Seeking

    val gestureHorizontalSeek: Preference<Boolean> = preferenceStore.getBoolean("pref_gesture_horizontal_seek", true)
    val showSeekBar: Preference<Boolean> = preferenceStore.getBoolean("pref_show_seekbar", false)
    val defaultIntroLength: Preference<Int> = preferenceStore.getInt("pref_default_intro_length", 85)
    val skipLengthPreference: Preference<Int> = preferenceStore.getInt("pref_skip_length_preference", 10)
    val playerSmoothSeek: Preference<Boolean> = preferenceStore.getBoolean("pref_player_smooth_seek", false)

    // Double tap

    val leftDoubleTapGesture: Preference<SingleActionGesture> = preferenceStore.getEnum(
        "pref_left_double_tap",
        SingleActionGesture.Seek,
    )
    val centerDoubleTapGesture: Preference<SingleActionGesture> = preferenceStore.getEnum(
        "pref_center_double_tap",
        SingleActionGesture.PlayPause,
    )
    val rightDoubleTapGesture: Preference<SingleActionGesture> = preferenceStore.getEnum(
        "pref_right_double_tap",
        SingleActionGesture.Seek,
    )

    // Long press

    val longPressGesture: Preference<SingleActionGesture> = preferenceStore.getEnum(
        "pref_long_press",
        SingleActionGesture.Screenshot,
    )

    // Media controls

    val mediaPreviousGesture: Preference<SingleActionGesture> = preferenceStore.getEnum(
        "pref_media_previous",
        SingleActionGesture.Switch,
    )
    val mediaPlayPauseGesture: Preference<SingleActionGesture> = preferenceStore.getEnum(
        "pref_media_playpause",
        SingleActionGesture.PlayPause,
    )
    val mediaNextGesture: Preference<SingleActionGesture> = preferenceStore.getEnum(
        "pref_media_next",
        SingleActionGesture.Switch,
    )

    // Bottom controls

    /**
     * Fixed [MAX_BOTTOM_PLAYER_BUTTONS]-length list of positions along the bottom of the
     * player; a null entry means that position is empty. Gaps are preserved so a button
     * assigned to position 5 stays at position 5 even if earlier positions are empty.
     */
    val bottomPlayerButtons: Preference<List<BottomPlayerButton?>> = preferenceStore.getEnumSlots(
        "pref_bottom_player_buttons",
        listOf(
            BottomPlayerButton.Lock,
            BottomPlayerButton.Rotation,
            BottomPlayerButton.Speed,
            BottomPlayerButton.Chapters,
            BottomPlayerButton.PictureInPicture,
            BottomPlayerButton.AspectRatio,
        ),
    )

    companion object {
        const val MAX_BOTTOM_PLAYER_BUTTONS = 6
    }
}
