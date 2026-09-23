// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.data.order

import aniyomi.domain.order.model.EpisodeOrderOverride
import aniyomi.domain.order.repository.EpisodeOrderRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EpisodeOrderRepositoryImpl(
    private val database: Database,
) : EpisodeOrderRepository {

    override suspend fun getByHostAnimeId(hostAnimeId: Long): List<EpisodeOrderOverride> {
        return database.episode_orderQueries.getByHostAnimeId(
            hostAnimeId,
            ::EpisodeOrderOverride,
        ).awaitAsList()
    }

    override fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<List<EpisodeOrderOverride>> {
        return database.episode_orderQueries.getByHostAnimeId(
            hostAnimeId,
            ::EpisodeOrderOverride,
        ).subscribeToList()
    }

    override suspend fun upsert(hostAnimeId: Long, episodeId: Long, seasonNumber: Long?, sortKey: Double?) {
        database.episode_orderQueries.upsert(hostAnimeId, episodeId, seasonNumber, sortKey)
    }

    override suspend fun delete(hostAnimeId: Long, episodeId: Long) {
        database.episode_orderQueries.delete(hostAnimeId, episodeId)
    }

    override suspend fun deleteAll(hostAnimeId: Long, episodeIds: List<Long>) {
        database.transaction {
            episodeIds.forEach { database.episode_orderQueries.delete(hostAnimeId, it) }
        }
    }

    override suspend fun deleteByHostAnimeId(hostAnimeId: Long) {
        database.episode_orderQueries.deleteByHostAnimeId(hostAnimeId)
    }

    override suspend fun upsertAll(overrides: List<EpisodeOrderOverride>) {
        database.transaction {
            overrides.forEach {
                database.episode_orderQueries.upsert(it.hostAnimeId, it.episodeId, it.seasonNumber, it.sortKey)
            }
        }
    }

    override suspend fun replaceAll(hostAnimeId: Long, overrides: List<EpisodeOrderOverride>) {
        database.transaction {
            database.episode_orderQueries.deleteByHostAnimeId(hostAnimeId)
            overrides.forEach {
                database.episode_orderQueries.upsert(hostAnimeId, it.episodeId, it.seasonNumber, it.sortKey)
            }
        }
    }
}
// <-- AM (CUSTOM_EPISODE_ORDER)
