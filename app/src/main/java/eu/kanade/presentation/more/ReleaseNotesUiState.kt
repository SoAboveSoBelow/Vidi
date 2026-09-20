package eu.kanade.presentation.more

import androidx.compose.runtime.Immutable

/**
 * View state for a release-notes page.
 *
 * Lives here rather than inside a screen model because two screens render the same page: the
 * post-update What's new flow, which fetches, and the release history detail, which already has
 * its data and starts in [Loaded].
 */
@Immutable
sealed interface ReleaseNotesUiState {
    data object Loading : ReleaseNotesUiState
    data object Empty : ReleaseNotesUiState
    data object Error : ReleaseNotesUiState

    @Immutable
    data class Loaded(
        val changelogInfo: String,
        val latestVersion: String,
        val releaseLink: String,
    ) : ReleaseNotesUiState
}
