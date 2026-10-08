// AM -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import animiru.domain.anime.repository.TagVisibilityRepository
import aniyomi.domain.episode.repository.EpisodeNameRepository
import aniyomi.domain.order.model.EpisodeOrderOverride
import aniyomi.domain.order.repository.EpisodeOrderRepository
import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.repository.EntrySeasonRepository
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import tachiyomi.domain.anime.model.Anime

/**
 * The per-entry arrangements that are neither the entry nor its episodes: season
 * names and order, renamed episodes, a custom episode order, and pinned tags.
 *
 * All of it was silently dropped by every restore before this, because none of
 * these tables were in the backup at all. Each is sparse by design - a row exists
 * only where the user changed something - so an entry nobody arranged restores
 * exactly as it did before.
 */
@Inject
class EntryExtrasRestorer(
    private val entrySeasonRepository: EntrySeasonRepository,
    private val episodeNameRepository: EpisodeNameRepository,
    private val episodeOrderRepository: EpisodeOrderRepository,
    private val tagVisibilityRepository: TagVisibilityRepository,
) {

    suspend fun restore(anime: Anime, backupAnime: BackupAnime, resolver: BackupEntryResolver) {
        restoreSeasons(anime, backupAnime)
        restoreTagVisibility(anime, backupAnime)
        restoreEpisodeNames(anime, backupAnime, resolver)
        restoreEpisodeOrder(anime, backupAnime, resolver)
    }

    private suspend fun restoreSeasons(anime: Anime, backupAnime: BackupAnime) {
        if (backupAnime.entrySeasons.isEmpty()) return

        entrySeasonRepository.upsertAll(
            hostAnimeId = anime.id,
            seasons = backupAnime.entrySeasons.map {
                // season_number is the season's stable identity and is never
                // renumbered, so it carries across verbatim - merge children and
                // episode order both point at it.
                EntrySeason(number = it.number, name = it.name, sortOrder = it.sortOrder)
            },
        )
    }

    private suspend fun restoreTagVisibility(anime: Anime, backupAnime: BackupAnime) {
        if (backupAnime.tagVisibility.isEmpty()) return

        tagVisibilityRepository.upsertAll(
            animeId = anime.id,
            visibilityByTag = backupAnime.tagVisibility.associate { it.tag to it.visible },
        )
    }

    private suspend fun restoreEpisodeNames(
        anime: Anime,
        backupAnime: BackupAnime,
        resolver: BackupEntryResolver,
    ) {
        backupAnime.episodes.forEach { backupEpisode ->
            val customName = backupEpisode.customName ?: return@forEach
            val episodeId = resolver.episodeId(anime.id, backupEpisode.url) ?: return@forEach
            episodeNameRepository.set(episodeId, customName)
        }
    }

    private suspend fun restoreEpisodeOrder(
        anime: Anime,
        backupAnime: BackupAnime,
        resolver: BackupEntryResolver,
    ) {
        if (backupAnime.episodeOrder.isEmpty()) return

        val overrides = backupAnime.episodeOrder.mapNotNull { stored ->
            // The episode belongs to whichever entry owns it, which for a merged
            // entry is a child rather than the host being restored here.
            val episodeId = resolver.episodeId(
                source = stored.animeSource,
                url = stored.animeUrl,
                episodeUrl = stored.episodeUrl,
            ) ?: return@mapNotNull null
            EpisodeOrderOverride(
                hostAnimeId = anime.id,
                episodeId = episodeId,
                seasonNumber = stored.seasonNumber,
                sortKey = stored.sortKey,
            )
        }

        if (overrides.isNotEmpty()) {
            episodeOrderRepository.upsertAll(overrides)
        }
    }
}
// <-- AM
