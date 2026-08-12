// AY -->
package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

private const val DEFAULT_THUMBNAIL_NAME = "thumbnail.jpg"
private const val DEFAULT_THUMBNAIL_SUFFIX = "thumbnail"

// AM (THUMBNAILS_SUBFOLDER) -->
private const val THUMBNAILS_DIR_NAME = ".thumbnails"
// <-- AM (THUMBNAILS_SUBFOLDER)

class LocalEpisodeThumbnailManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    // AM (THUMBNAILS_SUBFOLDER) -->
    /**
     * The ".thumbnails" subfolder inside the anime's directory, created on demand.
     * Keeping generated thumbnails out of the anime's root folder means a big series
     * doesn't end up with hundreds of loose "*-thumbnail.jpg" files sitting next to
     * the episodes.
     *
     * The first time this folder is created for an anime, any legacy thumbnails
     * already sitting loose in the root get folded into it - see [migrateLegacyThumbnails].
     */
    private fun getThumbnailsDirectory(animeDir: UniFile): UniFile? {
        val alreadyExisted = animeDir.findFile(THUMBNAILS_DIR_NAME) != null
        val thumbnailsDir = animeDir.createDirectory(THUMBNAILS_DIR_NAME) ?: return null

        if (!alreadyExisted) {
            migrateLegacyThumbnails(animeDir, thumbnailsDir)
        }

        return thumbnailsDir
    }

    /**
     * Moves any "{episode name}-thumbnail.{ext}" files out of the anime's root folder
     * and into [thumbnailsDir]. The naming scheme already ties each file to its episode,
     * so no episode/video matching logic is needed - it's a plain rename by filename.
     */
    private fun migrateLegacyThumbnails(animeDir: UniFile, thumbnailsDir: UniFile) {
        animeDir.listFiles().orEmpty()
            .filter {
                it.isFile &&
                    it.nameWithoutExtension.orEmpty().endsWith("-$DEFAULT_THUMBNAIL_SUFFIX", ignoreCase = true) &&
                    ImageUtil.isImage(it.name) { it.openInputStream() }
            }
            .forEach { moveInto(it, thumbnailsDir) }
    }

    /**
     * Copies [file] into [targetDir] under its own name - overwriting anything already
     * there with that name - then deletes [file]. Used both for the one-time legacy
     * migration and for folding in a manually-dropped override (see [find]).
     */
    private fun moveInto(file: UniFile, targetDir: UniFile): UniFile? {
        return try {
            val target = targetDir.createFile(file.name!!) ?: return null
            file.openInputStream().use { input ->
                target.openOutputStream().use { output -> input.copyTo(output) }
            }
            file.delete()
            target
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Couldn't move thumbnail ${file.name}: $e" }
            null
        }
    }

    private fun findIn(directory: UniFile?, fileName: String): UniFile? {
        return directory?.listFiles().orEmpty()
            // Get all file whose names contain the episode name and the word 'thumbnail'
            .filter { it.isFile && it.nameWithoutExtension.equals(fileName, ignoreCase = true) }
            // Get the first actual image
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
    }
    // <-- AM (THUMBNAILS_SUBFOLDER)

    fun find(animeUrl: String, fileName: String): UniFile? {
        val animeDir = fileSystem.getAnimeDirectory(animeUrl) ?: return null
        // AM (THUMBNAILS_SUBFOLDER) -->
        val thumbnailsDir = getThumbnailsDirectory(animeDir)

        // A file sitting directly in the anime's root at this point is either a legacy
        // one this migration hasn't reached yet, or - just as likely - a thumbnail a
        // user just dropped in to override the current one. Either way it wins: fold
        // it into .thumbnails, replacing whatever's already there under that name, and
        // clean up the root copy so it isn't reprocessed (or re-matched) on every scan.
        findIn(animeDir, fileName)?.let { override ->
            return thumbnailsDir?.let { moveInto(override, it) } ?: override
        }

        return findIn(thumbnailsDir, fileName)
        // <-- AM (THUMBNAILS_SUBFOLDER)
    }

    // AM (PARTIAL_EPISODE_SYNC) -->

    /**
     * Batched version of a per-episode "manually-supplied thumbnail" lookup, for scanning
     * an entire episode list at once. Looking up each episode individually means listing
     * a directory fresh per lookup - fine for one episode but an O(n) directory listing
     * for each of n episodes adds up fast on a big folder. This lists the root folder and
     * .thumbnails exactly once, folds in any root overrides the same way [find] does, and
     * returns a lookup keyed by lowercased "{episode name}-thumbnail" so callers can do an
     * O(1) map lookup per episode instead.
     */
    fun findExistingBatch(animeUrl: String): Map<String, UniFile> {
        val animeDir = fileSystem.getAnimeDirectory(animeUrl) ?: return emptyMap()
        val thumbnailsDir = getThumbnailsDirectory(animeDir)

        val result = mutableMapOf<String, UniFile>()

        fun keyOf(file: UniFile) = file.nameWithoutExtension.orEmpty().lowercase()
        fun isThumbnailFile(file: UniFile): Boolean {
            return file.isFile &&
                keyOf(file).endsWith("-$DEFAULT_THUMBNAIL_SUFFIX") &&
                ImageUtil.isImage(file.name) { file.openInputStream() }
        }

        // Root overrides win, same priority as find() - fold each into .thumbnails.
        // Only files matching the "{episode name}-thumbnail" pattern qualify, so this
        // doesn't sweep up unrelated images like cover.jpg or background.jpg.
        animeDir.listFiles().orEmpty()
            .filter(::isThumbnailFile)
            .forEach { file ->
                val target = thumbnailsDir?.let { moveInto(file, it) } ?: file
                result[keyOf(file)] = target
            }

        thumbnailsDir?.listFiles().orEmpty()
            .filter(::isThumbnailFile)
            .forEach { file ->
                result.getOrPut(keyOf(file)) { file }
            }

        return result
    }

    /** The lookup key [findExistingBatch] uses for a manually-provided thumbnail of [episodeName]. */
    fun batchKeyFor(episodeName: String): String {
        return "$episodeName-$DEFAULT_THUMBNAIL_SUFFIX".lowercase()
    }
    // <-- AM (PARTIAL_EPISODE_SYNC)

    fun update(anime: SAnime, episode: SEpisode, inputStream: InputStream): UniFile? {
        val animeDir = fileSystem.getAnimeDirectory(anime.url)
        if (animeDir == null) {
            inputStream.close()
            return null
        }

        // AM (THUMBNAILS_SUBFOLDER) -->
        val directory = getThumbnailsDirectory(animeDir)
        // <-- AM (THUMBNAILS_SUBFOLDER)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val fileName = "${episode.name}-$DEFAULT_THUMBNAIL_NAME"
        // AM (LOCAL_THUMBNAIL_LOOKUP) -->
        // find() compares against the name with the extension stripped, so the
        // lookup key must be passed without one - otherwise it can never match
        // and a new file gets created every time instead of overwriting.
        // AM (THUMBNAILS_SUBFOLDER) -->
        // find() already folds a root override into .thumbnails, so by the time this
        // runs there's nothing left to check outside .thumbnails - just look here.
        val targetFile = findIn(directory, "${episode.name}-$DEFAULT_THUMBNAIL_SUFFIX")
            ?: directory.createFile(fileName)!!
        // <-- AM (THUMBNAILS_SUBFOLDER)
        // <-- AM (LOCAL_THUMBNAIL_LOOKUP)

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        episode.preview_url = targetFile.uri.toString()
        return targetFile
    }
}

// <-- AY
