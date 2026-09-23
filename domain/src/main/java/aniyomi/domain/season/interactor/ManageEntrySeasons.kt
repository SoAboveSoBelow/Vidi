// AM (NAMED_SEASONS) -->
package aniyomi.domain.season.interactor

import aniyomi.domain.merge.model.MERGED_SOURCE_ID
import aniyomi.domain.merge.model.MERGE_DEFAULT_SEASON_NUMBER
import aniyomi.domain.merge.repository.MergeChildRepository
import aniyomi.domain.order.interactor.GetEpisodeOrder
import aniyomi.domain.order.interactor.MoveEpisodesInOrder
import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.repository.EntrySeasonRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.Anime

/**
 * Season management for one library entry - the category-style add, rename,
 * reorder and delete.
 *
 * Every edit starts from the entry's resolved season list (GetEpisodeOrder),
 * so seasons that are referenced but were never stored are included, and
 * writes the whole list back with contiguous positions. The first edit to an
 * entry therefore materialises all its seasons at once, and every edit after
 * that stays a single atomic write.
 */
@Inject
class ManageEntrySeasons(
    private val getEpisodeOrder: GetEpisodeOrder,
    private val moveEpisodesInOrder: MoveEpisodesInOrder,
    private val entrySeasonRepository: EntrySeasonRepository,
    private val mergeChildRepository: MergeChildRepository,
) {

    suspend fun seasons(host: Anime): List<EntrySeason> {
        return getEpisodeOrder.awaitResolved(host, applyScanlatorFilter = false).seasons
    }

    /** Adds a season at the end. A blank [name] leaves it unnamed ("Season N" by position). */
    suspend fun create(host: Anime, name: String?): Long {
        val current = seasons(host)
        val number = (current.maxOfOrNull { it.number } ?: MERGE_DEFAULT_SEASON_NUMBER) + 1
        write(host, current + EntrySeason(number = number, name = name.clean(), sortOrder = null))
        return number
    }

    /** A blank [name] clears it back to the positional label. */
    suspend fun rename(host: Anime, seasonNumber: Long, name: String?) {
        write(host, seasons(host).map { if (it.number == seasonNumber) it.copy(name = name.clean()) else it })
    }

    /** [orderedNumbers] is the full new display order; seasons missing from it keep their relative order at the end. */
    suspend fun reorder(host: Anime, orderedNumbers: List<Long>) {
        val current = seasons(host)
        val rank = orderedNumbers.withIndex().associate { (i, n) -> n to i }
        write(host, current.sortedBy { rank[it.number] ?: Int.MAX_VALUE })
    }

    /**
     * Deletes a season. The default season can't be deleted - it is where
     * everything falls back to.
     *
     * Its sources move to the default season, and its episodes are placed at
     * the END of the default season as one block in their existing order, so
     * it stays obvious where they came from rather than having them scatter
     * back to each source's natural position.
     */
    suspend fun delete(host: Anime, seasonNumber: Long) {
        if (seasonNumber == MERGE_DEFAULT_SEASON_NUMBER) return
        val order = getEpisodeOrder.awaitResolved(host, applyScanlatorFilter = false)
        val episodeIds = order.canonicalEpisodes
            .filter { order.seasonByEpisodeId[it.id] == seasonNumber }
            .map { it.id }

        if (host.source == MERGED_SOURCE_ID) {
            mergeChildRepository.moveChildrenBetweenSeasons(host.id, seasonNumber, MERGE_DEFAULT_SEASON_NUMBER)
        }
        if (episodeIds.isNotEmpty()) {
            moveEpisodesInOrder.await(
                host,
                episodeIds,
                MERGE_DEFAULT_SEASON_NUMBER,
                MoveEpisodesInOrder.Placement.AtEnd,
            )
        }
        entrySeasonRepository.delete(host.id, seasonNumber)
        write(host, seasons(host).filter { it.number != seasonNumber })
    }

    private suspend fun write(host: Anime, ordered: List<EntrySeason>) {
        entrySeasonRepository.upsertAll(
            host.id,
            ordered.mapIndexed { i, season -> season.copy(sortOrder = i.toLong()) },
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
// <-- AM (NAMED_SEASONS)
