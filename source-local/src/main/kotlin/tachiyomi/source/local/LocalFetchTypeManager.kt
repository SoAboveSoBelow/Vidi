package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.model.FetchType
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem

@Inject
@SingleIn(AppScope::class)
class LocalFetchTypeManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {
    fun find(animeUrl: String): FetchType = find(fileSystem.getFilesInAnimeDirectory(animeUrl))

    // AM (LOCAL_SOURCE_SINGLE_LISTING)
    /** [find] over an already-listed [files] - see LocalSourceFileSystem.visibleChildren. */
    fun find(files: List<UniFile>): FetchType {
        return when {
            files.any { Format.isSupported(it) } -> FetchType.Episodes
            files.any { it.isDirectory } -> FetchType.Seasons
            else -> FetchType.Episodes
        }
    }
}
