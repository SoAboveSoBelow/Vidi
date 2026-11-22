package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import java.util.regex.Pattern

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple episodes.
 *
 * @param context context of application
 */
@Inject
class DownloadNotifier(
    private val context: Context,
    private val preferences: SecurityPreferences,
) {

    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setAutoCancel(false)
            setOnlyAlertOnce(true)
        }
    }

    private val errorNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_ERROR) {
            setAutoCancel(false)
        }
    }

    /**
     * Status of download. Used for correct notification icon.
     */
    private var isDownloading = false

    /**
     * Shows a notification from this builder.
     *
     * @param id the id of the notification.
     */
    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    fun dismissProgress() {
        // AM (PROGRESS_ONLY_WHILE_DOWNLOADING): under the lock a progress post takes.
        synchronized(progressLock) {
            context.cancelNotification(Notifications.ID_DOWNLOAD_EPISODE_PROGRESS)
        }
    }

    // AM (PROGRESS_ONLY_WHILE_DOWNLOADING) -->
    /**
     * Serialises the progress notification's posts against its dismissal.
     *
     * The progress loop polls every 50ms and posts after its wait, so a Stop
     * landing in that wait was followed by one more post: the downloader had
     * already flipped the download out of DOWNLOADING and dismissed the
     * notification, and the late post put it back - ongoing, so it could not be
     * swiped away, showing a download in progress that nothing was running.
     * A post now requires the download to still be DOWNLOADING, checked under
     * this lock; the downloader changes that status before it dismisses, so a
     * post either lands before the dismissal and is removed by it, or sees the
     * new status and does nothing.
     */
    private val progressLock = Any()
    // <-- AM (PROGRESS_ONLY_WHILE_DOWNLOADING)

    /**
     * Called when download progress changes.
     *
     * @param download download object containing download information.
     */
    fun onProgressChange(download: Download) {
        synchronized(progressLock) {
            // AM (PROGRESS_ONLY_WHILE_DOWNLOADING)
            if (download.status != Download.State.DOWNLOADING) return
            with(progressNotificationBuilder) {
                if (!isDownloading) {
                    setSmallIcon(android.R.drawable.stat_sys_download)
                    clearActions()
                    // Open download manager when clicked
                    setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                    isDownloading = true
                    // AY -->
                    // Stop action
                    addAction(
                        R.drawable.ic_pause_24dp,
                        context.stringResource(AYMR.strings.action_stop),
                        NotificationReceiver.pauseDownloadsPendingBroadcast(context),
                    )
                    // <-- AY
                    addAction(
                        R.drawable.ic_book_24dp,
                        context.stringResource(MR.strings.action_show_manga),
                        NotificationReceiver.openEntryPendingActivity(context, download.anime.id),
                    )
                }

                // AY -->
                val downloadingProgressText = if (download.progress == 0) {
                    context.stringResource(AMMR.strings.am_update_check_notification_download_in_progress)
                } else {
                    context.stringResource(AYMR.strings.episode_downloading_progress, download.progress)
                }
                // <-- AY

                if (preferences.hideNotificationContent.get()) {
                    setContentTitle(downloadingProgressText)
                    setContentText(null)
                } else {
                    val title = download.anime.title.chop(15)
                    val quotedTitle = Pattern.quote(title)
                    val episode = download.episode.name.replaceFirst(
                        "$quotedTitle[\\s]*[-]*[\\s]*".toRegex(RegexOption.IGNORE_CASE),
                        "",
                    )
                    setContentTitle("$title - $episode".chop(30))
                    setContentText(downloadingProgressText)
                }

                // AY -->
                setProgress(100, download.progress, download.progress == 0)
                // <-- AY
                setOngoing(true)

                show(Notifications.ID_DOWNLOAD_EPISODE_PROGRESS)
            }
        }
    }

    /**
     * Show notification when download is paused.
     */
    fun onPaused() {
        synchronized(progressLock) {
            with(progressNotificationBuilder) {
                setContentTitle(context.stringResource(MR.strings.chapter_paused))
                setContentText(context.stringResource(MR.strings.download_notifier_download_paused))
                setSmallIcon(R.drawable.ic_pause_24dp)
                setProgress(0, 0, false)
                setOngoing(false)
                clearActions()
                // Open download manager when clicked
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                // Resume action
                addAction(
                    R.drawable.ic_play_arrow_24dp,
                    context.stringResource(MR.strings.action_resume),
                    NotificationReceiver.resumeDownloadsPendingBroadcast(context),
                )
                // Clear action
                addAction(
                    R.drawable.ic_close_24dp,
                    context.stringResource(MR.strings.action_cancel_all),
                    NotificationReceiver.clearDownloadsPendingBroadcast(context),
                )

                show(Notifications.ID_DOWNLOAD_EPISODE_PROGRESS)
            }

            // Reset initial values
            isDownloading = false
        }
    }

    /**
     * Resets the state once downloads are completed.
     */
    fun onComplete() {
        synchronized(progressLock) {
            dismissProgress()

            // Reset states to default
            isDownloading = false
        }
    }

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     * @param timeout duration after which to automatically dismiss the notification.
     * @param animeId the id of the entry being warned about
     * Only works on Android 8+.
     */
    fun onWarning(reason: String, timeout: Long? = null, contentIntent: PendingIntent? = null, animeId: Long? = null) {
        with(errorNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.download_notifier_downloader_title))
            setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setAutoCancel(true)
            clearActions()
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (animeId != null) {
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, animeId),
                )
            }
            setProgress(0, 0, false)
            timeout?.let { setTimeoutAfter(it) }
            contentIntent?.let { setContentIntent(it) }

            show(Notifications.ID_DOWNLOAD_EPISODE_ERROR)
        }

        // Reset download information
        isDownloading = false
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param episode string containing episode title.
     * @param animeId the id of the entry that the error occurred on
     */
    fun onError(error: String? = null, episode: String? = null, animeTitle: String? = null, animeId: Long? = null) {
        // Create notification
        with(errorNotificationBuilder) {
            setContentTitle(
                animeTitle?.plus(": $episode") ?: context.stringResource(MR.strings.download_notifier_downloader_title),
            )
            setContentText(error ?: context.stringResource(AMMR.strings.am_download_notifier_unknown_error))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            clearActions()
            // AM (ERROR_OPENS_ITS_ENTRY) -->
            // Tapping an error opens the entry that failed, where it can be
            // retried; the queue is the action. It was the other way round, so
            // the tap went to the queue and the entry sat behind a button.
            if (animeId != null) {
                setContentIntent(NotificationReceiver.openEntryPendingActivity(context, animeId))
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.label_download_queue),
                    NotificationHandler.openDownloadManagerPendingActivity(context),
                )
            } else {
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            }
            // <-- AM (ERROR_OPENS_ITS_ENTRY)
            setProgress(0, 0, false)

            show(Notifications.ID_DOWNLOAD_EPISODE_ERROR)
        }

        // Reset download information
        isDownloading = false
    }
}
