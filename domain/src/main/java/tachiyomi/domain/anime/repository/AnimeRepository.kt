package tachiyomi.domain.anime.repository

import aniyomi.domain.anime.model.SeasonAnime
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.model.AnimeWithEpisodeCount
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.source.model.DeletableAnime

interface AnimeRepository {

    suspend fun getAnimeById(id: Long): Anime

    fun getAnimeByIdAsFlow(id: Long): Flow<Anime>

    suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): Anime?

    fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Anime?>

    suspend fun getFavorites(): List<Anime>

    // AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF): seen entries that the
    // library cannot reach - not favorited AND not a child of a favorited
    // merged entry. Was getSeenAnimeNotInLibrary, which named only the first
    // half of that test.
    suspend fun getSeenAnimeNotReachableFromLibrary(): List<Anime>

    // AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME): the same selection in id
    // order, from just past [afterId], at most [limit] rows. Pass 0 to start.
    suspend fun getSeenAnimeNotReachableFromLibraryAfter(afterId: Long, limit: Long): List<Anime>

    suspend fun getLibraryAnime(): List<LibraryAnime>

    fun getLibraryAnimeAsFlow(): Flow<List<LibraryAnime>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Anime>>

    // AM (MERGED_SOURCES) -->
    /**
     * [getFavoritesBySourceId] widened to everything [sourceId] contributes to
     * the library, including entries that are only in it as children of a
     * merged entry and so carry favorite = 0 themselves.
     */
    fun getLibraryEntriesBySourceId(sourceId: Long): Flow<List<Anime>>
    // <-- AM (MERGED_SOURCES)

    suspend fun getDuplicateLibraryAnime(id: Long, title: String): List<AnimeWithEpisodeCount>

    suspend fun getUpcomingAnime(
        statuses: Set<Long>,
        excludedCategories: List<Long>,
        includedCategories: List<Long>,
    ): Flow<List<Anime>>

    suspend fun resetViewerFlags(): Boolean

    suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>)

    suspend fun update(update: AnimeUpdate): Boolean

    suspend fun updateAll(animeUpdates: List<AnimeUpdate>): Boolean

    suspend fun insertNetworkAnime(anime: List<Anime>): List<Anime>

    // AY -->
    suspend fun getAnimeSeasonsById(parentId: Long): List<SeasonAnime>

    fun getAnimeSeasonsByIdAsFlow(parentId: Long): Flow<List<SeasonAnime>>

    suspend fun removeParentIdByIds(animeIds: List<Long>)

    fun getDeletableParentAnime(): Flow<List<DeletableAnime>>

    // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO): the library half of the same
    // question - what a source holds that clearing it would remove.
    fun getLibraryParentAnime(): Flow<List<DeletableAnime>>

    suspend fun getChildrenByParentId(parentId: Long): List<Anime>
    // <-- AY
}
