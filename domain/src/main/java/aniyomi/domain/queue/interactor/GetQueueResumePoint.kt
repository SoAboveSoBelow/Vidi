// AM (PLAYBACK_QUEUE) -->
package aniyomi.domain.queue.interactor

import aniyomi.domain.queue.model.QueueItem
import aniyomi.domain.queue.repository.QueueRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.history.repository.HistoryRepository

/**
 * Where the queue should pick up - what "resume" means once the queue is the
 * play order.
 *
 * The answer is the episode that was last PLAYING, and nothing else. Not a
 * stored pointer - history already records it - and deliberately not anything
 * derived from how far through it the user got. Watched state cannot decide
 * this: seen is set at PlayerPreferences.progressPreference, 85% by default,
 * so it cannot tell an episode abandoned in its last stretch from one watched
 * to the end; and an already-seen episode being rewatched looks finished by
 * every stored measure while being exactly the thing the user wants back.
 * Both of those read as "finished" and would step the user forward past the
 * episode they were in.
 *
 * Stepping forward is not this gesture's job in the first place. When an
 * episode ends during playback the player advances through the queue itself,
 * and loading what it advanced to writes that episode to history - so natural
 * progression is already recorded here by the thing that actually played it.
 * Where in the episode to come back is likewise the player's own question,
 * answered by its resume priority (RESUME_PRIORITY_SYSTEM) when it loads.
 * This decides identity only.
 *
 * The queue is still consulted for two things: the entry an episode was
 * queued as, and - when nothing has played, or what played is gone - where
 * the order starts.
 *
 * Returns null only when nothing has ever played and the queue is empty.
 */
@Inject
class GetQueueResumePoint(
    private val queueRepository: QueueRepository,
    private val getEpisode: GetEpisode,
    private val historyRepository: HistoryRepository,
) {

    /**
     * An entry and one of its episodes, as the player is launched with.
     *
     * Taken off the queue row rather than derived from the episode: an item
     * records the entry it was queued AS, which for an episode of a merged
     * entry is the parent, not the child that owns the episode - the same
     * distinction AnimeScreen.openEpisode()'s targetAnimeId makes.
     */
    data class ResumePoint(val animeId: Long, val episodeId: Long)

    suspend fun await(): ResumePoint? {
        val items = queueRepository.getItems()
        val lastPlayed = historyRepository.getLastHistory()

        if (lastPlayed != null && getEpisode.await(lastPlayed.episodeId) != null) {
            // Existence only - the row can be gone with an entry removed from
            // the library or an episode dropped by a source refresh, and there
            // is nothing to open then. Nothing about its progress is read.
            val position = items.indexOfFirst {
                it.episodeId != null && it.episodeId == lastPlayed.episodeId
            }
            val animeId = items.getOrNull(position)?.animeId ?: lastPlayed.animeId
            return ResumePoint(animeId = animeId, episodeId = lastPlayed.episodeId)
        }

        // Nothing has played, or what played no longer exists: the queue
        // starts from its head. An empty one leaves the caller to fall back to
        // history alone.
        return items.firstResumePoint()
    }

    /**
     * The first item that can be opened as an entry's episode.
     *
     * External items (a video handed in from outside the app) are stepped
     * over: they have no entry or episode row to launch with, and reopening
     * one needs the request its notification carries, not a queue row.
     */
    private fun List<QueueItem>.firstResumePoint(): ResumePoint? {
        return firstNotNullOfOrNull { item ->
            val animeId = item.animeId
            val episodeId = item.episodeId
            if (animeId != null && episodeId != null) ResumePoint(animeId, episodeId) else null
        }
    }
}
// <-- AM (PLAYBACK_QUEUE)
