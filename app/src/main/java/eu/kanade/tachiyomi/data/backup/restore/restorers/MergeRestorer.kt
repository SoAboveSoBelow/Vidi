// AM (MERGED_SOURCES) -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import aniyomi.domain.merge.model.DedupeMode
import aniyomi.domain.merge.model.MergeSettings
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
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

    suspend fun restore(parent: Anime, backupAnime: BackupAnime, resolver: BackupEntryResolver) {
        if (backupAnime.mergeChildren.isEmpty()) return

        // Replace rather than merge: a parent's membership is a set the user
        // arranged, and adding to whatever is already there would leave a
        // half-restored merge carrying children from both devices.
        mergeChildRepository.removeAllChildren(parent.id)

        backupAnime.mergeChildren.forEach { child ->
            val childAnime = resolver.anime(child.animeSource, child.animeUrl) ?: return@forEach
            mergeChildRepository.addChild(
                mergeParentId = parent.id,
                animeId = childAnime.id,
                priority = child.priority,
                seasonNumber = child.seasonNumber,
            )
        }

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
