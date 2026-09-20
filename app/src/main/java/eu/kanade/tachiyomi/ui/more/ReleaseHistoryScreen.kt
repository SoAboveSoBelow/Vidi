package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.ReleaseHistoryContent
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class ReleaseHistoryScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ReleaseHistoryScreenModel>()
        val state by viewModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(AMMR.strings.release_history),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ReleaseHistoryContent(
                state = state,
                contentPadding = contentPadding,
                onReleaseClick = {
                    navigator.push(
                        ReleaseNotesScreen(
                            version = it.version,
                            info = it.info,
                            releaseLink = it.releaseLink,
                        ),
                    )
                },
                onLoadMore = viewModel::loadNextPage,
            )
        }
    }
}
