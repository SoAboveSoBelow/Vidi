package eu.kanade.tachiyomi.ui.player

// AM (PLAYER_SCREEN_HOSTING_SPIKE) -->
// Throwaway prototype for scoping doc work item 2 ("MainActivity + player
// Screen") - see vidi-single-activity-scoping.md. Purpose: find out whether
// today's PlayerScreen() composable (already Activity-agnostic content) and
// PlayerViewModel (already decoupled from Android's ViewModelProvider since
// the SERVICE_OWNED_VIEWMODEL fix - see PlayerActivity.viewModel's own doc
// comment) can be hosted as a real Voyager Screen pushed onto MainActivity's
// existing Navigator, adopting whatever live session PlayerMediaHolder
// already holds, with no PlayerActivity involved for that content at all.
// Deliberately NOT wired into the real startPlayerActivity() path - see
// MainActivity's onNewIntent() debug hook for the isolated adb trigger. Not
// the target architecture's real player Screen - that absorbs whatever this
// proves out, once immersive-mode/orientation/PIP-trigger/service-binding
// logic (still all Activity-lifecycle-coupled in PlayerActivity today) gets
// ported across, which this spike deliberately does NOT attempt yet.
// <-- AM (PLAYER_SCREEN_HOSTING_SPIKE)

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat

class PlayerVoyagerScreenSpike : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { context.appGraph }

        val viewModel = remember {
            val holder = PlayerMediaHolder.current
            holder?.viewModel
                ?: holder?.adoptViewModel(graph.playerViewModelFactory.create(SavedStateHandle()))
        }

        if (viewModel == null) {
            logcat(LogPriority.WARN) {
                "PLAYER_SCREEN_HOSTING_SPIKE no live PlayerMediaHolder/viewModel - popping, nothing to host"
            }
            navigator.pop()
            return
        }

        logcat(LogPriority.INFO) {
            "PLAYER_SCREEN_HOSTING_SPIKE Content() hosting viewModel=${System.identityHashCode(viewModel)} " +
                "player=${System.identityHashCode(viewModel.player)}"
        }

        DisposableEffect(Unit) {
            // AM (EXTERNAL_SCREEN_CONSUMER_FIX) -->
            // See PlayerMediaHolder.hasExternalScreenConsumer's own doc comment
            // for the exact race this closes.
            val holder = PlayerMediaHolder.current
            holder?.hasExternalScreenConsumer = true
            onDispose {
                holder?.hasExternalScreenConsumer = false
            }
            // <-- AM (EXTERNAL_SCREEN_CONSUMER_FIX)
        }

        PlayerScreen(
            viewModel = viewModel,
            onBack = { navigator.pop() },
        )
    }
}
