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
            insertItems(blockId, startPosition = FIRST_POSITION, items = items)
            blockId
        }
    }

    override suspend fun addItems(blockId: Long, items: List<QueueItem.New>) {
        if (items.isEmpty()) return
        database.transaction {
            insertItems(blockId, startPosition = nextItemPosition(blockId), items = items)
        }
    }

    override suspend fun replaceBlockItems(blockId: Long, items: List<QueueItem.New>) {
        database.transaction {
            database.queue_itemsQueries.deleteByBlockId(blockId)
            insertItems(blockId, startPosition = FIRST_POSITION, items = items)
        }
    }

    override suspend fun setBlockFillState(blockId: Long, fillState: QueueFillState) {
        database.queue_blocksQueries.setFillState(fillState.value, blockId)
    }

    override suspend fun setBlockOrder(orderedBlockIds: List<Long>) {
        database.transaction {
            orderedBlockIds.forEachIndexed { index, blockId ->
                database.queue_blocksQueries.setPosition(index.toLong() * POSITION_STEP, blockId)
            }
        }
    }

    override suspend fun removeItem(itemId: Long) {
        database.queue_itemsQueries.delete(itemId)
    }

    override suspend fun removeItemsByEpisodeIds(episodeIds: List<Long>) {
        if (episodeIds.isEmpty()) return
        database.transaction {
            database.queue_itemsQueries.deleteByEpisodeIds(episodeIds)
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

    private suspend fun nextItemPosition(blockId: Long): Long {
        val last = database.queue_itemsQueries
            .getItemsByBlockId(blockId, ::mapItem)
            .awaitAsList()
            .lastOrNull()
        return last?.position?.plus(POSITION_STEP) ?: FIRST_POSITION
    }

    private suspend fun insertItems(blockId: Long, startPosition: Long, items: List<QueueItem.New>) {
        items.forEachIndexed { index, item ->
            database.queue_itemsQueries.insert(
                blockId = blockId,
                position = startPosition + index * POSITION_STEP,
                animeId = item.animeId,
                episodeId = item.episodeId,
                uri = item.uri,
            )
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
    ): QueueBlock {
        return QueueBlock(
            id = id,
            position = position,
            animeId = animeId,
            fillState = QueueFillState.from(fillState),
        )
    }

    private companion object {
        const val FIRST_POSITION = 0L
        const val POSITION_STEP = 1024L
    }
}
// <-- AM (PLAYBACK_QUEUE)
