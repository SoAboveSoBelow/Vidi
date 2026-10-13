// AM (MERGED_SOURCES) -->
package aniyomi.domain.merge.interactor

import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import aniyomi.domain.order.repository.EpisodeOrderRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * Swaps one source for another inside every merge the old source belongs to,
 * keeping the slot it occupied.
 *
 * This is what makes migration safe for a merged entry. Migration replaces a
 * library row with a row from another source; a merge refers to the old row by
 * id, so without this the merge keeps serving the source the user has just
 * migrated away from, while the replacement sits in the library as a separate
 * unmerged entry.
 *
 * The slot - the child's season and priority - is the position the user chose
 * in merge settings, so it transfers rather than the replacement being appended
 * at the end.
 *
 * The departing source's episode-keyed rows go the same way they do in
 * [RemoveFromMerge]: custom-order positions and episode exclusions describe
 * episodes that are no longer part of the merge's list, and left behind they
 * would linger in backups and silently re-apply if that source were ever added
 * back. They are dropped rather than remapped onto the replacement's episodes -
 * matching them up is per-episode work with no reliable key, and a wrong
 * mapping is worse than a cleared one.
 *
 * Unlike [RemoveFromMerge] the old row is NOT put back in the library: the
 * whole point of a migration is that it stops being the entry the user keeps.
 * Its unfavoriting is migration's own business, and happens there.
 */
@Inject
class ReplaceMergeChild(
    private val episodeRepository: EpisodeRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val mergeSettingsRepository: MergeSettingsRepository,
    private val mergeEpisodeExclusionRepository: MergeEpisodeExclusionRepository,
    private val episodeOrderRepository: EpisodeOrderRepository,
    private val syncMergedEntryInfo: SyncMergedEntryInfo,
) {

    /**
     * @return the merges that were changed. Zero means [oldChild] was in none,
     *   which is the ordinary case for an entry that was never merged.
     */
    suspend fun await(oldChild: Anime, newChild: Anime): Int {
        if (oldChild.id == newChild.id) return 0
        val parents = mergeChildRepository.getMergeParentsByAnimeId(oldChild.id)
        if (parents.isEmpty()) return 0

        val oldEpisodeIds = episodeRepository.getEpisodeByAnimeId(oldChild.id).map { it.id }
        var changed = 0

        parents.forEach { parent ->
            val ordering = mergeChildRepository.getChildOrderingByMergeParentId(parent.id)
            val slot = ordering.firstOrNull { it.animeId == oldChild.id } ?: return@forEach
            // The replacement may already be a source of this same merge, which
            // the primary key forbids twice over. Then the swap is just a
            // removal: the slot the user is migrating out of collapses into the
            // one the replacement already holds.
            val alreadyPresent = ordering.any { it.animeId == newChild.id }

            episodeOrderRepository.deleteAll(parent.id, oldEpisodeIds)
            mergeEpisodeExclusionRepository.removeAll(parent.id, oldEpisodeIds)
            mergeChildRepository.removeChild(parent.id, oldChild.id)
            if (!alreadyPresent) {
                mergeChildRepository.addChild(parent.id, newChild.id, slot.priority, slot.seasonNumber)
            }

            // The merge drew its details from the source being replaced, so the
            // replacement inherits that job - or, if it was already a source
            // here, the merge falls back to the default (highest priority).
            val settings = mergeSettingsRepository.get(parent.id)
            if (settings.infoAnimeId == oldChild.id) {
                mergeSettingsRepository.set(
                    parent.id,
                    settings.copy(infoAnimeId = newChild.id.takeUnless { alreadyPresent }),
                )
            }

            // The merged entry's title, description and ranked tags are derived
            // from its sources, and one of them has just changed.
            syncMergedEntryInfo.await(parent.id)
            changed++
        }

        return changed
    }
}
// <-- AM (MERGED_SOURCES)
