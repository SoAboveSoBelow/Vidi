// AM (DOWNLOAD_RESTORE) -->
package eu.kanade.tachiyomi.data.download

import animiru.domain.download.model.PlannedDownloadEntry
import animiru.domain.download.repository.PlannedDownloadRepository
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Returns the still-wanted planned downloads, dropping any that have since turned
 * up on disk.
 *
 * Two routes can satisfy an entry and they are handled in the two different places
 * that suit them. A normal download has an event and an episode id already in hand,
 * so [Downloader] deletes that one row as it finishes - no scanning, exactly once.
 * Files copied in from an old install have no event at all: nothing calls
 * [DownloadCache.addEpisode], and only the cache's own filesystem scan ever learns
 * about them. That case is caught here instead, when the list is actually read.
 *
 * Checking on read rather than on every cache change is the cheaper half of the
 * same accuracy: the list is only ever shown on one screen, so a hand-copied
 * download that goes unnoticed until that screen opens has been noticed in time.
 * Presence is still the test, not a remembered event, so a missed signal costs
 * nothing.
 */
@Inject
class ReconcilePlannedDownloads(
    private val plannedDownloadRepository: PlannedDownloadRepository,
    private val downloadCache: DownloadCache,
) {

    suspend operator fun invoke(): List<PlannedDownloadEntry> {
        val pending = plannedDownloadRepository.getAll()
        if (pending.isEmpty()) return emptyList()

        val (present, wanted) = pending.partition { entry ->
            downloadCache.isEpisodeDownloaded(
                episodeName = entry.episodeName,
                episodeScanlator = entry.episodeScanlator,
                episodeUrl = entry.episodeUrl,
                // The download folder is named after the source's title, never a
                // custom one.
                animeTitle = entry.animeTitle,
                sourceId = entry.sourceId,
                skipCache = false,
            )
        }

        if (present.isNotEmpty()) {
            try {
                plannedDownloadRepository.removeAll(present.map { it.episodeId })
            } catch (e: Exception) {
                // The list is a convenience; showing it matters more than pruning it.
                logcat(LogPriority.WARN, e) { "Failed to prune satisfied planned downloads" }
            }
        }

        return wanted
    }
}
// <-- AM (DOWNLOAD_RESTORE)
