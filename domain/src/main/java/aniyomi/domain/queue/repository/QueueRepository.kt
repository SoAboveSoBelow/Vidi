// AM (PLAYBACK_QUEUE) -->
package aniyomi.domain.queue.repository

import aniyomi.domain.queue.model.QueueBlock
import aniyomi.domain.queue.model.QueueFillState
import aniyomi.domain.queue.model.QueueItem
import kotlinx.coroutines.flow.Flow

/**
 * The stored play order (see queue_items.sq and queue_blocks.sq).
 *
 * Storage only: this decides nothing about what order an entry's episodes
 * should be in - GetEpisodeOrder does that before anything reaches here - and
 * nothing about what plays next, which is PlaybackQueue's job over the items
 * this returns.
 */
interface QueueRepository {

    /** The whole queue in play order: block position, then item position. */
    suspend fun getItems(): List<QueueItem>

    /**
     * Reactive [getItems]. Emits on any insert, reorder or removal, including
     * a worker filling a pending block - which is how an open queue screen
     * shows items arriving without being told to look again.
     */
    fun getItemsAsFlow(): Flow<List<QueueItem>>

    suspend fun getItemsByBlockId(blockId: Long): List<QueueItem>

    suspend fun getBlocks(): List<QueueBlock>

    fun getBlocksAsFlow(): Flow<List<QueueBlock>>

    suspend fun getBlock(blockId: Long): QueueBlock?

    /** Blocks in a given state, in queue order - what a fill worker picks up. */
    suspend fun getBlocksByFillState(fillState: QueueFillState): List<QueueBlock>

    /**
     * Appends a block after everything already queued and returns its id.
     *
     * [items] may be empty, which is the pending case: the block takes its
     * place in the order now and is filled once a fetch has something to put
     * in it. Both inserts happen in one transaction, so a block never becomes
     * visible without the items it was created with.
     */
    suspend fun addBlock(
        animeId: Long?,
        items: List<QueueItem.New>,
        fillState: QueueFillState = QueueFillState.COMPLETE,
    ): Long

    /** Appends to an existing block, keeping its place in the queue. */
    suspend fun addItems(blockId: Long, items: List<QueueItem.New>)

    /**
     * Replaces a block's items wholesale, in one transaction.
     *
     * This is what a shuffle-seed or search-scope change does to a block that
     * is already queued: the order within the entry changes, where the entry
     * sits in the queue does not. Expressed as a replace rather than a
     * reorder because the new order may not hold the same episodes - a
     * re-scoped search is a different set, not a permutation.
     */
    suspend fun replaceBlockItems(blockId: Long, items: List<QueueItem.New>)

    suspend fun setBlockFillState(blockId: Long, fillState: QueueFillState)

    // AM (QUEUE_BLOCK_PROGRESS) -->
    /**
     * Records where the user is in [blockId] - see [QueueBlock.lastPlayedEpisodeId].
     *
     * Null clears it, which is what a block being re-filled with a different
     * set of episodes means: the remembered one may not be in it any more.
     */
    suspend fun setBlockLastPlayedEpisode(blockId: Long, episodeId: Long?)
    // <-- AM (QUEUE_BLOCK_PROGRESS)

    /** Rewrites the order of the blocks, in the order given. */
    suspend fun setBlockOrder(orderedBlockIds: List<Long>)

    suspend fun removeItem(itemId: Long)

    /**
     * Removes every queued position of [episodeIds], and prunes any block left
     * empty by it.
     *
     * What makes queueing something already queued MOVE it rather than
     * duplicate it: the old position goes, the new append becomes its only
     * one. Pending blocks are never pruned - see queue_blocks.deleteEmptyComplete.
     */
    suspend fun removeItemsByEpisodeIds(episodeIds: List<Long>)

    suspend fun removeBlock(blockId: Long)

    suspend fun clear()

    suspend fun count(): Long

    /**
     * Every anime the queue points at, favorited or not - the set Clear
     * Database has to leave alone, for the same reason it leaves merge
     * children alone. See queue_items.getAllQueuedAnimeIds.
     */
    suspend fun getAllQueuedAnimeIds(): Set<Long>
}
// <-- AM (PLAYBACK_QUEUE)
