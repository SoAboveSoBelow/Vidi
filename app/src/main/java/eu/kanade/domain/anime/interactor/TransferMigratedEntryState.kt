// AM (MIGRATION_CARRY_OVER) -->
package eu.kanade.domain.anime.interactor

import animiru.domain.anime.repository.TagVisibilityRepository
import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.interactor.GetCustomAnimeInfo
import tachiyomi.domain.anime.interactor.SetCustomAnimeInfo
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.CustomAnimeInfo

/**
 * Carries the hand-authored state of a migrated entry onto its replacement.
 *
 * Migration's own flags cover the things that are stored on the anime row or in
 * a cache keyed by its id - cover, background, notes, categories, tracks. The
 * details the user typed, and the tag work they did, live elsewhere and were
 * being dropped on the floor: every custom title, author, description and tag
 * edit was lost the moment an entry was migrated, with nothing to say so.
 *
 * None of this is per-episode. Episode-keyed state (watch position, custom
 * episode names, per-episode tags, custom order) is deliberately out of scope:
 * the replacement's episodes are different rows with no dependable key to match
 * them on, so a mapping would be guesswork.
 */
@Inject
class TransferMigratedEntryState(
    private val getCustomAnimeInfo: GetCustomAnimeInfo,
    private val setCustomAnimeInfo: SetCustomAnimeInfo,
    private val tagVisibilityRepository: TagVisibilityRepository,
) {

    suspend fun await(current: Anime, target: Anime) {
        if (current.id == target.id) return
        transferCustomDetails(current, target)
        transferTagVisibility(current, target)
    }

    /**
     * The typed-in details, plus the tags the entry showed that its replacement
     * does not.
     *
     * [CustomAnimeInfo.genre] is not carried as a snapshot: it replaces the
     * entry's tags outright, which would freeze the replacement at the old
     * source's list and hide everything the new source brings in. Instead the
     * tags the user would lose - whatever the old entry effectively showed that
     * the new source does not supply - are added as [CustomAnimeInfo.addedGenre],
     * which is unioned on top and so survives refreshes.
     *
     * The blacklist travels as-is: throwing a tag away is a statement about the
     * tag, not about the source it came from, so it should keep applying if the
     * new source supplies the same one.
     */
    private suspend fun transferCustomDetails(current: Anime, target: Anime) {
        val custom = getCustomAnimeInfo.get(current.id)
        val blacklisted = custom?.blacklistedGenre.orEmpty()
        val blacklistedKeys = blacklisted.mapTo(HashSet(), String::lowercase)

        // What the old entry actually showed: its own tags, or the snapshot if
        // one replaced them, plus the user's additions, minus the thrown-away.
        val shown = ((custom?.genre ?: current.genre.orEmpty()) + custom?.addedGenre.orEmpty())
            .distinctBy(String::lowercase)
            .filterNot { it.lowercase() in blacklistedKeys }

        val targetKeys = target.genre.orEmpty().mapTo(HashSet(), String::lowercase)
        val missing = shown.filterNot { it.lowercase() in targetKeys }

        val info = CustomAnimeInfo(
            id = target.id,
            title = custom?.title,
            author = custom?.author,
            artist = custom?.artist,
            description = custom?.description,
            addedGenre = missing.takeIf { it.isNotEmpty() },
            blacklistedGenre = blacklisted.takeIf { it.isNotEmpty() },
            status = custom?.status,
        )
        // An entry nobody edited, whose tags the new source already covers,
        // produces an empty record - writing it would create a custom-info row
        // where there was none.
        if (info != CustomAnimeInfo(id = target.id, title = null)) {
            setCustomAnimeInfo.set(info)
        }
    }

    /**
     * Tag pins and the tag lock.
     *
     * Both are keyed by tag text rather than by anything source-specific, so
     * they transfer verbatim; a pin on a tag the new source does not have is
     * harmless, and the tag may well come back through [transferCustomDetails]'
     * added tags anyway.
     */
    private suspend fun transferTagVisibility(current: Anime, target: Anime) {
        val pins = tagVisibilityRepository.getByAnimeId(current.id)
        if (pins.isNotEmpty()) {
            tagVisibilityRepository.upsertAll(target.id, pins.associate { it.tag to it.visible })
        }
    }
}
// <-- AM (MIGRATION_CARRY_OVER)
