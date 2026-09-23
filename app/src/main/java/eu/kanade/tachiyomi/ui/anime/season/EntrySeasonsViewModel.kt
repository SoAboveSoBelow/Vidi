// AM (NAMED_SEASONS) -->
package eu.kanade.tachiyomi.ui.anime.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import aniyomi.domain.order.interactor.GetEpisodeOrder
import aniyomi.domain.season.interactor.ManageEntrySeasons
import aniyomi.domain.season.model.EntrySeason
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime

/**
 * The category-style season manager for one entry. Reads the resolved season
 * list live (so it reflects episode moves made elsewhere) and routes every
 * edit through ManageEntrySeasons.
 */
@AssistedInject
class EntrySeasonsViewModel(
    @Assisted private val animeId: Long,
    private val getAnime: GetAnime,
    private val getEpisodeOrder: GetEpisodeOrder,
    private val manageEntrySeasons: ManageEntrySeasons,
) : ViewModel() {

    sealed interface Dialog {
        data object Create : Dialog
        data class Rename(val season: EntrySeason) : Dialog
        data class Delete(val season: EntrySeason) : Dialog
    }

    data class State(
        val isLoading: Boolean = true,
        val seasons: List<EntrySeason> = emptyList(),
        val dialog: Dialog? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var anime: Anime? = null

    init {
        viewModelScope.launchIO {
            val loaded = getAnime.await(animeId) ?: return@launchIO
            anime = loaded
            getEpisodeOrder.subscribe(loaded, applyScanlatorFilter = false).collect { order ->
                _state.update { it.copy(isLoading = false, seasons = order.seasons) }
            }
        }
    }

    fun showDialog(dialog: Dialog) = _state.update { it.copy(dialog = dialog) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun create(name: String) = edit { manageEntrySeasons.create(it, name) }

    fun rename(seasonNumber: Long, name: String) = edit { manageEntrySeasons.rename(it, seasonNumber, name) }

    fun delete(seasonNumber: Long) = edit { manageEntrySeasons.delete(it, seasonNumber) }

    fun reorder(orderedNumbers: List<Long>) = edit { manageEntrySeasons.reorder(it, orderedNumbers) }

    private fun edit(block: suspend (Anime) -> Unit) {
        val host = anime ?: return
        viewModelScope.launchIO { block(host) }
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(animeId: Long): EntrySeasonsViewModel
    }
}
// <-- AM (NAMED_SEASONS)
