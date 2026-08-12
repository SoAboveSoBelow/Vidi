package eu.kanade.tachiyomi.ui.player.settings

import eu.kanade.tachiyomi.ui.player.PlayerOrientation
import eu.kanade.tachiyomi.ui.player.VideoAspect
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class PlayerPreferences(
    preferenceStore: PreferenceStore,
) {
    val preserveWatchingPosition: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_preserve_watching_position",
        false,
    )
    // AM (RECENT_EPISODE_POSITIONS) -->

    /**
     * How many recently-departed episodes (not counting the one currently open) keep a
     * temporary resume position in memory, so an accidental next/previous click doesn't
     * lose your place. Doesn't affect the currently-open episode's own resume support.
     */
    val recentEpisodePositionSlots: Preference<Int> = preferenceStore.getInt(
        "pref_recent_episode_position_slots",
        2,
    )

    // <-- AM (RECENT_EPISODE_POSITIONS)
    val backgroundPlayback: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_player_background_playback",
        true,
    )
    val switchOnFailure: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_player_switch_on_failure",
        true,
    )
    val progressPreference: Preference<Float> = preferenceStore.getFloat("pref_progress_preference", 0.85F)
    val defaultPlayerOrientationType: Preference<PlayerOrientation> = preferenceStore.getEnum(
        "pref_default_player_orientation_type_key",
        PlayerOrientation.SensorLandscape,
    )

    // Controls

    val allowGestures: Preference<Boolean> = preferenceStore.getBoolean("pref_allow_gestures_in_panels", false)
    val showLoadingCircle: Preference<Boolean> = preferenceStore.getBoolean("pref_show_loading", true)
    val showCurrentChapter: Preference<Boolean> = preferenceStore.getBoolean("pref_show_current_chapter", true)
    val rememberPlayerBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_remember_brightness", false)
    val playerBrightnessValue: Preference<Float> = preferenceStore.getFloat("player_brightness_value", -1.0F)
    val rememberPlayerVolume: Preference<Boolean> = preferenceStore.getBoolean("pref_remember_volume", false)
    val playerVolumeValue: Preference<Int> = preferenceStore.getInt("player_volume_value_v2", -1)

    // Hoster

    val showFailedHosters: Preference<Boolean> = preferenceStore.getBoolean("pref_show_failed_hosters", false)
    val showEmptyHosters: Preference<Boolean> = preferenceStore.getBoolean("pref_show_empty_hosters", false)

    // Display

    val playerFullscreen: Preference<Boolean> = preferenceStore.getBoolean("player_fullscreen", true)
    val hideControls: Preference<Boolean> = preferenceStore.getBoolean("player_hide_controls", false)
    val displayVolPer: Preference<Boolean> = preferenceStore.getBoolean("pref_display_vol_as_per", true)
    val showSystemStatusBar: Preference<Boolean> = preferenceStore.getBoolean("pref_show_system_status_bar", false)
    val reduceMotion: Preference<Boolean> = preferenceStore.getBoolean("pref_reduce_motion", false)
    val playerTimeToDisappear: Preference<Int> = preferenceStore.getInt("pref_player_time_to_disappear", 4000)
    val panelOpacity: Preference<Int> = preferenceStore.getInt("pref_panel_opacity", 60)

    // Skip intro button

    val enableSkipIntro: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_skip_intro", true)
    val autoSkipIntro: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_auto_skip_ani_skip", false)
    val enableNetflixStyleIntroSkip: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_netflixStyle_aniskip",
        false,
    )
    val waitingTimeIntroSkip: Preference<Int> = preferenceStore.getInt("pref_waiting_time_aniskip", 5)
    val aniSkipEnabled: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_ani_skip", false)
    val disableAniSkipOnChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_disabled_ani_skip_chapters",
        true,
    )

    // PiP

    val enablePip: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_pip", true)
    val pipEpisodeToasts: Preference<Boolean> = preferenceStore.getBoolean("pref_pip_episode_toasts", true)
    // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
    // Default true: exiting via Home/Back should auto-enter PIP rather than silently
    // falling back to audio-only background playback. No settings screen currently
    // exposes this toggle, so the default is effectively the only way to control it.
    val pipOnExit: Preference<Boolean> = preferenceStore.getBoolean("pref_pip_on_exit", true)
    // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
    val pipFirstButtonAction: Preference<Int> = preferenceStore.getInt("pip_first_button_action", 0)

    // External player

    val alwaysUseExternalPlayer: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_always_use_external_player",
        false,
    )
    val externalPlayerPreference: Preference<String> = preferenceStore.getString("external_player_preference", "")

    // Non-preferences

    val playerSpeed: Preference<Float> = preferenceStore.getFloat("pref_player_speed", 1f)
    val speedPresets: Preference<Set<String>> = preferenceStore.getStringSet(
        "default_speed_presets",
        setOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0", "3.5", "4.0"),
    )
    val invertDuration: Preference<Boolean> = preferenceStore.getBoolean("invert_duration", false)
    val aspectState: Preference<VideoAspect> = preferenceStore.getEnum("pref_player_aspect_state", VideoAspect.Fit)

    // Old

    val autoplayEnabled: Preference<Boolean> = preferenceStore.getBoolean("pref_auto_play_enabled", false)
}
