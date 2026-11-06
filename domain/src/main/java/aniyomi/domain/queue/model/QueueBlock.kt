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
    // AM (QUEUE_BLOCK_PROGRESS) -->
    /**
     * Where the user left this source in THIS queued run, or null when nothing
     * in the block has played yet.
     *
     * What a step crossing into the block from a later one lands on - see
     * queue_blocks.sq for why this is the queue's own memory rather than a
     * history lookup.
     */
    val lastPlayedEpisodeId: Long? = null,

    /**
     * Whether [lastPlayedEpisodeId] was finished - playback crossed the
     * mark-as-watched threshold - rather than left part way through.
     *
     * Recorded as the event it is, at the moment the threshold fires, rather
     * than read back off the episode's seen column later: seen cannot tell an
     * episode finished today from one watched a year ago, and the column is
     * the same either way. See GetQueueResumePoint for what reads it.
     */
    val lastPlayedCompleted: Boolean = false,
    // <-- AM (QUEUE_BLOCK_PROGRESS)
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
