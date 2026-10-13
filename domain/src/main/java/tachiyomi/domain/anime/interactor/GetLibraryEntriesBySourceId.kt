// AM (MERGED_SOURCES) -->
package tachiyomi.domain.anime.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository

/**
 * Everything a source contributes to the library.
 *
 * [GetFavorites.subscribe] answers the narrower question - which of this
 * source's entries carry the favorite flag - and that is wrong for anything
 * offering to act on a source's library content. An entry that only exists as
 * a child of a merged entry has favorite = 0 while the library still plays it,
 * so asking for favorites alone makes those sources look empty.
 */
@Inject
class GetLibraryEntriesBySourceId(
    private val animeRepository: AnimeRepository,
) {

    fun subscribe(sourceId: Long): Flow<List<Anime>> {
        return animeRepository.getLibraryEntriesBySourceId(sourceId)
    }
}
// <-- AM (MERGED_SOURCES)
