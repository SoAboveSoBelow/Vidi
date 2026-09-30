// AM (MERGE_EPISODE_EXCLUSION) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * Takes ONE episode out of a merged entry's order, leaving the rest of its
 * source in the merge. The source keeps contributing its other episodes, and
 * the removed one is untouched everywhere else - its own entry still lists it,
 * its downloads and watch history are not affected, and another merge it
 * belongs to still includes it.
 *
 * Removing the source's LAST remaining episode is the one case that escalates:
 * a source contributing nothing is still carried in the merge - resynced into
 * its details, listed in its settings, counted as a season - so that removal
 * goes through [RemoveFromMerge] instead and takes the source out properly. The
 * exception is a merge with only one source left, which [RemoveFromMerge]
 * refuses (removing the merged entry itself is the tool for that); there the
 * episode is removed on its own and the merge is left empty rather than the
 * action doing nothing.
 */
@Inject
class RemoveEpisodeFromMerge(
    private val episodeRepository: EpisodeRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val exclusionRepository: MergeEpisodeExclusionRepository,
    private val removeFromMerge: RemoveFromMerge,
) {

    sealed interface Result {
        /** The episode alone was removed. */
        data object EpisodeRemoved : Result

        /** It was the source's last one, so the source left the merge. */
        data class SourceRemoved(val childTitle: String) : Result

        /** Not a merged entry, or the episode does not belong to one of its sources. */
        data object NotApplicable : Result
    }

    suspend fun await(mergeParentId: Long, episodeId: Long): Result {
        val episode = episodeRepository.getEpisodeById(episodeId) ?: return Result.NotApplicable
        val children = mergeChildRepository.getChildrenByMergeParentId(mergeParentId)
        val child = children.firstOrNull { it.id == episode.animeId } ?: return Result.NotApplicable

        val childEpisodeIds = episodeRepository.getEpisodeByAnimeId(child.id).map { it.id }
        val alreadyRemoved = exclusionRepository.getByHostAnimeId(mergeParentId)
        val stillContributing = childEpisodeIds.filter { it != episodeId && it !in alreadyRemoved }

        if (stillContributing.isEmpty() && children.size > 1) {
            if (removeFromMerge.await(mergeParentId, child)) {
                // The source is gone, so its episodes' removals describe a list
                // they are no longer candidates for - the same reason
                // RemoveFromMerge drops their order rows.
                exclusionRepository.removeAll(mergeParentId, childEpisodeIds)
                return Result.SourceRemoved(child.title)
            }
        }

        exclusionRepository.add(mergeParentId, episodeId)
        return Result.EpisodeRemoved
    }
}
// <-- AM (MERGE_EPISODE_EXCLUSION)
