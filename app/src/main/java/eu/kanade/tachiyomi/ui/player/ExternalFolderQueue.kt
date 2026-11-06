package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.Format

// AM (EXTERNAL_FOLDER_GRANT) -->
/**
 * The playable files in a folder the user has explicitly granted, in natural
 * order, so next/previous work through a folder opened from a file manager.
 *
 * Built from a tree the user picked through ACTION_OPEN_DOCUMENT_TREE, which is
 * why this needs none of the path-matching an earlier attempt at the same
 * feature did: with the tree in hand the folder is simply listable, and the
 * opened file is simply in it. Nothing is inferred and nothing is resolved
 * through /proc.
 *
 * A snapshot, deliberately: the listing is a storage round trip and must not
 * happen per next/previous press, so files added or removed afterwards are not
 * picked up. That is the right trade for a queue that has to answer instantly
 * and identically every time it is asked.
 */
class ExternalFolderQueue private constructor(
    private val uris: List<String>,
) : PlaybackQueue {

    override val size: Int get() = uris.size

    override fun keyAt(index: Int): SessionKey? =
        uris.getOrNull(index)?.let { SessionKey.External(uri = it) }

    override fun indexOf(key: SessionKey): Int {
        if (key !is SessionKey.External) return -1
        return uris.indexOf(key.uri)
    }

    companion object {
        /**
         * Folders larger than this are not queued - a listing that long is slow
         * enough to be felt, and is not what opening one video is asking for.
         */
        private const val MAX_FILES = 500

        /**
         * Builds a queue from [treeUri], or null when it yields nothing usable.
         *
         * [openedUri] and [openedFileName] identify the file already playing.
         * The opened file keeps the URI it arrived with, at its sorted position,
         * rather than taking the tree's URI for the same file: the session's key
         * must stay the string it was created with, or indexOf() would report -1
         * forever against a queue the current item is absent from.
         *
         * Matched by name because the two URIs for one file genuinely differ -
         * the launching app's provider and the tree are different providers, and
         * the name is what they agree on.
         *
         * Touches storage, so call it off the main thread.
         */
        fun create(
            context: Context,
            treeUri: Uri,
            openedUri: String,
            openedFileName: String?,
        ): ExternalFolderQueue? {
            return try {
                val dir = UniFile.fromUri(context, treeUri)
                if (dir == null || !dir.isDirectory) {
                    logcat(LogPriority.INFO) { "EXTERNAL_FOLDER $treeUri is not a listable directory" }
                    return null
                }

                val files = dir.listFiles()
                if (files == null) {
                    logcat(LogPriority.INFO) { "EXTERNAL_FOLDER $treeUri could not be listed" }
                    return null
                }
                if (files.size > MAX_FILES) {
                    logcat(LogPriority.INFO) {
                        "EXTERNAL_FOLDER ${files.size} entries exceeds cap $MAX_FILES"
                    }
                    return null
                }

                val playable = files
                    .filter { it.isFile && isQueueable(it) }
                    .sortedWith(compareBy(NaturalOrder) { it.name.orEmpty() })

                if (playable.isEmpty()) {
                    logcat(LogPriority.INFO) { "EXTERNAL_FOLDER no playable files in $treeUri" }
                    return null
                }

                val selfIndex = openedFileName
                    ?.let { name -> playable.indexOfFirst { it.name == name } }
                    ?: -1

                val uris = playable.mapIndexed { index, file ->
                    if (index == selfIndex) openedUri else file.uri.toString()
                }

                // The opened file not being in the granted folder is a real
                // possibility - the user can pick any directory, including the
                // wrong one. Prepending it keeps the session's own key in its own
                // queue, which indexOf() depends on, rather than refusing a
                // listing that is otherwise perfectly good.
                val ordered = if (selfIndex == -1) listOf(openedUri) + uris else uris

                logcat(LogPriority.INFO) {
                    "EXTERNAL_FOLDER ${ordered.size} files, opened file at " +
                        "${ordered.indexOf(openedUri)}"
                }
                ExternalFolderQueue(ordered)
            } catch (e: Exception) {
                logcat(LogPriority.INFO, e) { "EXTERNAL_FOLDER listing failed for $treeUri" }
                null
            }
        }

        /**
         * Whether [file] belongs in a next/previous queue.
         *
         * [Format.isSupported] is the app's own answer to "is this a video Vidi
         * plays", reused rather than duplicated so one list defines that.
         * Torrents are excluded: a .torrent plays through the torrent server
         * (see TorrentServerService), which handing a file straight to mpv does
         * not go through.
         */
        private fun isQueueable(file: UniFile): Boolean =
            Format.isSupported(file) && file.extension?.lowercase() != "torrent"
    }

    /**
     * Orders names the way someone reading the folder would: digit runs compare
     * as numbers, so "Episode 2" sorts before "Episode 10" where a plain string
     * comparison puts "10" first. Everything else compares case-insensitively.
     *
     * Digit runs compare by leading-zero-trimmed length then lexically rather
     * than by parsing - an episode number long enough to overflow a Long is
     * absurd, but a hash or a date in a filename is not, and parsing would throw
     * on exactly those.
     */
    private object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = i
                    val startB = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++
                    val numA = a.substring(startA, i).trimStart('0')
                    val numB = b.substring(startB, j).trimStart('0')
                    if (numA.length != numB.length) return numA.length - numB.length
                    val byValue = numA.compareTo(numB)
                    if (byValue != 0) return byValue
                } else {
                    val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (byChar != 0) return byChar
                    i++
                    j++
                }
            }
            return when {
                i < a.length -> 1
                j < b.length -> -1
                else -> a.compareTo(b)
            }
        }
    }
}
// <-- AM (EXTERNAL_FOLDER_GRANT)
