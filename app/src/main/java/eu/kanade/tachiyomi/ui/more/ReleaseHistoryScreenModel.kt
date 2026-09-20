package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.model.Release

/**
 * Pages the full release history.
 *
 * Paging is deliberate rather than a single fetch: the repo has well over a hundred tags, and the
 * GitHub list endpoint caps a page at 100 anyway. Pulling one page at a time also keeps the
 * unauthenticated rate limit (60/hr per IP) intact for the update check.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReleaseHistoryScreenModel(
    private val updateChecker: AppUpdateChecker,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private var loadJob: Job? = null

    init {
        loadNextPage()
    }

    fun loadNextPage() {
        if (loadJob?.isActive == true) return
        val current = state.value
        if (current.endReached) return

        loadJob = viewModelScope.launch {
            state.update { it.copy(isLoading = true, hasError = false) }
            try {
                val page = current.nextPage
                val releases = updateChecker.getReleaseHistory(page)
                state.update {
                    it.copy(
                        releases = (it.releases + releases.map(::toItem)).toImmutableList(),
                        nextPage = page + 1,
                        endReached = releases.isEmpty(),
                        isLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                state.update { it.copy(isLoading = false, hasError = true) }
            }
        }
    }

    private fun toItem(release: Release) = ReleaseItem(
        version = release.version,
        info = release.info,
        releaseLink = release.releaseLink,
        isInstalled = release.version.removePrefix("v") ==
            BuildConfig.VERSION_NAME.substringBefore("-"),
    )

    @Immutable
    data class ReleaseItem(
        val version: String,
        val info: String,
        val releaseLink: String,
        val isInstalled: Boolean,
    )

    @Immutable
    data class State(
        val releases: ImmutableList<ReleaseItem> = persistentListOf(),
        val nextPage: Int = 1,
        val endReached: Boolean = false,
        val isLoading: Boolean = false,
        val hasError: Boolean = false,
    ) {
        val isInitialLoad: Boolean get() = releases.isEmpty() && isLoading
    }
}
