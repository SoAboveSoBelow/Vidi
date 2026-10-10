// AM (EPISODE_TAGS) -->
package animiru.domain.episode.interactor

import animiru.domain.episode.repository.EpisodeTagRepository
import dev.zacsweers.metro.Inject

/**
 * The edits the per-episode tag popout offers.
 *
 * Every one of them writes or drops rows for a single episode, except
 * [deleteFromEntry], which is the entry's aggregated chip being deleted and so
 * clears the tag from all of that entry's episodes at once.
 */
@Inject
class SetEpisodeTags(
    private val episodeTagRepository: EpisodeTagRepository,
) {

    /**
     * Sets whether one episode carries [tags], against what it would carry with
     * no rows at all ([default]).
     *
     * A row is written only where the answer differs from that default, and the
     * row is DROPPED where it agrees - so putting a tag back where it started
     * leaves the episode with no row for it, exactly as if it had never been
     * touched. Storing the agreeing row instead was what left a returned tag
     * looking edited, and what made "delete" offer to remove a tag that had only
     * been moved.
     */
    suspend fun set(
        episodeId: Long,
        tags: Collection<String>,
        carried: Boolean,
        default: Collection<String>,
    ) {
        val trimmed = tags.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return
        val defaults = default.mapTo(HashSet()) { it.lowercase() }
        val (agreeing, differing) = trimmed.partition { (it.lowercase() in defaults) == carried }
        if (differing.isNotEmpty()) {
            episodeTagRepository.upsertAll(episodeId, differing.associateWith { carried })
        }
        if (agreeing.isNotEmpty()) {
            episodeTagRepository.deleteAll(episodeId, agreeing)
        }
    }

    /** Clears the user's rows for [tags] on one episode, whichever way they went. */
    suspend fun reset(episodeId: Long, tags: Collection<String>) {
        if (tags.isEmpty()) return
        episodeTagRepository.deleteAll(episodeId, tags)
    }

    /**
     * Deletes [tag] from every episode of [animeId] - the entry's aggregated
     * chip. Its episodes go back to inheriting the tag if the entry still has
     * one by that name, and lose it outright if it was only ever theirs.
     */
    suspend fun deleteFromEntry(animeId: Long, tag: String) {
        episodeTagRepository.deleteByAnimeIdAndTag(animeId, tag)
    }
}
// <-- AM (EPISODE_TAGS)
