package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
// AM (LIBRARY_HEART_ICON)
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import tachiyomi.presentation.core.components.Badge

@Composable
internal fun InLibraryBadge(enabled: Boolean) {
    if (enabled) {
        Badge(
            // AM (LIBRARY_HEART_ICON): the heart the entry screen's own
            // add-to-library button uses, so library membership is said with one
            // icon everywhere rather than with a shelf here and a heart there -
            // and so it is not mistaken for the bookmark and fillermark badges,
            // which are a different thing about a different object.
            imageVector = Icons.Filled.Favorite,
        )
    }
}

// AM (MERGED_SOURCES) -->
/**
 * Marks a result that is a child of a merged entry in the library rather than a
 * library entry of its own. Deliberately a different icon from [InLibraryBadge]:
 * opening it leads to a plain entry with an empty heart, so showing the library
 * badge here would promise something the detail screen then contradicts.
 */
@Composable
internal fun InMergeBadge(enabled: Boolean) {
    if (enabled) {
        Badge(
            imageVector = Icons.AutoMirrored.Outlined.FormatListBulleted,
        )
    }
}
// <-- AM (MERGED_SOURCES)
