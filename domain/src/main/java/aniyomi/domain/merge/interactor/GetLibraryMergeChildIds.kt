// AM (MERGED_SOURCES) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.merge.repository.MergeChildRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/**
 * The anime that belong to the library by way of a merged entry rather than by
 * their own favorite flag.
 *
 * The rest of the app treats animes.favorite as the definition of "in library".
 * A merge child does not carry it: creating a merge leaves the originals
 * favorited, but the post-merge prompt to remove them clears the flag while the
 * merge keeps depending on the row. Such an entry is in the user's library in
 * every sense that matters to them - its episodes are in a library entry they
 * can open - so any surface that asks "do I already have this" has to union
 * this set with the flag.
 *
 * Deliberately not folded into favorite itself: these entries must stay out of
 * the library grid, out of the favorites sweep, and must still show an empty
 * heart on their own detail screen, which is what distinguishes "reachable
 * through a merge" from "in the library in its own right".
 */
@Inject
class GetLibraryMergeChildIds(
    private val mergeChildRepository: MergeChildRepository,
) {

    suspend fun await(): Set<Long> = mergeChildRepository.getLibraryChildAnimeIds()

    fun subscribe(): Flow<Set<Long>> = mergeChildRepository.getLibraryChildAnimeIdsAsFlow()
}
// <-- AM (MERGED_SOURCES)
