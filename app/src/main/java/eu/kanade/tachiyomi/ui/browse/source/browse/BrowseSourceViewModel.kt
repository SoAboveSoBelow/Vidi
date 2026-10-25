package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import aniyomi.domain.merge.interactor.GetLibraryMergeChildIds
import aniyomi.domain.queue.interactor.AddToQueue
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.category.interactor.GetCategorySelection
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeWithEpisodeCount
import tachiyomi.domain.anime.model.toAnimeUpdate
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.GetVisibleCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock
import eu.kanade.tachiyomi.animesource.model.AnimeFilter as SourceModelFilter

@AssistedInject
class BrowseSourceViewModel(
    @Assisted private val sourceId: Long,
    @Assisted listingQuery: String?,
    sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val getRemoteAnime: GetRemoteAnime,
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime,
    private val getCategories: GetCategories,
    // AM (BROWSE_SELECTION) -->
    private val getVisibleCategories: GetVisibleCategories,
    private val getCategorySelection: GetCategorySelection,
    // <-- AM (BROWSE_SELECTION)
    // AM (PLAYBACK_QUEUE)
    private val addToQueue: AddToQueue,
    private val setAnimeCategories: SetAnimeCategories,
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags,
    private val getAnime: GetAnime,
    private val updateAnime: UpdateAnime,
    private val addTracks: AddTracks,
    // AM (MERGED_SOURCES)
    private val getLibraryMergeChildIds: GetLibraryMergeChildIds,
    getIncognitoState: GetIncognitoState,
) : ViewModel() {

    val state: StateFlow<BrowseSourceViewModel.State>
        field = MutableStateFlow<BrowseSourceViewModel.State>(State(Listing.valueOf(listingQuery)))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long, listingQuery: String?): BrowseSourceViewModel
    }

    var displayMode by sourcePreferences.sourceDisplayMode.asState(viewModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        // AM (LOCAL_SOURCE_FILTER_OFF_MAIN) -->
        // getFilterList() is synchronous, and for the local source it scans
        // storage to build the app group filter - which made opening the
        // local source in browse freeze the UI for the whole scan on the main
        // thread, twice (this init called getFilterList() once for the listing
        // and again for the state). Fetch it once, on IO, and let the listing
        // flow start in the meantime: GetRemoteAnime routes by query, so
        // Popular/Latest never read filters at all and start immediately;
        // only an initial Search listing (deep link, global search handoff)
        // runs its first page against the placeholder filters and reloads
        // once the real ones land.
        viewModelScope.launchIO {
            val filters = source.getFilterList()
            state.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    // AM (LOCAL_SOURCE_NO_DOUBLE_SCAN) -->
                    // A null query is the default browse state (Listing.valueOf
                    // hands out Search(null, emptyFilters) when no query was
                    // passed), not a real search. Replacing the listing with a
                    // new Search object would make the listing flow emit twice,
                    // and for the local source each emission is a full storage
                    // scan - so opening the source would scan everything twice.
                    // Only a genuine query needs the filters spliced in up
                    // front, and search() reads state.filters when the listing
                    // still carries its placeholder ones.
                    if (query != null) {
                        listing = Listing.Search(query, filters)
                    }
                    // <-- AM (LOCAL_SOURCE_NO_DOUBLE_SCAN)
                }

                it.copy(
                    listing = listing,
                    filters = filters,
                    toolbarQuery = query,
                )
            }
        }
        // <-- AM (LOCAL_SOURCE_FILTER_OFF_MAIN)

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }

        // AM (MERGED_SOURCES) -->
        // Results that are children of a merged entry in the library carry
        // favorite=0, so the rows themselves cannot say they are already held.
        // Kept as one set on the state rather than per row: the membership
        // table is small and this is a single query instead of one per result.
        viewModelScope.launch {
            getLibraryMergeChildIds.subscribe().collect { ids ->
                state.update { it.copy(mergeChildIds = ids) }
            }
        }
        // <-- AM (MERGED_SOURCES)
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val animePagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteAnime(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map { anime ->
                    getAnime.subscribe(anime.url, anime.source)
                        .map { it ?: anime }
                        .stateIn(viewModelScope)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(viewModelScope)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // AY -->
    // returns the number from the size slider
    fun getColumnsPreferenceForCurrentOrientation(orientation: Int): Int {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
    }
    // <-- AY

    // AM (LOCAL_SOURCE_FILTER_OFF_MAIN): same off-main-thread fetch as init -
    // resetting the filter sheet re-scans storage for the local source.
    fun resetFilters() {
        viewModelScope.launchIO {
            state.update { it.copy(filters = source.getFilterList()) }
        }
    }

    fun setListing(listing: Listing) {
        state.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: AnimeFilterList) {
        state.update {
            it.copy(
                filters = filters,
            )
        }
    }

    // AM (LOCAL_SOURCE_FILTER_OFF_MAIN): see init - getFilterList() can scan
    // storage, so it is never called on the main thread.
    fun search(query: String? = null, filters: AnimeFilterList? = null) {
        viewModelScope.launchIO {
            // AM (LOCAL_SOURCE_NO_DOUBLE_SCAN): the default browse listing
            // keeps its placeholder filters (see init), so prefer the loaded
            // state.filters when the listing has none.
            val input = (state.value.listing as? Listing.Search)?.let {
                it.copy(filters = state.value.filters.ifEmpty { it.filters })
            } ?: Listing.Search(query = null, filters = source.getFilterList())

            state.update {
                it.copy(
                    listing = input.copy(
                        query = query ?: input.query,
                        filters = filters ?: input.filters,
                    ),
                    toolbarQuery = query ?: input.query,
                )
            }
        }
    }

    // AM (LOCAL_SOURCE_FILTER_OFF_MAIN): see init - getFilterList() can scan
    // storage, so it is never called on the main thread.
    fun searchGenre(genreName: String) {
        viewModelScope.launchIO {
            val defaultFilters = source.getFilterList()
            var genreExists = false

            filter@ for (sourceFilter in defaultFilters) {
                if (sourceFilter is SourceModelFilter.Group<*>) {
                    for (filter in sourceFilter.state) {
                        if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                            when (filter) {
                                is SourceModelFilter.TriState -> filter.state = 1
                                is SourceModelFilter.CheckBox -> filter.state = true
                                else -> {}
                            }
                            genreExists = true
                            break@filter
                        }
                    }
                } else if (sourceFilter is SourceModelFilter.Select<*>) {
                    val index = sourceFilter.values.filterIsInstance<String>()
                        .indexOfFirst { it.equals(genreName, true) }

                    if (index != -1) {
                        sourceFilter.state = index
                        genreExists = true
                        break
                    }
                }
            }

            state.update {
                val listing = if (genreExists) {
                    Listing.Search(query = null, filters = defaultFilters)
                } else {
                    Listing.Search(query = genreName, filters = defaultFilters)
                }
                it.copy(
                    filters = defaultFilters,
                    listing = listing,
                    toolbarQuery = listing.query,
                )
            }
        }
    }

    /**
     * Adds or removes an anime from the library.
     *
     * @param anime the anime to update.
     */
    fun changeAnimeFavorite(anime: Anime) {
        viewModelScope.launch {
            var new = anime.copy(
                favorite = !anime.favorite,
                dateAdded = when (anime.favorite) {
                    true -> 0
                    false -> Clock.System.now().toEpochMilliseconds()
                },
            )

            // AM (ART_SURVIVES_UNFAVORITE) -->
            // The !favorite branch here was removeCovers()/removeBackgrounds() -
            // unfavoriting from a source's browse list took the custom art with
            // it just like the other two paths. See removeCovers' own doc
            // comment; only the favorite branch has anything to do now.
            // <-- AM (ART_SURVIVES_UNFAVORITE)
            if (new.favorite) {
                setAnimeDefaultEpisodeFlags.await(anime)
                addTracks.bindEnhancedTrackers(anime, source)
            }

            updateAnime.await(new.toAnimeUpdate())
        }
    }

    fun addFavorite(anime: Anime) {
        viewModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveAnimeToCategories(anime, defaultCategory)

                    changeAnimeFavorite(anime)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveAnimeToCategories(anime)

                    changeAnimeFavorite(anime)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(anime.id).map { it.id }
                    setDialog(
                        Dialog.ChangeAnimeCategory(
                            listOf(anime),
                            categories.mapAsCheckboxState { it.id in preselectedIds },
                        ),
                    )
                }
            }
        }
    }

    // AM (BROWSE_SELECTION) -->
    fun toggleSelection(anime: Anime) {
        state.update { current ->
            current.copy(
                selection = if (anime.id in current.selection) {
                    current.selection - anime.id
                } else {
                    current.selection + anime.id
                },
            )
        }
    }

    fun clearSelection() {
        state.update { it.copy(selection = emptySet()) }
    }

    /**
     * Queues the selection, one block per entry.
     *
     * A browse result has no episodes in the database yet, so [AddToQueue]
     * records each block as pending and [QueueFillJob] fetches them - which is
     * why this asks for a fill run rather than doing the fetching itself. An
     * entry that HAS been opened before already has its episodes, and
     * AddToQueue fills its block outright; the run then finds nothing pending
     * and stops, so there is no need to work out which case applies here.
     */
    /**
     * Queues one result, without selecting it first - what a double tap does.
     *
     * Goes through the same [AddToQueue] call and the same fill request as the
     * selection action above rather than a path of its own: the only thing a
     * double tap decides is WHICH entry, and one entry is a selection of one.
     */
    fun addAnimeToQueue(anime: Anime, onRequestFill: () -> Unit) {
        viewModelScope.launchIO {
            addToQueue.await(anime)
            onRequestFill()
        }
    }

    fun addSelectionToQueue(onRequestFill: () -> Unit) {
        viewModelScope.launchIO {
            val animeList = selectedAnime()
            clearSelection()
            if (animeList.isEmpty()) return@launchIO
            addToQueue.await(animeList)
            onRequestFill()
        }
    }

    /**
     * Resolved from the DB by id rather than from the pager: a selected result
     * can scroll out of the loaded pages, and the row is already local by the
     * time it renders (see networkToLocalAnime in the paging source).
     */
    private suspend fun selectedAnime(): List<Anime> {
        return state.value.selection.mapNotNull { getAnime.await(it) }
    }

    /**
     * The selection's one library action: the library screen's own
     * multi-select category dialog, over browse's selection.
     *
     * A selection of one that is not in the library yet still goes through the
     * duplicate check first, so the migrate option the old long-press offered
     * stays reachable.
     */
    fun openChangeCategoryDialog() {
        viewModelScope.launchIO {
            val animeList = selectedAnime()
            if (animeList.isEmpty()) {
                clearSelection()
                return@launchIO
            }

            val single = animeList.singleOrNull()
            if (single != null && !single.favorite) {
                val duplicates = getDuplicateLibraryAnime(single)
                if (duplicates.isNotEmpty()) {
                    clearSelection()
                    setDialog(Dialog.AddDuplicateAnime(single, duplicates))
                    return@launchIO
                }
            }

            // No categories to show means no dialog to show them in, so this
            // falls back to the per-entry add the long-press always used.
            val categories = getCategories()
            if (categories.isEmpty()) {
                clearSelection()
                animeList.filterNot { it.favorite }.forEach { addFavorite(it) }
                return@launchIO
            }

            val preselected = getCategorySelection.await(animeList.map { it.id }, categories)
            clearSelection()
            setDialog(Dialog.ChangeAnimeCategory(animeList, preselected))
        }
    }

    /**
     * The dialog's result, per entry, through the same two calls the anime and
     * history screens use for it (moveAnimeToCategoriesAndAddToLibrary):
     * categories first, then favorite if it is not already.
     *
     * [include] and [exclude] are the dialog's own lists. A category left on
     * the dash is in neither and keeps whatever each entry had, which is what
     * [exclude] rather than "everything not included" is for.
     *
     * The one thing with no existing counterpart is the last line: nothing
     * anywhere else in the app takes an entry back OUT of the library from a
     * category dialog. It is here because the bar has no separate remove
     * action, so clearing an entry's last category has to be what removes it.
     */
    fun confirmCategorySelection(animeList: List<Anime>, include: List<Long>, exclude: List<Long>) {
        viewModelScope.launchIO {
            animeList.forEach { anime ->
                val categoryIds = getVisibleCategories.await(anime.id)
                    .map { it.id }
                    .subtract(exclude.toSet())
                    .plus(include)
                    .toList()

                setAnimeCategories.await(anime.id, categoryIds)

                if (anime.favorite == categoryIds.isNotEmpty()) return@forEach
                updateAnime.awaitUpdateFavorite(anime.id, categoryIds.isNotEmpty())
            }
        }
    }
    // <-- AM (BROWSE_SELECTION)

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryAnime(anime: Anime): List<AnimeWithEpisodeCount> {
        return getDuplicateLibraryAnime.invoke(anime)
    }

    private fun moveAnimeToCategories(anime: Anime, vararg categories: Category) {
        moveAnimeToCategories(anime, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveAnimeToCategories(anime: Anime, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setAnimeCategories.await(
                animeId = anime.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        state.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        state.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: AnimeFilterList) {
        data object Popular : Listing(query = GetRemoteAnime.QUERY_POPULAR, filters = AnimeFilterList())
        data object Latest : Listing(query = GetRemoteAnime.QUERY_LATEST, filters = AnimeFilterList())
        data class Search(
            override val query: String?,
            override val filters: AnimeFilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteAnime.QUERY_POPULAR -> Popular
                    GetRemoteAnime.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = AnimeFilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog

        data class AddDuplicateAnime(val anime: Anime, val duplicates: List<AnimeWithEpisodeCount>) : Dialog
        data class ChangeAnimeCategory(
            val animeList: List<Anime>,
            // AM (BROWSE_SELECTION): CheckboxState rather than its State
            // subtype, because a selection can leave a category in the mixed
            // TriState the dialog already knows how to render.
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class Migrate(val target: Anime, val current: Anime) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: AnimeFilterList = AnimeFilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        // AM (MERGED_SOURCES)
        val mergeChildIds: Set<Long> = emptySet(),
        // AM (BROWSE_SELECTION)
        val selection: Set<Long> = emptySet(),
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()

        // AM (BROWSE_SELECTION)
        val selectionMode get() = selection.isNotEmpty()
    }
}
