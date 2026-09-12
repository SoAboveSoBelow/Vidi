package eu.kanade.tachiyomi.ui.player

// AM (PLAYER_FRESH_START_SPIKE) -->
// Throwaway prototype answering the real open question from the
// PLAYER_SCREEN_HOSTING_SPIKE runs: everything tested so far only ever
// ADOPTED a session PlayerActivity had already started - which is exactly
// why those runs kept racing against PlayerActivity's own still-active
// lifecycle callbacks (PIP entry, background fallback). The target
// architecture never needs that adoption at all for a normal playback
// start; it needs THIS instead - originating a session directly from a
// screen with no PlayerActivity ever created, so there's no second
// Activity for Android to send onUserLeaveHint/PIP to in the first place.
// This mirrors PlayerActivity.onCreate()'s own startService()+bindService()
// and PlayerActivity.mediaHolderConnection.onServiceConnected() (see both
// for the full, more complete version), deliberately simplified for a v1
// test: notification setup and the stop/toggle-play-pause callbacks wired
// there are NOT ported here yet - this only answers "can a screen bind the
// service and load an episode into a genuinely fresh player at all",
// nothing about the always-on notification. Refuses to run if a live
// PlayerMediaHolder already exists, to keep this cleanly isolated from
// anything already playing (adoption is the already-tested, separate
// scenario).
// Trigger via MainActivity's onNewIntent() debug hook with animeId/
// episodeId extras - see that hook's own doc comment.
// <-- AM (PLAYER_FRESH_START_SPIKE)

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.screens.LoadingScreen

class PlayerFreshStartScreenSpike(
    private val animeId: Long,
    private val episodeId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { context.appGraph }
        val scope = rememberCoroutineScope()

        var viewModel by remember { mutableStateOf<PlayerViewModel?>(null) }

        DisposableEffect(Unit) {
            // AM (SELF_CLEANING_FIX) -->
            // Was a hard refusal here if PlayerMediaHolder.current != null,
            // requiring the environment to already be perfectly clean before
            // this could be tested at all - which turned out to be genuinely
            // hard to guarantee live: this app auto-resumes the last-watched
            // episode on ordinary launch, independent of anything here,
            // repeatedly recreating exactly the state this refused to run
            // against. Tearing down any existing session directly instead -
            // PlayerMediaHolder.release() is the same, already-correct,
            // Activity-independent teardown PlayerActivity.onDestroy()'s own
            // genuine-teardown branch calls, so this is not a new/weaker
            // teardown path, just this spike driving the existing one itself
            // rather than depending on manual prep to avoid needing to.
            PlayerMediaHolder.current?.let { existing ->
                logcat(LogPriority.INFO) {
                    "PLAYER_FRESH_START_SPIKE tearing down existing holder=${System.identityHashCode(existing)} " +
                        "before originating a fresh session at=${SystemClock.elapsedRealtime()}"
                }
                existing.release()
                context.stopService(PlayerBackgroundPlaybackService.newIntent(context))
            }
            // <-- AM (SELF_CLEANING_FIX)

            val vm = graph.playerViewModelFactory.create(SavedStateHandle())
            logcat(LogPriority.INFO) {
                "PLAYER_FRESH_START_SPIKE constructed fresh viewModel=${System.identityHashCode(vm)} " +
                    "player=${System.identityHashCode(vm.player)} at=${SystemClock.elapsedRealtime()}"
            }

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as? PlayerBackgroundPlaybackService.LocalBinder ?: return
                    val holder = binder.getMediaHolder()
                    logcat(LogPriority.INFO) {
                        "PLAYER_FRESH_START_SPIKE onServiceConnected holder=${System.identityHashCode(holder)} " +
                            "at=${SystemClock.elapsedRealtime()}"
                    }
                    // No PlayerActivity involved at all, ever, for this session - not
                    // a race to close, structurally not applicable.
                    holder.hasExternalScreenConsumer = true
                    vm.bindToService(holder)

                    scope.launch {
                        val (initResult, loadResult) = vm.init(
                            animeId = animeId,
                            initialEpisodeId = episodeId,
                            hostList = "",
                            hostIndex = -1,
                            vidIndex = -1,
                        )
                        logcat(LogPriority.INFO) {
                            "PLAYER_FRESH_START_SPIKE init() returned initResult=$initResult " +
                                "loadResult=$loadResult at=${SystemClock.elapsedRealtime()}"
                        }

                        // AM (LOAD_HOSTERS_FIX) -->
                        // init() alone only resolves metadata/hoster list - it
                        // never actually loads video into mpv. Confirmed live
                        // on-device (2026-09-06): without this, the player
                        // screen and title show up but nothing plays until
                        // manually skipping, which happens to trigger this
                        // same call via a different path (changeEpisode()).
                        // This mirrors PlayerActivity.onNewIntent()'s own
                        // call, made exactly once, right after init()
                        // succeeds - see that call site's own surrounding
                        // code for the non-simplified version.
                        vm.loadHosters(
                            hosterList = initResult.hosterList ?: emptyList(),
                            hosterIndex = initResult.videoIndex.first,
                            videoIndex = initResult.videoIndex.second,
                        )
                        // <-- AM (LOAD_HOSTERS_FIX)
                    }

                    viewModel = vm
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    logcat(LogPriority.WARN) { "PLAYER_FRESH_START_SPIKE onServiceDisconnected" }
                }
            }

            context.startService(PlayerBackgroundPlaybackService.newIntent(context))
            context.bindService(
                PlayerBackgroundPlaybackService.newIntent(context),
                connection,
                android.content.Context.BIND_AUTO_CREATE,
            )

            onDispose {
                context.unbindService(connection)
            }
        }

        val currentViewModel = viewModel
        if (currentViewModel == null) {
            LoadingScreen()
            return
        }

        PlayerScreen(
            viewModel = currentViewModel,
            onBack = { navigator.pop() },
        )
    }
}
