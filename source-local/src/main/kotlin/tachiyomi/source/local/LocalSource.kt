package tachiyomi.source.local

import android.content.Context
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.SnapshotInvalidatingSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
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
import tachiyomi.source.local.filter.AppGroup
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalBackgroundManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalEpisodeThumbnailManager
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource

@Inject
@SingleIn(AppScope::class)
class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    private val json: Json,
    // AY -->
    private val backgroundManager: LocalBackgroundManager,
    private val thumbnailManager: LocalEpisodeThumbnailManager,
    private val fetchTypeManager: LocalFetchTypeManager,
    // <-- AY
) : AnimeSource, UnmeteredSource, SnapshotInvalidatingSource {

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
        // AM (EXTRA_LOCAL_FOLDERS) -->
        // Was getFilesInBaseDirectory(), which only ever saw the configured
        // directory. getEntryDirectories() also covers folders the user has
        // granted, each child carrying the url that addresses it - so a granted
        // folder's videos become ordinary local entries and everything that
        // already works for a local entry works for them.
        //
        // distinctBy is on the url rather than the name now: two roots can hold a
        // folder of the same name, and those are different entries, not a
        // duplicate to collapse.
        // AM (LOCAL_SOURCE_APP_FILTER) -->
        // An app group is a container, never an entry: `Chrome` holds no video
        // of its own, and listing it would put a row in browse that opens on
        // nothing. What belongs in the list is the SITES inside it, so a group
        // is always replaced by its children - the list is sites and ordinary
        // entries, never groups.
        //
        // The filter then only narrows which of those are shown: one app's
        // sites for a named group, and the entries belonging to no app for
        // "Local".
        // AM (LOCAL_SOURCE_SINGLE_LISTING) -->
        // Every directory this pass touches is listed exactly once, here, and
        // the children travel with it from then on.
        //
        // They used to be listed over and over: isAppGroup listed a directory
        // to classify it, getEntryDirectoriesInGroup threw away the UniFile it
        // had just been handed and resolved the group's url again to list it a
        // second time, and then getSAnime resolved each entry's url twice more
        // - once for the fetch type and once for the cover - listing it both
        // times. Resolving is itself a listing per url segment, because
        // UniFile.findFile enumerates the directory it searches, so an entry
        // inside an app group re-enumerated that group on every one of those
        // lookups. For N top-level directories holding M entries that is on the
        // order of 4N + 4M listings where N + M will do.
        //
        // Nothing was cached: the listing lived for this one call. The
        // LOCAL_SOURCE_SNAPSHOT notes below are the deliberate exception.
        // <-- AM (LOCAL_SOURCE_SINGLE_LISTING)
        // <-- AM (LOCAL_SOURCE_APP_FILTER)

        // AM (LOCAL_SOURCE_SNAPSHOT) -->
        // Browse is served from a persisted snapshot, and disk is a background
        // refresher - the structure a file manager uses, and the only one that
        // makes a storage-backed source feel preloaded:
        //
        // - getSearchAnime answers from the snapshot: filtering, sorting and
        //   cover/type lookup are pure memory, so every call returns in
        //   milliseconds no matter how many folders exist or how slow the
        //   provider is.
        // - Opening the source (or the filter list) kicks a background
        //   re-scan - the same parallel scan as before, now off the critical
        //   path. When the scan finds changes, the snapshot is rewritten and
        //   [snapshotRefreshes] emits, which invalidates the paging source so
        //   the grid moves to the fresh data on its own.
        // - The very first scan after install has no snapshot to serve, so it
        //   reads disk synchronously once; every call afterwards is served.
        //
        // Contents of an entry folder are never part of the snapshot beyond
        // what browse needs to SHOW the entry (title, cover, fetch type,
        // mtime) - the episode list is still read when the entry is opened,
        // as it always was.
        val snapshot = loadSnapshot()
        if (snapshot != null) {
            refreshSnapshotInBackground()
            AnimesPage(snapshotAnimes(snapshot, query, filters), false)
        } else {
            val scanned = scanNow()
            storeSnapshot(scanned)
            AnimesPage(snapshotAnimes(scanned, query, filters), false)
        }
        // <-- AM (LOCAL_SOURCE_SNAPSHOT)
    }

    // AM (LOCAL_SOURCE_SNAPSHOT) -->

    /**
     * The persisted browse snapshot - see the note in [getSearchAnime].
     * Rewritten by every completed background refresh; disk remains the
     * source of truth and nothing user-authored lives only in the snapshot.
     */
    @Serializable
    private data class BrowseSnapshot(
        val entries: List<Entry>,
        val groups: List<Group>,
        val takenAtMillis: Long,
    ) {
        /** One browsable entry: everything the grid needs, nothing more. */
        @Serializable
        data class Entry(
            val url: String,
            val title: String,
            val fetchType: FetchType,
            val coverUri: String? = null,
            val lastModified: Long = 0L,
        )

        /** One app group, for the filter sheet. */
        @Serializable
        data class Group(val url: String, val title: String)
    }

    private fun BrowseSnapshot.Entry.toSAnime(): SAnime {
        return SAnime.create().apply {
            title = this@toSAnime.title
            url = this@toSAnime.url
            fetch_type = this@toSAnime.fetchType
            coverUri?.let { thumbnail_url = it }
        }
    }

    /** The snapshot backing [getSearchAnime], once loaded or taken. */
    @Volatile
    private var snapshot: BrowseSnapshot? = null

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshotRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val snapshotRefreshes: Flow<Unit> = _snapshotRefreshes.asSharedFlow()

    @Volatile
    private var refreshRunning = false

    @Volatile
    private var lastRefreshAtMillis = 0L

    private fun loadSnapshot(): BrowseSnapshot? {
        snapshot?.let { return it }
        val loaded = runCatching {
            context.openFileInput(SNAPSHOT_FILE).use {
                json.decodeFromStream<BrowseSnapshot>(it)
            }
        }.getOrNull()
        snapshot = loaded
        return loaded
    }

    private fun storeSnapshot(value: BrowseSnapshot) {
        snapshot = value
        // A store always follows a just-completed scan - count it as the
        // last refresh so the background worker doesn't immediately re-scan.
        lastRefreshAtMillis = System.currentTimeMillis()
        runCatching {
            context.openFileOutput(SNAPSHOT_FILE, Context.MODE_PRIVATE).use {
                json.encodeToStream(value, it)
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Couldn't persist local source snapshot" }
        }
    }

    /**
     * Re-scan disk off the critical path, at most once per
     * [REFRESH_THROTTLE_MS]. Emits [snapshotRefreshes] only when the listing
     * actually changed, so sitting on the screen never turns into a reload
     * loop.
     */
    private fun refreshSnapshotInBackground() {
        val now = System.currentTimeMillis()
        if (refreshRunning || now - lastRefreshAtMillis < REFRESH_THROTTLE_MS) return
        lastRefreshAtMillis = now
        refreshRunning = true
        refreshScope.launch {
            try {
                val fresh = scanNow()
                // Ignore the timestamp when diffing - a refresh that found
                // nothing must not invalidate the page.
                val changed = fresh.copy(takenAtMillis = 0) != snapshot?.copy(takenAtMillis = 0)
                if (changed) {
                    storeSnapshot(fresh)
                    _snapshotRefreshes.tryEmit(Unit)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Background local source refresh failed" }
            } finally {
                refreshRunning = false
            }
        }
    }

    /**
     * Apply the search/filter/sort pipeline to [snapshot] entirely in memory.
     * This is what runs on every browse call - no provider queries.
     */
    private fun snapshotAnimes(
        snapshot: BrowseSnapshot,
        query: String,
        filters: AnimeFilterList,
    ): List<SAnime> {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }
        val appFilter = filters.filterIsInstance<AppGroup>().firstOrNull()
        val selectedGroup = appFilter?.selectedGroup
        val groupPrefixes = snapshot.groups.map { "${it.url}/" }

        var entries = snapshot.entries
            .filter { entry ->
                when {
                    selectedGroup != null -> entry.url.startsWith("$selectedGroup/")
                    appFilter?.isLocalOnly == true -> groupPrefixes.none { entry.url.startsWith(it) }
                    else -> true
                }
            }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.title.contains(query, ignoreCase = true)
                } else {
                    it.lastModified >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    entries = if (filter.state!!.ascending) {
                        entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    } else {
                        entries.sortedWith(
                            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title },
                        )
                    }
                }
                is OrderBy.Latest -> {
                    entries = if (filter.state!!.ascending) {
                        entries.sortedBy { it.lastModified }
                    } else {
                        entries.sortedByDescending { it.lastModified }
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        return entries.map { it.toSAnime() }
    }

    /**
     * The one place disk is read: base directory, every top-level directory
     * (in parallel), then the app groups' children. Everything the snapshot
     * stores is derived here; nothing else in this class lists storage.
     */
    private suspend fun scanNow(): BrowseSnapshot {
        val topLevel = coroutineScope {
            fileSystem.getEntryDirectories()
                .filter { it.file.isDirectory && !it.file.name.orEmpty().startsWith('.') }
                .distinctBy { it.url }
                .map { dir ->
                    async {
                        ScannedEntry(dir, fileSystem.visibleChildren(dir.file))
                    }
                }
                .awaitAll()
        }
        val expansion = topLevel.flatMap { entry ->
            if (fileSystem.isAppGroup(entry.children)) {
                fileSystem.entryDirectoriesInGroup(entry.dir.url, entry.children).scan()
            } else {
                listOf(entry)
            }
        }
        val entries = coroutineScope {
            expansion
                .map { entry ->
                    async {
                        BrowseSnapshot.Entry(
                            url = entry.dir.url,
                            title = fileSystem.getEntryTitle(entry.dir.url, entry.dir.file),
                            fetchType = fetchTypeManager.find(entry.children),
                            coverUri = coverManager.find(entry.children)?.uri?.toString(),
                            lastModified = entry.dir.file.lastModified(),
                        )
                    }
                }
                .awaitAll()
        }
        val groups = topLevel
            .filter { fileSystem.isAppGroup(it.children) }
            .map {
                BrowseSnapshot.Group(
                    url = it.dir.url,
                    title = fileSystem.getEntryTitle(it.dir.url, it.dir.file),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return BrowseSnapshot(
            entries = entries,
            groups = groups,
            takenAtMillis = System.currentTimeMillis(),
        )
    }
    // <-- AM (LOCAL_SOURCE_SNAPSHOT)

    // AM (LOCAL_SOURCE_SINGLE_LISTING) -->
    /**
     * An entry directory together with its children, listed once - see the note
     * in [getSearchAnime]. Only [scanNow] builds these; everything displayed
     * is copied into [BrowseSnapshot] so later calls never touch the provider.
     */
    private class ScannedEntry(
        val dir: LocalSourceFileSystem.EntryDirectory,
        val children: List<UniFile>,
    )

    // AM (LOCAL_SOURCE_PARALLEL_LISTING): one listing per directory of the
    // group, all at once - see the note in [getSearchAnime].
    private suspend fun List<LocalSourceFileSystem.EntryDirectory>.scan(): List<ScannedEntry> =
        coroutineScope {
            filter { it.file.isDirectory && !it.file.name.orEmpty().startsWith('.') }
                .distinctBy { it.url }
                .map { dir -> async { ScannedEntry(dir, fileSystem.visibleChildren(dir.file)) } }
                .awaitAll()
        }
    // <-- AM (LOCAL_SOURCE_SINGLE_LISTING)

    // AY -->
    private fun getSAnime(animeDir: String?): SAnime {
        return SAnime.create().apply {
            // AM (EXTRA_LOCAL_FOLDERS) -->
            // Was substringAfterLast(separator) on the url, which is the
            // directory name for a configured-directory entry but would show the
            // percent-encoded root for a granted folder's entry. getEntryTitle()
            // is the one place that knows how to read a title out of either.
            title = fileSystem.getEntryTitle(animeDir.orEmpty())
            // <-- AM (EXTRA_LOCAL_FOLDERS)
            url = animeDir.orEmpty()
            fetch_type = fetchTypeManager.find(animeDir.orEmpty())

            // Try to find the cover
            coverManager.find(animeDir.orEmpty())?.let {
                thumbnail_url = it.uri.toString()
            }
        }
    }

    override val supportsRelatedAnime = false

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()
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
        // AM (LOCAL_COVER_ON_FIRST_SCAN) -->
        // Episodes before details when both are wanted, rather than in
        // parallel. Scanning the episodes is what WRITES the cover for an entry
        // that has none (see getOldEpisodeList), and reading the details is
        // what records where it was written. Run together, the details read
        // almost always looks before the file exists, so a newly added entry
        // came back with a null thumbnail_url and stayed uncovered until some
        // later refresh happened to scan it again.
        //
        // Only the both-wanted case is ordered; either one alone is unchanged,
        // and nothing else about the scan is serialised.
        val newEpisodes = if (fetchEpisodes) getOldEpisodeList(anime) else null
        val asyncAnime = if (fetchDetails) async { getOldAnimeDetails(anime) } else null
        SAnimeEpisodeUpdate(asyncAnime?.await() ?: anime, newEpisodes ?: episodes)
        // <-- AM (LOCAL_COVER_ON_FIRST_SCAN)
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
        // AM (LOCAL_SOURCE_SINGLE_LISTING): the directory was listed twice here,
        // once for episodes.json and once for the videos - the same listing
        // answers both.
        val animeDirFiles = fileSystem.getFilesInAnimeDirectory(anime.url)

        val episodesData = animeDirFiles
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

        animeDirFiles
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
    // AM (LOCAL_SOURCE_APP_FILTER) -->
    // A group exists exactly as long as its directory does, and the first
    // video saved from a new app creates one - so the list comes from the
    // same snapshot as browse and is refreshed with it, except on the very
    // first run after install when no snapshot exists yet and disk is read
    // once to keep the first filter sheet honest.
    override fun getFilterList(): AnimeFilterList {
        val snapshot = loadSnapshot()
        val groups = if (snapshot != null) {
            refreshSnapshotInBackground()
            snapshot.groups.map { AppGroup.Group(url = it.url, title = it.title) }
        } else {
            appGroupNames()
        }
        return AnimeFilterList(
            OrderBy.Popular(context),
            AppGroup(context, groups),
        )
    }

    /** The top-level directories that are app groups - see isAppGroup. */
    // AM (LOCAL_SOURCE_PARALLEL_LISTING) -->
    // Each isAppGroup lists its directory, and these ran one after another -
    // as many sequential provider queries as there are top-level directories.
    // getFilterList() is only ever called off the main thread (see
    // BrowseSourceViewModel), so the classification fans out across
    // Dispatchers.IO and costs one listing's wall time instead of N. A
    // main-thread caller still gets the sequential scan - blocking the main
    // thread on runBlocking would be worse than a slower scan.
    // Only the first run after install takes this path; afterwards the
    // snapshot serves (LOCAL_SOURCE_SNAPSHOT).
    private fun appGroupNames(): List<AppGroup.Group> {
        val dirs = fileSystem.getEntryDirectories()
            .filter { it.file.isDirectory && !it.file.name.orEmpty().startsWith('.') }
            .distinctBy { it.url }
        val groups = if (Looper.myLooper() == Looper.getMainLooper()) {
            dirs.filter { fileSystem.isAppGroup(it.file) }
        } else {
            runBlocking(Dispatchers.IO) {
                coroutineScope {
                    dirs.map { dir -> async { dir to fileSystem.isAppGroup(dir.file) } }
                        .awaitAll()
                        .filter { (_, isGroup) -> isGroup }
                        .map { (dir, _) -> dir }
                }
            }
        }
        return groups
            .map { AppGroup.Group(url = it.url, title = fileSystem.getEntryTitle(it.url, it.file)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
    // <-- AM (LOCAL_SOURCE_APP_FILTER)

    // Unused stuff
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException("Unused")

    // AY -->
    private fun updateImageFromVideo(
        episode: SEpisode,
        anime: SAnime,
        tempFileSuffix: String,
        updateImage: (InputStream) -> Unit,
    ) {
        // AM (FRAME_EXTRACT_TEMP_FILE_LEAK) -->
        // Was File.createTempFile("tmp_", suffix) with no directory argument, so
        // it landed in java.io.tmpdir - which on Android is the app's internal
        // cache root - and was never deleted. No deleteOnExit, no delete in a
        // finally, and the `length() > 0` guard below means the failures (ffmpeg
        // not producing a frame) skip straight past and leave a zero-byte file
        // behind for good. Several hundred of them accumulate loose in the cache
        // root, which is also why they were indistinguishable from anything else
        // living there.
        //
        // A named subdirectory and a finally fix both halves: the files are
        // identifiable as this function's scratch, and they do not outlive the
        // call that made them.
        val tempDir = File(context.cacheDir, FRAME_EXTRACT_DIR).apply { mkdirs() }
        val tempFile = File.createTempFile("tmp_", tempFileSuffix, tempDir)
        val outFile = tempFile.path
        try {
        // <-- AM (FRAME_EXTRACT_TEMP_FILE_LEAK)

            // AM (LOCAL_NESTED_ENTRY_URLS)
            // The file name is the LAST segment, not everything after the first.
            // An entry inside an app folder has a two-segment url
            // (`Chrome/archive`), so its episode url has three - and the old
            // split(limit = 2) handed findFile "archive/video.mkv", which resolves
            // to nothing. The !! below then threw and the caller logged a failed
            // extraction, which is why such an entry never got a cover, a
            // background or episode thumbnails.
            val episodeName = episode.url.substringAfterLast('/')
            val animeDir = fileSystem.getAnimeDirectory(anime.url)!!
            val episodeFile = animeDir.findFile(episodeName)!!
            val episodeFilename = { episodeFile.toFFmpegString(context) }

            val ffProbe = FFprobeKit.execute(
                "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 " +
                    "\"${episodeFilename()}\"",
            )
            val duration = ffProbe.allLogsAsString.trim().toFloat()
            val second = duration.toInt() / 2

            FFmpegKit.execute(
                "-ss $second -i \"${episodeFilename()}\" -frames:v 1 -update true \"$outFile\" -y",
            )

        // AM (FRAME_EXTRACT_TEMP_FILE_LEAK) -->
        // The stream was also never closed - updateImage takes an InputStream and
        // has no contract to close what it is handed, so use() does it here where
        // ownership actually sits.
            if (tempFile.length() > 0L) {
                tempFile.inputStream().use { updateImage(it) }
            }
        } finally {
            tempFile.delete()
        }
        // <-- AM (FRAME_EXTRACT_TEMP_FILE_LEAK)
    }
    // <-- AY

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.org/help/guides/local-anime/"

        // AY -->
        private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()) }
        private const val DEFAULT_COVER_NAME = "cover.jpg"
        private const val DEFAULT_BACKGROUND_NAME = "background.jpg"
        // AM (FRAME_EXTRACT_TEMP_FILE_LEAK) -->
        /** Scratch directory for ffmpeg frame extraction, emptied per call. */
        private const val FRAME_EXTRACT_DIR = "frame_extract"
        // <-- AM (FRAME_EXTRACT_TEMP_FILE_LEAK)

        private const val DEFAULT_THUMBNAIL_NAME = "thumbnail.jpg"
        // <-- AY

        // AM (PARTIAL_EPISODE_SYNC) -->
        private const val THUMBNAIL_CHUNK_SIZE = 10
        // <-- AM (PARTIAL_EPISODE_SYNC)

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds

        // AM (LOCAL_SOURCE_SNAPSHOT) -->
        /** Where the browse snapshot lives - app-private, see [loadSnapshot]. */
        private const val SNAPSHOT_FILE = "local_source_browse_snapshot.json"

        /** Minimum interval between background re-scans - see
         * [refreshSnapshotInBackground]. */
        private const val REFRESH_THROTTLE_MS = 10_000L
        // <-- AM (LOCAL_SOURCE_SNAPSHOT)
    }
}

fun Anime.isLocal(): Boolean = source == LocalSource.ID

fun AnimeSource.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID
