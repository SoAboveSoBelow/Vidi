// AM (EPISODE_TAGS) -->
package animiru.data.episode

import animiru.domain.episode.model.EpisodeTag
import animiru.domain.episode.repository.EpisodeTagRepository
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
class EpisodeTagRepositoryImpl(
    private val database: Database,
) : EpisodeTagRepository {

    override suspend fun getByAnimeId(animeId: Long): List<EpisodeTag> {
        return database.episode_tagsQueries.getByAnimeId(animeId, ::EpisodeTag).awaitList()
    }

    override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<EpisodeTag>> {
        return database.episode_tagsQueries.getByAnimeId(animeId, ::EpisodeTag).subscribeToList()
    }

    override suspend fun getByMergeParentId(mergeParentId: Long): List<EpisodeTag> {
        return database.episode_tagsQueries.getByMergeParentId(mergeParentId, ::EpisodeTag).awaitList()
    }

    override fun getByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<EpisodeTag>> {
        return database.episode_tagsQueries.getByMergeParentId(mergeParentId, ::EpisodeTag).subscribeToList()
    }

    override suspend fun getByEpisodeId(episodeId: Long): List<EpisodeTag> {
        return database.episode_tagsQueries.getByEpisodeId(episodeId, ::EpisodeTag).awaitList()
    }

    override suspend fun upsertAll(episodeId: Long, tagsByName: Map<String, Boolean>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                tagsByName.forEach { (tag, visible) ->
                    database.episode_tagsQueries.upsert(episodeId, tag, visible)
                }
            }
        }
    }

    override suspend fun deleteAll(episodeId: Long, tags: Collection<String>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                tags.forEach { database.episode_tagsQueries.delete(episodeId, it) }
            }
        }
    }

    override suspend fun deleteByAnimeIdAndTag(animeId: Long, tag: String) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.episode_tagsQueries.deleteByAnimeIdAndTag(tag, animeId)
        }
    }
}
// <-- AM (EPISODE_TAGS)
