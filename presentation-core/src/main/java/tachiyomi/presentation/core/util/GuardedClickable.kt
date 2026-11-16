package tachiyomi.presentation.core.util

import androidx.compose.foundation.Indication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration

// AM (DOUBLE_TAP_TAIL_GUARD) -->
/**
 * [combinedClickable] with the tail of a double tap absorbed.
 *
 * Compose resolves a triple tap as a double tap followed by a fresh single tap: the
 * third tap begins a new gesture and commits as [onClick] one double-tap timeout later.
 * Wherever a double tap does something the single tap does not, a slipped finger
 * therefore performs both.
 *
 * The window is the platform's double-tap timeout doubled, which is what it takes to
 * span a third tap landing at the end of the timeout plus its own timeout before it
 * commits.
 *
 * [onClick] is left untouched where there is no [onDoubleClick]: with no double tap
 * there is no tail, and a single tap commits immediately. That fast path matters on a
 * transport control, where a non-null double-tap handler makes every single tap wait
 * out the timeout and the delay is felt at once.
 *
 * Lifted here from the library grid so the player's controls share it rather than
 * growing a copy. A caller that can test state instead - a dialog the double tap
 * opened is already in composition by the time the third tap runs - should keep doing
 * that; it is exact rather than timing-based.
 */
@Composable
fun Modifier.guardedCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
): Modifier {
    if (onDoubleClick == null) {
        return combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            indication = indication,
        )
    }

    val tailWindowMs = LocalViewConfiguration.current.doubleTapTimeoutMillis * 2
    // A plain holder rather than a State: the guard is read inside the gesture
    // callbacks, never during composition, so writing it has nothing to redraw.
    val guard = remember { DoubleTapTailGuard() }

    return combinedClickable(
        enabled = enabled,
        onClick = {
            if (android.os.SystemClock.uptimeMillis() - guard.lastDoubleTapAt >= tailWindowMs) {
                onClick()
            }
        },
        onLongClick = onLongClick,
        onDoubleClick = {
            guard.lastDoubleTapAt = android.os.SystemClock.uptimeMillis()
            onDoubleClick()
        },
        interactionSource = interactionSource,
        indication = indication,
    )
}

private class DoubleTapTailGuard {
    var lastDoubleTapAt = 0L
}
// <-- AM (DOUBLE_TAP_TAIL_GUARD)
