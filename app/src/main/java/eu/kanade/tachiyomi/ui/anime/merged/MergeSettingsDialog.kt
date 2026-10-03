// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedSettingsDialog: a Compose dialog hosting the
// view-based list, with edits staged until Save.
package eu.kanade.tachiyomi.ui.anime.merged

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import aniyomi.domain.merge.model.DedupeMode
import aniyomi.domain.merge.model.RemovedMergeEpisode
import aniyomi.domain.season.model.EntrySeason
import aniyomi.domain.season.model.isProvisionalSeason
import aniyomi.domain.season.model.provisionalSeasonNumber
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.presentation.anime.season.ChangeSeasonDialog
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme
import eu.kanade.tachiyomi.databinding.MergeSettingsDialogBinding
import eu.kanade.tachiyomi.ui.anime.AnimeViewModel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Stable
class MergeSettingsState(
    private val context: Context,
    private val onDeleteClick: (Anime) -> Unit,
    private val onDismissRequest: () -> Unit,
    private val onPositiveClick: (MergeSettingsResult) -> Unit,
    private val onOpenEntryClick: (Anime) -> Unit,
    private val onEditSeasonsClick: () -> Unit,
) : MergeSettingsAdapter.ItemListener {

    var sources: List<AnimeViewModel.MergeSource> by mutableStateOf(emptyList())
    var seasons: List<EntrySeason> by mutableStateOf(emptyList())
    var dedupeMode: DedupeMode by mutableStateOf(DedupeMode.OFF)
    var infoAnimeId: Long? by mutableStateOf(null)
    var adapter: MergeSettingsAdapter? by mutableStateOf(null)
    var headerAdapter: MergeSettingsHeaderAdapter? by mutableStateOf(null)

    private var seasonByChild: Map<Long, Long> = emptyMap()

    // AM (NAMED_SEASONS) -->
    /**
     * Seasons the quick-add staged for creation, in the order they were staged,
     * carrying [provisionalSeasonNumber]s until Save turns them into real rows.
     * Staged rather than created on tap so Cancel discards them along with every
     * other edit in this dialog.
     */
    private var pendingSeasons: List<EntrySeason> by mutableStateOf(emptyList())

    /** The real seasons plus the staged ones - what the picker shows. */
    val shownSeasons: List<EntrySeason> get() = seasons + pendingSeasons

    /** The season [childId] is currently pointed at, staged or saved. */
    fun stagedSeasonOf(childId: Long): Long? = seasonByChild[childId]

    /** The source [childId] belongs to, for the picker's title. */
    fun sourceNameOf(childId: Long): String? = sources.firstOrNull { it.anime.id == childId }?.sourceName
    // <-- AM (NAMED_SEASONS)

    // AM: reordering is always available - merge order is the block order of
    // the sources' episodes, not just the dedupe tiebreak. See
    // GetEpisodeOrder.defaultSortKey.

    fun onViewCreated(
        context: Context,
        binding: MergeSettingsDialogBinding,
        dialog: AnimeViewModel.Dialog.MergeSettings,
        colorScheme: AndroidViewColorScheme,
    ) {
        sources = dialog.sources
        seasons = dialog.seasons
        dedupeMode = dialog.dedupeMode
        infoAnimeId = dialog.infoAnimeId
        seasonByChild = dialog.sources.associate { it.anime.id to it.seasonNumber }

        val adapter = MergeSettingsAdapter(this, colorScheme, shownSeasons)
        this.adapter = adapter
        headerAdapter = MergeSettingsHeaderAdapter(this, colorScheme)
        binding.recycler.adapter = ConcatAdapter(headerAdapter, adapter)
        binding.recycler.layoutManager = LinearLayoutManager(context)
        adapter.isHandleDragEnabled = true
        adapter.updateDataSet(sources.map { MergeSettingsItem(it) })
    }

    override fun onItemReleased(position: Int) = Unit

    override fun onOpenEntryClick(position: Int) {
        val source = adapter?.currentItems?.getOrNull(position)?.source ?: return
        onOpenEntryClick(source.anime)
    }

    override fun onDeleteClick(position: Int) {
        val source = adapter?.currentItems?.getOrNull(position)?.source ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(context.stringResource(AMMR.strings.am_merge_settings_remove_source))
            .setMessage(context.stringResource(AMMR.strings.am_merge_settings_remove_confirm, source.anime.title))
            .setPositiveButton(context.stringResource(MR.strings.action_ok)) { _, _ ->
                onDeleteClick(source.anime)
                onDismissRequest()
            }
            .setNegativeButton(context.stringResource(MR.strings.action_cancel), null)
            .show()
    }

    // AM (NAMED_SEASONS) -->
    /**
     * The row whose season dialog is open, by child anime id, or null for none.
     * The dialog is Compose while the row is a RecyclerView item, so the row
     * asks for it and the composable renders it from here.
     */
    var seasonPickerChildId: Long? by mutableStateOf(null)
        private set

    override fun onSeasonPickerClick(position: Int) {
        seasonPickerChildId = adapter?.currentItems?.getOrNull(position)?.source?.anime?.id
    }

    fun dismissSeasonPicker() {
        seasonPickerChildId = null
    }

    /** Stages [seasonNumber] for [childId] - written by Save, like every edit here. */
    fun onSeasonPicked(childId: Long, seasonNumber: Long) {
        seasonByChild = seasonByChild + (childId to seasonNumber)
        dropUnusedPendingSeasons()
        // The row shows its season on a button, so it has to be rebound even
        // when the season list itself did not change.
        adapter?.updateSeasons(shownSeasons)
    }

    /**
     * Stages the offered next season for [childId]. It gets a provisional
     * number until Save creates it, so Cancel discards it with everything else.
     */
    fun onNextSeasonPicked(childId: Long) {
        val number = provisionalSeasonNumber(pendingSeasons.size)
        pendingSeasons = pendingSeasons + EntrySeason(number = number, name = null, sortOrder = null)
        onSeasonPicked(childId, number)
    }
    // <-- AM (NAMED_SEASONS)

    // AM (NAMED_SEASONS) -->
    /**
     * Forgets the staged seasons no source points at any more - the user picked
     * the offered season for a row and then picked something else for it, so the
     * season was never wanted. Save already skips creating them; this is what
     * takes them out of the pickers, which would otherwise keep listing a
     * season that is not going to exist and offer the one after it.
     *
     * The survivors are renumbered so provisional numbers stay -1, -2, ... in
     * staged order: the pickers label them by position, and Save creates them in
     * that order.
     */
    private fun dropUnusedPendingSeasons() {
        if (pendingSeasons.isEmpty()) return
        val used = seasonByChild.values.filterTo(HashSet()) { it.isProvisionalSeason() }
        val kept = pendingSeasons.filter { it.number in used }
        if (kept.size == pendingSeasons.size) return
        val renumbered = kept.mapIndexed { index, season ->
            season.copy(number = provisionalSeasonNumber(index))
        }
        val remapped = kept.map { it.number }.zip(renumbered.map { it.number }).toMap()
        pendingSeasons = renumbered
        seasonByChild = seasonByChild.mapValues { (_, number) -> remapped[number] ?: number }
        adapter?.updateSeasons(shownSeasons)
    }

    /** Seasons added in the season manager while this dialog stayed open. */
    fun onSeasonsRefreshed(refreshed: List<EntrySeason>) {
        if (refreshed == seasons) return
        seasons = refreshed
        adapter?.updateSeasons(shownSeasons)
    }
    // <-- AM (NAMED_SEASONS)

    override fun seasonOf(position: Int): Long? {
        val source = adapter?.currentItems?.getOrNull(position)?.source ?: return null
        return seasonByChild[source.anime.id]
    }

    fun onPositiveButtonClick() {
        val order = adapter?.currentItems?.map { it.source.anime.id } ?: sources.map { it.anime.id }
        onPositiveClick(
            MergeSettingsResult(
                order = order,
                seasonByChild = seasonByChild,
                dedupeMode = dedupeMode,
                infoAnimeId = infoAnimeId,
            ),
        )
        onDismissRequest()
    }
}

/** What Save applies. */
data class MergeSettingsResult(
    val order: List<Long>,
    val seasonByChild: Map<Long, Long>,
    val dedupeMode: DedupeMode,
    val infoAnimeId: Long?,
)

@Composable
fun MergeSettingsDialog(
    dialog: AnimeViewModel.Dialog.MergeSettings,
    onDismissRequest: () -> Unit,
    onDeleteClick: (Anime) -> Unit,
    onPositiveClick: (MergeSettingsResult) -> Unit,
    onOpenEntryClick: (Anime) -> Unit,
    onEditSeasonsClick: () -> Unit,
    // AM (MERGE_EPISODE_EXCLUSION) -->
    onRestoreEpisodeClick: (Long) -> Unit,
    // <-- AM (MERGE_EPISODE_EXCLUSION)
) {
    val colorScheme = AndroidViewColorScheme(MaterialTheme.colorScheme)
    val context = LocalContext.current
    val state = remember {
        MergeSettingsState(
            context,
            onDeleteClick,
            onDismissRequest,
            onPositiveClick,
            onOpenEntryClick,
            onEditSeasonsClick,
        )
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = state::onPositiveButtonClick) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                AndroidView(
                    factory = { factoryContext ->
                        val binding = MergeSettingsDialogBinding.inflate(LayoutInflater.from(factoryContext))
                        state.onViewCreated(factoryContext, binding, dialog, colorScheme)
                        binding.root
                    },
                    // AM (NAMED_SEASONS): onViewCreated runs once, so without
                    // this the adapter keeps the season list the dialog opened
                    // with and never sees one added in the season manager.
                    update = { state.onSeasonsRefreshed(dialog.seasons) },
                    modifier = Modifier.fillMaxWidth(),
                )

                // AM (MERGE_EPISODE_EXCLUSION) -->
                // Compose rather than another row type in the view-based adapter
                // above: this list is read-only apart from Restore, so it needs
                // none of that adapter's drag/reorder machinery. Restore applies
                // immediately instead of waiting for Save - it is not a staged
                // edit like the order and season changes around it, and the
                // ViewModel refreshes this list as it goes.
                if (dialog.removedEpisodes.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))
                    Text(
                        text = stringResource(AMMR.strings.am_merge_removed_episodes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    dialog.removedEpisodes.forEach { removed ->
                        RemovedEpisodeRow(
                            removed = removed,
                            onRestoreClick = { onRestoreEpisodeClick(removed.episodeId) },
                        )
                    }
                }
                // <-- AM (MERGE_EPISODE_EXCLUSION)
            }
        },
    )

    // AM (NAMED_SEASONS) -->
    // The row's season picker, over this dialog rather than inside the
    // RecyclerView - the same dialog reorder mode uses. Its picks are staged
    // like every other edit here, so it has no Save of its own.
    val pickerChildId = state.seasonPickerChildId
    if (pickerChildId != null) {
        ChangeSeasonDialog(
            seasons = state.shownSeasons,
            selectedSeason = state.stagedSeasonOf(pickerChildId),
            title = state.sourceNameOf(pickerChildId)
                ?: stringResource(AMMR.strings.am_action_change_episode_season),
            onSeasonSelected = { state.onSeasonPicked(pickerChildId, it) },
            onCreateNextSeason = { state.onNextSeasonPicked(pickerChildId) },
            onEditSeasons = onEditSeasonsClick,
            onDismissRequest = state::dismissSeasonPicker,
        )
    }
    // <-- AM (NAMED_SEASONS)
}
// <-- AM (MERGE_SETTINGS)

// AM (MERGE_EPISODE_EXCLUSION) -->
/** One removed episode, with the source it came from so identical names stay apart. */
@Composable
private fun RemovedEpisodeRow(
    removed: RemovedMergeEpisode,
    onRestoreClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = removed.episodeName,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (removed.sourceTitle.isNotEmpty()) {
                Text(
                    text = removed.sourceTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRestoreClick) {
            Text(text = stringResource(AMMR.strings.am_action_restore_to_merge))
        }
    }
}
// <-- AM (MERGE_EPISODE_EXCLUSION)
