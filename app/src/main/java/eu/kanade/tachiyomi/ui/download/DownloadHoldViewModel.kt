// AM (PLANNED_DOWNLOADS) -->
package eu.kanade.tachiyomi.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import animiru.domain.download.model.PlannedDownloadEntry
import animiru.domain.download.repository.PlannedDownloadRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.ReconcilePlannedDownloads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.source.service.SourceManager

/**
 * The on-hold tab: episodes wanted but not queued.
 *
 * Grouped by source like the queue is, so the two tabs read the same way. The list
 * is observed rather than reloaded, so rows leave without this screen doing anything
 * - the downloader deletes one as its download finishes - and reconciled once when it
 * opens, which is where a hand-copied download gets noticed.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DownloadHoldViewModel(
    private val plannedDownloadRepository: PlannedDownloadRepository,
    private val reconcilePlannedDownloads: ReconcilePlannedDownloads,
    private val downloadManager: DownloadManager,
    private val animeRepository: AnimeRepository,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val sourceManager: SourceManager,
    // AM (EPISODE_NAMES) -->
    private val getDownloadDisplayNames: GetDownloadDisplayNames,
    // <-- AM (EPISODE_NAMES)
) : ViewModel() {

    private val _state = MutableStateFlow<List<HeldDownloadGroup>>(emptyList())
    val state: StateFlow<List<HeldDownloadGroup>> = _state.asStateFlow()

    // AM (EPISODE_NAMES) -->
    private val _displayNames = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** Row titles, resolved as the entry screen and player resolve them. */
    val displayNames: StateFlow<Map<Long, String>> = _displayNames.asStateFlow()
    // <-- AM (EPISODE_NAMES)

    init {
        viewModelScope.launchIO { reconcilePlannedDownloads() }

        plannedDownloadRepository.getAllAsFlow()
            .onEach { entries -> _state.value = entries.groupIntoSources() }
            .launchIn(viewModelScope)

        // AM (EPISODE_NAMES) -->
        // Off the entry ids alone, so a row leaving the list does not re-resolve what
        // the remaining rows are called. Collected separately rather than chained onto
        // the state above: resolving suspends, and on one chain a slow read would hold
        // up the list's own update.
        plannedDownloadRepository.getAllAsFlow()
            .map { entries -> entries.mapTo(mutableSetOf()) { it.animeId } }
            .distinctUntilChanged()
            .onEach { animeIds -> _displayNames.value = getDownloadDisplayNames.await(animeIds) }
            .catch { error -> logcat(LogPriority.ERROR, error) }
            .launchIn(viewModelScope)
        // <-- AM (EPISODE_NAMES)
    }

    fun download(entry: PlannedDownloadEntry) {
        enqueue(entry.animeId, setOf(entry.episodeId))
    }

    fun downloadSeries(animeId: Long) {
        val episodeIds = episodeIdsFor(animeId)
        if (episodeIds.isNotEmpty()) enqueue(animeId, episodeIds)
    }

    /**
     * Queueing hands an episode over to the queue tab, so its row leaves this one -
     * an episode is in one place or the other, never visible in both.
     *
     * Nothing is lost by deleting it here, because cancelling a queued download
     * writes the row back as Held: the only ways out of the queue are finishing, which
     * should clear the intent, and cancelling, which returns it. Rows are deleted after
     * the queue accepts the episodes, so a failure to resolve the entry leaves the hold
     * list untouched.
     */
    private fun enqueue(animeId: Long, episodeIds: Set<Long>) {
        viewModelScope.launchIO {
            try {
                val anime = animeRepository.getAnimeById(animeId)
                val episodes = getEpisodesByAnimeId.await(anime.id).filter { it.id in episodeIds }
                if (episodes.isEmpty()) return@launchIO
                downloadManager.downloadEpisodes(anime, episodes)
                plannedDownloadRepository.removeAll(episodes.map { it.id })
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to queue held downloads for $animeId" }
            }
        }
    }

    fun dismiss(entry: PlannedDownloadEntry) {
        viewModelScope.launchIO { plannedDownloadRepository.remove(entry.episodeId) }
    }

    fun dismissSeries(animeId: Long) {
        viewModelScope.launchIO { plannedDownloadRepository.removeByAnimeId(animeId) }
    }

    private fun episodeIdsFor(animeId: Long): Set<Long> {
        return _state.value
            .flatMap { group -> group.entries }
            .filter { it.animeId == animeId }
            .mapTo(mutableSetOf()) { it.episodeId }
    }

    private fun List<PlannedDownloadEntry>.groupIntoSources(): List<HeldDownloadGroup> {
        return groupBy { it.sourceId }
            .map { (sourceId, entries) ->
                HeldDownloadGroup(
                    sourceId = sourceId,
                    // getOrStub so a source that is no longer installed still names
                    // itself rather than dropping its rows out of the list.
                    sourceName = sourceManager.getOrStub(sourceId).name,
                    entries = entries,
                )
            }
            .sortedBy { it.sourceName.lowercase() }
    }
}

/** One source's block of the on-hold list, mirroring [DownloadSourceGroup]. */
data class HeldDownloadGroup(
    val sourceId: Long,
    val sourceName: String,
    val entries: List<PlannedDownloadEntry>,
)
// <-- AM (PLANNED_DOWNLOADS)
