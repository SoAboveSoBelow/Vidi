package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import animiru.domain.player.model.ControlAction
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.player.custombutton.CustomButtonDialog
import eu.kanade.presentation.more.settings.screen.player.custombutton.CustomButtonEvent
import eu.kanade.presentation.more.settings.screen.player.custombutton.CustomButtonScreenState
import eu.kanade.presentation.more.settings.screen.player.custombutton.PlayerSettingsCustomButtonViewModel
import eu.kanade.presentation.more.settings.screen.player.custombutton.components.CustomButtonCreateDialog
import eu.kanade.presentation.more.settings.screen.player.custombutton.components.CustomButtonEditDialog
import eu.kanade.tachiyomi.ui.player.controls.components.controlActionIcon
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.domain.custombutton.model.CustomButtonUpdate
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

object PlayerSettingsButtonsScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.pref_bottom_player_buttons

    @Composable
    override fun RowScope.AppBarAction() {
        val viewModel = metroViewModel<PlayerSettingsCustomButtonViewModel>()
        AppBarActions(
            actions = listOf(
                AppBar.Action(
                    title = stringResource(AYMR.strings.pref_player_custom_button_restore_defaults),
                    icon = Icons.Outlined.Restore,
                    onClick = viewModel::restoreDefaults,
                ),
            ),
        )
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val viewModel = metroViewModel<PlayerSettingsCustomButtonViewModel>()
        val state by viewModel.state.collectAsState()
        val successState = state as? CustomButtonScreenState.Success
        val customButtons = successState?.customButtons.orEmpty()

        when (val dialog = successState?.dialog) {
            null -> {}
            CustomButtonDialog.Create -> CustomButtonCreateDialog(
                onDismissRequest = viewModel::dismissDialog,
                onCreate = viewModel::createCustomButton,
                buttonNames = customButtons.map { it.name },
            )
            is CustomButtonDialog.Delete -> Unit
            is CustomButtonDialog.Edit -> CustomButtonEditDialog(
                onDismissRequest = viewModel::dismissDialog,
                onDelete = { viewModel.deleteCustomButton(dialog.customButton) },
                onEdit = { title, content, doubleTapContent, longPressContent, onStartup, icon, isToggle ->
                    viewModel.editCustomButton(
                        CustomButtonUpdate(
                            id = dialog.customButton.id,
                            name = title,
                            sortIndex = dialog.customButton.sortIndex,
                            content = content,
                            doubleTapContent = doubleTapContent,
                            longPressContent = longPressContent,
                            onStartup = onStartup,
                            icon = icon,
                            isToggle = isToggle,
                        ),
                    )
                },
                buttonNames = customButtons.filterNot { it.id == dialog.customButton.id }.map { it.name },
                initialState = dialog.customButton,
            )
        }

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { event ->
                if (event is CustomButtonEvent.LocalizedMessage) context.toast(event.stringRes)
            }
        }

        val buttonPreferences = customButtons.map { button ->
            Preference.PreferenceItem.TextPreference(
                title = button.name,
                icon = controlActionIcon(ControlAction.CustomButton(button.id, icon = button.icon)),
                onClick = { viewModel.showDialog(CustomButtonDialog.Edit(button)) },
            )
        }

        return buttonPreferences + Preference.PreferenceItem.TextPreference(
            title = stringResource(AYMR.strings.pref_player_custom_button_add),
            subtitle = stringResource(AYMR.strings.pref_player_custom_button_summary),
            onClick = { viewModel.showDialog(CustomButtonDialog.Create) },
        )
    }
}
