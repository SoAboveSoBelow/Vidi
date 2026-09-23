// AM (EPISODE_NAMES) -->
package aniyomi.data.episode

import aniyomi.domain.episode.repository.EpisodeNameRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EpisodeNameRepositoryImpl(
    private val database: Database,
) : EpisodeNameRepository {

    override suspend fun getByAnimeIds(animeIds: List<Long>): Map<Long, String> {
        if (animeIds.isEmpty()) return emptyMap()
        return database.episode_namesQueries.getByAnimeIds(animeIds, ::Pair).awaitAsList().toMap()
    }

    override fun getByAnimeIdsAsFlow(animeIds: List<Long>): Flow<Map<Long, String>> {
        if (animeIds.isEmpty()) return flowOf(emptyMap())
        return database.episode_namesQueries.getByAnimeIds(animeIds, ::Pair)
            .subscribeToList()
            .map { it.toMap() }
    }

    override suspend fun set(episodeId: Long, name: String?) {
        val cleaned = name?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned == null) {
            database.episode_namesQueries.delete(episodeId)
        } else {
            database.episode_namesQueries.upsert(episodeId, cleaned)
        }
    }
}
// <-- AM (EPISODE_NAMES)
