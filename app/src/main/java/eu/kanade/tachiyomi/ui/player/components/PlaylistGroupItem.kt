// AM (QUEUE_GROUPED_PLAYLIST) -->
package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.nowPlayingBackground
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.selectedBackground

/**
 * A collapsible row in the player playlist: one queued source, or one season
 * of one.
 *
 * The row and the chevron do different things on purpose. Tapping the row
 * plays - it is a thing to watch, and the first episode of the run is what
 * "play this source" means. Expanding is the chevron's job alone, in its own
 * touch target at the end of the row, so reaching for the list of episodes can
 * never start playback by accident.
 *
 * [onToggle] null is a row with nothing to expand - a source queued with a
 * single episode - which draws no chevron rather than a disabled one.
 */
@Composable
fun PlaylistGroupItem(
    title: String,
    subtitle: String?,
    depth: Int,
    isCurrent: Boolean,
    isSelected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // AM (OPEN_ENTRY_ON_DOUBLE_TAP) -->
    /**
     * Opens the entry this row stands for. Null while selecting or reordering,
     * and deliberately nullable rather than a no-op: a non-null handler makes
     * every single tap wait out the double-tap timeout before it can play,
     * and playing is what this row is mostly for.
     */
    onDoubleClick: (() -> Unit)? = null,
    // <-- AM (OPEN_ENTRY_ON_DOUBLE_TAP)
    onToggle: (() -> Unit)?,
    // AM (QUEUE_REORDER)
    reorderHandleModifier: Modifier? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(isSelected)
            // The same tint the episode rows use for the playing episode, so a
            // collapsed source still says the playing episode is inside it.
            .nowPlayingBackground(isCurrent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                // AM (OPEN_ENTRY_ON_DOUBLE_TAP)
                onDoubleClick = onDoubleClick,
            )
            // Depth is drawn as a rule down the left rather than as indent -
            // see playlistNesting.
            .playlistNesting(depth)
            .padding(
                start = MaterialTheme.padding.small,
                top = MaterialTheme.padding.extraSmall,
                bottom = MaterialTheme.padding.extraSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reorderHandleModifier != null) {
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = stringResource(AMMR.strings.am_episode_drag_handle),
                modifier = reorderHandleModifier
                    .size(48.dp)
                    .padding(12.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                // A nested group also reads as nested by being set smaller
                // than the row it hangs off, which costs no width at all.
                style = if (depth > 0) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                fontStyle = if (isCurrent) FontStyle.Italic else FontStyle.Normal,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
        }

        if (onToggle != null) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) AMMR.strings.am_action_collapse else AMMR.strings.am_action_expand,
                    ),
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            // Keeps the titles of expandable and non-expandable rows on the
            // same left-to-right rhythm instead of letting one run wider.
            Spacer48()
        }
    }
}

@Composable
private fun Spacer48() {
    Row(modifier = Modifier.width(48.dp)) {}
}

// <-- AM (QUEUE_GROUPED_PLAYLIST)
