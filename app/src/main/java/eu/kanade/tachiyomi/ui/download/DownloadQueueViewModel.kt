package eu.kanade.tachiyomi.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DownloadQueueViewModel(
    private val downloadManager: DownloadManager,
    // AM (EPISODE_NAMES) -->
    private val getDownloadDisplayNames: GetDownloadDisplayNames,
    // <-- AM (EPISODE_NAMES)
) : ViewModel() {

    // AM (EPISODE_NAMES) -->
    /**
     * Titles for the rows, resolved the way the entry screen and player resolve them.
     *
     * Keyed off the set of queued entries and nothing finer, so it is recomputed when
     * an entry joins or leaves the queue rather than on every status tick.
     */
    val displayNames: StateFlow<Map<Long, String>> = downloadManager.queueState
        .map { downloads -> downloads.mapTo(mutableSetOf()) { it.anime.id } }
        .distinctUntilChanged()
        .map { animeIds -> getDownloadDisplayNames.await(animeIds) }
        .catch { error -> logcat(LogPriority.ERROR, error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), emptyMap())
    // <-- AM (EPISODE_NAMES)

    // AM (COMPOSE_DOWNLOAD_QUEUE) -->
    /**
     * The queue grouped by source, as the RecyclerView's header items used to be.
     *
     * Rows are value objects rather than [Download]s. A Download is mutated in place
     * as it progresses and re-emitted as the same instance, so a state holding them
     * compares equal to its predecessor and nothing recomposes - the same trap the
     * download indicator fell into. Actions therefore take an episode id and look the
     * live Download back up, which also means they can never act on a stale object.
     */
    val state: StateFlow<List<DownloadSourceGroup>> = downloadManager.queueState
        .map { downloads -> downloads.groupIntoSources() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), emptyList())

    private val _downloadStates = MutableStateFlow<Map<Long, QueuedDownloadState>>(emptyMap())

    /** Live status and progress per episode, kept separate so a tick does not rebuild the list. */
    val downloadStates: StateFlow<Map<Long, QueuedDownloadState>> = _downloadStates.asStateFlow()

    init {
        observeDownloadStates()
    }

    private fun observeDownloadStates() {
        // Three triggers, one snapshot. queueState covers joining and leaving the
        // queue; the other two carry the same mutated object, so they are signals
        // rather than values. onStart seeds the current queue, because statusFlow
        // drops its first emission and replays only what is already running.
        merge(
            downloadManager.queueState.map { },
            downloadManager.statusFlow().map { },
            downloadManager.progressFlow().map { },
        )
            .onStart { emit(Unit) }
            .onEach { snapshotDownloadStates() }
            .catch { error -> logcat(LogPriority.ERROR, error) }
            .launchIn(viewModelScope)
    }

    private fun snapshotDownloadStates() {
        _downloadStates.value = downloadManager.queueState.value.associate { download ->
            download.episode.id to QueuedDownloadState(
                status = download.status,
                progress = download.progress,
            )
        }
    }

    private fun List<Download>.groupIntoSources(): List<DownloadSourceGroup> {
        return groupBy { it.source }
            .map { (source, downloads) ->
                DownloadSourceGroup(
                    sourceId = source.id,
                    sourceName = source.name,
                    downloads = downloads.map { download ->
                        QueuedDownloadRow(
                            episodeId = download.episode.id,
                            animeId = download.anime.id,
                            animeTitle = download.anime.title,
                            episodeName = download.episode.name,
                            episodeScanlator = download.episode.scanlator,
                            previewUrl = download.episode.previewUrl ?: download.anime.thumbnailUrl,
                        )
                    },
                )
            }
    }

    private fun queued(): List<Download> = downloadManager.queueState.value

    private fun downloadOf(episodeId: Long): Download? = queued().firstOrNull { it.episode.id == episodeId }

    /** Commits a drag: [episodeIds] is the whole queue in its new order. */
    fun reorderTo(episodeIds: List<Long>) {
        val byEpisodeId = queued().associateBy { it.episode.id }
        val reordered = episodeIds.mapNotNull { byEpisodeId[it] }
        if (reordered.size != byEpisodeId.size) return
        reorder(reordered)
    }

    fun moveToTop(episodeId: Long) = moveWithinSource(episodeId, toTop = true)

    fun moveToBottom(episodeId: Long) = moveWithinSource(episodeId, toTop = false)

    /**
     * Moves one download to the top or bottom of its own source's block.
     *
     * Within the source, because that is the unit the queue is grouped and dragged
     * in - the old adapter enforced the same rule in `shouldMove`.
     */
    private fun moveWithinSource(episodeId: Long, toTop: Boolean) {
        val all = queued()
        val target = all.firstOrNull { it.episode.id == episodeId } ?: return
        val sameSource = all.filter { it.source.id == target.source.id }
        val without = sameSource.filterNot { it.episode.id == episodeId }
        val movedBlock = if (toTop) listOf(target) + without else without + listOf(target)
        reorder(replaceBlock(all, movedBlock))
    }

    fun moveSeriesToTop(animeId: Long) = moveSeries(animeId, toTop = true)

    fun moveSeriesToBottom(animeId: Long) = moveSeries(animeId, toTop = false)

    private fun moveSeries(animeId: Long, toTop: Boolean) {
        val (series, others) = queued().partition { it.anime.id == animeId }
        if (series.isEmpty()) return
        reorder(if (toTop) series + others else others + series)
    }

    fun cancel(episodeId: Long) {
        val download = downloadOf(episodeId) ?: return
        cancel(listOf(download))
    }

    fun cancelSeries(animeId: Long) {
        val series = queued().filter { it.anime.id == animeId }
        if (series.isEmpty()) return
        cancel(series)
    }

    /**
     * Rebuilds the queue with one source's block replaced in place.
     *
     * The block re-enters at the position its first member held, so reordering inside
     * one source leaves every other source exactly where it was instead of hoisting
     * the edited one to the front.
     */
    private fun replaceBlock(original: List<Download>, movedBlock: List<Download>): List<Download> {
        val movedIds = movedBlock.mapTo(mutableSetOf()) { it.episode.id }
        val result = mutableListOf<Download>()
        var emitted = false
        original.forEach { download ->
            if (download.episode.id in movedIds) {
                if (!emitted) {
                    result += movedBlock
                    emitted = true
                }
            } else {
                result += download
            }
        }
        return result
    }
    // <-- AM (COMPOSE_DOWNLOAD_QUEUE)

    val isDownloaderRunning = downloadManager.isDownloaderRunning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), false)

    fun startDownloads() {
        downloadManager.startDownloads()
    }

    fun pauseDownloads() {
        downloadManager.pauseDownloads()
    }

    fun clearQueue() {
        downloadManager.clearQueue()
    }

    fun reorder(downloads: List<Download>) {
        downloadManager.reorderQueue(downloads)
    }

    fun cancel(downloads: List<Download>) {
        downloadManager.cancelQueuedDownloads(downloads)
    }

    /**
     * Sorts the whole queue by [selector].
     *
     * Selects on the live [Download] rather than the row model, so sorting keeps
     * working on episode fields the list itself has no reason to carry.
     */
    fun <R : Comparable<R>> reorderQueue(selector: (Download) -> R, reverse: Boolean = false) {
        val comparator = if (reverse) {
            compareByDescending(selector)
        } else {
            compareBy(selector)
        }
        reorder(queued().sortedWith(comparator))
    }
}

// AM (COMPOSE_DOWNLOAD_QUEUE) -->
/** One source's block of the queue, matching the old header-and-sub-items shape. */
data class DownloadSourceGroup(
    val sourceId: Long,
    val sourceName: String,
    val downloads: List<QueuedDownloadRow>,
)

/** A queued episode, as the list needs it - no [Download] reference, so it compares by value. */
data class QueuedDownloadRow(
    val episodeId: Long,
    val animeId: Long,
    val animeTitle: String,
    val episodeName: String,
    val episodeScanlator: String?,
    val previewUrl: String?,
)

/** Status and progress copied out of the queue so a change is visible by value. */
data class QueuedDownloadState(
    val status: Download.State,
    val progress: Int,
)
// <-- AM (COMPOSE_DOWNLOAD_QUEUE)
