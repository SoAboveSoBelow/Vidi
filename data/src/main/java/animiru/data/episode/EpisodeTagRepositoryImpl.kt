// AM (EPISODE_TAGS) -->
package animiru.data.episode

import animiru.domain.episode.model.EpisodeTag
import animiru.domain.episode.repository.EpisodeTagRepository
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
class EpisodeTagRepositoryImpl(
    private val database: Database,
) : EpisodeTagRepository {

    override suspend fun getByAnimeId(animeId: Long): List<EpisodeTag> {
        return database.episode_tagsQueries.getByAnimeId(animeId, ::EpisodeTag).awaitAsList()
    }

    override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<EpisodeTag>> {
        return database.episode_tagsQueries.getByAnimeId(animeId, ::EpisodeTag).subscribeToList()
    }

    override suspend fun getByMergeParentId(mergeParentId: Long): List<EpisodeTag> {
        return database.episode_tagsQueries.getByMergeParentId(mergeParentId, ::EpisodeTag).awaitAsList()
    }

    override fun getByMergeParentIdAsFlow(mergeParentId: Long): Flow<List<EpisodeTag>> {
        return database.episode_tagsQueries.getByMergeParentId(mergeParentId, ::EpisodeTag).subscribeToList()
    }

    override suspend fun getByEpisodeId(episodeId: Long): List<EpisodeTag> {
        return database.episode_tagsQueries.getByEpisodeId(episodeId, ::EpisodeTag).awaitAsList()
    }

    override suspend fun upsertAll(episodeId: Long, tagsByName: Map<String, Boolean>) {
        database.transaction {
            tagsByName.forEach { (tag, visible) ->
                database.episode_tagsQueries.upsert(episodeId, tag, visible)
            }
        }
    }

    override suspend fun deleteAll(episodeId: Long, tags: Collection<String>) {
        database.transaction {
            tags.forEach { database.episode_tagsQueries.delete(episodeId, it) }
        }
    }

    override suspend fun deleteByAnimeIdAndTag(animeId: Long, tag: String) {
        database.episode_tagsQueries.deleteByAnimeIdAndTag(tag, animeId)
    }
}
// <-- AM (EPISODE_TAGS)
