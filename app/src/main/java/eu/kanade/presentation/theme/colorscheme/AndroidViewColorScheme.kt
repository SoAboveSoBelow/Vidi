// AM (MERGE_SETTINGS) -->
// Ported from Komikku (eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme),
// reduced to the members the merge settings views use.
package eu.kanade.presentation.theme.colorscheme

import android.content.res.ColorStateList
import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/** Bridges the Compose color scheme into the Android views hosted by AndroidView. */
class AndroidViewColorScheme(
    colorScheme: ColorScheme,
) {
    @ColorInt val primary: Int = colorScheme.primary.toArgb()

    @ColorInt val onPrimary: Int = colorScheme.onPrimary.toArgb()

    @ColorInt val secondary: Int = colorScheme.secondary.toArgb()

    @ColorInt val surface: Int = colorScheme.surface.toArgb()

    @ColorInt val onSurface: Int = colorScheme.onSurface.toArgb()

    @ColorInt val onSurfaceVariant: Int = colorScheme.onSurfaceVariant.toArgb()

    @ColorInt val surfaceContainerHighest: Int = colorScheme.surfaceContainerHighest.toArgb()

    @ColorInt
    val textColor: Int = onSurfaceVariant

    @ColorInt
    val dropdownBgColor: Int = surfaceContainerHighest

    @ColorInt
    val surfaceElevation = colorScheme.surfaceColorAtElevation(4.dp).toArgb()

    /* MaterialSwitch */
    val trackTintList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        ),
        intArrayOf(
            primary,
            surface,
        ),
    )

    val thumbTintList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        ),
        intArrayOf(
            onPrimary,
            onSurfaceVariant,
        ),
    )

    val imageButtonTintList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        ),
        intArrayOf(
            primary,
            primary,
            primary,
        ),
    )
}
// <-- AM (MERGE_SETTINGS)
