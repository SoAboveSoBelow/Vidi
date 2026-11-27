package eu.kanade.tachiyomi.data.backup.create.creators

import animiru.domain.anime.repository.TagVisibilityRepository
import animiru.domain.episode.repository.EpisodeTagRepository
import aniyomi.domain.episode.repository.EpisodeNameRepository
import aniyomi.domain.merge.model.MERGED_SOURCE_ID
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import aniyomi.domain.order.model.EpisodeOrderOverride
import aniyomi.domain.order.repository.EpisodeOrderRepository
import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.repository.EntrySeasonRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
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

@Inject
class AnimeBackupCreator(
    private val database: Database,
    private val getCategories: GetCategories,
    // AM (CUSTOM_INFORMATION) -->
    private val getCustomAnimeInfo: GetCustomAnimeInfo,
    // <-- AM (CUSTOM_INFORMATION)
    // AM -->
    private val animeRepository: AnimeRepository,
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
        val session = session(options)
        return animes.map { session.create(it) }
    }

    // AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME) -->
    /**
     * One backup's worth of entry conversion, entry at a time.
     *
     * The caller streams entries out as they are built rather than collecting
     * them into a list (see BackupAnimeChunk), and the id cache below has to
     * outlive each one, so it belongs to the session rather than to a call.
     */
    fun session(options: BackupOptions): Session = Session(options)

    inner class Session internal constructor(private val options: BackupOptions) {
        // AM -->
        // Resolving an anime id to its (source, url) happens repeatedly - once per
        // merge child, per reordered episode, per exclusion - and the same ids
        // recur across the entries of one merge. Cached for the whole backup
        // rather than re-queried, since a library-sized walk would otherwise
        // issue thousands of single-row lookups.
        private val animeKeyCache = mutableMapOf<Long, AnimeKey?>()
        // <-- AM

        // AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP) -->
        // The two structure tables, read once for the whole backup and grouped
        // by the entry that hosts them, in place of two queries per entry.
        //
        // Both tables only hold rows for entries the user has actually arranged,
        // so these maps are small whatever the library's size - but the queries
        // they replace scaled with the library, not with the structure. Built on
        // first use rather than in the constructor, so a backup with
        // libraryEntries off reads neither table at all.
        private var seasonsByHost: Map<Long, List<EntrySeason>>? = null
        private var orderByHost: Map<Long, List<EpisodeOrderOverride>>? = null

        private suspend fun seasons(hostAnimeId: Long): List<EntrySeason> {
            val cached = seasonsByHost
                ?: entrySeasonRepository.getAllByHostAnimeId().also { seasonsByHost = it }
            return cached[hostAnimeId].orEmpty()
        }

        private suspend fun order(hostAnimeId: Long): List<EpisodeOrderOverride> {
            val cached = orderByHost
                ?: episodeOrderRepository.getAllByHostAnimeId().also { orderByHost = it }
            return cached[hostAnimeId].orEmpty()
        }
        // <-- AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP)

        suspend fun create(anime: Anime): BackupAnime =
            backupAnime(anime, options, animeKeyCache, ::seasons, ::order)
    }
    // <-- AM (A_BACKUP_IS_WRITTEN_ONE_ENTRY_AT_A_TIME)

    private suspend fun backupAnime(
        anime: Anime,
        options: BackupOptions,
        animeKeyCache: MutableMap<Long, AnimeKey?>,
        // AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP)
        seasons: suspend (Long) -> List<EntrySeason>,
        order: suspend (Long) -> List<EpisodeOrderOverride>,
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
            // AM (A_BACKUP_READS_HISTORY_IN_ONE_QUERY): one join instead of the
            // entry's history rows plus a getEpisodeById per row.
            val history = database.historyQueries.getBackupHistoryByAnimeId(anime.id) { url, lastSeen, origin ->
                // AM (A_ORIGIN_SURVIVES_A_BACKUP)
                BackupHistory(url, lastSeen?.time ?: 0L, origin)
            }.awaitAsList()
            if (history.isNotEmpty()) {
                animeObject.history = history
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
            backupStructure(anime, animeObject, animeKeyCache, seasons, order)
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
        // AM (A_STRUCTURE_IS_READ_ONCE_FOR_A_BACKUP): looked up in the session's
        // maps rather than queried per entry.
        seasons: suspend (Long) -> List<EntrySeason>,
        order: suspend (Long) -> List<EpisodeOrderOverride>,
    ) {
        animeObject.entrySeasons = seasons(anime.id)
            .map {
                BackupEntrySeason(
                    number = it.number,
                    name = it.name,
                    // A season with no stored row sorts after every season that
                    // has one, by number - the same fallback the reader applies.
                    sortOrder = it.sortOrder ?: it.number,
                )
            }

        val orderOverrides = order(anime.id)
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

        // AM (A_STRUCTURE_IS_ONLY_READ_WHERE_IT_CAN_EXIST) -->
        // Merge membership can only exist for an entry on the merged source:
        // MergeAnimeEntries creates a parent there, with a merge:// url, and a
        // restored parent keeps that source. So for every ordinary entry the
        // four reads below were guaranteed to find nothing - and two of them
        // ran before the children.isEmpty() return could stop them, so a backup
        // spent four queries per entry on a structure only a handful of entries
        // can have.
        if (anime.source != MERGED_SOURCE_ID) return
        // <-- AM (A_STRUCTURE_IS_ONLY_READ_WHERE_IT_CAN_EXIST)

        val children = mergeChildRepository.getChildrenByMergeParentId(anime.id)
        if (children.isEmpty()) return

        // AM (A_BACKUP_CARRIES_WHAT_A_MERGE_IS_MADE_OF): what the database says
        // this merge holds, recorded before the mapNotNull below can drop any of
        // it. Restore compares against this rather than against what it reads.
        animeObject.mergeChildCount = children.size

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

        // AM (A_EXTRAS_READ_TWO_COLUMNS_NOT_EVERY_ONE): the url-to-id mapping is
        // all this needs, and GetEpisodesByAnimeId was reading every column of
        // every episode through a LEFT JOIN on excluded_scanlators and building a
        // domain Episode for each - per entry, on top of the full episode read
        // backupAnime has already done a few lines earlier. Two columns, no join,
        // no objects.
        val idByUrl = database.episodesQueries
            .getEpisodeIdsAndUrlsByAnimeId(anime.id) { id, url -> url to id }
            .awaitAsList()
            .toMap()
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
