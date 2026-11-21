package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
// AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

object SettingsBrowseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { context.appGraph.sourcePreferences }
        val getExtensionStoreCountAsFlow = remember { context.appGraph.getExtensionStoreCountAsFlow }

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS) -->
                    // A list of fractions rather than a slider, matching how
                    // "at what point to mark as seen" is offered: the useful
                    // values are a handful of percentages and the difference
                    // between neighbouring ones matters, which is the case a
                    // list serves and a drag does not. Reuses that setting's
                    // percentage labels, which are plain numbers.
                    Preference.PreferenceItem.ListPreference(
                        preference = sourcePreferences.browsePageSkipThreshold,
                        entries = mapOf(
                            1.00F to stringResource(AYMR.strings.pref_progress_100),
                            0.95F to stringResource(AYMR.strings.pref_progress_95),
                            0.90F to stringResource(AYMR.strings.pref_progress_90),
                            0.85F to stringResource(AYMR.strings.pref_progress_85),
                            0.80F to stringResource(AYMR.strings.pref_progress_80),
                            0.75F to stringResource(AYMR.strings.pref_progress_75),
                            0.70F to stringResource(AYMR.strings.pref_progress_70),
                        ),
                        title = stringResource(AMMR.strings.am_pref_page_skip_threshold),
                        subtitle = stringResource(AMMR.strings.am_pref_page_skip_threshold_summary),
                    ),
                    // <-- AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        subtitle = pluralStringResource(MR.plurals.num_repos, reposCount.toInt(), reposCount),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_nsfw_content),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showNsfwSource,
                        title = stringResource(MR.strings.pref_show_nsfw_source),
                        subtitle = stringResource(MR.strings.requires_app_restart),
                        onValueChanged = {
                            (context as FragmentActivity).authenticate(
                                title = context.stringResource(MR.strings.pref_category_nsfw_content),
                            )
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.parental_controls_info)),
                ),
            ),
        )
    }
}
