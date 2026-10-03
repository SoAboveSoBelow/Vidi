package eu.kanade.presentation.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.lifecycle.DisposableEffectIgnoringConfiguration
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.internal.BackHandler
import eu.kanade.presentation.util.ScreenTransition
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.presentation.core.components.AdaptiveSheet as AdaptiveSheetImpl

@OptIn(InternalVoyagerApi::class)
@Composable
fun NavigatorAdaptiveSheet(
    screen: Screen,
    enableSwipeDismiss: (Navigator) -> Boolean = { true },
    onDismissRequest: () -> Unit,
) {
    Navigator(
        screen = screen,
        content = { sheetNavigator ->
            AdaptiveSheet(
                onDismissRequest = onDismissRequest,
                enableSwipeDismiss = enableSwipeDismiss(sheetNavigator),
            ) {
                ScreenTransition(
                    navigator = sheetNavigator,
                    transition = {
                        fadeIn(animationSpec = tween(220, delayMillis = 90)) togetherWith
                            fadeOut(animationSpec = tween(90))
                    },
                )

                // AY -->
                BackHandler(
                    enabled = sheetNavigator.size > 1,
                    onBack = sheetNavigator::pop,
                )
                // <-- AY
            }

            // Make sure screens are disposed no matter what
            if (sheetNavigator.parent?.disposeBehavior?.disposeNestedNavigators == false) {
                DisposableEffectIgnoringConfiguration {
                    onDispose {
                        sheetNavigator.items
                            .asReversed()
                            .forEach(sheetNavigator::dispose)
                    }
                }
            }
        },
    )
}

/**
 * Sheet with adaptive position aligned to bottom on small screen, otherwise aligned to center
 * and will not be able to dismissed with swipe gesture.
 *
 * Max width of the content is set to 460 dp.
 */
@Composable
fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    enableSwipeDismiss: Boolean = true,
    // AM (TAG_LIMIT) -->
    dismissThreshold: Dp? = null,
    /**
     * Whether the sheet's base rises with the keyboard, for a sheet with a field in it:
     * its content would otherwise sit behind the keyboard, since the sheet is anchored
     * to the bottom of the screen rather than to the bottom of the visible area.
     *
     * Off by default. Turning it on also stops the window fitting the system decor, so
     * the sheet has to pad for the IME itself, which is exactly what it wants to do.
     */
    fitsKeyboard: Boolean = false,
    // <-- AM (TAG_LIMIT)
    content: @Composable () -> Unit,
) {
    val isTabletUi = isTabletUi()

    // AM (APP_DIALOG_LAYER) -->
    // Rendered into LocalAppDialogLayer when there is one, instead of a Dialog.
    //
    // This is the app's shared sheet primitive, so every sheet built on it comes
    // down into the composition together - which is what lets the player draw above
    // them. A Dialog is its own platform window and sits above the Activity's whole
    // content view by construction, so the player, which lives in that view, can
    // never be on top of one however it is composed or elevated.
    //
    // The content is identical either way: AdaptiveSheetImpl does the layout, the
    // swipe-dismiss and the scrim, and it does not care whether it is in a window.
    // What the window was providing is back-to-dismiss and the IME inset, and the
    // layer entry supplies both - see AppDialogLayer.
    val layer = LocalAppDialogLayer.current
    if (layer != null) {
        AppDialogLayerEntry(onDismissRequest = onDismissRequest) {
            AdaptiveSheetImpl(
                isTabletUi = isTabletUi,
                enableSwipeDismiss = enableSwipeDismiss,
                onDismissRequest = onDismissRequest,
                // fitsKeyboard asked the WINDOW not to fit the system decor so the
                // IME inset reached the content. In the composition there is no
                // window to ask: the inset is already live here, so the padding is
                // all that is needed and it means the same thing.
                modifier = if (fitsKeyboard) modifier.imePadding() else modifier,
                dismissThreshold = dismissThreshold,
            ) {
                content()
            }
        }
        return
    }
    // <-- AM (APP_DIALOG_LAYER)

    Dialog(
        onDismissRequest = onDismissRequest,
        // AM (TAG_LIMIT) -->
        properties = if (fitsKeyboard) keyboardDialogProperties else dialogProperties,
        // <-- AM (TAG_LIMIT)
    ) {
        AdaptiveSheetImpl(
            isTabletUi = isTabletUi,
            enableSwipeDismiss = enableSwipeDismiss,
            onDismissRequest = onDismissRequest,
            // AM (TAG_LIMIT) -->
            modifier = if (fitsKeyboard) modifier.imePadding() else modifier,
            dismissThreshold = dismissThreshold,
            // <-- AM (TAG_LIMIT)
        ) {
            content()
        }
    }
}

private val dialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = true,
)

// AM (TAG_LIMIT) -->
// decorFitsSystemWindows off so the IME inset reaches the content instead of the
// window being panned as a whole, which is what lets the sheet lift its own base.
private val keyboardDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)
// <-- AM (TAG_LIMIT)
