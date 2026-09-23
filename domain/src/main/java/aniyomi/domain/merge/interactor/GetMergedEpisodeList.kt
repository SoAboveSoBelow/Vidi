// AM (MERGED_SOURCES) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.merge.model.DedupeMode
import aniyomi.domain.merge.model.MERGE_DEFAULT_SEASON_NUMBER
import aniyomi.domain.merge.model.MergeChildOrdering
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * Builds the combined episode list for a merged entry.
 *
 * Every episode of every child is kept - nothing is collapsed or deduped by
 * episode number: merging a 10-episode entry with a 20-episode entry yields
 * 30 episodes. Ordering is the watch order: children are grouped by their
 * internal season (`merge_children.season_number` - a single shared season,
 * [MERGE_DEFAULT_SEASON_NUMBER], by default; splitting children into separate
 * seasons is a later UI feature), seasons are laid out ascending, and within
 * a season children follow merge order (`merge_children.priority`) with each
 * child's episodes sorted by episode number. The player consumes this order
 * as-is and the detail screen preserves it.
 *
 * Children with no episodes (or the whole entry having no children) are
 * ordinary results, not error states - see [await]'s empty-list return.
 */
@Inject
class GetMergedEpisodeList(
    private val mergeChildRepository: MergeChildRepository,
    private val episodeRepository: EpisodeRepository,
    // AM (MERGE_SETTINGS) -->
    private val mergeSettingsRepository: MergeSettingsRepository,
    // <-- AM (MERGE_SETTINGS)
) {
    suspend fun await(mergeParentId: Long, applyScanlatorFilter: Boolean = false): List<Episode> {
        val childOrdering = mergeChildRepository.getChildOrderingByMergeParentId(mergeParentId)
        if (childOrdering.isEmpty()) return emptyList()
        // AM (MERGED_SOURCES) -->
        // One query for the whole merge rather than one per source.
        val episodesByChild = episodeRepository
            .getEpisodesByMergeParentId(mergeParentId, applyScanlatorFilter)
            .groupBy { it.animeId }
        // <-- AM (MERGED_SOURCES)
        return build(
            childOrdering = childOrdering,
            episodesByChild = episodesByChild,
            dedupeMode = mergeSettingsRepository.get(mergeParentId).dedupeMode,
        )
    }

    /**
     * The union, built from episodes the caller already has - so the reactive
     * path can use what its per-source flows just delivered instead of reading
     * them back out of the database.
     *
     * [childOrdering] need not be sorted; priority order is applied here.
     */
    fun build(
        childOrdering: List<MergeChildOrdering>,
        episodesByChild: Map<Long, List<Episode>>,
        dedupeMode: DedupeMode,
    ): List<Episode> {
        return childOrdering
            .sortedBy { it.priority }
            .withIndex()
            .groupBy { (_, child) -> child.seasonNumber }
            .toSortedMap()
            .flatMap { (_, seasonChildren) ->
                // Priority is the child's position in the priority-ordered
                // list, which is also what GetEpisodeOrder breaks
                // same-number ties with.
                val episodesByPriority = seasonChildren.map { (priority, child) ->
                    priority to episodesByChild[child.animeId].orEmpty()
                }
                // AM (MERGE_SETTINGS) -->
                dedupe(episodesByPriority, dedupeMode)
                    // <-- AM (MERGE_SETTINGS)
                    // AM (CUSTOM_EPISODE_ORDER) -->
                    // Sources interleave by number within the season (A1, B1,
                    // A2...), priority then id breaking ties - exactly
                    // GetEpisodeOrder's canonical order, so the first override
                    // never reshuffles untouched episodes.
                    .sortedWith(compareBy({ it.second.episodeNumber }, { it.first }, { it.second.id }))
                    .map { it.second }
                // <-- AM (CUSTOM_EPISODE_ORDER)
            }
    }

    // AM (MERGE_SETTINGS) -->
    /**
     * Applies one season's dedupe. Input and output are (priority, episode)
     * pairs so the caller can still order by priority afterwards.
     *
     * PRIORITY matches by episode number: a number already supplied by a
     * higher-priority source is dropped from lower ones. A source's own
     * repeats (two releases of one episode) aren't cross-source duplicates and
     * stay; unrecognised numbers are never matched. The two whole-source modes
     * keep a single source's episodes, ties going to priority.
     */
    private fun dedupe(
        episodesByPriority: List<Pair<Int, List<Episode>>>,
        mode: DedupeMode,
    ): List<Pair<Int, Episode>> {
        val flat = { groups: List<Pair<Int, List<Episode>>> ->
            groups.flatMap { (priority, episodes) -> episodes.map { priority to it } }
        }
        if (episodesByPriority.size <= 1) return flat(episodesByPriority)
        return when (mode) {
            DedupeMode.OFF -> flat(episodesByPriority)
            DedupeMode.PRIORITY -> {
                val claimed = HashSet<Double>()
                episodesByPriority.sortedBy { it.first }.flatMap { (priority, episodes) ->
                    val kept = episodes.filter { !it.isRecognizedNumber || it.episodeNumber !in claimed }
                    episodes.filter { it.isRecognizedNumber }.mapTo(claimed) { it.episodeNumber }
                    kept.map { priority to it }
                }
            }
            DedupeMode.MOST_EPISODES -> flat(
                listOf(episodesByPriority.maxWith(compareBy({ it.second.size }, { -it.first }))),
            )
            DedupeMode.HIGHEST_EPISODE -> flat(
                listOf(
                    episodesByPriority.maxWith(
                        compareBy(
                            { (_, episodes) -> episodes.filter { it.isRecognizedNumber }.maxOfOrNull { it.episodeNumber } ?: -1.0 },
                            { -it.first },
                        ),
                    ),
                ),
            )
        }
    }
    // <-- AM (MERGE_SETTINGS)
}
// <-- AM (MERGED_SOURCES)
