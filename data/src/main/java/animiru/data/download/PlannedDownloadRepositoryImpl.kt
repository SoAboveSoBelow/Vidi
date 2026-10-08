// AM (PLANNED_DOWNLOADS) -->
package animiru.data.download

import animiru.domain.download.model.PlannedDownloadEntry
import animiru.domain.download.model.PlannedDownloadOrigin
import animiru.domain.download.repository.PlannedDownloadRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PlannedDownloadRepositoryImpl(
    private val database: Database,
) : PlannedDownloadRepository {

    override suspend fun getAll(): List<PlannedDownloadEntry> {
        return database.planned_downloadsQueries.getAllDetailed(::mapEntry).awaitAsList()
    }

    override fun getAllAsFlow(): Flow<List<PlannedDownloadEntry>> {
        return database.planned_downloadsQueries.getAllDetailed(::mapEntry).subscribeToList()
    }

    override fun countAsFlow(): Flow<Long> {
        return database.planned_downloadsQueries.count().subscribeToOne()
    }

    override fun getEpisodeIdsByAnimeIdAsFlow(animeId: Long): Flow<Set<Long>> {
        return database.planned_downloadsQueries.getEpisodeIdsByAnimeId(animeId)
            .subscribeToList()
            .map { it.toSet() }
    }

    override suspend fun add(animeId: Long, episodeId: Long, origin: PlannedDownloadOrigin) {
        database.planned_downloadsQueries.upsert(
            animeId = animeId,
            episodeId = episodeId,
            origin = origin.ordinal.toLong(),
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    override suspend fun addAll(episodeIdsByAnimeId: List<Pair<Long, Long>>, origin: PlannedDownloadOrigin) {
        if (episodeIdsByAnimeId.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        database.transaction {
            episodeIdsByAnimeId.forEach { (animeId, episodeId) ->
                database.planned_downloadsQueries.upsert(
                    animeId = animeId,
                    episodeId = episodeId,
                    origin = origin.ordinal.toLong(),
                    createdAt = now,
                )
            }
        }
    }

    override suspend fun remove(episodeId: Long) {
        database.planned_downloadsQueries.deleteByEpisodeId(episodeId)
    }

    override suspend fun removeAll(episodeIds: List<Long>) {
        if (episodeIds.isEmpty()) return
        database.transaction {
            episodeIds.forEach { database.planned_downloadsQueries.deleteByEpisodeId(it) }
        }
    }

    override suspend fun removeByAnimeId(animeId: Long) {
        database.planned_downloadsQueries.deleteByAnimeId(animeId)
    }

    override suspend fun clear() {
        database.planned_downloadsQueries.deleteAll()
    }

    @Suppress("LongParameterList")
    private fun mapEntry(
        animeId: Long,
        episodeId: Long,
        origin: Long,
        createdAt: Long,
        animeTitle: String,
        sourceId: Long,
        episodeName: String,
        episodeScanlator: String?,
        episodeUrl: String,
        episodeNumber: Double,
        episodePreviewUrl: String?,
        animeThumbnailUrl: String?,
    ): PlannedDownloadEntry {
        return PlannedDownloadEntry(
            animeId = animeId,
            episodeId = episodeId,
            origin = PlannedDownloadOrigin.fromOrdinal(origin),
            createdAt = createdAt,
            animeTitle = animeTitle,
            sourceId = sourceId,
            episodeName = episodeName,
            episodeScanlator = episodeScanlator,
            episodeUrl = episodeUrl,
            episodeNumber = episodeNumber,
            episodePreviewUrl = episodePreviewUrl,
            animeThumbnailUrl = animeThumbnailUrl,
        )
    }
}
// <-- AM (PLANNED_DOWNLOADS)
