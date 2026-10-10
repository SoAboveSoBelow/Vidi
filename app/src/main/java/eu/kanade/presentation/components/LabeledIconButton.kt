// AM (ICON_ACTION_LABELS) -->
package eu.kanade.presentation.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * An icon button that says what it does when held.
 *
 * An icon is only worth the space it saves if the user can find out what it
 * means, and on touch there is nowhere for a label to live until it is asked
 * for. Holding the button is that ask: the same gesture the app bar's actions
 * already answer, with the same plain tooltip, so one habit covers both.
 *
 * [label] is the whole contract - it is the tooltip, and it is the content
 * description, so the button reads the same to the screen reader as it does to
 * the eye. A button whose label would be a guess should stay a text button.
 */
@Composable
fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    anchor: TooltipAnchorPosition = TooltipAnchorPosition.Above,
) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(anchor),
        tooltip = { PlainTooltip { Text(text = label) } },
        state = rememberTooltipState(),
        modifier = modifier,
        // Taking focus would move it off whatever the user was on just to show a
        // label, which is not what holding a button asks for.
        focusable = false,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                tint = tint ?: LocalContentColor.current,
                contentDescription = label,
            )
        }
    }
}
// <-- AM (ICON_ACTION_LABELS)
