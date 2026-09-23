// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedMangaItem.
package eu.kanade.tachiyomi.ui.anime.merged

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.MergeSettingsItemBinding
import eu.kanade.tachiyomi.ui.anime.AnimeViewModel

class MergeSettingsItem(val source: AnimeViewModel.MergeSource) : AbstractFlexibleItem<MergeSettingsHolder>() {

    override fun getLayoutRes(): Int = R.layout.merge_settings_item

    override fun isDraggable(): Boolean = true

    lateinit var binding: MergeSettingsItemBinding

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): MergeSettingsHolder {
        binding = MergeSettingsItemBinding.bind(view)
        return MergeSettingsHolder(binding.root, adapter as MergeSettingsAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>?,
        holder: MergeSettingsHolder,
        position: Int,
        payloads: MutableList<Any>?,
    ) {
        holder.bind(this)
    }

    override fun hashCode(): Int = source.anime.id.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is MergeSettingsItem) return source.anime.id == other.source.anime.id
        return false
    }
}
// <-- AM (MERGE_SETTINGS)
