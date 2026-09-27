// AM (TAG_LIMIT) -->
package animiru.domain.anime.repository

import animiru.domain.anime.model.TagVisibility
import kotlinx.coroutines.flow.Flow

interface TagVisibilityRepository {

    /** Overrides for [animeId]. Empty for an entry whose tags the user hasn't moved. */
    fun getByAnimeIdAsFlow(animeId: Long): Flow<List<TagVisibility>>

    /**
     * Pins each tag in [visibilityByTag] to its mapped side, in one transaction -
     * a multi-tag edit is one edit, so the split never redraws half-applied.
     *
     * A map rather than one flag for the batch because pinning a mixed selection
     * where it already sits gives each tag its own side.
     */
    suspend fun upsertAll(animeId: Long, visibilityByTag: Map<String, Boolean>)

    /** Drops the pins on [tags] in one transaction, returning them to the cap. */
    suspend fun deleteAll(animeId: Long, tags: List<String>)

    /** Drops every override for [animeId], restoring the default split. */
    suspend fun deleteByAnimeId(animeId: Long)
}
// <-- AM (TAG_LIMIT)
