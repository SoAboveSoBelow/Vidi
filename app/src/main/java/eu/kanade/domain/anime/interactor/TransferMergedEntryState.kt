// AM (MERGE_EXISTING) -->
package eu.kanade.domain.anime.interactor

import animiru.domain.anime.repository.TagVisibilityRepository
import aniyomi.domain.merge.model.MERGE_DEFAULT_SEASON_NUMBER
import aniyomi.domain.merge.model.MergeChildOrdering
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeEpisodeExclusionRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import aniyomi.domain.order.interactor.GetEpisodeOrder
import aniyomi.domain.order.model.EpisodeOrderOverride
import aniyomi.domain.order.repository.EpisodeOrderRepository
import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.repository.EntrySeasonRepository
import dev.zacsweers.metro.Inject
import eu.kanade.domain.anime.model.hasCustomBackground
import eu.kanade.domain.anime.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.BackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.anime.interactor.GetCustomAnimeInfo
import tachiyomi.domain.anime.interactor.SetCustomAnimeInfo
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.CustomAnimeInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * Carries everything a merged entry keeps on its PARENT row onto the new merge
 * built from several of them.
 *
 * Merging two merged entries produces a brand new parent (see
 * [MergeAnimeEntries.mergeSelection]), and the children it inherits bring their
 * own state with them - episodes, bookmarks, fillermarks, custom episode names
 * are all keyed by episode or by child, so they arrive untouched. Everything
 * keyed by the merge PARENT does not: tag visibility, custom episode order,
 * episode exclusions, merge settings, internal seasons, categories and custom
 * details all hang off an id the new entry does not have. Without this they are
 * silently dropped, and the old parents are usually removed from the library
 * right afterwards, so there is nothing left to go back to.
 *
 * The merges being combined are "groups", in selection order. The first group
 * is the one everything defers to: its seasons keep their numbers, its settings
 * become the merge's, and its details win where two groups disagree. Later
 * groups are appended - their sources follow the first's in merge order
 * already, so their order, seasons and exclusions follow the same way.
 */
@Inject
class TransferMergedEntryState(
    private val mergeChildRepository: MergeChildRepository,
    private val mergeSettingsRepository: MergeSettingsRepository,
    private val mergeEpisodeExclusionRepository: MergeEpisodeExclusionRepository,
    private val episodeOrderRepository: EpisodeOrderRepository,
    private val episodeRepository: EpisodeRepository,
    private val entrySeasonRepository: EntrySeasonRepository,
    private val tagVisibilityRepository: TagVisibilityRepository,
    private val getCategories: GetCategories,
    private val setAnimeCategories: SetAnimeCategories,
    private val getCustomAnimeInfo: GetCustomAnimeInfo,
    private val setCustomAnimeInfo: SetCustomAnimeInfo,
    private val updateAnime: UpdateAnime,
    private val coverCache: CoverCache,
    private val backgroundCache: BackgroundCache,
) {

    /**
     * @param newParent the merge just created from [groups]' sources.
     * @param groups the merged entries that were selected, in selection order.
     * @param newChildren [newParent]'s children, in the priority order they were given.
     */
    suspend fun await(newParent: Anime, groups: List<Anime>, newChildren: List<Anime>) {
        if (groups.isEmpty()) return

        val newIndexById = newChildren.withIndex().associate { (index, child) -> child.id to index }
        val orderings = groups.associate { group ->
            group.id to mergeChildRepository.getChildOrderingByMergeParentId(group.id)
                .sortedBy { it.priority }
        }

        // A source reached through more than one selected merge is kept once,
        // at its earliest position - so the first group that has it is the one
        // whose order, seasons and details speak for it.
        val ownerByChildId = HashMap<Long, Long>()
        groups.forEach { group ->
            orderings.getValue(group.id).forEach { ordering ->
                if (ordering.animeId !in ownerByChildId) {
                    ownerByChildId[ordering.animeId] = group.id
                }
            }
        }

        val seasonMaps = transferSeasons(newParent, groups, orderings, ownerByChildId)
        transferSettings(newParent, groups.first(), newIndexById)
        transferEpisodeOrder(newParent, groups, orderings, newIndexById, ownerByChildId, seasonMaps)
        transferExclusions(newParent, groups)
        transferTagVisibility(newParent, groups)
        transferCategories(newParent, groups)
        transferCustomDetails(newParent, groups)
    }

    /**
     * Lays the groups' internal seasons end to end.
     *
     * The default season is the one every entry falls back to and every merge
     * has, so it is shared: both merges' first seasons become the new merge's
     * first season, exactly as merging two unmerged entries puts them together.
     * Every other season of a later group becomes a season of its own at the
     * end of the list, keeping its name - suffixed with a number if a season
     * already carried that name, so two "Specials" stay apart.
     *
     * Every season gets a stored row, including ones that only had an implicit
     * position before: appending depends on the whole list being explicitly
     * ordered, since an unnamed season with no row sorts after every season
     * that has one.
     *
     * @return per group, old season number -> the new merge's season number.
     */
    private suspend fun transferSeasons(
        newParent: Anime,
        groups: List<Anime>,
        orderings: Map<Long, List<MergeChildOrdering>>,
        ownerByChildId: Map<Long, Long>,
    ): Map<Long, Map<Long, Long>> {
        val seasonMaps = HashMap<Long, Map<Long, Long>>()
        val rows = mutableListOf<EntrySeason>()
        val takenNames = HashSet<String>()
        var nextNumber = MERGE_DEFAULT_SEASON_NUMBER + 1

        groups.forEachIndexed { groupIndex, group ->
            val ordering = orderings.getValue(group.id)
            val stored = entrySeasonRepository.getByHostAnimeId(group.id)
            val displayed = displaySeasons(stored, ordering.map { it.seasonNumber })
            val map = HashMap<Long, Long>()

            displayed.forEach { season ->
                if (season.number == MERGE_DEFAULT_SEASON_NUMBER) {
                    map[season.number] = MERGE_DEFAULT_SEASON_NUMBER
                    if (groupIndex == 0) {
                        rows += EntrySeason(MERGE_DEFAULT_SEASON_NUMBER, season.name, rows.size.toLong())
                        season.name?.let { takenNames += it.lowercase() }
                    }
                    return@forEach
                }
                val number = if (groupIndex == 0) season.number else nextNumber++
                nextNumber = maxOf(nextNumber, number + 1)
                map[season.number] = number
                val name = season.name?.let { uniqueName(it, takenNames) }
                name?.let { takenNames += it.lowercase() }
                rows += EntrySeason(number, name, rows.size.toLong())
            }
            seasonMaps[group.id] = map
        }

        // The default season always exists, even if no group had a row for it.
        if (rows.none { it.number == MERGE_DEFAULT_SEASON_NUMBER }) {
            rows.add(0, EntrySeason(MERGE_DEFAULT_SEASON_NUMBER, null, 0))
        }
        entrySeasonRepository.upsertAll(
            newParent.id,
            rows.mapIndexed { index, season -> season.copy(sortOrder = index.toLong()) },
        )

        // createMerge put every child in the default season; only the ones
        // their own merge had elsewhere need moving.
        groups.forEach { group ->
            val map = seasonMaps.getValue(group.id)
            orderings.getValue(group.id).forEach { ordering ->
                if (ownerByChildId[ordering.animeId] != group.id) return@forEach
                val season = map[ordering.seasonNumber] ?: MERGE_DEFAULT_SEASON_NUMBER
                if (season != MERGE_DEFAULT_SEASON_NUMBER) {
                    mergeChildRepository.setSeasonNumber(newParent.id, ordering.animeId, season)
                }
            }
        }
        return seasonMaps
    }

    /**
     * Seasons in the order they are shown, which is what appending comes after:
     * stored rows by their sort order, then any season only referenced by a
     * child, by number. Mirrors GetEpisodeOrder.displaySeasons.
     */
    private fun displaySeasons(stored: List<EntrySeason>, referenced: List<Long>): List<EntrySeason> {
        val ordered = stored.sortedWith(compareBy({ it.sortOrder }, { it.number }))
        val storedNumbers = ordered.mapTo(HashSet()) { it.number }
        val implicit = (referenced + MERGE_DEFAULT_SEASON_NUMBER)
            .filter { it !in storedNumbers }
            .distinct()
            .sorted()
            .map { EntrySeason(number = it, name = null, sortOrder = null) }
        return ordered + implicit
    }

    /** [name], or [name] with the lowest free number appended if it is already in use. */
    private fun uniqueName(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        var suffix = 2
        while ("$name $suffix".lowercase() in taken) suffix++
        return "$name $suffix"
    }

    /**
     * The first group's settings become the merge's - its dedupe mode decides
     * which of the combined sources' episodes are hidden, and its info source
     * supplies the details, as long as that source is in the new merge (it
     * always is, being one of the first group's own children).
     */
    private suspend fun transferSettings(newParent: Anime, first: Anime, newIndexById: Map<Long, Int>) {
        val settings = mergeSettingsRepository.get(first.id)
        mergeSettingsRepository.set(
            newParent.id,
            settings.copy(infoAnimeId = settings.infoAnimeId?.takeIf { it in newIndexById }),
        )
    }

    /**
     * Carries each group's custom episode order, one after the other: the first
     * merge's order, then the second's below it.
     *
     * Order overrides are keyed in the same space as the default keys - an
     * episode's number inside its source's BAND, the source's position in merge
     * order times [GetEpisodeOrder.BLOCK_SPAN] (see GetEpisodeOrder.defaultSortKey).
     * The sources keep their relative order inside the new merge, so a group's
     * keys only need shifting into the bands its sources now occupy: a strictly
     * increasing map of old band -> new band, with the position inside the band
     * scaled to the room available, which preserves the group's order exactly
     * including episodes dragged out of their own source's band.
     *
     * Because the map sends an untouched episode's default key to its new
     * default key, the order stays as sparse as it was: a group's rows carry
     * over one for one and nothing else is written.
     *
     * An episode whose source was reached through an earlier group too is left
     * to that group - it only holds one position in the combined list.
     */
    private suspend fun transferEpisodeOrder(
        newParent: Anime,
        groups: List<Anime>,
        orderings: Map<Long, List<MergeChildOrdering>>,
        newIndexById: Map<Long, Int>,
        ownerByChildId: Map<Long, Long>,
        seasonMaps: Map<Long, Map<Long, Long>>,
    ) {
        val carried = mutableListOf<EpisodeOrderOverride>()
        groups.forEach { group ->
            val overrides = episodeOrderRepository.getByHostAnimeId(group.id)
            if (overrides.isEmpty()) return@forEach

            val ordering = orderings.getValue(group.id)
            val bands = bandMap(ordering.map { it.animeId }, group.id, ownerByChildId, newIndexById)
            if (bands == null) return@forEach

            val ownedEpisodeIds = episodeRepository.getEpisodesByMergeParentId(group.id)
                .filter { ownerByChildId[it.animeId] == group.id }
                .mapTo(HashSet()) { it.id }
            val seasonMap = seasonMaps.getValue(group.id)

            overrides.forEach { override ->
                if (override.episodeId !in ownedEpisodeIds) return@forEach
                carried += EpisodeOrderOverride(
                    hostAnimeId = newParent.id,
                    episodeId = override.episodeId,
                    seasonNumber = override.seasonNumber?.let { seasonMap[it] ?: it },
                    sortKey = override.sortKey?.let { rebase(it, bands) },
                )
            }
        }
        if (carried.isNotEmpty()) {
            episodeOrderRepository.upsertAll(carried)
        }
    }

    /**
     * Where each of a group's old bands lands in the new merge, by old band
     * index. Strictly increasing, so remapping keys through it cannot reorder
     * anything.
     *
     * A source the group no longer speaks for (an earlier group had it too)
     * leaves a hole: it is given a position interpolated between its surviving
     * neighbours, so an episode the user had dragged into that source's band
     * still lands between the same two sources.
     *
     * Null when the group has no sources of its own left, which leaves its
     * order with nothing to attach to.
     */
    private fun bandMap(
        oldChildIds: List<Long>,
        groupId: Long,
        ownerByChildId: Map<Long, Long>,
        newIndexById: Map<Long, Int>,
    ): DoubleArray? {
        val bands = DoubleArray(oldChildIds.size)
        val owned = oldChildIds.indices.filter { index ->
            ownerByChildId[oldChildIds[index]] == groupId && oldChildIds[index] in newIndexById
        }
        if (owned.isEmpty()) return null
        owned.forEach { index -> bands[index] = newIndexById.getValue(oldChildIds[index]).toDouble() }

        var previous = -1
        owned.forEach { index ->
            fillHole(bands, from = previous, to = index)
            previous = index
        }
        fillHole(bands, from = previous, to = bands.size)
        return bands
    }

    /** Spreads the holes between two filled positions (either end may be open). */
    private fun fillHole(bands: DoubleArray, from: Int, to: Int) {
        val count = to - from - 1
        if (count <= 0) return
        val low = bands.getOrNull(from)
        val high = bands.getOrNull(to)
        val start = low ?: (high!! - HOLE_SPAN)
        val end = high ?: (low!! + HOLE_SPAN)
        val step = (end - start) / (count + 1)
        for (offset in 1..count) {
            bands[from + offset] = start + step * offset
        }
    }

    /**
     * [key] moved from its old band into the band it maps to, its position
     * inside the band scaled to the room before the next one so two old bands
     * landing close together still cannot overlap.
     */
    private fun rebase(key: Double, bands: DoubleArray): Double {
        val span = GetEpisodeOrder.BLOCK_SPAN
        val band = (key / span).toInt().coerceIn(0, bands.size - 1)
        val within = (key - band * span).coerceIn(0.0, span)
        val room = bands.getOrNull(band + 1)?.minus(bands[band]) ?: 1.0
        return bands[band] * span + within * room
    }

    /**
     * Every episode any group had taken out of its order stays out of the new
     * one. Nothing is lost by keeping a removal the user can undo on the entry,
     * while dropping it puts episodes they removed back in front of them.
     */
    private suspend fun transferExclusions(newParent: Anime, groups: List<Anime>) {
        groups
            .flatMap { mergeEpisodeExclusionRepository.getByHostAnimeId(it.id) }
            .distinct()
            .forEach { mergeEpisodeExclusionRepository.add(newParent.id, it) }
    }

    /**
     * Tag pins carry over, and a tag pinned visible by one group and hidden by
     * another is shown: the pin says the user wanted that tag in front of them
     * on an entry these sources now belong to.
     */
    private suspend fun transferTagVisibility(newParent: Anime, groups: List<Anime>) {
        val visibilityByTag = HashMap<String, Boolean>()
        groups.forEach { group ->
            tagVisibilityRepository.getByAnimeId(group.id).forEach { pin ->
                visibilityByTag[pin.tag] = (visibilityByTag[pin.tag] ?: false) || pin.visible
            }
        }
        if (visibilityByTag.isNotEmpty()) {
            tagVisibilityRepository.upsertAll(newParent.id, visibilityByTag)
        }
    }

    /**
     * The groups' categories join the ones createMerge already took from the
     * sources. Only ever added: a category one merge was filed under is a place
     * the user expects to still find these sources.
     */
    private suspend fun transferCategories(newParent: Anime, groups: List<Anime>) {
        val existing = getCategories.await(newParent.id).map { it.id }
        val categoryIds = (existing + groups.flatMap { getCategories.await(it.id).map { c -> c.id } })
            .distinct()
        if (categoryIds.isNotEmpty() && categoryIds.size != existing.size) {
            setAnimeCategories.await(newParent.id, categoryIds)
        }
    }

    /**
     * Details the user set by hand follow the first group, field by field: a
     * title the first merge had is the new merge's title, and a field it never
     * had takes the first later group that did rather than falling back to the
     * sources' own details. A field nobody customised is left alone, so
     * SyncMergedEntryInfo keeps supplying it.
     *
     * [CustomAnimeInfo.genre] is deliberately not carried. It replaces the
     * entry's tags outright, which on a merge freezes the ranked list its
     * sources produce - the first group's snapshot would hide every tag the
     * other groups' sources bring in. Added tags have no such problem and are
     * unioned.
     */
    private suspend fun transferCustomDetails(newParent: Anime, groups: List<Anime>) {
        val customs = groups.mapNotNull { group -> getCustomAnimeInfo.get(group.id) }
        val addedGenre = customs.flatMap { it.addedGenre.orEmpty() }.distinctBy(String::lowercase)
        val info = CustomAnimeInfo(
            id = newParent.id,
            title = customs.firstNotNullOfOrNull { it.title },
            author = customs.firstNotNullOfOrNull { it.author },
            artist = customs.firstNotNullOfOrNull { it.artist },
            description = customs.firstNotNullOfOrNull { it.description },
            addedGenre = addedGenre.takeIf { it.isNotEmpty() },
            status = customs.firstNotNullOfOrNull { it.status },
        )
        if (info != CustomAnimeInfo(id = newParent.id, title = null)) {
            setCustomAnimeInfo.set(info)
        }

        groups.firstOrNull { it.hasCustomCover(coverCache) }?.let { group ->
            coverCache.getCustomCoverFile(group.id).inputStream().use {
                coverCache.setCustomCoverToCache(newParent, it)
            }
            updateAnime.awaitUpdateCoverLastModified(newParent.id)
        }
        groups.firstOrNull { it.hasCustomBackground(backgroundCache) }?.let { group ->
            backgroundCache.getCustomBackgroundFile(group.id).inputStream().use {
                backgroundCache.setCustomBackgroundToCache(newParent, it)
            }
            updateAnime.awaitUpdateBackgroundLastModified(newParent.id)
        }
    }

    private companion object {
        /** How far an open-ended run of holes reaches past the band it hangs off. */
        const val HOLE_SPAN = 0.5
    }
}
// <-- AM (MERGE_EXISTING)
