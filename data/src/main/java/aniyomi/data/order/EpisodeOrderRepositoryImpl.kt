// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.data.order

import aniyomi.domain.order.model.EpisodeOrderOverride
import aniyomi.domain.order.repository.EpisodeOrderRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.awaitList
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
        ).awaitList()
    }

    // AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP)
    override suspend fun getAllByHostAnimeId(): Map<Long, List<EpisodeOrderOverride>> {
        return database.episode_orderQueries.getAll(::EpisodeOrderOverride)
            .awaitList()
            .groupBy { it.hostAnimeId }
    }

    override fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<List<EpisodeOrderOverride>> {
        return database.episode_orderQueries.getByHostAnimeId(
            hostAnimeId,
            ::EpisodeOrderOverride,
        ).subscribeToList()
    }

    override suspend fun upsert(hostAnimeId: Long, episodeId: Long, seasonNumber: Long?, sortKey: Double?) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.episode_orderQueries.upsert(hostAnimeId, episodeId, seasonNumber, sortKey)
        }
    }

    override suspend fun delete(hostAnimeId: Long, episodeId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.episode_orderQueries.delete(hostAnimeId, episodeId)
        }
    }

    override suspend fun deleteAll(hostAnimeId: Long, episodeIds: List<Long>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                episodeIds.forEach { database.episode_orderQueries.delete(hostAnimeId, it) }
            }
        }
    }

    override suspend fun deleteByHostAnimeId(hostAnimeId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.episode_orderQueries.deleteByHostAnimeId(hostAnimeId)
        }
    }

    override suspend fun upsertAll(overrides: List<EpisodeOrderOverride>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                overrides.forEach {
                    database.episode_orderQueries.upsert(it.hostAnimeId, it.episodeId, it.seasonNumber, it.sortKey)
                }
            }
        }
    }

    override suspend fun replaceAll(hostAnimeId: Long, overrides: List<EpisodeOrderOverride>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                database.episode_orderQueries.deleteByHostAnimeId(hostAnimeId)
                overrides.forEach {
                    database.episode_orderQueries.upsert(hostAnimeId, it.episodeId, it.seasonNumber, it.sortKey)
                }
            }
        }
    }
}
// <-- AM (CUSTOM_EPISODE_ORDER)
