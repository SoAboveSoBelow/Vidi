// AM (MERGE_EPISODE_EXCLUSION) -->
package aniyomi.data.merge

import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergeEpisodeExclusionRepositoryImpl(
    private val database: Database,
) : MergeEpisodeExclusionRepository {

    override suspend fun getByHostAnimeId(hostAnimeId: Long): Set<Long> {
        return database.merge_episode_exclusionsQueries
            .getByHostAnimeId(hostAnimeId)
            .awaitAsList()
            .toSet()
    }

    override fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<Set<Long>> {
        return database.merge_episode_exclusionsQueries
            .getByHostAnimeId(hostAnimeId)
            .subscribeToList()
            .map { it.toSet() }
    }

    override suspend fun add(hostAnimeId: Long, episodeId: Long) {
        database.merge_episode_exclusionsQueries.insert(hostAnimeId, episodeId)
    }

    override suspend fun remove(hostAnimeId: Long, episodeId: Long) {
        database.merge_episode_exclusionsQueries.delete(hostAnimeId, episodeId)
    }

    override suspend fun removeAll(hostAnimeId: Long, episodeIds: List<Long>) {
        if (episodeIds.isEmpty()) return
        database.merge_episode_exclusionsQueries.deleteAll(hostAnimeId, episodeIds)
    }
}
// <-- AM (MERGE_EPISODE_EXCLUSION)
