// AM (TAG_LIMIT) -->
package animiru.domain.anime.interactor

import animiru.domain.anime.repository.TagVisibilityRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.Anime

/**
 * The four edits the tag popout offers.
 *
 * Moving a tag stores which side it belongs on. Clearing that row is the only way
 * back to the cap for one tag, as [reset] is for a whole entry.
 *
 * AM (TAG_LOCKS): keeping the whole list where it is is no longer one of these -
 * it is the entry's lock, which writes nothing per tag. See anime_tag_lock.sq.
 */
@Inject
class SetTagVisibility(
    private val tagVisibilityRepository: TagVisibilityRepository,
) {

    /**
     * Moves [tags] to Visible or Hidden and pins them there, in one transaction.
     *
     * A choice is always stored, even where it happens to agree with what the
     * global cap does today. The user said "show this one" or "hide this one", and
     * that has to keep meaning the same thing after the cap moves - storing
     * nothing because it currently agrees would quietly hand the tag back to the
     * setting, so it would move on its own later. Sparseness comes from the user
     * not having touched a tag, not from the row being redundant right now.
     */
    suspend fun awaitAll(anime: Anime, tags: Collection<String>, visible: Boolean) {
        val known = known(anime, tags)
        if (known.isEmpty()) return
        tagVisibilityRepository.upsertAll(anime.id, known.associateWith { visible })
    }

    /**
     * Pins a tag that has just been created on [animeId], without checking it
     * against the entry's tag list.
     *
     * [awaitAll] cannot do this. Its check reads `Anime.genre`, and an Anime caches
     * its custom info when it is constructed, so the instance the caller is holding
     * predates the write that added the tag: the check would find the tag missing
     * and drop the pin, leaving a new tag unpinned and, on an entry already at its
     * cap, invisible. The caller has just written the tag, so there is nothing to
     * verify.
     */
    suspend fun pinNew(animeId: Long, tag: String, visible: Boolean) {
        tagVisibilityRepository.upsertAll(animeId, mapOf(tag to visible))
    }

    /** Drops the pins on [tags], returning just those to the global cap. */
    suspend fun unpinAll(anime: Anime, tags: Collection<String>) {
        val known = known(anime, tags)
        if (known.isEmpty()) return
        tagVisibilityRepository.deleteAll(anime.id, known)
    }

    /**
     * Drops every pinned choice for [animeId], returning the whole entry to the
     * global cap.
     */
    suspend fun reset(animeId: Long) {
        tagVisibilityRepository.deleteByAnimeId(animeId)
    }

    /**
     * Tags the entry actually has. A row for one it does not would never match
     * anything and only invites the question of why it is there.
     */
    private fun known(anime: Anime, tags: Collection<String>): List<String> {
        if (tags.isEmpty()) return emptyList()
        val entryTags = anime.genre.orEmpty()
        return tags.filter { tag -> entryTags.any { it.equals(tag, ignoreCase = true) } }
    }
}
// <-- AM (TAG_LIMIT)
