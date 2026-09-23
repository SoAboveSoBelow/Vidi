// AM (MERGE_SETTINGS) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.repository.AnimeRepository

/**
 * Brings a merged entry's details in line with its sources.
 *
 * Everything but tags comes from the info entry - the source chosen in merge
 * settings, or the highest-priority source if none is.
 *
 * Tags are drawn from every source, keeping the [MAX_TAGS] most popular: the
 * ones the most sources agree on. A merge of dozens of sources otherwise
 * accumulates hundreds, which say little about the entry and are slow to lay
 * out when the description is expanded. Duplicates differing only in case are
 * counted as one, keeping the first spelling seen in priority order, and ties
 * are broken the same way.
 *
 * Called whenever an input changes: creating the merge, adding or removing a
 * source, changing the info entry, and refreshing the merge (sources' own
 * details can change on refresh).
 */
@Inject
class SyncMergedEntryInfo(
    private val animeRepository: AnimeRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val mergeSettingsRepository: MergeSettingsRepository,
) {
    suspend fun await(mergeParentId: Long) {
        // Already ordered by priority ascending.
        val children = mergeChildRepository.getChildrenByMergeParentId(mergeParentId)
        if (children.isEmpty()) return
        val infoAnimeId = mergeSettingsRepository.get(mergeParentId).infoAnimeId
        val info = children.firstOrNull { it.id == infoAnimeId } ?: children.first()

        val tags = children
            // A source listing a tag twice shouldn't count twice.
            .flatMap { child -> child.genre.orEmpty().map { it.trim() }.distinctBy(String::lowercase) }
            .filter { it.isNotEmpty() }
            .withIndex()
            .groupBy { (_, tag) -> tag.lowercase() }
            .values
            .map { duplicates ->
                RankedTag(
                    tag = duplicates.first().value,
                    sources = duplicates.size,
                    firstSeen = duplicates.first().index,
                )
            }
            .sortedWith(compareByDescending<RankedTag> { it.sources }.thenBy { it.firstSeen })
            .take(MAX_TAGS)
            .map { it.tag }

        animeRepository.update(
            AnimeUpdate(
                id = mergeParentId,
                title = info.ogTitle,
                artist = info.artist,
                author = info.author,
                description = info.description,
                genre = tags,
                status = info.status,
                thumbnailUrl = info.thumbnailUrl,
            ),
        )
    }

    /** [sources] is how many sources list the tag; [firstSeen] breaks ties by priority order. */
    private data class RankedTag(val tag: String, val sources: Int, val firstSeen: Int)

    companion object {
        /** How many tags a merged entry keeps, most popular first. */
        const val MAX_TAGS = 20
    }
}
// <-- AM (MERGE_SETTINGS)
