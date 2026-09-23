// AM (MERGE_SETTINGS) -->
// Ported from Komikku's EditMergedSettingsDialog: a Compose dialog hosting the
// view-based list, with edits staged until Save.
package eu.kanade.tachiyomi.ui.anime.merged

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import aniyomi.domain.merge.model.DedupeMode
import aniyomi.domain.season.model.EntrySeason
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme
import eu.kanade.tachiyomi.databinding.MergeSettingsDialogBinding
import eu.kanade.tachiyomi.ui.anime.AnimeViewModel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
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

    fun canMove(): Boolean = dedupeMode == DedupeMode.PRIORITY

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

        val adapter = MergeSettingsAdapter(this, canMove(), colorScheme, seasons)
        this.adapter = adapter
        headerAdapter = MergeSettingsHeaderAdapter(this, adapter, colorScheme)
        binding.recycler.adapter = ConcatAdapter(headerAdapter, adapter)
        binding.recycler.layoutManager = LinearLayoutManager(context)
        adapter.isHandleDragEnabled = canMove()
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

    override fun onSeasonSelected(position: Int, seasonNumber: Long) {
        val source = adapter?.currentItems?.getOrNull(position)?.source ?: return
        seasonByChild = seasonByChild + (source.anime.id to seasonNumber)
    }

    override fun onEditSeasonsClick() = onEditSeasonsClick.invoke()

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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
        ),
    )
}
// <-- AM (MERGE_SETTINGS)
