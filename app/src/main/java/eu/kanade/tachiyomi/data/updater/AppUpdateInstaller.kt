// AM (SILENT_SELF_UPDATE) -->
package eu.kanade.tachiyomi.data.updater

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Installs an app update over this install, in place.
 *
 * The old path handed the APK to the system installer with ACTION_VIEW, which
 * always raises the full "do you want to install this application?" dialog -
 * the same one a brand new app gets, listing the permissions as though they
 * were being granted for the first time. That reads as a fresh install of
 * something unfamiliar, when what is actually happening is this app replacing
 * its own code with a build signed by the same key.
 *
 * A session install can say so. From API 31 a session may ask for
 * [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED], and the platform
 * grants it when the installer is updating a package it installed itself. The
 * first update after a manual install therefore still confirms - this app is
 * not yet the installer of record for itself - and committing that session
 * makes it one, so every update after goes through without a dialog.
 *
 * The confirmation is the platform's to give or withhold, not something to
 * work around: when it withholds, the session reports
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] and hands back the intent to
 * show, which is launched unchanged.
 */
class AppUpdateInstaller(private val context: Context) {

    private val packageInstaller = context.packageManager.packageInstaller

    private var receiverRegistered = false

    /**
     * Streams [apkFile] into a session and commits it.
     *
     * Returns false if the session could not be created or written, so the
     * caller can fall back to the ACTION_VIEW path rather than leaving the
     * user on a button that did nothing.
     *
     * A successful commit replaces this very package, so the process is killed
     * partway through: there is no success path to observe here, and none is
     * written.
     */
    fun install(apkFile: File): Boolean {
        var sessionId: Int? = null
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // Before createSession: the params are copied into the session when
            // it is created, so setting anything on them afterwards is a no-op.
            params.setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            ensureStatusReceiver()

            val id = packageInstaller.createSession(params)
            sessionId = id

            packageInstaller.openSession(id).use { session ->
                session.openWrite(APK_NAME, 0, apkFile.length()).use { output ->
                    apkFile.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }

                val callback = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(INSTALL_ACTION).setPackage(context.packageName),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    },
                )
                @SuppressLint("RequestInstallPackagesPolicy")
                session.commit(callback.intentSender)
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Session install of the app update failed" }
            sessionId?.let {
                try {
                    packageInstaller.abandonSession(it)
                } catch (_: SecurityException) {
                    // Already gone, or committed after all.
                }
            }
            false
        }
    }

    /** The pre-API-31 route, and the fallback when a session cannot be opened. */
    fun installWithSystemUi(apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkFile.getUriCompat(context), ExtensionInstaller.APK_MIME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    /**
     * Shows the platform's own confirmation when it declines to skip it, and
     * logs a failure otherwise.
     *
     * Registered at most once per instance and never unregistered: the commit
     * that succeeds kills this process, so there is nothing to tear down on the
     * path that matters, and a failed attempt leaves one receiver behind rather
     * than one per retry.
     */
    private fun ensureStatusReceiver() {
        if (receiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val userAction = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
                        if (userAction == null) {
                            logcat(LogPriority.ERROR) { "Update install wanted user action but sent no intent" }
                            return
                        }
                        userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        receiverContext.startActivity(userAction)
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        logcat(LogPriority.ERROR) { "Update install failed: $message" }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private companion object {
        const val APK_NAME = "update.apk"
    }
}

private const val INSTALL_ACTION = "AppUpdateInstaller.INSTALL_ACTION"
// <-- AM (SILENT_SELF_UPDATE)
