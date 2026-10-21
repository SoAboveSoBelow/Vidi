package tachiyomi.source.local.io

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences

@Inject
@SingleIn(AppScope::class)
class LocalSourceFileSystem(
    private val storageManager: StorageManager,
    // AM (EXTRA_LOCAL_FOLDERS) -->
    private val context: Context,
    private val storagePreferences: StoragePreferences,
    // <-- AM (EXTRA_LOCAL_FOLDERS)
) {

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    // AM (EXTRA_LOCAL_FOLDERS) -->
    /**
     * One entry directory the local source covers, with the url that addresses it.
     *
     * [url] is what goes into `anime.url` and is the only handle the database
     * keeps, so it has to say which folder the entry lives in. A child of the
     * configured local source directory keeps the bare directory name it has
     * always had; a granted folder IS the entry, addressed by its own
     * percent-encoded tree uri.
     *
     * A granted folder being the entry rather than a second root is what the
     * grant can actually express: the user picks the folder their video is in,
     * through a picker opened at that folder, so what the app holds afterwards is
     * a tree for the folder itself. Treating it as a root would make its videos
     * loose files at a root, which the local source does not list - the thing it
     * lists is a directory of videos, which is exactly what was granted.
     *
     * That encoding is unambiguous rather than merely unlikely: percent-encoding
     * leaves no `/` in the first segment, and [resolveUrl] decides which kind of
     * url it has by testing that segment against the REGISTERED set of granted
     * folders - a membership test, not a guess at the shape of a name. A real
     * directory called `content%3A%2F%2F...` therefore still resolves as itself.
     *
     * Appending still works, which matters: LocalSource builds a season entry's
     * url as `anime.url + "/" + seasonName`, unchanged for both kinds.
     */
    data class EntryDirectory(
        val url: String,
        val file: UniFile,
    )

    /** The granted folders the local source also covers, as tree URIs. */
    private fun extraRootUris(): List<String> =
        storagePreferences.extraLocalFolders.get().toList().sorted()

    private fun extraRoot(treeUri: String): UniFile? =
        runCatching { UniFile.fromUri(context, Uri.parse(treeUri)) }.getOrNull()
            ?.takeIf { it.isDirectory }

    /** The url that addresses the granted folder [treeUri] as an entry. */
    fun entryUrlForGrantedFolder(treeUri: String): String = Uri.encode(treeUri)

    /**
     * Every entry directory across the configured directory and granted folders.
     *
     * Used for discovery, where [getFilesInBaseDirectory] used to be. That one
     * still answers only for the configured directory, which is what its
     * remaining callers mean by it.
     */
    fun getEntryDirectories(): List<EntryDirectory> {
        val configured = getFilesInBaseDirectory()
            .map { EntryDirectory(url = it.name.orEmpty(), file = it) }
        val extra = extraRootUris().mapNotNull { treeUri ->
            val root = extraRoot(treeUri) ?: return@mapNotNull null
            EntryDirectory(url = entryUrlForGrantedFolder(treeUri), file = root)
        }
        return configured + extra
    }

    // AM (LOCAL_SOURCE_APP_FILTER) -->
    /**
     * Whether [dir] is an app group - a container of entries rather than an
     * entry itself.
     *
     * Structural, not recorded anywhere: a directory holding subdirectories and
     * no playable file is a container, and one holding playable files is an
     * entry. That answers correctly for a folder the user arranged by hand,
     * which a list of folders the app created would not, and it cannot drift
     * out of step with what is actually on disk.
     *
     * An empty directory is not a group. It is not an entry either, but calling
     * it a group would put an empty row in the filter for every stray folder.
     */
    fun isAppGroup(dir: UniFile): Boolean = isAppGroup(visibleChildren(dir))

    // AM (LOCAL_SOURCE_SINGLE_LISTING) -->
    /**
     * The children of [dir] that the local source considers at all, listed once.
     *
     * Every classification below - group or entry, which fetch type, where the
     * cover is - is a question about this same list, and listing a directory is
     * the expensive step over SAF. Callers that ask more than one of those
     * questions about a directory list it here and pass the result along, rather
     * than each lookup listing the directory again for itself.
     */
    fun visibleChildren(dir: UniFile): List<UniFile> =
        dir.listFiles().orEmpty().filterNot { it.name.orEmpty().startsWith('.') }

    /** [isAppGroup] over an already-listed [children], from [visibleChildren]. */
    fun isAppGroup(children: List<UniFile>): Boolean {
        if (children.isEmpty()) return false
        return children.none { it.isFile && Format.isSupported(it) } &&
            children.any { it.isDirectory }
    }

    /**
     * [getEntryDirectoriesInGroup] over an already-listed [children] of the group
     * addressed by [url], from [visibleChildren] - the group itself needs no
     * resolving, because the caller already holds it.
     */
    fun entryDirectoriesInGroup(url: String, children: List<UniFile>): List<EntryDirectory> =
        children
            .filter { it.isDirectory }
            .map { EntryDirectory(url = "$url/${it.name.orEmpty()}", file = it) }
    // <-- AM (LOCAL_SOURCE_SINGLE_LISTING)

    /**
     * The entry directories inside the app group [url], each addressed by a url
     * that appends its name to the group's - the same `parent/child` form
     * LocalSource already builds season urls with, so these resolve through
     * [resolveUrl] unchanged.
     */
    fun getEntryDirectoriesInGroup(url: String): List<EntryDirectory> {
        val group = resolveUrl(url)?.takeIf { it.isDirectory } ?: return emptyList()
        return entryDirectoriesInGroup(url, visibleChildren(group))
    }
    // <-- AM (LOCAL_SOURCE_APP_FILTER)

    /**
     * The display title for [url] - the directory's own name, either kind.
     *
     * A granted folder's name comes from the folder, not from its url: the url is
     * a percent-encoded `content://` tree, which decoded is a provider path and
     * not something to show anyone. The configured directory's entries are named
     * by their url's last segment, as they always were - no decoding there, which
     * would have mangled any directory whose name contains `%` or `+`.
     */
    fun getEntryTitle(url: String): String = getEntryTitle(url, file = null)

    // AM (LOCAL_SOURCE_SINGLE_LISTING)
    /**
     * [getEntryTitle] against the directory [file] when the caller already holds
     * it, which is the only reason the url-only form has to resolve anything.
     */
    fun getEntryTitle(url: String, file: UniFile?): String {
        if (firstSegmentGrant(url) != null) {
            val name = file?.name ?: resolveUrl(url)?.name
            return name.orEmpty().ifBlank { url.substringAfterLast('/') }
        }
        return url.substringAfterLast('/')
    }

    /** The granted folder [url]'s first segment names, or null when it names none. */
    private fun firstSegmentGrant(url: String): String? {
        if (url.isBlank()) return null
        val firstSegment = url.substringBefore('/')
        val decoded = runCatching { Uri.decode(firstSegment) }.getOrNull() ?: return null
        if (decoded == firstSegment) return null
        return decoded.takeIf { it in extraRootUris() }
    }

    /**
     * The directory [url] addresses, in whichever root it belongs to.
     *
     * Every existing caller passes `anime.url` and lets this resolve it, which is
     * why extra folders need no change at those call sites.
     */
    private fun resolveUrl(url: String): UniFile? {
        if (url.isBlank()) return null
        firstSegmentGrant(url)?.let { treeUri ->
            val root = extraRoot(treeUri) ?: return null
            val relative = url.substringAfter('/', "")
            if (relative.isBlank()) return root
            return relative.split('/').fold(root as UniFile?) { dir, segment ->
                dir?.findFile(segment)
            }
        }
        return url.split('/').fold(getBaseDirectory() as UniFile?) { dir, segment ->
            dir?.findFile(segment)
        }
    }
    // <-- AM (EXTRA_LOCAL_FOLDERS)

    fun getAnimeDirectory(name: String): UniFile? {
        // AM (EXTRA_LOCAL_FOLDERS) -->
        // Was findFile(name) against the one base directory. Resolution is all
        // that changed; the url this is handed is the same one as before.
        return resolveUrl(name)?.takeIf { it.isDirectory }
        // <-- AM (EXTRA_LOCAL_FOLDERS)
    }

    fun getFilesInAnimeDirectory(name: String): List<UniFile> {
        return getAnimeDirectory(name)?.listFiles().orEmpty().toList()
    }
}
