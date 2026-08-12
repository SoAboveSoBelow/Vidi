package tachiyomi.source.local

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import rx.Observable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.tachiyomi.AnimeDetails
import tachiyomi.core.metadata.tachiyomi.EpisodeDetails
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.service.EpisodeRecognition
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalBackgroundManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalEpisodeThumbnailManager
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource

class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    // AY -->
    private val backgroundManager: LocalBackgroundManager,
    private val thumbnailManager: LocalEpisodeThumbnailManager,
    private val fetchTypeManager: LocalFetchTypeManager,
    // <-- AY
) : AnimeSource, UnmeteredSource {

    private val json: Json by injectLazy()

    @Suppress("PrivatePropertyName")
    private val PopularFilters = AnimeFilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = AnimeFilterList(OrderBy.Latest(context))

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularAnime(page: Int) = getSearchAnime(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchAnime(page, "", LatestFilters)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        var animeDirs = fileSystem.getFilesInBaseDirectory()
            // Filter out files that are hidden and is not a folder
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    animeDirs = if (filter.state!!.ascending) {
                        animeDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        animeDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    }
                }
                is OrderBy.Latest -> {
                    animeDirs = if (filter.state!!.ascending) {
                        animeDirs.sortedBy(UniFile::lastModified)
                    } else {
                        animeDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val animes = animeDirs
            .map { animeDir ->
                async {
                    // AY -->
                    getSAnime(animeDir.name)
                    // <-- AY
                }
            }
            .awaitAll()

        AnimesPage(animes, false)
    }

    // AY -->
    private fun getSAnime(animeDir: String?): SAnime {
        return SAnime.create().apply {
            title = animeDir.orEmpty().substringAfterLast(File.separator)
            url = animeDir.orEmpty()
            fetch_type = fetchTypeManager.find(animeDir.orEmpty())

            // Try to find the cover
            coverManager.find(animeDir.orEmpty())?.let {
                thumbnail_url = it.uri.toString()
            }
        }
    }
    // <-- AY

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = supervisorScope {
        val asyncAnime = if (fetchDetails) async { getOldAnimeDetails(anime) } else null
        val asyncSeasons = if (fetchSeasons) async { getOldSeasonList(anime) } else null
        SAnimeSeasonUpdate(asyncAnime?.await() ?: anime, asyncSeasons?.await() ?: seasons)
    }

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate = supervisorScope {
        val asyncAnime = if (fetchDetails) async { getOldAnimeDetails(anime) } else null
        val asyncEpisodes = if (fetchEpisodes) async { getOldEpisodeList(anime) } else null
        SAnimeEpisodeUpdate(asyncAnime?.await() ?: anime, asyncEpisodes?.await() ?: episodes)
    }

    // AM (CUSTOM_INFORMATION) -->
    fun updateAnimeInfo(anime: SAnime) {
        val directory = fileSystem.getAnimeDirectory(anime.url) ?: return
        val existingFileName = directory.listFiles()?.find {
            it.extension == "json" && it.nameWithoutExtension == "details"
        }?.name
        val file = directory.createFile(existingFileName ?: "info.json") ?: return
        file.openOutputStream().use {
            json.encodeToStream(anime.toJson(), it)
        }
    }

    private fun SAnime.toJson(): AnimeDetails {
        return AnimeDetails(title, author, artist, description, genre?.split(", "), status)
    }
    // <-- AM (CUSTOM_INFORMATION)

    // Anime details related
    private suspend fun getOldAnimeDetails(anime: SAnime): SAnime = withIOContext {
        coverManager.find(anime.url)?.let {
            anime.thumbnail_url = it.uri.toString()
        }

        // AY -->
        backgroundManager.find(anime.url)?.let {
            anime.background_url = it.uri.toString()
        }
        // <-- AY

        // Augment anime details based on metadata files
        try {
            val animeDirFiles = fileSystem.getFilesInAnimeDirectory(anime.url)

            animeDirFiles
                .firstOrNull { it.extension == "json" && it.nameWithoutExtension == "details" }
                ?.let { file ->
                    json.decodeFromStream<AnimeDetails>(file.openInputStream()).run {
                        title?.let { anime.title = it }
                        author?.let { anime.author = it }
                        artist?.let { anime.artist = it }
                        description?.let { anime.description = it }
                        genre?.let { anime.genre = it.joinToString() }
                        status?.let { anime.status = it }
                    }
                }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting anime details from local metadata for ${anime.title}" }
        }

        return@withIOContext anime
    }

    // AY -->
    // Seasons
    private suspend fun getOldSeasonList(anime: SAnime): List<SAnime> = withIOContext {
        val animeDirs = fileSystem.getFilesInAnimeDirectory(anime.url)
            // Filter out files that are hidden and is not a folder
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .distinctBy { it.name }

        animeDirs
            .map { animeDir ->
                async {
                    val url = animeDir.name?.let { season ->
                        buildString {
                            append(anime.url)
                            append(File.separator)
                            append(season)
                        }
                    }
                    getSAnime(url)
                }
            }
            .awaitAll()
            .toList()
    }
    // <-- AY

    // Episodes
    // AM (PARTIAL_EPISODE_SYNC) -->
    // Upstream's original implementation extracted a thumbnail per episode inline here,
    // which for a folder with hundreds of episodes could stall the whole scan. This now
    // just scans (see scanEpisodeFiles below) and leaves thumbnails empty; the caller
    // (UpdateAnimeFromRemote) fills them in progressively after the episode list is synced.
    private suspend fun getOldEpisodeList(anime: SAnime): List<SEpisode> = withIOContext {
        val episodes = scanEpisodeFiles(anime)
    // <-- AM (PARTIAL_EPISODE_SYNC)

        // Generate the cover from the first episode found if not available
        // AY -->
        if (anime.thumbnail_url.isNullOrBlank() || coverManager.find(anime.url) == null) {
            // <-- AY
            try {
                episodes.lastOrNull()?.let { episode ->
                    // AY -->
                    val tempFileSuffix = anime.title + DEFAULT_COVER_NAME
                    val updateCover: (InputStream) -> Unit = { coverManager.update(anime, it) }
                    updateImageFromVideo(episode, anime, tempFileSuffix, updateCover)
                    // <-- AY
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Couldn't extract cover from video: $e" }
            }
        }

        // AY -->
        // Generate the background from the first episode found if not available
        if (anime.background_url == null || backgroundManager.find(anime.url) == null) {
            try {
                episodes.lastOrNull()?.let { episode ->
                    val tempFileSuffix = anime.title + DEFAULT_BACKGROUND_NAME
                    val updateBackground: (InputStream) -> Unit = { backgroundManager.update(anime, it) }
                    updateImageFromVideo(episode, anime, tempFileSuffix, updateBackground)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Couldn't extract background from video: $e" }
            }
        }
        // <-- AY

        episodes
    }

    // AM (PARTIAL_EPISODE_SYNC) -->

    /**
     * Scans the anime directory for episode files and builds the episode list.
     * Deliberately does not touch video frames - this is the part of episode
     * listing that stays fast no matter how many episodes are in the folder,
     * so it's kept separate from thumbnail generation, which doesn't.
     */
    suspend fun scanEpisodeFiles(anime: SAnime): List<SEpisode> = withIOContext {
        val episodesData = fileSystem.getFilesInAnimeDirectory(anime.url)
            .firstOrNull {
                it.extension == "json" && it.nameWithoutExtension == "episodes"
            }?.let { file ->
                json.decodeFromStream<List<EpisodeDetails>>(file.openInputStream())
            }

        // AM (LOCAL_THUMBNAIL_LOOKUP) -->
        // Looking up each episode's thumbnail one at a time means listing the thumbnail
        // folder(s) fresh per episode - fine for one lookup, but an O(n) directory listing
        // for each of n episodes adds up fast on a big folder. Doing it once up front here
        // turns that into a single listing plus an O(1) map lookup per episode below.
        val existingThumbnails = thumbnailManager.findExistingBatch(anime.url)
        // <-- AM (LOCAL_THUMBNAIL_LOOKUP)

        fileSystem.getFilesInAnimeDirectory(anime.url)
            // Only keep supported formats
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter { Format.isSupported(it) }
            .map { episodeFile ->
                SEpisode.create().apply {
                    url = "${anime.url}/${episodeFile.name}"
                    name = episodeFile.nameWithoutExtension.orEmpty()
                    date_upload = episodeFile.lastModified()

                    val episodeNumber = EpisodeRecognition
                        .parseEpisodeNumber(anime.title, this.name, this.episode_number.toDouble())
                        .toFloat()
                    episode_number = episodeNumber

                    // Overwrite data from episodes.json file
                    episodesData?.also { dataList ->
                        dataList.firstOrNull { it.episodeNumber.equalsTo(episodeNumber) }?.also { data ->
                            data.name?.also { name = it }
                            data.dateUpload?.also { date_upload = parseDate(it) }
                            scanlator = data.scanlator
                            summary = data.summary
                        }
                    }

                    // AM (LOCAL_THUMBNAIL_LOOKUP) -->
                    // A manually-supplied "{episode name}-thumbnail.jpg" always wins and
                    // skips extraction entirely.
                    existingThumbnails[thumbnailManager.batchKeyFor(this.name)]?.let {
                        this.preview_url = it.uri.toString()
                    }
                    // <-- AM (LOCAL_THUMBNAIL_LOOKUP)
                }
            }
            .sortedWith { e1, e2 ->
                e2.name.compareToCaseInsensitiveNaturalOrder(e1.name)
            }
    }

    /**
     * Generates thumbnails for [episodes] that don't already have one, [chunkSize] at a
     * time, calling [onChunkGenerated] after each chunk finishes. Thumbnails are generated
     * regardless of the Simplified/Preview display setting, since they're still used by the
     * background playback notification.
     *
     * Large folders can hold hundreds of episodes, and each one costs an ffprobe + ffmpeg
     * pass, so generating every thumbnail before returning anything (the old behavior of
     * this function) meant a sync of a big folder could take long enough to never finish -
     * and nothing showed up, not even the episodes that were already done. Chunking lets a
     * caller persist and display progress as it goes instead of waiting on the whole folder.
     */
    suspend fun generateMissingThumbnails(
        anime: SAnime,
        episodes: List<SEpisode>,
        chunkSize: Int = THUMBNAIL_CHUNK_SIZE,
        onChunkGenerated: suspend (List<SEpisode>) -> Unit = {},
    ) = withIOContext {
        episodes
            .filter { it.preview_url == null }
            .chunked(chunkSize)
            .forEach { chunk ->
                chunk.forEach { episode ->
                    try {
                        val tempFileSuffix = anime.title + episode.name + DEFAULT_THUMBNAIL_NAME
                        val updateThumbnail: (InputStream) -> Unit =
                            { thumbnailManager.update(anime, episode, it) }
                        updateImageFromVideo(episode, anime, tempFileSuffix, updateThumbnail)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "Couldn't extract thumbnail from video: $e" }
                    }
                }
                onChunkGenerated(chunk)
            }
    }
    // <-- AM (PARTIAL_EPISODE_SYNC)

    // AY -->
    private fun parseDate(isoDate: String): Long {
        return dateFormat.parse(isoDate)?.time ?: 0L
    }

    private fun Float.equalsTo(other: Float): Boolean {
        return abs(this - other) < 0.0001
    }
    // <-- AY

    // Filters
    override fun getFilterList() = AnimeFilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException("Unused")

    // AY -->
    private fun updateImageFromVideo(
        episode: SEpisode,
        anime: SAnime,
        tempFileSuffix: String,
        updateImage: (InputStream) -> Unit,
    ) {
        val tempFile = File.createTempFile(
            "tmp_",
            tempFileSuffix,
        )
        val outFile = tempFile.path

        val episodeName = episode.url.split('/', limit = 2).last()
        val animeDir = fileSystem.getAnimeDirectory(anime.url)!!
        val episodeFile = animeDir.findFile(episodeName)!!
        val episodeFilename = { episodeFile.toFFmpegString(context) }

        val ffProbe = FFprobeKit.execute(
            "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"${episodeFilename()}\"",
        )
        val duration = ffProbe.allLogsAsString.trim().toFloat()
        val second = duration.toInt() / 2

        FFmpegKit.execute(
            "-ss $second -i \"${episodeFilename()}\" -frames:v 1 -update true \"$outFile\" -y",
        )

        if (tempFile.length() > 0L) {
            updateImage(tempFile.inputStream())
        }
    }
    // <-- AY

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.org/help/guides/local-anime/"

        // AY -->
        private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()) }
        private const val DEFAULT_COVER_NAME = "cover.jpg"
        private const val DEFAULT_BACKGROUND_NAME = "background.jpg"
        private const val DEFAULT_THUMBNAIL_NAME = "thumbnail.jpg"
        // <-- AY

        // AM (PARTIAL_EPISODE_SYNC) -->
        private const val THUMBNAIL_CHUNK_SIZE = 10
        // <-- AM (PARTIAL_EPISODE_SYNC)

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }
}

fun Anime.isLocal(): Boolean = source == LocalSource.ID

fun AnimeSource.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID
