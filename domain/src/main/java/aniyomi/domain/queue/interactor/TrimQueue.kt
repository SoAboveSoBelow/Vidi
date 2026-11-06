// AM (PLAYBACK_QUEUE) -->
package aniyomi.domain.queue.interactor

import aniyomi.domain.queue.model.QueueItem
import aniyomi.domain.queue.repository.QueueRepository
import dev.zacsweers.metro.Inject

/**
 * Drops runs the user has already gone past, keeping a trailing window of
 * them.
 *
 * Deliberately NOT a cap on the queue's length. A cap punishes the one thing
 * the queue is for - lining up a lot to watch - by throwing away things the
 * user queued and has not reached. Only what is BEHIND the playing position is
 * ever removed, so anything queued ahead survives however much of it there is.
 *
 * The window is what makes stepping back work: going forward and then back a
 * couple of sources has to still find them, so the last few runs before the
 * current position are kept and only what is further back than that goes.
 *
 * There is no timer. Progression is the only trigger, which means the only way
 * an entry disappears is the user moving past it, and a queue nobody is
 * playing is never touched.
 */
@Inject
class TrimQueue(
    private val queueRepository: QueueRepository,
) {

    /**
     * [keepBehind] runs before the one holding [currentEpisodeId] are kept;
     * earlier ones are removed. 0 disables trimming entirely, matching how the
     * rest of the app reads 0 in a limit preference.
     *
     * Works in RUNS, not single items: a run is one source as it appears in
     * the play order, and half of one is not a thing anyone asked to keep.
     *
     * AM (QUEUE_TRIM_BY_POSITION): a run rather than a whole block, and
     * removal by item rather than by block. Episode dragging can put one
     * source's items in several places, so a block can have a run behind the
     * playing position and another ahead of it; removing the block took the
     * run ahead with it, which is exactly the thing this is not allowed to do.
     * Walking positions keeps that promise: only what is behind is ever
     * considered, and a source with anything still ahead simply keeps it.
     */
    suspend fun await(currentEpisodeId: Long, keepBehind: Int) {
        if (keepBehind <= 0) return

        val items = queueRepository.getItems()
        if (items.isEmpty()) return

        val playingIndex = items.indexOfFirst { it.episodeId == currentEpisodeId }
        if (playingIndex == -1) return

        // getItems is in play order, so consecutive items sharing a block are
        // one run - the same grouping the playlist shows.
        val runs = mutableListOf<MutableList<QueueItem>>()
        items.take(playingIndex).forEach { item ->
            val current = runs.lastOrNull()
            if (current != null && current.first().blockId == item.blockId) {
                current += item
            } else {
                runs += mutableListOf(item)
            }
        }

        val expendable = runs.size - keepBehind
        if (expendable <= 0) return

        queueRepository.removeItems(runs.take(expendable).flatten().map { it.id })
    }
}
// <-- AM (PLAYBACK_QUEUE)
