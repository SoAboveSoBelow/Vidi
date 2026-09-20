package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.more.ReleaseNotesPage
import eu.kanade.presentation.more.ReleaseNotesUiState
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.util.system.openInBrowser
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shows the release notes for every version the user missed.
 *
 * [sinceVersion] is the versionName the user last saw notes for; null or blank means "just show
 * the installed version's notes", which is what the About screen entry wants.
 */
class WhatsNewScreen(
    private val sinceVersion: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<WhatsNewScreenModel, WhatsNewScreenModel.Factory> {
            create(sinceVersion = sinceVersion)
        }

        val state by viewModel.state.collectAsState()

        ReleaseNotesPage(
            headingText = stringResource(MR.strings.whats_new),
            subtitleText = stringResource(MR.strings.updated_version, BuildConfig.VERSION_NAME),
            state = state,
            onOpenInBrowser = {
                val link = (state as? ReleaseNotesUiState.Loaded)?.releaseLink ?: RELEASE_URL
                context.openInBrowser(link)
            },
            onRetry = viewModel::load,
            onDismiss = navigator::pop,
        )
    }
}
