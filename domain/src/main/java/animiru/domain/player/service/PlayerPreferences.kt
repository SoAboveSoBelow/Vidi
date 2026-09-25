package animiru.domain.player.service

import animiru.domain.player.model.PlayerOrientation
import animiru.domain.player.model.VideoAspect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

@Inject
@SingleIn(AppScope::class)
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

    // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
    /**
     * Disk allowance, in bytes, for keeping the raw stream bytes of episodes that
     * hold a temporary position slot, so returning to one replays from disk instead
     * of re-fetching.
     *
     * ZERO MEANS OFF - it is the whole switch, not a degenerate size. A separate
     * boolean alongside this would allow "keeping enabled, with no room to keep
     * anything", a state with no meaning; an allowance of nothing says it once.
     * Nothing survives a restart at 0, though the cache still de-duplicates a
     * download and a playback of the same episode within one session, which costs
     * no lasting disk.
     *
     * How MANY episodes are kept is not set here - that follows
     * [recentEpisodePositionSlots], since the position table is what defines
     * "recent" for this feature. This only bounds how much space those episodes are
     * allowed to occupy, because episode sizes differ by an order of magnitude
     * between a 480p stream and a 4K one.
     */
    val retainRecentEpisodeMediaMaxBytes: Preference<Long> = preferenceStore.getLong(
        "pref_retain_recent_episode_media_max_bytes",
        2L * 1024 * 1024 * 1024,
    )
    // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)

    // <-- AM (RECENT_EPISODE_POSITIONS)

    // AM (NETWORK_BUFFER_SECONDS) -->
    /**
     * How many seconds of a network stream to buffer ahead (mpv's `cache-secs`).
     * Still capped by `demuxer-max-bytes`, which is a memory guard rather than a
     * buffer policy - see MPVPlayer's own note. No effect on downloaded or local
     * playback, where mpv's cache is off entirely.
     */
    val networkBufferSeconds: Preference<Int> = preferenceStore.getInt(
        "pref_network_buffer_seconds",
        30,
    )
    // <-- AM (NETWORK_BUFFER_SECONDS)

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
        // AM (FREE_ORIENTATION_DEFAULT_FIX) -->
        // Free (full sensor) instead of SensorLandscape: the player
        // should allow rotation while it's on screen, not lock to
        // landscape out of the box. Users who explicitly picked an
        // orientation keep their stored value.
        PlayerOrientation.Free,
        // <-- AM (FREE_ORIENTATION_DEFAULT_FIX)
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

    // AM (CUSTOM_EPISODE_ORDER) -->
    /**
     * When autoplay reaches the end of a season, ask before rolling into the
     * next one instead of continuing automatically. Only meaningful for
     * entries split into seasons; manual next is never gated by this.
     */
    val askBeforeNextSeason: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_ask_before_next_season",
        true,
    )
    // <-- AM (CUSTOM_EPISODE_ORDER)

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

    // AM (CAST) -->
    // Cast

    val enableCast: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_cast", true)
    val castProxy: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_cast_proxy", true)
    val castProxyPort: Preference<String> = preferenceStore.getString("pref_cast_proxy_port", "8091")
    // <-- AM (CAST)

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
