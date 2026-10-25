// AM (PLAYBACK_QUEUE) -->
package aniyomi.domain.queue.model

/**
 * One run of queued items, added together - see queue_blocks.sq.
 *
 * [animeId] is where the block came from and is null for items with no library
 * entry behind them. Nothing resolves playback through it; the items carry
 * their own ids.
 */
data class QueueBlock(
    val id: Long,
    val position: Long,
    val animeId: Long?,
    val fillState: QueueFillState,
)

/**
 * Whether a block's items are all there yet.
 *
 * A block queued from Browse usually has no episodes in the database to point
 * at, so it is inserted [PENDING] and holds its place in the order while a
 * worker fetches them (see queue_blocks.sq). A block built from episodes that
 * were already local is [COMPLETE] from the start and never passes through
 * pending.
 */
enum class QueueFillState(val value: Long) {
    /** Every item the block will ever have is in queue_items. */
    COMPLETE(0),

    /** Waiting on a fetch. May hold no items at all. */
    PENDING(1),

    /**
     * The fetch gave up. Distinguished from [PENDING] so a block that failed
     * does not get retried forever by a worker that cannot tell the two
     * apart, and so the queue can say which entry did not arrive. Whatever
     * items did land are left alone: a partly filled block is still a
     * truthful play order, just a shorter one than asked for.
     */
    FAILED(2),
    ;

    companion object {
        fun from(value: Long): QueueFillState = entries.firstOrNull { it.value == value } ?: COMPLETE
    }
}
// <-- AM (PLAYBACK_QUEUE)
