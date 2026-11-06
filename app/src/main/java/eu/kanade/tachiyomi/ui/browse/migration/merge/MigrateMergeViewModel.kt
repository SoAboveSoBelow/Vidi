// AM (MERGED_SOURCES) -->
package eu.kanade.tachiyomi.ui.browse.migration.merge

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import aniyomi.domain.merge.repository.MergeChildRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.common.utils.mutate
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.service.SourceManager

/**
 * The sources inside one merged entry, as migration subjects.
 *
 * A merged entry cannot itself be migrated - its pseudo-source has no
 * catalogue to search - so migrating "a merged entry" means migrating one or
 * more of the sources it is made of. Each child is an ordinary library row
 * from a real source, so once chosen they go through exactly the same
 * migration the rest of the app uses, and the merge is repointed at the
 * replacements by ReplaceMergeChild.
 *
 * The list is in merge order, the order the user arranged in merge settings,
 * so it reads the same as the merge it describes.
 */
@AssistedInject
class MigrateMergeViewModel(
    @Assisted private val mergeParentId: Long,
    private val mergeChildRepository: MergeChildRepository,
    private val sourceManager: SourceManager,
    private val getAnime: GetAnime,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mergeParentId: Long): MigrateMergeViewModel
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launchIO {
            try {
                val parent = getAnime.await(mergeParentId)
                val children = mergeChildRepository.getChildrenByMergeParentId(mergeParentId)
                    .map { child ->
                        MergeChildItem(
                            anime = child,
                            sourceName = sourceManager.getOrStub(child.source).name,
                        )
                    }
                _state.update { it.copy(parentTitle = parent?.title.orEmpty(), items = children) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load merge children for $mergeParentId" }
                _state.update { it.copy(items = emptyList()) }
            }
        }
    }

    fun toggleSelection(animeId: Long) {
        _state.update { state ->
            state.copy(
                selection = state.selection.mutate { if (!it.remove(animeId)) it.add(animeId) },
            )
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    /** Both act on what the filter is showing, never on hidden sources. */
    fun selectAll() {
        _state.update { it.copy(selection = it.allShownIds.toSet()) }
    }

    fun invertSelection() {
        _state.update { state ->
            state.copy(selection = state.allShownIds.filterNot { it in state.selection }.toSet())
        }
    }

    /** Null clears the filter. Selections outside the new filter are dropped, so
     *  what the FAB migrates is always what the user can see. */
    fun setSourceFilter(sourceId: Long?) {
        _state.update { state ->
            val shown = state.itemsFor(sourceId).mapTo(HashSet()) { it.anime.id }
            state.copy(sourceFilter = sourceId, selection = state.selection.intersect(shown))
        }
    }

    @Immutable
    data class MergeChildItem(
        val anime: Anime,
        val sourceName: String,
    )

    @Immutable
    data class State(
        val parentTitle: String = "",
        val selection: Set<Long> = emptySet(),
        val sourceFilter: Long? = null,
        private val items: List<MergeChildItem>? = null,
    ) {

        val isLoading: Boolean get() = items == null

        val isEmpty: Boolean get() = items.orEmpty().isEmpty()

        /** The children the filter lets through, in merge order. */
        val shownItems: List<MergeChildItem> get() = itemsFor(sourceFilter)

        /** Every extension represented in the merge, for the filter, in merge order. */
        val sourceFilters: List<Pair<Long, String>>
            get() = items.orEmpty()
                .map { it.anime.source to it.sourceName }
                .distinctBy { it.first }

        val selectionMode: Boolean get() = selection.isNotEmpty()

        /** What the selection actions act on - the sources currently on screen. */
        val allShownIds: List<Long> get() = shownItems.map { it.anime.id }

        fun itemsFor(sourceId: Long?): List<MergeChildItem> {
            val all = items.orEmpty()
            return if (sourceId == null) all else all.filter { it.anime.source == sourceId }
        }
    }
}
// <-- AM (MERGED_SOURCES)
