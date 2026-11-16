package animiru.domain.player.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
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

    // AM (PINCH_ZOOM) -->
    val gesturePinchZoom: Preference<Boolean> = preferenceStore.getBoolean("pref_gesture_pinch_zoom", true)
    // <-- AM (PINCH_ZOOM)

    // Seeking

    val gestureHorizontalSeek: Preference<Boolean> = preferenceStore.getBoolean("pref_gesture_horizontal_seek", true)
    val showSeekBar: Preference<Boolean> = preferenceStore.getBoolean("pref_show_seekbar", false)
    val defaultIntroLength: Preference<Int> = preferenceStore.getInt("pref_default_intro_length", 85)
    val skipLengthPreference: Preference<Int> = preferenceStore.getInt("pref_skip_length_preference", 10)
    val playerSmoothSeek: Preference<Boolean> = preferenceStore.getBoolean("pref_player_smooth_seek", false)

    // AM (CONTROL_BINDINGS) -->
    // Double tap, long press and the media buttons used to live here as one preference
    // per slot. They are bindings now, held per surface by ControlBindingPreferences,
    // which reads these keys directly to derive a surface's starting layout. The fields
    // are gone rather than deprecated because a second accessor is a second way for the
    // two stores to disagree. The bottom row went the same way: it is the Bottom region
    // of the player's layout now, measured at render instead of capped at six.
    // <-- AM (CONTROL_BINDINGS)
}
