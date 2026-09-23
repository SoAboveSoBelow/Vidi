// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.domain.order.interactor

import aniyomi.domain.episode.repository.EpisodeNameRepository
import aniyomi.domain.merge.interactor.GetMergedEpisodeList
import aniyomi.domain.merge.model.MERGED_SOURCE_ID
import aniyomi.domain.merge.model.MERGE_DEFAULT_SEASON_NUMBER
import aniyomi.domain.merge.model.MergeChildOrdering
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.merge.repository.MergeSettingsRepository
import aniyomi.domain.order.model.EpisodeOrderOverride
import aniyomi.domain.order.model.ResolvedEpisodeOrder
import aniyomi.domain.order.repository.EpisodeOrderRepository
import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.repository.EntrySeasonRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.repository.EpisodeRepository

/**
 * The single place an entry's episode order is decided, for merged and
 * ordinary entries alike. A "playlist" is nothing more than this order; the
 * player and the episode list both consume it as-is rather than re-deriving
 * anything of their own.
 *
 * Order resolves in two layers:
 *
 * 1. The host's DEFAULT order - the union in season/merge order for a merged
 *    entry (see [GetMergedEpisodeList]), or the entry's own episodes for an
 *    ordinary one.
 * 2. The user's sparse overrides from `episode_order`, applied on top.
 *
 * With no overrides the default list is returned untouched and never sorted,
 * so an entry nobody has reordered behaves exactly as it always did.
 *
 * Every episode also resolves into a SEASON: its override's season if it has
 * one, else its owning child's merge season, else the default season. That is
 * what the episode list's season switcher groups by; it is a grouping over one
 * host's order, not separate entries, so the playlist still runs straight
 * across season boundaries.
 *
 * Override keys sit in the same space as the default keys rather than being
 * list indices, which is what lets them stay sparse: [defaultSortKey] is built
 * from values that survive new episodes arriving (an episode's `sourceOrder`,
 * and for a merged host the owning child's merge priority), so an override
 * written today still means the same thing after the next library update.
 *
 * Display concerns stay out of here. The stored order is the ascending watch
 * order; the descending toggle and shuffle are layers callers apply on top.
 */
@Inject
class GetEpisodeOrder(
    private val episodeRepository: EpisodeRepository,
    private val mergeChildRepository: MergeChildRepository,
    private val getMergedEpisodeList: GetMergedEpisodeList,
    private val episodeOrderRepository: EpisodeOrderRepository,
    // AM (NAMED_SEASONS) -->
    private val entrySeasonRepository: EntrySeasonRepository,
    // <-- AM (NAMED_SEASONS)
    // AM (MERGE_SETTINGS) -->
    private val mergeSettingsRepository: MergeSettingsRepository,
    // <-- AM (MERGE_SETTINGS)
    // AM (EPISODE_NAMES) -->
    private val episodeNameRepository: EpisodeNameRepository,
    // <-- AM (EPISODE_NAMES)
) {

    /**
     * Whether [host]'s order is already decided here and must not be
     * re-sorted by a caller.
     *
     * True for a merged entry, whose union order (season, then merge order)
     * would be destroyed by re-sorting on episode number, and for any entry
     * the user has given a custom order. False otherwise, which is the normal
     * case: an ordinary untouched entry keeps whatever display sort it already
     * had, and nothing about ordering changes for it.
     */
    suspend fun isPreordered(host: Anime): Boolean {
        return host.isMerged() || episodeOrderRepository.getByHostAnimeId(host.id).isNotEmpty()
    }

    suspend fun await(host: Anime, applyScanlatorFilter: Boolean = false): List<Episode> {
        return awaitResolved(host, applyScanlatorFilter).episodes
    }

    suspend fun awaitResolved(host: Anime, applyScanlatorFilter: Boolean = false): ResolvedEpisodeOrder {
        if (!host.isMerged()) {
            val episodes = episodeRepository.getEpisodeByAnimeId(host.id, applyScanlatorFilter)
            return resolve(
                host = host,
                default = episodes,
                overrides = episodeOrderRepository.getByHostAnimeId(host.id),
                childOrdering = emptyList(),
                seasonRows = entrySeasonRepository.getByHostAnimeId(host.id),
                // AM (EPISODE_NAMES) -->
                episodesByChild = mapOf(host.id to episodes),
                childTitles = emptyMap(),
                customNames = episodeNameRepository.getByAnimeIds(listOf(host.id)),
                // <-- AM (EPISODE_NAMES)
            )
        }

        val childOrdering = mergeChildRepository.getChildOrderingByMergeParentId(host.id)
        // AM (EPISODE_NAMES) -->
        // Grouped BEFORE dedupe: the single-episode rule is about what a source
        // actually offers, not what survived dedupe.
        val episodesByChild = episodeRepository
            .getEpisodesByMergeParentId(host.id, applyScanlatorFilter)
            .groupBy { it.animeId }
        // <-- AM (EPISODE_NAMES)
        return resolve(
            host = host,
            default = getMergedEpisodeList.build(
                childOrdering = childOrdering,
                episodesByChild = episodesByChild,
                dedupeMode = mergeSettingsRepository.get(host.id).dedupeMode,
            ),
            overrides = episodeOrderRepository.getByHostAnimeId(host.id),
            childOrdering = childOrdering,
            seasonRows = entrySeasonRepository.getByHostAnimeId(host.id),
            // AM (EPISODE_NAMES) -->
            episodesByChild = episodesByChild,
            childTitles = mergeChildRepository.getChildrenByMergeParentId(host.id)
                .associate { it.id to it.title },
            customNames = episodeNameRepository.getByAnimeIds(childOrdering.map { it.animeId }),
            // <-- AM (EPISODE_NAMES)
        )
    }

    /**
     * Reactive [awaitResolved]. Re-resolves whenever anything that feeds the
     * order changes: the host's own episodes, or for a merged host any
     * child's episodes; the merge's membership or season assignments; and the
     * user's overrides. Membership is followed with flatMapLatest so a child
     * joining or leaving the merge starts watching that child's episodes
     * without the screen having to be reopened.
     *
     * Suspending because EpisodeRepository.getEpisodeByAnimeIdAsFlow is.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun subscribe(host: Anime, applyScanlatorFilter: Boolean = false): Flow<ResolvedEpisodeOrder> {
        val overridesFlow = episodeOrderRepository.getByHostAnimeIdAsFlow(host.id)
        // AM (NAMED_SEASONS) -->
        val seasonRowsFlow = entrySeasonRepository.getByHostAnimeIdAsFlow(host.id)
        // <-- AM (NAMED_SEASONS)

        if (!host.isMerged()) {
            return combine(
                episodeRepository.getEpisodeByAnimeIdAsFlow(host.id, applyScanlatorFilter),
                overridesFlow,
                seasonRowsFlow,
                // AM (EPISODE_NAMES) -->
                episodeNameRepository.getByAnimeIdsAsFlow(listOf(host.id)),
            ) { episodes, overrides, seasonRows, customNames ->
                resolve(
                    host = host,
                    default = episodes,
                    overrides = overrides,
                    childOrdering = emptyList(),
                    seasonRows = seasonRows,
                    episodesByChild = mapOf(host.id to episodes),
                    childTitles = emptyMap(),
                    customNames = customNames,
                )
                // <-- AM (EPISODE_NAMES)
            }
        }

        return mergeChildRepository.getChildOrderingByMergeParentIdAsFlow(host.id)
            .flatMapLatest { childOrdering ->
                if (childOrdering.isEmpty()) {
                    return@flatMapLatest flowOf(ResolvedEpisodeOrder.EMPTY.copy(isPreordered = true))
                }
                // AM (MERGED_SOURCES) -->
                // Keep what the flows deliver: the union is built from these
                // below rather than read back out of the database.
                val childEpisodes = combine(
                    childOrdering.map {
                        episodeRepository.getEpisodeByAnimeIdAsFlow(it.animeId, applyScanlatorFilter)
                    },
                ) { lists -> lists.flatMap { it }.groupBy { episode -> episode.animeId } }
                // <-- AM (MERGED_SOURCES)
                val settingsFlow = mergeSettingsRepository.getAsFlow(host.id)
                // AM (EPISODE_NAMES) -->
                // Titles are read once per membership change rather than per
                // emission; a source renaming itself on refresh shows up when
                // the screen is next opened.
                val childTitles = mergeChildRepository.getChildrenByMergeParentId(host.id)
                    .associate { it.id to it.title }
                val namesFlow = episodeNameRepository.getByAnimeIdsAsFlow(childOrdering.map { it.animeId })
                // <-- AM (EPISODE_NAMES)
                combine(
                    childEpisodes,
                    overridesFlow,
                    seasonRowsFlow,
                    settingsFlow,
                    namesFlow,
                ) { episodesByChild, overrides, seasonRows, settings, customNames ->
                    resolve(
                        host = host,
                        // AM (MERGED_SOURCES) -->
                        // Built from the episodes the flows just delivered -
                        // re-querying every source here made each emission
                        // fetch them twice.
                        default = getMergedEpisodeList.build(
                            childOrdering = childOrdering,
                            episodesByChild = episodesByChild,
                            dedupeMode = settings.dedupeMode,
                        ),
                        // <-- AM (MERGED_SOURCES)
                        overrides = overrides,
                        childOrdering = childOrdering,
                        seasonRows = seasonRows,
                        // AM (EPISODE_NAMES) -->
                        episodesByChild = episodesByChild,
                        childTitles = childTitles,
                        customNames = customNames,
                        // <-- AM (EPISODE_NAMES)
                    )
                }
            }
    }

    private suspend fun defaultOrder(host: Anime, applyScanlatorFilter: Boolean): List<Episode> {
        return if (host.isMerged()) {
            getMergedEpisodeList.await(host.id, applyScanlatorFilter)
        } else {
            episodeRepository.getEpisodeByAnimeId(host.id, applyScanlatorFilter)
        }
    }

    private fun resolve(
        host: Anime,
        default: List<Episode>,
        overrides: List<EpisodeOrderOverride>,
        childOrdering: List<MergeChildOrdering>,
        // AM (NAMED_SEASONS) -->
        seasonRows: List<EntrySeason>,
        // <-- AM (NAMED_SEASONS)
        // AM (EPISODE_NAMES) -->
        episodesByChild: Map<Long, List<Episode>>,
        childTitles: Map<Long, String>,
        customNames: Map<Long, String>,
        // <-- AM (EPISODE_NAMES)
    ): ResolvedEpisodeOrder {
        val overrideById = overrides.associateBy { it.episodeId }
        val orderingByChildId = childOrdering.associateBy { it.animeId }

        val defaultSeasonById = default.associate {
            it.id to (orderingByChildId[it.animeId]?.seasonNumber ?: MERGE_DEFAULT_SEASON_NUMBER)
        }
        val seasonById = default.associate {
            it.id to (overrideById[it.id]?.seasonNumber ?: defaultSeasonById.getValue(it.id))
        }
        val defaultSortKeyById = default.associate { it.id to defaultSortKey(it) }
        val priorityById = default.associate { it.id to (orderingByChildId[it.animeId]?.priority ?: 0L) }
        val sortKeyById = default.associate {
            it.id to (overrideById[it.id]?.sortKey ?: defaultSortKeyById.getValue(it.id))
        }

        // AM (NAMED_SEASONS) -->
        val seasons = displaySeasons(seasonRows, seasonById.values)
        val rankByNumber = seasons.withIndex().associate { (i, season) -> season.number to i }

        // An ordinary entry with no overrides keeps its untouched list, which
        // its display sort then orders. A merged entry is always sorted here:
        // its seasons follow their display order, which only this knows. With
        // no season reordering that is the order GetMergedEpisodeList already
        // produced, so the sort changes nothing until the user reorders.
        val ordered = if (overrides.isEmpty() && !host.isMerged()) {
            default
        } else {
            // Ties are only reachable through duplicate episode numbers; the
            // id breaks them the same way GetMergedEpisodeList does, so the
            // order is stable between reads.
            default.sortedWith(
                compareBy<Episode>(
                    { seasonById[it.id]?.let(rankByNumber::get) },
                    { sortKeyById[it.id] },
                    { priorityById[it.id] },
                    { it.id },
                ),
            )
        }
        // <-- AM (NAMED_SEASONS)

        // AM (EPISODE_NAMES) -->
        // A source offering exactly one episode names it after itself: its
        // episode name ("Movie", "1", the show's name again) says nothing the
        // source's title doesn't say better. Scoped to merged entries - a
        // standalone one-episode entry would just repeat its own title. A
        // custom name always wins.
        val singleEpisodeChildTitles = if (host.isMerged()) {
            episodesByChild.filterValues { it.size == 1 }
                .mapNotNull { (childId, episodes) -> childTitles[childId]?.let { episodes.single().id to it } }
                .toMap()
        } else {
            emptyMap()
        }
        val displayNames = ordered.associate {
            it.id to (customNames[it.id] ?: singleEpisodeChildTitles[it.id] ?: it.name)
        }
        // <-- AM (EPISODE_NAMES)

        return ResolvedEpisodeOrder(
            episodes = ordered,
            seasonByEpisodeId = seasonById,
            sortKeyByEpisodeId = sortKeyById,
            defaultSortKeyByEpisodeId = defaultSortKeyById,
            defaultSeasonByEpisodeId = defaultSeasonById,
            seasons = seasons,
            priorityByEpisodeId = priorityById,
            displayNameByEpisodeId = displayNames,
            isPreordered = host.isMerged() || overrides.isNotEmpty(),
        )
    }

    // AM (NAMED_SEASONS) -->
    /**
     * Every season of the entry in display order: stored rows by their sort
     * order, then any season that's referenced but has no row yet, by number.
     * The default season is always included, so there is always somewhere for
     * episodes to fall back to - an entry with no seasons at all shows exactly
     * one.
     */
    private fun displaySeasons(rows: List<EntrySeason>, referenced: Collection<Long>): List<EntrySeason> {
        val stored = rows.sortedWith(compareBy({ it.sortOrder }, { it.number }))
        val storedNumbers = stored.mapTo(HashSet()) { it.number }
        val implicit = (referenced + MERGE_DEFAULT_SEASON_NUMBER)
            .filter { it !in storedNumbers }
            .distinct()
            .sorted()
            .map { EntrySeason(number = it, name = null, sortOrder = null) }
        return stored + implicit
    }
    // <-- AM (NAMED_SEASONS)

    /**
     * Default position of [episode] within its season, in the key space
     * overrides are written against: simply its episode number.
     *
     * The number is what the default order is sorted by, and the two must
     * agree exactly - the first override flips an entry from its untouched
     * default list to being sorted by these keys, and any disagreement would
     * reshuffle every episode the user never touched. sourceOrder would not
     * do: sources conventionally give 0 to the NEWEST episode.
     *
     * For a merged entry this interleaves sources by number within a season
     * (A1, B1, A2, B2...), with merge priority breaking ties - duplicates sit
     * together, which dedupe then collapses. The number is also what keeps
     * overrides meaningful over time: a newly released episode gets a higher
     * number and lands after existing ones instead of shifting them.
     */
    private fun defaultSortKey(episode: Episode): Double = episode.episodeNumber

    private fun Anime.isMerged(): Boolean = source == MERGED_SOURCE_ID
}
// <-- AM (CUSTOM_EPISODE_ORDER)
