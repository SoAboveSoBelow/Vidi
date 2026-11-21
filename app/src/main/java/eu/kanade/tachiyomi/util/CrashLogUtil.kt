package eu.kanade.tachiyomi.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.createFileInCacheDir
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

@Inject
class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val preferences: BasePreferences,
    private val networkPreferences: NetworkPreferences,
) {

    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val file = context.createFileInCacheDir("animiru_crash_logs.txt")

            file.appendText(getDebugInfo() + "\n\n")
            // AM (A_AN_ANR_LEAVES_NO_CRASH_TO_CATCH): before the extension
            // list and the logcat dump, because it is the part worth reading
            // first and a shared log gets skimmed from the top.
            getExitInfo()?.let { file.appendText("$it\n\n") }
            getExtensionsInfo()?.let { file.appendText("$it\n\n") }
            exception?.let { file.appendText("$it\n\n") }

            val logPriority = if (networkPreferences.verboseLogging.get()) "V" else "E"
            Runtime.getRuntime().exec("logcat *:$logPriority -d -v year -v zone -f ${file.absolutePath}").waitFor()

            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (_: Throwable) {
            withUIContext { context.toast("Failed to get logs") }
        }
    }

    fun getDebugInfo(): String {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Installation ID: ${preferences.installationId.get()}
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${now.toLocalDateTime(tz)}${tz.offsetAt(now)}
            MPV version: 6764488
            Libplacebo version: v7.349.0
            FFmpeg version: n7.1
        """.trimIndent()
        // TODO: Use this again (from aniyomi-mpv-lib 1.17.n onwards):

        //    MPV version: ${Utils.VERSIONS.mpv}
        //    Libplacebo version: ${Utils.VERSIONS.libPlacebo}
        //    FFmpeg version: ${Utils.VERSIONS.ffmpeg}
    }

    private companion object {
        /** Enough to cover a hang and whatever led to it, not a whole history. */
        const val EXIT_INFO_COUNT = 5

        /** Bounds one trace; the main thread is at its head. */
        const val MAX_TRACE_BYTES = 256 * 1024
    }

    // AM (A_AN_ANR_LEAVES_NO_CRASH_TO_CATCH) -->

    /**
     * How this app's recent processes ended, with the thread dump when the
     * system took one.
     *
     * An ANR is not a crash: the process survives, so no exception is thrown,
     * nothing reaches the uncaught handler, and this file used to contain
     * nothing about it. The system's own trace - every thread's stack at the
     * moment it stopped responding - lives in /data/anr, which an app cannot
     * read, and reaching it otherwise meant catching the ANR live with adb or
     * pulling a full bugreport afterwards. In practice the dialog is dismissed
     * before anyone starts capturing and the trace is simply lost.
     *
     * [ApplicationExitInfo] is the same trace handed to the app that produced
     * it, on the next launch, with no tooling and no timing. So after a hang,
     * dumping logs here now carries the reason the hang happened.
     *
     * Null below API 30, where none of this exists.
     */
    private fun getExitInfo(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { readExitInfo() }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitInfo(): String? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_INFO_COUNT)
        if (records.isEmpty()) return null

        val tz = TimeZone.currentSystemDefault()
        val sections = records.map { info ->
            val ended = kotlin.time.Instant.fromEpochMilliseconds(info.timestamp)
            buildString {
                appendLine("- ${exitReasonName(info.reason)} at ${ended.toLocalDateTime(tz)}${tz.offsetAt(ended)}")
                appendLine("  pid ${info.pid}, importance ${info.importance}, status ${info.status}")
                appendLine("  pss ${info.pss} kB, rss ${info.rss} kB")
                info.description?.let { appendLine("  description: $it") }
                readTrace(info)?.let {
                    appendLine("  trace:")
                    appendLine(it.prependIndent("    "))
                }
            }
        }
        return (listOf("Recent process exits (most recent first):") + sections).joinToString("\n")
    }

    /**
     * The system's thread dump for this exit, bounded.
     *
     * Only an ANR or a native crash has one. Read up to [MAX_TRACE_BYTES]
     * rather than whole: an ANR trace carries every thread in the process and
     * can run to hundreds of kilobytes, and the main thread - the one that
     * says why the app stopped answering - is at the top of it.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? {
        if (info.reason != ApplicationExitInfo.REASON_ANR &&
            info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE
        ) {
            return null
        }
        return runCatching {
            info.traceInputStream?.use { stream ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (out.size() < MAX_TRACE_BYTES) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    out.write(chunk, 0, read)
                }
                val text = out.toString("UTF-8")
                if (out.size() >= MAX_TRACE_BYTES) "$text\n    [trace truncated]" else text
            }
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "crash (unhandled exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_SIGNALED -> "signalled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown ($reason)"
    }
    // <-- AM (A_AN_ANR_LEAVES_NO_CRASH_TO_CATCH)

    private suspend fun getExtensionsInfo(): String? {
        val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

        val extensionInfoList = extensionManager.installedExtensionsFlow.first()
            .sortedBy { it.name }
            .mapNotNull {
                val availableExtension = availableExtensions[it.pkgName]
                val hasUpdate = (availableExtension?.versionCode ?: 0) > it.versionCode

                if (!hasUpdate && !it.isObsolete) return@mapNotNull null

                """
                    - ${it.name}
                      Installed: ${it.versionName} / Available: ${availableExtension?.versionName ?: "?"}
                      Orphaned: ${it.isObsolete}
                """.trimIndent()
            }

        return if (extensionInfoList.isNotEmpty()) {
            (listOf("Problematic extensions:") + extensionInfoList)
                .joinToString("\n")
        } else {
            null
        }
    }
}
