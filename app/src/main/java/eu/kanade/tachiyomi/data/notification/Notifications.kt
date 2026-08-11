package eu.kanade.tachiyomi.data.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_DEFAULT
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_HIGH
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW
import eu.kanade.tachiyomi.data.connection.discord.RICH_PRESENCE_TAG
import eu.kanade.tachiyomi.util.system.buildNotificationChannel
import eu.kanade.tachiyomi.util.system.buildNotificationChannelGroup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR

/** Notification channel and id constants used across the app. */
object Notifications {

    // Common
    const val CHANNEL_COMMON = "common_channel"
    const val ID_DOWNLOAD_IMAGE = 2

    // Background/PIP-less playback continuation
    const val CHANNEL_BACKGROUND_PLAYBACK = "background_playback_channel"
    const val ID_BACKGROUND_PLAYBACK = -901

    // Library updater
    private const val GROUP_LIBRARY = "group_library"
    const val CHANNEL_LIBRARY_PROGRESS = "library_progress_channel"
    const val ID_LIBRARY_PROGRESS = -101
    const val ID_LIBRARY_SIZE_WARNING = -103
    const val CHANNEL_LIBRARY_ERROR = "library_errors_channel"
    const val ID_LIBRARY_ERROR = -102

    // Downloader
    private const val GROUP_DOWNLOADER = "group_downloader"
    const val CHANNEL_DOWNLOADER_PROGRESS = "downloader_progress_channel"
    const val ID_DOWNLOAD_EPISODE_PROGRESS = -201
    const val CHANNEL_DOWNLOADER_ERROR = "downloader_error_channel"
    const val ID_DOWNLOAD_EPISODE_ERROR = -202

    // New episodes
    const val CHANNEL_NEW_EPISODES = "new_episodes_channel"
    const val ID_NEW_EPISODES = -301
    const val GROUP_NEW_EPISODES = "eu.kanade.tachiyomi.NEW_EPISODES"

    // Backup/restore
    private const val GROUP_BACKUP_RESTORE = "group_backup_restore"
    const val CHANNEL_BACKUP_RESTORE_PROGRESS = "backup_restore_progress_channel"
    const val ID_BACKUP_PROGRESS = -501
    const val ID_RESTORE_PROGRESS = -503
    const val CHANNEL_BACKUP_RESTORE_COMPLETE = "backup_restore_complete_channel_v2"
    const val ID_BACKUP_COMPLETE = -502
    const val ID_RESTORE_COMPLETE = -504

    // Incognito mode
    const val CHANNEL_INCOGNITO_MODE = "incognito_mode_channel"
    const val ID_INCOGNITO_MODE = -701

    // AM (DISCORD_RPC) -->

    // Discord RPC
    const val CHANNEL_DISCORD_RPC = "${RICH_PRESENCE_TAG}_channel"
    const val ID_DISCORD_RPC = -1701
    // <-- AM (DISCORD_RPC)

    // App/extension updates
    private const val GROUP_APK_UPDATES = "group_apk_updates"
    const val CHANNEL_APP_UPDATE = "app_apk_update_channel"
    const val ID_APP_UPDATER = 1
    const val ID_APP_UPDATE_PROMPT = 2
    const val ID_APP_UPDATE_ERROR = 3
    const val CHANNEL_EXTENSIONS_UPDATE = "ext_apk_update_channel"
    const val ID_UPDATES_TO_EXTS = -401
    const val ID_EXTENSION_INSTALLER = -402

    private val deprecatedChannels = listOf(
        "downloader_channel",
        "downloader_complete_channel",
        "backup_restore_complete_channel",
        "library_channel",
        "library_progress_channel",
        "updates_ext_channel",
        "downloader_cache_renewal",
        "crash_logs_channel",
        "library_skipped_channel",
        // AM (DISCORD_RPC) -->
        "Discord RPC",
        // <-- AM (DISCORD_RPC)
    )

    /** Creates the app's notification channels (no-op below Oreo). */
    fun createChannels(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)

        // Delete old notification channels
        deprecatedChannels.forEach(notificationManager::deleteNotificationChannel)

        notificationManager.createNotificationChannelGroupsCompat(
            listOf(
                buildNotificationChannelGroup(GROUP_BACKUP_RESTORE) {
                    setName(context.stringResource(MR.strings.label_backup))
                },
                buildNotificationChannelGroup(GROUP_DOWNLOADER) {
                    setName(context.stringResource(MR.strings.download_notifier_downloader_title))
                },
                buildNotificationChannelGroup(GROUP_LIBRARY) {
                    setName(context.stringResource(MR.strings.label_library))
                },
                buildNotificationChannelGroup(GROUP_APK_UPDATES) {
                    setName(context.stringResource(MR.strings.label_recent_updates))
                },
            ),
        )

        notificationManager.createNotificationChannelsCompat(
            listOf(
                buildNotificationChannel(CHANNEL_COMMON, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_common))
                },
                buildNotificationChannel(CHANNEL_BACKGROUND_PLAYBACK, IMPORTANCE_LOW) {
                    setName(context.stringResource(AMMR.strings.player_background_playback_channel))
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_LIBRARY_PROGRESS, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_progress))
                    setGroup(GROUP_LIBRARY)
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_LIBRARY_ERROR, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_errors))
                    setGroup(GROUP_LIBRARY)
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_NEW_EPISODES, IMPORTANCE_DEFAULT) {
                    setName(context.stringResource(AMMR.strings.am_channel_new_episodes))
                },
                buildNotificationChannel(CHANNEL_DOWNLOADER_PROGRESS, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_progress))
                    setGroup(GROUP_DOWNLOADER)
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_DOWNLOADER_ERROR, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_errors))
                    setGroup(GROUP_DOWNLOADER)
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_BACKUP_RESTORE_PROGRESS, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_progress))
                    setGroup(GROUP_BACKUP_RESTORE)
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_BACKUP_RESTORE_COMPLETE, IMPORTANCE_HIGH) {
                    setName(context.stringResource(MR.strings.channel_complete))
                    setGroup(GROUP_BACKUP_RESTORE)
                    setShowBadge(false)
                    setSound(null, null)
                },
                buildNotificationChannel(CHANNEL_INCOGNITO_MODE, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.pref_incognito_mode))
                },
                // AM (DISCORD_RPC) -->
                buildNotificationChannel(CHANNEL_DISCORD_RPC, IMPORTANCE_LOW) {
                    setName(context.stringResource(AMMR.strings.pref_discord_rpc))
                },
                // <-- AM (DISCORD_RPC)
                buildNotificationChannel(CHANNEL_APP_UPDATE, IMPORTANCE_DEFAULT) {
                    setGroup(GROUP_APK_UPDATES)
                    setName(context.stringResource(MR.strings.channel_app_updates))
                },
                buildNotificationChannel(CHANNEL_EXTENSIONS_UPDATE, IMPORTANCE_DEFAULT) {
                    setGroup(GROUP_APK_UPDATES)
                    setName(context.stringResource(MR.strings.channel_ext_updates))
                },
            ),
        )
    }
}
