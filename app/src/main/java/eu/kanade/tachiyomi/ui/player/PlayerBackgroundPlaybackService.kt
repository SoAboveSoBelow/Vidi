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
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
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
        PlayerMediaHolder(this).also {
            logcat { "PlayerMediaHolder constructed in PlayerBackgroundPlaybackService" }
        }
    }
    val mediaHolder: PlayerMediaHolder by mediaHolderLazy
    // <-- AM (SERVICE_OWNED_PLAYER)

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
        onTogglePlayPause = null
        onStopRequested = null
        releaseWakeLock()
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
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            PlayerActivity.newIntent(this, animeId, episodeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
        const val ACTION_TOGGLE_PLAY_PAUSE = "eu.kanade.tachiyomi.ui.player.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_STOP = "eu.kanade.tachiyomi.ui.player.action.STOP"

        fun newIntent(context: Context): Intent {
            return Intent(context, PlayerBackgroundPlaybackService::class.java)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, newIntent(context))
        }
    }
}
