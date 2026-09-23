// AM (NAMED_SEASONS) -->
package aniyomi.data.season

import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.repository.EntrySeasonRepository
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
class EntrySeasonRepositoryImpl(
    private val database: Database,
) : EntrySeasonRepository {

    override suspend fun getByHostAnimeId(hostAnimeId: Long): List<EntrySeason> {
        return database.entry_seasonsQueries.getByHostAnimeId(hostAnimeId, ::mapSeason).awaitAsList()
    }

    override fun getByHostAnimeIdAsFlow(hostAnimeId: Long): Flow<List<EntrySeason>> {
        return database.entry_seasonsQueries.getByHostAnimeId(hostAnimeId, ::mapSeason).subscribeToList()
    }

    override suspend fun upsertAll(hostAnimeId: Long, seasons: List<EntrySeason>) {
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

    override suspend fun delete(hostAnimeId: Long, seasonNumber: Long) {
        database.entry_seasonsQueries.delete(hostAnimeId, seasonNumber)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapSeason(hostAnimeId: Long, seasonNumber: Long, name: String?, sortOrder: Long): EntrySeason {
        return EntrySeason(number = seasonNumber, name = name, sortOrder = sortOrder)
    }
}
// <-- AM (NAMED_SEASONS)
