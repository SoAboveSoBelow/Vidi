// AM (NAMED_SEASONS) -->
package aniyomi.data.season

import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.repository.EntrySeasonRepository
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
class EntrySeasonRepositoryImpl(
    private val database: Database,
) : EntrySeasonRepository {

    override suspend fun getByHostAnimeId(hostAnimeId: Long): List<EntrySeason> {
        return database.entry_seasonsQueries.getByHostAnimeId(hostAnimeId, ::mapSeason).awaitList()
    }

    // AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP): its own mapper, because
    // mapSeason drops host_anime_id - which is exactly the column this needs.
    override suspend fun getAllByHostAnimeId(): Map<Long, List<EntrySeason>> {
        return database.entry_seasonsQueries
            .getAll { hostAnimeId, seasonNumber, name, sortOrder ->
                hostAnimeId to EntrySeason(number = seasonNumber, name = name, sortOrder = sortOrder)
            }
            .awaitList()
            .groupBy({ it.first }, { it.second })
    }

    override fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<List<EntrySeason>> {
        return database.entry_seasonsQueries.getByHostAnimeId(hostAnimeId, ::mapSeason).subscribeToList()
    }

    override suspend fun upsertAll(hostAnimeId: Long, seasons: List<EntrySeason>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                seasons.forEach {
                    database.entry_seasonsQueries.upsert(
                        hostAnimeId,
                        it.number,
                        it.name,
                        requireNotNull(it.sortOrder) { "season ${it.number} written without a sortOrder" },
                    )
                }
            }
        }
    }

    override suspend fun delete(hostAnimeId: Long, seasonNumber: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.entry_seasonsQueries.delete(hostAnimeId, seasonNumber)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapSeason(hostAnimeId: Long, seasonNumber: Long, name: String?, sortOrder: Long): EntrySeason {
        return EntrySeason(number = seasonNumber, name = name, sortOrder = sortOrder)
    }
}
// <-- AM (NAMED_SEASONS)
