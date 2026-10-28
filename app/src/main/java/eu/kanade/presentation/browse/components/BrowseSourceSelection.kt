// AM (BROWSE_SELECTION) -->
package eu.kanade.presentation.browse.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
// AM (LIBRARY_HEART_ICON)
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import eu.kanade.presentation.anime.components.BottomActionBar
import eu.kanade.presentation.anime.components.BottomActionItem
import eu.kanade.presentation.anime.components.BottomActionPlacement
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Browse's counterpart to the library's selection toolbar.
 *
 * Deliberately barer than that one: select-all and invert have nothing to
 * operate on here. The result list is a pager over an endless remote catalogue,
 * so "all" is only ever "all that happen to be loaded", which is not a thing a
 * user can mean.
 */
@Composable
fun BrowseSourceSelectionToolbar(
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        isActionMode = true,
        onCancelActionMode = onClickUnselectAll,
    )
}

/**
 * The bottom bar for a browse selection.
 *
 * One action for library membership rather than separate add and remove
 * buttons: which of those a mixed selection needs is exactly what the
 * category dialog's tri-state already expresses, and asking it twice - once
 * as a choice of button and again inside the dialog - is how the two drift
 * apart. See BrowseSourceViewModel.openChangeCategoryDialog.
 */
@Composable
fun BrowseSourceSelectionBottomBar(
    visible: Boolean,
    onChangeCategoryClicked: () -> Unit,
    // AM (PLAYBACK_QUEUE)
    onAddToQueueClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = listOf(
        BottomActionItem(
            key = "move_category",
            // AM (LIBRARY_HEART_ICON): "Set categories" describes the dialog
            // rather than the action - which is library membership, removal
            // included, and is the whole of what this button does here. So it
            // is labelled for the library, like the heart beside it.
            title = stringResource(MR.strings.label_library),
            // AM (LIBRARY_HEART_ICON): this button is library membership, not
            // tagging - the category dialog it opens is what adds and removes
            // the entry (see BrowseSourceViewModel.openChangeCategoryDialog). A
            // label icon said tagging, and said it in the same glyph fillermark
            // uses, so the one action in browse that puts an entry in the
            // library looked like neither. The heart is what that means
            // everywhere else, including the entry screen's own button.
            icon = Icons.Outlined.FavoriteBorder,
            onClick = onChangeCategoryClicked,
            placement = BottomActionPlacement.Auto,
        ),
        // AM (PLAYBACK_QUEUE)
        BottomActionItem(
            key = "add_to_queue",
            title = stringResource(AMMR.strings.am_action_add_to_queue),
            icon = ImageVector.vectorResource(R.drawable.ic_playlist_add_24dp),
            onClick = onAddToQueueClicked,
            placement = BottomActionPlacement.Auto,
        ),
    )

    BottomActionBar(
        visible = visible,
        actions = actions,
        modifier = modifier,
        enter = expandVertically(animationSpec = tween(delayMillis = 300)),
        exit = shrinkVertically(animationSpec = tween()),
        insetsModifier = Modifier.windowInsetsPadding(
            WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom),
        ),
    )
}
// <-- AM (BROWSE_SELECTION)
