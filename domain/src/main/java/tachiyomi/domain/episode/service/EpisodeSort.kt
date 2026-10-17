package tachiyomi.domain.episode.service

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode

/**
 * [displayNameOf] is the name the list actually shows for an episode, which
 * alphabetical order has to be computed against - sorting by a string the user
 * cannot see reads as no sort at all.
 *
 * AM (EPISODE_NAMES): the two diverge on a merged entry, where GetEpisodeOrder
 * substitutes a custom name, or the source's own title for a source
 * contributing a single episode. Defaults to the episode's own name, so an
 * entry with no substitutions - and every caller that has no resolved names to
 * hand - behaves exactly as before.
 */
fun getEpisodeSort(
    anime: Anime,
    sortDescending: Boolean = anime.sortDescending(),
    displayNameOf: (Episode) -> String = { it.name },
): (
    Episode,
    Episode,
) -> Int {
    return when (anime.sorting) {
        Anime.EPISODE_SORTING_SOURCE -> when (sortDescending) {
            true -> { e1, e2 -> e1.sourceOrder.compareTo(e2.sourceOrder) }
            false -> { e1, e2 -> e2.sourceOrder.compareTo(e1.sourceOrder) }
        }
        Anime.EPISODE_SORTING_NUMBER -> when (sortDescending) {
            true -> { e1, e2 -> e2.episodeNumber.compareTo(e1.episodeNumber) }
            false -> { e1, e2 -> e1.episodeNumber.compareTo(e2.episodeNumber) }
        }
        Anime.EPISODE_SORTING_UPLOAD_DATE -> when (sortDescending) {
            true -> { e1, e2 -> e2.dateUpload.compareTo(e1.dateUpload) }
            false -> { e1, e2 -> e1.dateUpload.compareTo(e2.dateUpload) }
        }
        Anime.EPISODE_SORTING_ALPHABET -> when (sortDescending) {
            true -> { e1, e2 -> displayNameOf(e2).compareToWithCollator(displayNameOf(e1)) }
            false -> { e1, e2 -> displayNameOf(e1).compareToWithCollator(displayNameOf(e2)) }
        }
        else -> throw NotImplementedError("Invalid episode sorting method: ${anime.sorting}")
    }
}
