package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import animiru.domain.player.model.VideoAspect
import animiru.domain.player.model.VideoAspectOverride
import eu.kanade.presentation.player.components.PlayerSheet
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// AM (ASPECT_OVERRIDE_PICKER) -->
/**
 * Two separate questions, deliberately presented as two.
 *
 * Fill mode is how the picture sits on this screen - a viewing preference, and the
 * persistent counterpart to a pinch, which resets each episode. The aspect ratio
 * below it is a repair for a file whose own metadata is wrong, which is why it
 * names real ratios rather than offering to stretch to whatever panel you happen to
 * be holding.
 */
@Composable
fun AspectSheet(
    selectedAspect: VideoAspect,
    onSelectAspect: (VideoAspect) -> Unit,
    selectedOverride: VideoAspectOverride,
    onSelectOverride: (VideoAspectOverride) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSheet(onDismissRequest = onDismissRequest, modifier = modifier) {
        Column(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
        ) {
            SheetHeading(stringResource(AYMR.strings.player_aspect_fill_mode))
            listOf(VideoAspect.Fit, VideoAspect.Crop).forEach { aspect ->
                SelectableRow(
                    title = stringResource(aspect.titleRes),
                    selected = aspect == selectedAspect,
                    onClick = { onSelectAspect(aspect) },
                )
            }

            SheetHeading(stringResource(AYMR.strings.player_aspect_override))
            Text(
                text = stringResource(AYMR.strings.player_aspect_override_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
            )
            VideoAspectOverride.entries.forEach { override ->
                SelectableRow(
                    title = stringResource(override.titleRes),
                    selected = override == selectedOverride,
                    onClick = { onSelectOverride(override) },
                )
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = MaterialTheme.padding.medium),
    )
}

@Composable
private fun SelectableRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            modifier = Modifier.size(20.dp),
        )
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}
// <-- AM (ASPECT_OVERRIDE_PICKER)
