// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedMangaHolder; its download and chapter-updates
// buttons are replaced by the season spinner.
package eu.kanade.tachiyomi.ui.anime.merged

import android.content.Context
import android.view.View
import aniyomi.domain.season.model.EntrySeason
import coil3.load
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import eu.davidea.viewholders.FlexibleViewHolder
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

    // AM (NAMED_SEASONS) -->
    /**
     * Re-runs just the picker on an already-bound row - see
     * [MergeSettingsAdapter.updateSeasons]. Not [bind], which would re-issue the
     * cover load.
     */
    fun rebindSeasonPicker() = bindSeasonPicker()
    // <-- AM (NAMED_SEASONS)

    /**
     * The season picker: a button showing the row's current season that opens
     * the shared season dialog (ChangeSeasonDialog), the same one reorder mode
     * uses. It was a PopupMenu; the dialog reads better for a list that also
     * offers creating a season and editing them, and means one picker to
     * maintain rather than two.
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
        binding.seasonButton.setOnClickListener {
            adapter.itemListener.onSeasonPickerClick(bindingAdapterPosition)
        }
    }

    private fun seasonLabel(context: Context, index: Int, seasons: List<EntrySeason>): String {
        return seasons[index].name ?: context.stringResource(AMMR.strings.am_merge_season_number, index + 1)
    }
}
// <-- AM (MERGE_SETTINGS)
