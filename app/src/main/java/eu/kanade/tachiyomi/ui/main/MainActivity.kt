package eu.kanade.tachiyomi.ui.main

import android.animation.ValueAnimator
import android.app.PictureInPictureParams
import android.app.SearchManager
import android.app.assist.AssistContent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.repeatOnLifecycle
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
import eu.kanade.presentation.components.AppDialogHost
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.presentation.components.LocalAppDialogLayer
import eu.kanade.presentation.components.rememberAppDialogLayer
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
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.more.WhatsNewScreen
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.ExternalIntents
import eu.kanade.tachiyomi.ui.player.LocalInRealPip
import eu.kanade.tachiyomi.ui.player.PIP_BACKGROUND_PLAY
import eu.kanade.tachiyomi.ui.player.PIP_INTENTS_FILTER
import eu.kanade.tachiyomi.ui.player.PIP_INTENT_ACTION
import eu.kanade.tachiyomi.ui.player.PIP_NEXT
import eu.kanade.tachiyomi.ui.player.PIP_PAUSE
import eu.kanade.tachiyomi.ui.player.PIP_PLAY
import eu.kanade.tachiyomi.ui.player.PIP_PREVIOUS
import eu.kanade.tachiyomi.ui.player.PIP_SKIP
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlaybackRequest
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerBackgroundPlaybackService
import eu.kanade.tachiyomi.ui.player.PlayerFreshStartScreenSpike
import eu.kanade.tachiyomi.ui.player.PlayerMediaHolder
import eu.kanade.tachiyomi.ui.player.PlayerOverlayHost
import eu.kanade.tachiyomi.ui.player.PlayerVoyagerScreenSpike
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.createPipActions
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isBenchmarkBuildType
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
import kotlin.math.roundToInt
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

    // AM (SELF_PIP_DISMISS_PAUSE_FIX) -->
    // No state field here - see onPictureInPictureModeChanged()'s else
    // branch. An earlier version armed a flag there and consumed it in
    // onStop(); that never fired on-device because the system STOPPING
    // the Activity is what generates the pip-exit callback, so onStop()
    // had already run (with the flag still false) by the time the flag
    // was armed. PlayerActivity's proven pattern does the opposite: the
    // callback itself checks whether the Activity is already stopped
    // (lifecycle == CREATED), which is exactly what distinguishes a
    // genuine X/swipe dismiss (stopped, never resumed) from an
    // expand-tap return (resumed, or about to be).
    // <-- AM (SELF_PIP_DISMISS_PAUSE_FIX)

    // AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX) -->
    // The headphones "Background Play" PIP action ends with
    // moveTaskToBack(), which closes the PIP window exactly like a user
    // X/swipe dismiss does - pip-exit callback, Activity stopped, the
    // SELF_PIP_DISMISS_PAUSE_FIX branch's CREATED check all fire
    // identically, and the pause landed on a button whose entire point
    // is to KEEP playing. PlayerActivity solves the same ambiguity with
    // its isIntentionalBackgroundTransition; same idea here, consumed
    // once by the dismiss-pause branch (and reset on every PIP entry so
    // a swallowed callback can't leak into a later genuine dismiss).
    private var pipBackgroundPlayTransition = false

    // AM (PIP_DROPS_ORIENTATION_LOCK) -->
    /** The player's orientation lock, held while pinned so it can be restored. */
    private var orientationBeforePip: Int? = null
    // <-- AM (PIP_DROPS_ORIENTATION_LOCK)
    // <-- AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX)

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
                    startPlayerActivity(
                        context = this@MainActivity,
                        animeId = animeId,
                        episodeId = episodeId,
                        extPlayer = false,
                    )
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
            // AM (SYSTEM_VOLUME_PANEL) -->
            // Dummy pip has no in-player slider, so volume keys there go
            // to the OS's own panel; fullscreen keeps the custom slider
            // (the only UI that can show mpv's volume boost past 100%);
            // casting falls through to the OS entirely. See
            // PlayerViewModel's SYSTEM_VOLUME_PANEL comment.
            // <-- AM (SYSTEM_VOLUME_PANEL)
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                }
                when {
                    // Casting: don't consume at all - the system panel
                    // drives the cast device's volume via the media route.
                    viewModel.stateData.value.isCasting -> return super.onKeyDown(keyCode, event)
                    // Dummy pip: the in-player slider doesn't exist here,
                    // so the OS's own panel shows instead.
                    holder.isDummyPipActive -> {
                        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                        viewModel.syncVolumeFromSystem()
                    }
                    // Fullscreen keeps the in-player slider: it's the only
                    // UI that can show mpv's volume boost past 100%.
                    else -> {
                        viewModel.changeVolumeBy(if (direction == AudioManager.ADJUST_RAISE) 1 else -1)
                        viewModel.displayVolumeSlider(true)
                    }
                }
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
    // AM (SELF_PIP_SOURCE_RECT_HINT_FIX) -->
    /**
     * The rect a video of [aspect] actually occupies inside a [width] x [height]
     * window - the window with mpv's letterbox bars taken off, centred.
     *
     * Returns the full window when no usable aspect has been reported yet, which is
     * the previous behaviour: an imprecise hint still gets a morph, where no hint at
     * all makes the system fall back to resizing the window generically.
     */
    private fun videoRectWithin(width: Int, height: Int, aspect: Double?): android.graphics.Rect {
        val full = android.graphics.Rect(0, 0, width, height)
        if (aspect == null || aspect <= 0.001 || width <= 0 || height <= 0) return full
        val w = width.toDouble()
        val h = height.toDouble()
        // The same fit mpv performs inside the surface: whichever axis runs out first.
        val videoWidth: Double
        val videoHeight: Double
        if (w / h >= aspect) {
            videoWidth = h * aspect
            videoHeight = h
        } else {
            videoWidth = w
            videoHeight = w / aspect
        }
        val insetX = ((w - videoWidth) / 2.0).roundToInt()
        val insetY = ((h - videoHeight) / 2.0).roundToInt()
        return android.graphics.Rect(insetX, insetY, width - insetX, height - insetY)
    }
    // <-- AM (SELF_PIP_SOURCE_RECT_HINT_FIX)

    private fun buildSelfPipParams(autoEnter: Boolean): PictureInPictureParams? {
        val holder = PlayerMediaHolder.current
        val viewModel = holder?.viewModel
        // AM (DUMMY_PIP) -->
        // isDummyPipActive intentionally NOT part of this guard (it was,
        // originally - see DUMMY_PIP_STALE_AUTO_ENTER_FIX below for why that
        // was wrong). This guard now only covers "is there even a session/
        // viewModel to build params from at all" - whether dummy pip is up
        // is handled entirely inside the autoEnter computation further down,
        // so a valid params object (with autoEnter explicitly false) still
        // gets built and pushed to the OS while dummy pip is active, instead
        // of this function bailing out and updateAutoEnterPipParams() below
        // skipping the OS call entirely.
        if (holder?.hasExternalScreenConsumer != true || viewModel == null) return null
        // <-- AM (DUMMY_PIP)

        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val anime = viewModel.stateData.value.currentAnime
            val episode = viewModel.stateData.value.currentEpisode
            if (anime != null && episode != null) {
                builder.setTitle(anime.title).setSubtitle(episode.name)
            }
        }
        // AM (SELF_PIP_DISPLAY_ASPECT_FIX) -->
        // Display aspect, not storage dimensions - the same fix as
        // PlayerActivity.createPipParams()'s own REAL_PIP_DISPLAY_ASPECT_FIX, which
        // this path had the identical bug as. videoWidth/videoHeight are what the
        // frames are stored at, so anamorphic content (a 16:9 picture stored
        // 1440x1080) asked the system for a 4:3 window and a rotation-tagged
        // portrait clip asked for a landscape one, for as long as PiP was up.
        //
        // It also has to agree with the source-rect hint below: the hint is the
        // video's rect, so if the window were built at a different shape the system
        // would be morphing between two shapes again, which is the squash this is
        // all about.
        val displayAspect = viewModel.aspectRatio.value?.takeIf { it > 0.001 }
        val rational = if (displayAspect != null) {
            Rational((displayAspect * SELF_PIP_ASPECT_DENOMINATOR).roundToInt(), SELF_PIP_ASPECT_DENOMINATOR)
        } else {
            viewModel.stateData.value.let {
                if (it.videoWidth > 0 && it.videoHeight > 0) {
                    Rational(it.videoWidth, it.videoHeight)
                } else {
                    Rational(16, 9)
                }
            }
        }
        if (rational.toDouble() in 0.42..2.38) {
            builder.setAspectRatio(rational)
        }
        // <-- AM (SELF_PIP_DISPLAY_ASPECT_FIX)
        // AM (SELF_PIP_SOURCE_RECT_HINT_FIX) -->
        // The VIDEO's rect, not the window's.
        //
        // This used to pass the whole decor view, reasoning that "fullscreen video
        // occupies essentially the whole window at that point, so the window's own
        // current bounds are a reasonable hint". That reasoning is wrong, and it is
        // what makes PiP entry squash the picture: fullscreen video does NOT occupy
        // the whole window - mpv fits it and fills the rest with black bars - so the
        // hint described a screen-shaped region while the window the system builds
        // is video-shaped. Asked to morph one into the other, it scales the entire
        // window down into the video-shaped target and the picture is squeezed along
        // with everything else.
        //
        // Given the video's own rect, the system animates exactly that rect into the
        // PiP bounds and cross-fades the rest away, so the picture stays on screen
        // and only changes size. That is the behaviour this was trying to get.
        //
        // AM (DUMMY_PIP_REPORTS_WINDOW_RECT) -->
        // While the mini player is up it is the thing becoming the PiP window, so
        // its rect is the hint - not the fullscreen video's, which is not even on
        // screen. This used to be skipped entirely, leaving the system with no hint
        // and nothing to morph from, so it resized the whole window generically:
        // the entire screen appearing to shrink rather than the little window
        // growing into place.
        //
        // The rect comes from the window itself via the holder, and is reported only
        // at rest, so it describes where the window actually is. Null means no
        // settled window to morph from (mid-morph, mid-gesture), and no hint is
        // better than a wrong one.
        val dummyPipRect = holder.dummyPipWindowRect
        if (holder.isDummyPipActive) {
            if (dummyPipRect != null) {
                builder.setSourceRectHint(
                    android.graphics.Rect(
                        dummyPipRect.left.roundToInt(),
                        dummyPipRect.top.roundToInt(),
                        dummyPipRect.right.roundToInt(),
                        dummyPipRect.bottom.roundToInt(),
                    ),
                )
            }
        } else {
            val decorView = window?.decorView
            if (decorView != null && decorView.width > 0 && decorView.height > 0) {
                builder.setSourceRectHint(videoRectWithin(decorView.width, decorView.height, displayAspect))
            }
        }
        // <-- AM (DUMMY_PIP_REPORTS_WINDOW_RECT)
        // <-- AM (SELF_PIP_SOURCE_RECT_HINT_FIX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // AM (DUMMY_PIP_STALE_AUTO_ENTER_FIX) -->
            // holder.isDummyPipActive added here, not just to a top-level
            // guard on this whole function - confirmed on-device this was
            // the actual bug behind "leaving the app always enters PIP,
            // showing the small dummy-pip window instead of fullscreen".
            // setAutoEnterEnabled() is a registration the OS acts on later,
            // on its own, without calling back into this code - it isn't
            // re-derived at the moment of leaving. This function is only
            // ever re-run reactively (PlayerHostScreen's own playbackData
            // collector), and that collector stops running the instant
            // PlayerHostScreen is popped for dummy pip - so whatever
            // autoEnterEnabled value was last pushed (almost always true,
            // since back is normally pressed mid-playback) stays registered
            // with the OS for as long as dummy pip is up, regardless of
            // what buildSelfPipParams() would compute if it ran again.
            // Making dummy pip a top-level bail-out here (the original,
            // wrong version) didn't fix that - it just meant this function
            // stopped being called at all during dummy pip, which left the
            // stale true registration in place rather than replacing it
            // with an explicit false. Folding the check into the value
            // itself means every call this function still receives (see
            // PlayerHostScreen's onEnterDummyPip, which now calls
            // updateAutoEnterPipParams() once immediately on entry)
            // actively pushes the correct false registration instead of
            // silently skipping the update.
            // AM (DUMMY_PIP_AUTO_ENTER_RESTORED) -->
            // isDummyPipActive removed from this value again - but NOT back to
            // the original bail-out (see the DUMMY_PIP_STALE_AUTO_ENTER_FIX
            // note above for why that was wrong; this function must keep being
            // called and keep pushing a value either way).
            //
            // Suppressing auto-enter during dummy pip fixed the visual symptom
            // by giving up the only mechanism that can enter PIP at all on a
            // Recents press: onUserLeaveHint() is documented not to fire for
            // Recents, and the legacy pause-driven path is closed there too -
            // AOSP's Task.enableEnterPipOnTaskSwitch() sets the activity's
            // supportsEnterPipOnTaskSwitch false for a transient launch, which
            // is what a Recents press is, and Transition.checkEnterPipOnFinish()
            // only force-enables it once that transient launch commits. The
            // auto-enter path is the one that survives this, and it reads
            // exactly one thing: whatever isAutoEnterEnabled() was last
            // registered. Registering false for the entire time the mini player
            // is up - which includes all of browsing a new source - is
            // therefore the direct cause of "PIP is reset by opening a new
            // source".
            //
            // The visual concern it was guarding is now handled where the
            // explicit path already handles it: onPictureInPictureModeChanged()
            // clears isDummyPipActive unconditionally on PIP entry. That
            // callback fires AFTER the window has genuinely resized, so the
            // fullscreen player simply fills whatever small window now exists -
            // the same reasoning DUMMY_PIP_FULLSCREEN_RESTORE already relied
            // on, just no longer gated on a flag only the explicit path sets.
            val shouldAutoEnter = autoEnter &&
                !viewModel.playbackData.value.paused &&
                graph.playerPreferences.pipOnExit.get()
            // <-- AM (DUMMY_PIP_AUTO_ENTER_RESTORED)
            // <-- AM (DUMMY_PIP_STALE_AUTO_ENTER_FIX)
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
        if (lifecycle.currentState != Lifecycle.State.RESUMED &&
            lifecycle.currentState != Lifecycle.State.STARTED
        ) {
            return
        }
        // AM (SELF_PIP_STALE_AUTO_ENTER_ON_SESSION_END_FIX) -->
        // Was `?: return`, which made this unreachable exactly when it mattered
        // most. buildSelfPipParams() returns null for "there is no session",
        // and a session ending is precisely when the registration needs to go
        // back to false: setAutoEnterEnabled() is a standing registration the
        // OS acts on by itself, so whatever was last pushed while playing
        // (true, normally) stays armed on the ActivityRecord for the rest of
        // this Activity's life. Pausing de-registered correctly because the
        // session still existed and !paused folded into the value; ending
        // never did, because the function bailed before reaching the push.
        // Result: Home or Recents later, with no player anywhere, auto-entered
        // PIP on a stale registration - an empty window, no video, no dummy
        // pip.
        //
        // This is the same shape as DUMMY_PIP_STALE_AUTO_ENTER_FIX's own
        // finding ("it just meant this function stopped being called at all,
        // which left the stale true registration in place rather than
        // replacing it with an explicit false"). That fix folded the check
        // into the value for the dummy-pip branch but left this top-level
        // guard as the bail-out it was warning about.
        //
        // Not a timing guard: an explicit de-registration replacing a missing
        // one. enterSelfPipIfEligible() still treats null as "do not enter" -
        // only the registration path changes.
        val params = buildSelfPipParams(autoEnter = true) ?: disabledAutoEnterPipParams()
        // <-- AM (SELF_PIP_STALE_AUTO_ENTER_ON_SESSION_END_FIX)
        try {
            setPictureInPictureParams(params)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SELF_PIP_AUTO_ENTER_FIX setPictureInPictureParams failed: $e" }
        }
    }

    // AM (SELF_PIP_STALE_AUTO_ENTER_ON_SESSION_END_FIX) -->
    /**
     * Params that say one thing: do not auto-enter. Deliberately sets nothing
     * else - ActivityRecord.setPictureInPictureParams() merges via
     * copyOnlySet(), so every field left unset keeps whatever the last real
     * session registered, and only the auto-enter bit is revoked.
     */
    private fun disabledAutoEnterPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false)
        }
        return builder.build()
    }

    /**
     * Keeps the OS registration tied to whether a session actually exists,
     * rather than to any one teardown path remembering to poke it.
     *
     * Every way a session can end - onDismiss, onEnterBackground, and
     * PlayerHostScreen's own onDispose - clears hasExternalScreenConsumer, and
     * the collector that used to push params is cancelled alongside it, so
     * none of them could push the de-registration themselves. Observing the
     * state instead means a new teardown path cannot reintroduce this bug by
     * forgetting a call.
     *
     * repeatOnLifecycle(STARTED) also covers a session ending while this
     * Activity is stopped: updateAutoEnterPipParams()'s own lifecycle guard
     * would drop that push, and the re-emission on restart replays it before
     * the user can leave the app again.
     */
    private fun observeSessionForPipRegistration() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerMediaHolder.currentFlow.collectLatest { holder ->
                    if (holder == null) {
                        updateAutoEnterPipParams()
                    } else {
                        // AM (DUMMY_PIP_REPORTS_WINDOW_RECT) -->
                        // The mini player's rect is now part of the params, so a push
                        // is needed when it changes too - an auto-enter animates from
                        // whatever was last registered, and the window can be dragged
                        // or resized any number of times between pushes.
                        //
                        // It only emits at rest (see DummyPipActions.
                        // onWindowRectChanged), so this is one push per settle, not
                        // one per frame of a drag.
                        combine(
                            holder.hasExternalScreenConsumerFlow,
                            holder.dummyPipWindowRectFlow,
                        ) { _, _ -> }.collect {
                            updateAutoEnterPipParams()
                        }
                        // <-- AM (DUMMY_PIP_REPORTS_WINDOW_RECT)
                    }
                }
            }
        }
    }
    // <-- AM (SELF_PIP_STALE_AUTO_ENTER_ON_SESSION_END_FIX)
    // <-- AM (SELF_PIP_AUTO_ENTER_FIX)

    private fun enterSelfPipIfEligible() {
        if (isInPictureInPictureMode || lifecycle.currentState != Lifecycle.State.RESUMED) return
        val holder = PlayerMediaHolder.current
        if (holder?.hasExternalScreenConsumer != true || holder.viewModel?.playbackData?.value?.paused != false) {
            return
        }
        // AM (SELF_PIP_EXPLICIT_ENTRY_HONOURS_PREFERENCE) -->
        // SELF_PIP_AUTO_ENTER_FIX added pipOnExit to the auto-enter value but
        // not here, so the explicit path entered PIP on a Home press even with
        // the preference off - the auto-enter and explicit paths disagreed
        // about the same setting. Drop this hunk if the intent was that the
        // preference only ever governed the Recents/auto path.
        if (!graph.playerPreferences.pipOnExit.get()) return
        // <-- AM (SELF_PIP_EXPLICIT_ENTRY_HONOURS_PREFERENCE)
        // AM (DUMMY_PIP_FULLSCREEN_RESTORE) -->
        // Requirement from the original dummy-pip scoping: leaving the app
        // must show real PIP with the FULL video, not the small dummy-pip
        // crop - "skip real PIP entirely while dummy pip is active" (an
        // earlier version of this guard) was a deliberate stand-in for
        // this, not the actual answer.
        //
        // Enter-then-restore, not restore-then-enter (a first version of
        // this tried the other order - correctly pointed out that's
        // backwards): PIP doesn't shrink whatever Compose happens to be
        // rendering at the moment of capture, it resizes the Activity's
        // whole window first, and Compose content fills whatever that
        // window's actual bounds are via fillMaxSize(). So there's no
        // layout race to win at all if the fullscreen screen gets pushed
        // AFTER the window has already become PIP-sized - it just
        // naturally fills whatever small size that now is. That also means
        // this doesn't need a guessed frame delay: onPictureInPictureMode
        // Changed() is a real completion callback for exactly the moment
        // the resize has happened, not an estimate.
        //
        // AM (DUMMY_PIP_AUTO_ENTER_RESTORED) -->
        // The dummy-pip branch that used to sit here is gone, along with the
        // pendingDummyPipFullscreenRestore flag it armed: the restore it was
        // arming now runs unconditionally for every PIP entry in
        // onPictureInPictureModeChanged(), which is what makes the auto-enter
        // (Recents) path restore fullscreen too - the gap the paragraph above
        // described. One entry path, one restore, no flag in between.
        //
        // autoEnter = true, not false. enterPictureInPictureMode(params) is not
        // a read-only use of these params: ActivityTaskManagerService hands them
        // to ActivityRecord.setPictureInPictureParams(), whose copyOnlySet()
        // makes every explicitly-set field REPLACE the registered one. Passing
        // false therefore left auto-enter registered false after every Home
        // press, and nothing re-registered it on expand-back (the playbackData
        // collector that pushes params only fires on a state CHANGE, and
        // expanding isn't one) - so PIP worked exactly once per session.
        val params = buildSelfPipParams(autoEnter = true) ?: return
        // <-- AM (DUMMY_PIP_AUTO_ENTER_RESTORED)
        // <-- AM (DUMMY_PIP_FULLSCREEN_RESTORE)
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

    // AM (REAL_PIP_HIDES_APP_UI) -->
    /**
     * Whether the Activity is currently in real (system) PiP, as snapshot state so
     * the composition can leave the app's own UI out while it is.
     *
     * Written from [onPictureInPictureModeChanged], which the system calls after the
     * window has finished resizing.
     */
    private var inRealPip by mutableStateOf(false)
    // <-- AM (REAL_PIP_HIDES_APP_UI)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // AM (REAL_PIP_HIDES_APP_UI) -->
        inRealPip = isInPictureInPictureMode
        // <-- AM (REAL_PIP_HIDES_APP_UI)
        // Matches PlayerActivity.onConfigurationChanged()'s own existing
        // pattern for the same purpose.
        if (isInPictureInPictureMode) {
            // AM (SELF_PIP_ENTRY_CLOSES_MENUS) -->
            // Was hideControls() only. Ported the rest of PlayerActivity's
            // pip-entry cleanup: any open sheet/panel/dialog (and the seek/
            // volume/brightness sliders) belongs to the fullscreen player -
            // inside the small PIP window it renders cramped or clipped,
            // and PlayerScreen's own controls aren't composed there at all.
            // <-- AM (SELF_PIP_ENTRY_CLOSES_MENUS)
            // AM (PIP_DROPS_ORIENTATION_LOCK) -->
            // A pinned activity must not be holding a fixed-orientation
            // request. OrientationOverlay writes MainActivity's
            // requestedOrientation from inside PlayerScreen, via an effect that
            // re-runs whenever that subtree re-enters composition - and entering
            // PIP is exactly such a moment (isDummyPipActive flips, the window
            // resizes, PlayerScreen recomposes). So the app could issue an
            // orientation request while the task was being pinned, which forces
            // WindowManager to re-evaluate display rotation and lands a
            // configuration change on the task - visible in logs as
            // "Checking to restart ... MainActivity: changed=0xd80"
            // (orientation|screenLayout|screenSize|smallestScreenSize) right
            // after the pin, while the launcher is snapshotting that same task
            // and laying out its Overview card.
            //
            // The player's lock belongs to the fullscreen player, not to a
            // 100x60 window the OS sizes itself. Drop it on entry, restore it on
            // exit from the session's own current value.
            orientationBeforePip = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // <-- AM (PIP_DROPS_ORIENTATION_LOCK)
            PlayerMediaHolder.current?.viewModel?.let { vm ->
                // Closers FIRST: each of setSheet/setPanel/setDialog(None)
                // calls showControls() as a side effect, so hideControls()
                // must run LAST to actually win.
                vm.setSheet(Sheets.None)
                vm.setPanel(Panels.None)
                vm.setDialog(Dialogs.None)
                vm.hideSeekBar()
                vm.displayBrightnessSlider(false)
                vm.displayVolumeSlider(false)
                vm.hideControls()
            }
            // AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX) -->
            pipBackgroundPlayTransition = false
            // <-- AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX)
            // AM (DUMMY_PIP_AUTO_ENTER_RESTORED) -->
            // Unconditional, and the reason auto-enter no longer has to be
            // suppressed while the mini player is up: whichever path got us
            // into PIP - our own enterPictureInPictureMode() on Home, or the
            // OS acting on the registered auto-enter flag on Recents - the
            // window has finished resizing by the time this fires, so clearing
            // the flag here hands the small window to the fullscreen player,
            // which fills it. The OS gives no callback before an auto-enter,
            // which is exactly why the restore belongs on this side of it.
            PlayerMediaHolder.current?.isDummyPipActive = false
            // <-- AM (DUMMY_PIP_AUTO_ENTER_RESTORED)
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
            // AM (SELF_PIP_DISMISS_PAUSE_FIX) -->
            // PIP window closed - expand-tap and X/swipe-dismiss both
            // land here. The distinction is the lifecycle state AT THIS
            // MOMENT (see the tag comment by the class fields for why the
            // old armed-flag/onStop version never fired): a dismiss
            // stops the Activity BEFORE this callback runs, so
            // currentState is already CREATED; an expand-tap return keeps
            // it STARTED/RESUMED. Ported from PlayerActivity's
            // PIP_REOPEN_RACE_FIX + PIP_DISMISS_PAUSE_FIX: the pause is
            // posted and RE-VALIDATED at execution time, so a concurrent
            // reopen that already brought this instance back up (state no
            // longer CREATED) self-cancels instead of pausing a session
            // the user is actively back in. Screen-off during PIP also
            // exits pip mode with the Activity stopped (PlayerActivity's
            // SECURE_LOCK_BACKGROUND_PLAYBACK found that case) - only the
            // interactive case is a genuine user dismiss. Pausing (not
            // releasing) keeps the session/notification alive, just not
            // actively playing audio the user didn't ask to keep hearing.
            // AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX) -->
            // Exempt the headphones "Background Play" action: its
            // moveTaskToBack() produces this exact same callback+state
            // signature but is an explicit keep-playing request.
            // <-- AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX)
            if (pipBackgroundPlayTransition) {
                pipBackgroundPlayTransition = false
            } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
                val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
                if (powerManager.isInteractive) {
                    window.decorView.postDelayed(
                        {
                            if (lifecycle.currentState == Lifecycle.State.CREATED && !isFinishing) {
                                PlayerMediaHolder.current?.viewModel?.pause()
                            }
                        },
                        100,
                    )
                }
            }
            // <-- AM (SELF_PIP_DISMISS_PAUSE_FIX)
            // AM (PIP_DROPS_ORIENTATION_LOCK) -->
            // Back to the session's own orientation. Taken from the live
            // ViewModel rather than the saved value where possible, since the
            // session may have changed episode - and therefore video aspect,
            // under the Video orientation mode - while pinned. The saved value
            // is the fallback for a session that has since ended.
            val sessionOrientation = PlayerMediaHolder.current?.viewModel
                ?.playbackData?.value?.currentOrientation
            requestedOrientation = sessionOrientation
                ?: orientationBeforePip
                ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            orientationBeforePip = null
            // <-- AM (PIP_DROPS_ORIENTATION_LOCK)
            // AM (DUMMY_PIP_AUTO_ENTER_RESTORED) -->
            // Re-register on the way out of PIP. AOSP clears the activity's
            // supportsEnterPipOnTaskSwitch as part of moving it into the pinned
            // task (RootWindowContainer), and an expand-tap changes no playback
            // state, so the collector that normally pushes params never fires -
            // without this the session comes back from PIP with whatever was
            // registered on the way in. Self-limiting on a genuine dismiss: the
            // Activity is already CREATED there, so updateAutoEnterPipParams()
            // returns early on its own lifecycle guard.
            updateAutoEnterPipParams()
            // <-- AM (DUMMY_PIP_AUTO_ENTER_RESTORED)
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
                    // AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX) -->
                    // moveTaskToBack() tears down the PIP window exactly
                    // like a user dismiss - arm the exemption so the
                    // dismiss-pause branch skips THIS pip exit.
                    // <-- AM (SELF_PIP_BACKGROUND_PLAY_NO_PAUSE_FIX)
                    pipBackgroundPlayTransition = true
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

        // AM (DUMMY_PIP_BACK_TO_ROOT_PIP_FIX) -->
        // PlayerScreen's BackHandler is disabled while the dummy pip is up
        // (DUMMY_PIP_BACK_PASSTHROUGH_FIX in PlayerScreen.kt), so back
        // presses navigate the app pages UNDERNEATH the pip. When those
        // run out, the press would finish the Activity - with a live,
        // playing pip session that's the wrong default: entering real PiP
        // keeps playback going instead (same as Home). Registered in
        // onCreate, i.e. BEFORE every Compose BackHandler, so it has the
        // LOWEST priority and only ever fires when nothing above it
        // consumed the press - exactly the "back would leave the app"
        // case. Paused sessions keep the ordinary finish behavior.
        // <-- AM (DUMMY_PIP_BACK_TO_ROOT_PIP_FIX)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val holder = PlayerMediaHolder.current
                    if (holder?.isDummyPipActive == true && holder.viewModel?.playbackData?.value?.paused == false) {
                        enterSelfPipIfEligible()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        // AM (SELF_PIP_STALE_AUTO_ENTER_ON_SESSION_END_FIX) -->
        observeSessionForPipRegistration()
        // <-- AM (SELF_PIP_STALE_AUTO_ENTER_ON_SESSION_END_FIX)

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

            // AM (APP_DIALOG_LAYER) -->
            // One layer for every app dialog, provided around the whole Navigator so
            // any screen's AlertDialog lands in it. See AppDialogLayer.kt for why the
            // dialogs come down into the composition at all.
            val appDialogLayer = rememberAppDialogLayer()
            // AM (REAL_PIP_CONTROLS_LEAK_FIX) -->
            CompositionLocalProvider(
                LocalAppDialogLayer provides appDialogLayer,
                LocalInRealPip provides inRealPip,
            ) {
                // <-- AM (REAL_PIP_CONTROLS_LEAK_FIX)
            // <-- AM (APP_DIALOG_LAYER)
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
                        // AM (REAL_PIP_HIDES_APP_UI) -->
                        if (!inRealPip) {
                            AppStateBanners(
                                downloadedOnlyMode = downloadOnly,
                                incognitoMode = incognito,
                                indexing = indexing,
                                modifier = Modifier.windowInsetsPadding(scaffoldInsets),
                            )
                        }
                        // <-- AM (REAL_PIP_HIDES_APP_UI)
                    },
                    contentWindowInsets = scaffoldInsets,
                ) { contentPadding ->
                    // Consume insets already used by app state banners
                    Box {
                        // AM (REAL_PIP_HIDES_APP_UI) -->
                        // The app's own screens are left OUT of composition while in
                        // real PiP, so the PiP window contains the player and
                        // nothing else - which is what the system PiP window is.
                        //
                        // This is also what closes dialogs on PiP entry, generally.
                        // onPictureInPictureModeChanged only reached the PLAYER's own
                        // sheets/panels/dialogs, through
                        // PlayerMediaHolder.current.viewModel - every other dialog in
                        // the app is local state inside whichever screen owns it
                        // (dozens of independent `remember { mutableStateOf }` sites,
                        // with no registry to ask), so there was nothing to call.
                        // Dropping the subtree dismisses all of them at once, for the
                        // reason they exist at all: a Compose Dialog or Popup is
                        // hosted by its composable, and it goes away when that
                        // composable leaves composition. It also covers every dialog
                        // added later without anyone having to remember this rule.
                        //
                        // Those dialogs do not come back on PiP exit, which is the
                        // intended behaviour rather than a side effect. Screen state
                        // held in rememberSaveable survives; plain remember does not,
                        // the same as for any configuration change.
                        if (!inRealPip) {
                            // Shows current screen
                            DefaultNavigatorScreenTransition(
                                navigator = navigator,
                                modifier = Modifier
                                    .padding(contentPadding)
                                    .consumeWindowInsets(contentPadding),
                            )
                        }
                        // <-- AM (REAL_PIP_HIDES_APP_UI)

                        // Draw navigation bar scrim when needed
                        // AM (PLAYER_NAV_SCRIM_Z_ORDER) -->
                        // Composed BEFORE PlayerOverlayHost so it sits beneath the
                        // player in the Box's z-order. It used to be composed after,
                        // which drew it on top of the fullscreen player's video.
                        // <-- AM (PLAYER_NAV_SCRIM_Z_ORDER)
                        // AM (REAL_PIP_HIDES_APP_UI) -->
                        // No nav-bar scrim in the PiP window either - there is no
                        // nav bar inside it, so it would just be a grey band over
                        // the bottom of the video.
                        if (!inRealPip && remember { isNavigationBarNeedsScrim() }) {
                        // <-- AM (REAL_PIP_HIDES_APP_UI)
                            Spacer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                    .alpha(0.8f)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }

                        // AM (APP_DIALOG_LAYER) -->
                        // Layer 2: above the app's content, BELOW the player hosted
                        // just after this. That position is the entire fix for the
                        // player being stuck behind dialog windows - see
                        // AppDialogLayer.kt.
                        AppDialogHost(appDialogLayer)
                        // <-- AM (APP_DIALOG_LAYER)

                        // AM (PLAYER_OVERLAY_MIGRATION) -->
                        // Hosted here, outside DefaultNavigatorScreenTransition
                        // entirely - see PlayerHostScreen.kt's own doc comment
                        // for why the player (both fullscreen and dummy-pip
                        // modes) can't be a Navigator screen at all anymore.
                        // Renders nothing when there's no live playback
                        // request, so this is safe to always compose
                        // regardless of what screen the Navigator itself is
                        // currently showing.
                        PlayerOverlayHost()
                        // <-- AM (PLAYER_OVERLAY_MIGRATION)
                    }
                }

                // AM (OPEN_ENTRY_FROM_OVERLAY) -->
                // Collected here rather than in HomeScreen: this is composed
                // for the life of the Activity, so a request lands whatever
                // screen is currently on top.
                //
                // Pushes on top of whatever is there rather than switching
                // tabs. Consecutive requests replace the entry this opened
                // last instead of stacking, but only while that screen is
                // still the top one - a screen the user navigated to
                // themselves is never replaced out from under them.
                var lastOpenedEntryKey by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(navigator) {
                    OpenEntryRequests.requests.collect { animeId ->
                        val screen = AnimeScreen(animeId)
                        if (lastOpenedEntryKey != null && navigator.lastItem.key == lastOpenedEntryKey) {
                            navigator.replace(screen)
                        } else {
                            navigator.push(screen)
                        }
                        lastOpenedEntryKey = screen.key
                    }
                }
                // <-- AM (OPEN_ENTRY_FROM_OVERLAY)

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

            // AM (WHATS_NEW) -->
            // The version the user was last shown notes for, captured before it is advanced so
            // the What's new screen knows the range to fetch. Blank means fresh install: record
            // the current version and show nothing, since there is nothing new to them yet.
            val previousVersion = remember {
                preferences.lastShownChangelogVersion.get().also {
                    preferences.lastShownChangelogVersion.set(BuildConfig.VERSION_NAME)
                }
            }
            var showChangelog by remember {
                mutableStateOf(
                    !BuildConfig.DEBUG &&
                        previousVersion.isNotBlank() &&
                        previousVersion != BuildConfig.VERSION_NAME,
                )
            }
            if (showChangelog) {
                AlertDialog(
                    onDismissRequest = { showChangelog = false },
                    title = { Text(text = stringResource(MR.strings.updated_version, BuildConfig.VERSION_NAME)) },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showChangelog = false
                                navigator?.push(WhatsNewScreen(sinceVersion = previousVersion))
                            },
                        ) {
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
            // <-- AM (WHATS_NEW)
            // AM (APP_DIALOG_LAYER) -->
            }
            // <-- AM (APP_DIALOG_LAYER)
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
        // AM (DUMMY_PIP_REOPEN_STAYS_PIP_FIX) -->
        // Was DUMMY_PIP_PAUSED_REOPEN_FIX: a paused dummy pip was
        // force-expanded to fullscreen on every onResume, because the pip's
        // controls used to come back dead after a background stint. That
        // workaround threw away the user's pip session on every reopen -
        // the pip now simply stays up (activity-surviving backgrounding
        // never tears the composable down, so its gesture state never dies
        // in the first place; activity-destroyed reopening re-composes it
        // fresh, also with working controls).
        // <-- AM (DUMMY_PIP_REOPEN_STAYS_PIP_FIX)
    }

    private fun Intent.isAddExtensionStoreIntent(): Boolean {
        return scheme == "aniyomi" && (data?.host == "add-repo" || data?.host == "extension-store")
    }

    companion object {
        // AM (SELF_PIP_DISPLAY_ASPECT_FIX) -->
        /** Denominator used to express mpv's Double display aspect as a [Rational]. */
        private const val SELF_PIP_ASPECT_DENOMINATOR = 1000
        // <-- AM (SELF_PIP_DISPLAY_ASPECT_FIX)

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
                // reasoning.
                //
                // AM (PLAYER_OVERLAY_MIGRATION) -->
                // "Already showing fullscreen" is no longer a Navigator
                // question (navigator.lastItem is PlayerHostScreen) -
                // PlayerHostScreen isn't pushed onto the Navigator anymore;
                // what's live is asked of the holder directly.
                // <-- AM (PLAYER_OVERLAY_MIGRATION)
                val holder = PlayerMediaHolder.current
                val liveViewModel = holder?.takeIf { it.hasExternalScreenConsumer }?.viewModel
                val liveState = liveViewModel?.stateData?.value

                NotificationReceiver.dismissNotification(
                    context,
                    animeId.hashCode(),
                    Notifications.ID_NEW_EPISODES,
                )

                // AM (CROSS_ANIME_IN_PLACE_SWITCH) -->
                // Was a three-case when that called changeEpisode() directly
                // for the same anime (and, as a side effect, RELOADED the
                // episode when only the dummy pip was up) and submitted a
                // request only for a different anime. Requests now drive
                // every transition: the persistent PlayerHostScreen's
                // reconciliation effect routes same-episode/same-anime/
                // cross-anime through its one serialized path, so the whole
                // session - player, surface, dummy-pip window state -
                // survives a playlist switch. The only case still handled
                // here is "already exactly this", where the correct action
                // is just expanding a floating pip, with NO reload.
                // <-- AM (CROSS_ANIME_IN_PLACE_SWITCH)
                if (liveState?.currentAnime?.id == animeId &&
                    liveState.currentEpisode?.id == episodeId
                ) {
                    // liveState is non-null in this branch, which is only
                    // reachable when a holder exists - no safe call needed.
                    holder.isDummyPipActive = false
                } else {
                    PlayerMediaHolder.requestPlayback(
                        PlaybackRequest(
                            animeId = animeId,
                            episodeId = episodeId,
                            hosterList = hosterList,
                            hosterIndex = hosterIndex,
                            videoIndex = videoIndex,
                            forceResume = forceResume,
                        ),
                    )
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
