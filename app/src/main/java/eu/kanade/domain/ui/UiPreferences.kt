package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.StartScreen
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.Constants
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    preferenceStore: PreferenceStore,
) {

    val themeMode: Preference<ThemeMode> = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    val appTheme: Preference<AppTheme> = preferenceStore.getEnum(
        "pref_app_theme",
        if (DeviceUtil.isDynamicColorAvailable) {
            AppTheme.MONET
        } else {
            AppTheme.DEFAULT
        },
    )

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    // AY -->
    val startScreen: Preference<StartScreen> = preferenceStore.getEnum("start_screen", StartScreen.ANIME)
    // <-- AY

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    // AM (LAST_LOCATION) -->
    // Which top-level Home tab (as one of the Constants.SHORTCUT_* actions already used
    // for deep-linking) the user last had open, and which anime's detail screen - if any -
    // was on top of it. Used to rebuild the real navigation stack when reopening the app
    // from the background-playback notification after the original task/back stack was
    // lost, instead of always falling back to a fixed tab. Backed by the same disk-persisted
    // PreferenceStore as everything else here, so it survives process death, not just
    // Activity recreation.
    val lastVisitedTabAction: Preference<String> = preferenceStore.getString(
        "last_visited_tab_action",
        Constants.SHORTCUT_LIBRARY,
    )

    val lastVisitedAnimeId: Preference<Long> = preferenceStore.getLong("last_visited_anime_id", -1L)
    // <-- AM (LAST_LOCATION)

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
