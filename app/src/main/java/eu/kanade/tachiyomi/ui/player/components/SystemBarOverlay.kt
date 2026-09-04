package eu.kanade.tachiyomi.ui.player.components

import android.annotation.SuppressLint
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.ui.player.PlayerActivity

// From https://github.com/MakD/AFinity/blob/master/app/src/main/java/com/makd/afinity/ui/player/utils/PlayerSystemBarsController.kt
// AM (UNIFIED_NAV_BAR_VISIBILITY) -->
// Was its own WindowInsetsController show()/hide() calls here, duplicating
// (and racing with) PlayerActivity.onStart()'s. Both now go through
// PlayerActivity.applySystemBarVisibility() - see that function's doc comment.
// <-- AM (UNIFIED_NAV_BAR_VISIBILITY)
@SuppressLint("WrongConstant")
@Composable
fun SystemBarOverlay(showStatusBar: Boolean) {
    val activity = LocalActivity.currentOrThrow as PlayerActivity

    LaunchedEffect(showStatusBar) {
        activity.applySystemBarVisibility(show = showStatusBar)
    }

    DisposableEffect(Unit) {
        onDispose {
            activity.applySystemBarVisibility(show = true)
        }
    }
}
