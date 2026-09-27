// AM (TAG_LIMIT) -->
package animiru.domain.anime.interactor

import aniyomi.domain.merge.repository.MergeChildRepository
import dev.zacsweers.metro.Inject

@Inject
class GetTagSourceCounts(
    private val mergeChildRepository: MergeChildRepository,
) {

    /**
     * How many of a merged entry's sources list each tag, keyed by lowercased
     * tag - the same count SyncMergedEntryInfo ranks by, so the popout can show
     * why a tag sits where it does.
     *
     * Empty for an ordinary entry and for a single-source merge, where every tag
     * would read 1 and the number would be noise rather than information. A
     * source listing a tag twice counts once, matching the ranking.
     *
     * Queried when the popout opens rather than held in screen state: it costs a
     * read of the children, and nothing outside the popout shows it.
     */
    suspend fun await(animeId: Long): Map<String, Int> {
        val children = mergeChildRepository.getChildrenByMergeParentId(animeId)
        if (children.size < 2) return emptyMap()
        return children
            .flatMap { child -> child.genre.orEmpty().map { it.trim() }.distinctBy(String::lowercase) }
            .filter { it.isNotEmpty() }
            .groupingBy(String::lowercase)
            .eachCount()
    }
}
// <-- AM (TAG_LIMIT)
