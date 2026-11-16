package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.repository.HistoryRepository

// AM (BROWSE_HIDE_VIEWED) -->
/**
 * Every entry of one source that has been viewed.
 *
 * Viewed means opened - an episode started in the player, or a card the
 * scroller feed played - not marked seen, so this reads history rather than
 * the episodes' seen state. See history.sq for the query itself.
 *
 * A one-shot read, deliberately: its caller, browse's hide-viewed trim, needs
 * one answer that holds still for the listing it is trimming, not a feed of
 * them.
 */
@Inject
class GetViewedAnimeIds(
    private val repository: HistoryRepository,
) {

    suspend fun await(sourceId: Long): Set<Long> {
        return repository.getViewedAnimeIdsBySource(sourceId)
    }
}
// <-- AM (BROWSE_HIDE_VIEWED)
