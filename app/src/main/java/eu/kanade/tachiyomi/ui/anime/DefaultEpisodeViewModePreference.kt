// AM (EPISODE_VIEW_MODE) -->
package eu.kanade.tachiyomi.ui.anime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Presents [LibraryPreferences.showEpisodeThumbnailPreviews] and
 * [LibraryPreferences.hideEpisodeMetadata] - two independent Long preferences -
 * as a single combined Long preference, so the Appearance settings screen can
 * drive them from one three-option ListPreference (Simplified / Preview /
 * Minimal) using the same UI component as every other dropdown setting.
 *
 * The combined value is just the bitwise OR of both underlying values, since
 * they occupy non-overlapping bits (EPISODE_PREVIEWS_MASK and
 * EPISODE_METADATA_MASK) - masking the combined value against either mask
 * recovers that dimension's value independently.
 */
class DefaultEpisodeViewModePreference(
    private val libraryPreferences: LibraryPreferences,
) : Preference<Long> {

    override fun key(): String = "default_episode_view_mode_combined"

    override fun get(): Long {
        return libraryPreferences.showEpisodeThumbnailPreviews.get() or
            libraryPreferences.hideEpisodeMetadata.get()
    }

    override fun set(value: Long) {
        libraryPreferences.showEpisodeThumbnailPreviews.set(value and Anime.EPISODE_PREVIEWS_MASK)
        libraryPreferences.hideEpisodeMetadata.set(value and Anime.EPISODE_METADATA_MASK)
    }

    override fun isSet(): Boolean {
        return libraryPreferences.showEpisodeThumbnailPreviews.isSet() ||
            libraryPreferences.hideEpisodeMetadata.isSet()
    }

    override fun delete() {
        libraryPreferences.showEpisodeThumbnailPreviews.delete()
        libraryPreferences.hideEpisodeMetadata.delete()
    }

    override fun defaultValue(): Long {
        return libraryPreferences.showEpisodeThumbnailPreviews.defaultValue() or
            libraryPreferences.hideEpisodeMetadata.defaultValue()
    }

    override fun changes(): Flow<Long> {
        return combine(
            libraryPreferences.showEpisodeThumbnailPreviews.changes(),
            libraryPreferences.hideEpisodeMetadata.changes(),
        ) { previews, hideMetadata -> previews or hideMetadata }
    }

    override fun stateIn(scope: CoroutineScope): StateFlow<Long> {
        return changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}
// <-- AM (EPISODE_VIEW_MODE)
