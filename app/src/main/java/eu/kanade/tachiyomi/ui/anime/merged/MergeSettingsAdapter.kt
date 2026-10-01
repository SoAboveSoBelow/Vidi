// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedMangaAdapter.
package eu.kanade.tachiyomi.ui.anime.merged

import aniyomi.domain.season.model.EntrySeason
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme

/**
 * Adapter holding a merge's sources.
 *
 * AM: reordering is always available, unlike Komikku's, which enables the drag
 * handle only under priority dedupe. Merge order decides more here than which
 * duplicate wins: it is also the block order of the sources' episodes (see
 * GetEpisodeOrder.defaultSortKey), so it is worth setting whatever the dedupe
 * mode is.
 */
class MergeSettingsAdapter(
    listener: MergeSettingsState,
    val colorScheme: AndroidViewColorScheme,
    // AM: the season picker replaces Komikku's download/updates toggles, so rows need the seasons.
    var seasons: List<EntrySeason>,
) : FlexibleAdapter<MergeSettingsItem>(null, listener, true) {

    val itemListener: ItemListener = listener

    interface ItemListener {
        fun onItemReleased(position: Int)
        fun onDeleteClick(position: Int)
        fun onOpenEntryClick(position: Int)

        // AM -->
        /** Opens the shared season dialog for this row. */
        fun onSeasonPickerClick(position: Int)
        fun seasonOf(position: Int): Long?
        // <-- AM
    }

    // AM (NAMED_SEASONS) -->
    /**
     * Seasons can appear while the dialog is open - added in the season manager,
     * or staged by a row's own quick-add - so already-bound rows have to be
     * repoked. Done by walking bound holders rather than notifyItemRangeChanged:
     * it cannot collide with a RecyclerView layout pass, and a merge has a
     * handful of rows.
     */
    fun updateSeasons(seasons: List<EntrySeason>) {
        this.seasons = seasons
        rebindSeasonPickers()
    }

    fun rebindSeasonPickers() {
        allBoundViewHolders.forEach { if (it is MergeSettingsHolder) it.rebindSeasonPicker() }
    }
    // <-- AM (NAMED_SEASONS)

}
// <-- AM (MERGE_SETTINGS)
