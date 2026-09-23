// AM (EPISODE_NAMES) -->
package aniyomi.domain.episode.repository

import kotlinx.coroutines.flow.Flow

/** Display-only custom episode names, keyed by episode - see episode_names.sq. */
interface EpisodeNameRepository {

    suspend fun getByAnimeIds(animeIds: List<Long>): Map<Long, String>

    fun getByAnimeIdsAsFlow(animeIds: List<Long>): Flow<Map<Long, String>>

    /** A blank [name] clears the custom name, restoring the source's. */
    suspend fun set(episodeId: Long, name: String?)
}
// <-- AM (EPISODE_NAMES)
