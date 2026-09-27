// AM (TAG_LIMIT) -->
package animiru.data.anime

import animiru.domain.anime.model.TagVisibility
import animiru.domain.anime.repository.TagVisibilityRepository
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
class TagVisibilityRepositoryImpl(
    private val database: Database,
) : TagVisibilityRepository {

    override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<TagVisibility>> {
        return database.anime_tag_visibilityQueries.getByAnimeId(
            animeId,
            ::TagVisibility,
        ).subscribeToList()
    }

    override suspend fun upsertAll(animeId: Long, visibilityByTag: Map<String, Boolean>) {
        database.transaction {
            visibilityByTag.forEach { (tag, visible) ->
                database.anime_tag_visibilityQueries.upsert(animeId, tag, visible)
            }
        }
    }

    override suspend fun deleteAll(animeId: Long, tags: List<String>) {
        database.transaction {
            tags.forEach { database.anime_tag_visibilityQueries.delete(animeId, it) }
        }
    }

    override suspend fun deleteByAnimeId(animeId: Long) {
        database.anime_tag_visibilityQueries.deleteByAnimeId(animeId)
    }
}
// <-- AM (TAG_LIMIT)
