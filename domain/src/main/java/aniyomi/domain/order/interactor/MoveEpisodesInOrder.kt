// AM (CUSTOM_EPISODE_ORDER) -->
package aniyomi.domain.order.interactor

import aniyomi.domain.order.model.EpisodeOrderOverride
import aniyomi.domain.order.model.ResolvedEpisodeOrder
import aniyomi.domain.order.repository.EpisodeOrderRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.Anime

/**
 * Moves one or more episodes to a point in an entry's order - the write side
 * of GetEpisodeOrder, and the only thing that creates order overrides.
 *
 * Episodes move as a group: they keep their current relative order and land
 * contiguously at the [Placement], in [targetSeason]. That covers both a drag
 * (drop point in the season being viewed) and a season change (end of the
 * target season).
 *
 * Only the moved episodes get rows. Each is given a sort key strictly between
 * its new neighbours' EFFECTIVE keys, so every other episode keeps whatever it
 * already had - a default key or an earlier override - and the order stays
 * sparse. The one exception is [renormalize], when repeated drops into the
 * same gap have exhausted the key precision there.
 */
@Inject
class MoveEpisodesInOrder(
    private val getEpisodeOrder: GetEpisodeOrder,
    private val episodeOrderRepository: EpisodeOrderRepository,
) {

    sealed interface Placement {
        data object AtStart : Placement
        data object AtEnd : Placement

        /** Immediately after [episodeId]. Falls back to [AtEnd] if it isn't in the target season. */
        data class After(val episodeId: Long) : Placement
    }

    suspend fun await(host: Anime, movedIds: List<Long>, targetSeason: Long, placement: Placement) {
        if (movedIds.isEmpty()) return
        // Unfiltered on purpose: episodes hidden by the scanlator filter still
        // hold positions. Computing against the visible subset would let a
        // renormalize reposition the hidden ones relative to everything else.
        var order = getEpisodeOrder.awaitResolved(host, applyScanlatorFilter = false)

        // The group moves in its current relative order, whatever order the
        // caller collected it in.
        val canonicalIndex = order.canonicalEpisodes.withIndex().associate { (i, e) -> e.id to i }
        val moved = movedIds.distinct()
            .filter { it in canonicalIndex }
            .sortedBy { canonicalIndex.getValue(it) }
        if (moved.isEmpty()) return

        var neighbours = neighbourKeys(order, moved, targetSeason, placement)
        if (!hasRoom(neighbours, moved.size)) {
            renormalize(host, order, moved, targetSeason)
            order = getEpisodeOrder.awaitResolved(host, applyScanlatorFilter = false)
            neighbours = neighbourKeys(order, moved, targetSeason, placement)
        }

        val keys = spreadKeys(neighbours.first, neighbours.second, moved.size)
        episodeOrderRepository.upsertAll(
            moved.mapIndexed { i, episodeId ->
                EpisodeOrderOverride(
                    hostAnimeId = host.id,
                    episodeId = episodeId,
                    // Stored only when it differs from the default, so an
                    // episode reordered within its own season keeps following
                    // its source if that source is later moved to another
                    // season in Edit seasons.
                    seasonNumber = targetSeason.takeIf { it != order.defaultSeasonByEpisodeId[episodeId] },
                    sortKey = keys[i],
                )
            },
        )
    }

    /**
     * Effective keys of the episodes the group will sit between, within
     * [targetSeason] and ignoring the group itself. Null on a side means the
     * group lands at that end of the season.
     */
    private fun neighbourKeys(
        order: ResolvedEpisodeOrder,
        moved: List<Long>,
        targetSeason: Long,
        placement: Placement,
    ): Pair<Double?, Double?> {
        val movedSet = moved.toHashSet()
        val season = order.canonicalEpisodes
            .filter { order.seasonByEpisodeId[it.id] == targetSeason && it.id !in movedSet }
        val keys = season.map { order.sortKeyByEpisodeId.getValue(it.id) }

        val insertAt = when (placement) {
            Placement.AtStart -> 0
            Placement.AtEnd -> keys.size
            is Placement.After -> {
                val anchor = season.indexOfFirst { it.id == placement.episodeId }
                if (anchor == -1) keys.size else anchor + 1
            }
        }
        return keys.getOrNull(insertAt - 1) to keys.getOrNull(insertAt)
    }

    /** Whether [count] keys fit between the neighbours without dropping below [MIN_GAP]. */
    private fun hasRoom(neighbours: Pair<Double?, Double?>, count: Int): Boolean {
        val (lo, hi) = neighbours
        return lo == null || hi == null || (hi - lo) / (count + 1) >= MIN_GAP
    }

    /** [count] increasing keys strictly between [lo] and [hi] (either may be open). */
    private fun spreadKeys(lo: Double?, hi: Double?, count: Int): List<Double> {
        if (lo != null && hi != null) {
            val step = (hi - lo) / (count + 1)
            return List(count) { lo + step * (it + 1) }
        }
        if (lo != null) return List(count) { lo + (it + 1) }
        if (hi != null) return List(count) { hi - (count - it) }
        return List(count) { (it + 1).toDouble() }
    }

    /**
     * Rewrites every episode of [targetSeason] outside the group with keys
     * spaced out in its current order, reopening room between them. This trades
     * sparsity in that one season for precision, and only happens after many
     * drops into the same gap.
     *
     * The new keys are the season's own DEFAULT keys, handed out in current
     * order: the i-th episode gets the i-th smallest default key present. That
     * keeps the season in the same key space as defaults, which matters once an
     * override is reset - the reset episode falls back to its default key (its
     * episode number) and lands near its natural rank rather than wherever an
     * unrelated 1, 2, 3... spacing would put it.
     *
     * Equal default keys (duplicate episode numbers) are nudged apart by
     * [DUPLICATE_KEY_SPACING] so the keys stay strictly increasing.
     */
    private suspend fun renormalize(
        host: Anime,
        order: ResolvedEpisodeOrder,
        moved: List<Long>,
        targetSeason: Long,
    ) {
        val movedSet = moved.toHashSet()
        val season = order.canonicalEpisodes
            .filter { order.seasonByEpisodeId[it.id] == targetSeason && it.id !in movedSet }
        val defaults = season.map { order.defaultSortKeyByEpisodeId.getValue(it.id) }.sorted()

        var previous: Double? = null
        val keys = defaults.map { key ->
            val spaced = previous?.let { maxOf(key, it + DUPLICATE_KEY_SPACING) } ?: key
            previous = spaced
            spaced
        }
        episodeOrderRepository.upsertAll(
            season.mapIndexed { i, episode ->
                EpisodeOrderOverride(
                    hostAnimeId = host.id,
                    episodeId = episode.id,
                    seasonNumber = targetSeason.takeIf { it != order.defaultSeasonByEpisodeId[episode.id] },
                    sortKey = keys[i],
                )
            },
        )
    }

    companion object {
        /**
         * Smallest gap a drop may subdivide. Keys are episode numbers, where a
         * Double resolves far below this, so it leaves a wide safety margin
         * while allowing ~20 drops into one gap before a renormalize.
         */
        private const val MIN_GAP = 1e-6

        private const val DUPLICATE_KEY_SPACING = 1e-3
    }
}
// <-- AM (CUSTOM_EPISODE_ORDER)
