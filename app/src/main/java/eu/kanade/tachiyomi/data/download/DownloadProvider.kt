package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.download.model.LocalSourceTarget
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.size
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.isLocal
import java.io.IOException

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<anime>/<episode>
 *
 * @param context the application context.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager,
    private val libraryPreferences: LibraryPreferences,
    // AM (FILE_SIZE) -->
    private val localFileSystem: LocalSourceFileSystem,
    // <-- AM (FILE_SIZE)
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    /**
     * Returns the download directory for an anime. For internal use only.
     *
     * @param animeTitle the title of the anime to query.
     * @param source the source of the anime.
     */
    internal fun getAnimeDir(animeTitle: String, source: AnimeSource): Result<UniFile> {
        val downloadsDir = downloadsDir
        if (downloadsDir == null) {
            logcat(LogPriority.ERROR) { "Failed to create download directory" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_download_directory)),
            )
        }

        val sourceDirName = getSourceDirName(source)
        val sourceDir = downloadsDir.createDirectory(sourceDirName)
        if (sourceDir == null) {
            val displayablePath = downloadsDir.displayablePath + "/$sourceDirName"
            logcat(LogPriority.ERROR) { "Failed to create source download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        val animeDirName = getAnimeDirName(animeTitle)
        val animeDir = sourceDir.createDirectory(animeDirName)
        if (animeDir == null) {
            val displayablePath = sourceDir.displayablePath + "/$animeDirName"
            logcat(LogPriority.ERROR) { "Failed to create anime download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        return Result.success(animeDir)
    }

    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /**
     * Returns the local source entry directory a local source download lands in,
     * creating it when this is the first video saved from that origin.
     *
     * The configured local source directory rather than a granted folder: a
     * granted folder IS one entry (see LocalSourceFileSystem.EntryDirectory), so
     * there is no "put a new entry in it", while the configured directory is
     * exactly a place entries live.
     *
     * The two levels are the entry and a season inside it - see
     * [LocalSourceTarget], which says why a saved video has both.
     */
    internal fun getLocalSourceDir(target: LocalSourceTarget): Result<UniFile> {
        val baseDir = localFileSystem.getBaseDirectory()
        if (baseDir == null) {
            logcat(LogPriority.ERROR) { "Failed to resolve local source directory" }
            return Result.failure(
                IOException(
                    context.stringResource(
                        MR.strings.storage_failed_to_create_directory,
                        context.stringResource(MR.strings.local_source),
                    ),
                ),
            )
        }

        return baseDir
            .createChildDirectory(DiskUtil.buildValidFilename(target.entryName))
            .mapCatching { entryDir ->
                entryDir.createChildDirectory(DiskUtil.buildValidFilename(target.seasonName)).getOrThrow()
            }
    }

    /**
     * Creates (or opens) the directory [name] inside this one, as a [Result]
     * carrying the same failure every other directory step here reports.
     */
    private fun UniFile.createChildDirectory(name: String): Result<UniFile> {
        val child = createDirectory(name)
        if (child == null) {
            val displayablePath = displayablePath + "/$name"
            logcat(LogPriority.ERROR) { "Failed to create local source directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }
        return Result.success(child)
    }

    /**
     * Returns the video already saved into [target] under [fileName], if any.
     *
     * Matched on the name without its extension, because what lands there is
     * whatever container the download produced and the caller asking "do I
     * already have this episode" does not know which that will be.
     */
    fun findLocalSourceFile(target: LocalSourceTarget, fileName: String): UniFile? {
        val seasonDir = localFileSystem.getBaseDirectory()
            ?.findFile(DiskUtil.buildValidFilename(target.entryName))
            ?.findFile(DiskUtil.buildValidFilename(target.seasonName))
            ?: return null
        val wanted = DiskUtil.buildValidFilename(fileName)
        return seasonDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.name?.substringBeforeLast('.') == wanted
        }
    }
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: AnimeSource): UniFile? {
        return downloadsDir?.findFile(getSourceDirName(source))
    }

    /**
     * Returns the download directory for an anime if it exists.
     *
     * @param animeTitle the title of the anime to query.
     * @param source the source of the anime.
     */
    fun findAnimeDir(animeTitle: String, source: AnimeSource): UniFile? {
        val sourceDir = findSourceDir(source)
        return sourceDir?.findFile(getAnimeDirName(animeTitle))
    }

    /**
     * Returns the download directory for an episode if it exists.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     * @param episodeUrl url of the episode to query.
     * @param animeTitle the title of the anime to query.
     * @param source the source of the episode.
     */
    fun findEpisodeDir(
        episodeName: String,
        episodeScanlator: String?,
        episodeUrl: String,
        animeTitle: String,
        source: AnimeSource,
    ): UniFile? {
        val animeDir = findAnimeDir(animeTitle, source)
        return getValidEpisodeDirNames(episodeName, episodeScanlator, episodeUrl).asSequence()
            .mapNotNull { animeDir?.findFile(it) }
            .firstOrNull()
    }

    /**
     * Returns a list of downloaded directories for the episodes that exist.
     *
     * @param episodes the episodes to query.
     * @param anime the anime of the episode.
     * @param source the source of the episode.
     */
    fun findEpisodeDirs(episodes: List<Episode>, anime: Anime, source: AnimeSource): Pair<UniFile?, List<UniFile>> {
        // AM (CUSTOM_INFORMATION) -->
        val animeDir = findAnimeDir(anime.ogTitle, source) ?: return null to emptyList()
        // <-- AM (CUSTOM_INFORMATION)
        return animeDir to episodes.mapNotNull { episode ->
            getValidEpisodeDirNames(episode.name, episode.scanlator, episode.url).asSequence()
                .mapNotNull { animeDir.findFile(it) }
                .firstOrNull()
        }
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: AnimeSource): String {
        return DiskUtil.buildValidFilename(
            source.toString(),
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    /**
     * Returns the download directory name for an anime.
     *
     * @param animeTitle the title of the anime to query.
     */
    fun getAnimeDirName(animeTitle: String): String {
        return DiskUtil.buildValidFilename(
            animeTitle,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    /**
     * Returns the episode directory name for an episode.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query.
     * @param episodeUrl url of the episode to query.
     */
    fun getEpisodeDirName(
        episodeName: String,
        episodeScanlator: String?,
        episodeUrl: String,
        disallowNonAsciiFilenames: Boolean = libraryPreferences.disallowNonAsciiFilenames.get(),
    ): String {
        var dirName = sanitizeEpisodeName(episodeName)
        if (!episodeScanlator.isNullOrBlank()) {
            dirName = episodeScanlator + "_" + dirName
        }
        // Subtract 7 bytes for hash and underscore, 4 bytes for .mkv
        dirName = DiskUtil.buildValidFilename(dirName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAsciiFilenames)
        dirName += "_" + md5(episodeUrl).take(6)
        return dirName
    }

    /**
     * Returns list of names that might have been previously used as
     * the directory name for an episode.
     * Add to this list if naming pattern ever changes.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query.
     * @param episodeUrl url of the episode to query.
     */
    private fun getLegacyEpisodeDirNames(
        episodeName: String,
        episodeScanlator: String?,
        episodeUrl: String,
    ): List<String> {
        // AY -->
        val oldEpisodeName = DiskUtil.buildValidFilename(
            when {
                episodeScanlator != null -> "${episodeScanlator}_$episodeName"
                else -> episodeName
            },
        )
        // <-- AY

        val sanitizedEpisodeName = sanitizeEpisodeName(episodeName)
        val episodeNameV1 = DiskUtil.buildValidFilename(
            when {
                !episodeScanlator.isNullOrBlank() -> "${episodeScanlator}_$sanitizedEpisodeName"
                else -> sanitizedEpisodeName
            },
        )

        // Get the filename that would be generated if the user were
        // using the other value for the disallow non-ASCII
        // filenames setting. This ensures that episodes downloaded
        // before the user changed the setting can still be found.
        val otherEpisodeDirName =
            getEpisodeDirName(
                episodeName,
                episodeScanlator,
                episodeUrl,
                !libraryPreferences.disallowNonAsciiFilenames.get(),
            )

        return buildList(3) {
            // Episode name without hash (unable to handle duplicate
            // episode names)
            // AY -->
            add(oldEpisodeName)
            // <-- AY
            add(episodeNameV1)
            add(otherEpisodeDirName)
        }
    }

    /**
     * Return the new name for the episode (in case it's empty or blank)
     *
     * @param episodeName the name of the episode
     */
    private fun sanitizeEpisodeName(episodeName: String): String {
        return episodeName.ifBlank {
            "Episode"
        }
    }

    fun isEpisodeDirNameChanged(oldEpisode: Episode, newEpisode: Episode): Boolean {
        return getEpisodeDirName(oldEpisode.name, oldEpisode.scanlator, oldEpisode.url) !=
            getEpisodeDirName(newEpisode.name, newEpisode.scanlator, newEpisode.url)
    }

    /**
     * Returns valid downloaded episode directory names.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query.
     * @param episodeUrl url of the episode to query.
     */
    fun getValidEpisodeDirNames(episodeName: String, episodeScanlator: String?, episodeUrl: String): List<String> {
        val episodeDirName = getEpisodeDirName(episodeName, episodeScanlator, episodeUrl)
        val legacyEpisodeDirNames = getLegacyEpisodeDirNames(episodeName, episodeScanlator, episodeUrl)

        return buildList {
            add(episodeDirName)
            addAll(legacyEpisodeDirNames)
        }
    }

    // AM (FILE_SIZE) -->

    /**
     * Returns an episode file size in bytes.
     * Returns null if the episode is not found in expected location
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     * @param animeTitle the title of the anime
     * @param animeSource the source of the anime
     */
    fun getEpisodeFileSize(
        episodeName: String,
        episodeUrl: String?,
        episodeScanlator: String?,
        animeTitle: String,
        animeSource: AnimeSource?,
    ): Long? {
        if (animeSource == null) return null
        return if (animeSource.isLocal()) {
            // AM (LOCAL_NESTED_ENTRY_URLS): split at the last separator, so an
            // entry inside an app folder resolves - see EpisodeLoader.
            val url = episodeUrl ?: return null
            // AM (EXTRA_LOCAL_FOLDERS)
            localFileSystem.getAnimeDirectory(url.substringBeforeLast('/'))
                ?.findFile(url.substringAfterLast('/'))
                ?.size()
        } else {
            val episodeUrl = episodeUrl ?: return null
            findEpisodeDir(episodeName, episodeScanlator, episodeUrl, animeTitle, animeSource)?.size()
        }
    }
    // <-- AM (FILE_SIZE)
}
