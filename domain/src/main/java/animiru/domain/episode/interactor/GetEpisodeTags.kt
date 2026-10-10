// AM (EPISODE_TAGS) -->
package animiru.domain.episode.interactor

import animiru.domain.episode.model.EpisodeTagResolution
import animiru.domain.episode.model.resolveEpisodeTags
import animiru.domain.episode.repository.EpisodeTagRepository
import aniyomi.domain.merge.model.MERGED_SOURCE_ID
import aniyomi.domain.merge.repository.MergeChildRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * One host's resolved episode tags - the read side of [episode_tags].
 *
 * Which entries own the host's episodes is the only thing that differs between
 * an ordinary entry and a merge, and it is what both of the resolution rules
 * are scoped by, so it is gathered here and the rules themselves stay in
 * [resolveEpisodeTags].
 */
@Inject
class GetEpisodeTags(
    private val episodeTagRepository: EpisodeTagRepository,
    private val episodeRepository: EpisodeRepository,
    private val mergeChildRepository: MergeChildRepository,
) {

    suspend fun await(host: Anime): EpisodeTagResolution {
        val (episodesByOwner, ownerTags) = owners(host)
        if (episodesByOwner.isEmpty()) return EpisodeTagResolution.Empty
        return resolveEpisodeTags(episodesByOwner, ownerTags, rows(host))
    }

    /**
     * Reactive [await], on the rows alone. Membership and the owners' own tags
     * are read when this is collected rather than watched: a source joining the
     * merge or a refresh re-ranking its tags already redraws the screen through
     * the flows that carry them.
     */
    suspend fun subscribe(host: Anime): Flow<EpisodeTagResolution> {
        val (episodesByOwner, ownerTags) = owners(host)
        val rows = if (host.source == MERGED_SOURCE_ID) {
            episodeTagRepository.getByMergeParentIdAsFlow(host.id)
        } else {
            episodeTagRepository.getByAnimeIdAsFlow(host.id)
        }
        return rows.map { resolveEpisodeTags(episodesByOwner, ownerTags, it) }
    }

    private suspend fun rows(host: Anime) = if (host.source == MERGED_SOURCE_ID) {
        episodeTagRepository.getByMergeParentId(host.id)
    } else {
        episodeTagRepository.getByAnimeId(host.id)
    }

    /** The host's episodes grouped by the entry that owns them, and those entries' tags. */
    private suspend fun owners(host: Anime): Pair<Map<Long, List<Long>>, Map<Long, List<String>>> {
        if (host.source != MERGED_SOURCE_ID) {
            val episodeIds = episodeRepository.getEpisodeByAnimeId(host.id).map { it.id }
            return mapOf(host.id to episodeIds) to mapOf(host.id to host.genre.orEmpty())
        }
        val children = mergeChildRepository.getChildrenByMergeParentId(host.id)
        val episodesByOwner = episodeRepository.getEpisodesByMergeParentId(host.id)
            .groupBy { it.animeId }
            .mapValues { (_, episodes) -> episodes.map { it.id } }
        return episodesByOwner to children.associate { it.id to it.genre.orEmpty() }
    }
}
// <-- AM (EPISODE_TAGS)
