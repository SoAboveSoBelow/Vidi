// AM (NAMED_SEASONS) -->
package eu.kanade.tachiyomi.ui.anime.season

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import aniyomi.domain.merge.model.MERGE_DEFAULT_SEASON_NUMBER
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.anime.season.EntrySeasonDeleteDialog
import eu.kanade.presentation.anime.season.EntrySeasonNameDialog
import eu.kanade.presentation.anime.season.EntrySeasonsScreen
import eu.kanade.presentation.anime.season.seasonLabel
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/** Season manager for one library entry, reached from the season pickers' edit buttons. */
class EntrySeasonsScreen(private val animeId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<EntrySeasonsViewModel, EntrySeasonsViewModel.Factory> {
            create(animeId = animeId)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        EntrySeasonsScreen(
            seasons = state.seasons,
            onClickCreate = { viewModel.showDialog(EntrySeasonsViewModel.Dialog.Create) },
            onClickRename = { viewModel.showDialog(EntrySeasonsViewModel.Dialog.Rename(it)) },
            onClickDelete = { viewModel.showDialog(EntrySeasonsViewModel.Dialog.Delete(it)) },
            onReorder = viewModel::reorder,
            navigateUp = navigator::pop,
        )

        val takenNames = state.seasons.mapNotNullTo(HashSet()) { it.name }
        when (val dialog = state.dialog) {
            null -> {}
            EntrySeasonsViewModel.Dialog.Create -> {
                EntrySeasonNameDialog(
                    title = stringResource(AMMR.strings.am_entry_season_create),
                    initialName = "",
                    takenNames = takenNames,
                    onConfirm = viewModel::create,
                    onDismissRequest = viewModel::dismissDialog,
                )
            }
            is EntrySeasonsViewModel.Dialog.Rename -> {
                EntrySeasonNameDialog(
                    title = stringResource(AMMR.strings.am_entry_season_rename),
                    initialName = dialog.season.name.orEmpty(),
                    takenNames = takenNames - dialog.season.name.orEmpty(),
                    onConfirm = { viewModel.rename(dialog.season.number, it) },
                    onDismissRequest = viewModel::dismissDialog,
                )
            }
            is EntrySeasonsViewModel.Dialog.Delete -> {
                EntrySeasonDeleteDialog(
                    seasonLabel = seasonLabel(dialog.season.number, state.seasons),
                    defaultSeasonLabel = seasonLabel(MERGE_DEFAULT_SEASON_NUMBER, state.seasons),
                    onConfirm = { viewModel.delete(dialog.season.number) },
                    onDismissRequest = viewModel::dismissDialog,
                )
            }
        }
    }
}
// <-- AM (NAMED_SEASONS)
