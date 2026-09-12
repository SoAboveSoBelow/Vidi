package eu.kanade.tachiyomi.ui.player

// AM (PLAYER_HOST_SCREEN) -->
// The real cutover of MainActivity.startPlayerActivity()'s internal path -
// see vidi-single-activity-scoping.md work item 2. Built from what
// PlayerFreshStartScreenSpike and PlayerVoyagerScreenSpike already proved
// on-device this session, plus a direct port of PlayerActivity.onNewIntent()'s
// three-case live-reuse logic (see that function's own LIVE_REDELIVERY_TRUST_FIX
// doc comment for the un-simplified version) - same anime+episode is a no-op,
// same anime different episode calls the existing, already-safe
// viewModel.changeEpisode() directly, a different anime entirely tears down
// and starts fresh (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX's reasoning: hot-swap
// caused flash/bleed).
//
// NOT yet done, deliberately, same as every prior step in this session:
// notification setup, NotificationReceiver/DeepLinkScreen (still target
// PlayerActivity directly - only MainActivity.startPlayerActivity()'s
// internal branch is cut over here), and real hoster/video-index parameters
// beyond what's threaded through from the caller.
// <-- AM (PLAYER_HOST_SCREEN)

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
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.screens.LoadingScreen

class PlayerHostScreen(
    private val animeId: Long,
    private val episodeId: Long,
    private val hosterList: List<Hoster>? = null,
    private val hosterIndex: Int = -1,
    private val videoIndex: Int = -1,
    private val forceResume: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { context.appGraph }
        val scope = rememberCoroutineScope()

        var viewModel by remember { mutableStateOf<PlayerViewModel?>(null) }

        // AM (PLAYER_HOST_SCREEN) -->
        // Runs once, at this screen instance's first composition - see the
        // caller-side reuse check in MainActivity.startPlayerActivity()'s
        // else-branch for why a NEW PlayerHostScreen instance (running this
        // fresh) is only ever pushed for the "no live session" or "different
        // anime" cases; the "same anime" cases are handled by that caller
        // directly against the already-composed screen's live viewModel,
        // without pushing a new instance at all (Voyager's default screen
        // key is a fresh uniqueScreenKey per instance, not content-based -
        // confirmed by reading eu.kanade.presentation.util.Navigator.kt - so
        // two equally-parameterized instances are never automatically
        // deduplicated by the framework itself).
        // <-- AM (PLAYER_HOST_SCREEN)
        DisposableEffect(Unit) {
            val existingHolder = PlayerMediaHolder.current
            val existingViewModel = existingHolder?.takeIf { it.hasAdoptedPlayer }?.viewModel
            val existingState = existingViewModel?.stateData?.value

            if (existingViewModel != null && existingState?.currentAnime?.id == animeId) {
                // AM (PLAYER_HOST_SCREEN_EPISODE_SWITCH_FIX) -->
                // Was missing entirely - adopted whatever session was live
                // unconditionally, with no comparison against this screen's
                // own target episodeId at all. Confirmed live on-device
                // (2026-09-06): tapping a different episode after backing
                // out to the episode list (so this branch runs via a freshly
                // pushed PlayerHostScreen instance, not the caller-side
                // already-on-top check in startPlayerActivity()) just
                // reopened the same episode that was already playing,
                // because nothing here ever called changeEpisode(). Same
                // same-anime/different-episode case PlayerActivity.
                // onNewIntent() already handles - see that function's own
                // LIVE_REDELIVERY_TRUST_FIX doc comment.
                if (existingState.currentEpisode?.id != episodeId) {
                    logcat(LogPriority.INFO) {
                        "PLAYER_HOST_SCREEN_EPISODE_SWITCH_FIX changeEpisode() " +
                            "from=${existingState.currentEpisode?.id} to=$episodeId"
                    }
                    existingViewModel.changeEpisode(episodeId)
                }
                // <-- AM (PLAYER_HOST_SCREEN_EPISODE_SWITCH_FIX)
                logcat(LogPriority.INFO) {
                    "PLAYER_HOST_SCREEN reusing existing viewModel=${System.identityHashCode(existingViewModel)} " +
                        "for animeId=$animeId episodeId=$episodeId"
                }
                existingHolder.hasExternalScreenConsumer = true
                viewModel = existingViewModel
                onDispose { existingHolder.hasExternalScreenConsumer = false }
            } else {
                if (existingHolder != null) {
                    logcat(LogPriority.INFO) {
                        "PLAYER_HOST_SCREEN tearing down stale holder=${System.identityHashCode(existingHolder)} " +
                            "before originating a fresh session"
                    }
                    existingHolder.release()
                    context.stopService(PlayerBackgroundPlaybackService.newIntent(context))
                }

                val vm = graph.playerViewModelFactory.create(SavedStateHandle())
                logcat(LogPriority.INFO) {
                    "PLAYER_HOST_SCREEN constructed fresh viewModel=${System.identityHashCode(vm)} " +
                        "animeId=$animeId episodeId=$episodeId"
                }

                val connection = object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                        val binder = service as? PlayerBackgroundPlaybackService.LocalBinder ?: return
                        val holder = binder.getMediaHolder()
                        holder.hasExternalScreenConsumer = true
                        vm.bindToService(holder)

                        // AM (PLAYER_HOST_SCREEN_NOTIFICATION_FIX) -->
                        // Was entirely missing - every session originated through this
                        // screen had no persistent notification/background-audio
                        // controls at all, a real regression for daily use once
                        // MainActivity.startPlayerActivity() actually started routing
                        // here. This is a direct port of PlayerActivity's own
                        // onServiceConnected() call to the same
                        // PlayerBackgroundPlaybackService.start() - see that call
                        // site's own doc comments (SECURE_LOCK_BACKGROUND_PLAYBACK,
                        // NOTIFICATION_CREATION_STALE_SNAPSHOT_FIX) for why no title/
                        // subtitle/animeId/episodeId snapshot is passed here either -
                        // the holder's own reactive state observer is the sole writer
                        // of that data, unconditionally, regardless of which Activity
                        // or screen originated the session. onStopRequested pops this
                        // screen instead of PlayerActivity's finish() - same intent
                        // (ending the whole session), adapted to Voyager navigation.
                        binder.getService().start(
                            isPlaying = !vm.playbackData.value.paused,
                            mediaSessionToken = holder.mediaSession?.sessionToken,
                            onTogglePlayPause = {
                                if (vm.playbackData.value.paused) vm.unpause() else vm.pause()
                                binder.getService().updatePlaybackState(!vm.playbackData.value.paused)
                            },
                            onStopRequested = {
                                vm.pause()
                                context.stopService(PlayerBackgroundPlaybackService.newIntent(context))
                                navigator.pop()
                            },
                        )
                        // <-- AM (PLAYER_HOST_SCREEN_NOTIFICATION_FIX)

                        scope.launch {
                            val (initResult, loadResult) = vm.init(
                                animeId = animeId,
                                initialEpisodeId = episodeId,
                                hostList = hosterList?.let { it.serialize() } ?: "",
                                hostIndex = hosterIndex,
                                vidIndex = videoIndex,
                            )
                            logcat(LogPriority.INFO) {
                                "PLAYER_HOST_SCREEN init() returned initResult=$initResult loadResult=$loadResult"
                            }
                            vm.loadHosters(
                                hosterList = initResult.hosterList ?: emptyList(),
                                hosterIndex = initResult.videoIndex.first,
                                videoIndex = initResult.videoIndex.second,
                            )
                        }

                        viewModel = vm
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        logcat(LogPriority.WARN) { "PLAYER_HOST_SCREEN onServiceDisconnected" }
                    }
                }

                context.startService(PlayerBackgroundPlaybackService.newIntent(context))
                context.bindService(
                    PlayerBackgroundPlaybackService.newIntent(context),
                    connection,
                    android.content.Context.BIND_AUTO_CREATE,
                )

                onDispose { context.unbindService(connection) }
            }
        }

        val currentViewModel = viewModel
        if (currentViewModel == null) {
            LoadingScreen()
            return
        }

        // AM (SELF_PIP_AUTO_ENTER_FIX) -->
        // Keeps MainActivity.updateAutoEnterPipParams() (see that function's
        // own doc comment) fresh whenever pause state changes, and once
        // right away for a session that's already playing when this screen
        // composes - Recents/Overview auto-enter reads whatever was last
        // registered, not anything computed at the moment of leaving.
        // <-- AM (SELF_PIP_AUTO_ENTER_FIX)
        DisposableEffect(currentViewModel) {
            val mainActivity = context as? MainActivity
            val job = scope.launch {
                currentViewModel.playbackData.collect {
                    mainActivity?.updateAutoEnterPipParams()
                }
            }
            onDispose { job.cancel() }
        }

        PlayerScreen(
            viewModel = currentViewModel,
            onBack = { navigator.pop() },
        )
    }
}
