package tachiyomi.domain.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.source.model.DeletableAnime

@Inject
class GetSourcesWithNonLibraryAnime(
    private val repository: AnimeRepository,
) {

    // AY -->
    fun subscribe(): Flow<List<DeletableAnime>> {
        return repository.getDeletableParentAnime()
    }

    suspend fun getDeletableChildren(parentId: Long): List<Anime> {
        return repository.getChildrenByParentId(parentId)
    }
    // <-- AY

    // AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO) -->
    /**
     * What each source holds IN the library.
     *
     * On this interactor rather than one of its own: it is the same question
     * this already answers, asked of the other half of the table, and the one
     * screen that wants it wants both together.
     */
    fun subscribeLibrary(): Flow<List<DeletableAnime>> {
        return repository.getLibraryParentAnime()
    }
    // <-- AM (A_A_SOURCE_HAS_LIBRARY_DATA_TOO)
}
