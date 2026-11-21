package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
// AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import aniyomi.domain.queue.interactor.GetQueueResumePoint
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.history.HistoryUiModel
// AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
// AM (A_A_GLANCE_IS_NOT_A_VIEWING)
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeWithEpisodeCount
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
// AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
import tachiyomi.source.local.LocalSource
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HistoryViewModel(
    private val addTracks: AddTracks,
    private val getCategories: GetCategories,
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime,
    private val getHistory: GetHistory,
    private val getAnime: GetAnime,
    private val getNextEpisodes: GetNextEpisodes,
    // AM (PLAYBACK_QUEUE)
    private val getQueueResumePoint: GetQueueResumePoint,
    private val libraryPreferences: LibraryPreferences,
    // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): for the downloaded filter, which
    // the database cannot answer - a download is a folder, not a row.
    private val downloadManager: DownloadManager,
    private val removeHistory: RemoveHistory,
    private val setAnimeCategories: SetAnimeCategories,
    private val updateAnime: UpdateAnime,
    private val sourceManager: SourceManager,
) : ViewModel() {

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<Dialog?>(null)

    // AM (HISTORY_ORIGIN) -->
    /**
     * Whether the list is narrowed to episodes watched in the player, leaving
     * out the cards seen in the browse feed. Toggled by tapping the History
     * chip while it is already showing.
     *
     * Not persisted: it is a way of looking at the list for a moment, not a
     * setting, and coming back to a History tab that silently hides entries
     * would be worse than re-tapping.
     */
    private val playerOnly = MutableStateFlow(false)

    fun togglePlayerOnly() {
        playerOnly.value = !playerOnly.value
    }
    // <-- AM (HISTORY_ORIGIN)

    // AM (A_A_GLANCE_IS_NOT_A_VIEWING) -->
    /**
     * The history screen's own threshold, chosen from its top bar.
     *
     * Persisted, unlike [playerOnly] above: the point of it is to stay out of
     * the way, so having to set it again on every visit would defeat it.
     */
    private val minSecondsSeen = libraryPreferences.historyMinSecondsSeen

    fun setMinSecondsSeen(seconds: Int) {
        minSecondsSeen.set(seconds)
    }
    // <-- AM (A_A_GLANCE_IS_NOT_A_VIEWING)

    // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS) -->
    private val watchedOnly = libraryPreferences.historyWatchedOnly

    private val downloadedOnly = libraryPreferences.historyDownloadedOnly

    fun toggleWatchedOnly() {
        watchedOnly.set(!watchedOnly.get())
    }

    fun toggleDownloadedOnly() {
        downloadedOnly.set(!downloadedOnly.get())
    }
    // <-- AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)

    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME) -->
    /**
     * The screen's items, one page at a time.
     *
     * Placeholders are off, which is a choice with a visible consequence either
     * way. With them on, the paging source's itemsBefore/itemsAfter would keep
     * FastScrollLazyColumn's thumb sized against the whole list, but unloaded
     * rows arrive as null and have to be keyed by position - and
     * HISTORY_STABLE_ITEM_KEY in HistoryScreen records what positional keys
     * cost here: every progress write re-animated the watched entry out and
     * back in. Off, the keys and the separator logic are exactly what they
     * were, and the thumb represents loaded rows instead of all of them.
     *
     * cachedIn, so the pages survive configuration changes and the tab being
     * left and returned to without re-reading the database.
     */
    val historyItems: Flow<PagingData<HistoryUiModel>> = combine(
        searchQuery,
        playerOnly,
        // AM (A_A_GLANCE_IS_NOT_A_VIEWING): part of what the pager is built
        // from, so changing the setting rebuilds it - the filter is in the
        // query, so there is nothing a running pager could re-apply.
        minSecondsSeen.changes().distinctUntilChanged(),
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): likewise in the query, so
        // likewise part of what the pager is built from.
        watchedOnly.changes().distinctUntilChanged(),
        // AM (A_A_FILTER_THAT_DOES_NOTHING): here with the other three, even
        // though this one is not in the query - see the filter below.
        downloadedOnly.changes().distinctUntilChanged(),
    ) { query, playerOnly, minSecondsSeen, watchedOnly, downloadedOnly ->
        QueryFilters(query, playerOnly, minSecondsSeen, watchedOnly, downloadedOnly)
    }
        .flatMapLatest { filters ->
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = {
                    getHistory.pagingSource(
                        query = filters.query ?: "",
                        playerOnly = filters.playerOnly,
                        minSecondsSeen = filters.minSecondsSeen,
                        watchedOnly = filters.watchedOnly,
                    )
                },
            ).flow
                // AM (A_A_FILTER_THAT_DOES_NOTHING) -->
                // Inside the Pager's own flow and above cachedIn, with the
                // filters state captured from the rebuild rather than combined
                // in afterwards.
                //
                // It was downstream of cachedIn, combined with the preference,
                // on the reasoning that toggling it could then re-use the
                // loaded pages instead of re-reading them. It did nothing at
                // all there. cachedIn hands the same cached PagingData to each
                // new combination, and the presenter the list collects through
                // treats an emission carrying the receiver it is already
                // presenting as the stream it is already on - so the toggle
                // produced an emission, the filter was never applied to
                // anything, and the list did not change.
                //
                // Here it is simply part of what the pager is built from, like
                // the other three, and the page re-read that costs is the same
                // one they all pay.
                //
                // The filter itself cannot move into the query: whether an
                // episode is downloaded is a question about the filesystem,
                // which only the download cache can answer. So historyCount
                // still cannot account for it and a page can come back shorter
                // than its page size - harmless with placeholders off, which
                // they are.
                //
                // Before the ui models are built, so a day that filters down
                // to nothing takes its date header with it rather than leaving
                // an empty heading.
                .map { pagingData ->
                    if (!filters.downloadedOnly) {
                        pagingData
                    } else {
                        pagingData.filter { item ->
                            // A local entry is on disk by definition and has no
                            // download folder to find, so the cache would answer
                            // no for the one case that is certainly yes. The
                            // library's own downloaded filter leads with the same
                            // test (isLocal || downloadCount > 0).
                            item.coverData.sourceId == LocalSource.ID ||
                                downloadManager.isEpisodeDownloaded(
                                    episodeName = item.episodeName,
                                    episodeScanlator = item.scanlator,
                                    episodeUrl = item.episodeUrl,
                                    animeTitle = item.ogTitle,
                                    sourceId = item.coverData.sourceId,
                                )
                        }
                    }
                }
                // <-- AM (A_A_FILTER_THAT_DOES_NOTHING)
        }
        .cachedIn(viewModelScope)
        .map { it.toHistoryUiModels() }

    /**
     * What the Flow's catch used to do - see [reportLoadError].
     *
     * A Pager's flow does not throw: QueryPagingSource answers a failed load
     * with LoadResult.Error, which the UI sees as LoadState.Error. So the
     * snackbar is raised from there instead, and the behaviour is unchanged.
     */
    fun reportLoadError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        viewModelScope.launch { _events.send(Event.InternalError) }
    }
    // <-- AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME)

    val state: StateFlow<State> = combine(
        searchQuery,
        dialog,
        // AM (HISTORY_ORIGIN)
        playerOnly,
        // AM (A_A_GLANCE_IS_NOT_A_VIEWING): read into state so the top bar can
        // show which threshold is set.
        minSecondsSeen.changes().distinctUntilChanged(),
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): as one flow, because combine
        // tops out at five and the two belong together anyway.
        combine(
            watchedOnly.changes().distinctUntilChanged(),
            downloadedOnly.changes().distinctUntilChanged(),
            ::Pair,
        ),
    ) { searchQuery, dialog, playerOnly, minSecondsSeen, extraFilters ->
        State(
            searchQuery = searchQuery,
            dialog = dialog,
            playerOnly = playerOnly,
            minSecondsSeen = minSecondsSeen,
            // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
            watchedOnly = extraFilters.first,
            downloadedOnly = extraFilters.second,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): the same separator rule, applied
    // to a page instead of a list. PagingData.insertSeparators hands the
    // generator the items either side of a boundary exactly as the list version
    // did, so a date header still appears only where the date changes.
    private fun PagingData<HistoryWithRelations>.toHistoryUiModels(): PagingData<HistoryUiModel> {
        return map { HistoryUiModel.Item(it) }
            .insertSeparators<HistoryUiModel.Item, HistoryUiModel> { before, after ->
                val beforeDate = before?.item?.seenAt?.time?.toLocalDate()
                val afterDate = after?.item?.seenAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    // AM (PLAYBACK_QUEUE) -->
    /**
     * Where the resume gesture should go - see RecentsTab.onReselect().
     *
     * No GetNextEpisodes fallback behind it: that interactor answers "what
     * should I watch next in this entry", which is the Continue button's
     * question, not this gesture's - and it answers it by advancing past
     * anything already seen, which is exactly what must not happen here (see
     * GetQueueResumePoint). It can only return nothing in the one case
     * GetQueueResumePoint already returns null for, nothing ever played, so it
     * would add no answer either.
     */
    suspend fun getResumePoint(): GetQueueResumePoint.ResumePoint? {
        return withIOContext { getQueueResumePoint.await() }
    }
    // <-- AM (PLAYBACK_QUEUE)

    fun getNextEpisodeForAnime(animeId: Long, episodeId: Long) {
        viewModelScope.launchIO {
            sendNextEpisodeEvent(getNextEpisodes.await(animeId, episodeId, onlyUnseen = false))
        }
    }

    private suspend fun sendNextEpisodeEvent(episodes: List<Episode>) {
        val episode = episodes.firstOrNull()
        _events.send(Event.OpenEpisode(episode))
    }

    fun removeFromHistory(history: HistoryWithRelations) {
        viewModelScope.launchIO {
            removeHistory.await(history)
        }
    }

    fun removeAllFromHistory(animeId: Long) {
        viewModelScope.launchIO {
            removeHistory.await(animeId)
        }
    }

    fun removeAllHistory() {
        viewModelScope.launchIO {
            val result = removeHistory.awaitAll()
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        searchQuery.update { query }
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.update { dialog }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    private fun moveAnimeToCategory(animeId: Long, categories: Category?) {
        val categoryIds = listOfNotNull(categories).map { it.id }
        moveAnimeToCategory(animeId, categoryIds)
    }

    private fun moveAnimeToCategory(animeId: Long, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setAnimeCategories.await(animeId, categoryIds)
        }
    }

    fun moveAnimeToCategoriesAndAddToLibrary(anime: Anime, categories: List<Long>) {
        moveAnimeToCategory(anime.id, categories)
        if (anime.favorite) return

        viewModelScope.launchIO {
            updateAnime.awaitUpdateFavorite(anime.id, true)
        }
    }

    private suspend fun getAnimeCategoryIds(anime: Anime): List<Long> {
        return getCategories.await(anime.id)
            .map { it.id }
    }

    fun addFavorite(animeId: Long) {
        viewModelScope.launchIO {
            val anime = getAnime.await(animeId) ?: return@launchIO

            val duplicates = getDuplicateLibraryAnime(anime)
            if (duplicates.isNotEmpty()) {
                dialog.update { Dialog.DuplicateAnime(anime, duplicates) }
                return@launchIO
            }

            addFavorite(anime)
        }
    }

    fun addFavorite(anime: Anime) {
        viewModelScope.launchIO {
            // Move to default category if applicable
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when {
                // Default category set
                defaultCategory != null -> {
                    val result = updateAnime.awaitUpdateFavorite(anime.id, true)
                    if (!result) return@launchIO
                    moveAnimeToCategory(anime.id, defaultCategory)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0L || categories.isEmpty() -> {
                    val result = updateAnime.awaitUpdateFavorite(anime.id, true)
                    if (!result) return@launchIO
                    moveAnimeToCategory(anime.id, null)
                }

                // Choose a category
                else -> showChangeCategoryDialog(anime)
            }

            // Sync with tracking services if applicable
            addTracks.bindEnhancedTrackers(anime, sourceManager.getOrStub(anime.source))
        }
    }

    fun showMigrateDialog(target: Anime, current: Anime) {
        dialog.update { Dialog.Migrate(target = target, current = current) }
    }

    fun showChangeCategoryDialog(anime: Anime) {
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getAnimeCategoryIds(anime)
            dialog.update {
                Dialog.ChangeCategory(
                    anime = anime,
                    initialSelection = categories.mapAsCheckboxState { it.id in selection },
                )
            }
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val dialog: Dialog? = null,
        // AM (HISTORY_ORIGIN)
        val playerOnly: Boolean = false,
        // AM (A_A_GLANCE_IS_NOT_A_VIEWING)
        val minSecondsSeen: Int = 0,
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS) -->
        val watchedOnly: Boolean = false,
        val downloadedOnly: Boolean = false,
        // <-- AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
    ) {
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): whether anything is
        // narrowing the list, which is what tints the filter icon - the same
        // thing the Updates bar beside it does with its own.
        val hasFilters: Boolean get() = minSecondsSeen > 0 || watchedOnly || downloadedOnly
    }

    // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS) -->
    /**
     * What the pager is built from. Named rather than a tuple because there
     * are five of them and a destructured tuple stops saying which is which.
     *
     * AM (A_A_FILTER_THAT_DOES_NOTHING): [downloadedOnly] is the odd one - the
     * other four go into the query and it is applied to the rows afterwards -
     * but it belongs here all the same, because changing it has to rebuild the
     * pager for the filter to be applied at all.
     */
    private data class QueryFilters(
        val query: String?,
        val playerOnly: Boolean,
        val minSecondsSeen: Int,
        val watchedOnly: Boolean,
        val downloadedOnly: Boolean,
    )
    // <-- AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)

    // AM (A_HISTORY_IS_READ_A_PAGE_AT_A_TIME): 25 rows. A page has to outrun a
    // screenful so scrolling never waits on a load, and stay small enough that
    // the query it runs is nothing like the whole-table read it replaced.
    private companion object {
        const val PAGE_SIZE = 25
    }

    sealed interface Dialog {
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS): all three filters at once -
        // see HistoryFilterDialog.
        data object Filter : Dialog
        data object DeleteAll : Dialog
        data class Delete(val history: HistoryWithRelations) : Dialog
        data class DuplicateAnime(val anime: Anime, val duplicates: List<AnimeWithEpisodeCount>) : Dialog
        data class ChangeCategory(
            val anime: Anime,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class Migrate(val target: Anime, val current: Anime) : Dialog
    }

    sealed interface Event {
        data class OpenEpisode(val episode: Episode?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
