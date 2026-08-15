/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Mix of PlayerActivity from mpvKt and the former PlayerActivity from Aniyomi.

package eu.kanade.tachiyomi.ui.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import animiru.domain.player.model.ArtType
import animiru.domain.player.model.CustomKeyCodes
import animiru.domain.player.model.SetAsArt
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.service.GesturePreferences
import animiru.domain.player.service.PlayerPreferences
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PlayerActivity : BaseActivity() {
    private val viewModel by viewModels<PlayerViewModel>()
    private val windowInsetsController by lazy { WindowCompat.getInsetsController(window, window.decorView) }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val inputMethodManager by lazy { getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }

    // AM (MEDIA_SESSION_SERVICE_OWNED) -->
    // Backed by the Service-owned holder instead of a locally-constructed session, so
    // it survives this Activity's destruction the same way the player now does. Every
    // existing `mediaSession?.xxx` call site elsewhere in this file keeps working
    // unchanged - only the backing storage moved.
    private val mediaSession: MediaSession?
        get() = mediaHolder?.mediaSession
    // <-- AM (MEDIA_SESSION_SERVICE_OWNED)
    private val gesturePreferences: GesturePreferences = Injekt.get()
    private val playerPreferences: PlayerPreferences = Injekt.get()

    private var backgroundPlaybackService: PlayerBackgroundPlaybackService? = null

    // Set before we intentionally moveTaskToBack() (e.g. PIP "background play"),
    // so onPictureInPictureModeChanged doesn't treat it as the user swiping PIP away.
    private var isIntentionalBackgroundTransition = false

    // AM (TASK_SWIPE_TEARDOWN_FIX) -->
    // Set right before finish() at every call site that represents a genuine "the user
    // is done with this session" exit (notification Stop, back-out without PIP/background,
    // crashes, load failures, PIP swiped away). onDestroy() below used to gate its full
    // teardown (stopBackgroundPlayback/player.release/mediaHolder.release/stopService)
    // purely on isFinishing() - but Android also sets isFinishing() to true when the
    // *whole task* gets removed from Recents (a swipe, or "clear all"), which is exactly
    // the scenario the synthetic-back-stack notification-reopen architecture exists to
    // survive. Without this flag, that swipe wrongly tore down the still-live session -
    // including the app-lock exemption and the Service/player/notification the reopen
    // was supposed to find waiting - which is why reopening afterward could prompt for
    // unlock or reload/restart the episode even though playback never actually stopped.
    private var intentionalStop = false
    // <-- AM (TASK_SWIPE_TEARDOWN_FIX)

    // AM (SERVICE_OWNED_PLAYER) -->
    // Bound for the whole playback session (established in onCreate, torn down in
    // onDestroy) rather than only while backgrounded - keeps PlayerMediaHolder alive
    // and, since step 4b, also posts/owns the always-on playback notification as
    // soon as this connects (not just once backgrounded).
    private var mediaHolder: PlayerMediaHolder? = null
    private val mediaHolderConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlayerBackgroundPlaybackService.LocalBinder ?: return

            val bound = binder.getMediaHolder()
            mediaHolder = bound
            logcat { "PlayerActivity bound to PlayerMediaHolder: $bound (viewModel.player=${viewModel.player})" }
            viewModel.bindToService(bound)

            // AM (MEDIA_SESSION_SERVICE_OWNED) -->
            // The MediaSession is now Service-owned (via the holder), same reasoning
            // as the player: it needs to survive this Activity's destruction, not get
            // garbage-collected along with it. ensureMediaSession() creates it once and
            // redirects the callback on every later call, so a reattaching instance's
            // media-button presses route to the current ViewModel, not a dead one.
            setupMediaSessionCallback(bound)
            // <-- AM (MEDIA_SESSION_SERVICE_OWNED)

            // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
            // Step 4b: the notification now posts as soon as the Service is bound -
            // i.e. as soon as playback exists at all, not only once backgrounded -
            // per the YouTube-style always-on-notification decision. Whatever
            // title/subtitle is available right now (possibly still blank, since
            // this fires before onNewIntent's episode load in some orderings) gets
            // corrected shortly after by the existing reactive updateEpisodeInfo()
            // collector further down in onCreate - that collector already runs
            // unconditionally and doesn't care when the Service connected.
            backgroundPlaybackService = binder.getService().also { svc ->
                svc.start(
                    title = viewModel.uiData.value.animeTitle,
                    subtitle = viewModel.uiData.value.mediaTitle,
                    isPlaying = !viewModel.playbackData.value.paused,
                    animeId = viewModel.stateData.value.currentAnime?.id,
                    episodeId = viewModel.stateData.value.currentEpisode?.id,
                    mediaSessionToken = mediaSession?.sessionToken,
                    onTogglePlayPause = {
                        if (viewModel.playbackData.value.paused) viewModel.unpause() else viewModel.pause()
                        backgroundPlaybackService?.updatePlaybackState(!viewModel.playbackData.value.paused)
                    },
                    onStopRequested = {
                        // Tapping "stop" now ends the whole session (there's no more
                        // "hide the notification but keep the player alive" state to
                        // fall back to, since the notification is meant to track
                        // playback existing at all) - matches onDestroy's teardown path.
                        viewModel.pause()
                        // AM (TASK_SWIPE_TEARDOWN_FIX) -->
                        intentionalStop = true
                        // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
                        stopService(PlayerBackgroundPlaybackService.newIntent(this@PlayerActivity))
                        finish()
                    },
                )
            }
            // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaHolder = null
            backgroundPlaybackService = null
        }
    }
    // <-- AM (SERVICE_OWNED_PLAYER)

    // AM (DISCORD_RPC) -->
    // private val connectionPreferences: ConnectionPreferences = Injekt.get()
    // <-- AM (DISCORD_RPC)

    private var pipRect: Rect? = null
    val isPipSupportedAndEnabled by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            playerPreferences.enablePip.get()
    }

    private var pipReceiver: BroadcastReceiver? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        var initialized = false
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                viewModel.pause()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        var initialized = false
        override fun onReceive(context: Context?, intent: Intent?) {
            // Screen-off alone doesn't trigger onPause()/onStop() while still
            // foreground, so background playback needs its own trigger here.
            // Not handling ACTION_SCREEN_ON: it fires for any screen-on (e.g. lock
            // screen), not necessarily app visibility - onStart() covers that.
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (playerPreferences.backgroundPlayback.get() && !viewModel.playbackData.value.paused) {
                    startBackgroundPlayback()
                }
            }
        }
    }

    companion object {
        // AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX) -->
        // Refcount, not a plain boolean - a boolean set in onDestroy() would be a real
        // race: the notification-reopen transition can have a new PlayerActivity
        // instance's onCreate() run before the old, now-finishing instance's onDestroy()
        // gets around to running, so an unconditional "false" write in the old
        // instance's onDestroy() could stomp a "true" the new instance already set.
        // Increments/decrements are commutative regardless of ordering, so this stays
        // correct no matter which instance's lifecycle callback runs first.
        private var liveInstanceCount = 0

        /**
         * True while at least one PlayerActivity instance is alive (created, not yet
         * destroyed) - regardless of whether it's currently resumed, paused, or fully
         * backgrounded behind another screen in the app.
         *
         * PlayerBackgroundPlaybackService.buildReopenPendingIntent() uses this to decide
         * whether the notification's reopen action needs the synthetic MainActivity ->
         * PlayerActivity back stack at all. That synthetic stack exists to give
         * PlayerActivity a parent when the *real* back stack is gone (Activity destroyed,
         * Service kept playback going) - but PlayerActivity is singleTask, so if an
         * instance already exists anywhere, Android reroutes that hop of the stack build
         * to the existing task regardless of the Intent's own flags, while
         * TaskStackBuilder has already committed to building a brand-new task for
         * MainActivity as the first hop. That leaves two competing tasks momentarily in
         * flight with no deterministic winner - the "opens from the notification while
         * still inside the app" failure this signal exists to avoid entirely, by skipping
         * the synthetic stack (and MainActivity) altogether whenever a real target
         * already exists to route to directly.
         */
        val isAnyInstanceAlive: Boolean
            get() = liveInstanceCount > 0
        // <-- AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX)

        fun newIntent(
            context: Context,
            animeId: Long?,
            episodeId: Long?,
            hostList: List<Hoster>? = null,
            hostIndex: Int? = null,
            vidIndex: Int? = null,
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra("animeId", animeId)
                putExtra("episodeId", episodeId)
                hostIndex?.let { putExtra("hostIndex", it) }
                vidIndex?.let { putExtra("vidIndex", it) }
                hostList?.let { putExtra("hostList", it.serialize()) }
                // AM (PIP_REOPEN_DUPLICATE_TASK_FIX) -->
                // Explicitly setting NEW_TASK here (rather than letting Android inject it
                // implicitly at PendingIntent.send() time when this Intent is fired from a
                // non-Activity context, e.g. PlayerBackgroundPlaybackService's reopen
                // notification) gives the framework full task-affinity information up
                // front. The implicit-injection path was observed to briefly stand up a
                // second, genuine Task record for this singleTask Activity before
                // reconciling back to the existing one - and the system's cleanup of that
                // transient duplicate was tearing down the real, live task instead of an
                // empty one. Paired with the existing CLEAR_TOP, this is the standard
                // combo for "bring an existing singleTask instance to front via
                // onNewIntent" recommended for PendingIntents fired from services.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // <-- AM (PIP_REOPEN_DUPLICATE_TASK_FIX)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // AM (RESUMED_INSTANCE_RECREATE_CRASH_FIX) -->
                // CLEAR_TOP without SINGLE_TOP has a well-documented Android gotcha: if
                // the target Activity is already the resumed/topmost instance, Android
                // finishes and recreates it instead of routing through onNewIntent() -
                // this combination was previously only reachable via a rarely-hit
                // fallback path, but reopening while PlayerActivity is already alive and
                // foreground now goes through this exact intent as the primary route.
                // That rapid finish-then-recreate tears down and reconstructs the native
                // mpv context back-to-back on the main thread - mpv's own internal
                // decode/audio threads are asynchronous to that, so one still mid-callback
                // when mpv.close() destroys the context is a real "pthread_mutex_lock on a
                // destroyed mutex" native crash, not a routing failure. SINGLE_TOP tells
                // Android to deliver via onNewIntent() instead of recreating when already
                // on top, which is the correct behavior for every caller of newIntent() -
                // there's no legitimate case where destroying and rebuilding an
                // already-foreground PlayerActivity for the exact same launch is desired.
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // <-- AM (RESUMED_INSTANCE_RECREATE_CRASH_FIX)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val animeId = intent.extras?.getLong("animeId") ?: -1
        val episodeId = intent.extras?.getLong("episodeId") ?: -1
        val hostList = intent.extras?.getString("hostList") ?: ""
        val hostIndex = intent.extras?.getInt("hostIndex") ?: -1
        val vidIndex = intent.extras?.getInt("vidIndex") ?: -1
        // AM (REOPEN_RACE_DIAGNOSTICS) -->
        logcat {
            "onNewIntent: animeId=$animeId, episodeId=$episodeId, " +
                "instanceHash=${System.identityHashCode(this)}, " +
                "viewModelHash=${System.identityHashCode(viewModel)}"
        }
        // <-- AM (REOPEN_RACE_DIAGNOSTICS)
        if (animeId == -1L || episodeId == -1L) {
            // AM (TASK_SWIPE_TEARDOWN_FIX) -->
            intentionalStop = true
            // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
            finish()
            return
        }
        NotificationReceiver.dismissNotification(
            this,
            animeId.hashCode(),
            Notifications.ID_NEW_EPISODES,
        )

        if (!viewModel.needsInit(animeId, episodeId)) {
            // Already playing this exact episode in THIS instance (e.g. a redundant
            // re-intent) - its own state is already correct, nothing to do.
            setIntent(intent)
            return
        }

        // AM (LAYERED_REATTACH_FIX) -->
        // This instance has no local state for animeId/episodeId (needsInit() above was
        // true) - but that doesn't necessarily mean the actual player needs to load
        // anything. If the canonical player already has this exact file loaded (e.g. a
        // fresh instance spun up by the notification's forced FLAG_ACTIVITY_NEW_TASK
        // while the original task's back stack was lost), this instance still needs its
        // own metadata (anime/episode/hoster list/PIP flag - all populated by init()
        // below regardless), but must NOT re-run the network/hoster-resolution/
        // mpv-loadfile pipeline against a file that's already loaded and playing.
        val alreadyLiveInPlayer = viewModel.isSessionAlreadyLiveInPlayer(animeId, episodeId)
        // AM (REOPEN_RACE_DIAGNOSTICS) -->
        logcat { "onNewIntent: alreadyLiveInPlayer=$alreadyLiveInPlayer for ($animeId, $episodeId)" }
        // <-- AM (REOPEN_RACE_DIAGNOSTICS)
        if (alreadyLiveInPlayer) {
            // AM (LIVE_HOLDER_PLAYBACK_STATE) -->
            // Seed the correct paused state immediately rather than waiting on this
            // fresh instance's own reactive player-flow wiring to catch up.
            viewModel.syncPlaybackStateFromHolder()
            // <-- AM (LIVE_HOLDER_PLAYBACK_STATE)
        }
        // <-- AM (LAYERED_REATTACH_FIX)

        viewModel.saveCurrentEpisodeWatchingProgress()

        lifecycleScope.launchNonCancellable {
            viewModel.updateIsLoadingEpisode(!alreadyLiveInPlayer)
            viewModel.updateIsLoadingHosters(!alreadyLiveInPlayer)
            viewModel.updateHasPip(
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    playerPreferences.enablePip.get(),
            )
            if (!alreadyLiveInPlayer) {
                // In-memory state was lost (e.g. process killed while backgrounded) and
                // this is a genuine re-init, not a fresh open - resume from the position
                // just saved above instead of the normal "already watched -> start at 0"
                // rule.
                viewModel.forceResumeFromLastPosition = true
            }

            val initResult = viewModel.init(animeId, episodeId, hostList, hostIndex, vidIndex)
            if (!initResult.second.getOrDefault(false)) {
                val exception = initResult.second.exceptionOrNull() ?: IllegalStateException(
                    "Unknown error",
                )
                withUIContext {
                    setInitialEpisodeError(exception)
                }
            }

            viewModel.updateIsLoadingHosters(false)

            // AM (LAYERED_REATTACH_FIX) -->
            if (alreadyLiveInPlayer) {
                viewModel.syncHosterUiStateWithoutReload(initResult.first)
            } else {
                // <-- AM (LAYERED_REATTACH_FIX)
                lifecycleScope.launch {
                    viewModel.loadHosters(
                        hosterList = initResult.first.hosterList ?: emptyList(),
                        hosterIndex = initResult.first.videoIndex.first,
                        videoIndex = initResult.first.videoIndex.second,
                    )
                }
            }
        }

        setIntent(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)
        // AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX) -->
        // Paired 1:1 with the decrement in onDestroy() - see isAnyInstanceAlive's doc
        // comment for why this exists.
        liveInstanceCount++
        // <-- AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX)

        // AM (MEDIA_SESSION_SERVICE_OWNED) -->
        // No longer set up synchronously here - it's created/redirected via the
        // holder once mediaHolderConnection binds (setupMediaSessionCallback()),
        // same timing as the notification and player adoption below.
        // <-- AM (MEDIA_SESSION_SERVICE_OWNED)
        viewModel.setupPlayerOrientation()

        // AM (SERVICE_OWNED_PLAYER) -->
        // Step 3: an explicit (non-foreground) start, independent of any bind/unbind
        // cycle, so the Service - and the player it ends up holding - survives this
        // Activity's destruction whenever playback is meant to continue (see onDestroy).
        // Deliberately NOT startForegroundService here: that requires calling
        // startForeground() within ~5s or the process crashes. In practice that
        // deadline is met anyway, since mediaHolderConnection's onServiceConnected
        // (below) calls the Service's real notification-posting start() - which does
        // call startForeground() - essentially immediately after this. Using a plain
        // start() here just means the Service surviving isn't ITSELF gated on that
        // 5s deadline if binding is ever slower than expected.
        startService(PlayerBackgroundPlaybackService.newIntent(this))
        bindService(
            PlayerBackgroundPlaybackService.newIntent(this),
            mediaHolderConnection,
            Context.BIND_AUTO_CREATE,
        )
        // <-- AM (SERVICE_OWNED_PLAYER)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            runOnUiThread {
                toast(throwable.message)
            }
            logcat(LogPriority.ERROR, throwable)
            // AM (TASK_SWIPE_TEARDOWN_FIX) -->
            intentionalStop = true
            // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
            finish()
        }

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    PlayerViewModel.Event.EnterPip -> {
                        enterPictureInPictureMode(createPipParams())
                    }
                    is PlayerViewModel.Event.EpisodeTitle -> {
                        // No-op: used to toast "<anime> - <episode>" on change; not wanted.
                    }
                    PlayerViewModel.Event.Finish -> {
                        // AM (TASK_SWIPE_TEARDOWN_FIX) -->
                        intentionalStop = true
                        // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
                        finish()
                    }
                    is PlayerViewModel.Event.InitialEpisodeError -> {
                        setInitialEpisodeError(event.error)
                    }
                    is PlayerViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is PlayerViewModel.Event.SetArtResult -> {
                        onSetAsArtResult(event.result, event.artType)
                    }
                    is PlayerViewModel.Event.SetKeyboard -> {
                        if (event.show) {
                            forceShowSoftwareKeyboard()
                        } else {
                            forceHideSoftwareKeyboard()
                        }
                    }
                    is PlayerViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.seconds)
                    }
                    is PlayerViewModel.Event.ToastResource -> {
                        showToast(this.stringResource(event.stringRes))
                    }
                    is PlayerViewModel.Event.ToastString -> {
                        showToast(event.string)
                    }
                    PlayerViewModel.Event.ToggleKeyboard -> {
                        toggleShowSoftwareKeyboard()
                    }
                }
            }
            .launchIn(lifecycleScope)

        // PIP params otherwise only refresh right after next/previous, before the new
        // episode's async-loaded dimensions are known, so react to dimension changes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            viewModel.stateData
                .map { it.videoWidth to it.videoHeight }
                .distinctUntilChanged()
                .onEach {
                    if (isInPictureInPictureMode) {
                        setPictureInPictureParams(createPipParams())
                    }
                }
                .launchIn(lifecycleScope)

            // AM (PIP_NEXT_PAUSE_ICON) -->
            // changeEpisode() pauses synchronously to freeze the frame during the async
            // load, then unpauses once the new episode starts. Manual next/previous
            // refreshes PIP params immediately after triggering the switch, capturing
            // that transient paused=true before the new episode actually resumes - so
            // react to paused-state changes too, not just dimension changes, to catch
            // same-resolution episodes where the dimension observer above never fires.
            viewModel.playbackData
                .map { it.paused }
                .distinctUntilChanged()
                .onEach {
                    if (isInPictureInPictureMode) {
                        setPictureInPictureParams(createPipParams())
                    }
                }
                .launchIn(lifecycleScope)
            // <-- AM (PIP_NEXT_PAUSE_ICON)
        }

        // Keep PlaybackState genuinely current - OEM battery managers look for real
        // STATE_PLAYING/position to exempt media apps from background restrictions.
        viewModel.playbackData
            .map { it.paused to it.position }
            .distinctUntilChanged()
            .onEach { (paused, position) ->
                mediaSession?.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or
                                PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_STOP or
                                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackState.ACTION_SKIP_TO_NEXT,
                        )
                        .setState(
                            if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                            position * 1000L,
                            1f,
                        )
                        .build(),
                )
            }
            .launchIn(lifecycleScope)

        // Duration/seek-bar/thumbnail in the media notification come from MediaMetadata,
        // not PlaybackState - without this they show blank despite PlaybackState working.
        combine(
            viewModel.stateData.map { it.currentAnime to it.currentEpisode }.distinctUntilChanged(),
            viewModel.playbackData.map { it.duration }.distinctUntilChanged(),
            viewModel.thumbnailGenerated,
        ) { (anime, episode), duration, _ -> Triple(anime, episode, duration) }
            .onEach { (anime, episode, duration) ->
                if (anime == null || episode == null) return@onEach
                val artwork = runCatching {
                    val request = ImageRequest.Builder(this@PlayerActivity)
                        .data(episode.preview_url?.takeIf { it.isNotBlank() } ?: anime)
                        .size(Size.ORIGINAL)
                        .build()
                    imageLoader.execute(request).image
                        ?.asDrawable(resources)
                        ?.toBitmap()
                }.getOrNull()

                mediaSession?.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, episode.name)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, anime.title)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, duration * 1000L)
                        .apply {
                            if (artwork != null) {
                                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                            }
                        }
                        .build(),
                )
                // Metadata alone won't redraw an already-posted background-playback
                // notification - keep the service's title/subtitle/reopen-intent in sync too.
                backgroundPlaybackService?.updateEpisodeInfo(
                    title = anime.title,
                    subtitle = episode.name,
                    animeId = anime.id,
                    episodeId = episode.id,
                )
            }
            .launchIn(lifecycleScope)

        setContent {
            TachiyomiTheme {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = {
                        // AM (UNIFIED_BACK_HANDLING) -->
                        // No PIP check here anymore - PlayerScreen's handleBackPress is
                        // now the single place that decides PIP-vs-open-app, for both the
                        // system back gesture and the on-screen back button alike. This
                        // lambda only ever runs once that's already decided against PIP,
                        // so it's purely the "actually leave the player" fallback.
                        // <-- AM (UNIFIED_BACK_HANDLING)
                        // AM (BACK_FALLBACK_TO_ANIME) -->
                        // If this Activity is its task's root, finish() alone drops
                        // straight to the launcher instead of back into the app -
                        // there's nothing else left in THIS task's back stack. Most
                        // common cause: reopening from the background-playback
                        // notification (a Service-originated PendingIntent, so
                        // Android forces FLAG_ACTIVITY_NEW_TASK on it) after the
                        // original task was swiped from Recents while playback kept
                        // the process alive. Reuses the same SHOW_ANIME deep link
                        // NotificationReceiver.openEntryPendingActivity() already
                        // relies on, so MainActivity lands back on the anime being
                        // watched instead of an empty stack.
                        if (isTaskRoot) {
                            viewModel.stateData.value.currentAnime?.id?.let { animeId ->
                                startActivity(
                                    Intent(this, MainActivity::class.java)
                                        .setAction(Constants.SHORTCUT_ANIME)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        .putExtra(Constants.ANIME_EXTRA, animeId),
                                )
                            }
                        }
                        // <-- AM (BACK_FALLBACK_TO_ANIME)
                        // AM (TASK_SWIPE_TEARDOWN_FIX) -->
                        intentionalStop = true
                        // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
                        finish()
                    },
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        pipRect = run {
                            val boundsInWindow = it.boundsInWindow()
                            Rect(
                                boundsInWindow.left.toInt(),
                                boundsInWindow.top.toInt(),
                                boundsInWindow.right.toInt(),
                                boundsInWindow.bottom.toInt(),
                            )
                        }
                    },
                )
            }
        }

        onNewIntent(this.intent)
    }

    override fun onDestroy() {
        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        // Safety net: onPictureInPictureModeChanged(false, ...) normally clears this, but
        // isn't guaranteed to fire if the activity is destroyed directly out of PIP.
        SecureActivityDelegate.setPipActive(false)
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)

        // AM (SERVICE_OWNED_PLAYER) -->
        // Step 3: onDestroy fires both when this session genuinely ends and when the OS
        // (or a Recents task removal) destroys this Activity while playback is meant to
        // continue - those need very different teardown. Previously every line below ran
        // whenever isFinishing() was true, which meant an ordinary background eviction
        // would kill the notification/foreground state and, shortly after, playback
        // itself - likely a real contributor to the lifecycle-order-dependent bugs this
        // refactor exists to fix.
        // AM (TASK_SWIPE_TEARDOWN_FIX) -->
        // isFinishing() alone isn't a reliable "genuinely done" signal: Android also sets
        // it to true when the *whole task* is removed from Recents, which is exactly the
        // scenario the synthetic-back-stack notification-reopen architecture exists to
        // survive. Gate on the explicit intentionalStop flag (set at every real "user is
        // done" finish() call site) instead, falling back to isFinishing only when
        // nothing marked it as intentional AND playback wasn't actively continuing in the
        // background anyway - covering any finish() path this flag doesn't yet cover
        // without resurrecting the task-swipe bug for the paths it does.
        // AM (REOPEN_RACE_DIAGNOSTICS) -->
        logcat {
            "onDestroy: intentionalStop=$intentionalStop, isFinishing=$isFinishing, " +
                "isBackgroundPlaybackActive=${SecureActivityDelegate.isBackgroundPlaybackActive}, " +
                "animeId=${viewModel.stateData.value.currentAnime?.id}, " +
                "episodeId=${viewModel.stateData.value.currentEpisode?.id}"
        }
        // <-- AM (REOPEN_RACE_DIAGNOSTICS)
        if (intentionalStop || (isFinishing && !SecureActivityDelegate.isBackgroundPlaybackActive)) {
            // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
            // Genuine end of this playback session - tear everything down.
            logcat { "onDestroy: tearing down (mediaHolder.release(), stopService)" }
            // AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX) -->
            // No refresh call needed here (unlike the preserving-session branch below) -
            // stopService() a few lines down removes the notification entirely, so
            // there's nothing left whose cached PendingIntent could go stale.
            liveInstanceCount--
            // <-- AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX)
            stopBackgroundPlayback()
            viewModel.player.release()
            viewModel.stopHttpServer()
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
            // AM (STALE_HOLDER_STATE_FIX) -->
            // Clear the holder's own player reference and session state synchronously,
            // here, rather than relying solely on stopService() below to eventually get
            // to it - stopService() is asynchronous, so the Service (and this same
            // PlayerMediaHolder instance) can still be alive for a window after this
            // call returns. A fast reopen (tapping the always-on notification quickly)
            // can bind to that still-alive holder before its own onDestroy() runs, and
            // without clearing state here first, needsInit() on that reopen would see
            // stale animeId/episodeId still "matching" and skip reinitializing against
            // a player already released two lines above.
            mediaHolder?.release()
            // <-- AM (STALE_HOLDER_STATE_FIX)
            stopService(PlayerBackgroundPlaybackService.newIntent(this))
        } else {
            // AM (REOPEN_RACE_DIAGNOSTICS) -->
            logcat { "onDestroy: preserving session (Service/mediaHolder left running)" }
            // <-- AM (REOPEN_RACE_DIAGNOSTICS)
            // AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX) -->
            // Decrement before the refresh call below, and only on this branch: this is
            // the one case where isAnyInstanceAlive is actually about to become false
            // while the Service itself lives on - the notification's cached reopen
            // PendingIntent was most recently built while an instance was still alive
            // (skipping the synthetic stack), so it needs rebuilding now to switch back
            // to that stack before anyone can tap it against this now-stale state.
            liveInstanceCount--
            backgroundPlaybackService?.refreshReopenIntent()
            // <-- AM (SYNTHETIC_STACK_LIVE_INSTANCE_FIX)
        }
        // else: this Activity instance is being destroyed while playback is meant to
        // continue - leave the Service, its player, its notification (if active), and
        // the MediaSession running. A future reattach adopts the same player via
        // PlayerMediaHolder.adopt() instead of these being torn down out from under it.

        unbindService(mediaHolderConnection)
        mediaHolder = null
        // <-- AM (SERVICE_OWNED_PLAYER)

        if (noisyReceiver.initialized) {
            unregisterReceiver(noisyReceiver)
            noisyReceiver.initialized = false
        }

        if (screenStateReceiver.initialized) {
            unregisterReceiver(screenStateReceiver)
            screenStateReceiver.initialized = false
        }

        super.onDestroy()
    }

    private fun startBackgroundPlayback() {
        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        // Step 4a: represents "the user is leaving the foreground UI while playback
        // continues," independent of notification state. Step 4b: this function no
        // longer starts/binds the Service or posts the notification - mediaHolderConnection
        // (bound for the whole session in onCreate) already handles that unconditionally,
        // since the notification is now always-on while playing. This is just the
        // exemption toggle now; setBackgroundServiceActive is idempotent, so no guard
        // is needed against calling it more than once.
        SecureActivityDelegate.setBackgroundServiceActive(true)
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
    }

    private fun stopBackgroundPlayback() {
        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        SecureActivityDelegate.setBackgroundServiceActive(false)
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
    }

    override fun onPause() {
        viewModel.saveCurrentEpisodeWatchingProgress()

        if (isInPictureInPictureMode) {
            super.onPause()
            return
        }

        viewModel.setPlayerExiting(true)
        // AM (TASK_SWIPE_TEARDOWN_FIX) -->
        // isFinishing() is already true here for a Recents task removal - Android marks
        // it before dispatching onPause(), not just by the time onDestroy() checks it -
        // so this used to hard-stop mpv (mpvCommand("stop")) and skip
        // startBackgroundPlayback() below entirely for a task swipe while actively
        // playing, the exact scenario the notification-reopen architecture exists to
        // survive. That stop is what produced the dead-air/rubber-banding notification
        // seekbar: mpv went silent immediately, but the MediaSession's last reported
        // state stayed "playing" until something later pushed a fresh update, so the
        // system extrapolated the bar forward in the meantime and snapped it back once
        // real playback resumed and reported its true position. Gate the hard stop on
        // intentionalStop (set at every genuine "user is done" finish() call site,
        // already true here by the time onPause() runs as part of that finish()
        // sequence) instead, matching onDestroy()'s gate below.
        if (intentionalStop) {
            viewModel.deletePendingEpisodes()
            viewModel.mpvCommand("stop")
        } else if (playerPreferences.backgroundPlayback.get() && !viewModel.playbackData.value.paused) {
            // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
            // The Service/player/notification keep running regardless (they're not
            // tied to this Activity's visibility since step 4b) - this branch is only
            // about whether backgrounding should also mark the app-lock exemption and
            // leave playback running, versus falling through to an actual pause below.
            startBackgroundPlayback()
        } else {
            viewModel.pause()
        }

        super.onPause()
    }

    override fun onStop() {
        if (isInPictureInPictureMode && powerManager.isInteractive) {
            viewModel.deletePendingEpisodes()
        }

        super.onStop()
    }

    override fun onUserLeaveHint() {
        if (isPipSupportedAndEnabled && !viewModel.playbackData.value.paused && playerPreferences.pipOnExit.get()) {
            enterPictureInPictureMode()
        }
        super.onUserLeaveHint()
    }

    override fun onStart() {
        super.onStart()
        // Foreground again - the app-lock "user is away while playback continues"
        // exemption no longer applies. The notification itself is untouched here -
        // it stays up regardless of foreground/background state since step 4b.
        stopBackgroundPlayback()
        setPictureInPictureParams(createPipParams())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LOW_PROFILE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = if (playerPreferences.playerFullscreen.get()) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
    }

    override fun onResume() {
        if (!viewModel.isPlayerExiting()) {
            super.onResume()
            return
        }

        viewModel.setPlayerExiting(false)
        super.onResume()

        viewModel.setVolumeTo(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also {
                if (it < viewModel.stateData.value.maxVolume) viewModel.changeMPVVolumeTo(100)
            },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!isInPictureInPictureMode) {
            // showConfirmation = false: reapplies the persisted aspect on config change
            // (rotation, initial callback) - not a deliberate user choice, no toast.
            viewModel.setAspectRatio(playerPreferences.aspectState.get(), showConfirmation = false)
        } else {
            viewModel.hideControls()
        }
        super.onConfigurationChanged(newConfig)
    }

    fun showToast(stringRes: StringResource) {
        runOnUiThread { toast(stringRes) }
    }

    fun showToast(message: String) {
        runOnUiThread { toast(message) }
    }

    fun createPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val anime = viewModel.stateData.value.currentAnime
            val episode = viewModel.stateData.value.currentEpisode

            if (anime != null && episode != null) {
                builder.setTitle(anime.title).setSubtitle(episode.name)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val autoEnter = playerPreferences.pipOnExit.get()
            builder.setAutoEnterEnabled(!viewModel.playbackData.value.paused && autoEnter)
            builder.setSeamlessResizeEnabled(!viewModel.playbackData.value.paused && autoEnter)
        }
        builder.setActions(
            createPipActions(
                context = this,
                isPaused = viewModel.playbackData.value.paused,
                firstButtonAction = playerPreferences.pipFirstButtonAction.get(),
                playlistCount = viewModel.stateData.value.currentPlaylist.size,
                playlistPosition = viewModel.stateData.value.currentPlaylistIndex,
            ),
        )
        builder.setSourceRectHint(pipRect)
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
        return builder.build()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        // Entering PIP is safe to mark immediately. Clearing it on exit is deferred into
        // each branch below so startBackgroundPlayback() (when it runs) can set
        // isBackgroundServiceActive = true first - otherwise there's a brief window where
        // both flags are false and a concurrently-firing ProcessLifecycleOwner stop can
        // wrongly consume the exemption.
        if (isInPictureInPictureMode) {
            SecureActivityDelegate.setPipActive(true)
        }
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)

        if (!isInPictureInPictureMode) {
            pipReceiver?.let {
                unregisterReceiver(pipReceiver)
                pipReceiver = null
            }

            if (isIntentionalBackgroundTransition) {
                isIntentionalBackgroundTransition = false
                SecureActivityDelegate.setPipActive(false)
            } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
                // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
                // The system also fires this when the screen turns off during PIP (tearing
                // down the PIP surface for the lock screen), not just on a genuine user
                // swipe-away. Only treat it as dismissal when the screen is actually on -
                // otherwise fall back to background playback the same way onPause() does,
                // so the session survives and the app lock stays exempt.
                if (powerManager.isInteractive) {
                    // AM (PIP_REOPEN_RACE_FIX) -->
                    // Both the exemption clear and the teardown used to fire unconditionally
                    // here - correct for a genuine swipe-away, but if a concurrent reopen
                    // (e.g. tapping the always-on notification while this Activity is still
                    // technically mid-PIP-exit) reclaims this same instance within the delay
                    // window, that reopen already correctly recognizes "already playing this,
                    // no reload needed" via needsInit() - and then this callback would release
                    // the very player it just decided not to reinitialize, leaving playback
                    // permanently stuck, while the immediate exemption clear below would also
                    // cause that reopen's own lock check to fire even though playback never
                    // stopped. Re-validating at execution time instead of trusting the state
                    // at schedule time makes this self-correcting either way: if the reclaim
                    // already brought this instance back to STARTED/RESUMED, currentState is
                    // no longer CREATED and this was never actually a genuine swipe-away, so
                    // skip both the exemption clear and the teardown entirely.
                    window.decorView.postDelayed(
                        {
                            if (lifecycle.currentState == Lifecycle.State.CREATED && !isFinishing) {
                                SecureActivityDelegate.setPipActive(false)
                                viewModel.player.release()
                                // AM (TASK_SWIPE_TEARDOWN_FIX) -->
                                intentionalStop = true
                                // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
                                finish()
                            }
                        },
                        100,
                    )
                    // <-- AM (PIP_REOPEN_RACE_FIX)
                } else if (playerPreferences.backgroundPlayback.get() && !viewModel.playbackData.value.paused) {
                    startBackgroundPlayback()
                    // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
                    // Use the handoff clear here, not setPipActive(false) - the service
                    // start above is async, so isBackgroundServiceActive isn't true yet.
                    SecureActivityDelegate.clearPipForBackgroundHandoff()
                    // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
                } else {
                    viewModel.pause()
                    SecureActivityDelegate.setPipActive(false)
                }
                // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
            } else {
                // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
                SecureActivityDelegate.setPipActive(false)
                // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
                window.attributes = window.attributes.apply {
                    screenBrightness = viewModel.playbackData.value.currentBrightness.coerceIn(0f, 1f)
                }
            }
        } else {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            setPictureInPictureParams(createPipParams())
            viewModel.hideControls()
            viewModel.hideSeekBar()
            viewModel.displayBrightnessSlider(false)
            viewModel.displayVolumeSlider(false)
            viewModel.setSheet(Sheets.None)
            pipReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || intent.action != PIP_INTENTS_FILTER) return
                    when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
                        PIP_PAUSE -> viewModel.pause()
                        PIP_PLAY -> viewModel.unpause()
                        PIP_NEXT -> viewModel.nextEpisode(next = true)
                        PIP_PREVIOUS -> viewModel.nextEpisode(next = false)
                        PIP_SKIP -> viewModel.seekBy(10)
                        PIP_BACKGROUND_PLAY -> {
                            // Manually trigger the background-audio path (onPause() skips
                            // it while still in PIP), then moveTaskToBack() to exit PIP.
                            // Must start the service even while paused - it's an explicit
                            // user action, and nothing else keeps the player alive here.
                            if (playerPreferences.backgroundPlayback.get()) {
                                startBackgroundPlayback()
                            }
                            isIntentionalBackgroundTransition = true
                            moveTaskToBack(true)
                        }
                    }
                    setPictureInPictureParams(createPipParams())
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER), RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER))
            }
        }

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
            // AM (TASK_SWIPE_TEARDOWN_FIX) -->
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                intentionalStop = true
                finishAndRemoveTask()
            }
            // <-- AM (TASK_SWIPE_TEARDOWN_FIX)

            KeyEvent.KEYCODE_MEDIA_REWIND -> viewModel.handleLeftDoubleTap()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> viewModel.handleRightDoubleTap()

            // other keys should be bound by the user in input.conf ig
            else -> {
                event?.let { viewModel.onKey(it) }
                super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.onKey(event!!)) return true
        return super.onKeyUp(keyCode, event)
    }

    // AM (MEDIA_SESSION_SERVICE_OWNED) -->
    // Builds the media-button callback and registers/redirects it via the holder's
    // ensureMediaSession(). Callback body is otherwise unchanged from before this
    // moved to the Service - it still closes over this instance's viewModel/gesture
    // prefs, which is correct: ensureMediaSession() redirects the session to
    // whichever instance called it most recently, so a reattach's callback always
    // wins over a stale one from a dead Activity.
    private fun setupMediaSessionCallback(holder: PlayerMediaHolder) {
        // <-- AM (MEDIA_SESSION_SERVICE_OWNED)
        val previousAction = gesturePreferences.mediaPreviousGesture.get()
        val playAction = gesturePreferences.mediaPlayPauseGesture.get()
        val nextAction = gesturePreferences.mediaNextGesture.get()

        // Declared before the callback below so onStop() can reference the session
        // it's attached to - the callback object is built before ensureMediaSession()
        // returns it, so it can't be captured any other way. Safe: onStop() only ever
        // runs later, once session is definitely assigned.
        lateinit var session: MediaSession

        val callback = object : MediaSession.Callback() {
            override fun onPlay() {
                when (playAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {}
                    SingleActionGesture.PlayPause -> {
                        super.onPlay()
                        viewModel.unpause()
                    }
                    SingleActionGesture.Custom -> {
                        viewModel.mpvCommand("keypress", CustomKeyCodes.MediaPlay.keyCode)
                    }

                    SingleActionGesture.Switch -> {}
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onPause() {
                when (playAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {}
                    SingleActionGesture.PlayPause -> {
                        super.onPause()
                        viewModel.pause()
                    }
                    SingleActionGesture.Custom -> {
                        viewModel.mpvCommand("keypress", CustomKeyCodes.MediaPlay.keyCode)
                    }

                    SingleActionGesture.Switch -> {}
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onSkipToPrevious() {
                when (previousAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {
                        viewModel.leftSeek()
                    }
                    SingleActionGesture.PlayPause -> {
                        viewModel.pauseUnpause()
                    }
                    SingleActionGesture.Custom -> {
                        viewModel.mpvCommand("keypress", CustomKeyCodes.MediaPrevious.keyCode)
                    }

                    SingleActionGesture.Switch -> viewModel.nextEpisode(next = false)
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onSkipToNext() {
                when (nextAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {
                        viewModel.rightSeek()
                    }
                    SingleActionGesture.PlayPause -> {
                        viewModel.pauseUnpause()
                    }
                    SingleActionGesture.Custom -> {
                        viewModel.mpvCommand("keypress", CustomKeyCodes.MediaNext.keyCode)
                    }

                    SingleActionGesture.Switch -> viewModel.nextEpisode(next = true)
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onStop() {
                super.onStop()
                session.isActive = false
                this@PlayerActivity.onStop()
            }
        }

        session = holder.ensureMediaSession(this, callback).apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT,
                    )
                    .build(),
            )
            isActive = true
        }

        // AM (MEDIA_SESSION_SERVICE_OWNED) -->
        // Guarded: this function now runs from mediaHolderConnection.onServiceConnected,
        // which could in theory fire more than once per Activity instance if the Service
        // ever disconnects and reconnects mid-session - without this check that would
        // double-register these receivers and crash.
        if (!noisyReceiver.initialized) {
            val filter = IntentFilter().apply { addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY) }
            registerReceiver(noisyReceiver, filter)
            noisyReceiver.initialized = true
        }

        if (!screenStateReceiver.initialized) {
            val screenStateFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenStateReceiver, screenStateFilter)
            screenStateReceiver.initialized = true
        }
        // <-- AM (MEDIA_SESSION_SERVICE_OWNED)
    }

    // ==== END MPVKT ====

    override fun onSaveInstanceState(outState: Bundle) {
        if (!isChangingConfigurations) {
            viewModel.onSaveInstanceStateNonConfigurationChange()
        }
        super.onSaveInstanceState(outState)
    }

    /** Shows a toast and closes the activity when the initial episode load fails. */
    private fun setInitialEpisodeError(error: Throwable) {
        if (error is PlayerViewModel.ExceptionWithStringResource) {
            showToast(error.stringResource)
        } else {
            showToast(error.message ?: "")
        }
        logcat(LogPriority.ERROR, error)
        // AM (TASK_SWIPE_TEARDOWN_FIX) -->
        intentionalStop = true
        // <-- AM (TASK_SWIPE_TEARDOWN_FIX)
        finish()
    }

    /** Shows Android's share sheet for a captured screenshot. */
    private fun onShareImageResult(uri: Uri, seconds: String) {
        val anime = viewModel.stateData.value.currentAnime ?: return
        val episode = viewModel.stateData.value.currentEpisode ?: return

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(AYMR.strings.share_screenshot_info, anime.title, episode.name, seconds),
        )
        startActivity(intent)
    }

    private fun onSaveImageResult(result: PlayerViewModel.SaveImageResult) {
        when (result) {
            is PlayerViewModel.SaveImageResult.Success -> {
                showToast(MR.strings.picture_saved)
            }
            is PlayerViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    private fun onSetAsArtResult(result: SetAsArt, artType: ArtType) {
        showToast(
            when (result) {
                SetAsArt.Success ->
                    when (artType) {
                        ArtType.Cover -> MR.strings.cover_updated
                        ArtType.Background -> AYMR.strings.background_updated
                        ArtType.Thumbnail -> AYMR.strings.thumbnail_updated
                    }
                SetAsArt.AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                SetAsArt.Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    private fun toggleShowSoftwareKeyboard() {
        if (inputMethodManager.isActive) {
            forceHideSoftwareKeyboard()
        } else {
            forceShowSoftwareKeyboard()
        }
    }

    private fun forceShowSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun forceHideSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    // AM (DISCORD_RPC) -->
    /*
     private fun updateDiscordRPC(exitingPlayer: Boolean) {
     DiscordRPCService.discordScope.launchIO {
     if (connectionPreferences.enableDiscordRPC.get()) {
     if (!exitingPlayer) {
     DiscordRPCService.setPlayerActivity(
     context = applicationContext,
     PlayerData(
     incognitoMode = viewModel.currentSource.isNsfw() || viewModel.incognitoMode,
     animeId = viewModel.currentAnime?.id,
     // AM (CUSTOM_INFORMATION) -->
     animeTitle = viewModel.currentAnime?.ogTitle,
     // <-- AM (CUSTOM_INFORMATION)
     episodeNumber = viewModel.currentEpisode?.episode_number?.toString(),
     thumbnailUrl = viewModel.currentAnime?.thumbnailUrl,
     ),
     )
     } else {
     with(DiscordRPCService) {
     setScreen(this@PlayerActivity.applicationContext, lastUsedScreen)
     }
     }
     }
     }
     }
     // <-- AM (DISCORD_RPC)
     **/
}
