package aniyomi.source.js

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * What a project declares about itself. Everything the host needs to present it
 * as a source, and nothing that lets it claim an identity.
 */
@Serializable
class JsProjectManifest(
    val name: String,
    val lang: String = "en",
    val baseUrl: String = "",
    val supportsLatest: Boolean = false,
    val nsfw: Boolean = false,
    val apiVersion: Int = 1,
    /**
     * Draft projects are never registered as sources. That is the gate: a
     * half-written project cannot be reached by a library update, so it cannot
     * write malformed entries into the database. Activation is what grants it
     * access, and it is the user's explicit act.
     */
    val active: Boolean = false,
)

/**
 * One directory under the project root, readable or not.
 *
 * A store that silently drops what it cannot parse is fine for a source list and
 * wrong for a dev environment: the person who wrote the broken project would
 * watch it vanish with only a logcat line to explain it. The editor needs to see
 * the failure, so the reason travels with the slug.
 */
sealed interface JsProjectEntry {
    val slug: String

    class Readable(val project: JsProject) : JsProjectEntry {
        override val slug: String get() = project.slug
    }

    class Unreadable(override val slug: String, val reason: String) : JsProjectEntry
}

class JsProject(
    val slug: String,
    val manifest: JsProjectManifest,
    val script: String,
) {
    val id: Long get() = JsProjectStore.idForSlug(slug)
}

/**
 * Projects on disk, one directory each, under the app's own private storage.
 *
 * ```
 * filesDir/js-projects/<slug>/project.json   the manifest
 * filesDir/js-projects/<slug>/project.js     the code
 * ```
 *
 * Private storage rather than `/sdcard/.Aniyomi` deliberately: no SAF grant, no
 * scoped-storage dance, and nothing else on the device can edit a project behind
 * the app's back. Export is how a project leaves.
 *
 * ## Identity
 *
 * The source id is derived from the **slug**, not from the manifest, and not by
 * the project. `AnimeHttpSource.generateId` masks with `Long.MAX_VALUE`, so every
 * extension id is non-negative; setting the sign bit puts projects in a namespace
 * extensions can never enter, and `-1` (merged sources) and `0` (local) are the
 * only reserved values to dodge.
 *
 * Deriving from the slug rather than storing an id means a lost or hand-edited
 * manifest cannot orphan a library: the same directory always produces the same
 * source. The consequence is that renaming the *directory* changes identity and
 * detaches library entries, while renaming the manifest's `name` is cosmetic.
 */
class JsProjectStore(context: Context) {

    /** A project as bytes on disk, for backup and export. */
    class RawProject(val slug: String, val manifest: String, val script: String)

    /** One past version of a project's script. */
    class JsRevision(val slug: String, val atMillis: Long, val sizeBytes: Long)


    private val root = File(context.filesDir, DIRECTORY)

    /** Every directory, with a reason attached to the ones that did not load. */
    fun listAll(): List<JsProjectEntry> {
        val directories = root.listFiles { file: File -> file.isDirectory }.orEmpty()
        return directories.map { directory -> entryFor(directory) }.sortedBy { it.slug }
    }

    fun list(): List<JsProject> = listAll()
        .filterIsInstance<JsProjectEntry.Readable>()
        .map { it.project }
        .sortedBy { it.manifest.name }

    fun read(slug: String): JsProject? =
        (entryFor(File(root, slug)) as? JsProjectEntry.Readable)?.project

    /** Only active projects become sources - see [JsProjectManifest.active]. */
    fun activeSources(network: NetworkHelper): List<JsProjectSource> =
        list().filter { it.manifest.active }.map { project -> sourceFor(project, network) }

    fun sourceFor(project: JsProject, network: NetworkHelper): JsProjectSource = JsProjectSource(
        id = project.id,
        name = project.manifest.name,
        lang = project.manifest.lang,
        supportsLatest = project.manifest.supportsLatest,
        script = project.script,
        baseUrl = project.manifest.baseUrl,
        networkHelper = network,
    )

    fun save(slug: String, manifest: JsProjectManifest, script: String): JsProject {
        require(slug.matches(SLUG_PATTERN)) { "Project slug must match $SLUG_PATTERN, got '$slug'" }
        val directory = File(root, slug).apply { mkdirs() }
        File(directory, MANIFEST_FILE).writeText(jsJson.encodeToString(manifest))
        File(directory, SCRIPT_FILE).writeText(script)
        return JsProject(slug, manifest, script)
    }

    fun delete(slug: String): Boolean = File(root, slug).deleteRecursively()

    fun exists(slug: String): Boolean = File(root, slug).isDirectory

    /**
     * Every project directory read verbatim, without parsing.
     *
     * Backups use this rather than [listAll] so a project whose manifest no
     * longer parses still travels: the user's broken work is exactly what they
     * would least want a backup to drop.
     */
    fun rawEntries(): List<RawProject> {
        val directories = root.listFiles { file: File -> file.isDirectory }.orEmpty()
        return directories.mapNotNull { directory ->
            val manifest = File(directory, MANIFEST_FILE).takeIf { it.isFile }?.readText()
            val script = File(directory, SCRIPT_FILE).takeIf { it.isFile }?.readText()
            if (manifest == null || script == null) {
                null
            } else {
                RawProject(directory.name, manifest, script)
            }
        }.sortedBy { it.slug }
    }

    /** Writes both files as given, with no parsing or normalization. */
    fun writeRaw(slug: String, manifest: String, script: String) {
        require(slug.matches(SLUG_PATTERN)) { "Project slug must match $SLUG_PATTERN, got '$slug'" }
        val directory = File(root, slug).apply { mkdirs() }
        File(directory, MANIFEST_FILE).writeText(manifest)
        File(directory, SCRIPT_FILE).writeText(script)
    }

    /**
     * The script file alone, with no manifest involved.
     *
     * [read] cannot serve the editor: it returns null when the *manifest* does
     * not parse, and an editor that treated that as an empty script would
     * autosave the emptiness over the person's code - destroying the work in
     * exactly the case they opened the editor to repair. The two files are
     * independent on disk and are read independently here.
     */
    fun readScript(slug: String): String? =
        File(File(root, slug), SCRIPT_FILE).takeIf { it.isFile }?.readText()

    /** The manifest as written, for repairing one that does not parse. */
    fun readManifestText(slug: String): String? =
        File(File(root, slug), MANIFEST_FILE).takeIf { it.isFile }?.readText()

    /**
     * Writes the script and nothing else.
     *
     * The editor autosaves on a debounce, so this is the hottest write in the
     * feature and must not touch the manifest: re-encoding it on every keystroke
     * would mean a crash mid-write could leave a project with a truncated
     * manifest and an intact script, which is the one combination
     * [entryFor] cannot recover from.
     */
    fun writeScript(slug: String, script: String) {
        require(slug.matches(SLUG_PATTERN)) { "Project slug must match $SLUG_PATTERN, got '$slug'" }
        val directory = File(root, slug).apply { mkdirs() }
        File(directory, SCRIPT_FILE).writeText(script)
    }

    fun writeManifest(slug: String, manifest: JsProjectManifest) {
        require(slug.matches(SLUG_PATTERN)) { "Project slug must match $SLUG_PATTERN, got '$slug'" }
        val directory = File(root, slug).apply { mkdirs() }
        File(directory, MANIFEST_FILE).writeText(jsJson.encodeToString(manifest))
    }

    /**
     * Keeps a copy of [script] under `revisions/`, newest wins, oldest pruned.
     *
     * Deliberately not called from [writeScript]: autosave fires every few
     * hundred milliseconds and a revision per keystroke would be noise with the
     * useful versions pruned out of it. Revisions are taken at the points a
     * person would recognise as a version - opening a project, running it,
     * leaving the editor - and an identical script is never stored twice, so
     * those points are cheap to call from.
     *
     * Returns true when a revision was actually written.
     */
    fun saveRevision(slug: String, script: String, atMillis: Long = System.currentTimeMillis()): Boolean {
        val directory = File(root, slug)
        if (!directory.isDirectory) return false

        val existing = revisions(slug)
        if (existing.firstOrNull()?.let { readRevision(slug, it.atMillis) } == script) return false

        val revisionsDirectory = File(directory, REVISIONS_DIRECTORY).apply { mkdirs() }
        File(revisionsDirectory, "$atMillis$REVISION_SUFFIX").writeText(script)

        // Prune by name rather than by lastModified: the name is the timestamp
        // we chose, while mtime is whatever the filesystem decided.
        revisions(slug).drop(MAX_REVISIONS).forEach { stale ->
            File(revisionsDirectory, "${stale.atMillis}$REVISION_SUFFIX").delete()
        }
        return true
    }

    /** Newest first. */
    fun revisions(slug: String): List<JsRevision> {
        val revisionsDirectory = File(File(root, slug), REVISIONS_DIRECTORY)
        val files = revisionsDirectory.listFiles { file: File ->
            file.isFile && file.name.endsWith(REVISION_SUFFIX)
        }.orEmpty()
        return files.mapNotNull { file ->
            val at = file.name.removeSuffix(REVISION_SUFFIX).toLongOrNull() ?: return@mapNotNull null
            JsRevision(slug, at, file.length())
        }.sortedByDescending { it.atMillis }
    }

    fun readRevision(slug: String, atMillis: Long): String? =
        File(File(File(root, slug), REVISIONS_DIRECTORY), "$atMillis$REVISION_SUFFIX")
            .takeIf { it.isFile }
            ?.readText()

    fun directoryOf(slug: String): File = File(root, slug)

    fun rootPath(): String = root.absolutePath

    private fun entryFor(directory: File): JsProjectEntry {
        val slug = directory.name
        val manifestFile = File(directory, MANIFEST_FILE)
        val scriptFile = File(directory, SCRIPT_FILE)

        if (!manifestFile.isFile) return unreadable(slug, "no $MANIFEST_FILE")
        if (!scriptFile.isFile) return unreadable(slug, "no $SCRIPT_FILE")

        return try {
            val manifest = jsJson.decodeFromString<JsProjectManifest>(manifestFile.readText())
            if (manifest.apiVersion != SUPPORTED_API_VERSION) {
                // Refusing an unknown version is what makes the format changeable
                // later; a best-effort read would strand both sides.
                unreadable(
                    slug,
                    "apiVersion ${manifest.apiVersion} needs a newer app (this one reads $SUPPORTED_API_VERSION)",
                )
            } else {
                JsProjectEntry.Readable(JsProject(slug, manifest, scriptFile.readText()))
            }
        } catch (e: Exception) {
            unreadable(slug, "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun unreadable(slug: String, reason: String): JsProjectEntry.Unreadable {
        logcat(LogPriority.WARN) { "Project '$slug' is unreadable: $reason" }
        return JsProjectEntry.Unreadable(slug, reason)
    }

    companion object {
        const val DIRECTORY = "js-projects"
        const val MANIFEST_FILE = "project.json"
        const val SCRIPT_FILE = "project.js"
        const val SUPPORTED_API_VERSION = 1

        /**
         * Local history, not part of a project. [rawEntries] reads only the
         * manifest and the script, so revisions stay out of backups and exports
         * on purpose - a backup carries the project, not the undo stack.
         */
        const val REVISIONS_DIRECTORY = "revisions"
        const val REVISION_SUFFIX = ".js"
        const val MAX_REVISIONS = 10

        private val SLUG_PATTERN = Regex("[a-z0-9][a-z0-9-_]{0,63}")

        /** Reserved by merged sources; the only negative id that is taken. */
        private const val MERGED_SOURCE_ID = -1L

        /**
         * Stable, negative, and derived only from the directory name.
         */
        fun idForSlug(slug: String): Long {
            val bytes = MessageDigest.getInstance("MD5").digest(slug.toByteArray())
            val raw = (0..7)
                .map { index -> bytes[index].toLong() and 0xff shl 8 * (7 - index) }
                .reduce(Long::or) or Long.MIN_VALUE
            return if (raw == MERGED_SOURCE_ID) raw + 1 else raw
        }
    }
}
