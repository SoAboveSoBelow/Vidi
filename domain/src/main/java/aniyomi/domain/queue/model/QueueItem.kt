// AM (PLAYBACK_QUEUE) -->
package aniyomi.domain.queue.model

/**
 * One playable item in the queue - see queue_items.sq.
 *
 * Deliberately a flat row rather than a sealed type, because that is what the
 * table holds and the either/or is enforced there by a CHECK. The sealed type
 * this corresponds to is SessionKey, which lives in the app module; turning a
 * row into a key is the resolver's job, not this model's, so domain code can
 * read and reorder the queue without depending on the player.
 *
 * Exactly one of these shapes holds for a valid row:
 *  - [animeId] and [episodeId] both set, [uri] null: an episode of an entry.
 *  - [uri] set, both ids null: a video from outside the app.
 *
 * For an episode of a merged entry, [animeId] is the merge parent the item was
 * queued as - not the child that owns the episode.
 */
data class QueueItem(
    val id: Long,
    val blockId: Long,
    val position: Long,
    val animeId: Long?,
    val episodeId: Long?,
    val uri: String?,
) {
    /**
     * What to write for a new item, before the row exists and has an id or a
     * position. The two factories are the only intended way to build one, so
     * a caller cannot assemble a combination the table would reject.
     */
    data class New(
        val animeId: Long?,
        val episodeId: Long?,
        val uri: String?,
    ) {
        companion object {
            fun library(animeId: Long, episodeId: Long) = New(
                animeId = animeId,
                episodeId = episodeId,
                uri = null,
            )

            fun external(uri: String) = New(
                animeId = null,
                episodeId = null,
                uri = uri,
            )
        }
    }
}
// <-- AM (PLAYBACK_QUEUE)
