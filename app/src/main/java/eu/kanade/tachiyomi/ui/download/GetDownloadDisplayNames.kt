// AM (EPISODE_NAMES) -->
package eu.kanade.tachiyomi.ui.download

import aniyomi.domain.order.interactor.GetEpisodeOrder
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.repository.AnimeRepository

/**
 * Episode titles for the download queue and hold lists, as the rest of the app
 * resolves them.
 *
 * The rule - a custom name wins, a source offering exactly one episode names that
 * episode after itself, otherwise the episode's own name - lives in
 * [GetEpisodeOrder] and is consumed here rather than repeated: the entry screen and
 * the player's playlist read the same map, so a movie that reads as its own title
 * there cannot read as "Movie" in the queue.
 *
 * Resolved per entry rather than per episode, because that is the unit the rule is
 * about: the names come back for every episode of the entry, which is also every
 * episode of it the list could hold. Callers resolve on a change to the set of
 * entries, not on a progress tick.
 */
@Inject
class GetDownloadDisplayNames(
    private val animeRepository: AnimeRepository,
    private val getEpisodeOrder: GetEpisodeOrder,
) {

    /**
     * Display names by episode id for every episode of [animeIds].
     *
     * An entry that cannot be read is skipped rather than failing the lot: a missing
     * name falls back to the episode's own, which is what the list already had.
     */
    suspend fun await(animeIds: Set<Long>): Map<Long, String> {
        if (animeIds.isEmpty()) return emptyMap()

        val names = mutableMapOf<Long, String>()
        animeIds.forEach { animeId ->
            try {
                val anime = animeRepository.getAnimeById(animeId)
                names += getEpisodeOrder.awaitResolved(anime).displayNameByEpisodeId
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not resolve episode names for $animeId" }
            }
        }
        return names
    }
}
// <-- AM (EPISODE_NAMES)
