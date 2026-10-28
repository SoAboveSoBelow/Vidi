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
    suspend fun await(anime: Anime): Long = await(resolve(anime))

    // AM (QUEUE_RESOLVE_ONCE) -->
    /**
     * An entry's episodes in queue order, resolved once.
     *
     * Every queue add needs this list, and so does the question of whether the
     * entry has a mixed watch state - which is asked first, from the UI, to
     * decide whether to prompt. Resolving it is a full [GetEpisodeOrder] pass
     * (a merged entry's union across its children, seasons, custom order), so
     * asking and then adding used to pay for it two or three times per entry;
     * a multi-select paid that per entry before anything was queued.
     *
     * Passing the resolved list around instead means one pass per add. It also
     * makes the prompt and the add agree by construction: with two passes, an
     * episode arriving between them could leave the user answering a question
     * about a list that is not the one queued.
     */
    data class QueueSource(
        val anime: Anime,
        val episodes: List<Episode>,
    ) {
        /**
         * Whether this needs an "unwatched or all" answer. An entry that is
         * entirely watched, or entirely unwatched, has nothing to choose
         * between and is never prompted for.
         */
        val hasMixedWatchState: Boolean
            get() = episodes.any { it.seen } && episodes.any { !it.seen }
    }

    suspend fun resolve(anime: Anime): QueueSource = QueueSource(anime, orderFor(anime))

    suspend fun resolve(animeList: List<Anime>): List<QueueSource> = animeList.map { resolve(it) }

    /** Queues a pre-resolved source - see [QueueSource]. */
    suspend fun await(source: QueueSource): Long {
        val anime = source.anime
        val episodes = source.episodes
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

    // AM (QUEUE_RESOLVE_ONCE) -->
    /**
     * Queues pre-resolved sources, in the order given.
     *
     * [unwatchedOnly] is the one answer the user gave for the whole selection
     * and applies only where it means something: a source with a mixed watch
     * state. One that is entirely watched is queued whole rather than filtered
     * down to nothing, and one that is entirely unwatched is unaffected
     * either way.
     */
    suspend fun await(
        sources: List<QueueSource>,
        unwatchedOnly: Boolean = false,
        currentEpisodeId: Long? = null,
    ) {
        sources.forEach { source ->
            if (unwatchedOnly && source.hasMixedWatchState) {
                await(source, unwatchedOnly = true, currentEpisodeId = currentEpisodeId)
            } else {
                await(source, unwatchedOnly = false, currentEpisodeId = currentEpisodeId)
            }
        }
    }
    // <-- AM (QUEUE_RESOLVE_ONCE)

    // AM (QUEUE_WATCH_FILTER) -->
    /**
     * Queues [anime] as one source block, optionally keeping only unwatched episodes.
     *
     * A source with no resolved episodes still goes through the ordinary pending-
     * block path; "unwatched" is only a choice the UI offers once a mixed list is
     * actually available, so this cannot accidentally create an empty block.
     */
    suspend fun await(anime: Anime, unwatchedOnly: Boolean, currentEpisodeId: Long? = null): Long? =
        await(resolve(anime), unwatchedOnly, currentEpisodeId)

    suspend fun await(
        source: QueueSource,
        unwatchedOnly: Boolean,
        currentEpisodeId: Long? = null,
    ): Long? {
        val anime = source.anime
        val ordered = source.episodes
        // A merged entry is one queue source even if some of its rows were
        // added through a child. Match the resolved episode set rather than
        // whichever entry id each row happened to record.
        val orderedEpisodeIds = ordered.mapTo(mutableSetOf()) { it.id }
        val queuedItems = queueRepository.getItems().filter { it.episodeId in orderedEpisodeIds }
        val currentIsQueued = currentEpisodeId != null && queuedItems.any { it.episodeId == currentEpisodeId }

        if (!unwatchedOnly) {
            val queuedEpisodeIds = queuedItems.mapNotNull { it.episodeId }.toSet()
            val trimmedSourceIsQueued = ordered.any { it.seen && it.id !in queuedEpisodeIds }
            // AM (QUEUE_RESOLVE_ONCE): the resolved source, not the entry - the
            // list is already in hand and re-resolving it here was the third
            // pass over the same entry.
            if (!trimmedSourceIsQueued) return await(source)

            // Inverse of the unwatched cleanup: a trimmed source gets its
            // watched episodes back. Keep the active episode in its existing
            // position rather than moving it into the restored block.
            return await(
                anime,
                ordered.filterNot { currentIsQueued && it.id == currentEpisodeId },
            )
        }

        // Re-adding unwatched also cleans watched positions already queued as
        // this source. The active episode is left exactly where it is: removing
        // it, or moving it into the new block, would change the live playlist.
        queueRepository.removeItemsByEpisodeIds(
            ordered.filter { it.seen && it.id != currentEpisodeId }.map { it.id },
        )
        val episodes = ordered.filter { episode ->
            !episode.seen && !(currentIsQueued && episode.id == currentEpisodeId)
        }
        if (episodes.isEmpty()) return queuedItems.firstOrNull()?.blockId
        return await(anime, episodes)
    }
    // <-- AM (QUEUE_WATCH_FILTER)

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

        // AM (QUEUE_REORDER) -->
        // Moved as ITEMS, not by reordering blocks. setBlockOrder gathers every
        // source's runs into contiguous coarse slots, which is right for a
        // source drag and wrong here: opening something is not a request to
        // rearrange the rest of the queue, and going through it undid any
        // cross-source interleaving the user had dragged into place - on every
        // open, silently.
        //
        // The anchor is the playing ITEM rather than the end of its block, so
        // the new run lands directly after what is playing even when that
        // block's own run continues further down.
        val items = queueRepository.getItems()
        val moving = items.filter { it.blockId == newBlockId }.map { it.id }
        if (moving.isEmpty()) return newBlockId
        val anchorItemId = afterEpisodeId?.let { id ->
            items.firstOrNull { it.episodeId == id && it.blockId != newBlockId }?.id
        }
        // A null anchor is a fresh session: the front of the queue, which is
        // where a first play belongs, and which moveItems reads a null anchor
        // as.
        queueRepository.moveItems(moving, anchorItemId)
        // <-- AM (QUEUE_REORDER)
        return newBlockId
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
