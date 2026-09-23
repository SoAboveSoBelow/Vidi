// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedMangaAdapter.
package eu.kanade.tachiyomi.ui.anime.merged

import aniyomi.domain.season.model.EntrySeason
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme

/**
 * Adapter holding a merge's sources.
 *
 * @param isPriorityOrder whether deduplication is by priority, which is the
 * only thing priority decides - so the drag handle is enabled only then, as in
 * Komikku.
 */
class MergeSettingsAdapter(
    listener: MergeSettingsState,
    var isPriorityOrder: Boolean,
    val colorScheme: AndroidViewColorScheme,
    // AM: the season picker replaces Komikku's download/updates toggles, so rows need the seasons.
    var seasons: List<EntrySeason>,
) : FlexibleAdapter<MergeSettingsItem>(null, listener, true),
    MergeSettingsHeaderAdapter.SortingListener {

    val itemListener: ItemListener = listener

    interface ItemListener {
        fun onItemReleased(position: Int)
        fun onDeleteClick(position: Int)
        fun onOpenEntryClick(position: Int)

        // AM -->
        fun onSeasonSelected(position: Int, seasonNumber: Long)
        fun onEditSeasonsClick()
        fun seasonOf(position: Int): Long?
        // <-- AM
    }

    override fun onSetPrioritySort(isPriorityOrder: Boolean) {
        isHandleDragEnabled = isPriorityOrder
        this.isPriorityOrder = isPriorityOrder
        allBoundViewHolders.onEach { holder ->
            if (holder is MergeSettingsHolder) {
                holder.setHandleAlpha(isPriorityOrder)
            }
        }
    }
}
// <-- AM (MERGE_SETTINGS)
