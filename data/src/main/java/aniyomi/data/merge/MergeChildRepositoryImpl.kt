// AM (MERGED_SOURCES) -->
package aniyomi.data.merge

import aniyomi.domain.anime.model.SeasonAnime
import aniyomi.domain.merge.model.MergeChildOrdering
import aniyomi.domain.merge.repository.MergeChildRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.anime.AnimeMapper
import tachiyomi.data.awaitList
import tachiyomi.data.awaitOneOrNull
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
        ).awaitList()
    }

    override suspend fun getMergeParentsByAnimeId(animeId: Long): List<Anime> {
        return database.merge_childrenQueries.getMergeParentsByAnimeId(
            animeId,
            AnimeMapper::mapAnime,
        ).awaitList()
    }

    override suspend fun getChildSeasonsByMergeParentId(mergeParentId: Long): Map<Long, Long> {
        return database.merge_childrenQueries.getChildSeasonsByMergeParentId(
            mergeParentId,
            ::Pair,
        ).awaitList().toMap()
    }

    // AM (CUSTOM_EPISODE_ORDER) -->
    override suspend fun getChildOrderingByMergeParentId(mergeParentId: Long): List<MergeChildOrdering> {
        return database.merge_childrenQueries.getChildOrderingByMergeParentId(
            mergeParentId,
            ::MergeChildOrdering,
        ).awaitList()
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
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.merge_childrenQueries.setSeasonNumber(seasonNumber, mergeParentId, animeId)
        }
    }
    // <-- AM (MERGE_SEASONS)

    // AM (MERGE_SETTINGS) -->
    override suspend fun setPriorities(mergeParentId: Long, orderedChildIds: List<Long>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                orderedChildIds.forEachIndexed { index, animeId ->
                    database.merge_childrenQueries.setPriority(index.toLong(), mergeParentId, animeId)
                }
            }
        }
    }
    // <-- AM (MERGE_SETTINGS)

    // AM (NAMED_SEASONS) -->
    override suspend fun moveChildrenBetweenSeasons(mergeParentId: Long, fromSeason: Long, toSeason: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.merge_childrenQueries.moveChildrenBetweenSeasons(toSeason, mergeParentId, fromSeason)
        }
    }
    // <-- AM (NAMED_SEASONS)

    override suspend fun getSeasonsByMergeParentId(mergeParentId: Long): List<SeasonAnime> {
        return database.merge_childrenQueries.getSeasonsByMergeParentId(
            mergeParentId,
            AnimeMapper::mapSeasonAnime,
        ).awaitList()
    }

    override fun getSeasonsByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<SeasonAnime>> {
        return database.merge_childrenQueries.getSeasonsByMergeParentId(
            mergeParentId,
            AnimeMapper::mapSeasonAnime,
        ).subscribeToList()
    }

    override suspend fun addChild(mergeParentId: Long, animeId: Long, priority: Long, seasonNumber: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.merge_childrenQueries.insert(mergeParentId, animeId, priority, seasonNumber)
        }
    }

    override suspend fun removeChild(mergeParentId: Long, animeId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.merge_childrenQueries.delete(mergeParentId, animeId)
        }
    }

    override suspend fun removeAllChildren(mergeParentId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.merge_childrenQueries.deleteByMergeParentId(mergeParentId)
        }
    }

    override suspend fun getAllChildAnimeIds(): Set<Long> {
        return database.merge_childrenQueries.getAllChildAnimeIds().awaitList().toSet()
    }

    override suspend fun getLibraryChildAnimeIds(): Set<Long> {
        return database.merge_childrenQueries.getLibraryMergeChildAnimeIds().awaitList().toSet()
    }

    // AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF) -->
    override suspend fun getNonFavoriteLibraryChildren(): List<Anime> {
        return database.merge_childrenQueries.getNonFavoriteLibraryMergeChildren(
            AnimeMapper::mapAnime,
        ).awaitList()
    }
    // <-- AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF)

    override fun getLibraryChildAnimeIdsAsFlow(): Flow<Set<Long>> {
        return database.merge_childrenQueries.getLibraryMergeChildAnimeIds()
            .subscribeToList()
            .map { it.toSet() }
    }

    override suspend fun maxPriority(mergeParentId: Long): Long? {
        return database.merge_childrenQueries.maxPriority(mergeParentId).awaitOneOrNull()
    }

    override suspend fun maxSeasonNumber(mergeParentId: Long): Long? {
        return database.merge_childrenQueries.maxSeasonNumber(mergeParentId).awaitOneOrNull()
    }
}
// <-- AM (MERGED_SOURCES)
