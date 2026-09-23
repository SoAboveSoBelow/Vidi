// AM (MERGE_SETTINGS) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import aniyomi.domain.order.repository.EpisodeOrderRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.episode.repository.EpisodeRepository
import kotlin.time.Clock

/**
 * Takes one source out of a merge. A merge can't lose its last source this
 * way - with one source left there is nothing to merge, and removing the
 * merged entry itself is the right tool for that.
 *
 * The source goes back into the library if it had been taken out (the usual
 * case after "remove original entries"): leaving it out would make it
 * invisible and eligible for Clear database, losing its watch history.
 *
 * Its episodes' custom-order rows for this merge are dropped - they describe
 * positions in a list the episodes are no longer part of, and would otherwise
 * linger and be carried into backups. If it was the info entry, the merge
 * falls back to the default (highest priority), and the merged entry's
 * details are resynced either way, since its tags include this source's.
 */
@Inject
class RemoveFromMerge(
    private val animeRepository: AnimeRepository,
    private val episodeRepository: EpisodeRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val mergeSettingsRepository: MergeSettingsRepository,
    private val episodeOrderRepository: EpisodeOrderRepository,
    private val syncMergedEntryInfo: SyncMergedEntryInfo,
) {
    /** False (and nothing changed) if [child] is the merge's only source or isn't in it. */
    suspend fun await(mergeParentId: Long, child: Anime): Boolean {
        val children = mergeChildRepository.getChildrenByMergeParentId(mergeParentId)
        if (children.size <= 1 || children.none { it.id == child.id }) return false

        val episodeIds = episodeRepository.getEpisodeByAnimeId(child.id).map { it.id }
        episodeOrderRepository.deleteAll(mergeParentId, episodeIds)
        mergeChildRepository.removeChild(mergeParentId, child.id)

        if (!child.favorite) {
            animeRepository.update(
                AnimeUpdate(
                    id = child.id,
                    favorite = true,
                    dateAdded = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }

        val settings = mergeSettingsRepository.get(mergeParentId)
        if (settings.infoAnimeId == child.id) {
            mergeSettingsRepository.set(mergeParentId, settings.copy(infoAnimeId = null))
        }
        syncMergedEntryInfo.await(mergeParentId)
        return true
    }
}
// <-- AM (MERGE_SETTINGS)
