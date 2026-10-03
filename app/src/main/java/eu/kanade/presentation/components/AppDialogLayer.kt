package eu.kanade.presentation.components

// AM (APP_DIALOG_LAYER) -->
// Dialogs that live INSIDE the composition instead of in their own platform
// window, so the player can be drawn above them.
//
// Why this exists: a Compose Dialog (and so Material3's AlertDialog, which wraps
// one) does not render in the Activity's content view at all - it attaches its own
// window, which sits above that whole view by construction. The player lives in
// the content view, so no amount of composition order, elevation or z-index can
// put it above an open dialog. Real PiP does not have the problem because the
// system owns its window; an in-app floating player does.
//
// The alternative was a TYPE_APPLICATION_OVERLAY window for the player, which
// needs the "Display over other apps" permission and a path for when it is
// refused. Bringing the dialogs down into the composition needs no permission and
// nothing that can race.
//
// Ordering is decided once, here, rather than per call site. The host is composed
// at one point in MainActivity, which fixes the layering for everything:
//
//   1. app content (the Navigator)
//   2. app dialogs and sheets   <- this host
//   3. the player, fullscreen or dummy pip
//   4. the player's own sheets, panels and dialogs
//
// Within this layer, entries draw in the order they were opened, so the last
// dialog opened is on top - the same as the window behaviour it replaces, which is
// what keeps dialog-over-dialog from becoming a decision at every call site.
//
// Back handling and the scrim are the host's job, once, instead of each dialog
// inheriting them from the window it no longer has.
// <-- AM (APP_DIALOG_LAYER)

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Null outside a host - [AlertDialog] then falls back to the platform dialog, so a
 * screen hosted somewhere without a layer (a standalone Activity, a preview) keeps
 * working unchanged.
 */
val LocalAppDialogLayer: ProvidableCompositionLocal<AppDialogLayer?> = compositionLocalOf { null }

/** The open entries, in the order they were opened. */
class AppDialogLayer {
    internal class Entry(val content: @Composable () -> Unit)

    internal val entries = mutableStateListOf<Entry>()

    val isEmpty: Boolean get() = entries.isEmpty()
}

@Composable
fun rememberAppDialogLayer(): AppDialogLayer = remember { AppDialogLayer() }

/**
 * Draws the layer's open dialogs. Compose this ABOVE the app's content and BELOW
 * the player - that placement is the whole mechanism.
 */
@Composable
fun AppDialogHost(layer: AppDialogLayer) {
    // Nothing in the tree at all when no dialog is open, so this costs nothing and
    // cannot intercept a touch.
    if (layer.entries.isEmpty()) return
    Box(Modifier.fillMaxSize()) {
        layer.entries.forEach { entry ->
            key(entry) { entry.content() }
        }
    }
}

/**
 * Registers arbitrary content as an entry in the layer, for callers that bring their
 * own visuals - [AdaptiveSheet] and anything else that was its own Dialog rather
 * than an AlertDialog.
 *
 * Supplies what the window used to: a back handler, and a full-size box so the
 * content lays out against the screen exactly as it did inside one. The scrim and
 * dismiss-on-outside are NOT supplied here - a sheet draws its own - so this stays a
 * placement mechanism and nothing more.
 */
@Composable
fun AppDialogLayerEntry(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val layer = LocalAppDialogLayer.current
    if (layer == null) {
        content()
        return
    }
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentContent by rememberUpdatedState(content)
    DisposableEffect(layer) {
        val entry = AppDialogLayer.Entry {
            BackHandler(onBack = currentOnDismiss)
            Box(Modifier.fillMaxSize()) { currentContent() }
        }
        layer.entries.add(entry)
        onDispose { layer.entries.remove(entry) }
    }
}

/**
 * Drop-in replacement for `androidx.compose.material3.AlertDialog` that renders
 * into [LocalAppDialogLayer] instead of its own window.
 *
 * Same parameter names and order as the Material3 one, so converting a call site
 * is a change of import and nothing else. With no layer present it delegates to
 * the real thing.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val layer = LocalAppDialogLayer.current
    if (layer == null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            title = title,
            text = text,
        )
        return
    }

    // The content is re-read through rememberUpdatedState so an entry registered
    // once still renders the caller's CURRENT lambdas - the entry outlives
    // individual recompositions of the caller.
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentConfirm by rememberUpdatedState(confirmButton)
    val currentDismissButton by rememberUpdatedState(dismissButton)
    val currentTitle by rememberUpdatedState(title)
    val currentText by rememberUpdatedState(text)
    val currentModifier by rememberUpdatedState(modifier)

    DisposableEffect(layer) {
        val entry = AppDialogLayer.Entry {
            AppDialogScrimAndSurface(
                onDismissRequest = currentOnDismiss,
                modifier = currentModifier,
                title = currentTitle,
                text = currentText,
                dismissButton = currentDismissButton,
                confirmButton = currentConfirm,
            )
        }
        layer.entries.add(entry)
        onDispose { layer.entries.remove(entry) }
    }
}

/**
 * The Material3 alert-dialog visuals, rebuilt because the real ones are only
 * reachable through a platform-window dialog.
 *
 * Paddings and the width bound follow the Material3 dialog spec, so a converted
 * dialog looks the same as the one it replaces.
 */
@Composable
private fun AppDialogScrimAndSurface(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
) {
    // One BackHandler per entry: the topmost entry is the last one composed, so its
    // handler is the innermost and wins, which reproduces the window behaviour
    // where back reaches the frontmost dialog.
    BackHandler(onBack = onDismissRequest)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            // Tap-outside-to-dismiss, matching the platform dialog's default. Also
            // swallows every touch so the content behind an open dialog cannot be
            // used, which the window gave for free.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismissRequest() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                // Consumes taps on the dialog itself so they do not reach the
                // dismiss-on-outside handler above.
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                if (title != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    ) {
                        androidx.compose.material3.ProvideTextStyle(
                            MaterialTheme.typography.headlineSmall,
                        ) {
                            Box(Modifier.fillMaxWidth()) { title() }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (text != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        androidx.compose.material3.ProvideTextStyle(
                            MaterialTheme.typography.bodyMedium,
                        ) {
                            Box(Modifier.fillMaxWidth()) { text() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(Modifier.width(8.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}

private val SCRIM_COLOR = Color.Black.copy(alpha = 0.32f)
