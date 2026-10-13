package mihon.domain.migration.usecases

import animiru.domain.download.model.PlannedDownloadOrigin
import animiru.domain.download.repository.PlannedDownloadRepository
import aniyomi.domain.merge.interactor.ReplaceMergeChild
import dev.zacsweers.metro.Inject
import eu.kanade.domain.anime.interactor.TransferMigratedEntryState
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.model.hasCustomBackground
import eu.kanade.domain.anime.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.cache.BackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.toEpisodeUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import kotlin.time.Clock

@Inject
class MigrateAnimeUseCase(
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateAnime: UpdateAnime,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val updateEpisode: UpdateEpisode,
    private val getCategories: GetCategories,
    private val setAnimeCategories: SetAnimeCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
    // AY -->
    private val backgroundCache: BackgroundCache,
    // <-- AY
    private val updateAnimeFromRemote: UpdateAnimeFromRemote,
    // AM (MIGRATION_CARRY_OVER)
    private val transferMigratedEntryState: TransferMigratedEntryState,
    // AM (MERGED_SOURCES)
    private val replaceMergeChild: ReplaceMergeChild,
    // AM (PLANNED_DOWNLOADS)
    private val plannedDownloadRepository: PlannedDownloadRepository,
) {
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    suspend operator fun invoke(current: Anime, target: Anime, replace: Boolean) {
        val targetSource = sourceManager.get(target.source) ?: return
        val currentSource = sourceManager.get(current.source)
        val flags = sourcePreferences.migrationFlags.get()

        try {
            when (target.fetchType) {
                // AY -->
                FetchType.Seasons -> {
                    updateAnimeFromRemote.awaitSeasonsUpdate(target, fetchSeasons = true).getOrThrow()
                }
                // <-- AY
                FetchType.Episodes -> {
                    updateAnimeFromRemote.awaitEpisodesUpdate(target, fetchEpisodes = true).getOrThrow()
                }
            }

            // Update episodes seen, bookmark and dateFetch
            // AY -->
            if (MigrationFlag.EPISODE in flags && target.fetchType == FetchType.Episodes) {
                // <-- AY
                // AM: sorted so index means the source's own order, which is what
                // the positional fallback below leans on.
                val prevAnimeEpisodes = getEpisodesByAnimeId.await(current.id).sortedBy { it.sourceOrder }
                val animeEpisodes = getEpisodesByAnimeId.await(target.id).sortedBy { it.sourceOrder }

                val maxEpisodeSeen = prevAnimeEpisodes
                    .filter { it.seen }
                    .maxOfOrNull { it.episodeNumber }

                val updatedAnimeEpisodes = animeEpisodes.mapIndexed { index, animeEpisode ->
                    var updatedEpisode = animeEpisode
                    // AM -->
                    // Real number first. Position is the fallback only for an
                    // episode whose number could not be parsed at all - a parsed
                    // number the old source never had is a genuine absence, not
                    // something to guess a neighbour for.
                    val prevEpisode = if (updatedEpisode.isRecognizedNumber) {
                        prevAnimeEpisodes
                            .find { it.isRecognizedNumber && it.episodeNumber == updatedEpisode.episodeNumber }
                    } else {
                        prevAnimeEpisodes.getOrNull(index)
                    }

                    if (prevEpisode != null) {
                        updatedEpisode = updatedEpisode.copy(
                            dateFetch = prevEpisode.dateFetch,
                            bookmark = prevEpisode.bookmark,
                        )
                    }

                    if (updatedEpisode.isRecognizedNumber) {
                        if (maxEpisodeSeen != null && updatedEpisode.episodeNumber <= maxEpisodeSeen) {
                            updatedEpisode = updatedEpisode.copy(seen = true)
                        }
                    } else if (prevEpisode?.seen == true) {
                        // No number to compare against the watched high-water
                        // mark, so the matched episode's own state stands in.
                        updatedEpisode = updatedEpisode.copy(seen = true)
                    }
                    // <-- AM

                    updatedEpisode
                }

                val episodeUpdates = updatedAnimeEpisodes.map { it.toEpisodeUpdate() }
                updateEpisode.awaitAll(episodeUpdates)
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setAnimeCategories.await(target.id, categoryIds)
            }

            // Update track
            getTracks.await(current.id).mapNotNull { track ->
                val updatedTrack = track.copy(animeId = target.id)

                val service = enhancedServices
                    .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                if (service != null) {
                    service.migrateTrack(updatedTrack, target, targetSource)
                } else {
                    updatedTrack
                }
            }
                .takeIf { it.isNotEmpty() }
                ?.let { insertTrack.awaitAll(it) }

            // AM (PLANNED_DOWNLOADS) -->
            // What was downloaded on the old source becomes intent to download
            // it on the new one. The files themselves cannot move - they belong
            // to the old source's folder and are its encodes - so the episodes
            // go to the hold list instead and the user re-fetches them from the
            // new source when they choose to.
            //
            // Matching is by real episode number wherever both sides have one
            // parsed, since that survives a source numbering its list
            // differently, carrying specials, or starting part way into a
            // series. Position in the list is the fallback for an episode whose
            // number could not be parsed, or whose number the new source does
            // not have - it is the only thing left to go on, and it is right
            // whenever the two lists run in step.
            //
            // Not gated on REMOVE_DOWNLOAD: that flag decides whether the old
            // files are deleted, which is a separate question from whether the
            // user still wants those episodes.
            if (current.fetchType == FetchType.Episodes && target.fetchType == FetchType.Episodes) {
                val currentEpisodes = getEpisodesByAnimeId.await(current.id).sortedBy { it.sourceOrder }
                val targetEpisodes = getEpisodesByAnimeId.await(target.id).sortedBy { it.sourceOrder }

                val targetByNumber = targetEpisodes
                    .filter { it.isRecognizedNumber }
                    .associateBy { it.episodeNumber }

                val held = currentEpisodes
                    .withIndex()
                    .filter { (_, episode) ->
                        downloadManager.isEpisodeDownloaded(
                            episode.name,
                            episode.scanlator,
                            episode.url,
                            current.ogTitle,
                            current.source,
                        )
                    }
                    .mapNotNull { (index, episode) ->
                        val match = if (episode.isRecognizedNumber) {
                            targetByNumber[episode.episodeNumber]
                        } else {
                            targetEpisodes.getOrNull(index)
                        }
                        match?.let { target.id to it.id }
                    }
                    .distinct()

                if (held.isNotEmpty()) {
                    plannedDownloadRepository.addAll(held, PlannedDownloadOrigin.Held)
                }
            }
            // <-- AM (PLANNED_DOWNLOADS)

            // Delete downloaded
            // AY -->
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null &&
                current.fetchType == FetchType.Episodes
            ) {
                // <-- AY
                downloadManager.deleteAnime(current, currentSource)
            }

            // Update custom cover (recheck if custom cover exists)
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
            }

            // Update custom background (recheck if custom background exists)
            if (MigrationFlag.CUSTOM_BACKGROUND in flags && current.hasCustomBackground()) {
                backgroundCache.setCustomBackgroundToCache(
                    target,
                    backgroundCache.getCustomBackgroundFile(current.id).inputStream(),
                )
            }

            // AM (MIGRATION_CARRY_OVER): the typed-in details and tag work,
            // which no flag covered and which were being lost on every
            // migration. Done for a copy as well - a copy is still meant to be
            // the same entry elsewhere.
            transferMigratedEntryState.await(current, target)

            val currentAnimeUpdate = AnimeUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetAnimeUpdate = AnimeUpdate(
                id = target.id,
                favorite = true,
                episodeFlags = current.episodeFlags,
                viewerFlags = current.viewerFlags,
                // AM: seasonFlags belongs with the other two - a seasons entry
                // kept its sort and filter settings everywhere except here.
                seasonFlags = current.seasonFlags,
                dateAdded = if (replace) current.dateAdded else Clock.System.now().toEpochMilliseconds(),
                notes = if (MigrationFlag.NOTES in flags) current.notes else null,
            )

            updateAnime.awaitAll(listOfNotNull(currentAnimeUpdate, targetAnimeUpdate))

            // AM (MERGED_SOURCES) -->
            // Only on replace: a copy leaves the original in place, so the
            // merges it belongs to are still about the original. Last, so the
            // replacement is already favorited and carries its state by the
            // time a merge starts pointing at it.
            if (replace) {
                replaceMergeChild.await(current, target)
            }
            // <-- AM (MERGED_SOURCES)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
        }
    }
}
