package eu.kanade.tachiyomi.ui.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
// AM (BACKGROUND_SKIP_FIX) -->
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
// <-- AM (BACKGROUND_SKIP_FIX)
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR

/**
 * Foreground service that keeps playback alive when [PlayerActivity] is backgrounded
 * outside PIP. Doesn't own the mpv instance - just holds the process foreground and
 * relays play/pause/stop back to whoever bound to it.
 */
class PlayerBackgroundPlaybackService : Service() {

    private val binder = LocalBinder()

    // AM (SERVICE_OWNED_PLAYER) -->
    // Lazy: only constructed once something actually binds and asks for it, so a
    // stray start() of this Service never spins up a second live mpv instance.
    // Step 1: constructed and reachable, but nothing reads from it yet - the
    // ViewModel-owned player (PlayerViewModel.player) remains the one actually
    // driving playback until step 2 cuts call sites over.
    private val mediaHolderLazy = lazy {
        PlayerMediaHolder(this).also { holder ->
            // SVC_RACE_DEBUG -->
            logcat {
                "SVC_RACE_DEBUG PlayerMediaHolder constructed service=${System.identityHashCode(this)} " +
                    "holder=${System.identityHashCode(holder)} at=${android.os.SystemClock.elapsedRealtime()}"
            }
            // <-- SVC_RACE_DEBUG
            // AM (BACKGROUND_SKIP_FIX) -->
            // Drives the notification directly off the holder's own state, rather
            // than relying solely on PlayerActivity's REOPEN_TARGET_STALENESS_FIX
            // observer pushing updates via updateEpisodeInfo() - that observer only
            // runs while a live Activity exists. Without this, a successful
            // background skip (see PlayerMediaHolder.skipToAdjacentEpisode())
            // updated the holder's own bookkeeping correctly but the visible
            // notification never refreshed to match, since nothing was watching
            // for that change with no Activity around to notice it.
            holder.state
                .map { Triple(it.animeTitle to it.episodeTitle, it.animeId to it.episodeId, it.resolvedEpisodeKey) }
                .distinctUntilChanged()
                .onEach { (titles, ids, resolvedKey) ->
                    // AM (MEDIASESSION_SINGLE_WRITER_FIX) -->
                    // Previously deferred entirely to PlayerActivity's own
                    // REOPEN_TARGET_STALENESS_FIX observer whenever an Activity was alive,
                    // to avoid calling updateEpisodeInfo()/notify() twice for the same
                    // change. That Activity-side observer is gone now (see PlayerActivity's
                    // own MEDIASESSION_SINGLE_WRITER_FIX removal notes) - this is the sole
                    // writer of the notification's title/subtitle/reopen-target, live
                    // Activity or not. Reacts off the holder's own state, which
                    // syncHolderSessionState() keeps current the instant any switch
                    // happens (see PlayerViewModel), so this is just as fast as the
                    // Activity-side observer it replaces.
                    // <-- AM (MEDIASESSION_SINGLE_WRITER_FIX)
                    val (animeTitle, episodeTitle) = titles
                    val (animeId, episodeId) = ids
                    if (animeTitle.isEmpty() && episodeTitle.isEmpty()) return@onEach
                    // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
                    // Gated on the SAME resolution-finished signal
                    // pushLiveMediaState() uses (see PlayerMediaState's own doc comment
                    // on resolvedEpisodeKey) - without this, the notification's visible
                    // title/subtitle updated instantly on every switch while its
                    // artwork (a completely separate Android API surface -
                    // MediaSession's metadata, not this Notification's own text) waited
                    // on resolution, which looked exactly like an incremental,
                    // partial-then-corrected render to anyone just looking at the
                    // notification. This intentionally also delays the reopen-intent's
                    // animeId/episodeId by the same amount - accepting that trade-off
                    // (a stale reopen target for however long resolution takes, capped
                    // at 2 minutes) in favor of a genuinely atomic notification update,
                    // rather than splitting "text can wait" from "ids can't" and ending
                    // up back at a partially-incremental render either way.
                    // <-- AM (WAIT_FOR_COMPLETE_DATA_FIX)
                    if (resolvedKey != ids) return@onEach
                    updateEpisodeInfo(animeTitle, episodeTitle, animeId, episodeId)
                }
                .launchIn(serviceScope)
            // <-- AM (BACKGROUND_SKIP_FIX)

            // AM (NOTIFICATION_RESUME_RESTORE_FIX) -->
            // Handles the pause-then-resume case: fallbackCallback.onPlay() (the
            // resume path used with no live Activity, e.g. resuming from Bluetooth
            // controls after the notification was dismissed while paused) only
            // ever flipped mpv's own pause state - nothing called back into this
            // Service to re-establish the notification. updatePlaybackState()
            // already calls startForeground() (see NOTIFICATION_REPOST_FIX), which
            // is exactly what's needed here too.
            // <-- AM (NOTIFICATION_RESUME_RESTORE_FIX)
            holder.state
                .map { it.paused }
                .distinctUntilChanged()
                .onEach { paused -> if (!paused) updatePlaybackState(isPlaying = true) }
                .launchIn(serviceScope)
        }
    }
    val mediaHolder: PlayerMediaHolder by mediaHolderLazy
    // <-- AM (SERVICE_OWNED_PLAYER)

    // AM (BACKGROUND_SKIP_FIX) -->
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // <-- AM (BACKGROUND_SKIP_FIX)

    private var wakeLock: PowerManager.WakeLock? = null

    private var title: String = ""
    private var subtitle: String = ""
    private var isPlaying: Boolean = true
    private var mediaSessionToken: MediaSession.Token? = null
    private var animeId: Long? = null
    private var episodeId: Long? = null
    private var onTogglePlayPause: (() -> Unit)? = null
    private var onStopRequested: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): PlayerBackgroundPlaybackService = this@PlayerBackgroundPlaybackService

        // AM (SERVICE_OWNED_PLAYER) -->
        // Step 1 verification hook: lets PlayerActivity confirm the Service-owned
        // holder is alive alongside the existing ViewModel-owned player. Not used
        // for actual playback control until step 2.
        fun getMediaHolder(): PlayerMediaHolder = mediaHolder
        // <-- AM (SERVICE_OWNED_PLAYER)
    }

    // AM (NOTIFICATION_DISMISS_STOPS_BACKGROUND_FIX) -->
    // Standard getBroadcast() pairing
    private var dismissReceiverRegistered = false
    // AM (NOTIFICATION_DISMISS_KEEPS_ACTIVITY_FIX) -->
    // Doesn't close PlayerActivity
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopBackgroundPlayback()
        }
    }
    // <-- AM (NOTIFICATION_DISMISS_KEEPS_ACTIVITY_FIX)

    override fun onCreate() {
        super.onCreate()
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG Service.onCreate() service=${System.identityHashCode(this)} " +
                "at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_NOTIFICATION_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        dismissReceiverRegistered = true
    }
    // <-- AM (NOTIFICATION_DISMISS_STOPS_BACKGROUND_FIX)

    override fun onBind(intent: Intent?): IBinder {
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG Service.onBind() service=${System.identityHashCode(this)} " +
                "mediaHolderInitialized=${mediaHolderLazy.isInitialized()} at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
        return binder
    }

    // AM (NOTIFICATION_CREATION_STALE_SNAPSHOT_FIX) -->
    // start() used to also take title/subtitle/animeId/episodeId and write them
    // directly here, sourced from a one-off snapshot of viewModel.uiData/stateData
    // taken at the call site (PlayerActivity's onServiceConnected). That snapshot is
    // provably unreliable at that exact moment - onServiceConnected fires as soon as
    // the Service binds, which is not ordered against onNewIntent()'s episode load
    // completing, so the anime/episode title/ids were frequently still blank/default
    // the instant this ran. The ORIGINAL notification the OS/lock-screen widgets
    // first observe was therefore built from that blank snapshot - not from "no data
    // yet" (which would at least be an honest, obviously-loading state) but from an
    // empty title/subtitle that visually looks like a fully-loaded, empty-metadata
    // notification. That first bad post is exactly what upstream system consumers
    // (Samsung's FaceWidget media card, confirmed via logcat) latched onto and were
    // slow or inconsistent about ever refreshing away from, independent of anything
    // downstream correcting this.title/this.subtitle a moment later.
    //
    // Now start() only ever begins the foreground service state and posts a neutral,
    // honestly-empty holder notification - it never writes title/subtitle/animeId/
    // episodeId itself. PlayerBackgroundPlaybackService's own reactive holder.state
    // observer (now unconditional - see MEDIASESSION_SINGLE_WRITER_FIX) is the ONLY
    // writer of those fields, firing the instant PlayerViewModel.syncHolderSessionState()
    // populates real data (itself called from setupEpisode(), the single choke point
    // for the very first episode load same as every later switch). One writer, one
    // moment real data appears, instead of a racy snapshot needing a later correction.
    // <-- AM (NOTIFICATION_CREATION_STALE_SNAPSHOT_FIX)
    fun start(
        isPlaying: Boolean,
        mediaSessionToken: MediaSession.Token?,
        onTogglePlayPause: () -> Unit,
        onStopRequested: () -> Unit,
    ) {
        this.isPlaying = isPlaying
        this.mediaSessionToken = mediaSessionToken
        this.onTogglePlayPause = onTogglePlayPause
        this.onStopRequested = onStopRequested
        ServiceCompat.startForeground(
            this,
            Notifications.ID_BACKGROUND_PLAYBACK,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        acquireWakeLock()
        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        // Step 4a: the app-lock exemption used to be set here, tied to "the
        // notification is showing." That stops being a valid proxy once the
        // notification can show during ordinary foreground playback too (step 4b) -
        // the exemption now belongs to PlayerActivity, driven by whether the
        // Activity itself is actually away from the foreground, not by Service
        // state. See PlayerActivity's onPause()/onStart()/onDestroy().
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
    }

    /** Keeps the CPU awake so decode/EOF/episode-load logic keeps running with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:BackgroundPlaybackWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_DURATION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Reflects the current pause state in the notification. */
    fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        // AM (NOTIFICATION_REPOST_FIX) -->
        // Was a bare NotificationManagerCompat.notify() call, which assumes the
        // foreground-service/notification association is already intact - if the
        // notification was ever dismissed (confirmed: possible independently of
        // the Service itself stopping, e.g. via Samsung's own "Check background
        // activity" UI), a bare notify() with the same id does not reliably bring
        // it back. startForeground() is the call that actually (re-)establishes
        // that association in the first place, so calling it again here is what
        // genuinely guarantees a notification exists whenever content updates,
        // rather than assuming one already does.
        // <-- AM (NOTIFICATION_REPOST_FIX)
        ServiceCompat.startForeground(
            this,
            Notifications.ID_BACKGROUND_PLAYBACK,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /** Keeps notification text and reopen-intent ids in sync when the episode changes mid-session. */
    fun updateEpisodeInfo(title: String, subtitle: String, animeId: Long?, episodeId: Long?) {
        this.title = title
        this.subtitle = subtitle
        this.animeId = animeId
        this.episodeId = episodeId
        // AM (NOTIFICATION_REPOST_FIX) -->
        // See updatePlaybackState()'s own doc comment above - same fix, same reason.
        // <-- AM (NOTIFICATION_REPOST_FIX)
        ServiceCompat.startForeground(
            this,
            Notifications.ID_BACKGROUND_PLAYBACK,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    fun stopBackgroundPlayback() {
        onTogglePlayPause = null
        onStopRequested = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG Service.onStartCommand() service=${System.identityHashCode(this)} " +
                "action=${intent?.action} startId=$startId at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> onTogglePlayPause?.invoke()
            ACTION_STOP -> {
                onStopRequested?.invoke()
                stopBackgroundPlayback()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG Service.onDestroy() service=${System.identityHashCode(this)} " +
                "mediaHolderInitialized=${mediaHolderLazy.isInitialized()} " +
                "holder=${if (mediaHolderLazy.isInitialized()) System.identityHashCode(mediaHolder) else "n/a"} " +
                "at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
        onTogglePlayPause = null
        onStopRequested = null
        releaseWakeLock()
        // AM (NOTIFICATION_DISMISS_STOPS_BACKGROUND_FIX) -->
        if (dismissReceiverRegistered) {
            unregisterReceiver(dismissReceiver)
            dismissReceiverRegistered = false
        }
        // <-- AM (NOTIFICATION_DISMISS_STOPS_BACKGROUND_FIX)
        // AM (BACKGROUND_SKIP_FIX) -->
        serviceScope.cancel()
        // <-- AM (BACKGROUND_SKIP_FIX)
        // AM (SECURE_LOCK_BACKGROUND_PLAYBACK) -->
        // Step 4a: no longer this Service's job - see the comment in start() above.
        // <-- AM (SECURE_LOCK_BACKGROUND_PLAYBACK)
        // AM (SERVICE_OWNED_PLAYER) -->
        // Step 1 only: this Service's onDestroy is still driven by the existing
        // bind/unbind lifecycle (tied to backgrounding), not by "playback ended".
        // Guard with isInitialized so a Service that was never actually bound to
        // for media-holder purposes doesn't construct one just to release it.
        if (mediaHolderLazy.isInitialized()) {
            mediaHolder.release()
        }
        // <-- AM (SERVICE_OWNED_PLAYER)
        super.onDestroy()
    }

    // AM (PIP_TASK_ROOT_FIX_REMOVED) -->
    // Was a TaskStackBuilder-based synthetic back stack (MainActivity -> PlayerActivity)
    // built via startActivities() whenever PlayerActivity.hasLiveInstance was false, to
    // avoid a bare task-root PlayerActivity - originally added because that was observed
    // to be involved in the system tearing the Activity down on PIP exit, bypassing its
    // own onPictureInPictureModeChanged()/finish() entirely. Removed because this
    // function only ever runs while this Service - and therefore its own live
    // mediaHolder - is already alive; hasLiveInstance=false here never meant "genuinely
    // cold, no session," only "Activity destroyed, session still live," the exact case
    // LIVE_INSTANCE_REOPEN_FIX's reuse-existing branch already handles correctly and
    // cheaply. Git history confirms the original task-root-teardown symptom (no clean
    // Activity lifecycle trace) predates isBackgroundPlayTransitionFinish and
    // PIP_DISMISS_RELEASE_CRASH_FIX by several days - both were later fixes for a
    // player.release()-while-Service-depends-on-it native crash that would produce
    // exactly that "bypassed my own code" symptom, since the whole process was dying,
    // not the system selectively tearing down one Activity. That crash is fixed at its
    // source now, not routed around - keeping this app's own reopen at a flat,
    // low-signature single-Activity PendingIntent, same as every other reopen path, is
    // simpler and no longer building a whole synthetic MainActivity+PlayerActivity task
    // construction burst every single reopen just to dodge a bug that isn't there
    // anymore. VERIFY before trusting this fully: force a task-root PlayerActivity into
    // PIP, dismiss it, confirm PIP_DISMISS_PAUSE_FIX's own detection branch actually
    // fires cleanly rather than the process dying below it - the same way the original
    // task-root-affinity ambiguity was confirmed via dumpsys, not assumed.
    // AM (PLAYER_HOST_SCREEN_REOPEN_FIX) -->
    // Was PlayerActivity.newIntent(this, animeId, episodeId) unconditionally -
    // confirmed live on-device (2026-09-06), once PLAYER_HOST_SCREEN_NOTIFICATION_FIX
    // made this notification actually appear for PlayerHostScreen-originated
    // sessions (the only kind MainActivity.startPlayerActivity() creates now),
    // tapping it opened a genuinely NEW PlayerActivity instance, which adopted
    // the same live PlayerMediaHolder alongside the still-showing
    // PlayerHostScreen - two simultaneous consumers of one session, visibly
    // "two instances." Routes through MainActivity's existing onNewIntent()
    // instead, reusing startPlayerActivity()'s own already-correct reuse-or-
    // push decision (see that function's own doc comment) rather than
    // duplicating it - so tapping this now behaves exactly like tapping the
    // same episode again from inside the app: a no-op if already showing,
    // brings MainActivity forward with PlayerHostScreen pushed otherwise.
    // NotificationReceiver's separate new-episode notification (a different
    // notification entirely, Notifications.ID_NEW_EPISODES) still targets
    // PlayerActivity directly - not touched here, still a known, separate gap.
    private fun buildReopenPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            Intent(this, MainActivity::class.java).apply {
                action = "${BuildConfig.APPLICATION_ID}.REOPEN_PLAYER_HOST_SCREEN"
                putExtra("animeId", animeId)
                putExtra("episodeId", episodeId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    // <-- AM (PLAYER_HOST_SCREEN_REOPEN_FIX)
    // <-- AM (PIP_TASK_ROOT_FIX_REMOVED)

    private fun buildNotification(): Notification {
        val togglePendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_TOGGLE,
            Intent(this, PlayerBackgroundPlaybackService::class.java).setAction(ACTION_TOGGLE_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, PlayerBackgroundPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openAppPendingIntent = buildReopenPendingIntent()
        // AM (NOTIFICATION_DISMISS_STOPS_BACKGROUND_FIX) -->
        // Explicit broadcast, this app only
        val deletePendingIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_DISMISS,
            Intent(ACTION_NOTIFICATION_DISMISSED).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // <-- AM (NOTIFICATION_DISMISS_STOPS_BACKGROUND_FIX)

        return NotificationCompat.Builder(this, Notifications.CHANNEL_BACKGROUND_PLAYBACK)
            .setSmallIcon(R.drawable.ic_ani)
            .setContentTitle(title)
            .setContentText(subtitle)
            // AM (NOTIFICATION_ONGOING_BLOCKS_CANCEL_FIX) -->
            // Was setOngoing(true), blocked swipe-dismiss
            // <-- AM (NOTIFICATION_ONGOING_BLOCKS_CANCEL_FIX)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .addAction(
                if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp,
                stringResource(if (isPlaying) MR.strings.action_pause else AYMR.strings.action_play),
                togglePendingIntent,
            )
            .addAction(
                R.drawable.ic_close_24dp,
                stringResource(AMMR.strings.player_background_playback_stop),
                stopPendingIntent,
            )
            .apply {
                val token = mediaSessionToken
                if (token != null) {
                    // MediaStyle + a live MediaSession gets this into the system media
                    // controls (shade, lock screen, some OEM quick-settings card).
                    setStyle(
                        androidx.media.app.NotificationCompat.MediaStyle()
                            .setMediaSession(MediaSessionCompat.Token.fromToken(token))
                            .setShowActionsInCompactView(0),
                    )
                }
            }
            .build()
    }

    companion object {
        private const val REQUEST_CODE_TOGGLE = 0
        private const val REQUEST_CODE_STOP = 1
        private const val REQUEST_CODE_OPEN = 2
        private const val REQUEST_CODE_DISMISS = 3

        // Caps wake lock lifetime in case stop/onDestroy never fires.
        private const val MAX_WAKE_LOCK_DURATION_MS = 12 * 60 * 60 * 1000L
        const val ACTION_TOGGLE_PLAY_PAUSE = "eu.kanade.tachiyomi.ui.player.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_STOP = "eu.kanade.tachiyomi.ui.player.action.STOP"
        const val ACTION_NOTIFICATION_DISMISSED = "eu.kanade.tachiyomi.ui.player.action.NOTIFICATION_DISMISSED"

        fun newIntent(context: Context): Intent {
            return Intent(context, PlayerBackgroundPlaybackService::class.java)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, newIntent(context))
        }
    }
}
