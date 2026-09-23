// AM (MERGED_SOURCES) -->
package aniyomi.data.merge

import aniyomi.domain.anime.model.SeasonAnime
import aniyomi.domain.merge.model.MergeChildOrdering
import aniyomi.domain.merge.repository.MergeChildRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.anime.AnimeMapper
import tachiyomi.data.subscribeToList
import tachiyomi.domain.anime.model.Anime

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergeChildRepositoryImpl(
    private val database: Database,
) : MergeChildRepository {

    override suspend fun getChildrenByMergeParentId(mergeParentId: Long): List<Anime> {
        return database.merge_childrenQueries.getChildrenByMergeParentId(
            mergeParentId,
            AnimeMapper::mapAnime,
        ).awaitAsList()
    }

    override suspend fun getMergeParentsByAnimeId(animeId: Long): List<Anime> {
        return database.merge_childrenQueries.getMergeParentsByAnimeId(
            animeId,
            AnimeMapper::mapAnime,
        ).awaitAsList()
    }

    override suspend fun getChildSeasonsByMergeParentId(mergeParentId: Long): Map<Long, Long> {
        return database.merge_childrenQueries.getChildSeasonsByMergeParentId(
            mergeParentId,
            ::Pair,
        ).awaitAsList().toMap()
    }

    // AM (CUSTOM_EPISODE_ORDER) -->
    override suspend fun getChildOrderingByMergeParentId(mergeParentId: Long): List<MergeChildOrdering> {
        return database.merge_childrenQueries.getChildOrderingByMergeParentId(
            mergeParentId,
            ::MergeChildOrdering,
        ).awaitAsList()
    }

    override fun getChildOrderingByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<MergeChildOrdering>> {
        return database.merge_childrenQueries.getChildOrderingByMergeParentId(
            mergeParentId,
            ::MergeChildOrdering,
        ).subscribeToList()
    }
    // <-- AM (CUSTOM_EPISODE_ORDER)

    // AM (MERGE_SEASONS) -->
    override suspend fun setSeasonNumber(mergeParentId: Long, animeId: Long, seasonNumber: Long) {
        database.merge_childrenQueries.setSeasonNumber(seasonNumber, mergeParentId, animeId)
    }
    // <-- AM (MERGE_SEASONS)

    // AM (MERGE_SETTINGS) -->
    override suspend fun setPriorities(mergeParentId: Long, orderedChildIds: List<Long>) {
        database.transaction {
            orderedChildIds.forEachIndexed { index, animeId ->
                database.merge_childrenQueries.setPriority(index.toLong(), mergeParentId, animeId)
            }
        }
    }
    // <-- AM (MERGE_SETTINGS)

    // AM (NAMED_SEASONS) -->
    override suspend fun moveChildrenBetweenSeasons(mergeParentId: Long, fromSeason: Long, toSeason: Long) {
        database.merge_childrenQueries.moveChildrenBetweenSeasons(toSeason, mergeParentId, fromSeason)
    }
    // <-- AM (NAMED_SEASONS)

    override suspend fun getSeasonsByMergeParentId(mergeParentId: Long): List<SeasonAnime> {
        return database.merge_childrenQueries.getSeasonsByMergeParentId(
            mergeParentId,
            AnimeMapper::mapSeasonAnime,
        ).awaitAsList()
    }

    override fun getSeasonsByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<SeasonAnime>> {
        return database.merge_childrenQueries.getSeasonsByMergeParentId(
            mergeParentId,
            AnimeMapper::mapSeasonAnime,
        ).subscribeToList()
    }

    override suspend fun addChild(mergeParentId: Long, animeId: Long, priority: Long, seasonNumber: Long) {
        database.merge_childrenQueries.insert(mergeParentId, animeId, priority, seasonNumber)
    }

    override suspend fun removeChild(mergeParentId: Long, animeId: Long) {
        database.merge_childrenQueries.delete(mergeParentId, animeId)
    }

    override suspend fun removeAllChildren(mergeParentId: Long) {
        database.merge_childrenQueries.deleteByMergeParentId(mergeParentId)
    }

    override suspend fun getAllChildAnimeIds(): Set<Long> {
        return database.merge_childrenQueries.getAllChildAnimeIds().awaitAsList().toSet()
    }

    override suspend fun maxPriority(mergeParentId: Long): Long? {
        return database.merge_childrenQueries.maxPriority(mergeParentId).awaitAsOneOrNull()
    }

    override suspend fun maxSeasonNumber(mergeParentId: Long): Long? {
        return database.merge_childrenQueries.maxSeasonNumber(mergeParentId).awaitAsOneOrNull()
    }
}
// <-- AM (MERGED_SOURCES)
