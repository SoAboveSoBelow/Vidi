// AM (TAG_LIMIT) -->
package animiru.data.anime

import animiru.domain.anime.model.TagVisibility
import animiru.domain.anime.repository.TagVisibilityRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.awaitList
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TagVisibilityRepositoryImpl(
    private val database: Database,
) : TagVisibilityRepository {

    override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<TagVisibility>> {
        return database.anime_tag_visibilityQueries.getByAnimeId(
            animeId,
            ::TagVisibility,
        ).subscribeToList()
    }

    override suspend fun getByAnimeId(animeId: Long): List<TagVisibility> {
        return database.anime_tag_visibilityQueries.getByAnimeId(
            animeId,
            ::TagVisibility,
        ).awaitList()
    }

    override suspend fun upsertAll(animeId: Long, visibilityByTag: Map<String, Boolean>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                visibilityByTag.forEach { (tag, visible) ->
                    database.anime_tag_visibilityQueries.upsert(animeId, tag, visible)
                }
            }
        }
    }

    override suspend fun deleteAll(animeId: Long, tags: List<String>) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): the whole transaction on one
        // IO thread rather than each statement on the caller's. Required, not
        // tidiness: the reads inside now dispatch themselves, so an unwrapped
        // transaction would run its statements on different threads.
        withIOContext {
            database.transaction {
                tags.forEach { database.anime_tag_visibilityQueries.delete(animeId, it) }
            }
        }
    }

    override suspend fun deleteByAnimeId(animeId: Long) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            database.anime_tag_visibilityQueries.deleteByAnimeId(animeId)
        }
    }

    // AM (TAG_LOCKS) -->
    override fun lockedVisibleTagsAsFlow(animeId: Long): Flow<List<String>?> {
        return database.anime_tag_lockQueries.getByAnimeId(animeId)
            .subscribeToList()
            .map { rows -> rows.firstOrNull()?.visible_tags }
    }

    override suspend fun setLocked(animeId: Long, visibleTags: List<String>?) {
        // AM (A_QUERY_RUNS_WHERE_THE_DATABASE_LIVES): a write, off the caller's thread.
        withIOContext {
            if (visibleTags != null) {
                database.anime_tag_lockQueries.upsert(animeId, visibleTags)
            } else {
                database.anime_tag_lockQueries.delete(animeId)
            }
        }
    }
    // <-- AM (TAG_LOCKS)
}
// <-- AM (TAG_LIMIT)
