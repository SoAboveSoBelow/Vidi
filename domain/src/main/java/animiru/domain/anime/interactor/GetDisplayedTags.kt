// AM (TAG_LIMIT) -->
package animiru.domain.anime.interactor

import animiru.domain.anime.model.DisplayedTags
import animiru.domain.anime.repository.TagVisibilityRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class GetDisplayedTags(
    private val tagVisibilityRepository: TagVisibilityRepository,
    private val libraryPreferences: LibraryPreferences,
) {

    /**
     * The split for [animeId], reacting to all three of its inputs: the entry's
     * own tags changing (a merge refresh re-ranks them), the user moving a tag,
     * and the global cap changing in settings.
     */
    fun subscribe(animeId: Long, tags: Flow<List<String>?>): Flow<DisplayedTags> {
        return combine(
            tags,
            tagVisibilityRepository.getByAnimeIdAsFlow(animeId),
            libraryPreferences.maxTagsShown.changes(),
        ) { entryTags, overrides, maxShown ->
            DisplayedTags.from(
                tags = entryTags,
                // The sentinel stops here: the split takes a count or nothing.
                maxShown = maxShown.takeIf { it < LibraryPreferences.UNCAPPED_TAG_LIMIT },
                overrides = overrides.associate { it.tag.lowercase() to it.visible },
            )
        }
    }
}
// <-- AM (TAG_LIMIT)
