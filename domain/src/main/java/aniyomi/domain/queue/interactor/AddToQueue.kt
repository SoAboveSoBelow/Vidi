// AM (PLAYBACK_QUEUE) -->
package aniyomi.domain.queue.interactor

import aniyomi.domain.order.interactor.GetEpisodeOrder
import aniyomi.domain.queue.model.QueueFillState
import aniyomi.domain.queue.model.QueueItem
import aniyomi.domain.queue.repository.QueueRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.service.getEpisodeSort

/**
 * Appends to the play order.
 *
 * The single way anything gets queued, from any screen: every selection
 * surface hands over entries or episodes and this decides nothing about them
 * except where they go. In particular it does not work out an entry's episode
 * order itself - [GetEpisodeOrder] does, and already handles the cases that
 * would otherwise each need their own branch here: a merged entry's union
 * across its children, seasons, and a user's custom episode order. Resolving
 * eagerly, at add time, is what lets queue_items hold a flat list of ids and
 * StoredPlaybackQueue stay a snapshot.
 *
 * Shuffle is NOT applied: the player shuffles the playlist it builds for a
 * session (see PlayerViewModel.setupEpisodeList), and a queue built here is
 * the order the user asked for rather than a session's. How the two meet is
 * the playback step's problem, not this one's.
 *
 * The SORT is applied, the same way setupEpisodeList applies it - see
 * [orderFor]. [GetEpisodeOrder] deliberately returns an ordinary
 * entry's episodes in raw database order when there is no custom order to
 * impose, leaving the display sort to the caller, so queueing without this
 * would put the tail of the playlist in an order matching neither the entry's
 * sort setting nor the episodes ahead of it.
 */
@Inject
class AddToQueue(
    private val queueRepository: QueueRepository,
    private val getEpisodeOrder: GetEpisodeOrder,
) {

    /**
     * Queues all of [anime]'s episodes, in play order, as one block.
     *
     * An entry with no episodes in the database yet - a browse result nothing
     * has fetched - is queued [QueueFillState.PENDING] rather than skipped:
     * the block holds its place in the order while the fetch happens, which is
     * the whole reason blocks exist (see queue_blocks.sq). Nothing fetches for
     * it yet, so such a block stays pending until the fill worker lands.
     *
     * Returns the new block's id.
     */
    suspend fun await(anime: Anime): Long {
        val episodes = orderFor(anime)
        // Queueing something already queued moves it to the end rather than
        // leaving it in two places - see removeItemsByEpisodeIds.
        queueRepository.removeItemsByEpisodeIds(episodes.map { it.id })
        if (episodes.isEmpty()) {
            // Nothing to move by episode id yet, so the same entry queued
            // twice before its fetch lands would otherwise end up with two
            // pending blocks that both fill with the same episodes. Dropping
            // the earlier one applies the same move-to-the-end rule to a block
            // whose contents are not known.
            queueRepository.getBlocksByFillState(QueueFillState.PENDING)
                .filter { it.animeId == anime.id }
                .forEach { queueRepository.removeBlock(it.id) }
        }
        return queueRepository.addBlock(
            animeId = anime.id,
            items = episodes.map { QueueItem.New.library(animeId = anime.id, episodeId = it.id) },
            fillState = if (episodes.isEmpty()) QueueFillState.PENDING else QueueFillState.COMPLETE,
        )
    }

    /** Queues each entry as its own block, in the order given. */
    suspend fun await(animeList: List<Anime>): List<Long> {
        return animeList.map { await(it) }
    }

    /**
     * Queues particular episodes of [anime] as one block, in the order given -
     * the caller's order is kept, because a selection made from a list the user
     * is looking at is already in the order they see.
     *
     * Unlike [await], an empty list queues nothing at all: there is nothing to
     * wait for, so a block would be a permanently empty one rather than a
     * pending one. Returns null in that case.
     */
    suspend fun await(anime: Anime, episodes: List<Episode>): Long? {
        if (episodes.isEmpty()) return null
        queueRepository.removeItemsByEpisodeIds(episodes.map { it.id })
        return queueRepository.addBlock(
            animeId = anime.id,
            items = episodes.map { QueueItem.New.library(animeId = anime.id, episodeId = it.id) },
        )
    }

    /**
     * An entry's episodes in the order the player would play them.
     *
     * Public because the fill worker needs the same answer for a block it is
     * completing rather than creating - one ordering, two callers, instead of
     * the worker growing its own copy of this and drifting from it.
     *
     * Mirrors setupEpisodeList: preordered entries - a by-source sort with a
     * custom order, and every merged entry - are used exactly as resolved,
     * and everything else takes the entry's sort MODE with the direction
     * forced ascending. The direction is deliberately not the entry's own:
     * the player hardcodes it too, so a list viewed newest-first still plays
     * oldest-first, and a queue that disagreed would reverse part of the play
     * order depending on which screen it was added from.
     *
     * Alphabetical sorts on the resolved display name rather than the raw
     * episode name, for the reason getEpisodeSort's own doc gives: sorting by
     * a string the user cannot see reads as no sort at all.
     */
    suspend fun orderFor(anime: Anime): List<Episode> {
        val resolved = getEpisodeOrder.awaitResolved(anime)
        if (resolved.isPreordered) return resolved.episodes
        return resolved.episodes.sortedWith(
            getEpisodeSort(anime, sortDescending = false) { episode ->
                resolved.displayNameByEpisodeId[episode.id] ?: episode.name
            },
        )
    }

    /**
     * Queues [episodeIds] of [anime] as one block, by id.
     *
     * For a caller that already has the ids and the order - the player, whose
     * playlist IS a block of this queue - so it need not resolve episodes back
     * out of the database just to hand them over.
     */
    suspend fun awaitEpisodeIds(anime: Anime, episodeIds: List<Long>): Long? {
        if (episodeIds.isEmpty()) return null
        queueRepository.removeItemsByEpisodeIds(episodeIds)
        return queueRepository.addBlock(
            animeId = anime.id,
            items = episodeIds.map { QueueItem.New.library(animeId = anime.id, episodeId = it) },
        )
    }

    /**
     * Queues [episodeIds] of [anime] as what plays NEXT: directly after the
     * block holding [afterEpisodeId], or first in the queue when nothing is
     * playing.
     *
     * What picking something to watch does: it interrupts, so it belongs next
     * rather than behind everything already lined up. Appending would mean
     * jumping past the rest of the queue to reach it and finding nothing after
     * it - the queue would be in play order but the user would not be at the
     * front of it.
     *
     * [afterEpisodeId] is null on the first play of a session, where there is
     * nothing to come after. That is NOT the same as appending: with a queue
     * kept from a previous session there is plenty ahead, and the thing just
     * picked belongs at the front of it rather than behind all of it.
     *
     * Placement is done by appending and then rewriting the block order,
     * rather than by computing a position between two others: the sparse
     * positions leave room for only so many inserts between the same pair, and
     * a renumber that is correct every time beats one that is cheap until it
     * runs out of gap.
     */
    suspend fun awaitEpisodeIdsNext(
        anime: Anime,
        episodeIds: List<Long>,
        afterEpisodeId: Long?,
    ): Long? {
        if (episodeIds.isEmpty()) return null

        // Read BEFORE the add: removeItemsByEpisodeIds inside it can prune the
        // very block being inserted after, if the playing episode belonged to
        // the entry now being re-queued.
        val afterBlockId = afterEpisodeId?.let { id ->
            queueRepository.getItems().firstOrNull { it.episodeId == id }?.blockId
        }

        val newBlockId = awaitEpisodeIds(anime, episodeIds) ?: return null
        if (afterBlockId == newBlockId) return newBlockId

        val order = queueRepository.getBlocks().map { it.id }
        val without = order.filterNot { it == newBlockId }
        if (without.isEmpty()) return newBlockId

        // No anchor, or an anchor the add above pruned, both land at the
        // front: indexOf gives -1 for the missing one, so +1 is 0, which is
        // exactly where a first play belongs.
        val insertAt = afterBlockId?.let { without.indexOf(it) + 1 } ?: 0
        queueRepository.setBlockOrder(without.take(insertAt) + newBlockId + without.drop(insertAt))
        return newBlockId
    }

    /**
     * Rewrites an existing block's contents, keeping its place in the queue.
     *
     * The counterpart to [awaitEpisodeIds] for a refresh rather than an add:
     * the playlist being rebuilt under a playing episode must not move the
     * block it belongs to. Queue writes all live here rather than half here
     * and half in callers.
     */
    suspend fun refreshBlock(blockId: Long, anime: Anime, episodeIds: List<Long>) {
        queueRepository.replaceBlockItems(
            blockId = blockId,
            items = episodeIds.map { QueueItem.New.library(animeId = anime.id, episodeId = it) },
        )
    }

    /** Queues a video opened from outside the app. */
    suspend fun awaitExternal(uri: String): Long {
        return queueRepository.addBlock(
            animeId = null,
            items = listOf(QueueItem.New.external(uri)),
        )
    }
}
// <-- AM (PLAYBACK_QUEUE)
