package eu.kanade.tachiyomi.data.backup.create.creators

import animiru.domain.anime.repository.TagVisibilityRepository
import animiru.domain.episode.repository.EpisodeTagRepository
import aniyomi.domain.episode.repository.EpisodeNameRepository
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import aniyomi.domain.order.repository.EpisodeOrderRepository
import aniyomi.domain.season.repository.EntrySeasonRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupEntrySeason
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupEpisodeTag
import eu.kanade.tachiyomi.data.backup.models.BackupEpisodeOrder
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupMergeChild
import eu.kanade.tachiyomi.data.backup.models.BackupMergeExclusion
import eu.kanade.tachiyomi.data.backup.models.BackupMergeSettings
import eu.kanade.tachiyomi.data.backup.models.BackupTagVisibility
import eu.kanade.tachiyomi.data.backup.models.backupEpisodeMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.data.download.DownloadCache
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.anime.interactor.GetCustomAnimeInfo
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.CustomAnimeInfo
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.history.interactor.GetHistory

@Inject
class AnimeBackupCreator(
    private val database: Database,
    private val getCategories: GetCategories,
    private val getHistory: GetHistory,
    // AM (CUSTOM_INFORMATION) -->
    private val getCustomAnimeInfo: GetCustomAnimeInfo,
    // <-- AM (CUSTOM_INFORMATION)
    // AM -->
    private val animeRepository: AnimeRepository,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val tagVisibilityRepository: TagVisibilityRepository,
    private val entrySeasonRepository: EntrySeasonRepository,
    private val episodeNameRepository: EpisodeNameRepository,
    // AM (EPISODE_TAGS)
    private val episodeTagRepository: EpisodeTagRepository,
    private val episodeOrderRepository: EpisodeOrderRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val mergeSettingsRepository: MergeSettingsRepository,
    private val mergeEpisodeExclusionRepository: MergeEpisodeExclusionRepository,
    private val downloadCache: DownloadCache,
    // <-- AM
) {

    suspend operator fun invoke(animes: List<Anime>, options: BackupOptions): List<BackupAnime> {
        // AM -->
        // Resolving an anime id to its (source, url) happens repeatedly - once per
        // merge child, per reordered episode, per exclusion - and the same ids
        // recur across the entries of one merge. Cached for the whole backup
        // rather than re-queried, since a library-sized walk would otherwise
        // issue thousands of single-row lookups.
        val animeKeyCache = mutableMapOf<Long, AnimeKey?>()
        // <-- AM
        return animes.map {
            backupAnime(it, options, animeKeyCache)
        }
    }

    private suspend fun backupAnime(
        anime: Anime,
        options: BackupOptions,
        animeKeyCache: MutableMap<Long, AnimeKey?>,
    ): BackupAnime {
        // Entry for this anime
        val animeObject = anime.toBackupAnime(
            // AM (CUSTOM_INFORMATION) -->
            if (options.customInfo) getCustomAnimeInfo.get(anime.id) else null,
            // <-- AM (CUSTOM_INFORMATION)
        )

        animeObject.excludedScanlators = database.excluded_scanlatorsQueries.getExcludedScanlatorsByAnimeId(anime.id)
            .awaitAsList()

        if (options.episodes) {
            // Backup all the episodes
            database.episodesQueries.getEpisodesByAnimeId(
                animeId = anime.id,
                applyScanlatorFilter = 0, // false
                mapper = backupEpisodeMapper,
            )
                .awaitAsList()
                .takeUnless(List<BackupEpisode>::isEmpty)
                ?.let { animeObject.episodes = it }
        }

        if (options.categories) {
            // Backup categories for this anime
            val categoriesForAnime = getCategories.await(anime.id)
            if (categoriesForAnime.isNotEmpty()) {
                animeObject.categories = categoriesForAnime.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = database.anime_syncQueries.getTracksByAnimeId(anime.id, backupTrackMapper).awaitAsList()
            if (tracks.isNotEmpty()) {
                animeObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByAnimeId = getHistory.await(anime.id)
            if (historyByAnimeId.isNotEmpty()) {
                val history = historyByAnimeId.map { history ->
                    val episode = database.episodesQueries.getEpisodeById(history.episodeId).awaitAsOne()
                    BackupHistory(episode.url, history.seenAt?.time ?: 0L)
                }
                if (history.isNotEmpty()) {
                    animeObject.history = history
                }
            }
        }

        // AM -->
        if (options.customInfo) {
            // Tag pins are a per-entry display choice, so they travel with the
            // other custom entry info rather than earning a toggle of their own.
            animeObject.tagVisibility = tagVisibilityRepository.getByAnimeId(anime.id)
                .map { BackupTagVisibility(tag = it.tag, visible = it.visible) }
        }

        if (options.libraryEntries) {
            backupStructure(anime, animeObject, animeKeyCache)
        }

        if (options.episodes) {
            backupEpisodeExtras(anime, animeObject)
        }
        // <-- AM

        return animeObject
    }

    // AM -->
    /**
     * Seasons, custom episode order and merge membership: the structure the user
     * arranged, none of which the backup carried before. All of it is keyed by
     * (source, url) pairs rather than row ids, because every id is reassigned
     * when the entry is inserted again on the restoring device.
     */
    private suspend fun backupStructure(
        anime: Anime,
        animeObject: BackupAnime,
        animeKeyCache: MutableMap<Long, AnimeKey?>,
    ) {
        animeObject.entrySeasons = entrySeasonRepository.getByHostAnimeId(anime.id)
            .map {
                BackupEntrySeason(
                    number = it.number,
                    name = it.name,
                    // A season with no stored row sorts after every season that
                    // has one, by number - the same fallback the reader applies.
                    sortOrder = it.sortOrder ?: it.number,
                )
            }

        val orderOverrides = episodeOrderRepository.getByHostAnimeId(anime.id)
        if (orderOverrides.isNotEmpty()) {
            animeObject.episodeOrder = orderOverrides.mapNotNull { override ->
                val episode = episodeKeyOf(override.episodeId, animeKeyCache) ?: return@mapNotNull null
                BackupEpisodeOrder(
                    animeSource = episode.anime.source,
                    animeUrl = episode.anime.url,
                    episodeUrl = episode.url,
                    seasonNumber = override.seasonNumber,
                    sortKey = override.sortKey,
                )
            }
        }

        val children = mergeChildRepository.getChildrenByMergeParentId(anime.id)
        if (children.isEmpty()) return

        val orderingByAnimeId = mergeChildRepository.getChildOrderingByMergeParentId(anime.id)
            .associateBy { it.animeId }
        animeObject.mergeChildren = children.mapNotNull { child ->
            val ordering = orderingByAnimeId[child.id] ?: return@mapNotNull null
            BackupMergeChild(
                animeSource = child.source,
                animeUrl = child.url,
                priority = ordering.priority,
                seasonNumber = ordering.seasonNumber,
            )
        }

        val settings = mergeSettingsRepository.get(anime.id)
        val infoKey = settings.infoAnimeId?.let { animeKeyOf(it, animeKeyCache) }
        animeObject.mergeSettings = BackupMergeSettings(
            dedupeMode = settings.dedupeMode.value,
            infoAnimeSource = infoKey?.source ?: 0,
            infoAnimeUrl = infoKey?.url.orEmpty(),
        )

        animeObject.mergeExclusions = mergeEpisodeExclusionRepository.getByHostAnimeId(anime.id)
            .mapNotNull { episodeId ->
                val episode = episodeKeyOf(episodeId, animeKeyCache) ?: return@mapNotNull null
                BackupMergeExclusion(
                    animeSource = episode.anime.source,
                    animeUrl = episode.anime.url,
                    episodeUrl = episode.url,
                )
            }
    }

    /**
     * Per-episode additions: the user's own episode names, and whether the episode
     * was downloaded here.
     *
     * Both ride along with the episode list rather than behind a toggle of their
     * own - an episode's name, and whether you had it downloaded, are facts about
     * that episode, so exporting episodes without them is the odd choice.
     *
     * Download state is read from [DownloadCache] rather than from any table,
     * because that is where it lives - presence on disk. Nothing restores a
     * download; the flag only lets the restoring device say "you had this one".
     */
    private suspend fun backupEpisodeExtras(anime: Anime, animeObject: BackupAnime) {
        if (animeObject.episodes.isEmpty()) return

        val idByUrl = getEpisodesByAnimeId.await(anime.id).associate { it.url to it.id }
        val customNames = episodeNameRepository.getByAnimeIds(listOf(anime.id))
        // AM (EPISODE_TAGS): the entry's own episodes only - a merged host's
        // children are backed up as the entries they are, carrying their own.
        val tagsByEpisode = episodeTagRepository.getByAnimeId(anime.id).groupBy { it.episodeId }

        animeObject.episodes.forEach { backupEpisode ->
            idByUrl[backupEpisode.url]?.let { episodeId ->
                backupEpisode.customName = customNames[episodeId]
                // AM (EPISODE_TAGS)
                backupEpisode.tags = tagsByEpisode[episodeId].orEmpty().map {
                    BackupEpisodeTag(tag = it.tag, visible = it.visible)
                }
            }

            backupEpisode.wasDownloaded = downloadCache.isEpisodeDownloaded(
                episodeName = backupEpisode.name,
                episodeScanlator = backupEpisode.scanlator,
                episodeUrl = backupEpisode.url,
                // The download folder is named after the source's title, never a
                // custom one.
                animeTitle = anime.ogTitle,
                sourceId = anime.source,
                skipCache = false,
            )
        }
    }

    /** (source, url) of [animeId], or null if that row is gone. Cached per backup. */
    private suspend fun animeKeyOf(animeId: Long, cache: MutableMap<Long, AnimeKey?>): AnimeKey? {
        return cache.getOrPut(animeId) {
            runCatching { animeRepository.getAnimeById(animeId) }
                .getOrNull()
                ?.let { AnimeKey(source = it.source, url = it.url) }
        }
    }

    /** The stable identity of [episodeId]: its own url plus its owning entry's key. */
    private suspend fun episodeKeyOf(episodeId: Long, cache: MutableMap<Long, AnimeKey?>): EpisodeKey? {
        val episode = database.episodesQueries.getEpisodeById(episodeId).awaitAsOneOrNull() ?: return null
        val anime = animeKeyOf(episode.anime_id, cache) ?: return null
        return EpisodeKey(url = episode.url, anime = anime)
    }

    private data class AnimeKey(val source: Long, val url: String)

    private data class EpisodeKey(val url: String, val anime: AnimeKey)
    // <-- AM
}

private fun Anime.toBackupAnime(
    // AM (CUSTOM_INFORMATION) -->
    customAnimeInfo: CustomAnimeInfo?,
    // <-- AM (CUSTOM_INFORMATION)
) =
    BackupAnime(
        url = this.url,
        // AM (CUSTOM_INFORMATION) -->
        title = this.ogTitle,
        artist = this.ogArtist,
        author = this.ogAuthor,
        description = this.ogDescription,
        genre = this.ogGenre.orEmpty(),
        status = this.ogStatus.toInt(),
        // <-- AM (CUSTOM_INFORMATION)
        thumbnailUrl = this.thumbnailUrl,
        // AM -->
        // Declared at ProtoNumber 500 and written straight into the database on
        // restore, but never populated here - so restoring over an existing entry
        // cleared whatever background it already had.
        backgroundUrl = this.backgroundUrl,
        // <-- AM
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.dateAdded,
        viewer_flags = this.viewerFlags.toInt(),
        episodeFlags = this.episodeFlags.toInt(),
        updateStrategy = this.updateStrategy,
        lastModifiedAt = this.lastModifiedAt,
        favoriteModifiedAt = this.favoriteModifiedAt,
        version = this.version,
        notes = this.notes,
        initialized = this.initialized,
        // AY -->
        fetchType = this.fetchType,
        parentId = this.parentId,
        id = this.id,
        seasonFlags = this.seasonFlags,
        seasonNumber = this.seasonNumber,
        seasonSourceOrder = this.seasonSourceOrder,
        // <-- AY
        memo = MemoColumnAdapter.encode(this.memo),
    )
        // AM (CUSTOM_INFORMATION) -->
        .also { backupAnime ->
            customAnimeInfo?.let {
                backupAnime.customTitle = it.title
                backupAnime.customArtist = it.artist
                backupAnime.customAuthor = it.author
                backupAnime.customDescription = it.description
                backupAnime.customGenre = it.genre
                backupAnime.customAddedGenre = it.addedGenre
                // AM (TAG_BLACKLIST)
                backupAnime.customBlacklistedGenre = it.blacklistedGenre
                backupAnime.customStatus = it.status?.toInt() ?: 0
            }
        }
// <-- AM (CUSTOM_INFORMATION)
