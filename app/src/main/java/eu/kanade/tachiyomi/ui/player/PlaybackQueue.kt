package eu.kanade.tachiyomi.ui.player

// AM (CONTENT_AGNOSTIC_SESSION) -->
/**
 * What plays next.
 *
 * The last of the three responsibilities PlayerViewModel conflated into the
 * Anime/Episode pair - progress went to [PlaybackProgressStore], resolution to
 * [PlaybackSource], and this is the order they come in.
 *
 * Deliberately narrow: positions and [SessionKey]s, nothing else. An episode
 * list carries a great deal besides order - seasons, shuffle seeds, search
 * scope, per-episode names and tags, merged-entry owners - and all of it is
 * library-playlist presentation that stays in PlayerStateData where the UI
 * reads it. What a session actually needs in order to advance is "is there an
 * item after this one, and what is it", which is the whole of this interface
 * and the only part a folder of video files can also answer.
 */
interface PlaybackQueue {

    /** How many items are queued. */
    val size: Int

    /** The item at [index], or null when [index] is outside the queue. */
    fun keyAt(index: Int): SessionKey?

    /** Where [key] sits, or -1 when it isn't queued. */
    fun indexOf(key: SessionKey): Int

    // AM (QUEUE_BLOCK_STRUCTURE) -->
    /**
     * Which queue block the item at [index] belongs to, or null when this
     * order has no blocks at all.
     *
     * Null is the honest answer for an order that is not the stored queue - a
     * folder of files, a single external video - rather than a fabricated
     * block id, and the helpers below read it as "the whole order is one
     * run", which is exactly what those orders are: there is no other source
     * in them to step to.
     */
    fun blockAt(index: Int): Long? = null
    // <-- AM (QUEUE_BLOCK_STRUCTURE)
}

/**
 * Whether an item follows [index].
 *
 * Both of these keep the exact comparisons the ViewModel used inline
 * (`index != 0`, `index != size - 1`), including their behaviour at index -1 -
 * an episode not found in the playlist reports BOTH a previous and a next.
 * That is odd read cold, but it is what every caller has always seen, and a
 * refactor is the wrong place to change what the next/previous buttons do on
 * an unlisted episode.
 */
fun PlaybackQueue.hasNext(index: Int): Boolean = index != size - 1

fun PlaybackQueue.hasPrevious(index: Int): Boolean = index != 0

// AM (QUEUE_BLOCK_STRUCTURE) -->
/**
 * The run of consecutive items the item at [index] belongs to - one queued
 * source, as the user added it.
 *
 * Computed by walking out from [index] while the block id holds, rather than
 * by grouping the whole order: the caller always asks about a position, the
 * runs are contiguous by construction (play order is block position then item
 * position), and a walk costs the length of one run instead of the length of
 * the queue.
 *
 * An order with no blocks answers with the whole of itself, so callers need no
 * branch for it.
 */
fun PlaybackQueue.blockRangeAt(index: Int): IntRange? {
    if (index !in 0 until size) return null
    val blockId = blockAt(index) ?: return 0 until size
    var first = index
    while (first > 0 && blockAt(first - 1) == blockId) first--
    var last = index
    while (last < size - 1 && blockAt(last + 1) == blockId) last++
    return first..last
}

/**
 * The first index of the run before or after the one holding [index], or null
 * when there is no further source that way.
 */
fun PlaybackQueue.adjacentBlockRange(index: Int, forward: Boolean): IntRange? {
    val range = blockRangeAt(index) ?: return null
    val step = if (forward) range.last + 1 else range.first - 1
    if (step !in 0 until size) return null
    return blockRangeAt(step)
}
// <-- AM (QUEUE_BLOCK_STRUCTURE)

/**
 * No order at all - a session with nothing loaded yet.
 *
 * Exists so callers can ask the same questions before an entry is loaded
 * instead of branching on a null queue. With size 0 the two helpers above
 * answer exactly what the inline comparisons did against an empty
 * currentPlaylist: at index -1, a previous and no next.
 */
object EmptyPlaybackQueue : PlaybackQueue {
    override val size: Int get() = 0
    override fun keyAt(index: Int): SessionKey? = null
    override fun indexOf(key: SessionKey): Int = -1
}

/**
 * The episodes of one library entry, in play order.
 *
 * Built fresh from PlayerStateData.currentPlaylist at each use rather than
 * held as a field, so it cannot drift from the list the UI is showing -
 * the playlist is rebuilt on a shuffle-seed change and re-scoped by search,
 * and a cached copy would be one more thing to remember to invalidate.
 *
 * [episodeIds] may contain nulls: currentPlaylist holds the legacy Episode
 * type, whose id is nullable for a row that was never inserted. Those
 * positions exist in the queue (they are real entries in the play order) but
 * match no key.
 */
class LibraryPlaybackQueue(
    private val animeId: Long,
    private val episodeIds: List<Long?>,
    // AM (QUEUE_BLOCK_STRUCTURE) -->
    /**
     * Which block each episode is queued under, by episode id. Empty before
     * the queue has been read - the playlist still plays, it simply has no
     * source boundaries to step by yet.
     */
    private val blockByEpisodeId: Map<Long, Long> = emptyMap(),
    // <-- AM (QUEUE_BLOCK_STRUCTURE)
) : PlaybackQueue {

    override val size: Int get() = episodeIds.size

    // AM (QUEUE_BLOCK_STRUCTURE) -->
    // Keyed by episode id rather than by position: the playlist is rebuilt,
    // re-scoped and reshuffled under a live session, and a positional copy
    // would have to be rebuilt in lockstep with it. The ids are what both
    // sides already agree on.
    override fun blockAt(index: Int): Long? {
        if (blockByEpisodeId.isEmpty()) return null
        return episodeIds.getOrNull(index)?.let(blockByEpisodeId::get)
    }
    // <-- AM (QUEUE_BLOCK_STRUCTURE)

    override fun keyAt(index: Int): SessionKey? {
        val episodeId = episodeIds.getOrNull(index) ?: return null
        return SessionKey.Library(animeId = animeId, episodeId = episodeId)
    }

    override fun indexOf(key: SessionKey): Int {
        if (key !is SessionKey.Library || key.animeId != animeId) return -1
        return episodeIds.indexOf(key.episodeId)
    }
}
// <-- AM (CONTENT_AGNOSTIC_SESSION)
