// AM (MERGE_MARK_TRANSFER) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * Carries bookmarks and fillermarks onto the episodes a merge actually shows.
 *
 * Merging never copies episodes - every episode still belongs to the source it
 * came from, marks included - but deduplication HIDES rows from the merged
 * list: PRIORITY drops a number a higher-priority source already supplies, and
 * the two whole-source modes keep one source's episodes and nothing else. A
 * mark on a hidden row is still in the database and still shows on its own
 * source's entry, but it has disappeared from the merge, which reads as the
 * mark having been lost.
 *
 * So each hidden mark is copied onto the episode that stands in for it in the
 * merged list - the visible episode with the same number. From then on the two
 * are independent: unmarking in the merge changes the visible episode,
 * unmarking on the source changes that source's own episode, and neither
 * touches the other.
 *
 * Only ever adds a mark. Nothing here clears one, so running it again - on the
 * next source added, or the next dedupe change - cannot undo a mark the user
 * has since removed on either side.
 */
@Inject
class TransferMergeEpisodeMarks(
    private val mergeChildRepository: MergeChildRepository,
    private val mergeSettingsRepository: MergeSettingsRepository,
    private val episodeRepository: EpisodeRepository,
    private val getMergedEpisodeList: GetMergedEpisodeList,
) {

    suspend fun await(mergeParentId: Long) {
        val childOrdering = mergeChildRepository.getChildOrderingByMergeParentId(mergeParentId)
        if (childOrdering.size < 2) return

        val episodesByChild = episodeRepository
            .getEpisodesByMergeParentId(mergeParentId)
            .groupBy { it.animeId }
        val all = episodesByChild.values.flatten()
        if (all.none { it.bookmark || it.fillermark }) return

        val visible = getMergedEpisodeList.build(
            childOrdering = childOrdering,
            episodesByChild = episodesByChild,
            dedupeMode = mergeSettingsRepository.get(mergeParentId).dedupeMode,
        )
        // Nothing is hidden with dedupe off, so there is nothing to carry over.
        if (visible.size == all.size) return

        val visibleIds = visible.mapTo(HashSet()) { it.id }
        // The stand-in for a hidden episode is the visible one with its number.
        // An unrecognised number (-1) identifies nothing, so those are skipped
        // rather than guessed at.
        val visibleByNumber = visible
            .filter { it.isRecognizedNumber }
            .groupBy { it.episodeNumber }

        val updates = HashMap<Long, EpisodeUpdate>()
        all.asSequence()
            .filter { it.id !in visibleIds && (it.bookmark || it.fillermark) && it.isRecognizedNumber }
            .forEach { hidden ->
                visibleByNumber[hidden.episodeNumber].orEmpty().forEach { target ->
                    val pending = updates[target.id]
                    val bookmark = needsMark(hidden.bookmark, target.bookmark, pending?.bookmark)
                    val fillermark = needsMark(hidden.fillermark, target.fillermark, pending?.fillermark)
                    if (bookmark == null && fillermark == null) return@forEach
                    updates[target.id] = EpisodeUpdate(
                        id = target.id,
                        bookmark = bookmark ?: pending?.bookmark,
                        fillermark = fillermark ?: pending?.fillermark,
                    )
                }
            }
        if (updates.isEmpty()) return
        episodeRepository.updateAll(updates.values.toList())
    }

    /** True to write, or null to leave alone - the mark is not set, or is already there. */
    private fun needsMark(hidden: Boolean, target: Boolean, pending: Boolean?): Boolean? {
        return true.takeIf { hidden && !target && pending != true }
    }
}
// <-- AM (MERGE_MARK_TRANSFER)
