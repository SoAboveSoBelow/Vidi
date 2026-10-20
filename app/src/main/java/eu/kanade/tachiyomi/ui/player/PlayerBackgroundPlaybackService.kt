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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
// AM (BACKGROUND_SKIP_FIX) -->
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
// <-- AM (BACKGROUND_SKIP_FIX)
import mihon.app.di.appGraph
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
                .map { Triple(it.animeTitle to it.episodeTitle, it.sessionKey, it.resolvedSessionKey) }
                .distinctUntilChanged()
                .onEach { (titles, key, resolvedKey) ->
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
                    if (animeTitle.isEmpty() && episodeTitle.isEmpty()) return@onEach
                    // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
                    // Gated on the SAME resolution-finished signal
                    // pushLiveMediaState() uses (see PlayerMediaState's own doc comment
                    // on resolvedSessionKey) - without this, the notification's visible
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
                    // AM (CONTENT_AGNOSTIC_SESSION) -->
                    // Null check for the same reason as pushLiveMediaState()'s -
                    // see that comment. Both nullable keys being null compares
                    // equal, where the old (animeId to episodeId) pair did not.
                    if (key == null || resolvedKey != key) return@onEach
                    // <-- AM (CONTENT_AGNOSTIC_SESSION)
                    updateEpisodeInfo(animeTitle, episodeTitle, key)
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
                // AM (IDLE_SESSION_COST_FIX) -->
                // Was `if (!paused)` - resume only. Both edges now, so this observer is
                // the single writer of the Service's foreground state, driven by mpv's
                // own real pause value rather than by whichever call site happened to
                // cause the change.
                // <-- AM (IDLE_SESSION_COST_FIX)
                .onEach { paused -> updatePlaybackState(isPlaying = !paused) }
                .launchIn(serviceScope)

            // AM (EXTERNAL_RESUME_POSITION) -->
            // The holder's own live sampler already pushes position into state; this
            // just keeps the latest where buildReopenIntent can reach it. Not
            // distinctUntilChanged on the whole state - only the field is taken, so
            // this collects a Flow of ints that changes about once a second.
            holder.state
                .map { it.positionMs }
                .distinctUntilChanged()
                .onEach { lastPositionMs = it }
                .launchIn(serviceScope)
            // <-- AM (EXTERNAL_RESUME_POSITION)

            // AM (IDLE_RECLAIM_GRACE) -->
            // The grace timer's two inputs, combined so either one changing
            // re-evaluates. Separate from the observer above deliberately: that one
            // owns the notification and the wake lock, this one owns the session's
            // lifetime, and folding them together would mean a change to either
            // concern editing the other's single-writer path.
            combine(
                holder.state.map { it.paused }.distinctUntilChanged(),
                holder.hasExternalScreenConsumerFlow,
            ) { paused, hasConsumer -> paused && !hasConsumer }
                .distinctUntilChanged()
                .onEach { idle -> if (idle) armIdleReclaim() else cancelIdleReclaim() }
                .launchIn(serviceScope)
            // <-- AM (IDLE_RECLAIM_GRACE)
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
    // AM (CONTENT_AGNOSTIC_SESSION) -->
    // Was animeId/episodeId. The notification only ever needed these to rebuild
    // its reopen intent, which is a "take me back to what's playing" request -
    // the session's identity, not a library lookup. Holding the key keeps that
    // true for sessions that have no library row.
    private var sessionKey: SessionKey? = null
    // <-- AM (CONTENT_AGNOSTIC_SESSION)

    // AM (EXTERNAL_RESUME_POSITION) -->
    /**
     * Where the session was last playing, for [buildReopenIntent] to carry.
     *
     * Held here because this Service lives for as long as the notification does,
     * which is the lifetime a resume position has to survive: the player may be
     * torn down to save battery, but while the notification is up, pressing play
     * must start again where it stopped. A library episode gets this from its DB
     * row; an external file has no row, so the reopen Intent carries it instead -
     * and a PendingIntent is held by the system alongside the notification, so
     * the position outlives even this field.
     */
    private var lastPositionMs: Int = 0
    // <-- AM (EXTERNAL_RESUME_POSITION)
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
        // AM (IDLE_SESSION_COST_FIX) -->
        // Always a real startForeground() first, whatever the pause state. This Service
        // is launched with startForegroundService() (see the companion's start()), which
        // obliges it to enter the foreground within the platform's timeout or be killed
        // with ForegroundServiceDidNotStartInTimeException - so a session that begins
        // paused cannot detach on its way in, it has to enter and then step back out.
        ServiceCompat.startForeground(
            this,
            Notifications.ID_BACKGROUND_PLAYBACK,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (!isPlaying) postNotification()
        // <-- AM (IDLE_SESSION_COST_FIX)
        // AM (WAKE_LOCK_TRACKS_PLAYBACK) -->
        // Only when the session actually starts playing - see updatePlaybackState.
        if (isPlaying) acquireWakeLock()
        // <-- AM (WAKE_LOCK_TRACKS_PLAYBACK)
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

    // AM (IDLE_SESSION_COST_FIX) -->
    /**
     * Posts the notification, in foreground service state while playing and detached
     * from it while paused.
     *
     * Every post used to be a startForeground() (see NOTIFICATION_REPOST_FIX), which
     * meant a session that was paused and left alone held the FOREGROUND_SERVICE_TYPE_
     * MEDIA_PLAYBACK obligation indefinitely. That pins the process at foreground
     * oom_adj: never frozen by the cached-app freezer, never reclaimable, and billed
     * for the whole stretch by the system's own accounting regardless of how little it
     * actually does per hour.
     *
     * STOP_FOREGROUND_DETACH gives that obligation up without taking the notification
     * down with it. The notification stays posted and the MediaSession stays active, so
     * the shade, the lock screen and Bluetooth controls all keep working and can resume
     * the session; the process simply stops being special while it is doing nothing.
     * This is the same shape the platform's own media stack uses - Media3's
     * MediaSessionService leaves foreground when playback stops and re-enters on play.
     *
     * AM (IDLE_RECLAIM_GRACE) -->
     * That detach is no longer done on the pause edge itself, which was the half of
     * this fix that was missing rather than a deliberate trade. Dropping the
     * obligation the instant mpv reports paused makes the process reclaimable
     * immediately, and the session is then killed by whatever happens next rather
     * than reclaimed at a point of our choosing. Confirmed on-device (2026-10-17):
     * dismissing the player (which pauses - see PlayerHostScreen's onDismiss, whose
     * own contract is "pause and hide, but keep the session alive") and then swiping
     * the task away destroyed the whole session, while doing the same with playback
     * left running survived it. Identical paths, differing only in pause state,
     * because pause was the thing deciding survivability.
     *
     * The obligation is now held for as long as a session exists, playing or paused,
     * and [armIdleReclaim] takes it away on a timer instead - so a paused session is
     * protected for a grace period and then torn down properly, in one orderly place,
     * rather than being left to be killed arbitrarily. The cost IDLE_SESSION_COST_FIX
     * exists to avoid is still avoided: nothing is pinned indefinitely, it just ends
     * deliberately instead of by reclamation.
     * <-- AM (IDLE_RECLAIM_GRACE)
     *
     * NOTIFICATION_REPOST_FIX's own reasoning still holds on the playing path, so that
     * branch is still a startForeground() rather than a bare notify().
     */
    private fun postNotification() {
        val notification = buildNotification()
        if (isPlaying || !foregroundReleased) {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_BACKGROUND_PLAYBACK,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            // The grace period has expired: hand the obligation back, keep the
            // notification posted. DETACH, not REMOVE - see foregroundReleased.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            NotificationManagerCompat.from(this)
                .notify(Notifications.ID_BACKGROUND_PLAYBACK, notification)
        }
    }
    // <-- AM (IDLE_SESSION_COST_FIX)

    // AM (IDLE_RECLAIM_GRACE) -->
    /** Pending orderly teardown of a paused, unwatched session - see [armIdleReclaim]. */
    private var idleReclaimJob: Job? = null

    /**
     * Starts the grace period after which a paused session is torn down.
     *
     * Armed only when BOTH are true: mpv is paused, and nothing is on screen
     * showing this session. A paused video with the player still open is a user
     * sitting on a frame, not an idle session, and must never be reclaimed under
     * them - which is why [PlayerMediaHolder.hasExternalScreenConsumerFlow] is part
     * of the condition rather than pause alone.
     *
     * Any change to either input cancels and re-evaluates, so resuming or coming
     * back to the player disarms this with no bookkeeping of its own. A plain
     * coroutine delay is enough and no alarm is needed, precisely because the
     * foreground obligation is held throughout the grace period - the process is
     * alive to run it.
     *
     * Expiry is an ORDERLY teardown, not a hand-off to the system: stop the
     * foreground state and remove the notification, then stopSelf, which runs
     * onDestroy and releases the holder through the one existing path. The pause
     * edge has already flushed the position (see PlayerMediaHolder's PauseChanged
     * observer), so the session is resumable from the DB afterwards exactly as it
     * would be after any other clean end.
     */
    private fun armIdleReclaim() {
        idleReclaimJob?.cancel()
        idleReclaimJob = serviceScope.launch {
            delay(IDLE_RECLAIM_GRACE_MS)
            logcat(LogPriority.INFO) {
                "IDLE_RECLAIM paused and unwatched for ${IDLE_RECLAIM_GRACE_MS}ms - releasing foreground"
            }
            withContext(Dispatchers.Main) {
                foregroundReleased = true
                postNotification()
            }
        }
    }

    private fun cancelIdleReclaim() {
        idleReclaimJob?.cancel()
        idleReclaimJob = null
        foregroundReleased = false
    }

    /**
     * True once the grace period has expired and the foreground obligation has
     * been given back.
     *
     * The session is NOT over at that point - the notification stays posted, the
     * MediaSession stays active, and every control on it must still work. All
     * that changed is that the process stopped being privileged. An earlier
     * version of this expiry called stopSelf() instead, which tore the session
     * down on a timer: that is the app deciding the user is finished, which is
     * not ours to decide. Swiping the notification away is that decision, and it
     * already has a path (see NOTIFICATION_DISMISS_STOPS_BACKGROUND_FIX).
     */
    private var foregroundReleased = false
    // <-- AM (IDLE_RECLAIM_GRACE)

    /** Reflects the current pause state in the notification. */
    fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        // AM (WAKE_LOCK_TRACKS_PLAYBACK) -->
        // The lock used to be taken when the session STARTED and released only when
        // the Service stopped, so a paused session held a PARTIAL_WAKE_LOCK
        // indefinitely - confirmed on-device at 2h 27m held with nothing playing,
        // flagged LONG by the system, and the reason an idle phone drained
        // overnight: a partial lock keeps the CPU from suspending even when the app
        // is doing nothing at all.
        //
        // What the lock is actually for (per acquireWakeLock's own comment) is
        // keeping decode, EOF handling and episode loading running with the screen
        // off. None of that happens while paused. Resuming does not need the lock
        // to already be held either - whatever delivers the play action, a
        // notification button or a MediaSession command, wakes the CPU to deliver
        // it, and this runs before playback resumes.
        if (isPlaying) acquireWakeLock() else releaseWakeLock()
        // <-- AM (WAKE_LOCK_TRACKS_PLAYBACK)
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
        // AM (IDLE_SESSION_COST_FIX) -->
        postNotification()
        // <-- AM (IDLE_SESSION_COST_FIX)
    }

    /** Keeps notification text and reopen-intent ids in sync when the episode changes mid-session. */
    fun updateEpisodeInfo(title: String, subtitle: String, sessionKey: SessionKey?) {
        this.title = title
        this.subtitle = subtitle
        this.sessionKey = sessionKey
        // AM (NOTIFICATION_REPOST_FIX) -->
        // See updatePlaybackState()'s own doc comment above - same fix, same reason.
        // <-- AM (NOTIFICATION_REPOST_FIX)
        // AM (IDLE_SESSION_COST_FIX) -->
        // Routed through postNotification() so an episode change while paused updates
        // the notification without silently re-entering the foreground state the pause
        // edge just gave up.
        postNotification()
        // <-- AM (IDLE_SESSION_COST_FIX)
    }

    fun stopBackgroundPlayback() {
        onTogglePlayPause = null
        onStopRequested = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // AM (NOTIFICATION_ACTIONS_OUTLIVE_SESSION) -->
            // Was `onTogglePlayPause?.invoke()` alone. That lambda is captured from
            // PlayerHostScreen's service connection and closes over ONE
            // PlayerViewModel instance; it is nulled in onDestroy and
            // stopBackgroundPlayback, and even while set it flips the pause state of
            // whatever player that VM holds. So once the session behind it is gone -
            // the holder released, the player torn down - this was `null?.invoke()`:
            // a silent no-op. The notification was still posted and still looked
            // live, and its play button did nothing at all.
            //
            // The notification's own existence is what says a session is resumable,
            // so its controls have to be as durable as it is. If a live player is
            // there, toggle it, exactly as before. If not, rebuild from the
            // [sessionKey] this Service already holds for the reopen intent - the
            // same payload, through the same path, so there is one definition of
            // "take me back to what was playing" rather than one for tapping the
            // notification body and a broken one for its button.
            //
            // Rebuilding has to go through MainActivity because that is where the
            // player overlay is hosted - there is no headless path that can
            // construct a session (see PlayerMediaHolder's note on
            // hasExternalScreenConsumer). Bringing the app up to resume is a
            // trade-off, but it is strictly better than a button that lies.
            ACTION_TOGGLE_PLAY_PAUSE -> {
                val hasLivePlayer = PlayerMediaHolder.current?.hasAdoptedPlayer == true
                val toggle = onTogglePlayPause
                if (hasLivePlayer && toggle != null) {
                    toggle.invoke()
                } else {
                    logcat(LogPriority.INFO) {
                        "NOTIFICATION_ACTIONS no live player for toggle - rebuilding session"
                    }
                    startActivity(buildReopenIntent())
                }
            }
            // <-- AM (NOTIFICATION_ACTIONS_OUTLIVE_SESSION)
            ACTION_STOP -> {
                onStopRequested?.invoke()
                stopBackgroundPlayback()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
            buildReopenIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // AM (NOTIFICATION_ACTIONS_OUTLIVE_SESSION) -->
    /**
     * "Take me back to what was playing", as an Intent.
     *
     * Extracted from [buildReopenPendingIntent] so the notification's own play
     * action can rebuild a torn-down session through the identical payload and
     * path rather than a second, parallel one - see onStartCommand.
     */
    private fun buildReopenIntent(): Intent {
        return Intent(this, MainActivity::class.java).apply {
            action = "${BuildConfig.APPLICATION_ID}.REOPEN_PLAYER_HOST_SCREEN"
            // AM (EXTERNAL_PLAYBACK_REOPEN_FIX) -->
            // Was library-only: an external session wrote NO extras at all,
            // so MainActivity's handler read -1/-1 and did nothing - tapping
            // the notification simply would not bring an externally-opened
            // video back. Writing the request the same way every other
            // caller does means the reopen path does not need to know which
            // kind of session it is reopening.
            //
            // The library extras keep their original names (see
            // PlaybackRequest.writeToIntent) so a PendingIntent the system
            // still holds from a previous build keeps resolving.
            // Both reads are qualified deliberately: inside apply{} on an
            // Intent, bare `sessionKey`/`title` resolve outward to this
            // Service only because Intent happens to declare neither, which
            // is not a property worth depending on.
            val svc = this@PlayerBackgroundPlaybackService
            svc.sessionKey?.let { key ->
                when (key) {
                    is SessionKey.Library -> PlaybackRequest.Library(
                        animeId = key.animeId,
                        episodeId = key.episodeId,
                    )
                    is SessionKey.External -> PlaybackRequest.External(
                        uri = key.uri,
                        title = svc.title.takeIf { it.isNotBlank() },
                        // AM (EXTERNAL_RESUME_POSITION)
                        positionMs = svc.lastPositionMs.toLong(),
                    )
                }.writeToIntent(this)
            }
            // <-- AM (EXTERNAL_PLAYBACK_REOPEN_FIX)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
    // <-- AM (NOTIFICATION_ACTIONS_OUTLIVE_SESSION)
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

        // AM (HIDE_NOTIFICATION_CONTENT) -->
        // The privacy setting hides what is playing. The MediaSession's own
        // metadata is redacted alongside this (see PlayerMediaHolder) - between
        // them they feed the shade, the lock screen and the media card, and
        // hiding one without the other leaves the episode name on display.
        // Read per build, so toggling the setting mid-playback takes effect on
        // the next notification update.
        val hideContent = appGraph.securityPreferences.hideNotificationContent.get()
        // <-- AM (HIDE_NOTIFICATION_CONTENT)

        // AM (MEDIA_TITLE_DEDUPE) -->
        // A single-entry video puts the same string in both slots, which read as
        // the same line printed twice. See dedupeMediaTitleLines() - the entry
        // title leads here, and the second line is dropped when it adds nothing.
        val (contentTitle, contentText) = dedupeMediaTitleLines(title, subtitle)
        // <-- AM (MEDIA_TITLE_DEDUPE)

        return NotificationCompat.Builder(this, Notifications.CHANNEL_BACKGROUND_PLAYBACK)
            .setSmallIcon(R.drawable.ic_ani)
            .setContentTitle(
                if (hideContent) stringResource(AMMR.strings.player_background_playback_channel) else contentTitle,
            )
            .setContentText(contentText.takeUnless { hideContent })
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
        // AM (IDLE_RECLAIM_GRACE) -->
        /**
         * How long a paused, unwatched session is kept before it is torn down.
         *
         * Twenty minutes: long enough to cover the ordinary reasons a video gets
         * paused and set down - a call, a meal, a conversation, putting the phone
         * in a pocket mid-episode - and still short enough that forgetting about
         * it does not hold the process at foreground priority for the rest of the
         * day. Giving the obligation up is also all that happens at expiry; the
         * notification and the session stay, so the cost of erring long is
         * foreground priority for a while, not anything the user loses.
         *
         * A constant rather than a preference because nothing yet suggests the
         * right value differs per person; promoting it to PlayerPreferences is a
         * few lines if it does.
         */
        private const val IDLE_RECLAIM_GRACE_MS = 20L * 60L * 1000L
        // <-- AM (IDLE_RECLAIM_GRACE)

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
