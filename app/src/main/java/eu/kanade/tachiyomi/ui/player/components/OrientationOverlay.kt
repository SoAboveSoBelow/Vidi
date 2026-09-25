package eu.kanade.tachiyomi.ui.player.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.navigator.currentOrThrow

@Composable
fun OrientationOverlay(orientation: Int?) {
    val activity = LocalActivity.currentOrThrow

    LaunchedEffect(orientation) {
        // AM (PIP_DROPS_ORIENTATION_LOCK) -->
        // Never while pinned. This effect re-runs whenever PlayerScreen
        // re-enters composition, not only when the orientation value changes -
        // and entering PIP is one of those moments. An orientation request
        // arriving as the task is pinned forces a display-rotation
        // re-evaluation and a configuration change on the task, in the middle
        // of the launcher snapshotting it. MainActivity restores the session's
        // orientation on PIP exit.
        if (activity.isInPictureInPictureMode) return@LaunchedEffect
        // <-- AM (PIP_DROPS_ORIENTATION_LOCK)
        if (orientation != null) {
            activity.requestedOrientation = orientation
        }
    }
}
