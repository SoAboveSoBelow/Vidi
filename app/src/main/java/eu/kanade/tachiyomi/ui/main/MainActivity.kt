package eu.kanade.tachiyomi.ui.main

import android.animation.ValueAnimator
import android.app.PictureInPictureParams
import android.app.SearchManager
import android.app.assist.AssistContent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import animiru.feature.mpvfiles.MpvConfig
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connection.service.ConnectionPreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.presentation.components.DownloadedOnlyBannerBackgroundColor
import eu.kanade.presentation.components.IncognitoModeBannerBackgroundColor
import eu.kanade.presentation.components.IndexingBannerBackgroundColor
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.connection.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.player.service.HttpServerService
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.player.ExternalIntents
import eu.kanade.tachiyomi.ui.player.PIP_BACKGROUND_PLAY
import eu.kanade.tachiyomi.ui.player.PIP_INTENT_ACTION
import eu.kanade.tachiyomi.ui.player.PIP_INTENTS_FILTER
import eu.kanade.tachiyomi.ui.player.PIP_NEXT
import eu.kanade.tachiyomi.ui.player.PIP_PAUSE
import eu.kanade.tachiyomi.ui.player.PIP_PLAY
import eu.kanade.tachiyomi.ui.player.PIP_PREVIOUS
import eu.kanade.tachiyomi.ui.player.PIP_SKIP
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerBackgroundPlaybackService
import eu.kanade.tachiyomi.ui.player.PlayerFreshStartScreenSpike
import eu.kanade.tachiyomi.ui.player.PlayerHostScreen
import eu.kanade.tachiyomi.ui.player.PlayerMediaHolder
import eu.kanade.tachiyomi.ui.player.PlayerVoyagerScreenSpike
import eu.kanade.tachiyomi.ui.player.createPipActions
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isBenchmarkBuildType
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import mihon.core.migration.Migrator
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MainActivity : BaseActivity() {

    private val graph: AppGraph by lazy { metroGraph() }

    @Inject private lateinit var preferences: BasePreferences

    // AM -->
    @Inject private lateinit var mpvConfig: MpvConfig

    @Inject private lateinit var uiPreferences: UiPreferences

    @Inject private lateinit var externalIntents: ExternalIntents
    // <-- AM

    // AM (CONNECTION) -->
    @Inject private lateinit var connectionPreferences: ConnectionPreferences
    // <-- AM (CONNECTION)

    @Inject private lateinit var downloadCache: DownloadCache

    @Inject private lateinit var getIncognitoState: GetIncognitoState

    @Inject private lateinit var extensionApi: ExtensionApi

    // To be checked by splash screen. If true then splash screen will be removed.
    var ready = false

    // AM (PLAYER_HOST_SCREEN) -->
    // Was private - widened so the companion object's startPlayerActivity()
    // can push/inspect it directly, matching every other real call site
    // reaching this instance the same way (as MainActivity, via context).
    // <-- AM (PLAYER_HOST_SCREEN)
    internal var navigator: Navigator? = null

    init {
        registerSecureActivity(this)
    }

    // AM (PLAYER_SCREEN_HOSTING_SPIKE) -->
    // Throwaway, adb-only trigger for PlayerVoyagerScreenSpike - see that
    // class's own doc comment. Not part of the real notification-reopen or
    // deep-link routing in handleIntentAction() below; that still only runs
    // on cold launch (isLaunch), and MainActivity has never overridden
    // onNewIntent() before this - real reopen-while-running routing (what
    // item 4 will need for real) is intentionally out of scope for this
    // spike, which only needs *a* way to push onto the live Navigator while
    // MainActivity's already running. Trigger with:
    //   adb shell am start -n xyz.Quickdev.Vidi.mi.dev/eu.kanade.tachiyomi.ui.main.MainActivity \
    //     -a xyz.Quickdev.Vidi.mi.dev.PLAYER_SCREEN_HOSTING_SPIKE
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "${BuildConfig.APPLICATION_ID}.PLAYER_SCREEN_HOSTING_SPIKE") {
            // AM (EXTERNAL_SCREEN_CONSUMER_ORDERING_FIX) -->
            // Was only set reactively inside PlayerVoyagerScreenSpike's own
            // Content()/DisposableEffect - confirmed live on-device
            // (2026-09-06) that's too late: a logcat capture showed
            // ActivityTaskManagerService.enterPictureInPictureMode()/
            // moveActivityToPinnedRootTask already committed on
            // PlayerActivity ~80ms BEFORE Content() ever ran, since Android
            // dispatches the leave-app lifecycle callbacks that trigger it
            // (part of just bringing this Task to front) well before this
            // Activity's own Compose recomposition catches up. Setting it
            // here instead - synchronous, before push(), before this method
            // even returns - actually beats that race instead of reacting
            // to it too late. The DisposableEffect's onDispose clear is
            // still correct as the counterpart for when the screen goes
            // away.
            PlayerMediaHolder.current?.hasExternalScreenConsumer = true
            // <-- AM (EXTERNAL_SCREEN_CONSUMER_ORDERING_FIX)
            navigator?.push(PlayerVoyagerScreenSpike())
        }
        // AM (PLAYER_FRESH_START_SPIKE) -->
        // Separate debug action from PLAYER_SCREEN_HOSTING_SPIKE above -
        // that one adopts a session PlayerActivity already started; this one
        // originates a session with no PlayerActivity involved at all, ever.
        // See PlayerFreshStartScreenSpike's own doc comment. Trigger with:
        //   adb shell am start -n xyz.Quickdev.Vidi.mi.dev/eu.kanade.tachiyomi.ui.main.MainActivity \
        //     -a xyz.Quickdev.Vidi.mi.dev.PLAYER_FRESH_START_SPIKE \
        //     --el animeId <id> --el episodeId <id>
        if (intent.action == "${BuildConfig.APPLICATION_ID}.PLAYER_FRESH_START_SPIKE") {
            val animeId = intent.getLongExtra("animeId", -1L)
            val episodeId = intent.getLongExtra("episodeId", -1L)
            if (animeId != -1L && episodeId != -1L) {
                navigator?.push(PlayerFreshStartScreenSpike(animeId, episodeId))
            }
        }
        // <-- AM (PLAYER_FRESH_START_SPIKE)

        // AM (PLAYER_HOST_SCREEN_REOPEN_FIX) -->
        // Real (non-debug) handler for the background-playback notification's
        // tap-to-reopen action - see PlayerBackgroundPlaybackService.
        // buildReopenPendingIntent()'s own doc comment for the bug this
        // fixes. Reuses startPlayerActivity()'s own reuse-or-push decision
        // rather than duplicating it, so tapping the notification behaves
        // exactly like tapping the same episode again from inside the app.
        if (intent.action == "${BuildConfig.APPLICATION_ID}.REOPEN_PLAYER_HOST_SCREEN") {
            val animeId = intent.getLongExtra("animeId", -1L)
            val episodeId = intent.getLongExtra("episodeId", -1L)
            if (animeId != -1L && episodeId != -1L) {
                lifecycleScope.launch {
                    startPlayerActivity(context = this@MainActivity, animeId = animeId, episodeId = episodeId, extPlayer = false)
                }
            }
        }
        // <-- AM (PLAYER_HOST_SCREEN_REOPEN_FIX)
    }
    // <-- AM (PLAYER_SCREEN_HOSTING_SPIKE)

    // AM (PLAYER_SCREEN_KEY_HANDLING_SPIKE) -->
    // Port of PlayerActivity.onKeyDown()/onKeyUp() - see that pair's own
    // comments for the un-simplified version. All the actual logic there was
    // already pure viewModel.* calls, so this is a straight copy, just
    // gated on hasExternalScreenConsumer (see that property's own doc
    // comment) instead of "this IS the player Activity", since MainActivity
    // hosts plenty of other screens these keys shouldn't touch. One
    // deliberate change: PlayerActivity's KEYCODE_MEDIA_STOP case called
    // finishAndRemoveTask() on itself - not applicable here (MainActivity
    // is never meant to finish for this), so it calls the same session
    // teardown PlayerFreshStartScreenSpike already uses instead, then pops
    // back to whatever screen was showing before the player.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val holder = PlayerMediaHolder.current
        val viewModel = holder?.viewModel
        if (holder?.hasExternalScreenConsumer != true || viewModel == null) {
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.changeVolumeBy(1)
                viewModel.displayVolumeSlider(true)
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.changeVolumeBy(-1)
                viewModel.displayVolumeSlider(true)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.handleLeftDoubleTap()
            KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.handleRightDoubleTap()
            KeyEvent.KEYCODE_SPACE -> viewModel.pauseUnpause()
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                holder.release()
                stopService(PlayerBackgroundPlaybackService.newIntent(this))
                navigator?.pop()
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> viewModel.handleLeftDoubleTap()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> viewModel.handleRightDoubleTap()

            else -> {
                event?.let { viewModel.onKey(it) }
                return super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val holder = PlayerMediaHolder.current
        val viewModel = holder?.viewModel
        if (holder?.hasExternalScreenConsumer != true || viewModel == null) {
            return super.onKeyUp(keyCode, event)
        }
        if (event != null && viewModel.onKey(event)) return true
        return super.onKeyUp(keyCode, event)
    }
    // <-- AM (PLAYER_SCREEN_KEY_HANDLING_SPIKE)

    // AM (SELF_PIP_ENTRY) -->
    // Reverted from a separate PipActivity (work item 3 as scoped) after
    // confirming live on-device (2026-09-06) that it caused a genuine
    // regression: launching a distinct Activity from onUserLeaveHint() left
    // MainActivity's own task backgrounded-but-visible instead of properly
    // going behind the launcher, and required an explicit
    // FLAG_ACTIVITY_NEW_TASK fix that still only got the task-switching
    // right, not the actual goal - a real single-Activity player mimics
    // YouTube (and this codebase's OWN existing PlayerActivity, which has
    // always done exactly this): the SAME Activity that's showing the video
    // calls enterPictureInPictureMode() on itself and the OS resizes that
    // one window in place. No second Activity, no second task, no return-
    // handoff to build - the window that shrinks is the window that expands
    // back, because it's the only window there ever was. This function
    // replaces onUserLeaveHint()'s old PipActivity-launch entirely.
    //
    // Deliberately simpler than PlayerActivity.createPipParams()/
    // tryEnterPictureInPicture() for this first pass - ported the RESUMED
    // guard (a confirmed real crash, not hypothetical - see
    // PlayerActivity.enterPipIfEligible()'s own doc comment) and real
    // aspect-ratio-from-video-dimensions, since both are simple and
    // self-contained. NOT yet ported: PIP action buttons, auto-enter-on-
    // Recents, source-rect-hint, and the app-lock (SecureActivityDelegate)
    // interaction PlayerActivity's version has several fixes for - those are
    // real, separately-scoped follow-up work, not attempted blind here.
    // Same reasoning as onUserLeaveHint() below for not attempting
    // PlayerActivity's Home-vs-Recents distinguishing logic: the Recents
    // case is a known, still-open, OS/OEM-level risk per the scoping doc's
    // own open-risks section.
    // AM (SELF_PIP_AUTO_ENTER_FIX) -->
    // Was missing pipOnExit's own preference gate entirely, and only ever
    // called enterPictureInPictureMode() reactively from onUserLeaveHint() -
    // which Android deliberately does NOT fire for Recents/Overview, only
    // Home-press (by design, not an OEM quirk - confirmed by Dan directly,
    // and matching Android's own documented behavior). PlayerActivity
    // already solves this correctly via proactive
    // setPictureInPictureParams(..., autoEnterEnabled=true) registration -
    // see its own createPipParams()'s AUTO_PIP_LOOP_FIX/
    // PIP_AUTOENTER_SELF_POISON_FIX doc comments for the real history here:
    // a self-poisoning auto-re-entry loop that took real on-device
    // debugging to track down and fix. This is a DELIBERATELY minimal
    // version of that same idea - registers auto-enter=true once a session
    // starts playing and once whenever pause state changes, nothing else -
    // and does NOT port either of those two loop-prevention guards, since
    // neither's trigger condition exists in this path yet (no headphone-
    // action finish(), no SecureActivityDelegate/app-lock interaction
    // here). That means this needs real on-device verification for loop
    // behavior specifically, more than most other pieces built this
    // session - if PIP re-enters itself repeatedly/immediately after being
    // dismissed, that's this simplification, not a new unrelated bug.
    private fun buildSelfPipParams(autoEnter: Boolean): PictureInPictureParams? {
        val holder = PlayerMediaHolder.current
        val viewModel = holder?.viewModel
        if (holder?.hasExternalScreenConsumer != true || viewModel == null) return null

        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val anime = viewModel.stateData.value.currentAnime
            val episode = viewModel.stateData.value.currentEpisode
            if (anime != null && episode != null) {
                builder.setTitle(anime.title).setSubtitle(episode.name)
            }
        }
        viewModel.stateData.value.let {
            val rational = if (it.videoWidth > 0 && it.videoHeight > 0) {
                Rational(it.videoWidth, it.videoHeight)
            } else {
                Rational(16, 9)
            }
            if (rational.toDouble() in 0.42..2.38) {
                builder.setAspectRatio(rational)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val shouldAutoEnter = autoEnter &&
                !viewModel.playbackData.value.paused &&
                graph.playerPreferences.pipOnExit.get()
            builder.setAutoEnterEnabled(shouldAutoEnter)
        }
        builder.setActions(
            createPipActions(
                context = this,
                isPaused = viewModel.playbackData.value.paused,
                firstButtonAction = graph.playerPreferences.pipFirstButtonAction.get(),
                playlistCount = viewModel.stateData.value.currentPlaylist.size,
                playlistPosition = viewModel.stateData.value.currentPlaylistIndex,
            ),
        )
        return builder.build()
    }

    // Proactively registers current PIP params with the OS - this is what
    // lets Recents/Overview auto-enter PIP without ever calling
    // enterPictureInPictureMode() directly (see this function's own doc
    // comment above for why onUserLeaveHint() alone can't cover that case).
    // Safe to call whenever relevant state changes; a no-op if nothing's
    // actually eligible.
    fun updateAutoEnterPipParams() {
        if (lifecycle.currentState != Lifecycle.State.RESUMED && lifecycle.currentState != Lifecycle.State.STARTED) return
        val params = buildSelfPipParams(autoEnter = true) ?: return
        try {
            setPictureInPictureParams(params)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SELF_PIP_AUTO_ENTER_FIX setPictureInPictureParams failed: $e" }
        }
    }
    // <-- AM (SELF_PIP_AUTO_ENTER_FIX)

    private fun enterSelfPipIfEligible() {
        if (isInPictureInPictureMode || lifecycle.currentState != Lifecycle.State.RESUMED) return
        val holder = PlayerMediaHolder.current
        if (holder?.hasExternalScreenConsumer != true || holder.viewModel?.playbackData?.value?.paused != false) {
            return
        }
        val params = buildSelfPipParams(autoEnter = false) ?: return
        try {
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SELF_PIP_ENTRY enterPictureInPictureMode failed: $e" }
        }
    }

    override fun onUserLeaveHint() {
        enterSelfPipIfEligible()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Matches PlayerActivity.onConfigurationChanged()'s own existing
        // pattern for the same purpose.
        if (isInPictureInPictureMode) {
            PlayerMediaHolder.current?.viewModel?.hideControls()
        }
        // AM (SELF_PIP_ACTIONS_FIX) -->
        // Registered/unregistered exactly when PlayerActivity's own
        // pipReceiver is - only while actually in PIP. See
        // pipActionsReceiver's own doc comment for what's ported and what
        // isn't.
        if (isInPictureInPictureMode) {
            val filter = IntentFilter(PIP_INTENTS_FILTER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipActionsReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pipActionsReceiver, filter)
            }
        } else {
            try {
                unregisterReceiver(pipActionsReceiver)
            } catch (e: IllegalArgumentException) {
                // Not registered - fine, matches PlayerActivity's own
                // null-checked unregister for the same reason.
            }
        }
        // <-- AM (SELF_PIP_ACTIONS_FIX)
    }

    // AM (SELF_PIP_ACTIONS_FIX) -->
    // Direct port of PlayerActivity's own pipReceiver for the simple cases
    // (pause/play/next/previous/skip - all plain viewModel calls, no
    // Activity-lifecycle involvement). Deliberately NOT porting
    // PIP_BACKGROUND_PLAY - PlayerActivity's version of that action is
    // built entirely around finish()ing itself in a specific, carefully-
    // sequenced way (see that receiver's own PIP_MOVETASKTOBACK_RACE_FIX/
    // AUTO_PIP_LOOP_FIX doc comments) to survive its own destruction model.
    // MainActivity is never meant to finish() for this at all in the new
    // architecture, so that logic doesn't have an equivalent to port yet -
    // this logs clearly instead of silently doing nothing, so a tap on it
    // is diagnosable rather than a mysterious no-op.
    private val pipActionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != PIP_INTENTS_FILTER) return
            val holder = PlayerMediaHolder.current
            val viewModel = holder?.viewModel
            if (holder?.hasExternalScreenConsumer != true || viewModel == null) return
            when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
                PIP_PAUSE -> viewModel.pause()
                PIP_PLAY -> viewModel.unpause()
                PIP_NEXT -> viewModel.nextEpisode(next = true)
                PIP_PREVIOUS -> viewModel.nextEpisode(next = false)
                PIP_SKIP -> viewModel.seekBy(10)
                PIP_BACKGROUND_PLAY -> {
                    // AM (SELF_PIP_BACKGROUND_PLAY_FIX) -->
                    // PlayerActivity's version of this exists to finish()
                    // itself while surviving via its own session-preservation
                    // model - not applicable here, MainActivity is never
                    // meant to finish() at all. moveTaskToBack() is the
                    // actual semantically-correct action for this Activity,
                    // not a workaround. One thing worth verifying live,
                    // though: the scoping doc itself flags moveTaskToBack()
                    // while genuinely PIP-pinned as an open risk needing
                    // dumpsys verification, not assumption -
                    // PlayerActivity's own PIP_FINISH_NOT_MOVETASKTOBACK
                    // fix found real OS-level pinned-state corruption from
                    // doing exactly this, and that finding was about the
                    // OS's own behavior, not something specific to
                    // PlayerActivity's code - so this needs the same kind
                    // of on-device confirmation before fully trusting it.
                    //
                    // startBackgroundPlayback()'s actual job (see its own
                    // doc comment) is only an app-lock exemption toggle -
                    // the notification/Service already keep playback alive
                    // unconditionally now (confirmed working since
                    // PLAYER_HOST_SCREEN_NOTIFICATION_FIX), so nothing
                    // further is needed there. No app-lock exemption toggle
                    // here either - SecureActivityDelegate isn't wired into
                    // this architecture at all yet, separate, not-yet-done
                    // work, same as everywhere else this session.
                    if (!graph.playerPreferences.backgroundPlayback.get()) {
                        viewModel.pause()
                    }
                    moveTaskToBack(true)
                    return
                    // <-- AM (SELF_PIP_BACKGROUND_PLAY_FIX)
                }
            }
            updateAutoEnterPipParams()
        }
    }
    // <-- AM (SELF_PIP_ACTIONS_FIX)
    // <-- AM (SELF_PIP_ENTRY)


    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        val isLaunch = savedInstanceState == null

        // Prevent splash screen showing up on configuration changes
        val splashScreen = if (isLaunch) installSplashScreen() else null

        super.onCreate(savedInstanceState)

        val didMigration = Migrator.awaitAndRelease()

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        setComposeContent {
            val context = LocalContext.current

            var incognito by remember { mutableStateOf(getIncognitoState.await(null)) }
            val downloadOnly by preferences.downloadedOnly.collectAsState()
            val indexing by downloadCache.isInitializing.collectAsState()

            val isSystemInDarkTheme = isSystemInDarkTheme()
            val statusBarBackgroundColor = when {
                indexing -> IndexingBannerBackgroundColor
                downloadOnly -> DownloadedOnlyBannerBackgroundColor
                incognito -> IncognitoModeBannerBackgroundColor
                else -> MaterialTheme.colorScheme.surface
            }
            LaunchedEffect(isSystemInDarkTheme, statusBarBackgroundColor) {
                // Draw edge-to-edge and set system bars color to transparent
                val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(
                    statusBarStyle = if (statusBarBackgroundColor.luminance() > 0.5) lightStyle else darkStyle,
                    navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
                )
            }

            Navigator(
                screen = HomeScreen,
                disposeBehavior = NavigatorDisposeBehavior(disposeNestedNavigators = false, disposeSteps = true),
            ) { navigator ->
                LaunchedEffect(navigator) {
                    this@MainActivity.navigator = navigator

                    if (isLaunch) {
                        // Set start screen
                        handleIntentAction(intent, navigator)

                        // Reset Incognito Mode on relaunch
                        preferences.incognitoMode.set(false)
                    }
                }
                LaunchedEffect(navigator.lastItem) {
                    (navigator.lastItem as? BrowseSourceScreen)?.sourceId
                        .let(getIncognitoState::subscribe)
                        .collectLatest { incognito = it }
                }

                val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                Scaffold(
                    topBar = {
                        AppStateBanners(
                            downloadedOnlyMode = downloadOnly,
                            incognitoMode = incognito,
                            indexing = indexing,
                            modifier = Modifier.windowInsetsPadding(scaffoldInsets),
                        )
                    },
                    contentWindowInsets = scaffoldInsets,
                ) { contentPadding ->
                    // Consume insets already used by app state banners
                    Box {
                        // Shows current screen
                        DefaultNavigatorScreenTransition(
                            navigator = navigator,
                            modifier = Modifier
                                .padding(contentPadding)
                                .consumeWindowInsets(contentPadding),
                        )

                        // Draw navigation bar scrim when needed
                        if (remember { isNavigationBarNeedsScrim() }) {
                            Spacer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                    .alpha(0.8f)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                }

                // Pop source-related screens when incognito mode is turned off
                LaunchedEffect(Unit) {
                    preferences.incognitoMode.changes()
                        .drop(1)
                        .filter { !it }
                        .onEach {
                            val currentScreen = navigator.lastItem
                            if (currentScreen is BrowseSourceScreen ||
                                (currentScreen is AnimeScreen && currentScreen.fromSource)
                            ) {
                                navigator.popUntilRoot()
                            }
                        }
                        .launchIn(this)

                    // AM (DISCORD_RPC) -->
                    val appContext = this@MainActivity.applicationContext
                    connectionPreferences.enableDiscordRPC.changes()
                        .drop(1)
                        .onEach {
                            if (it) {
                                DiscordRPCService.start(appContext)
                            } else {
                                DiscordRPCService.stop(appContext, 0L)
                            }
                        }.launchIn(this)
                    // <-- AM (DISCORD_RPC)
                }

                HandleOnNewIntent(context = context, navigator = navigator)

                if (!isBenchmarkBuildType) {
                    if (isLaunch) CheckForUpdates()
                    ShowOnboarding()
                }
            }

            var showChangelog by remember { mutableStateOf(didMigration && !BuildConfig.DEBUG) }
            if (showChangelog) {
                AlertDialog(
                    onDismissRequest = { showChangelog = false },
                    title = { Text(text = stringResource(MR.strings.updated_version, BuildConfig.VERSION_NAME)) },
                    dismissButton = {
                        TextButton(onClick = { openInBrowser(RELEASE_URL) }) {
                            Text(text = stringResource(MR.strings.whats_new))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showChangelog = false }) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                )
            }
        }

        val startTime = System.currentTimeMillis()
        splashScreen?.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            elapsed <= SPLASH_MIN_DURATION || (!ready && elapsed <= SPLASH_MAX_DURATION)
        }
        setSplashScreenExitAnimation(splashScreen)

        // AY -->
        externalPlayerResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                val animeId = savedInstanceState?.getLong(SAVED_STATE_ANIME_KEY)
                val episodeId = savedInstanceState?.getLong(SAVED_STATE_EPISODE_KEY)

                if (animeId != null && episodeId != null) {
                    runBlocking {
                        externalIntents.initAnime(animeId, episodeId)
                    }
                }

                // AM (DISCORD_RPC) -->
                externalIntents.onActivityResult(this.applicationContext, result.data)
                // <-- AM (DISCORD_RPC)
            }
        }
        // <-- AY
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (val screen = navigator?.lastItem) {
            is AssistContentScreen -> {
                screen.onProvideAssistUrl()?.let { outContent.webUri = it.toUri() }
            }
        }
    }

    @Composable
    private fun HandleOnNewIntent(context: Context, navigator: Navigator) {
        LaunchedEffect(Unit) {
            callbackFlow {
                val componentActivity = context as ComponentActivity
                val consumer = Consumer<Intent> { trySend(it) }
                componentActivity.addOnNewIntentListener(consumer)
                awaitClose { componentActivity.removeOnNewIntentListener(consumer) }
            }
                .collectLatest { handleIntentAction(it, navigator) }
        }
    }

    @Composable
    private fun CheckForUpdates() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        // App updates
        LaunchedEffect(Unit) {
            if (updaterEnabled) {
                try {
                    val result = context.appGraph.updateChecker.checkForUpdate()
                    if (result is GetApplicationRelease.Result.NewUpdate) {
                        val updateScreen = NewUpdateScreen(
                            versionName = result.release.version,
                            changelogInfo = result.release.info,
                            releaseLink = result.release.releaseLink,
                            downloadLink = result.release.downloadLink,
                        )
                        navigator.push(updateScreen)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }

        // Extensions updates
        LaunchedEffect(Unit) {
            try {
                extensionApi.checkForUpdates(context)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    @Composable
    private fun ShowOnboarding() {
        val navigator = LocalNavigator.currentOrThrow

        LaunchedEffect(Unit) {
            if (!preferences.shownOnboardingFlow.get() && navigator.lastItem !is OnboardingScreen) {
                navigator.push(OnboardingScreen())
            }
        }
    }

    /**
     * Sets custom splash screen exit animation on devices prior to Android 12.
     *
     * When custom animation is used, status and navigation bar color will be set to transparent and will be restored
     * after the animation is finished.
     */
    @Suppress("Deprecation")
    private fun setSplashScreenExitAnimation(splashScreen: SplashScreen?) {
        val root = findViewById<View>(android.R.id.content)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && splashScreen != null) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            splashScreen.setOnExitAnimationListener { splashProvider ->
                // For some reason the SplashScreen applies (incorrect) Y translation to the iconView
                splashProvider.iconView.translationY = 0F

                val activityAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = LinearOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        root.translationY = value * 16.dpToPx
                    }
                }

                val splashAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = FastOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        splashProvider.view.alpha = value
                    }
                    doOnEnd {
                        splashProvider.remove()
                    }
                }

                activityAnim.start()
                splashAnim.start()
            }
        }
    }

    private fun handleIntentAction(intent: Intent, navigator: Navigator): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }

        val tabToOpen = when (intent.action) {
            Constants.SHORTCUT_LIBRARY -> HomeScreen.Tab.Library()
            Constants.SHORTCUT_ANIME -> {
                val idToOpen = intent.extras?.getLong(Constants.ANIME_EXTRA) ?: return false
                navigator.popUntilRoot()
                HomeScreen.Tab.Library(idToOpen)
            }
            // AM (RECENTS) -->
            Constants.SHORTCUT_UPDATES -> HomeScreen.Tab.Recents(toHistory = false)
            Constants.SHORTCUT_HISTORY -> HomeScreen.Tab.Recents(toHistory = true)
            // <-- AM (RECENTS)
            Constants.SHORTCUT_SOURCES -> HomeScreen.Tab.Browse(false)
            Constants.SHORTCUT_EXTENSIONS -> HomeScreen.Tab.Browse(true)
            Constants.SHORTCUT_DOWNLOADS -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.More(toDownloads = true)
            }
            Intent.ACTION_APPLICATION_PREFERENCES -> {
                navigator.popUntilRoot()
                navigator.push(SettingsScreen())
                null
            }
            Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY) ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!query.isNullOrEmpty()) {
                    navigator.popUntilRoot()
                    navigator.push(DeepLinkScreen(query))
                }
                null
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    navigator.popUntilRoot()
                    navigator.push(GlobalSearchScreen(query, filter))
                }
                null
            }
            Intent.ACTION_VIEW -> {
                // Handling opening of backup files
                if (intent.data.toString().endsWith(".tachibk")) {
                    navigator.popUntilRoot()
                    navigator.push(RestoreBackupScreen(intent.data.toString()))
                }
                // Deep link to add extension store
                else if (intent.isAddExtensionStoreIntent()) {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(ExtensionStoresScreen(repoUrl))
                    }
                }
                null
            }
            // AM -->
            else -> uiPreferences.startScreen.get().tab
            // <-- AM
        }

        if (tabToOpen != null) {
            lifecycleScope.launch { HomeScreen.openTab(tabToOpen) }
        }

        ready = true
        return true
    }

    // AY -->
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        externalIntents.animeId?.let {
            outState.putLong(SAVED_STATE_ANIME_KEY, it)
        }
        externalIntents.episodeId?.let {
            outState.putLong(SAVED_STATE_EPISODE_KEY, it)
        }
    }
    // <-- AY

    // AM -->
    override fun onResume() {
        super.onResume()
        mpvConfig.copyFiles()
    }
    // <-- AM

    private fun Intent.isAddExtensionStoreIntent(): Boolean {
        return scheme == "aniyomi" && (data?.host == "add-repo" || data?.host == "extension-store")
    }

    companion object {
        const val INTENT_SEARCH = "eu.kanade.tachiyomi.ANIMESEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"

        // AY -->
        const val SAVED_STATE_ANIME_KEY = "saved_state_anime_key"
        const val SAVED_STATE_EPISODE_KEY = "saved_state_episode_key"

        private var externalPlayerResult: ActivityResultLauncher<Intent>? = null

        suspend fun startPlayerActivity(
            context: Context,
            animeId: Long,
            episodeId: Long,
            extPlayer: Boolean,
            sourceId: Long? = null,
            video: Video? = null,
            hosterIndex: Int = -1,
            videoIndex: Int = -1,
            hosterList: List<Hoster>? = null,
            // AM (CONTINUE_BUTTON_RESUME_FIX) -->
            // See AnimeScreen.openEpisode()'s forceResume param.
            forceResume: Boolean = false,
            // <-- AM (CONTINUE_BUTTON_RESUME_FIX)
        ) {
            if (extPlayer) {
                val sourceId = sourceId ?: (context.appGraph.getAnime.await(animeId)?.source ?: -1L)
                val (success, port) = startHttpServerService(context, sourceId)
                if (!success) {
                    withUIContext { context.toast(AYMR.strings.http_server_start_failure) }
                    return
                }

                val video = video?.copyHttpServer(port)
                val intent = try {
                    ExternalIntents.newIntent(context, animeId, episodeId, video)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withUIContext { context.toast(e.message) }
                    null
                } ?: return
                externalPlayerResult?.launch(intent) ?: return
            } else {
                // AM (PLAYER_HOST_SCREEN) -->
                // Real cutover of the internal (non-external-player) path -
                // see PlayerHostScreen's own doc comment for the full
                // reasoning. Only pushes a NEW PlayerHostScreen for a
                // genuinely new session (no live holder, or a different
                // anime entirely) - the "same anime" cases are handled
                // directly here against the already-composed screen's live
                // viewModel, since Voyager doesn't deduplicate equally-
                // parameterized screen instances on its own (confirmed by
                // reading its default key implementation).
                val mainActivity = context as? MainActivity
                val navigator = mainActivity?.navigator
                val holder = PlayerMediaHolder.current
                val liveViewModel = holder?.takeIf { it.hasExternalScreenConsumer }?.viewModel
                val liveState = liveViewModel?.stateData?.value

                NotificationReceiver.dismissNotification(
                    context,
                    animeId.hashCode(),
                    Notifications.ID_NEW_EPISODES,
                )

                when {
                    navigator?.lastItem is PlayerHostScreen &&
                        liveViewModel != null &&
                        liveState?.currentAnime?.id == animeId &&
                        liveState.currentEpisode?.id == episodeId -> {
                        // Already exactly this, already showing - nothing to do.
                    }
                    navigator?.lastItem is PlayerHostScreen &&
                        liveViewModel != null &&
                        liveState?.currentAnime?.id == animeId -> {
                        liveViewModel.changeEpisode(episodeId)
                    }
                    else -> {
                        navigator?.push(
                            PlayerHostScreen(
                                animeId = animeId,
                                episodeId = episodeId,
                                hosterList = hosterList,
                                hosterIndex = hosterIndex,
                                videoIndex = videoIndex,
                                forceResume = forceResume,
                            ),
                        )
                    }
                }
                // <-- AM (PLAYER_HOST_SCREEN)
            }
        }

        suspend fun startHttpServerService(
            context: Context,
            sourceId: Long,
            timeout: Duration = 5.seconds,
        ): Pair<Boolean, Int> {
            HttpServerService.resetIsRunning()
            context.startService(
                Intent(context, HttpServerService::class.java)
                    .putExtra(HttpServerService.EXTRA_SOURCE_ID, sourceId),
            )

            val ready = withTimeoutOrNull(timeout) {
                HttpServerService.isRunning.first { it }
            }

            return Pair(ready == true, HttpServerService.port)
        }
        // <-- AY
    }
}

// Splash screen
private const val SPLASH_MIN_DURATION = 500 // ms
private const val SPLASH_MAX_DURATION = 5000 // ms
private const val SPLASH_EXIT_ANIM_DURATION = 400L // ms
