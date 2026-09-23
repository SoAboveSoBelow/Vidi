// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedSettingsHeaderAdapter. Komikku's three
// dedupe modes map onto DedupeMode; "entry info" is the same idea.
package eu.kanade.tachiyomi.ui.anime.merged

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import aniyomi.domain.merge.model.DedupeMode
import eu.kanade.presentation.components.SpinnerAdapter
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme
import eu.kanade.tachiyomi.databinding.MergeSettingsHeaderBinding
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.animiru.AMMR

class MergeSettingsHeaderAdapter(
    private val state: MergeSettingsState,
    adapter: MergeSettingsAdapter,
    private val colorScheme: AndroidViewColorScheme,
) : RecyclerView.Adapter<MergeSettingsHeaderAdapter.HeaderViewHolder>() {

    private lateinit var binding: MergeSettingsHeaderBinding

    val sortingListener: SortingListener = adapter

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        binding = MergeSettingsHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HeaderViewHolder(binding.root)
    }

    override fun getItemCount(): Int = 1

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind()
    }

    inner class HeaderViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            val context = view.context
            binding.dedupeSwitchLabel.text = context.stringResource(AMMR.strings.am_merge_settings_allow_dedupe)
            binding.dedupeModeLabel.text = context.stringResource(AMMR.strings.am_merge_settings_dedupe_mode)
            binding.entryInfoLabel.text = context.stringResource(AMMR.strings.am_merge_settings_info_entry)
            binding.dedupeSwitchLabel.setTextColor(colorScheme.textColor)
            binding.dedupeModeLabel.setTextColor(colorScheme.textColor)
            binding.entryInfoLabel.setTextColor(colorScheme.textColor)

            val modes = listOf(DedupeMode.PRIORITY, DedupeMode.MOST_EPISODES, DedupeMode.HIGHEST_EPISODE)
            val dedupeAdapter = SpinnerAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    context.stringResource(AMMR.strings.am_merge_settings_dedupe_priority),
                    context.stringResource(AMMR.strings.am_merge_settings_dedupe_most),
                    context.stringResource(AMMR.strings.am_merge_settings_dedupe_highest),
                ),
                colorScheme,
            )
            binding.dedupeModeSpinner.adapter = dedupeAdapter
            binding.dedupeModeSpinner.setSelection(modes.indexOf(state.dedupeMode).coerceAtLeast(0))
            binding.dedupeModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (binding.dedupeSwitch.isChecked) {
                        state.dedupeMode = modes.getOrElse(position) { DedupeMode.PRIORITY }
                        sortingListener.onSetPrioritySort(state.canMove())
                    }
                    // Set the selected item's background to transparent.
                    if (view != null) (view as TextView).setBackgroundColor(Color.TRANSPARENT)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    state.dedupeMode = DedupeMode.OFF
                }
            }

            binding.dedupeSwitch.isChecked = state.dedupeMode != DedupeMode.OFF
            binding.dedupeSwitch.trackTintList = colorScheme.trackTintList
            binding.dedupeSwitch.thumbTintList = colorScheme.thumbTintList
            binding.dedupeModeSpinner.isEnabled = binding.dedupeSwitch.isChecked
            binding.dedupeSwitch.setOnCheckedChangeListener { _, isChecked ->
                state.dedupeMode = if (isChecked) {
                    modes.getOrElse(binding.dedupeModeSpinner.selectedItemPosition) { DedupeMode.PRIORITY }
                } else {
                    DedupeMode.OFF
                }
                binding.dedupeModeSpinner.isEnabled = isChecked
                sortingListener.onSetPrioritySort(state.canMove())
            }

            val sources = state.sources
            val infoAdapter = SpinnerAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                sources.map { "${it.sourceName} ${it.anime.title}" },
                colorScheme,
            )
            binding.entryInfoSpinner.adapter = infoAdapter
            sources.indexOfFirst { it.anime.id == state.infoAnimeId }.let {
                binding.entryInfoSpinner.setSelection(if (it != -1) it else 0)
            }
            binding.entryInfoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    state.infoAnimeId = sources.getOrNull(position)?.anime?.id
                    if (view != null) (view as TextView).setBackgroundColor(Color.TRANSPARENT)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    interface SortingListener {
        fun onSetPrioritySort(isPriorityOrder: Boolean)
    }
}
// <-- AM (MERGE_SETTINGS)
