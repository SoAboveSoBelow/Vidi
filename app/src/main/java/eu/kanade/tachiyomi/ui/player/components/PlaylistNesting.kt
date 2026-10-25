// AM (QUEUE_GROUPED_PLAYLIST) -->
package eu.kanade.tachiyomi.ui.player.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * Marks a playlist row as living inside its parent, without indenting it far
 * enough to matter.
 *
 * The dialog is 80% of a phone's width with a scroller down one side, so
 * depth-by-indent spends the one thing the list is short of: every level took
 * a chunk out of the episode titles, which are what the list is for. A rule
 * drawn down the left of the nested rows says the same thing for a fraction of
 * the width - the rows read as one block hanging off the row above, the way a
 * drawer reads as part of the cabinet - and the small step that remains is
 * only enough to keep the text clear of the rule.
 */
@Composable
fun Modifier.playlistNesting(depth: Int): Modifier {
    if (depth <= 0) return this
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return this
        .drawBehind {
            repeat(depth) { level ->
                val x = (RULE_INSET + RULE_STEP * level).toPx()
                drawLine(
                    color = color,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = RULE_WIDTH.toPx(),
                    alpha = RULE_ALPHA,
                )
            }
        }
        .padding(start = RULE_INSET + RULE_STEP * (depth - 1) + RULE_GAP)
}

private val RULE_INSET = 6.dp
private val RULE_STEP = 8.dp
private val RULE_GAP = 8.dp
private val RULE_WIDTH = 1.dp
private const val RULE_ALPHA = 0.4f
// <-- AM (QUEUE_GROUPED_PLAYLIST)
