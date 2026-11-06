package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

@Inject
@SingleIn(AppScope::class)
class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    fun find(animeUrl: String): UniFile? {
        return find(fileSystem.getFilesInAnimeDirectory(animeUrl))
    }

    // AM (LOCAL_SOURCE_SINGLE_LISTING)
    /** [find] over an already-listed [files] - see LocalSourceFileSystem.visibleChildren. */
    fun find(files: List<UniFile>): UniFile? {
        // AM (LOCAL_SOURCE_PERF_TRACE) -->
        val t0 = System.nanoTime()
        // <-- AM
        val result = files
            // AM (LOCAL_SOURCE_NO_ISFILE_STAT) -->
            // Get all file whose names start with "cover". This used isFile,
            // and in UniFile that is a fresh provider stat query PER CHILD -
            // over hundreds of files it cost seconds (measured ~5.6 ms each
            // on SAF). !isDirectory answers the same question from the
            // listing cursor, which already fetched the flags.
            // <-- AM (LOCAL_SOURCE_NO_ISFILE_STAT)
            .filter { !it.isDirectory && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            // Get the first actual image
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
        // AM (LOCAL_SOURCE_PERF_TRACE) -->
        logcat {
            "[perf] coverManager.find over ${files.size} files: " +
                "${(System.nanoTime() - t0) / 1_000_000} ms"
        }
        // <-- AM
        return result
    }

    fun update(
        anime: SAnime,
        inputStream: InputStream,
    ): UniFile? {
        val directory = fileSystem.getAnimeDirectory(anime.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = find(anime.url) ?: directory.createFile(DEFAULT_COVER_NAME)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        anime.thumbnail_url = targetFile.uri.toString()
        return targetFile
    }
}
