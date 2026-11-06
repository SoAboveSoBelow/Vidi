// AM (BROWSE_SELECTION) -->
package eu.kanade.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.interactor.GetVisibleCategories
import tachiyomi.domain.category.model.Category

/**
 * The checkbox states to open a category dialog with for a set of entries.
 *
 * This was two private helpers on LibraryViewModel (getCommonCategories and
 * getMixCategories) plus the mapping that turned them into checkbox states.
 * Browse needs the same thing for its own selection, so it lives here and
 * both callers use it rather than each deciding separately what a partially
 * shared category looks like.
 *
 * The three states are what [ChangeCategoryDialog] already renders and what
 * asToggleableState maps to a checkbox:
 *
 *  - every entry is in the category: [CheckboxState.State.Checked], a tick.
 *  - some but not all are: [CheckboxState.TriState.Exclude], the dash.
 *  - none are: [CheckboxState.State.None], an empty box.
 *
 * Exclude for the mixed case reads oddly in isolation but is deliberate and
 * predates this: the dialog returns Include and None as its add and remove
 * lists, so a category left in the Exclude state is left untouched for every
 * entry - which is the only coherent meaning of "leave the mixture as it is".
 * Pressing it cycles Exclude -> None -> Include, so the dash is the state the
 * user leaves alone rather than one they have to resolve.
 */
@Inject
class GetCategorySelection(
    private val getVisibleCategories: GetVisibleCategories,
) {

    /**
     * [categories] is the list to offer, already filtered by the caller - the
     * system Default category (id 0) is excluded by both callers because it
     * behaves differently from a real one.
     */
    suspend fun await(animeIds: List<Long>, categories: List<Category>): List<CheckboxState<Category>> {
        if (animeIds.isEmpty()) return categories.map { CheckboxState.State.None(it) }

        val perAnime = animeIds.map { getVisibleCategories.await(it).toSet() }
        val common = perAnime.reduce { acc, set -> acc.intersect(set) }
        val mixed = perAnime.flatten().toSet().subtract(common)

        return categories.map {
            when (it) {
                in common -> CheckboxState.State.Checked(it)
                in mixed -> CheckboxState.TriState.Exclude(it)
                else -> CheckboxState.State.None(it)
            }
        }
    }
}
// <-- AM (BROWSE_SELECTION)
