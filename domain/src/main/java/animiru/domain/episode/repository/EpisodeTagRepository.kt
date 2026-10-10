// AM (EPISODE_TAGS) -->
package animiru.domain.episode.repository

import animiru.domain.episode.model.EpisodeTag
import kotlinx.coroutines.flow.Flow

/**
 * The user's per-episode tag rows (see episode_tags.sq). Sparse - an episode
 * nobody has tagged has none, and shows its owner's tags untouched.
 */
interface EpisodeTagRepository {

    /** Rows for every episode of [animeId]. */
    suspend fun getByAnimeId(animeId: Long): List<EpisodeTag>

    fun getByAnimeIdAsFlow(animeId: Long): Flow<List<EpisodeTag>>

    /** Rows for every episode of every source of [mergeParentId], in one query. */
    suspend fun getByMergeParentId(mergeParentId: Long): List<EpisodeTag>

    fun getByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<EpisodeTag>>

    suspend fun getByEpisodeId(episodeId: Long): List<EpisodeTag>

    /** Writes [tagsByName] onto one episode in a single transaction. */
    suspend fun upsertAll(episodeId: Long, tagsByName: Map<String, Boolean>)

    /** Drops rows for [tags] on one episode, handing it back to what it inherits. */
    suspend fun deleteAll(episodeId: Long, tags: Collection<String>)

    /**
     * Drops [tag] from every episode of [animeId] - what deleting the entry's
     * aggregated chip does, so those episodes inherit the tag again if the entry
     * still has it, and lose it entirely if it was only ever theirs.
     */
    suspend fun deleteByAnimeIdAndTag(animeId: Long, tag: String)
}
// <-- AM (EPISODE_TAGS)
