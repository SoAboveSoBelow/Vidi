package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.PlayerMediaHolder
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

object PlayerSettingsControlsScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.pref_player_controls

    @Composable
    override fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.currentOrThrow

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(AYMR.strings.pref_player_controls_player),
                subtitle = stringResource(AYMR.strings.pref_player_controls_summary),
                onClick = { PlayerMediaHolder.openLayoutEditor() },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(AYMR.strings.pref_player_gestures),
                subtitle = stringResource(AYMR.strings.pref_player_gestures_summary),
                onClick = { navigator.push(PlayerSettingsGesturesScreen) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(AYMR.strings.pref_bottom_player_buttons),
                subtitle = stringResource(AYMR.strings.pref_player_custom_button_summary),
                onClick = { navigator.push(PlayerSettingsButtonsScreen) },
            ),
        )
    }
}
