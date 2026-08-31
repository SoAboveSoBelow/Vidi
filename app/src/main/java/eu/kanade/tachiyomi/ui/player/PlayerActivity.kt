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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlinx.coroutines.flow.first
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

    // Set before an intentional backgrounding transition we triggered ourselves -
    // either the swipe-dismiss path's moveTaskToBack() or the headphones
    // "Background Play" action's finish() - so onPictureInPictureModeChanged
    // doesn't treat it as the user swiping PIP away unexpectedly.
    private var isIntentionalBackgroundTransition = false

    // AM (PIP_FINISH_NOT_MOVETASKTOBACK) -->
    /**
     * Set right before finish() from the headphones "Background Play" action,
     * checked ONLY by onDestroy()'s teardown-vs-preserve decision. Deliberately a
     * separate flag from isIntentionalBackgroundTransition above, not a reuse of
     * it: onPictureInPictureModeChanged(false, ...) can fire before onDestroy() in
     * this exact sequence and CLEARS that flag as part of its own, different
     * handling - reusing it here would risk onDestroy() checking it after it's
     * already been cleared, incorrectly treating this as a genuine end.
     *
     * The whole reason this flag (and the finish() call it guards) exists:
     * moveTaskToBack() while a task is still actively pinned (in PIP) is a
     * genuinely unusual operation - confirmed by direct observation, this app's
     * PIP crash never happens across two genuinely separate Activity
     * instances/tasks, only when a single task survives across this exact
     * transition. finish() from PIP is the standard, heavily-exercised way every
     * PIP app closes its window (Android/Samsung's own PIP implementation is
     * tested against this constantly) - relying on it instead deliberately
     * produces a genuinely fresh Task on the next reopen (since
     * LIVE_INSTANCE_REOPEN_FIX's hasLiveInstance correctly reads false once this
     * Activity is actually destroyed, routing the reopen through the synthetic
     * back-stack path rather than reusing anything), matching the one condition
     * already confirmed to work.
     */
    private var isBackgroundPlayTransitionFinish = false
    // <-- AM (PIP_FINISH_NOT_MOVETASKTOBACK)

    // AM (PIP_ENTRY_CANCELLED_FIX) -->
    // Set right before every enterPictureInPictureMode() call, cleared once
    // onPictureInPictureModeChanged actually confirms PIP was entered. Exists to
    // distinguish "a PIP entry attempt was just made and got cancelled by the OS
    // mid-flight" from "an already-established PIP window was genuinely dismissed by
    // the user" - both currently land in onPictureInPictureModeChanged's same
    // lifecycle.currentState == CREATED branch below, but they mean opposite things.
    // Confirmed via logcat: re-entering PIP on a reused instance (see
    // LIVE_INSTANCE_REOPEN_FIX) can get cancelled by the system
    // (clearWaitForEnteringPinnedMode reason=exit_pip) - without this flag, that
    // cancelled *entry* attempt was being treated identically to a genuine
    // swipe-away, tearing down the whole session for something the user never asked
    // to end.
    private var isPipEntryPending = false

    // AM (LIVE_REDELIVERY_TRUST_FIX) -->
    // False until onNewIntent() completes its first call - which onCreate() always
    // triggers internally, synchronously, for every fresh launch (see the bottom of
    // onCreate()). Any LATER onNewIntent() call only ever happens because the
    // system redelivered a new Intent onto this already-alive instance (the
    // SINGLE_TOP win case - e.g. tapping the reopen notification while still in
    // the app) - see onNewIntent()'s own doc comment for why that case is handled
    // completely differently from the initial one.
    private var hasProcessedInitialOnNewIntent = false
    // <-- AM (LIVE_REDELIVERY_TRUST_FIX)

    // AM (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX) -->
    // Teardown + relaunch, not hot-swap
    private var pendingFreshInstanceIntent: Intent? = null
    // <-- AM (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX)
    // <-- AM (PIP_ENTRY_CANCELLED_FIX)

    // AM (CONTINUE_BUTTON_RESUME_FIX) -->
    // Deferred the same way pendingFreshInstanceIntent/pendingSkip are: onNewIntent()'s
    // init() and mediaHolderConnection.onServiceConnected() are two independently
    // scheduled async callbacks, both triggered from onCreate(), with no guaranteed
    // ordering between them. reconcilePausedFromPlayer() (called from
    // onServiceConnected(), right after binding) re-syncs playbackData.paused from
    // mpv's actual property - whichever of that or a direct unpause() call here runs
    // second wins. Calling unpause() directly from onNewIntent() raced that: it could
    // land, then get silently overwritten back to paused=true moments later once
    // onServiceConnected() finally ran and reconciled from mpv - or vice versa, in
    // which case it worked, purely by luck of the scheduling that run. Deferring
    // through this flag - applied from onServiceConnected() right after
    // reconcilePausedFromPlayer(), or immediately inline if that binding has already
    // happened by the time onNewIntent() gets here - guarantees the resume always
    // applies after the reconcile, regardless of which callback actually runs first.
    private var pendingForceResume = false
    // <-- AM (CONTINUE_BUTTON_RESUME_FIX)

    // AM (PIP_RECREATE_FIX_REMOVED) -->
    // hasAttemptedPipEntryThisInstance removed along with the recreate()-based
    // workaround in tryEnterPictureInPicture() - see that function's doc comment.
    // <-- AM (PIP_RECREATE_FIX_REMOVED)

    // AM (DUPLICATE_INSTANCE_SELF_TERMINATE) -->
    /**
     * Set true only in onCreate(), only when liveInstanceCount shows another
     * PlayerActivity instance is already alive, right before this one immediately
     * finish()es itself. Confirmed directly by observation: duplicate instances of
     * this singleTask Activity CAN coexist (e.g. PIP within the app, then
     * backgrounding and expanding the PIP - a second, fully separate instance/task
     * appears, capped around two + PIP; Recents can also be seen forcibly closing
     * one while audio keeps playing via the other). That's a structural gap
     * LIVE_INSTANCE_REOPEN_FIX doesn't cover, since it only prevents duplicates
     * arising from the notification's own reopen PendingIntent specifically - not
     * from other paths that can independently end up constructing a second
     * instance. A live duplicate is a very plausible root cause for the PIP
     * re-entry crash investigated at length elsewhere in this file: two
     * ActivityRecords for the same component present at once is exactly the kind
     * of state Android's own PIP/task transaction handling would struggle with,
     * independent of any of this app's own timing.
     *
     * Every lifecycle callback below checks this first and returns immediately
     * when true, before touching viewModel/mediaHolder/anything else - this
     * instance's job is only to get out of the way as cleanly as possible, not to
     * participate in the shared session the original, still-alive instance owns.
     * Critically, this means viewModel is never accessed at all for a detected
     * duplicate, so its underlying MPVPlayer is never even constructed - nothing
     * to release, nothing to leak. onDestroy() also needs its own gate here,
     * separate from isFinishing: without it, this instance's teardown branch
     * would try to release/stop the Service-owned session the ORIGINAL instance
     * still depends on, since isFinishing is true here regardless of which
     * instance called finish() on itself.
     */
    private var isDuplicateInstanceSelfTerminating = false
    // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)

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
            // SVC_RACE_DEBUG -->
            logcat {
                "SVC_RACE_DEBUG Activity.onServiceConnected() activity=${System.identityHashCode(this@PlayerActivity)} " +
                    "service=${System.identityHashCode(binder.getService())} holder=${System.identityHashCode(bound)} " +
                    "holderHasAdoptedPlayer=${bound.hasAdoptedPlayer} at=${android.os.SystemClock.elapsedRealtime()}"
            }
            // <-- SVC_RACE_DEBUG
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

            // AM (MEDIA_SESSION_FALLBACK_CALLBACK) -->
            // Reconcile paused state now: mpv is the only source of truth for whether
            // playback is paused across a gap where control may have come from the
            // fallback callback rather than viewModel.pause()/unpause(). Deliberately
            // NOT consuming any pending skip here yet - this fresh instance's
            // stateData.currentPlaylist is still empty at this point (only populated
            // once onNewIntent()'s viewModel.init() completes, which hasn't run yet),
            // so nextEpisode() would silently no-op. See onNewIntent() for where the
            // pending skip actually gets applied, once a real playlist exists to apply
            // it against.
            viewModel.reconcilePausedFromPlayer()
            // <-- AM (MEDIA_SESSION_FALLBACK_CALLBACK)

            // AM (CONTINUE_BUTTON_RESUME_FIX) -->
            // See pendingForceResume's own doc comment. If onNewIntent()'s init()
            // finished before this callback fired, it deferred here instead of
            // calling unpause() directly - apply it now, guaranteed to run after
            // the reconcile immediately above.
            if (pendingForceResume) {
                pendingForceResume = false
                viewModel.unpause()
            }
            // <-- AM (CONTINUE_BUTTON_RESUME_FIX)

            // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
            // Step 4b: the notification now posts as soon as the Service is bound -
            // i.e. as soon as playback exists at all, not only once backgrounded -
            // per the YouTube-style always-on-notification decision.
            //
            // AM (NOTIFICATION_CREATION_STALE_SNAPSHOT_FIX) -->
            // No longer passes a title/subtitle/animeId/episodeId snapshot here - see
            // start()'s own doc comment for why that snapshot was unreliable at this
            // exact point in the ordering. The holder's own reactive state observer
            // (unconditional now) is the sole writer of that data, firing as soon as
            // syncHolderSessionState() has something real to give it.
            // <-- AM (NOTIFICATION_CREATION_STALE_SNAPSHOT_FIX)
            backgroundPlaybackService = binder.getService().also { svc ->
                svc.start(
                    isPlaying = !viewModel.playbackData.value.paused,
                    mediaSessionToken = mediaSession?.sessionToken,
                    onTogglePlayPause = {
                        if (viewModel.playbackData.value.paused) viewModel.unpause() else viewModel.pause()
                        backgroundPlaybackService?.updatePlaybackState(!viewModel.playbackData.value.paused)
                    },
                    onStopRequested = {
                        // Tapping "stop" now ends the whole session (there's no more
                        // "hide the notification but keep the player alive" state to
                        // fall back to, since the notification is meant to track
                        // playback existing at all) - matches onDestroy's isFinishing
                        // teardown path.
                        viewModel.pause()
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
        // AM (LIVE_INSTANCE_REOPEN_FIX) -->
        /**
         * Tracks whether any PlayerActivity instance currently exists - incremented in
         * onCreate(), decremented in onDestroy() unconditionally (regardless of
         * isFinishing/session-preservation, since this tracks whether an Activity
         * WINDOW exists right now, a different question from whether the underlying
         * playback session is preserved). Used by
         * PlayerBackgroundPlaybackService.buildReopenPendingIntent() to decide whether
         * the notification's reopen intent needs the full synthetic back stack (for a
         * genuinely fresh cold start with no parent) or can just bring an existing
         * singleTask instance to front - see that function's doc comment for why this
         * distinction turned out to matter a great deal.
         */
        @Volatile
        private var liveInstanceCount = 0

        val hasLiveInstance: Boolean get() = liveInstanceCount > 0
        // <-- AM (LIVE_INSTANCE_REOPEN_FIX)

        // AM (PIP_RECREATE_FIX_REMOVED) -->
        // pendingPipEntryAfterRecreate removed along with the recreate()-based
        // workaround in tryEnterPictureInPicture() - see that function's doc
        // comment.
        // <-- AM (PIP_RECREATE_FIX_REMOVED)

        fun newIntent(
            context: Context,
            animeId: Long?,
            episodeId: Long?,
            hostList: List<Hoster>? = null,
            hostIndex: Int? = null,
            vidIndex: Int? = null,
            // AM (CONTINUE_BUTTON_RESUME_FIX) -->
            // See AnimeScreen.openEpisode()'s forceResume param - only the
            // "Continue" button should pass true.
            forceResume: Boolean = false,
            // <-- AM (CONTINUE_BUTTON_RESUME_FIX)
        ): Intent {
            // SVC_RACE_DEBUG -->
            logcat {
                "SVC_RACE_DEBUG PlayerActivity.newIntent() called animeId=$animeId episodeId=$episodeId " +
                    "hasLiveInstance=$hasLiveInstance liveInstanceCount=$liveInstanceCount " +
                    "at=${android.os.SystemClock.elapsedRealtime()}"
            }
            // <-- SVC_RACE_DEBUG
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra("animeId", animeId)
                putExtra("episodeId", episodeId)
                hostIndex?.let { putExtra("hostIndex", it) }
                vidIndex?.let { putExtra("vidIndex", it) }
                hostList?.let { putExtra("hostList", it.serialize()) }
                // AM (CONTINUE_BUTTON_RESUME_FIX) -->
                putExtra("forceResume", forceResume)
                // <-- AM (CONTINUE_BUTTON_RESUME_FIX)
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
                // AM (NEW_TASK_ACTIVITY_CONTEXT_FIX_REVERTED) -->
                // Tried making this opt-out for Activity-context callers (MainActivity,
                // DeepLinkScreen), on the theory that NEW_TASK forced from an Activity
                // context was what caused startActivity() to spin up a duplicate Task
                // instead of redelivering via onNewIntent() to an already-live singleTask
                // instance. Confirmed via logcat that theory was wrong: the exact same
                // failure (fresh onCreate(), never onNewIntent(), immediately caught by
                // DUPLICATE_INSTANCE_SELF_TERMINATE) reproduced identically with NEW_TASK
                // removed. Reverted - NEW_TASK is what lets Android search other tasks for
                // an existing singleTask instance at all; without it, startActivity() from
                // an Activity context just pushes onto the CALLER's own current task
                // instead (consistent with isTaskRoot=false on every doomed duplicate in
                // both captures - something else is root of whatever task they land in).
                // Retried once more, opt-out again, after DISTINCT_TASK_AFFINITY_FIX and
                // BACK_FALLBACK_NEW_TASK_FIX resolved the task-affinity ambiguity this
                // first attempt ran into - same result, same failure, confirmed not
                // coincidental this time either. NEW_TASK is not the cause of the
                // duplicate-instance failure under any task-affinity configuration tried
                // so far. Reverted again - do not retry a third time without new evidence
                // pointing specifically at this flag.
                // <-- AM (NEW_TASK_ACTIVITY_CONTEXT_FIX_REVERTED)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // <-- AM (PIP_REOPEN_DUPLICATE_TASK_FIX)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // AM (SINGLE_TOP_RECREATE_RACE_FIX) -->
                // CLEAR_TOP alone, even on a singleTask Activity, does not guarantee
                // redelivery via onNewIntent() when the target is already the sole/
                // topmost Activity in its task - a well-documented Android quirk where
                // the system can instead finish and recreate the existing instance from
                // scratch to "clear down to and relaunch" the target, rather than
                // recognizing there was nothing above it to clear. That's exactly the
                // "opening from the notification while already in the app" case: this
                // reopen Intent targets PlayerActivity while it's already resumed and
                // alone at the top of its own task. Without SINGLE_TOP, that can tear
                // down and reconstruct this Activity/ViewModel while the Service-held
                // PlayerMediaHolder's player is still alive and adopted elsewhere,
                // racing a fresh MPVPlayer construction against the live one - the same
                // class of native mpv/JNI race documented at length in
                // BRANCH_NOTES.md's `pip-finish-based-wip` postmortem. SINGLE_TOP tells
                // the system explicitly: if this exact Activity is already resumed at
                // the top of its task, never finish/recreate it - just deliver the new
                // Intent via onNewIntent() on the live instance, exactly the behavior
                // this whole reopen path already assumes.
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // <-- AM (SINGLE_TOP_RECREATE_RACE_FIX)
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
        // AM (CONTINUE_BUTTON_RESUME_FIX) -->
        val forceResume = intent.extras?.getBoolean("forceResume") ?: false
        // <-- AM (CONTINUE_BUTTON_RESUME_FIX)
        if (animeId == -1L || episodeId == -1L) {
            finish()
            return
        }
        NotificationReceiver.dismissNotification(
            this,
            animeId.hashCode(),
            Notifications.ID_NEW_EPISODES,
        )

        // AM (LIVE_REDELIVERY_TRUST_FIX) -->
        // For any onNewIntent() call past the first, this Activity is already
        // alive with an already-adopted canonical player - no fresh MPVPlayer
        // construction is involved here (unlike a cold reopen, which is what the
        // native-crash-prone reinit pipeline below was actually built to guard
        // against). What redelivers here falls into three cases:
        //  1. Exactly what's already playing - the background-playback reopen
        //     notification's common case, whose payload is subject to a real, if
        //     narrow, staleness window relative to what's actually playing right
        //     now (REOPEN_TARGET_STALENESS_FIX narrowed it, coroutine dispatch
        //     timing means it can't be fully eliminated). Comparing against it via
        //     needsInit() was the root of every remaining intermittent failure on
        //     this specific path - so for this case, skip that comparison
        //     entirely and just trust the already-correct session.
        //  2. A genuinely different episode of the SAME anime, requested while
        //     this instance is already alive - e.g. tapping another entry in an
        //     episode list/queue while PIP is active. This is real, legitimate
        //     work that must actually happen, not a stale-payload false alarm -
        //     route it through the existing, already-safe in-app episode-switch
        //     pipeline (changeEpisode()) directly, since it operates on the
        //     already-adopted player with none of the construction/adoption
        //     races the full reinit pipeline exists to guard against.
        //  3. A different anime entirely - a genuine "load new content" request,
        //     not a redundant reload.
        //     AM (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX) -->
        //     Hot-swap caused flash/bleed; teardown instead
        if (hasProcessedInitialOnNewIntent) {
            lifecycleScope.launchNonCancellable {
                viewModel.playerReady.first { it }
                val current = viewModel.stateData.value
                when {
                    current.currentAnime?.id == animeId && current.currentEpisode?.id == episodeId -> {
                        mediaHolder?.consumePendingSkip()?.let { next -> viewModel.nextEpisode(next = next) }
                        withUIContext { setIntent(intent) }
                    }
                    current.currentAnime?.id == animeId -> {
                        viewModel.changeEpisode(episodeId)
                        withUIContext { setIntent(intent) }
                    }
                    else -> {
                        pendingFreshInstanceIntent = Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                            putExtra("animeId", animeId)
                            putExtra("episodeId", episodeId)
                            if (hostIndex != -1) putExtra("hostIndex", hostIndex)
                            if (vidIndex != -1) putExtra("vidIndex", vidIndex)
                            if (hostList.isNotBlank()) putExtra("hostList", hostList)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        withUIContext { finish() }
                    }
                }
            }
            return
        }
        // <-- AM (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX)
        hasProcessedInitialOnNewIntent = true
        // <-- AM (LIVE_REDELIVERY_TRUST_FIX)

        // AM (NEEDSINIT_BIND_RACE_FIX) -->
        // needsInit()'s "is this exact session already live in the Service" fallback
        // check reads mediaHolder, which is only populated once bindToService()
        // completes - asynchronously, from bindService()'s onServiceConnected
        // callback fired back in onCreate(). onCreate() then calls this exact
        // function directly and synchronously, before that bind has any chance to
        // resolve - so for every freshly-created instance (precisely the case that
        // check exists to catch), needsInit() was being asked to answer a question
        // it structurally couldn't yet know the answer to, and always fell back to
        // "needs init". That meant reopening onto an already-live, already-watched
        // session reloaded it from scratch and hit the "already watched -> start at
        // 0" rule instead of resuming - the playback position visibly resetting.
        // Waiting for playerReady here means needsInit() always runs with complete
        // information instead of racing ahead of it: for an already-bound instance
        // (the common case - reusing an existing, already-resumed Activity)
        // playerReady is already true, so this proceeds with no added delay; only a
        // genuinely fresh instance actually waits, for exactly as long as the bind
        // takes to resolve.
        lifecycleScope.launchNonCancellable {
            viewModel.playerReady.first { it }

            // AM (PIP_AVAILABILITY_FASTPATH_FIX) -->
            // Moved out of the reinit-only block below - unlike updateIsLoadingEpisode/
            // updateIsLoadingHosters (which correctly should only run for a genuine
            // reload, since they control loading spinners), this is a pure device/
            // preference capability check with no dependency on session-specific data.
            // It used to only run inside the "needs a real reinit" branch, which was
            // fine as long as reopening a still-live session always reused the exact
            // same Activity instance (stateData.isPipAvailable was already correctly
            // true from before, nothing to re-establish). Confirmed by direct
            // observation: once a reopen can land on a genuinely fresh instance again
            // (see isBackgroundPlayTransitionFinish's doc comment on PlayerActivity),
            // that fresh instance's own stateData starts at its default
            // (isPipAvailable = false), and the "already playing, skip reload" fast
            // path below never ran this at all - the PIP button silently disappearing
            // after exactly one background/reopen cycle.
            viewModel.updateHasPip(
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    playerPreferences.enablePip.get(),
            )
            // <-- AM (PIP_AVAILABILITY_FASTPATH_FIX)

            if (!viewModel.needsInit(animeId, episodeId)) {
                // Already playing this exact episode (e.g. reopened from the
                // background-playback notification) - avoid restarting the whole
                // file-open pipeline.
                // AM (MEDIA_SESSION_FALLBACK_CALLBACK) -->
                mediaHolder?.consumePendingSkip()?.let { next -> viewModel.nextEpisode(next = next) }
                // <-- AM (MEDIA_SESSION_FALLBACK_CALLBACK)
                withUIContext { setIntent(intent) }
                return@launchNonCancellable
            }

            // AM (REUSED_PLAYER_SYNC_FIX) -->
            // needsInit() above correctly said this fresh ViewModel's own local
            // bookkeeping (currentPlaylist etc.) needs populating - that's true
            // regardless of whether the underlying player object was reused. But
            // if it WAS reused (see PlayerViewModel.wasPlayerReusedFromLiveHolder /
            // SYNCHRONOUS_HOLDER_LOOKUP_FIX) from a holder that already reports
            // this exact target, the actual video content is already correct and
            // playing - falling through to the full reinit pipeline below would
            // still call loadfile() on it, a genuine, audible reload for zero
            // benefit. Sync only the DB-derived bookkeeping in that case, skipping
            // mpv entirely; fall through to the normal pipeline if that sync
            // fails for any reason (nothing lost - same behavior as before this
            // fix existed).
            val holderState = mediaHolder?.state?.value
            val canSyncWithoutReload = viewModel.wasPlayerReusedFromLiveHolder &&
                holderState?.animeId == animeId &&
                holderState.episodeId == episodeId
            if (canSyncWithoutReload && viewModel.syncSessionStateFromDb(animeId, episodeId)) {
                mediaHolder?.consumePendingSkip()?.let { next -> viewModel.nextEpisode(next = next) }
                withUIContext { setIntent(intent) }
                return@launchNonCancellable
            }
            // <-- AM (REUSED_PLAYER_SYNC_FIX)

            // AM (CONCURRENT_REINIT_RACE_FIX) -->
            // A genuine reinit is needed by the check above, but if a load is
            // ALREADY in flight (e.g. an in-app episode switch the user just
            // triggered, or an earlier reopen's own reinit that hasn't settled yet)
            // starting a second, concurrent one races two loads against the same
            // live mpv instance and ViewModel state - source of exactly the kind of
            // intermittent (sometimes fine, sometimes not) failure this reopen path
            // has been showing. Defer entirely to whichever load is already running
            // rather than piling a competing one on top of it; that in-flight load
            // will settle on its own, and the intent is still updated so a future
            // needsInit() check (once things are quiet) sees accurate state.
            if (viewModel.uiData.value.isLoadingEpisode || viewModel.uiData.value.isLoadingHosters) {
                withUIContext { setIntent(intent) }
                return@launchNonCancellable
            }
            // <-- AM (CONCURRENT_REINIT_RACE_FIX)

            viewModel.saveCurrentEpisodeWatchingProgress()
            // <-- AM (NEEDSINIT_BIND_RACE_FIX)

            // AM (REOPEN_LOAD_FAILURE_PRESERVE_SESSION_FIX) -->
            // Captured before init() runs: whether this reopen is reinitializing on top
            // of an already-valid, already-playing session, versus a genuinely fresh
            // open with nothing behind it yet. setInitialEpisodeError() unconditionally
            // finish()es on any load failure - correct for the fresh-open case (nothing
            // to fall back to), but destructive here: a reopen triggered while already
            // alive and playing (e.g. tapping the notification with the app still open)
            // can hit needsInit() == true and attempt a redundant reload of the same or
            // an adjacent episode, which can then genuinely fail (see the still-open
            // GetAnime/getAnimeById database race documented in BRANCH_NOTES.md) even
            // though the actual, already-working session underneath was completely
            // fine. Finishing the whole Activity over a failed redundant reload
            // destroys a good session for no reason - the failure should be reported,
            // not fatal, whenever there's something valid to fall back to.
            val hadValidSessionBeforeReinit = viewModel.stateData.value.currentAnime != null &&
                viewModel.stateData.value.currentEpisode != null
            // <-- AM (REOPEN_LOAD_FAILURE_PRESERVE_SESSION_FIX)

            viewModel.updateIsLoadingEpisode(true)
            viewModel.updateIsLoadingHosters(true)
            // In-memory state was lost (e.g. process killed while backgrounded) and this
            // is a genuine re-init, not a fresh open - resume from the position just
            // saved above instead of the normal "already watched -> start at 0" rule.
            viewModel.forceResumeFromLastPosition = true

            val initResult = viewModel.init(animeId, episodeId, hostList, hostIndex, vidIndex)
            if (!initResult.second.getOrDefault(false)) {
                val exception = initResult.second.exceptionOrNull() ?: IllegalStateException(
                    "Unknown error",
                )
                // AM (REOPEN_LOAD_FAILURE_PRESERVE_SESSION_FIX) -->
                if (hadValidSessionBeforeReinit) {
                    withUIContext {
                        showToast(exception.message ?: "")
                    }
                    logcat(LogPriority.ERROR, exception)
                } else {
                    withUIContext {
                        setInitialEpisodeError(exception)
                    }
                }
                // <-- AM (REOPEN_LOAD_FAILURE_PRESERVE_SESSION_FIX)
            }

            viewModel.updateIsLoadingHosters(false)

            // AM (CONTINUE_BUTTON_RESUME_FIX) -->
            // wasPlayerReusedFromLiveHolder is checked here, after init() has
            // finished (and REUSED_PLAYER_TARGET_MISMATCH_FIX has had its chance to
            // correct it to false if the reused player didn't actually match this
            // target) - the authoritative, final value for whether this reopen
            // actually landed on an already-live session rather than a fresh one.
            // Only force a resume when both are true: the user specifically hit
            // "Continue" (not a notification tap or a plain episode-list selection),
            // AND this reopen genuinely reattached to an existing, possibly-paused
            // live session (not a fresh init, which starts playing on its own).
            // See pendingForceResume's own doc comment for why this can't just call
            // viewModel.unpause() directly here - mediaHolder being non-null already
            // means onServiceConnected()'s reconcilePausedFromPlayer() has already
            // run, so it's safe to resume immediately; otherwise, defer until it
            // does.
            if (forceResume && viewModel.wasPlayerReusedFromLiveHolder) {
                if (mediaHolder != null) {
                    viewModel.unpause()
                } else {
                    pendingForceResume = true
                }
            }
            // <-- AM (CONTINUE_BUTTON_RESUME_FIX)

            // AM (MEDIA_SESSION_FALLBACK_CALLBACK) -->
            // Apply any skip queued via the fallback MediaSession callback while this
            // session was backgrounded with no ViewModel attached (see
            // PlayerMediaHolder.requestSkip) - init() above has now populated a real
            // currentPlaylist for this fresh instance, so nextEpisode() can resolve and
            // load the actually-desired episode instead of the one this reopen intent
            // happened to carry. Supersedes the hoster load below for the just-reopened
            // episode, which is fine - the user has already moved past it.
            val pendingSkip = mediaHolder?.consumePendingSkip()
            if (pendingSkip != null) {
                viewModel.nextEpisode(next = pendingSkip)
            } else {
                lifecycleScope.launch {
                    viewModel.loadHosters(
                        hosterList = initResult.first.hosterList ?: emptyList(),
                        hosterIndex = initResult.first.videoIndex.first,
                        videoIndex = initResult.first.videoIndex.second,
                    )
                }
            }
            // <-- AM (MEDIA_SESSION_FALLBACK_CALLBACK)

            withUIContext { setIntent(intent) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)

        // AM (LIVE_INSTANCE_REOPEN_FIX) -->
        liveInstanceCount++
        // <-- AM (LIVE_INSTANCE_REOPEN_FIX)

        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG Activity.onCreate() liveInstanceCount=$liveInstanceCount " +
                "activity=${System.identityHashCode(this)} isTaskRoot=$isTaskRoot " +
                "currentHolder=${PlayerMediaHolder.current?.let { System.identityHashCode(it) }} " +
                "at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG

        // AM (DUPLICATE_INSTANCE_SELF_TERMINATE) -->
        // As early as possible, before anything below touches viewModel (which would
        // construct its own, throwaway MPVPlayer/native mpv instance for an Activity
        // that's about to immediately finish() anyway) or binds to the Service. See
        // isDuplicateInstanceSelfTerminating's doc comment for why this exists at all.
        if (liveInstanceCount > 1) {
            isDuplicateInstanceSelfTerminating = true
            // SVC_RACE_DEBUG -->
            logcat {
                "SVC_RACE_DEBUG Activity.onCreate() SELF-TERMINATING as duplicate " +
                    "activity=${System.identityHashCode(this)} liveInstanceCount=$liveInstanceCount " +
                    "isTaskRoot=$isTaskRoot at=${android.os.SystemClock.elapsedRealtime()}"
            }
            // <-- SVC_RACE_DEBUG
            finish()
            return
        }
        // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)

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
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG Activity.onCreate() about to startService+bindService activity=${System.identityHashCode(this)} " +
                "at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
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
            finish()
        }

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    PlayerViewModel.Event.EnterPip -> {
                        // AM (PIP_REENTRY_CRASH_FIX) -->
                        // Confirmed via logcat: entering PIP a second time on a reused
                        // instance (see LIVE_INSTANCE_REOPEN_FIX) can hit a genuine Android
                        // framework race - "java.lang.RuntimeException: Performing pause of
                        // activity that is not resumed", thrown from inside
                        // ActivityThread.performPauseActivity() as part of the PIP-entry
                        // transition itself. That's the framework's own internal lifecycle
                        // bookkeeping getting out of sync with reality, not something our
                        // app's own uncaught-exception handler catches (it's thrown from a
                        // system-dispatched lifecycle callback, not our code) - it can bring
                        // the whole process down with no toast, no crash dialog, no visible
                        // PIP transition. isInPictureInPictureMode alone (an earlier, wrong
                        // guess at this fix) doesn't catch it, since the Activity can
                        // genuinely not be in PIP yet while also not being fully RESUMED -
                        // exactly the state this exception fires from. Checking the actual
                        // lifecycle state directly is the correct guard: only ever request
                        // PIP entry from an Activity the framework itself agrees is resumed.
                        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.RESUMED) {
                            // AM (PIP_RECREATE_FIX) -->
                            tryEnterPictureInPicture(createPipParams())
                            // <-- AM (PIP_RECREATE_FIX)
                        }
                        // <-- AM (PIP_REENTRY_CRASH_FIX)
                    }
                    // AM (PIP_BACK_AUTOENTER_MAIN_FIX) -->
                    // Back-triggered PIP entry only. A manual enterPictureInPictureMode()
                    // call here (as EnterPip above does) requires PlayerActivity to
                    // still be RESUMED at the moment it runs - but bringing
                    // MainActivity's task forward first knocks PlayerActivity out of
                    // that state before a synchronous follow-up call would run,
                    // which is why an earlier attempt at this (starting MainActivity
                    // then immediately calling enterPictureInPictureMode()) silently
                    // did nothing. Using the OS's own auto-enter-on-leave mechanism
                    // instead avoids that race entirely: register auto-enter (with
                    // forceAutoEnter, since a paused video wouldn't otherwise qualify)
                    // while still RESUMED, THEN start MainActivity - the framework
                    // transitions PlayerActivity into PIP as one coordinated part of
                    // that same hand-off, with MainActivity already the task in
                    // front, no flash and no manual PIP call needed.
                    PlayerViewModel.Event.EnterPipFromBack -> {
                        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.RESUMED) {
                            enterPipFromBack()
                        }
                    }
                    // <-- AM (PIP_BACK_AUTOENTER_MAIN_FIX)
                    is PlayerViewModel.Event.EpisodeTitle -> {
                        // No-op: used to toast "<anime> - <episode>" on change; not wanted.
                    }
                    PlayerViewModel.Event.Finish -> {
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

        // AM (MEDIASESSION_SINGLE_WRITER_FIX) -->
        // Three separate pushes used to live here: a reactive PlaybackState push, the
        // REOPEN_TARGET_STALENESS_FIX notification-text push, and the metadata/artwork
        // combine() flow below. All three duplicated logic PlayerMediaHolder already had
        // (its periodic timer, and its own artwork flow) - and since both
        // setMetadata()/setPlaybackState() fully replace the previous object, having both
        // the Activity and the holder push independently meant whichever fired last won,
        // regardless of which had the freshest data. That's what caused MediaSession/
        // notification data to appear to lag a full episode switch behind. PlayerMediaHolder
        // is now the sole writer for all of this, live Activity or not - see its own
        // MEDIASESSION_SINGLE_WRITER_FIX notes, and PlayerViewModel.syncHolderSessionState()
        // for how it stays fed with fresh titles the instant a switch happens. PlaybackState
        // updates now lag up to ~1s behind pause/resume (the holder's timer cadence) even in
        // the foreground - the same trade-off this codebase already accepted for background
        // playback (BACKGROUND_SEEKBAR_FIGHT_FIX), now applied uniformly.
        // <-- AM (MEDIASESSION_SINGLE_WRITER_FIX)

        // AM (BACKGROUND_SKIP_FIX) -->
        // Mirrors the already-correctly-sorted/filtered playlist (see
        // PlayerViewModel.setupEpisodeList()) into the holder as a flat ordered list
        // of episode ids - PlayerMediaHolder.skipToAdjacentEpisode() needs this to
        // resolve "next"/"previous" without re-deriving sort/filter logic itself,
        // which would risk landing on the wrong episode if that logic ever depends on
        // a user preference this observer can't see. Reusing the ViewModel's own,
        // already-correct computation here is the safe way to keep both in sync.
        viewModel.stateData
            .map { it.currentPlaylist.mapNotNull { episode -> episode.id } }
            .distinctUntilChanged()
            .onEach { episodeIds -> mediaHolder?.updatePlaylist(episodeIds) }
            .launchIn(lifecycleScope)
        // <-- AM (BACKGROUND_SKIP_FIX)

        // Metadata/artwork/duration in the media notification are now pushed solely by
        // PlayerMediaHolder (its periodic timer + its own artwork flow) - see the
        // MEDIASESSION_SINGLE_WRITER_FIX note above for why the equivalent flow that used
        // to live here was removed.

        setContent {
            TachiyomiTheme {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = {
                        // AM (BACK_PRESERVE_PAUSED_FIX) -->
                        // See PlayerScreen.kt's BackHandler for the matching change -
                        // this redundant check had the same !paused gate, so keeping
                        // this one out of sync would have reintroduced the same bug
                        // for any caller that invokes this lambda directly rather than
                        // through that BackHandler.
                        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.RESUMED &&
                            isPipSupportedAndEnabled &&
                            // <-- AM (BACK_PRESERVE_PAUSED_FIX)
                            playerPreferences.pipOnExit.get() && !viewModel.stateData.value.isCasting
                        ) {
                            // AM (PIP_BACK_AUTOENTER_MAIN_FIX) -->
                            // Same call as the BackHandler's Event.EnterPipFromBack -
                            // see enterPipFromBack()'s own doc comment for why.
                            enterPipFromBack()
                            // <-- AM (PIP_BACK_AUTOENTER_MAIN_FIX)
                        } else {
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
                                // SVC_RACE_DEBUG -->
                                logcat {
                                    "SVC_RACE_DEBUG onBack() BACK_FALLBACK_TO_ANIME firing isTaskRoot=true " +
                                        "activity=${System.identityHashCode(this@PlayerActivity)} " +
                                        "at=${android.os.SystemClock.elapsedRealtime()}"
                                }
                                // <-- SVC_RACE_DEBUG
                                viewModel.stateData.value.currentAnime?.id?.let { animeId ->
                                    startActivity(
                                        Intent(this, MainActivity::class.java)
                                            .setAction(Constants.SHORTCUT_ANIME)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                            // AM (BACK_FALLBACK_NEW_TASK_FIX) -->
                                            // Missing NEW_TASK here meant this Intent
                                            // got pushed onto PlayerActivity's OWN
                                            // current task instead of being routed to
                                            // MainActivity's actual task by MainActivity's
                                            // own affinity - confirmed via
                                            // `adb shell dumpsys activity activities`:
                                            // MainActivity kept landing inside a task
                                            // still carrying PlayerActivity's task
                                            // affinity (task affinity belongs to the
                                            // task, not whichever Activity currently
                                            // occupies it - pushing onto PlayerActivity's
                                            // task without NEW_TASK doesn't clear or
                                            // reassign it). That produced two
                                            // simultaneous tasks sharing the same
                                            // PlayerActivity-affinity tag whenever this
                                            // fallback fired - one correctly holding a
                                            // live PlayerActivity, one holding this
                                            // MainActivity relaunch instead - which is
                                            // exactly the ambiguity that was making
                                            // subsequent singleTask reopens land on the
                                            // wrong task and get killed by
                                            // DUPLICATE_INSTANCE_SELF_TERMINATE. Adding
                                            // NEW_TASK here routes this to MainActivity's
                                            // own, separate, already-existing task by
                                            // MainActivity's own affinity instead.
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            // <-- AM (BACK_FALLBACK_NEW_TASK_FIX)
                                            .putExtra(Constants.ANIME_EXTRA, animeId),
                                    )
                                }
                            } else {
                                // SVC_RACE_DEBUG -->
                                logcat {
                                    "SVC_RACE_DEBUG onBack() isTaskRoot=false, plain finish() " +
                                        "activity=${System.identityHashCode(this@PlayerActivity)} " +
                                        "at=${android.os.SystemClock.elapsedRealtime()}"
                                }
                                // <-- SVC_RACE_DEBUG
                            }
                            // <-- AM (BACK_FALLBACK_TO_ANIME)
                            finish()
                        }
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
        // AM (PIP_STALE_REOPEN_INTENT_FIX) -->
        // See the PRESERVE-session branch below and the final decrement at the
        // end of this function for how this is used.
        var isPreserveSessionDecremented = false
        // <-- AM (PIP_STALE_REOPEN_INTENT_FIX)
        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        // Safety net: onPictureInPictureModeChanged(false, ...) normally clears this, but
        // isn't guaranteed to fire if the activity is destroyed directly out of PIP.
        SecureActivityDelegate.setPipActive(false)
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)

        // AM (DUPLICATE_INSTANCE_SELF_TERMINATE) -->
        // A duplicate instance never touches viewModel/mediaHolder (see that flag's
        // doc comment), so none of the SERVICE_OWNED_PLAYER teardown below applies -
        // isFinishing is true here too (this instance called finish() on itself),
        // but running that block would try to release/stop the Service-owned
        // session the ORIGINAL, still-alive instance depends on. unbindService()
        // specifically would also throw outright: this instance's own bindService()
        // call never happened, since onCreate() returned before reaching it.
        if (!isDuplicateInstanceSelfTerminating) {
            // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)
            // AM (SERVICE_OWNED_PLAYER) -->
            // Step 3: onDestroy fires both when this session genuinely ends (isFinishing)
            // and when the OS destroys an invisible backgrounded Activity to reclaim
            // memory while playback is meant to continue - those need very different
            // teardown. Previously every line below ran unconditionally, which meant an
            // ordinary background eviction would kill the notification/foreground state
            // and, shortly after, playback itself - likely a real contributor to the
            // lifecycle-order-dependent bugs this refactor exists to fix.
            // AM (PIP_FINISH_NOT_MOVETASKTOBACK) -->
            // isBackgroundPlayTransitionFinish excluded here too, same reasoning as
            // isDuplicateInstanceSelfTerminating just above: isFinishing is true for
            // this finish() call, but it represents backgrounding (see that flag's
            // doc comment), not a genuine end - the underlying player/Service must
            // survive it exactly as they already do for the OS-reclaim case below.
            if (isFinishing && !isBackgroundPlayTransitionFinish) {
                // <-- AM (PIP_FINISH_NOT_MOVETASKTOBACK)
                // Genuine end of this playback session - tear everything down.
                // SVC_RACE_DEBUG -->
                logcat {
                    "SVC_RACE_DEBUG Activity.onDestroy() genuine-teardown-start activity=${System.identityHashCode(this)} " +
                        "holder=${mediaHolder?.let { System.identityHashCode(it) }} " +
                        "player=${System.identityHashCode(viewModel.player)} at=${android.os.SystemClock.elapsedRealtime()}"
                }
                // <-- SVC_RACE_DEBUG
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
                // SVC_RACE_DEBUG -->
                logcat {
                    "SVC_RACE_DEBUG Activity.onDestroy() about to stopService+unbindService " +
                        "activity=${System.identityHashCode(this)} at=${android.os.SystemClock.elapsedRealtime()}"
                }
                // <-- SVC_RACE_DEBUG
                stopService(PlayerBackgroundPlaybackService.newIntent(this))
                // AM (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX) -->
                // After teardown above
                pendingFreshInstanceIntent?.let { startActivity(it) }
                pendingFreshInstanceIntent = null
                // <-- AM (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX)
            } else {
                // This Activity instance is being destroyed while playback is meant to
                // continue - leave the Service, its player, its notification (if active), and
                // the MediaSession running. A future reattach adopts the same player via
                // PlayerMediaHolder.adopt() instead of these being torn down out from under it.
                // SVC_RACE_DEBUG -->
                logcat {
                    "SVC_RACE_DEBUG Activity.onDestroy() PRESERVE-session branch activity=${System.identityHashCode(this)} " +
                        "holder=${mediaHolder?.let { System.identityHashCode(it) }} " +
                        "isFinishing=$isFinishing isBackgroundPlayTransitionFinish=$isBackgroundPlayTransitionFinish " +
                        "at=${android.os.SystemClock.elapsedRealtime()}"
                }
                // <-- SVC_RACE_DEBUG

                // AM (PIP_STALE_REOPEN_INTENT_FIX) -->
                // Confirmed via logcat, matched against buildReopenPendingIntent()'s
                // own branch logging: updateEpisodeInfo() below rebuilds the
                // notification, and that used to run while liveInstanceCount hadn't
                // been decremented yet - so buildReopenPendingIntent() kept taking
                // the REUSE-EXISTING branch and baking a PendingIntent aimed
                // directly at this about-to-be-destroyed Activity into the
                // notification, instead of the MainActivity-first path
                // (SYNTHETIC-BACKSTACK) a cold start actually uses. Decrementing
                // here, only for this branch, fixes that. An earlier attempt moved
                // the single shared decrement for ALL THREE onDestroy() paths to
                // this same early position - simpler, but wrong: the other two
                // paths (genuine teardown, duplicate-self-terminate) apparently
                // depend on something later in their own teardown still seeing the
                // pre-decrement count, and moving it broke cold-start Recents-PIP
                // in a way this scoped version doesn't. isPreserveSessionDecremented
                // below skips the original unconditional decrement at the end of
                // this function for this one path only, since it's already been
                // done here - the other two paths still hit that original call,
                // completely unchanged from stock.
                liveInstanceCount--
                isPreserveSessionDecremented = true
                // SVC_RACE_DEBUG -->
                logcat {
                    "SVC_RACE_DEBUG Activity.onDestroy() liveInstanceCount-- -> $liveInstanceCount " +
                        "activity=${System.identityHashCode(this)} isDuplicateInstanceSelfTerminating=$isDuplicateInstanceSelfTerminating " +
                        "at=${android.os.SystemClock.elapsedRealtime()}"
                }
                // <-- SVC_RACE_DEBUG
                // <-- AM (PIP_STALE_REOPEN_INTENT_FIX)

                // AM (MEDIA_SESSION_FALLBACK_CALLBACK) -->
                // Hand the MediaSession's callback back to the Service-owned fallback
                // before this instance's ViewModel is cleared - the mirror image of
                // setupMediaSessionCallback()'s redirect-to-the-reattaching-instance
                // behavior. Without this, the callback stays pointed at this instance's
                // (about to be dead) ViewModel until some future reattach overwrites it.
                mediaHolder?.restoreFallbackCallback()
                // <-- AM (MEDIA_SESSION_FALLBACK_CALLBACK)

                // AM (BACKGROUND_HANDOFF_NOTIFY_FIX) -->
                // The periodic timer in PlayerMediaHolder (BACKGROUND_SEEKBAR_TICK_FIX)
                // pushes correct PlaybackState/MediaMetadata to the MediaSession the
                // moment hasLiveInstance goes false, even with nothing having actually
                // changed yet - but the lock screen's rich media widget was still
                // showing blank until the next thing that happened to trigger an
                // actual NotificationManagerCompat.notify() call (a skip, since that's
                // gated on a title change). Explicitly forcing one here, using this
                // still-alive instance's own last-known-correct values, gives the lock
                // screen a fresh post to render from at the exact moment control hands
                // off - not waiting for something else to eventually cause one.
                viewModel.stateData.value.let { data ->
                    val anime = data.currentAnime
                    val episode = data.currentEpisode
                    if (anime != null && episode != null) {
                        backgroundPlaybackService?.updateEpisodeInfo(
                            title = anime.title,
                            subtitle = episode.name,
                            animeId = anime.id,
                            episodeId = episode.id,
                        )
                    }
                }
                // <-- AM (BACKGROUND_HANDOFF_NOTIFY_FIX)
            }

            unbindService(mediaHolderConnection)
            // SVC_RACE_DEBUG -->
            logcat {
                "SVC_RACE_DEBUG Activity.onDestroy() unbindService() returned activity=${System.identityHashCode(this)} " +
                    "at=${android.os.SystemClock.elapsedRealtime()}"
            }
            // <-- SVC_RACE_DEBUG
            mediaHolder = null
            // <-- AM (SERVICE_OWNED_PLAYER)
        }
        // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)

        if (noisyReceiver.initialized) {
            unregisterReceiver(noisyReceiver)
            noisyReceiver.initialized = false
        }

        if (screenStateReceiver.initialized) {
            unregisterReceiver(screenStateReceiver)
            screenStateReceiver.initialized = false
        }

        // AM (PIP_RECEIVER_STALE_ACTIVITY_FIX) -->
        // Safety net: normally unregistered as part of onPictureInPictureModeChanged's
        // own cleanup, but that's not guaranteed to have already run by the time
        // onDestroy() gets here - matters more now that finish() (not just
        // moveTaskToBack()) can genuinely destroy this Activity from within an active
        // PIP session. See onReceive()'s isFinishing/isDestroyed guard above for the
        // other half of this fix.
        pipReceiver?.let {
            unregisterReceiver(it)
            pipReceiver = null
        }
        // <-- AM (PIP_RECEIVER_STALE_ACTIVITY_FIX)

        // AM (LIVE_INSTANCE_REOPEN_FIX) -->
        // Unconditional, regardless of isFinishing - this tracks whether an Activity
        // window exists right now, not whether the underlying session is preserved.
        // AM (PIP_STALE_REOPEN_INTENT_FIX) -->
        // Skipped when the PRESERVE-session branch above already did this early
        // (see its own comment for why) - everything else (genuine teardown,
        // duplicate-self-terminate) still hits this exact original, unmoved call.
        if (!isPreserveSessionDecremented) {
            liveInstanceCount--
        }
        // <-- AM (PIP_STALE_REOPEN_INTENT_FIX)
        // <-- AM (LIVE_INSTANCE_REOPEN_FIX)
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG Activity.onDestroy() liveInstanceCount-- -> $liveInstanceCount " +
                "activity=${System.identityHashCode(this)} isDuplicateInstanceSelfTerminating=$isDuplicateInstanceSelfTerminating " +
                "at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG

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

    // AM (ENTER_BACKGROUND_CONSOLIDATION) -->
    /**
     * Single decision point for "this Activity is no longer visible - should
     * playback keep running via the always-on notification, or pause?" This exact
     * check (playerPreferences.backgroundPlayback + current paused state, then
     * either startBackgroundPlayback()+clear the PIP exemption, or pause+clear it)
     * used to be duplicated inline across three call sites - onPause(), the PIP
     * "Background Play" action, and the screen-off sub-case of PIP being dismissed -
     * with the PIP action's copy subtly different (it always started background
     * playback, ignoring paused state) from the other two, undocumented as
     * intentional. That divergence meant a fix to one call site silently didn't
     * apply to the others - now there's exactly one place this decision is made, and
     * the one genuine difference between call sites is an explicit, documented
     * parameter instead of three separately-hand-written copies of the same branch.
     * Pure deduplication - doesn't touch the genuine-swipe-away teardown branch,
     * which is a different decision (end the session, not "should it background").
     *
     * @param force Skip the "only if currently playing" check. The headphones
     * "Background Play" button is an explicit user request to keep this session
     * alive in the background even if paused right now (so a later notification tap
     * resumes it); passive backgrounding (leaving the app, screen off during PIP)
     * shouldn't spontaneously start playing an already-paused video.
     */
    private fun enterBackground(force: Boolean = false) {
        if (playerPreferences.backgroundPlayback.get() && (force || !viewModel.playbackData.value.paused)) {
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
    }
    // <-- AM (ENTER_BACKGROUND_CONSOLIDATION)

    override fun onPause() {
        // AM (DUPLICATE_INSTANCE_SELF_TERMINATE) -->
        // Checked before the very first viewModel access below - see that flag's
        // doc comment on why this instance must never touch viewModel at all.
        if (isDuplicateInstanceSelfTerminating) {
            super.onPause()
            return
        }
        // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)

        viewModel.saveCurrentEpisodeWatchingProgress()

        // AM (NO_IDLE_WINDOW_FIX) -->
        // isPipEntryPending used to also gate this whole block, on the theory that
        // onPause() could race the in-flight PIP transaction. That theory turned out
        // to be wrong: confirmed via logcat, the actual framework exception
        // ("Performing pause of activity that is not resumed") is thrown from inside
        // ActivityThread.performPauseActivity() itself, before this onPause() Kotlin
        // method is ever called - so nothing this method does could have been racing
        // it either way. Meanwhile, gating enterBackground() behind
        // isPipEntryPending was actively harmful: enterBackground() only sets flags
        // and talks to the Service (no Activity-transition APIs), so skipping it
        // left a real gap - right at the moment a PIP-entry attempt might get
        // cancelled by the OS - where nothing promptly told Samsung's own power/task
        // management "this app is still legitimately active." isInPictureInPictureMode
        // is the only check that's actually meaningful here: PIP being genuinely,
        // confirmedly active is the one case where re-establishing background
        // exemption is redundant, not just pending-and-uncertain.
        if (isInPictureInPictureMode) {
            // <-- AM (NO_IDLE_WINDOW_FIX)
            super.onPause()
            return
        }

        viewModel.setPlayerExiting(true)
        // AM (PIP_FINISH_NOT_MOVETASKTOBACK) -->
        // Defensive: isInPictureInPictureMode might already read false by the time
        // this runs (finish() itself is what causes PIP to exit, and the exact
        // ordering of that against onPause() isn't something to rely on) - without
        // this exclusion, that would fall through to isFinishing below and hard-stop
        // playback via mpvCommand("stop") for a transition meant to preserve it. The
        // headphones handler already explicitly calls enterBackground() itself
        // before finish(), so there's nothing this branch needs to do for that case
        // either way.
        if (isFinishing && !isBackgroundPlayTransitionFinish) {
            // <-- AM (PIP_FINISH_NOT_MOVETASKTOBACK)
            viewModel.deletePendingEpisodes()
            viewModel.mpvCommand("stop")
        } else {
            // AM (ENTER_BACKGROUND_CONSOLIDATION) -->
            enterBackground()
            // <-- AM (ENTER_BACKGROUND_CONSOLIDATION)
        }

        super.onPause()
    }

    override fun onStop() {
        // AM (DUPLICATE_INSTANCE_SELF_TERMINATE) -->
        if (isDuplicateInstanceSelfTerminating) {
            super.onStop()
            return
        }
        // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)

        if (isInPictureInPictureMode && powerManager.isInteractive) {
            viewModel.deletePendingEpisodes()
        }

        super.onStop()
    }

    override fun onUserLeaveHint() {
        // AM (AUTO_PIP_LOOP_FIX) -->
        // moveTaskToBack() (called by the swipe-dismiss-PIP path's own background-
        // playback branch, right after isIntentionalBackgroundTransition is set
        // true) is itself one of the standard triggers for onUserLeaveHint() -
        // same as pressing Home. This check used to run regardless of *why* the
        // app was leaving, so it was immediately re-entering PIP right on top of
        // that explicit background-play transition, completely undoing it.
        //
        // Checking isIntentionalBackgroundTransition alone (an earlier attempt at
        // this exact fix) wasn't enough: it's a per-Activity-instance field, but
        // the reopen loop this causes involves a brand new PlayerActivity instance
        // each cycle (confirmed via logcat - a fresh Android Task ID every time) -
        // a fresh instance's own copy of that field starts false regardless of the
        // original session's intent, so it protected only the very first
        // moveTaskToBack() call and nothing after. isBackgroundPlaybackActive is
        // the same static, cross-instance flag createPipParams() already relies on
        // for the same reason - it stays true across every instance boundary in
        // this exact window, until the deferred stopBackgroundPlayback() posted in
        // onStart() (see RESUME_LOCK_RACE_FIX) actually runs.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.RESUMED &&
            !isIntentionalBackgroundTransition &&
            !SecureActivityDelegate.isBackgroundPlaybackActive &&
            isPipSupportedAndEnabled && !viewModel.playbackData.value.paused && playerPreferences.pipOnExit.get()
        ) {
            // AM (PIP_RECREATE_FIX) -->
            tryEnterPictureInPicture(null)
            // <-- AM (PIP_RECREATE_FIX)
        }
        // <-- AM (AUTO_PIP_LOOP_FIX)
        super.onUserLeaveHint()
    }

    override fun onStart() {
        super.onStart()
        // AM (DUPLICATE_INSTANCE_SELF_TERMINATE) -->
        // onStart() fires before onResume() in the finish()-during-onCreate()
        // sequence - createPipParams() below (via setPictureInPictureParams) heavily
        // accesses viewModel, so this needs the same guard as every other lifecycle
        // callback here.
        if (isDuplicateInstanceSelfTerminating) {
            return
        }
        // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)
        // AM (RESUME_LOCK_RACE_FIX) -->
        // Deferred via post(), not called inline. onStart() always fully completes
        // before onResume() runs, and SecureActivityDelegateImpl's onResume observer
        // (the app-lock check) fires only after THIS Activity's own onResume() has
        // fully returned too - AppCompatActivity's lifecycle dispatch is driven by a
        // ReportFragment that resumes after its host Activity, not nested inside it.
        // Clearing the exemption here inline meant it was already gone by the time
        // that check ran, on every single resume from genuine background playback -
        // not a notification/back-stack-specific issue, just this ordering. Posting
        // defers the clear until after the current lifecycle dispatch (onStart,
        // onResume, and the observer's onResume) has settled, mirroring
        // SecureActivityDelegate.deferredApplicationStoppedCheck()'s identical fix for
        // the same class of problem on the other side of this same exemption.
        Handler(Looper.getMainLooper()).post {
            // Foreground again - the app-lock "user is away while playback continues"
            // exemption no longer applies. The notification itself is untouched here -
            // it stays up regardless of foreground/background state since step 4b.
            stopBackgroundPlayback()
            setPictureInPictureParams(createPipParams())
        }
        // <-- AM (RESUME_LOCK_RACE_FIX)
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
        // AM (DUPLICATE_INSTANCE_SELF_TERMINATE) -->
        // Android still calls onResume() even for an Activity that finish()ed during
        // onCreate() - checked before the first viewModel access below, same as
        // every other lifecycle callback this flag guards.
        if (isDuplicateInstanceSelfTerminating) {
            super.onResume()
            return
        }
        // <-- AM (DUPLICATE_INSTANCE_SELF_TERMINATE)

        // Restructured from two early-returns into if/else so both paths fall
        // through consistently (no longer gated on a pending-PIP check here - see
        // PIP_RECREATE_FIX_REMOVED below).
        if (!viewModel.isPlayerExiting()) {
            super.onResume()
        } else {
            viewModel.setPlayerExiting(false)
            super.onResume()

            viewModel.setVolumeTo(
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also {
                    if (it < viewModel.stateData.value.maxVolume) viewModel.changeMPVVolumeTo(100)
                },
            )
        }

        // AM (PIP_RECREATE_FIX_REMOVED) -->
        // pendingPipEntryAfterRecreate consumption removed along with the
        // recreate()-based workaround - see tryEnterPictureInPicture()'s doc
        // comment.
        // <-- AM (PIP_RECREATE_FIX_REMOVED)
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

    /**
     * Wraps every PIP-entry attempt.
     *
     * [params] null means "use whatever PictureInPictureParams are already
     * registered" (matching onUserLeaveHint()'s original plain
     * enterPictureInPictureMode() call); non-null explicitly supplies params
     * (matching the other two call sites, which always passed createPipParams()
     * directly).
     */
    // AM (PIP_RECREATE_FIX_REMOVED) -->
    // recreate() previously ran here on every second-or-later PIP-entry attempt on
    // this Activity instance, to dodge a framework race confirmed only on a reused
    // instance's repeat PIP entry. PIP_REENTRY_CRASH_FIX later added a direct
    // lifecycle.currentState == RESUMED guard at every real call site below,
    // targeting that exact same "second entry on a reused instance" scenario -
    // recreate() had become a second, more disruptive workaround for a problem the
    // RESUMED check may already fully cover. recreate() tearing down and rebuilding
    // this Activity's entire UI/window (including the Surface mpv renders into) on
    // every repeat PIP entry was the source of an audible pause/resume blip on PIP
    // open/close, and of exactly the kind of timing-sensitive re-run of onCreate()/
    // bindService()/onServiceConnected() that made repeat notification-reopen-then-
    // PIP races intermittent rather than reliable. All three real call sites
    // already gate on isInPictureInPictureMode and RESUMED before calling this, so
    // entering directly here, every time, is consistent with what already runs on
    // a first attempt - if the original crash resurfaces on a genuine reused-
    // instance repeat entry, that means the RESUMED guard alone isn't sufficient
    // and recreate() (or some other fresh mitigation) needs reinstating.
    // <-- AM (PIP_RECREATE_FIX_REMOVED)
    private fun tryEnterPictureInPicture(params: PictureInPictureParams?) {
        isPipEntryPending = true
        // AM (PIP_LOCK_RACE_FIX) -->
        // Confirmed via a full, unfiltered logcat capture with exact timestamps:
        // entering PIP resumes MainActivity (the synthetic back-stack entry ahead
        // of PlayerActivity - see setAppLock()'s own doc comment) as part of the
        // framework's own PIP-entry transition, and that resume's app-lock check
        // fired a mere 26ms later - 411ms BEFORE this Activity's own
        // onPictureInPictureModeChanged(true, ...) callback (which is what used
        // to be the only place setPipActive(true) got called) ever ran. The
        // isBackgroundPlaybackActive guard in setAppLock() was correct in
        // principle; it was just checking a flag this code hadn't set yet,
        // consistently and by a wide margin, not as a rare race. Setting it here
        // - synchronously, before enterPictureInPictureMode() is even called, so
        // there's no window for the OS to resume MainActivity ahead of it -
        // closes that gap instead of trying to win a callback race that was
        // never close enough to win. Safe to also still be set again later in
        // onPictureInPictureModeChanged() - setPipActive() is idempotent for the
        // same value.
        // <-- AM (PIP_LOCK_RACE_FIX)
        SecureActivityDelegate.setPipActive(true)
        if (params != null) {
            enterPictureInPictureMode(params)
        } else {
            enterPictureInPictureMode()
        }
    }

    // AM (PIP_BACK_AUTOENTER_MAIN_FIX) -->
    // Shared by every back-triggered PIP entry point (the BackHandler in
    // PlayerScreen.kt and the in-player UI back button, which calls the onBack
    // lambda below directly, bypassing BackHandler entirely) so they can't drift
    // out of sync with each other - see BACK_PRESERVE_PAUSED_FIX's own comment
    // for why that already happened once with the old duplicated-condition
    // approach. A manual enterPictureInPictureMode() call here would require
    // PlayerActivity to still be RESUMED at the moment it runs, but starting
    // MainActivity's task forward first knocks PlayerActivity out of that state
    // before a synchronous follow-up call would run. Registering auto-enter
    // (forceAutoEnter, since a paused video wouldn't otherwise qualify) while
    // still RESUMED, then starting MainActivity, lets the framework transition
    // PlayerActivity into PIP as one coordinated part of that same hand-off -
    // MainActivity already the task in front, no flash, no manual PIP call.
    private fun enterPipFromBack() {
        setPictureInPictureParams(createPipParams(forceAutoEnter = true))
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }
    // <-- AM (PIP_BACK_AUTOENTER_MAIN_FIX)

    fun createPipParams(forceDisableAutoEnter: Boolean = false, forceAutoEnter: Boolean = false): PictureInPictureParams {
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
            // AM (AUTO_PIP_LOOP_FIX) -->
            // forceDisableAutoEnter covers exactly one caller: the headphones
            // "Background Play" action, right before its own finish().
            // SecureActivityDelegate.isBackgroundPlaybackActive covers the much
            // more general case that turned out to actually be causing the loop:
            // a fresh PlayerActivity instance reopened from the notification calls
            // setPictureInPictureParams(createPipParams()) immediately in its own
            // onStart(), synchronously - before the deferred stopBackgroundPlayback()
            // posted there (see RESUME_LOCK_RACE_FIX) has run. Without this check,
            // that immediate call would re-register auto-enter=true on this brand
            // new instance, and the OS would auto-re-enter PIP on it the moment
            // anything (even a transient shade/overlay interaction from tapping the
            // notification itself) looks like "leaving" - which is exactly what the
            // logcat capture showed happening, repeatedly, with a fresh Android Task
            // each cycle. isBackgroundPlaybackActive is still true at that exact
            // moment specifically because the deferred clear hasn't run yet - a
            // fresh instance reopening from a genuine background-play session
            // correctly suppresses auto-enter until that clear actually happens.
            val shouldAutoEnter = forceAutoEnter || (
                !forceDisableAutoEnter &&
                    !SecureActivityDelegate.isBackgroundPlaybackActive &&
                    !viewModel.playbackData.value.paused &&
                    autoEnter
                )
            // AM (PIP_BACK_AUTOENTER_MAIN_FIX) -->
            // forceAutoEnter covers exactly one caller: back-triggered PIP entry
            // (Event.EnterPipFromBack below). That trigger is meant to enter PIP
            // unconditionally, same as the old manual tryEnterPictureInPicture() call
            // it replaces (see BACK_PRESERVE_PAUSED_FIX - back on a paused video
            // still enters PIP), so it bypasses the paused/background-playback-active
            // exclusions that otherwise gate the OS's automatic leave-triggered entry.
            // <-- AM (PIP_BACK_AUTOENTER_MAIN_FIX)
            builder.setAutoEnterEnabled(shouldAutoEnter)
            builder.setSeamlessResizeEnabled(shouldAutoEnter)
            // <-- AM (AUTO_PIP_LOOP_FIX)
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
            // AM (PIP_ENTRY_CANCELLED_FIX) -->
            isPipEntryPending = false
            // <-- AM (PIP_ENTRY_CANCELLED_FIX)
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
                // AM (PIP_ENTRY_CANCELLED_FIX) -->
                // If a PIP entry attempt was just made (isPipEntryPending, set right
                // before each enterPictureInPictureMode() call) and we land here instead
                // of a confirmed entry, the OS cancelled that attempt mid-flight - this is
                // NOT the user dismissing an already-established PIP window, even though
                // it lands in the exact same lifecycle.currentState == CREATED branch.
                // Confirmed via logcat: re-entering PIP on a reused instance (see
                // LIVE_INSTANCE_REOPEN_FIX) can get cancelled by the system
                // (clearWaitForEnteringPinnedMode reason=exit_pip), and treating that
                // identically to a genuine swipe-away was tearing down sessions the user
                // never asked to end. Fall back to background playback instead, same as
                // a normal pause - the safe, non-destructive response to "PIP didn't work
                // out," regardless of why the OS declined it.
                if (isPipEntryPending) {
                    isPipEntryPending = false
                    enterBackground()
                    // <-- AM (PIP_ENTRY_CANCELLED_FIX)
                } else if (powerManager.isInteractive) {
                    // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
                    // The system also fires this when the screen turns off during PIP
                    // (tearing down the PIP surface for the lock screen), not just on a
                    // genuine user swipe-away. Only treat it as dismissal when the screen
                    // is actually on - otherwise fall back to background playback the same
                    // way onPause() does, so the session survives and the app lock stays
                    // exempt.
                    // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
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
                                // AM (PIP_DISMISS_RELEASE_CRASH_FIX) -->
                                // Confirmed via a full, unfiltered logcat capture: this
                                // branch used to unconditionally call
                                // viewModel.player.release() (a full, permanent native
                                // mpv.close()) - regardless of whether background
                                // playback should be continuing. That directly
                                // contradicts the entire Service-owned-player
                                // architecture: PlayerMediaHolder/the Service holds
                                // this SAME MPVPlayer instance and depends on it
                                // staying alive for background playback to work at
                                // all. If background playback had already started (or
                                // was about to, via the SAME transition this branch is
                                // reacting to), releasing the shared player out from
                                // under it left the Service holding a closed, invalid
                                // native handle - confirmed by the log: "libmpv is not
                                // initialized" and an EGL context leak warning
                                // appeared within milliseconds of this firing, then
                                // the whole process died. This wasn't a rare edge case
                                // - it directly reproduced the reported "app crashed
                                // when I removed the PIP".
                                //
                                // Mirrors enterBackground()'s own, already-correct
                                // decision instead of ignoring it: if background
                                // playback should continue, do exactly that (and stay
                                // alive, matching PIP_BACKGROUND_PLAY_MODE_FIX's own
                                // reasoning for why finish() isn't the right response
                                // to "stop showing video" when the player itself
                                // should keep going) - only genuinely release and
                                // finish when background playback is actually
                                // disabled, which is the one case where nothing should
                                // keep this session alive.
                                // <-- AM (PIP_DISMISS_RELEASE_CRASH_FIX)
                                if (playerPreferences.backgroundPlayback.get()) {
                                    // AM (PIP_DISMISS_PAUSE_FIX) -->
                                    // Explicitly pause before backgrounding here,
                                    // unlike the headphones "Background Play" button's
                                    // own enterBackground(force = true) call, which
                                    // deliberately keeps playing - that's a distinct,
                                    // explicit "keep listening" action, while
                                    // dismissing the PIP window itself is more
                                    // ambiguous and closer to "I'm done with this
                                    // for now." Still starts background playback
                                    // (via enterBackground's own force=true) so the
                                    // session/notification survives and can be
                                    // resumed - just not actively playing audio the
                                    // user didn't ask to keep hearing.
                                    // <-- AM (PIP_DISMISS_PAUSE_FIX)
                                    viewModel.pause()
                                    isIntentionalBackgroundTransition = true
                                    enterBackground(force = true)
                                    isBackgroundPlayTransitionFinish = true
                                    setPictureInPictureParams(createPipParams(forceDisableAutoEnter = true))
                                    finish()
                                } else {
                                    SecureActivityDelegate.setPipActive(false)
                                    viewModel.player.release()
                                    finish()
                                }
                            }
                        },
                        100,
                    )
                    // <-- AM (PIP_REOPEN_RACE_FIX)
                } else {
                    // AM (ENTER_BACKGROUND_CONSOLIDATION) -->
                    enterBackground()
                    // <-- AM (ENTER_BACKGROUND_CONSOLIDATION)
                }
            } else {
                // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
                SecureActivityDelegate.setPipActive(false)
                // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
                stopBackgroundPlayback()
                setPictureInPictureParams(createPipParams())
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
                    // AM (PIP_RECEIVER_STALE_ACTIVITY_FIX) -->
                    // Confirmed via logcat: a real, caught crash -
                    // "IllegalStateException: setPictureInPictureParams: Can't find
                    // activity for token=..." - thrown when this receiver's trailing
                    // setPictureInPictureParams() call below runs against an Activity
                    // the system has already destroyed. Previously impossible: the
                    // headphones "Background Play" action used to moveTaskToBack()
                    // rather than finish(), so this Activity never actually got
                    // destroyed while still in PIP. Now that it can genuinely finish()
                    // itself (see isBackgroundPlayTransitionFinish), a late-arriving
                    // broadcast - another PIP button tap, or one already queued before
                    // this receiver got a chance to unregister - can reach onReceive()
                    // after destruction. isFinishing/isDestroyed are the correct,
                    // direct signals to check here, not receiver
                    // registration/unregistration timing.
                    if (isFinishing || isDestroyed) return
                    // <-- AM (PIP_RECEIVER_STALE_ACTIVITY_FIX)
                    when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
                        PIP_PAUSE -> viewModel.pause()
                        PIP_PLAY -> viewModel.unpause()
                        PIP_NEXT -> viewModel.nextEpisode(next = true)
                        PIP_PREVIOUS -> viewModel.nextEpisode(next = false)
                        PIP_SKIP -> viewModel.seekBy(10)
                        PIP_BACKGROUND_PLAY -> {
                            // Manually trigger the background-audio path (onPause() skips
                            // it while still in PIP).
                            // AM (ENTER_BACKGROUND_CONSOLIDATION) -->
                            enterBackground(force = true)
                            // <-- AM (ENTER_BACKGROUND_CONSOLIDATION)
                            isIntentionalBackgroundTransition = true

                            // AM (PIP_FINISH_NOT_MOVETASKTOBACK) -->
                            // finish() to exit PIP - this session's session-preservation
                            // architecture (see isBackgroundPlayTransitionFinish's doc
                            // comment) keeps the underlying player/Service alive across
                            // it, so audio playback continues uninterrupted. Confirmed:
                            // moveTaskToBack() while a task is still actively pinned in
                            // PIP corrupts the task's pinned-state bookkeeping at the OS
                            // level - the task later silently self-destructs the next
                            // time it genuinely attempts to auto-re-enter PIP, with no
                            // client-side finish()/finishAndRemoveTask() call ever
                            // firing (confirmed directly via ActivityTaskManagerService's
                            // own ActivityRecord state and mp_option_change_callback
                            // logging - see this branch's git history for the full
                            // investigation). finish() sidesteps that class of bug
                            // entirely rather than working around it. force=true: an
                            // explicit user action, so this should work even while
                            // paused - see enterBackground()'s doc comment.
                            isBackgroundPlayTransitionFinish = true
                            // <-- AM (PIP_FINISH_NOT_MOVETASKTOBACK)
                            // AM (AUTO_PIP_LOOP_FIX) -->
                            // Disable auto-enter-PIP before finishing, and return
                            // immediately after - skipping the unconditional
                            // setPictureInPictureParams() call below entirely for this
                            // action.
                            setPictureInPictureParams(createPipParams(forceDisableAutoEnter = true))
                            // AM (PIP_MOVETASKTOBACK_RACE_FIX) -->
                            // Deferred via post(), not called inline: the system is
                            // still processing this button tap's own broadcast dispatch
                            // at the moment this runs, and asking it to also finish() the
                            // Activity inline risks colliding with that in-flight work.
                            // Posting it instead lets the current dispatch settle first.
                            Handler(Looper.getMainLooper()).post {
                                finish()
                            }
                            // <-- AM (PIP_MOVETASKTOBACK_RACE_FIX)
                            // <-- AM (AUTO_PIP_LOOP_FIX)
                            return
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
            KeyEvent.KEYCODE_MEDIA_STOP -> finishAndRemoveTask()

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
                // AM (MEDIASESSION_STOP_SAFETY_FIX) -->
                // Used to be session.isActive = false followed by manually calling
                // this@PlayerActivity.onStop() directly - invoking an Activity's own
                // lifecycle method outside the real system dispatch is never actually
                // safe, since it runs our lifecycle-tracking code at a moment that
                // doesn't correspond to the Activity's real state, exactly the kind of
                // thing that could corrupt lifecycle bookkeeping the framework itself
                // relies on. An external "stop" transport command (Bluetooth/AVRCP, a
                // system media widget, etc.) reaching this callback is meant to behave
                // like the notification's own Stop button - reusing that exact same,
                // already-correct pattern (pause, stop the Service, then a real
                // finish() dispatched through the normal Android API) instead of
                // inventing an unsafe shortcut.
                viewModel.pause()
                stopService(PlayerBackgroundPlaybackService.newIntent(this@PlayerActivity))
                finish()
                // <-- AM (MEDIASESSION_STOP_SAFETY_FIX)
            }
        }

        val session = holder.ensureMediaSession(this, callback).apply {
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
