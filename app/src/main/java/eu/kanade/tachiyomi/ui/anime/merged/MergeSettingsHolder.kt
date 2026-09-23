// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedMangaHolder; its download and chapter-updates
// buttons are replaced by the season spinner.
package eu.kanade.tachiyomi.ui.anime.merged

import android.content.Context
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import aniyomi.domain.season.model.EntrySeason
import coil3.load
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.MergeSettingsItemBinding
import eu.kanade.tachiyomi.util.system.dpToPx
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.animiru.AMMR

class MergeSettingsHolder(view: View, val adapter: MergeSettingsAdapter) : FlexibleViewHolder(view, adapter) {

    var binding = MergeSettingsItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.cover.setOnClickListener {
            adapter.itemListener.onOpenEntryClick(bindingAdapterPosition)
        }
        binding.remove.setOnClickListener {
            adapter.itemListener.onDeleteClick(bindingAdapterPosition)
        }
        setHandleAlpha(adapter.isPriorityOrder)
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.itemListener.onItemReleased(position)
    }

    fun bind(item: MergeSettingsItem) {
        binding.cover.load(item.source.anime) {
            transformations(RoundedCornersTransformation(4.dpToPx.toFloat()))
        }
        binding.title.text = item.source.sourceName
        binding.subtitle.text = item.source.anime.title
        binding.remove.contentDescription = itemView.context.stringResource(
            AMMR.strings.am_merge_settings_remove_source,
        )
        bindSeasonPicker()
        binding.holder.setCardBackgroundColor(adapter.colorScheme.surfaceElevation)
        binding.remove.imageTintList = adapter.colorScheme.imageButtonTintList
    }

    /**
     * The season picker: the current season, and a popup of the others plus an
     * "Edit seasons" entry that opens the season manager.
     *
     * Deliberately not a Spinner (which Komikku uses for its header): a Spinner
     * draws its arrow over the child view and measures that child through
     * AbsSpinner, which clipped the label in this width-constrained row.
     */
    private fun bindSeasonPicker() {
        val context = itemView.context
        val seasons = adapter.seasons
        val selected = adapter.itemListener.seasonOf(bindingAdapterPosition)
        binding.seasonButton.text = seasons.indexOfFirst { it.number == selected }
            .takeIf { it != -1 }
            ?.let { seasonLabel(context, it, seasons) }
            .orEmpty()
        binding.seasonButton.setTextColor(adapter.colorScheme.textColor)
        binding.seasonButton.compoundDrawableTintList = adapter.colorScheme.imageButtonTintList
        binding.seasonButton.setOnClickListener { view ->
            val popup = PopupMenu(context, view, Gravity.NO_GRAVITY, R.attr.actionOverflowMenuStyle, 0)
            seasons.forEachIndexed { index, _ ->
                popup.menu.add(Menu.NONE, index, index, seasonLabel(context, index, seasons))
            }
            popup.menu.add(
                Menu.NONE,
                seasons.size,
                seasons.size,
                context.stringResource(AMMR.strings.am_action_edit_seasons),
            )
            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == seasons.size) {
                    adapter.itemListener.onEditSeasonsClick()
                } else {
                    seasons.getOrNull(item.itemId)?.let {
                        adapter.itemListener.onSeasonSelected(bindingAdapterPosition, it.number)
                        binding.seasonButton.text = seasonLabel(context, item.itemId, seasons)
                    }
                }
                true
            }
            popup.show()
        }
    }

    private fun seasonLabel(context: Context, index: Int, seasons: List<EntrySeason>): String {
        return seasons[index].name ?: context.stringResource(AMMR.strings.am_merge_season_number, index + 1)
    }

    fun setHandleAlpha(isPriorityOrder: Boolean) {
        binding.reorder.alpha = when (isPriorityOrder) {
            true -> 1F
            false -> 0.5F
        }
    }
}
// <-- AM (MERGE_SETTINGS)
