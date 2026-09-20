package eu.kanade.tachiyomi.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.presentation.more.ReleaseNotesUiState
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Owns the release-notes fetch for [WhatsNewScreen].
 *
 * The fetch lives here rather than in the dialog's click handler so that the screen can be pushed
 * immediately and render its own loading and failure states. That matters in practice: the GitHub
 * API is unauthenticated and rate limited, so a failed load is a state users will actually hit.
 */
@AssistedInject
class WhatsNewScreenModel(
    @Assisted private val sinceVersion: String?,
    private val updateChecker: AppUpdateChecker,
) : ViewModel() {

    val state: StateFlow<ReleaseNotesUiState>
        field = MutableStateFlow<ReleaseNotesUiState>(ReleaseNotesUiState.Loading)

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sinceVersion: String?): WhatsNewScreenModel
    }

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        if (loadJob?.isActive == true) return

        loadJob = viewModelScope.launch {
            state.value = ReleaseNotesUiState.Loading
            try {
                val releases = updateChecker.getReleaseNotes(sinceVersion)
                state.value = if (releases.isEmpty()) {
                    ReleaseNotesUiState.Empty
                } else {
                    ReleaseNotesUiState.Loaded(
                        changelogInfo = releases.joinToString(SEPARATOR) { release ->
                            "## ${release.version}\n\n${release.info}"
                        },
                        latestVersion = releases.first().version,
                        releaseLink = releases.first().releaseLink,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                state.value = ReleaseNotesUiState.Error
            }
        }
    }

    companion object {
        private const val SEPARATOR = "\n\n---\n\n"
    }
}
