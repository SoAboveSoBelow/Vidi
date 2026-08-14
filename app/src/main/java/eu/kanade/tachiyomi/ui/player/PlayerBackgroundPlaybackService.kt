package eu.kanade.tachiyomi.ui.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import animiru.domain.player.service.PlayerPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
        PlayerMediaHolder(this).also {
            logcat {
                "PlayerMediaHolder constructed in PlayerBackgroundPlaybackService " +
                    "(serviceHash=${System.identityHashCode(this@PlayerBackgroundPlaybackService)}, " +
                    "holderHash=${System.identityHashCode(it)})"
            }
            // AM (SYNC_DUPLICATE_REINIT_FIX) -->
            activeMediaHolder = it
            // <-- AM (SYNC_DUPLICATE_REINIT_FIX)
            // AM (SERVICE_OWNED_POSITION_TRACKING) -->
            startPositionPersistLoop()
            // <-- AM (SERVICE_OWNED_POSITION_TRACKING)
        }
    }
    val mediaHolder: PlayerMediaHolder by mediaHolderLazy
    // <-- AM (SERVICE_OWNED_PLAYER)

    // AM (SERVICE_OWNED_POSITION_TRACKING) -->
    // PlayerViewModel's own position-tracking (onSecondReached, wired to
    // viewModelScope) is Activity-scoped - it dies the instant the owning Activity is
    // destroyed, which Android does unconditionally on any non-config-change onDestroy
    // regardless of PlayerActivity's own intentionalStop/isBackgroundPlaybackActive
    // logic (that logic only controls what THIS Service does, not the framework's own
    // ViewModelStore.clear()). Since the canonical mpv player and audio output live
    // entirely in this Service and keep running across that gap, this created a
    // tracking blackout: real playback continued, but nothing persisted
    // last_second_seen and nothing noticed a genuine finish, from the moment the old
    // Activity died until a new one eventually reattached and re-wired its flows. If a
    // real reinit happened to be needed in that window, it resumed from a stale, frozen
    // position - visible as a "reset" - and in-progress (not-yet-seen) episodes were
    // where this was most noticeable, since already-seen episodes already skip live
    // position writes entirely (see PlayerViewModel's PRESERVE_POSITION_SETTING), so
    // there was little to lose there. This periodic saver runs on the Service's own
    // lifecycle instead, closing that gap regardless of whether any Activity/ViewModel
    // currently exists.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val getEpisode: GetEpisode by lazy { Injekt.get() }
    private val updateEpisode: UpdateEpisode by lazy { Injekt.get() }
    private val playerPreferences: PlayerPreferences by lazy { Injekt.get() }

    private fun startPositionPersistLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(POSITION_PERSIST_INTERVAL_MS)
                if (!mediaHolderLazy.isInitialized()) continue
                val state = mediaHolder.state.value
                val episodeId = state.episodeId ?: continue
                if (state.paused || state.positionMs <= 0) continue
                val episode = getEpisode.await(episodeId) ?: continue
                if (episode.seen && !playerPreferences.preserveWatchingPosition.get()) continue
                updateEpisode.await(
                    EpisodeUpdate(
                        id = episodeId,
                        lastSecondSeen = state.positionMs.toLong(),
                        totalSeconds = state.durationMs.toLong().takeIf { it > 0 },
                    ),
                )
            }
        }
    }
    // <-- AM (SERVICE_OWNED_POSITION_TRACKING)

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

    override fun onBind(intent: Intent?): IBinder = binder

    /** Begins foreground playback. Call once when backgrounding starts. */
    fun start(
        title: String,
        subtitle: String,
        isPlaying: Boolean,
        animeId: Long?,
        episodeId: Long?,
        mediaSessionToken: MediaSession.Token?,
        onTogglePlayPause: () -> Unit,
        onStopRequested: () -> Unit,
    ) {
        this.title = title
        this.subtitle = subtitle
        this.isPlaying = isPlaying
        this.animeId = animeId
        this.episodeId = episodeId
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
        NotificationManagerCompat.from(this).notify(Notifications.ID_BACKGROUND_PLAYBACK, buildNotification())
    }

    /** Keeps notification text and reopen-intent ids in sync when the episode changes mid-session. */
    fun updateEpisodeInfo(title: String, subtitle: String, animeId: Long?, episodeId: Long?) {
        this.title = title
        this.subtitle = subtitle
        this.animeId = animeId
        this.episodeId = episodeId
        NotificationManagerCompat.from(this).notify(Notifications.ID_BACKGROUND_PLAYBACK, buildNotification())
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
            ACTION_TOGGLE_PLAY_PAUSE -> onTogglePlayPause?.invoke()
            ACTION_STOP -> {
                onStopRequested?.invoke()
                stopBackgroundPlayback()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // AM (REOPEN_RACE_DIAGNOSTICS) -->
        logcat {
            "PlayerBackgroundPlaybackService.onDestroy (serviceHash=" +
                "${System.identityHashCode(this)}, mediaHolderLazy.isInitialized=" +
                "${mediaHolderLazy.isInitialized()})"
        }
        // <-- AM (REOPEN_RACE_DIAGNOSTICS)
        onTogglePlayPause = null
        onStopRequested = null
        releaseWakeLock()
        // AM (SERVICE_OWNED_POSITION_TRACKING) -->
        serviceScope.cancel()
        // <-- AM (SERVICE_OWNED_POSITION_TRACKING)
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
        // AM (SYNC_DUPLICATE_REINIT_FIX) -->
        // Clear the static reference so a genuinely new session later doesn't have
        // needsInit() match against this now-dead holder.
        activeMediaHolder = null
        // <-- AM (SYNC_DUPLICATE_REINIT_FIX)
        super.onDestroy()
    }

    // AM (PIP_TASK_ROOT_FIX) -->
    // Reopening PlayerActivity straight from this notification (via a plain
    // PendingIntent.getActivity()) made it its task's sole/root Activity - Android
    // forces FLAG_ACTIVITY_NEW_TASK on Intents fired from a non-Activity context like
    // this Service, and with nothing else in the back stack, PlayerActivity ends up as
    // task root. That was observed to be involved in the system tearing the Activity
    // down on PIP exit after a notification reopen - and critically, the teardown
    // bypasses PlayerActivity's own onPictureInPictureModeChanged()/finish() entirely,
    // meaning it's happening below any level the Activity's own code can intercept or
    // log. Building a synthetic back stack instead (MainActivity showing the anime,
    // then PlayerActivity on top - reusing the same SHOW_ANIME/SHORTCUT_ANIME deep
    // link the onBack-fallback and NotificationReceiver paths already rely on) gives
    // PlayerActivity a parent in its own task, so it's never task-root when reopened
    // this way - matching how it behaves when opened normally from within the app.
    // Falls back to the old bare-Activity PendingIntent if animeId is somehow
    // unavailable or TaskStackBuilder can't produce one, so the notification's open
    // action never silently breaks.
    private fun buildReopenPendingIntent(): PendingIntent {
        val safeAnimeId = animeId
        if (safeAnimeId != null) {
            val stackPendingIntent = TaskStackBuilder.create(this).run {
                addNextIntent(
                    Intent(this@PlayerBackgroundPlaybackService, MainActivity::class.java)
                        .setAction(Constants.SHORTCUT_ANIME)
                        .putExtra(Constants.ANIME_EXTRA, safeAnimeId),
                )
                addNextIntent(PlayerActivity.newIntent(this@PlayerBackgroundPlaybackService, animeId, episodeId))
                getPendingIntent(REQUEST_CODE_OPEN, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            if (stackPendingIntent != null) return stackPendingIntent
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            PlayerActivity.newIntent(this, animeId, episodeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    // <-- AM (PIP_TASK_ROOT_FIX)

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

        return NotificationCompat.Builder(this, Notifications.CHANNEL_BACKGROUND_PLAYBACK)
            .setSmallIcon(R.drawable.ic_ani)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
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

        // Caps wake lock lifetime in case stop/onDestroy never fires.
        private const val MAX_WAKE_LOCK_DURATION_MS = 12 * 60 * 60 * 1000L
        // AM (SERVICE_OWNED_POSITION_TRACKING) -->
        private const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        // <-- AM (SERVICE_OWNED_POSITION_TRACKING)
        const val ACTION_TOGGLE_PLAY_PAUSE = "eu.kanade.tachiyomi.ui.player.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_STOP = "eu.kanade.tachiyomi.ui.player.action.STOP"

        fun newIntent(context: Context): Intent {
            return Intent(context, PlayerBackgroundPlaybackService::class.java)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, newIntent(context))
        }

        // AM (SYNC_DUPLICATE_REINIT_FIX) -->
        // Set the moment this Service's PlayerMediaHolder is constructed (see
        // mediaHolderLazy above), cleared in onDestroy(). Exists so
        // PlayerViewModel.isSessionAlreadyLiveInPlayer() can consult the live session's
        // animeId/episodeId synchronously - PlayerActivity.onNewIntent() runs from
        // onCreate(), well before this Service's async bindService() callback could
        // possibly have fired for a freshly created duplicate instance (the exact
        // notification-reopen case this exists for), so the instance-bound
        // PlayerViewModel.mediaHolder field isn't populated yet at that point either.
        // Safe as a plain static: this Service never runs in a separate process (see
        // the manifest entry - no android:process).
        @Volatile
        var activeMediaHolder: PlayerMediaHolder? = null
            private set
        // <-- AM (SYNC_DUPLICATE_REINIT_FIX)
    }
}
