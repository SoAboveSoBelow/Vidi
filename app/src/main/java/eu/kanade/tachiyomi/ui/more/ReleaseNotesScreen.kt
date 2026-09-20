package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.ReleaseNotesPage
import eu.kanade.presentation.more.ReleaseNotesUiState
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.openInBrowser
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Notes for a single past release, rendered with the same page as the post-update What's new
 * flow. The body is passed in rather than re-fetched: the list endpoint already returned it, and
 * the GitHub API is rate limited per request, so fetching here would cost one request for every
 * version the user opens.
 */
class ReleaseNotesScreen(
    private val version: String,
    private val info: String,
    private val releaseLink: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val state = remember(version, info, releaseLink) {
            ReleaseNotesUiState.Loaded(
                changelogInfo = info,
                latestVersion = version,
                releaseLink = releaseLink,
            )
        }

        ReleaseNotesPage(
            headingText = version,
            subtitleText = stringResource(MR.strings.whats_new),
            state = state,
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onRetry = {},
            onDismiss = navigator::pop,
        )
    }
}
