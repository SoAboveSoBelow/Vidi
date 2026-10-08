// AM (DOWNLOAD_RESTORE) -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import animiru.domain.download.model.PlannedDownloadOrigin
import animiru.domain.download.repository.PlannedDownloadRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.download.DownloadCache
import tachiyomi.domain.anime.model.Anime

/**
 * Records which episodes were downloaded on the device the backup came from, as
 * planned downloads with origin Restored.
 *
 * Nothing is downloaded here: the backup can only say what you had, and fetching
 * it again is a choice with a data cost attached. Episodes already present on this
 * device are skipped outright rather than recorded and immediately reconciled
 * away, so restoring onto a device that already has the files adds nothing.
 */
@Inject
class DownloadStateRestorer(
    private val plannedDownloadRepository: PlannedDownloadRepository,
    private val downloadCache: DownloadCache,
) {

    suspend fun restore(anime: Anime, backupAnime: BackupAnime, resolver: BackupEntryResolver) {
        val wanted = backupAnime.episodes.filter { it.wasDownloaded }
        if (wanted.isEmpty()) return

        val pending = wanted.mapNotNull { backupEpisode ->
            val alreadyHere = downloadCache.isEpisodeDownloaded(
                episodeName = backupEpisode.name,
                episodeScanlator = backupEpisode.scanlator,
                episodeUrl = backupEpisode.url,
                // The download folder is named after the source's title, never a
                // custom one.
                animeTitle = anime.ogTitle,
                sourceId = anime.source,
                skipCache = false,
            )
            if (alreadyHere) return@mapNotNull null

            val episodeId = resolver.episodeId(anime.id, backupEpisode.url) ?: return@mapNotNull null
            anime.id to episodeId
        }

        plannedDownloadRepository.addAll(pending, PlannedDownloadOrigin.Restored)
    }
}
// <-- AM (DOWNLOAD_RESTORE)
