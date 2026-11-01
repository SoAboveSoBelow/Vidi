package eu.kanade.tachiyomi.data.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.core.net.toUri
import animiru.domain.download.repository.PlannedDownloadRepository
import aniyomi.core.common.torrent.TorrentPreferences
import aniyomi.core.common.torrent.TorrentServerApi
import aniyomi.core.common.torrent.TorrentServerUtils
import aniyomi.player.clip.ClipVideoResolver
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import animiru.domain.player.service.PlayerPreferences
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.model.LocalSourceTarget
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.media.MediaCache
import eu.kanade.tachiyomi.data.media.MediaProxyServer
import eu.kanade.tachiyomi.data.media.ResolvedVideoCache
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.torrent.service.TorrentServerService
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.components.HosterState
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.createFileInCacheDir
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import java.io.BufferedReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * This class is the one in charge of downloading episodes.
 *
 * Its queue contains the list of episodes to download.
 */
@Inject
@SingleIn(AppScope::class)
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager,
    private val downloadPreferences: DownloadPreferences,
    private val store: DownloadStore,
    private val notifier: DownloadNotifier,
    // AY -->
    private val episodeLoader: Lazy<EpisodeLoader>,
    private val hosterLoader: Lazy<HosterLoader>,
    private val networkService: NetworkHelper,
    private val torrentServerApi: TorrentServerApi,
    private val torrentServerUtils: TorrentServerUtils,
    private val torrentPreferences: TorrentPreferences,
    // <-- AY
    // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
    private val playerPreferences: PlayerPreferences,
    // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
    // AM (PLANNED_DOWNLOADS) -->
    private val plannedDownloadRepository: PlannedDownloadRepository,
    // <-- AM (PLANNED_DOWNLOADS)
) {

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    private val _localSourceSaved = MutableSharedFlow<LocalSourceTarget>(replay = REPLAYED_SAVES)

    /**
     * Emits each time a save into the local source finishes, with the target it
     * landed in.
     *
     * Emitted here rather than inferred from the queue: a download leaves the
     * queue at the same moment it reports DOWNLOADED, so a watcher built on
     * queue state can miss the completion entirely. This fires once the file is
     * actually in place, which is the only moment that means anything to a
     * reader of the local source. Replayed, so a watcher that subscribes just
     * after a very fast save still sees it - re-reading a folder that already
     * holds the files is harmless.
     */
    val localSourceSaved = _localSourceSaved.asSharedFlow()
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    // AM -->
    val client: OkHttpClient
        get() = networkService.client
    // <-- AM

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        launchNow {
            val episodes = async { store.restore() }
            addAllToQueue(episodes.await())
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        // AM (STOP_DISMISSES_NOTIFICATION) -->
        // The worker is stopped BEFORE the notification is updated. DownloadJob is
        // a foreground worker whose notification is the downloader's progress
        // notification, so dismissing first left the still-live worker to re-post
        // it on its way down - the notification stayed on screen, still showing a
        // download in progress, after Stop had been pressed.
        DownloadJob.stop(context)

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false
        // <-- AM (STOP_DISMISSES_NOTIFICATION)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = combine(
                queueState,
                downloadPreferences.parallelSourceLimit.changes(),
            ) { a, b -> a to b }.transformLatest { (queue, parallelCount) ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.DOWNLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(parallelCount)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }
                .distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Download, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    /**
     * Launch the job responsible for downloading a single video
     */
    private fun CoroutineScope.launchDownloadJob(download: Download) = launchIO {
        try {
            downloadEpisode(download)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every episode and adds them to the downloads queue.
     *
     * @param anime the anime of the episodes to download.
     * @param episodes the list of episodes to download.
     * @param autoStart whether to start the downloader after enqueing the episodes.
     */
    fun queueEpisodes(
        anime: Anime,
        episodes: List<Episode>,
        autoStart: Boolean,
        // AY -->
        changeDownloader: Boolean = false,
        video: Video? = null,
        // <-- AY
        // AM (LOCAL_SOURCE_DOWNLOAD)
        localSourceTarget: LocalSourceTarget? = null,
    ) {
        if (episodes.isEmpty()) return

        val source = sourceManager.get(anime.source) as? AnimeHttpSource ?: return
        val wasEmpty = queueState.value.isEmpty()
        val episodesToQueue = episodes.asSequence()
            // Filter out those already downloaded.
            // AM (CUSTOM_INFORMATION) -->
            // AM (LOCAL_SOURCE_DOWNLOAD): asked of the destination this download
            // is headed for. A library download already present says nothing
            // about whether the local source holds the same video, and the two
            // are allowed to coexist - they answer different questions.
            .filter { !alreadyDownloaded(it, anime, source, localSourceTarget) }
            // <-- AM (CUSTOM_INFORMATION)
            // Add episodes to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            // AM (LOCAL_SOURCE_DOWNLOAD): to the same destination, for the same
            // reason - a queued library download must not swallow a request to
            // also save the episode into the local source.
            .filter { episode ->
                queueState.value.none { it.episode.id == episode.id && it.localSourceTarget == localSourceTarget }
            }
            // Create a download for each one.
            .map { Download(source, anime, it, changeDownloader, video, localSourceTarget) }
            .toList()

        if (episodesToQueue.isNotEmpty()) {
            addAllToQueue(episodesToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > EPISODES_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(
                            MR.strings.download_queue_size_warning,
                            context.stringResource(MR.strings.app_name),
                        ),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                DownloadJob.start(context)
            }
        }
    }

    /**
     * Downloads an episode.
     *
     * @param download the episode to be downloaded.
     */
    private suspend fun downloadEpisode(download: Download) {
        // AM (CUSTOM_INFORMATION) -->
        // AM (LOCAL_SOURCE_DOWNLOAD): the directory the finished file lands in,
        // which is the only thing the destination changes before the fetch. The
        // scratch directory, the resolution, the fetch itself and every error
        // path below are shared.
        // AM (LOCAL_SOURCE_DOWNLOAD): the one thing the destination changes
        // before the fetch - which directory the download lands in.
        val animeDir = (
            download.localSourceTarget
                ?.let { provider.getLocalSourceDir(it) }
                ?: provider.getAnimeDir(download.anime.ogTitle, download.source)
            ).getOrElse { e ->
                download.status = Download.State.ERROR
                notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
                return
            }
        // <-- AM (CUSTOM_INFORMATION)

        val availSpace = DiskUtil.getAvailableStorageSpace(animeDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(
                context.stringResource(AMMR.strings.am_download_insufficient_space),
                download.episode.name,
                download.anime.title,
                download.anime.id,
            )
            return
        }

        val episodeDirname = provider.getEpisodeDirName(
            download.episode.name,
            download.episode.scanlator,
            download.episode.url,
        )
        val tmpDir = animeDir.createDirectory(episodeDirname + TMP_DIR_SUFFIX)!!
        // AM (TMP_DIR_IS_SCRATCH) -->
        // Emptied on entry, every time. Nothing used to clear this directory on a
        // failure - downloadVideo()'s catch deletes only "$filename.tmp" - so
        // whatever a failed attempt left behind stayed there for good, and
        // isDownloadSuccessful() below counted directory entries. One leftover
        // was therefore permanent: every later attempt at that episode reported
        // ERROR however cleanly the video itself downloaded, which is why an
        // episode could fail to download forever while streaming perfectly.
        //
        // Leftovers are easy to produce and hard to name individually. The
        // internal path writes "<episode name>.mkv" while downloadVideoExternal()
        // writes "<anime> - <episode>_tmp.mkv", and the success path deletes only
        // the latter spelling; a .nomedia, a partial file from a process killed
        // mid-write, or a rename of the episode all do the same thing. Treating
        // the directory as scratch - owned entirely by the attempt in progress -
        // removes the whole class rather than the instances.
        //
        // Safe because nothing outside this function reads it: the finished
        // download is the renameTo(episodeDirname) below, and the cache indexes
        // that name, not this one.
        //
        // The cost is one case that used to be free: a prior attempt whose video
        // downloaded and passed verifyDownloadedDuration() but failed afterwards
        // left a complete .mkv that getOrDownloadVideoFile() would reuse, and that
        // is now re-fetched. Keeping it would mean deciding which of possibly
        // several leftover files is the real one - the ambiguity this change
        // exists to remove - so the scratch directory stays scratch.
        clearTmpDir(tmpDir)
        // <-- AM (TMP_DIR_IS_SCRATCH)

        try {
            // AY -->
            if (download.video == null) {
                // Pull video from network and add them to download object
                val hosters = episodeLoader.value.getHosters(download.episode, download.anime, download.source)
                if (hosters.isEmpty()) {
                    throw Exception(context.stringResource(AYMR.strings.video_list_empty_error))
                }
                val bestVideo = hosterLoader.value.getBestVideo(download.source, hosters)
                    ?: throw Exception(context.stringResource(AYMR.strings.video_list_empty_error))
                download.video = bestVideo
            }

            withIOContext {
                getOrDownloadVideoFile(download, tmpDir)
            }
            // <-- AY

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            // AM (CUSTOM_INFORMATION) -->
            val filename = DiskUtil.buildValidFilename("${download.anime.ogTitle} - ${download.episode.name}")
            // <-- AM (CUSTOM_INFORMATION)
            // AY -->
            tmpDir.findFile("${filename}_tmp.mkv")?.delete()
            // <-- AY

            tmpDir.renameTo(episodeDirname)

            // AM (LOCAL_SOURCE_DOWNLOAD) -->
            // The library path below is exactly what it was. A local source
            // download differs in two ways and no others: the video has to sit
            // directly in the entry directory, because a subdirectory there
            // registers as a SEASON rather than an episode, and it is not part
            // of the downloads tree the cache indexes.
            val localTarget = download.localSourceTarget
            if (localTarget == null) {
                cache.addEpisode(episodeDirname, animeDir, download.anime)
            } else {
                flattenIntoLocalSource(animeDir, episodeDirname)
                _localSourceSaved.tryEmit(localTarget)
            }
            // <-- AM (LOCAL_SOURCE_DOWNLOAD)

            // AM (PLANNED_DOWNLOADS) -->
            // The episode is downloaded, so the intent to download it is spent.
            // Dropped here, where the answer is certain, rather than by a listener
            // re-checking the list afterwards - and keyed by the episode alone: the
            // previous version also required download.anime.id to match the stored
            // anime_id, which it does not for a merged or seasoned entry, whose
            // downloads carry the parent while the row names the child that owns the
            // episode. That mismatch deleted nothing and left the row behind.
            plannedDownloadRepository.remove(download.episode.id)
            // <-- AM (PLANNED_DOWNLOADS)

            // AM (LOCAL_SOURCE_DOWNLOAD): skipped for a local source download -
            // .nomedia would hide the entry from the device's media scanner,
            // and the directory it would be written to no longer exists.
            if (localTarget == null) {
                DiskUtil.createNoMediaFile(tmpDir, context)
            }

            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the video threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.episode.name, download.anime.title, download.anime.id)
        }
    }

    // AM (LOCAL_SOURCE_DOWNLOAD) -->

    /**
     * Whether [episode] is already present where this download would put it.
     *
     * The library answer is upstream's, unchanged. A local source download
     * asks the same question of the entry directory instead, since the two can
     * legitimately both exist - they answer different needs.
     */
    private fun alreadyDownloaded(
        episode: Episode,
        anime: Anime,
        source: AnimeHttpSource,
        target: LocalSourceTarget?,
    ): Boolean = if (target == null) {
        // AM (CUSTOM_INFORMATION)
        provider.findEpisodeDir(episode.name, episode.scanlator, episode.url, anime.ogTitle, source) != null
    } else {
        provider.findLocalSourceFile(target, DiskUtil.buildValidFilename(episode.name)) != null
    }

    /**
     * Moves the finished video out of [episodeDir] up into [entryDir], then
     * removes the now-empty directory.
     *
     * This is the whole of what a local source download does differently. The
     * fetch, the retries, the progress notification and the queue are the
     * library path's, untouched - only the resting place differs, because the
     * local source reads a directory of videos and treats a subdirectory as a
     * season.
     *
     * Renamed on the way if something of that name is already there, rather
     * than overwriting: two sites can serve an episode of the same name, and
     * silently replacing the first is the worse outcome.
     */
    private fun flattenIntoLocalSource(entryDir: UniFile, episodeDirname: String) {
        val episodeDir = entryDir.findFile(episodeDirname) ?: return
        val taken = entryDir.listFiles().orEmpty().mapNotNull { it.name }.toSet()
        episodeDir.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            val name = file.name ?: return@forEach
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var candidate = name
            var n = 2
            while (candidate in taken) {
                candidate = if (ext.isBlank()) "$base ($n)" else "$base ($n).$ext"
                n++
            }
            if (candidate != name) file.renameTo(candidate)
            moveUp(file, episodeDir, entryDir)
        }
        episodeDir.delete()
    }

    /**
     * Moves [file] from [from] into [to].
     *
     * The provider's own move first, which is metadata-only and so costs
     * nothing for a file that may be gigabytes; copy-then-delete for the
     * providers that do not support it, which is slow but is the difference
     * between a download that lands and one that does not.
     */
    private fun moveUp(file: UniFile, from: UniFile, to: UniFile): Boolean {
        val moved = runCatching {
            if (!DocumentsContract.isDocumentUri(context, file.uri)) return@runCatching null
            DocumentsContract.moveDocument(context.contentResolver, file.uri, from.uri, to.uri)
        }.getOrNull()
        if (moved != null) return true

        val name = file.name ?: return false
        val target = to.createFile(name) ?: return false
        return runCatching {
            file.openInputStream().use { input -> target.openOutputStream().use(input::copyTo) }
            file.delete()
            true
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "LOCAL_SOURCE_DOWNLOAD copy into local source failed" }
            target.delete()
            false
        }
    }
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)

    // AY -->

    /**
     * Gets the video file if already downloaded, otherwise downloads it
     *
     * @param download the download of the video.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadVideoFile(
        download: Download,
        tmpDir: UniFile,
    ) {
        val video = download.video!!

        video.status = Video.State.LOAD_VIDEO

        var progressJob: Job? = null
        var httpServer: HttpServer? = null

        // Get filename from download info
        val filename = DiskUtil.buildValidFilename(download.episode.name)

        // Delete temp file if it exists
        tmpDir.findFile("$filename.tmp")?.delete()

        // Try to find the video file
        // AM (SEPARATE_AUDIO_DOWNLOAD): or the video of a pair, which these
        // entries produce instead of an .mkv - without this the reuse check
        // never recognised one and fetched it again on every pass.
        val videoFile = tmpDir.listFiles()?.firstOrNull {
            val name = it.name.orEmpty()
            name.startsWith("$filename.mkv") || name == "$filename$VIDEO_SUFFIX"
        }

        try {
            // If the video is already downloaded, do nothing. Otherwise download from network
            val file = when {
                videoFile != null -> videoFile
                else -> {
                    notifier.onProgressChange(download)

                    download.status = Download.State.DOWNLOADING
                    download.progress = 0

                    // If videoFile is not existing then download it
                    if (downloadPreferences.useExternalDownloader.get() == download.changeDownloader) {
                        progressJob = scope.launch {
                            while (download.status == Download.State.DOWNLOADING) {
                                delay(50.milliseconds)
                                notifier.onProgressChange(download)
                            }
                        }

                        // Start and set http server if needed
                        if (video.usesHttpServer()) {
                            httpServer = download.source.createHttpServer()
                            httpServer?.start()
                            download.video = download.video?.copyHttpServer(httpServer?.listeningPort ?: 0)
                        }

                        downloadVideo(download, tmpDir, filename)
                    } else {
                        if (download.video!!.usesHttpServer()) {
                            val (success, port) = MainActivity.startHttpServerService(context, download.source.id)
                            if (!success) throw Exception("Failed to start server")
                            download.video = download.video!!.copyHttpServer(port)
                        }

                        val betterFileName = DiskUtil.buildValidFilename(
                            // AM (CUSTOM_INFORMATION) -->
                            "${download.anime.ogTitle} - ${download.episode.name}",
                            // <-- AM (CUSTOM_INFORMATION)
                        )
                        downloadVideoExternal(
                            video = download.video!!,
                            source = download.source,
                            tmpDir = tmpDir,
                            filename = betterFileName,
                        )
                    }
                }
            }

            video.videoUrl = file.uri.path ?: ""
            download.progress = 100
            video.status = Video.State.READY
            httpServer?.stop()
            progressJob?.cancel()
        } catch (e: Exception) {
            httpServer?.stop()
            if (e is CancellationException) throw e
            video.status = Video.State.ERROR
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
            progressJob?.cancel()
        }
    }

    /**
     * Define a retry routine in order to accommodate some errors that can be raised
     *
     * @param download the download reference
     * @param tmpDir the directory where placing the file
     * @param filename the name to give to download file
     */
    private suspend fun downloadVideo(
        download: Download,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        return flow {
            tmpDir.findFile("$filename.tmp")?.delete()
            val videoFile = tmpDir.createFile("$filename.tmp")!!
            try {
                if (torrentPreferences.torrServerEnable.get() && isTorrent(download.video)) {
                    torrentDownload(download, tmpDir, videoFile, filename)
                } else {
                    ffmpegDownload(download, tmpDir, videoFile, filename)
                }
            } catch (e: Exception) {
                videoFile.delete()
                throw e
            }

            emit(videoFile)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { cause, attempt ->
                if (attempt < 3) {
                    // AM (RETRY_RESOLVES_FRESH_LINK) -->
                    // Drop the resolved video so downloadEpisode() resolves a new
                    // one. Without this every attempt reused the URL that had just
                    // failed: for the most common cause - a hoster link that
                    // expired, either before the download started or part-way
                    // through it - all three retries were guaranteed to fail
                    // against a dead URL, and download.video staying non-null
                    // meant a later manual retry skipped resolution and failed the
                    // same way. Same reasoning as the player's own
                    // reloadCurrentVideo() on a post-EOF read error.
                    //
                    // Unconditional rather than filtered by exception type: the
                    // failures that actually arrive here are plain Exceptions -
                    // ffmpeg's own "Error in ffmpeg!" and the completeness check's
                    // "Download incomplete" - so there is nothing reliable to
                    // match on. Re-resolving costs one request on a retry that was
                    // already going to sleep for seconds, which is far cheaper
                    // than three guaranteed failures against a dead URL.
                    // Resolved HERE rather than by nulling download.video: this
                    // retry re-runs downloadVideo's flow, not downloadEpisode's
                    // resolution above it, so a null would reach download.video!!
                    // on the next attempt.
                    logcat(LogPriority.INFO) {
                        "RETRY_RESOLVES_FRESH_LINK re-resolving after: ${cause.message}"
                    }
                    refreshDownloadVideo(download)
                    // <-- AM (RETRY_RESOLVES_FRESH_LINK)
                    delay((2L shl attempt.toInt()).seconds)
                    true
                } else {
                    false
                }
            }
            .flowOn(Dispatchers.IO)
            .first()
    }

    // AM (RESOLVE_LINK_ON_TURN) -->
    /**
     * Resolves the video to download, preferring the quality named by
     * [requestedQuality] and falling back to best-available when that quality is
     * no longer offered by any hoster.
     */
    private suspend fun resolveQueuedVideo(
        download: Download,
        hosters: List<Hoster>,
        requestedQuality: String?,
    ): Video? {
        // AM (SHARED_LINK_RESOLUTION) -->
        // Take the player's resolution when it has one for this episode and
        // quality. Only when a quality was actually requested: without one this is
        // a best-available pick, and adopting whatever the player happens to be
        // watching would quietly change what gets downloaded.
        if (requestedQuality != null) {
            ResolvedVideoCache.get(ResolvedVideoCache.key(download.episode.id, requestedQuality))
                ?.let { return it }
        }
        // <-- AM (SHARED_LINK_RESOLUTION)

        if (requestedQuality != null) {
            for (hoster in hosters) {
                // loadHosterVideos, not hoster.videoList: getHosters() returns
                // hosters whose video lists are not populated yet (and for a lazy
                // hoster, never are until asked), so reading the field directly
                // would miss almost every match and silently fall through to
                // best-available - quietly ignoring the user's choice.
                val state = episodeLoader.value.loadHosterVideos(download.source, hoster)
                val videos = (state as? HosterState.Ready)?.videoList ?: continue
                val match = videos.firstOrNull { it.videoTitle == requestedQuality } ?: continue
                val resolved = hosterLoader.value.getResolvedVideo(download.source, match)
                if (resolved != null && resolved.videoUrl.isNotEmpty()) {
                    // AM (SHARED_LINK_RESOLUTION) -->
                    ResolvedVideoCache.put(
                        ResolvedVideoCache.key(download.episode.id, requestedQuality),
                        resolved,
                    )
                    // <-- AM (SHARED_LINK_RESOLUTION)
                    return resolved
                }
            }
            logcat(LogPriority.INFO) {
                "RESOLVE_LINK_ON_TURN requested quality no longer available: $requestedQuality"
            }
        }
        return hosterLoader.value.getBestVideo(download.source, hosters)
    }
    // <-- AM (RESOLVE_LINK_ON_TURN)

    // AM (RETRY_RESOLVES_FRESH_LINK) -->
    /**
     * Re-resolves [download]'s video from its hosters, replacing the URL that just
     * failed. Keeps the existing video if resolution fails, so a retry still has
     * something to attempt rather than turning a recoverable failure into a hard
     * one.
     */
    private suspend fun refreshDownloadVideo(download: Download) {
        try {
            val hosters = episodeLoader.value.getHosters(download.episode, download.anime, download.source)
            if (hosters.isEmpty()) return
            // AM (RESOLVE_LINK_ON_TURN) -->
            // Through the same helper, so a retry keeps the chosen quality too.
            // AM (SHARED_LINK_RESOLUTION) -->
            // The link being retried has already failed, so drop it from the shared
            // cache before resolving - otherwise this re-resolve could be handed
            // back the very link it is retrying away from.
            ResolvedVideoCache.invalidate(
                ResolvedVideoCache.key(download.episode.id, download.video?.videoTitle.orEmpty()),
            )
            // <-- AM (SHARED_LINK_RESOLUTION)
            val fresh = resolveQueuedVideo(download, hosters, download.video?.videoTitle) ?: return
            // <-- AM (RESOLVE_LINK_ON_TURN)
            download.video = fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "RETRY_RESOLVES_FRESH_LINK re-resolve failed" }
        }
    }
    // <-- AM (RETRY_RESOLVES_FRESH_LINK)

    private fun isTorrent(video: Video?): Boolean {
        val url = video?.videoUrl ?: return false
        return url.startsWith("magnet") || url.endsWith(".torrent") || url.startsWith(torrentServerApi.hostUrl)
    }

    private suspend fun torrentDownload(
        download: Download,
        tmpDir: UniFile,
        videoFile: UniFile,
        filename: String,
    ) {
        val video = download.video!!
        TorrentServerService.start(context)
        if (video.videoUrl.startsWith(torrentServerApi.hostUrl)) {
            val hash = video.videoUrl.substringAfter("link=").substringBefore("&")
            val index = video.videoUrl.substringAfter("index=").substringBefore("&").toInt()
            val magnet = "magnet:?xt=urn:btih:$hash&index=$index"
            video.videoUrl = magnet
        }
        val currentTorrent = torrentServerApi.addTorrent(video.videoUrl, video.videoTitle, "", "", false)
        var index = 0
        if (video.videoUrl.contains("index=")) {
            index = try {
                video.videoUrl.substringAfter("index=")
                    .substringBefore("&").toInt()
            } catch (_: Exception) {
                0
            }
        }
        val torrentUrl = torrentServerUtils.getTorrentPlayLink(currentTorrent, index)
        video.videoUrl = torrentUrl
        ffmpegDownload(download, tmpDir, videoFile, filename)
    }

    // ffmpeg is always on safe mode
    private suspend fun ffmpegDownload(
        download: Download,
        tmpDir: UniFile,
        videoFile: UniFile,
        filename: String,
    ) {
        val video = download.video!!

        val ffmpegFilename = { videoFile.uri.toFFmpegString(context) }

        val headers = video.headers ?: download.source.headers
        val headerOptions = headers.joinToString("", "-headers '", "'") {
            "${it.first}: ${it.second}\r\n"
        }

        // AM (PLAYBACK_OVER_DOWNLOADS) -->
        // Read the remote file through MediaProxyServer rather than letting ffmpeg
        // fetch it directly, so the rate can be adjusted while the transfer is
        // already running - see that server's own note for why ffmpeg's -readrate
        // could not do this. Fails open: no proxy, direct URL, today's behaviour.
        // AM (MEDIA_CACHE) -->
        // Keyed on the episode and quality, which is what lets a player watching
        // this same episode read the bytes this download is pulling instead of
        // fetching its own copy over the same connection.
        val cacheKey = MediaProxyServer.cacheKeyFor(download.episode.id, video.videoTitle)
        // AM (CLIP_PLAYLIST): the listing's own video where it states one -
        // see statedVideoUrl.
        val fetchUrl = statedVideoUrl(download.anime, video) ?: video.videoUrl
        val proxied = proxiedVideoUrl(fetchUrl, headers, cacheKey)
        // <-- AM (MEDIA_CACHE)
        val sourceUrl = proxied?.second ?: fetchUrl
        // <-- AM (PLAYBACK_OVER_DOWNLOADS)

        try {
            // AM (SEPARATE_AUDIO_DOWNLOAD) -->
            // A clip whose separate audio runs longer than its video - a Coub,
            // and anything shaped like one - is a looping video under a full
            // track, which is how the feed and the player both play it. Muxed
            // into one file it became a video that froze on its last frame for
            // the rest of the song, so the download did not keep the thing it
            // downloaded.
            //
            // Stored as one ordinary file with the full audio in it, which is
            // what makes it useful to anything else - and played by
            // ClipPlayback, which opens that file twice so the video repeats
            // on its own length under the track. A container cannot say "this
            // video repeats under this audio", so the file does not try to:
            // the thing that knows is the player.
            val extraAudioTracks = feedAudioTracks(download.anime, video)
            val audioTracks = video.audioTracks + extraAudioTracks
            // The decision is the presence of a separate audio stream, not a
            // comparison of durations. Probing both lengths over the network
            // is two requests that can each fail, and when one did the
            // download silently took the other path - so the same episode
            // stored as two files or as one depending on the network, and the
            // player then disagreed with whatever was on disk. One stream
            // means one file; two streams mean two files.
            val asTwoStreams = audioTracks.isNotEmpty()

            // AM (DOWNLOAD_PROGRESS_BY_BYTES) -->
            // Progress is bytes written over bytes expected, across every run
            // this download makes.
            //
            // It used to be ffmpeg's output timestamp over the media's
            // duration, which is only the same thing at a constant bitrate
            // and is not the same thing at all once a download writes more
            // than one file: the video's run reached the end of the video
            // while the bar was still measuring against the audio's length,
            // so it stopped a fraction of the way across and then jumped.
            //
            // The expected total is each source's own Content-Length. A
            // source that will not state one leaves this null and the
            // duration estimate below stands, so this is an improvement where
            // the information exists rather than a new way to fail.
            val videoBytes = contentLength(fetchUrl, headers)
            val audioBytes = audioTracks.firstOrNull()?.let { contentLength(it.url, headers) } ?: 0L
            val totalBytes = videoBytes?.let { it + audioBytes }?.takeIf { it > 0L }
            // What the video's own run accounts for, so the audio's run can
            // carry on from there rather than restarting the bar.
            var videoWritten = 0L
            // <-- AM (DOWNLOAD_PROGRESS_BY_BYTES)

            val videoDuration = getDuration(sourceUrl, headerOptions)?.toDouble() ?: 0.0
            // Measured against what this ffmpeg session actually writes: the
            // video alone when the streams are stored apart, both when they
            // are muxed into one file. Measuring one against the other is
            // what made the bar stall at a few percent and then fail its own
            // completeness check as a truncated transfer.
            val duration = if (asTwoStreams) {
                videoDuration.toLong()
            } else {
                val audioDuration = longestAudioDuration(audioTracks, headerOptions).toDouble()
                maxOf(videoDuration, audioDuration).toLong()
            }
            // <-- AM (SEPARATE_AUDIO_DOWNLOAD)

            val ffmpegOptions = if (asTwoStreams) {
                // The video alone, into the scratch file this download was
                // given - exactly where the muxed path writes - and renamed
                // once both streams are on disk. The audio is its own run
                // below, into its own scratch file.
                getStreamFFmpegOptions(headerOptions, sourceUrl, videoFile, audio = false)
            } else {
                getFFmpegOptions(
                    video,
                    headers,
                    headerOptions,
                    ffmpegFilename(),
                    sourceUrl,
                    // AM (SEPARATE_AUDIO_DOWNLOAD)
                    extraAudioTracks = extraAudioTracks,
                )
            }

            val logCallback = LogCallback { log ->
                if (log.level <= Level.AV_LOG_WARNING) {
                    log.message?.let {
                        logcat(LogPriority.ERROR) { it }
                    }
                }
            }

            // AM (VERIFY_FROM_STATS_NOT_PROBE) -->
            var lastOutTime = 0L
            // <-- AM (VERIFY_FROM_STATS_NOT_PROBE)
            val statCallback = StatisticsCallback { s ->
                val outTime = (s.time / 1000.0).toLong()

                // AM (DOWNLOAD_PROGRESS_BY_BYTES) -->
                val written = s.size.toLong()
                if (written > 0L) videoWritten = written
                if (totalBytes != null && written > 0L) {
                    download.progress = (100 * written / totalBytes).toInt().coerceIn(0, 100)
                } else if (duration != 0L && outTime > 0) {
                    download.progress = (100 * outTime / duration).toInt()
                }
                // <-- AM (DOWNLOAD_PROGRESS_BY_BYTES)
                // AM (VERIFY_FROM_STATS_NOT_PROBE) -->
                if (outTime > lastOutTime) lastOutTime = outTime
                // <-- AM (VERIFY_FROM_STATS_NOT_PROBE)
            }

            suspendCancellableCoroutine { continuation ->
                val session = FFmpegKit.executeWithArgumentsAsync(
                    ffmpegOptions,
                    {
                        if (it.returnCode.isValueSuccess) {
                            continuation.resume(it)
                        } else {
                            continuation.resumeWithException(Exception("Error in ffmpeg!"))
                        }
                    },
                    logCallback,
                    statCallback,
                )
                continuation.invokeOnCancellation {
                    session.cancel()
                }
            }

            // AM (VERIFY_DOWNLOAD_COMPLETE) -->
            // The rename moved out of the success callback and behind this check.
            // ffmpeg exiting successfully means "I remuxed what I was given", not
            // "I was given the whole file": any upstream that ends a stream early -
            // an expired link, a dropped connection, a proxy hop that gave up - is
            // an ordinary end of input as far as `-c copy` is concerned, and the
            // result was a truncated file renamed to .mkv and marked DOWNLOADED.
            // isDownloadSuccessful() cannot catch it either; it only counts files.
            //
            // The expected duration is already known (it drives the progress bar),
            // so comparing it against what actually landed is nearly free. Throwing
            // here puts the download through downloadVideo()'s existing retry and
            // cleanup rather than leaving a broken file that looks finished.
            verifyDownloadedDuration(lastOutTime, duration)
            // AM (SEPARATE_AUDIO_DOWNLOAD) -->
            if (asTwoStreams) {
                writeSeparateAudio(
                    download = download,
                    tmpDir = tmpDir,
                    filename = filename,
                    audioTrack = audioTracks.first(),
                    headers = headers,
                    headerOptions = headerOptions,
                    // AM (DOWNLOAD_PROGRESS_BY_BYTES): the bar carries on from
                    // whatever the video's run wrote.
                    writtenBefore = videoWritten,
                    totalBytes = totalBytes,
                )
                // Both renames last, the audio's first: until the video
                // carries its final name nothing treats this folder as a
                // finished download, so a failure writing the audio leaves an
                // unfinished attempt rather than a silent episode.
                tmpDir.findFile("$filename$AUDIO_SCRATCH")?.renameTo("$filename$AUDIO_SUFFIX")
                tmpDir.findFile("$filename.tmp")?.renameTo("$filename$VIDEO_SUFFIX")
            } else {
                tmpDir.findFile("$filename.tmp")?.renameTo("$filename.mkv")
            }
            // <-- AM (SEPARATE_AUDIO_DOWNLOAD)
            // <-- AM (VERIFY_DOWNLOAD_COMPLETE)
        } finally {
            // AM (PLAYBACK_OVER_DOWNLOADS) -->
            proxied?.let { (key, _) -> MediaProxyServer.release(key) }
            // <-- AM (PLAYBACK_OVER_DOWNLOADS)
        }
    }

    // AM (VERIFY_DOWNLOAD_COMPLETE) -->
    /**
     * Throws unless the file just written covers [expectedDurationSeconds].
     *
     * A small shortfall is tolerated: container rounding and a final partial
     * fragment can leave a remux marginally shorter than its source without
     * anything being wrong. A truncated transfer is not marginal.
     */
    private fun verifyDownloadedDuration(writtenSeconds: Long, expectedDurationSeconds: Long) {
        if (expectedDurationSeconds <= 0L) return
        // AM (VERIFY_FROM_STATS_NOT_PROBE) -->
        // Taken from ffmpeg's own statistics - how far through the media it had
        // written when the session ended - rather than by probing the file after.
        //
        // The probe was wrong twice over. It ran ffprobe against the output path,
        // and on SAF storage that path is a saf: URI needing a parameter obtained
        // through FFmpegKitConfig, so the probe failed on its own terms rather than
        // because anything was wrong with the download. And a failed probe threw,
        // which failed the download, which failed every retry the same way and
        // emptied the queue - a check meant to catch corruption became the thing
        // destroying good downloads.
        //
        // The statistics are already being collected for the progress bar, need no
        // second pass over the file, and answer exactly the question being asked:
        // how much of the media actually made it through.
        if (writtenSeconds <= 0L) return
        // <-- AM (VERIFY_FROM_STATS_NOT_PROBE)
        if (writtenSeconds < expectedDurationSeconds * MIN_COMPLETE_DURATION_RATIO) {
            throw Exception("Download incomplete: ${writtenSeconds}s of ${expectedDurationSeconds}s")
        }
    }
    // <-- AM (VERIFY_DOWNLOAD_COMPLETE)

    // AM (PLAYBACK_OVER_DOWNLOADS) -->
    /**
     * Registers [url] with the loopback proxy, or returns null when it should be
     * fetched directly: a local file has nothing to proxy, and a source's own
     * localhost server is already a local hop whose real fetch happens inside the
     * source, out of reach of this server's rate limiting either way.
     */
    private fun proxiedVideoUrl(url: String, headers: Headers, cacheKey: String?): Pair<String, String>? {
        if (!url.startsWith("http")) return null
        if (url.contains("127.0.0.1") || url.contains("localhost")) return null
        // AM (MEDIA_CACHE) -->
        MediaProxyServer.attachCache(
            MediaCache.get(
                context,
                // Zero allowance is the off state - see the preference's own note.
                playerPreferences.retainRecentEpisodeMediaMaxBytes.get() > 0,
                playerPreferences.retainRecentEpisodeMediaMaxBytes.get(),
            ),
        )
        // <-- AM (MEDIA_CACHE)
        return MediaProxyServer.proxyUrlFor(url, headers, client, cacheKey, throttled = true)
    }
    // <-- AM (PLAYBACK_OVER_DOWNLOADS)

    private suspend fun getFFmpegOptions(
        video: Video,
        // AM -->
        headers: Headers,
        // <-- AM
        headerOptions: String,
        ffmpegFilename: String,
        // AM (PLAYBACK_OVER_DOWNLOADS) -->
        // Separate from video.videoUrl: the video keeps its real URL (the rest of
        // the download pipeline writes and reads it), while ffmpeg is pointed at
        // whatever transport is actually serving those bytes.
        sourceUrl: String,
        // <-- AM (PLAYBACK_OVER_DOWNLOADS)
        // AM (SEPARATE_AUDIO_DOWNLOAD)
        extraAudioTracks: List<Track> = emptyList(),
    ): Array<String> {
        fun formatInputs(tracks: List<Track>) = tracks.joinToString(" ", postfix = " ") {
            buildList {
                if (it.url.startsWith("http")) {
                    add(headerOptions)
                }
                add("-i")
                add("\"${it.url}\"")
            }.joinToString(" ")
        }

        fun formatMaps(tracks: List<Track>, type: String, offset: Int = 0) = tracks.indices.joinToString(" ") {
            "-map ${it + 1 + offset}:$type"
        }

        fun formatMetadata(tracks: List<Track>, type: String) = tracks.mapIndexed { i, track ->
            "-metadata:s:$type:$i \"title=${track.lang}\""
        }.joinToString(" ")

        // AM -->
        val subtitleTracks = filterTracks(video.subtitleTracks, headers)
        // <-- AM
        val subtitleInputs = formatInputs(subtitleTracks)
        val subtitleMaps = formatMaps(subtitleTracks, "s")
        val subtitleMetadata = formatMetadata(subtitleTracks, "s")

        // AM -->
        // AM (SEPARATE_AUDIO_DOWNLOAD): plus whatever the entry itself knows
        // about, for a video the source hands over without its audio.
        val audioTracks = filterTracks(video.audioTracks + extraAudioTracks, headers)
        // <-- AM
        val audioInputs = formatInputs(audioTracks)
        val audioMaps = formatMaps(audioTracks, "a", subtitleTracks.size)
        val audioMetadata = formatMetadata(audioTracks, "a")

        val sourceStreamOptions = video.ffmpegStreamArgs.joinToString(" ") { (key, value) ->
            "-$key \"$value\""
        }
        val sourceVideoOptions = video.ffmpegVideoArgs.joinToString(" ") { (key, value) ->
            "-$key \"$value\""
        }

        val videoInput = buildList {
            if (sourceUrl.startsWith("http")) {
                add(headerOptions)
            }
            add(sourceStreamOptions)
            add("-i")
            add("\"$sourceUrl\"")
        }.joinToString(" ")

        val command = listOf(
            videoInput, subtitleInputs, audioInputs,
            "-map 0:v", audioMaps, "-map 0:a?", subtitleMaps, "-map 0:s? -map 0:t?",
            "-f matroska -c:a copy -c:v copy -c:s copy",
            subtitleMetadata, audioMetadata, sourceVideoOptions,
            "\"$ffmpegFilename\" -y",
        )
            .filter(String::isNotBlank)
            .joinToString(" ")

        return FFmpegKitConfig.parseArguments(command)
    }

    // AM -->
    private suspend fun filterTracks(tracks: List<Track>, headers: Headers): List<Track> {
        if (!downloadPreferences.ignoreBrokenTracks.get()) return tracks

        return tracks.mapNotNull { track ->
            try {
                val request = Request.Builder()
                    .url(track.url)
                    .headers(headers)
                    .head()
                    .build()
                client.newCall(request).awaitSuccess()
                track
            } catch (_: Exception) {
                null
            }
        }
    }
    // <-- AM

    // AM (SEPARATE_AUDIO_DOWNLOAD) -->
    /**
     * One stream of an entry, remuxed `-c copy` into [output].
     *
     * The same remux the muxed path does, writing through the same SAF
     * parameter - the only difference is which streams are mapped and that
     * each goes into a file of its own.
     */
    private fun getStreamFFmpegOptions(
        headerOptions: String,
        sourceUrl: String,
        output: UniFile,
        audio: Boolean,
    ): Array<String> {
        val command = listOf(
            if (sourceUrl.startsWith("http")) headerOptions else "",
            "-i", "\"" + sourceUrl + "\"",
            if (audio) "-map 0:a -c:a copy -vn -sn" else "-map 0:v -c:v copy -an -sn",
            "-f mp4 -movflags +faststart",
            "\"" + output.toFFmpegString(context) + "\" -y",
        )
            .filter(String::isNotBlank)
            .joinToString(" ")
        return FFmpegKitConfig.parseArguments(command)
    }

    /**
     * Fetches the audio into its own scratch file beside the video's.
     *
     * A scratch name, renamed by the caller once both streams are written:
     * a file created under its final name is a file that looks like part of a
     * finished download the moment it exists, and an attempt that failed here
     * left exactly that - an empty audio file, one per retry, since a SAF
     * provider gives the second one a suffixed name of its own rather than
     * replacing the first.
     */
    private suspend fun writeSeparateAudio(
        download: Download,
        tmpDir: UniFile,
        filename: String,
        audioTrack: Track,
        headers: Headers,
        headerOptions: String,
        // AM (DOWNLOAD_PROGRESS_BY_BYTES)
        writtenBefore: Long,
        totalBytes: Long?,
    ) {
        tmpDir.findFile(filename + AUDIO_SCRATCH)?.delete()
        val output = tmpDir.createFile(filename + AUDIO_SCRATCH)
            ?: throw Exception("Separate audio: could not create the scratch file")

        val proxied = proxiedVideoUrl(audioTrack.url, headers, null)
        try {
            suspendCancellableCoroutine { continuation ->
                val session = FFmpegKit.executeWithArgumentsAsync(
                    getStreamFFmpegOptions(
                        headerOptions = headerOptions,
                        sourceUrl = proxied?.second ?: audioTrack.url,
                        output = output,
                        audio = true,
                    ),
                    {
                        if (it.returnCode.isValueSuccess) {
                            continuation.resume(it)
                        } else {
                            continuation.resumeWithException(Exception("Error in ffmpeg: " + it.output))
                        }
                    },
                    { log ->
                        if (log.level <= Level.AV_LOG_WARNING) {
                            log.message?.let { logcat(LogPriority.ERROR) { it } }
                        }
                    },
                    // AM (DOWNLOAD_PROGRESS_BY_BYTES)
                    { stats ->
                        val written = writtenBefore + stats.size.toLong()
                        if (totalBytes != null && written > 0L) {
                            download.progress = (100 * written / totalBytes).toInt().coerceIn(0, 100)
                        }
                    },
                )
                continuation.invokeOnCancellation { session.cancel() }
            }
        } finally {
            proxied?.let { (key, _) -> MediaProxyServer.release(key) }
        }

        if (output.length() <= 0L) {
            throw Exception("Separate audio: the remux produced nothing")
        }
    }

    // AM (DOWNLOAD_PROGRESS_BY_BYTES) -->
    /**
     * How many bytes [url] will deliver, or null where it will not say.
     *
     * A one-byte range request rather than a HEAD: a CDN that answers HEAD
     * with no length still answers a range request with a Content-Range, and
     * the body is a byte.
     */
    private suspend fun contentLength(url: String, headers: Headers): Long? {
        if (!url.startsWith("http")) return null
        return try {
            withIOContext {
                val request = Request.Builder()
                    .url(url)
                    .headers(headers)
                    .header("Range", "bytes=0-0")
                    .build()
                client.newCall(request).execute().use { response ->
                    response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: response.header("Content-Length")?.toLongOrNull()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    // <-- AM (DOWNLOAD_PROGRESS_BY_BYTES)

    /**
     * The entry's own separate audio, for a video that arrived without it.
     *
     * A listing that states its media states the audio with it (see
     * ClipVideoResolver.statedBy, read from the browse memo, which is where the feed
     * gets it). The episode path does not carry it: a source hands back a url
     * per stream, so an entry whose site serves video and audio apart gives
     * either a pre-muxed file - Coub's share file, which needs none of this -
     * or the bare video, which downloads silent.
     *
     * Guarded on the urls matching. The memo describes the raw video; when
     * the source resolved to something else it resolved to a file that
     * carries its own audio, and attaching this would mux a second audio
     * track over it.
     */
    private fun feedAudioTracks(anime: Anime, video: Video): List<Track> {
        if (video.audioTracks.isNotEmpty()) return emptyList()
        // AM (CLIP_PLAYLIST): through the resolver the player uses, so a
        // download of one of these entries is the pair its listing states -
        // not the source's pre-muxed pick, whose audio runs only as long as
        // its video and so would download exactly the truncated track the
        // player no longer plays.
        val stated = ClipVideoResolver.statedBy(anime, null) ?: return emptyList()
        val audioUrl = stated.audioUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(Track(audioUrl, "Audio"))
    }

    /**
     * The video this entry's own listing states, where it states one.
     *
     * Same reason as above: the download should hold what the player plays.
     */
    private fun statedVideoUrl(anime: Anime, video: Video): String? {
        if (video.audioTracks.isNotEmpty()) return null
        val stated = ClipVideoResolver.statedBy(anime, null) ?: return null
        if (stated.audioUrl.isNullOrBlank()) return null
        return stated.url.takeIf { it != video.videoUrl }
    }

    /**
     * The longest of [tracks], in seconds, or 0.
     *
     * Probed rather than assumed: whether the audio outlasts the video is the
     * whole question, and only the files answer it. A track that cannot be
     * probed counts as 0 and simply does not trigger the loop - the download
     * then behaves exactly as it did before this existed.
     */
    private suspend fun longestAudioDuration(tracks: List<Track>, headerOptions: String): Long {
        if (tracks.isEmpty()) return 0L
        return tracks.maxOf { track ->
            try {
                getDuration(track.url, headerOptions)?.toLong() ?: 0L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
        }
    }
    // <-- AM (SEPARATE_AUDIO_DOWNLOAD)

    private suspend fun getDuration(videoUrl: String, headerOptions: String): Float? {
        val durationFile = context.createFileInCacheDir("ffprobe_duration.txt")
        val durationFilePath = durationFile.toUri().toFFmpegString(context)

        val ffprobeCommand = FFmpegKitConfig.parseArguments(
            listOf(
                headerOptions,
                "-v quiet -show_entries format=duration -of default=noprint_wrappers=1:nokey=1",
                "-o \"$durationFilePath\"",
                "\"$videoUrl\"",
            ).joinToString(" "),
        )

        suspendCancellableCoroutine { continuation ->
            val session = FFprobeKit.executeWithArgumentsAsync(ffprobeCommand) {
                if (it.returnCode.isValueSuccess) {
                    continuation.resume(it)
                } else {
                    continuation.resumeWithException(Exception(it.output))
                }
            }
            continuation.invokeOnCancellation { session.cancel() }
        }

        return durationFile.bufferedReader().use(BufferedReader::readText).trim().toFloatOrNull()
    }

    /**
     * Returns the observable which downloads the video with an external downloader.
     *
     * @param video the video to download.
     * @param source the source of the video.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the video.
     */
    private suspend fun downloadVideoExternal(
        video: Video,
        source: AnimeHttpSource,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        try {
            val file = tmpDir.createFile("${filename}_tmp.mkv")!!
            withUIContext {
                context.copyToClipboard("Episode download location", tmpDir.filePath!!.substringBeforeLast("_tmp"))
            }

            // TODO: support other file formats!!
            // start download with intent
            val pm = context.packageManager
            val pkgName = downloadPreferences.externalDownloaderSelection.get()
            val intent: Intent
            if (pkgName.isNotEmpty()) {
                intent = pm.getLaunchIntentForPackage(pkgName) ?: throw Exception(
                    "Launch intent not found",
                )
                when {
                    // 1DM
                    pkgName.startsWith("idm.internet.download.manager") -> {
                        val headers = (video.headers ?: source.headers).toMap()
                        val bundle = Bundle()
                        for ((key, value) in headers) {
                            bundle.putString(key, value)
                        }

                        intent.apply {
                            component = ComponentName(
                                pkgName,
                                "idm.internet.download.manager.Downloader",
                            )
                            action = Intent.ACTION_VIEW
                            data = video.videoUrl.toUri()

                            putExtra("extra_filename", "$filename.mkv")
                            putExtra("extra_headers", bundle)
                        }
                    }
                    // ADM
                    pkgName.startsWith("com.dv.adm") -> {
                        val headers = (video.headers ?: source.headers).toList()
                        val bundle = Bundle()
                        headers.forEach { a ->
                            bundle.putString(
                                a.first,
                                a.second.replace("http", "h_ttp"),
                            )
                        }

                        intent.apply {
                            component = ComponentName(pkgName, "$pkgName.AEditor")
                            action = Intent.ACTION_VIEW
                            putExtra(
                                "com.dv.get.ACTION_LIST_ADD",
                                "${video.videoUrl.toUri()}<info>$filename.mkv",
                            )
                            putExtra(
                                "com.dv.get.ACTION_LIST_PATH",
                                tmpDir.filePath!!.substringBeforeLast("_"),
                            )
                            putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
                        }
                        file.delete()
                        tmpDir.delete()
                        queueState.value.find { anime -> anime.video == video }?.let { download ->
                            download.status = Download.State.DOWNLOADED
                            // Delete successful downloads from queue
                            if (download.status == Download.State.DOWNLOADED) {
                                // Remove downloaded episode from queue
                                removeFromQueue(download)
                            }
                            if (areAllDownloadsFinished()) {
                                stop()
                            }
                        }
                    }
                }
            } else {
                intent = Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setDataAndType(video.videoUrl.toUri(), "video/*")
                    putExtra("extra_filename", filename)
                }
            }
            context.startActivity(intent)
            return file
        } catch (e: Exception) {
            tmpDir.findFile("${filename}_tmp.mkv")?.delete()
            throw e
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // AM (TMP_DIR_IS_SCRATCH) -->
        // Was `downloadedVideo.size == 1`, which asked about the directory rather
        // than about the download: an extra file made a good download fail, and a
        // single wrong file made a bad one pass. With the directory cleared on
        // entry the count is no longer load-bearing, so this asks the question it
        // always meant to - is there a finished video file here with bytes in it.
        //
        // Completeness is already checked where it can be: verifyDownloadedDuration()
        // runs against ffmpeg's own statistics before the .tmp is renamed, so a
        // truncated transfer never reaches this point as a .mkv at all.
        //
        // The exclusion was also spelled ".tmp" against UniFile.extension, which
        // is name.substringAfterLast('.') and so yields "tmp" with no dot - it
        // never matched, and an in-progress scratch file counted toward the total
        // like any other. Corrected here rather than left alongside the new test,
        // since a stale .tmp is still not a finished download.
        val downloadedVideo = tmpDir.listFiles().orEmpty()
            .filterNot { it.extension == "tmp" }
            .filterNot { it.name.orEmpty().startsWith(".") }
        return downloadedVideo.any { it.length() > 0L }
        // <-- AM (TMP_DIR_IS_SCRATCH)
    }

    // AM (TMP_DIR_IS_SCRATCH) -->
    /** Empties [tmpDir] without removing it, so the attempt starts from nothing. */
    private fun clearTmpDir(tmpDir: UniFile) {
        tmpDir.listFiles().orEmpty().forEach { file ->
            runCatching { file.delete() }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "TMP_DIR_IS_SCRATCH could not delete ${file.name}" }
                }
        }
    }
    // <-- AM (TMP_DIR_IS_SCRATCH)
    // <-- AY

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    fun removeFromQueue(episodes: List<Episode>) {
        val episodeIds = episodes.map { it.id }
        removeFromQueueIf { it.episode.id in episodeIds }
    }

    fun removeFromQueue(anime: Anime) {
        removeFromQueueIf { it.anime.id == anime.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        // AM (LOCAL_SOURCE_DOWNLOAD)
        private const val REPLAYED_SAVES = 8

        // AM (VERIFY_DOWNLOAD_COMPLETE) -->
        /** How much of the expected duration a finished download must cover. */
        private const val MIN_COMPLETE_DURATION_RATIO = 0.98
        // <-- AM (VERIFY_DOWNLOAD_COMPLETE)

        const val TMP_DIR_SUFFIX = "_tmp"

        // AM (SEPARATE_AUDIO_DOWNLOAD) -->
        /**
         * What the two files of an entry stored as separate streams are
         * called, relative to the episode's own name.
         *
         * Read back by the player to recognise such an entry and find its
         * pair, so the names live here rather than at both ends. Ordinary,
         * well-known extensions: a SAF provider derives a document's type
         * from the name it is CREATED with, and one it does not recognise
         * gets the document created as "(invalid)".
         */
        const val VIDEO_SUFFIX = ".video.mp4"
        const val AUDIO_SUFFIX = ".audio.m4a"

        /** The audio's scratch name, before both streams are known good. */
        const val AUDIO_SCRATCH = ".audio.tmp"
        // <-- AM (SEPARATE_AUDIO_DOWNLOAD)

        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val EPISODES_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
