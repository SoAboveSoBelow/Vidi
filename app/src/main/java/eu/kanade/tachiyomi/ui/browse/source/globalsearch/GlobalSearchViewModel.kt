package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import androidx.lifecycle.viewModelScope
import aniyomi.domain.merge.interactor.GetLibraryMergeChildIds
import aniyomi.domain.queue.interactor.AddToQueue
import aniyomi.domain.queue.interactor.AddToQueue.QueueSource
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.player.PlayerMediaHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class GlobalSearchViewModel(
    @Assisted initialQuery: String,
    @Assisted initialExtensionFilter: String?,
    sourcePreferences: SourcePreferences,
    sourceManager: SourceManager,
    extensionManager: ExtensionManager,
    networkToLocalAnime: NetworkToLocalAnime,
    getAnime: GetAnime,
    preferences: SourcePreferences,
    // AM (MERGED_SOURCES)
    getLibraryMergeChildIds: GetLibraryMergeChildIds,
    // AM (DOUBLE_TAP_QUEUE)
    private val addToQueue: AddToQueue,
) : SearchViewModel(
    initialState = State(searchQuery = initialQuery),
    sourcePreferences = sourcePreferences,
    sourceManager = sourceManager,
    extensionManager = extensionManager,
    networkToLocalAnime = networkToLocalAnime,
    getAnime = getAnime,
    preferences = preferences,
    getLibraryMergeChildIds = getLibraryMergeChildIds,
) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String, initialExtensionFilter: String?): GlobalSearchViewModel
    }

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }
    }

    // AM (DOUBLE_TAP_QUEUE) -->
    /**
     * Queues one result - what a double tap on it does.
     *
     * A search result has no episodes in the database yet, so the block is
     * recorded pending and the caller starts the fill worker, exactly as
     * BrowseSourceViewModel.addAnimeToQueue does.
     */
    fun addAnimeToQueue(
        anime: Anime,
        onRequestFill: () -> Unit,
        unwatchedOnly: Boolean? = null,
        onChoiceNeeded: (QueueSource) -> Unit = {},
    ) {
        viewModelScope.launchIO {
            // AM (QUEUE_WATCH_FILTER) -->
            // AM (QUEUE_RESOLVE_ONCE)
            val source = addToQueue.resolve(anime)
            if (unwatchedOnly == null && source.hasMixedWatchState) {
                withContext(Dispatchers.Main) { onChoiceNeeded(source) }
                return@launchIO
            }
            addToQueue.await(
                source = source,
                unwatchedOnly = unwatchedOnly ?: false,
                currentEpisodeId = PlayerMediaHolder.current?.viewModel?.stateData?.value?.currentEpisode?.id,
            )
            // <-- AM (QUEUE_WATCH_FILTER)
            withContext(Dispatchers.Main) { onRequestFill() }
        }
    }
    // <-- AM (DOUBLE_TAP_QUEUE)

    override fun getEnabledSources(): List<AnimeSource> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}
