// AM (MERGED_SOURCES) -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import aniyomi.domain.merge.model.DedupeMode
import aniyomi.domain.merge.model.MergeSettings
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupMergeChild
import tachiyomi.domain.anime.model.Anime

/**
 * Rebuilds a merged entry's membership, settings and exclusions.
 *
 * The merge parent itself is an ordinary favourite anime row and always restored
 * by [AnimeRestorer]; what was missing was everything that gave it content. A
 * parent with no children shows an empty entry, which is exactly the symptom of
 * the merge tables never having been in a backup.
 *
 * Runs in the restore's second pass, once every entry exists: a child cannot be
 * linked before the row it points at has been inserted.
 */
@Inject
class MergeRestorer(
    private val mergeChildRepository: MergeChildRepository,
    private val mergeSettingsRepository: MergeSettingsRepository,
    private val mergeEpisodeExclusionRepository: MergeEpisodeExclusionRepository,
) {

    // AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF) -->
    /**
     * What a merge's restore came to.
     *
     * @param declared children the backup says the merge had
     * @param resolved children that were found on this device
     * @param keptExisting membership was left as it already was here, because
     *   the backup could not account for all of it and this device had a merge
     *   worth more than the part of one on offer
     */
    data class Result(
        val declared: Int,
        val resolved: Int,
        val keptExisting: Boolean,
    ) {
        val complete get() = declared == resolved
    }
    // <-- AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF)

    suspend fun restore(parent: Anime, backupAnime: BackupAnime, resolver: BackupEntryResolver): Result {
        if (backupAnime.mergeChildren.isEmpty() && backupAnime.mergeChildCount == 0) {
            return Result(declared = 0, resolved = 0, keptExisting = false)
        }

        // AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF) -->
        // What the merge held when it was written, which is not the same as what
        // the file carries: mergeChildCount is taken before the creator can drop
        // a child, and is 0 in a backup written before the field existed - there,
        // the records present are all that can be claimed.
        val declared = maxOf(backupAnime.mergeChildCount, backupAnime.mergeChildren.size)

        // Resolved up front, because whether every child was found decides
        // whether this device's own membership may be thrown away at all.
        val resolved = backupAnime.mergeChildren.mapNotNull { child ->
            resolver.anime(child.animeSource, child.animeUrl)?.let { child to it }
        }

        if (resolved.size < declared) {
            // An incomplete set is the one case where replacing destroys more
            // than it restores: a correct merge here would be cut down to
            // whatever the file could still account for, and the user would see
            // a successful restore. So the parent keeps what it has, unless it
            // has nothing - then a partial merge beats an empty one, and there
            // is nothing to lose by writing it.
            val existing = mergeChildRepository.getChildrenByMergeParentId(parent.id)
            if (existing.isNotEmpty()) {
                return Result(declared = declared, resolved = resolved.size, keptExisting = true)
            }
            addChildren(parent, resolved)
            // Settings and exclusions still apply: they belong to the parent,
            // and the membership they sit on is the one just written.
            restoreSettingsAndExclusions(parent, backupAnime, resolver)
            return Result(declared = declared, resolved = resolved.size, keptExisting = false)
        }
        // <-- AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF)

        // Replace rather than merge: a parent's membership is a set the user
        // arranged, and adding to whatever is already there would leave a
        // half-restored merge carrying children from both devices.
        mergeChildRepository.removeAllChildren(parent.id)
        addChildren(parent, resolved)
        restoreSettingsAndExclusions(parent, backupAnime, resolver)

        return Result(declared = declared, resolved = resolved.size, keptExisting = false)
    }

    private suspend fun addChildren(
        parent: Anime,
        resolved: List<Pair<BackupMergeChild, Anime>>,
    ) {
        resolved.forEach { (child, childAnime) ->
            mergeChildRepository.addChild(
                mergeParentId = parent.id,
                animeId = childAnime.id,
                priority = child.priority,
                seasonNumber = child.seasonNumber,
            )
        }
    }

    private suspend fun restoreSettingsAndExclusions(
        parent: Anime,
        backupAnime: BackupAnime,
        resolver: BackupEntryResolver,
    ) {
        backupAnime.mergeSettings?.let { settings ->
            val infoAnime = resolver.anime(settings.infoAnimeSource, settings.infoAnimeUrl)
            mergeSettingsRepository.set(
                mergeParentId = parent.id,
                settings = MergeSettings(
                    dedupeMode = DedupeMode.from(settings.dedupeMode),
                    // Null falls back to the highest-priority child, which is the
                    // right outcome when the entry that supplied the details was
                    // not itself restored.
                    infoAnimeId = infoAnime?.id,
                ),
            )
        }

        backupAnime.mergeExclusions.forEach { exclusion ->
            val episodeId = resolver.episodeId(
                source = exclusion.animeSource,
                url = exclusion.animeUrl,
                episodeUrl = exclusion.episodeUrl,
            ) ?: return@forEach
            mergeEpisodeExclusionRepository.add(hostAnimeId = parent.id, episodeId = episodeId)
        }
    }
}
// <-- AM (MERGED_SOURCES)
