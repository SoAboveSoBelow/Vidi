// AM (PLAYBACK_QUEUE) -->
package aniyomi.data.queue

import aniyomi.domain.queue.model.QueueBlock
import aniyomi.domain.queue.model.QueueFillState
import aniyomi.domain.queue.model.QueueItem
import aniyomi.domain.queue.repository.QueueRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class QueueRepositoryImpl(
    private val database: Database,
) : QueueRepository {

    override suspend fun getItems(): List<QueueItem> {
        return database.queue_itemsQueries.getAllItems(::mapItem).awaitAsList()
    }

    override fun getItemsAsFlow(): Flow<List<QueueItem>> {
        return database.queue_itemsQueries.getAllItems(::mapItem).subscribeToList()
    }

    override suspend fun getItemsByBlockId(blockId: Long): List<QueueItem> {
        return database.queue_itemsQueries.getItemsByBlockId(blockId, ::mapItem).awaitAsList()
    }

    override suspend fun getBlocks(): List<QueueBlock> {
        return database.queue_blocksQueries.getAllBlocks(::mapBlock).awaitAsList()
    }

    override fun getBlocksAsFlow(): Flow<List<QueueBlock>> {
        return database.queue_blocksQueries.getAllBlocks(::mapBlock).subscribeToList()
    }

    override suspend fun getBlock(blockId: Long): QueueBlock? {
        return database.queue_blocksQueries.getBlockById(blockId, ::mapBlock).awaitAsOneOrNull()
    }

    override suspend fun getBlocksByFillState(fillState: QueueFillState): List<QueueBlock> {
        return database.queue_blocksQueries
            .getBlocksByFillState(fillState.value, ::mapBlock)
            .awaitAsList()
    }

    /**
     * The whole append is one transaction: a block that became visible before
     * its items would be read as an empty one, and an empty block means
     * something specific (pending a fetch) that an append is not.
     */
    override suspend fun addBlock(
        animeId: Long?,
        items: List<QueueItem.New>,
        fillState: QueueFillState,
    ): Long {
        return database.transactionWithResult {
            val position = nextBlockPosition()
            val blockId = database.queue_blocksQueries
                .insertReturningId(position, animeId, fillState.value)
                .awaitAsOne()
            val itemCount = database.queue_itemsQueries.getAllItems(::mapItem).awaitAsList().size
            insertItemsAt(blockId, insertAt = itemCount, items = items)
            blockId
        }
    }

    override suspend fun addItems(blockId: Long, items: List<QueueItem.New>) {
        if (items.isEmpty()) return
        database.transaction {
            val all = database.queue_itemsQueries.getAllItems(::mapItem).awaitAsList()
            insertItemsAt(
                blockId = blockId,
                insertAt = blockInsertionIndex(all, blockId, appendToExisting = true),
                items = items,
            )
        }
    }

    override suspend fun replaceBlockItems(blockId: Long, items: List<QueueItem.New>) {
        database.transaction {
            val all = database.queue_itemsQueries.getAllItems(::mapItem).awaitAsList()
            val insertAt = blockInsertionIndex(all, blockId, appendToExisting = false)
            database.queue_itemsQueries.deleteByBlockId(blockId)
            insertItemsAt(blockId, insertAt = insertAt, items = items)
        }
    }

    override suspend fun setBlockFillState(blockId: Long, fillState: QueueFillState) {
        database.queue_blocksQueries.setFillState(fillState.value, blockId)
    }

    // AM (QUEUE_BLOCK_PROGRESS)
    override suspend fun setBlockLastPlayedEpisode(blockId: Long, episodeId: Long?) {
        database.queue_blocksQueries.setLastPlayedEpisode(episodeId, blockId)
    }

    // AM (QUEUE_BLOCK_PROGRESS)
    override suspend fun setBlockLastPlayedCompleted(blockId: Long, episodeId: Long) {
        database.queue_blocksQueries.setLastPlayedCompleted(blockId, episodeId)
    }

    override suspend fun setBlockOrder(orderedBlockIds: List<Long>) {
        database.transaction {
            val blocks = database.queue_blocksQueries.getAllBlocks(::mapBlock).awaitAsList()
            val requested = orderedBlockIds.distinct().filter { id -> blocks.any { it.id == id } }
            val orderedIds = requested + blocks.map { it.id }.filterNot { it in requested }
            orderedIds.forEachIndexed { index, blockId ->
                database.queue_blocksQueries.setPosition(index.toLong() * POSITION_STEP, blockId)
            }
            // AM (QUEUE_SOURCE_INTERLEAVE) -->
            // Block positions only. This used to gather every source's items
            // into its new coarse slot, which is why a source could never sit
            // inside another one: any reorder flattened the queue back into
            // one contiguous run per source. Moving a source is an item move
            // now (see PlaylistReorder), so this is left to do the one thing
            // only a block position can express - where a block with no items
            // yet sits while it waits for its fetch (see blockInsertionIndex).
            // <-- AM (QUEUE_SOURCE_INTERLEAVE)
        }
    }

    // AM (QUEUE_REORDER) -->
    override suspend fun moveItems(itemIds: List<Long>, afterItemId: Long?) {
        if (itemIds.isEmpty()) return
        database.transaction {
            val all = database.queue_itemsQueries.getAllItems(::mapItem).awaitAsList()
            val movingIds = itemIds.toSet()
            val moving = all.filter { it.id in movingIds }
            if (moving.isEmpty()) return@transaction

            val remaining = all.filterNot { it.id in movingIds }
            val anchorIndex = afterItemId?.let { anchor ->
                remaining.indexOfFirst { it.id == anchor }
                    .also { if (it == -1) return@transaction }
            } ?: -1
            // AM (QUEUE_SPARSE_POSITIONS): only the moved rows are rewritten,
            // into the gap after the anchor; the rest of the queue is left
            // alone unless that gap has run out.
            val positions = placeItems(remaining, anchorIndex + 1, moving.size)
            moving.forEachIndexed { index, item ->
                database.queue_itemsQueries.setPosition(positions[index], item.id)
            }
        }
    }
    // <-- AM (QUEUE_REORDER)

    // AM (QUEUE_REBUILD_READ_ONLY)
    override suspend fun reorderItems(orderedItemIds: List<Long>) {
        if (orderedItemIds.size < 2) return
        database.transaction {
            val all = database.queue_itemsQueries.getAllItems(::mapItem).awaitAsList()
            val byId = all.associateBy { it.id }
            val moving = orderedItemIds.mapNotNull(byId::get)
            if (moving.size < 2) return@transaction
            // The positions this set already holds, handed back out in the new
            // order: nothing outside the set moves, and the set as a whole
            // stays exactly where it was in the queue.
            val slots = moving.map { it.position }.sorted()
            moving.forEachIndexed { index, item ->
                val position = slots[index]
                if (item.position != position) {
                    database.queue_itemsQueries.setPosition(position, item.id)
                }
            }
        }
    }

    override suspend fun removeItem(itemId: Long) {
        database.queue_itemsQueries.delete(itemId)
    }

    // AM (QUEUE_TRIM_BY_POSITION)
    override suspend fun removeItems(itemIds: List<Long>) {
        if (itemIds.isEmpty()) return
        database.transaction {
            database.queue_itemsQueries.deleteByItemIds(itemIds)
            database.queue_blocksQueries.deleteEmptyComplete()
        }
    }

    override suspend fun removeItemsByEpisodeIds(episodeIds: List<Long>) {
        if (episodeIds.isEmpty()) return
        database.transaction {
            database.queue_itemsQueries.deleteByEpisodeIds(episodeIds)
            database.queue_blocksQueries.deleteEmptyComplete()
        }
    }

    override suspend fun removeItemsByAnimeAndEpisodeIds(animeId: Long, episodeIds: List<Long>) {
        if (episodeIds.isEmpty()) return
        database.transaction {
            database.queue_itemsQueries.deleteByAnimeAndEpisodeIds(animeId, episodeIds)
            database.queue_blocksQueries.deleteEmptyComplete()
        }
    }

    override suspend fun removeBlock(blockId: Long) {
        // Items go with it through the foreign key.
        database.queue_blocksQueries.delete(blockId)
    }

    override suspend fun clear() {
        database.transaction {
            database.queue_itemsQueries.deleteAll()
            database.queue_blocksQueries.deleteAll()
        }
    }

    override suspend fun count(): Long {
        return database.queue_itemsQueries.countItems().awaitAsOne()
    }

    override suspend fun getAllQueuedAnimeIds(): Set<Long> {
        // The column is nullable in the table; the query's WHERE excludes the
        // nulls, which SQLDelight's generated type does not know about.
        return database.queue_itemsQueries.getAllQueuedAnimeIds().awaitAsList().filterNotNull().toSet()
    }

    /**
     * Positions are spaced rather than consecutive so a later insert between
     * two rows has somewhere to go without renumbering everything after it.
     */
    private suspend fun nextBlockPosition(): Long {
        val max = database.queue_blocksQueries.maxPosition().awaitAsOneOrNull()
        return max?.plus(POSITION_STEP) ?: FIRST_POSITION
    }

    /**
     * Where new rows for a block belong in global play order.
     *
     * Appending to a populated block goes after its last current run. Replacing
     * a block gathers its replacement at the first run instead. An empty block
     * has no row to anchor to, so its coarse block position finds the first row
     * of the next populated block; with none, the slot is the end of the queue.
     */
    private suspend fun blockInsertionIndex(
        all: List<QueueItem>,
        blockId: Long,
        appendToExisting: Boolean,
    ): Int {
        val existingIndex = if (appendToExisting) {
            all.indexOfLast { it.blockId == blockId }.let { if (it == -1) -1 else it + 1 }
        } else {
            all.indexOfFirst { it.blockId == blockId }
        }
        if (existingIndex != -1) return existingIndex

        val blocks = database.queue_blocksQueries.getAllBlocks(::mapBlock).awaitAsList()
        val laterBlockIds = blocks.dropWhile { it.id != blockId }.drop(1).mapTo(mutableSetOf()) { it.id }
        val nextBlockItem = all.indexOfFirst { it.blockId in laterBlockIds }
        return if (nextBlockItem == -1) all.size else nextBlockItem
    }

    /** Writes the new rows into the gap at [insertAt]. */
    private suspend fun insertItemsAt(blockId: Long, insertAt: Int, items: List<QueueItem.New>) {
        if (items.isEmpty()) return
        val all = database.queue_itemsQueries.getAllItems(::mapItem).awaitAsList()
        check(insertAt in 0..all.size)
        // AM (QUEUE_SPARSE_POSITIONS): into the gap between neighbours rather
        // than rewriting every row - see placeItems.
        val positions = placeItems(all, insertAt, items.size)
        items.forEachIndexed { index, item ->
            database.queue_itemsQueries.insert(
                blockId = blockId,
                position = positions[index],
                animeId = item.animeId,
                episodeId = item.episodeId,
                uri = item.uri,
            )
        }
    }

    // AM (QUEUE_SPARSE_POSITIONS) -->
    /**
     * Positions for [count] rows landing at [insertAt] among [rows].
     *
     * The gap first, and a full renumber only when the gap cannot hold them.
     * Writing every row's position on every insert and every move - which is
     * what this replaces - made each queue add and each drag cost one UPDATE
     * per queued item, and an open performs both; it also quietly gave up the
     * sparse ordering the table is documented to have (see queue_items.sq).
     * One renumber restores a full step between every pair, so the expensive
     * path is rare by construction.
     */
    private suspend fun placeItems(rows: List<QueueItem>, insertAt: Int, count: Int): List<Long> {
        positionsBetween(rows, insertAt, count)?.let { return it }
        renumberItems(rows)
        val renumbered = rows.mapIndexed { index, row -> row.copy(position = index.toLong() * POSITION_STEP) }
        return positionsBetween(renumbered, insertAt, count)
            ?: error("No room for $count queue items at $insertAt")
    }

    /**
     * [count] strictly increasing positions between the rows either side of
     * [insertAt], or null when there is not enough room between them.
     *
     * A head insert steps DOWN from the first row, which can go negative:
     * ordering is the whole meaning of this column and negatives order
     * correctly, so there is no reason to renumber the queue to make room at
     * the front.
     */
    private fun positionsBetween(rows: List<QueueItem>, insertAt: Int, count: Int): List<Long>? {
        val before = rows.getOrNull(insertAt - 1)?.position
        val after = rows.getOrNull(insertAt)?.position
        return when {
            before == null && after == null -> List(count) { FIRST_POSITION + it * POSITION_STEP }
            before == null -> {
                val start = after!! - POSITION_STEP * count
                List(count) { start + it * POSITION_STEP }
            }
            after == null -> List(count) { before + POSITION_STEP * (it + 1) }
            else -> {
                val step = (after - before) / (count + 1)
                if (step <= 0) null else List(count) { before + step * (it + 1) }
            }
        }
    }
    // <-- AM (QUEUE_SPARSE_POSITIONS)

    private suspend fun renumberItems(items: List<QueueItem>) {
        items.forEachIndexed { index, item ->
            val position = index.toLong() * POSITION_STEP
            if (item.position != position) {
                database.queue_itemsQueries.setPosition(position, item.id)
            }
        }
    }

    private fun mapItem(
        id: Long,
        blockId: Long,
        position: Long,
        animeId: Long?,
        episodeId: Long?,
        uri: String?,
    ): QueueItem {
        return QueueItem(
            id = id,
            blockId = blockId,
            position = position,
            animeId = animeId,
            episodeId = episodeId,
            uri = uri,
        )
    }

    private fun mapBlock(
        id: Long,
        position: Long,
        animeId: Long?,
        fillState: Long,
        // AM (QUEUE_BLOCK_PROGRESS) -->
        lastPlayedEpisodeId: Long?,
        lastPlayedCompleted: Long,
        // <-- AM (QUEUE_BLOCK_PROGRESS)
    ): QueueBlock {
        return QueueBlock(
            id = id,
            position = position,
            animeId = animeId,
            fillState = QueueFillState.from(fillState),
            // AM (QUEUE_BLOCK_PROGRESS) -->
            lastPlayedEpisodeId = lastPlayedEpisodeId,
            lastPlayedCompleted = lastPlayedCompleted == 1L,
            // <-- AM (QUEUE_BLOCK_PROGRESS)
        )
    }

    private companion object {
        const val FIRST_POSITION = 0L
        const val POSITION_STEP = 1024L
    }
}
// <-- AM (PLAYBACK_QUEUE)
