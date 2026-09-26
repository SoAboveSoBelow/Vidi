package mihon.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.anime.model.hasCustomBackground
import eu.kanade.domain.anime.model.hasCustomCover
import eu.kanade.domain.anime.model.toSAnime
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.episode.model.toSEpisode
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.cache.BackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import logcat.LogPriority
import mihon.domain.source.interactor.models.RemoteAnimeEpisodeUpdate
import mihon.domain.source.interactor.models.RemoteAnimeSeasonUpdate
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.repository.EpisodeRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

@Inject
class UpdateAnimeFromRemote(
    private val sourceManager: SourceManager,
    private val episodeRepository: EpisodeRepository,
    private val animeRepository: AnimeRepository,
    private val syncEpisodesWithSource: SyncEpisodesWithSource,
    private val syncSeasonsWithSource: SyncSeasonsWithSource,
    private val coverCache: CoverCache,
    private val backgroundCache: BackgroundCache,
) {
    // AM (PARTIAL_EPISODE_SYNC) -->
    // Long-lived scope for the LocalSource background thumbnail fill below - this
    // interactor is a singleton, not screen-scoped, so a per-call scope would get
    // cancelled with the suspend function that created it before the work finishes.
    private val scope = CoroutineScope(Dispatchers.IO)
    // <-- AM (PARTIAL_EPISODE_SYNC)

    // AM (FETCH_SINGLE_FLIGHT) -->
    // Every fetch path in the app funnels through this interactor - the anime
    // screen, merge children, seasons, migration and the library update job -
    // and nothing deduped them, so two callers wanting the same episode list
    // made two identical round trips. Common in practice: pull-to-refresh while
    // the library job is already updating that entry, a merged entry whose
    // children overlap, or the screen's own init fetch racing a manual pull.
    //
    // Keyed on the flags as well as the id, because a details-only fetch and a
    // details+episodes fetch are NOT interchangeable - joining the narrower one
    // would silently return without the episodes the second caller asked for.
    //
    // manualFetch deliberately does NOT join: it drives downloadNewEpisodes()
    // and the error surfacing at the call site, and an automatic fetch in
    // flight has neither. Its own entry is still published, so concurrent
    // automatic callers can join IT.
    private val inFlight = ConcurrentHashMap<FetchKey, Deferred<Result<*>>>()

    private data class FetchKey(
        val animeId: Long,
        val kind: String,
        val fetchDetails: Boolean,
        val fetchContent: Boolean,
        val fetchWindow: Pair<Long, Long>,
    )

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> singleFlight(
        key: FetchKey,
        manualFetch: Boolean,
        block: suspend () -> Result<T>,
    ): Result<T> {
        if (!manualFetch) {
            (inFlight[key] as Deferred<Result<T>>?)?.let { return it.await() }
        }
        val deferred = scope.async { block() }
        inFlight[key] = deferred
        return try {
            deferred.await()
        } finally {
            // remove(key, value), not remove(key): a manual fetch overwrites the
            // entry a concurrent automatic one published, and whichever finishes
            // second must not evict an entry it doesn't own. Always in a finally -
            // a fetch that throws would otherwise leave every later caller
            // joining a dead Deferred.
            inFlight.remove(key, deferred)
        }
    }
    // <-- AM (FETCH_SINGLE_FLIGHT)

    suspend fun awaitEpisodesUpdate(
        anime: Anime,
        fetchDetails: Boolean = false,
        fetchEpisodes: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteAnimeEpisodeUpdate> {
        val source = sourceManager.getOrStub(anime.source)
        return awaitEpisodesUpdate(
            source = source,
            anime = anime,
            fetchDetails = fetchDetails,
            fetchEpisodes = fetchEpisodes,
            manualFetch = manualFetch,
            fetchWindow = fetchWindow,
        )
    }

    suspend fun awaitEpisodesUpdate(
        source: AnimeSource,
        anime: Anime,
        fetchDetails: Boolean = false,
        fetchEpisodes: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteAnimeEpisodeUpdate> = singleFlight(
        key = FetchKey(anime.id, "episodes", fetchDetails, fetchEpisodes, fetchWindow),
        manualFetch = manualFetch,
    ) {
        try {
            val episodes = episodeRepository.getEpisodeByAnimeId(anime.id)
                .sortedBy { it.sourceOrder }
            val update = withIOContext {
                source.getAnimeEpisodeUpdate(
                    anime = anime.toSAnime(),
                    episodes = episodes.map(Episode::toSEpisode),
                    fetchDetails = fetchDetails,
                    fetchEpisodes = fetchEpisodes,
                )
            }
            awaitUpdateFromSource(anime, update.anime, manualFetch)
            val newEpisodes = syncEpisodesWithSource.await(
                rawSourceEpisodes = update.episodes,
                anime = anime,
                source = source,
                manualFetch = manualFetch,
                fetchWindow = fetchWindow,
            )

            // AM (PARTIAL_EPISODE_SYNC) -->
            // LocalSource returns episodes with thumbnails left empty so a big folder
            // shows up immediately (see LocalSource.getOldEpisodeList). Fill them in
            // chunk by chunk in the background instead of blocking this refresh on
            // every ffmpeg extraction.
            if (fetchEpisodes && source is LocalSource) {
                scope.launchNonCancellable {
                    source.generateMissingThumbnails(update.anime, update.episodes) { chunk ->
                        val dbEpisodes = episodeRepository.getEpisodeByAnimeId(anime.id)
                        val thumbnailUpdates = chunk.mapNotNull { sEpisode ->
                            dbEpisodes.find { it.url == sEpisode.url }?.let { dbEpisode ->
                                EpisodeUpdate(id = dbEpisode.id, previewUrl = sEpisode.preview_url)
                            }
                        }
                        if (thumbnailUpdates.isNotEmpty()) {
                            episodeRepository.updateAll(thumbnailUpdates)
                        }
                    }
                }
            }
            // <-- AM (PARTIAL_EPISODE_SYNC)

            val updatedAnime = animeRepository.getAnimeById(anime.id)
            Result.success(RemoteAnimeEpisodeUpdate(anime = updatedAnime, newEpisodes = newEpisodes))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.failure(e)
        }
    }

    suspend fun awaitSeasonsUpdate(
        anime: Anime,
        fetchDetails: Boolean = false,
        fetchSeasons: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteAnimeSeasonUpdate> {
        val source = sourceManager.getOrStub(anime.source)
        return awaitSeasonsUpdate(
            source = source,
            anime = anime,
            fetchDetails = fetchDetails,
            fetchSeasons = fetchSeasons,
            manualFetch = manualFetch,
            fetchWindow = fetchWindow,
        )
    }

    suspend fun awaitSeasonsUpdate(
        source: AnimeSource,
        anime: Anime,
        fetchDetails: Boolean = false,
        fetchSeasons: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteAnimeSeasonUpdate> = singleFlight(
        key = FetchKey(anime.id, "seasons", fetchDetails, fetchSeasons, fetchWindow),
        manualFetch = manualFetch,
    ) {
        try {
            val seasons = animeRepository.getAnimeSeasonsById(anime.id)
                .sortedBy { it.anime.seasonSourceOrder }
            val update = withIOContext {
                source.getAnimeSeasonUpdate(
                    anime = anime.toSAnime(),
                    seasons = seasons.map { it.anime.toSAnime() },
                    fetchDetails = fetchDetails,
                    fetchSeasons = fetchSeasons,
                )
            }
            awaitUpdateFromSource(anime, update.anime, manualFetch)
            val newSeasons = syncSeasonsWithSource.await(
                rawSourceSeasons = update.seasons,
                anime = anime,
                source = source,
                manualFetch = manualFetch,
                fetchWindow = fetchWindow,
            )
            val updatedAnime = animeRepository.getAnimeById(anime.id)
            Result.success(RemoteAnimeSeasonUpdate(anime = updatedAnime, newSeasons = newSeasons))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.failure(e)
        }
    }

    private suspend fun awaitUpdateFromSource(
        localAnime: Anime,
        remoteAnime: SAnime,
        manualFetch: Boolean,
    ): Boolean {
        val remoteTitle = try {
            remoteAnime.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }

        // if the anime isn't a favorite, set its title from source and update in db
        val title =
            if (remoteTitle.isNotEmpty() && !localAnime.favorite) {
                remoteTitle
            } else {
                null
            }

        val coverLastModified =
            when {
                // Never refresh covers if the url is empty to avoid "losing" existing covers
                remoteAnime.thumbnail_url.isNullOrEmpty() -> null
                !manualFetch && localAnime.thumbnailUrl == remoteAnime.thumbnail_url -> null
                localAnime.isLocal() -> Clock.System.now().toEpochMilliseconds()
                localAnime.hasCustomCover(coverCache) -> {
                    coverCache.deleteFromCache(localAnime, false)
                    null
                }
                else -> {
                    coverCache.deleteFromCache(localAnime, false)
                    Clock.System.now().toEpochMilliseconds()
                }
            }

        val backgroundLastModified =
            when {
                // Never refresh backgrounds if the url is empty to avoid "losing" existing backgrounds
                remoteAnime.background_url.isNullOrEmpty() -> null
                !manualFetch && localAnime.backgroundUrl == remoteAnime.background_url -> null
                localAnime.isLocal() -> Clock.System.now().toEpochMilliseconds()
                localAnime.hasCustomBackground(backgroundCache) -> {
                    backgroundCache.deleteFromCache(localAnime, false)
                    null
                }
                else -> {
                    backgroundCache.deleteFromCache(localAnime, false)
                    Clock.System.now().toEpochMilliseconds()
                }
            }

        val thumbnailUrl = remoteAnime.thumbnail_url?.takeIf { it.isNotEmpty() }
        val backgroundUrl = remoteAnime.background_url?.takeIf { it.isNotEmpty() }

        return animeRepository.update(
            AnimeUpdate(
                id = localAnime.id,
                title = title,
                coverLastModified = coverLastModified,
                backgroundLastModified = backgroundLastModified,
                author = remoteAnime.author,
                artist = remoteAnime.artist,
                description = remoteAnime.description,
                genre = remoteAnime.getGenres(),
                thumbnailUrl = thumbnailUrl,
                backgroundUrl = backgroundUrl,
                status = remoteAnime.status.toLong(),
                updateStrategy = remoteAnime.update_strategy,
                initialized = true,
                memo = remoteAnime.memo,
            ),
        )
    }
}
