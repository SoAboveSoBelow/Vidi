// AM (MERGE_EPISODE_EXCLUSION) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.episode.repository.EpisodeNameRepository
import aniyomi.domain.merge.model.RemovedMergeEpisode
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * The episodes [RemoveEpisodeFromMerge] has taken out of a merge, resolved for
 * display so merge settings can list and restore them. Without this the removal
 * would be invisible and only undoable by removing and re-adding the source.
 *
 * Rows whose source has since left the merge are dropped rather than shown:
 * restoring one would put back an episode the merge no longer draws from.
 * [RemoveEpisodeFromMerge] and [RemoveFromMerge] both clear those rows, so this
 * only covers a source removed some other way.
 */
@Inject
class GetRemovedMergeEpisodes(
    private val episodeRepository: EpisodeRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val exclusionRepository: MergeEpisodeExclusionRepository,
    private val episodeNameRepository: EpisodeNameRepository,
) {

    suspend fun await(mergeParentId: Long): List<RemovedMergeEpisode> {
        val removedIds = exclusionRepository.getByHostAnimeId(mergeParentId)
        if (removedIds.isEmpty()) return emptyList()

        val children = mergeChildRepository.getChildrenByMergeParentId(mergeParentId)
        if (children.isEmpty()) return emptyList()

        val customNames = episodeNameRepository.getByAnimeIds(children.map { it.id })
        val childTitles = children.associate { it.id to it.title }

        return children
            .flatMap { child -> episodeRepository.getEpisodeByAnimeId(child.id) }
            .filter { it.id in removedIds }
            .map { episode ->
                RemovedMergeEpisode(
                    episodeId = episode.id,
                    episodeName = customNames[episode.id] ?: episode.name,
                    sourceTitle = childTitles[episode.animeId].orEmpty(),
                    episodeNumber = episode.episodeNumber,
                )
            }
            .sortedWith(compareBy({ it.sourceTitle }, { it.episodeNumber }))
    }
}
// <-- AM (MERGE_EPISODE_EXCLUSION)
