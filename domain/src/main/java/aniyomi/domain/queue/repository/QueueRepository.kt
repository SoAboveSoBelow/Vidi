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

    /** The whole queue in global item-position order. */
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

    /**
     * Marks [blockId]'s remembered episode finished, if [episodeId] is still
     * the one remembered - see [QueueBlock.lastPlayedCompleted].
     */
    suspend fun setBlockLastPlayedCompleted(blockId: Long, episodeId: Long)
    // <-- AM (QUEUE_BLOCK_PROGRESS)

    // AM (QUEUE_SOURCE_INTERLEAVE) -->
    /**
     * Rewrites the coarse source order, leaving play order alone.
     *
     * Only a block with no items yet is positioned by this - it has nothing in
     * [moveItems]' terms to place. Moving a source that HAS items is an item
     * move, so that it can land inside another source's run rather than only
     * between whole sources.
     */
    suspend fun setBlockOrder(orderedBlockIds: List<Long>)
    // <-- AM (QUEUE_SOURCE_INTERLEAVE)

    // AM (QUEUE_REORDER) -->
    /**
     * Moves queue rows as one group, preserving their relative order.
     *
     * [afterItemId] null means the start of the whole queue. Moving rows never
     * changes block membership: source identity and progress stay with the
     * original block even when its episodes are interleaved with another's.
     */
    suspend fun moveItems(itemIds: List<Long>, afterItemId: Long?)

    // AM (QUEUE_REBUILD_READ_ONLY) -->
    /**
     * Reorders [orderedItemIds] among the positions those items already
     * occupy, leaving everything else exactly where it is.
     *
     * For a change to how one entry's episodes are ordered - a shuffle seed -
     * which must not move the entry relative to the rest of the queue, must
     * not disturb anything interleaved between its runs, and must not change
     * which of its episodes are queued.
     */
    suspend fun reorderItems(orderedItemIds: List<Long>)
    // <-- AM (QUEUE_REBUILD_READ_ONLY)
    // <-- AM (QUEUE_REORDER)

    suspend fun removeItem(itemId: Long)

    /**
     * Removes every queued position of [episodeIds], and prunes any block left
     * empty by it.
     *
     * What makes queueing something already queued MOVE it rather than
     * duplicate it: the old position goes, the new append becomes its only
     * one. Pending blocks are never pruned - see queue_blocks.deleteEmptyComplete.
     */
    // AM (QUEUE_TRIM_BY_POSITION) -->
    /**
     * Removes particular queued positions, and prunes any block left empty.
     *
     * By row identity rather than by episode or block, which is what a trim
     * needs now that one source's items can sit in several places: removing
     * the whole block would take runs the user has not reached yet.
     */
    suspend fun removeItems(itemIds: List<Long>)
    // <-- AM (QUEUE_TRIM_BY_POSITION)

    suspend fun removeItemsByEpisodeIds(episodeIds: List<Long>)

    /** Removes only positions queued as [animeId], leaving other entries' rows alone. */
    suspend fun removeItemsByAnimeAndEpisodeIds(animeId: Long, episodeIds: List<Long>)

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
