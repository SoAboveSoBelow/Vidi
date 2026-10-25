// AM (PLAYBACK_QUEUE) -->
package aniyomi.domain.queue.interactor

import aniyomi.domain.queue.repository.QueueRepository
import dev.zacsweers.metro.Inject

/**
 * Drops entries the user has already gone past, keeping a trailing window of
 * them.
 *
 * Deliberately NOT a cap on the queue's length. A cap punishes the one thing
 * the queue is for - lining up a lot to watch - by throwing away things the
 * user queued and has not reached. Only what is BEHIND the playing position is
 * ever removed, so anything queued ahead survives however much of it there is.
 *
 * The window is what makes stepping back work: going forward and then back a
 * couple of entries has to still find them, so the last
 * [QueueRepository] blocks before the current one are kept and only what is
 * further back than that goes.
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
     * [keepBehind] blocks before the one holding [currentEpisodeId] are kept;
     * earlier ones are removed. 0 disables trimming entirely, matching how the
     * rest of the app reads 0 in a limit preference.
     *
     * Works in BLOCKS, not items: a block is one entry as the user queued it,
     * and half an entry is not a thing anyone asked to keep.
     */
    suspend fun await(currentEpisodeId: Long, keepBehind: Int) {
        if (keepBehind <= 0) return

        val items = queueRepository.getItems()
        if (items.isEmpty()) return

        // getItems is in play order, so the blocks in first-seen order ARE the
        // queue's block order - no second query for the blocks themselves.
        val blockOrder = items.map { it.blockId }.distinct()
        val currentBlock = items.firstOrNull { it.episodeId == currentEpisodeId }?.blockId
            ?: return
        val position = blockOrder.indexOf(currentBlock)

        val expendable = position - keepBehind
        if (expendable <= 0) return

        blockOrder.take(expendable).forEach { queueRepository.removeBlock(it) }
    }
}
// <-- AM (PLAYBACK_QUEUE)
