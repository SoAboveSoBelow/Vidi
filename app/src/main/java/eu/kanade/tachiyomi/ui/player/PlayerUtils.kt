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

package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import java.io.File
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

internal fun Uri.openContentFd(context: Context): String? {
    return context.contentResolver.openFileDescriptor(this, "r")?.detachFd()?.let {
        Utils.findRealPath(it)?.also { _ ->
            ParcelFileDescriptor.adoptFd(it).close()
        } ?: "fd://$it"
    }
}

// AM (EXTERNAL_PREFERS_LIBRARY) -->
/**
 * The real filesystem path behind this URI, or null when it has none.
 *
 * Resolves the descriptor's own /proc symlink rather than going through
 * [Utils.findRealPath], which [openContentFd] above uses. That returns null for
 * at least ES File Explorer's provider - confirmed from a capture of an
 * external open, where openContentFd fell through to `fd://171`, and `fd://` is
 * only reached when findRealPath gave nothing. Whatever extra condition it
 * applies (being openable by path is the likely one, which scoped storage
 * denies for shared media regardless of whether the path is correct), the
 * symlink still names the file.
 *
 * UniFile.filePath is not an option either: it is null for SAF-backed files
 * often enough that this codebase carries `displayablePath` to cope with it
 * (see UniFileExtensions).
 *
 * [heldFd] is a descriptor already open for this file, when one is held - see
 * PlayerMediaHolder.externalFd. Preferred when given, because by the time a
 * session is rebuilt the grant that would let us open a fresh one may be gone,
 * while the held descriptor is still perfectly good.
 */
internal fun Uri.realFilePath(context: Context, heldFd: Int? = null): String? {
    heldFd?.let { fd -> realPathOfFd(fd)?.let { return it } }
    return try {
        context.contentResolver.openFileDescriptor(this, "r")?.use { pfd ->
            realPathOfFd(pfd.fd)
        }
    } catch (e: Exception) {
        // Single-argument logcat deliberately: this file imports logcat.logcat,
        // whose second positional parameter is a tag, not a throwable.
        logcat(LogPriority.INFO) { "realFilePath failed for $this: ${e.message}" }
        null
    }
}

/**
 * Where /proc says [fd] points, or null when it does not name a real file.
 *
 * A descriptor can legitimately resolve to a pipe, socket or memfd, none of
 * which is a file on disk, so anything that is not an absolute path outside
 * /proc is rejected rather than handed back for a caller to puzzle over.
 */
private fun realPathOfFd(fd: Int): String? {
    val resolved = runCatching { File("/proc/self/fd/$fd").canonicalPath }.getOrNull() ?: return null
    if (!resolved.startsWith("/") || resolved.startsWith("/proc/")) return null
    if (resolved.contains("memfd:") || resolved.contains("pipe:") || resolved.contains("socket:")) return null
    return resolved
}
// <-- AM (EXTERNAL_PREFERS_LIBRARY)

internal fun Uri.resolveUri(context: Context): String? {
    val filepath = when (scheme) {
        "file" -> path
        "content" -> openContentFd(context)
        "data" -> "data://$schemeSpecificPart"
        in Utils.PROTOCOLS -> toString()
        else -> null
    }

    if (filepath == null) logcat(LogPriority.ERROR) { "unknown scheme: $scheme" }
    return filepath
}

internal fun Uri.getFileName(context: Context): String? {
    return context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        cursor.getString(nameIndex)
    }
}

inline fun <reified T> MPVNode.toObject(json: Json): T = json.decodeFromString<T>(toJson())

fun <T> Flow<T>.collectAsState(scope: CoroutineScope, initialValue: T? = null) =
    object : ReadOnlyProperty<Any?, T?> {
        private var value: T? = initialValue
        init {
            scope.launch { collect { value = it } }
        }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = value
    }
