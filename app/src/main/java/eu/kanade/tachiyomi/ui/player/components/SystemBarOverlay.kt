package eu.kanade.tachiyomi.ui.player.components

import android.annotation.SuppressLint
import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cafe.adriel.voyager.navigator.currentOrThrow

// From https://github.com/MakD/AFinity/blob/master/app/src/main/java/com/makd/afinity/ui/player/utils/PlayerSystemBarsController.kt
// AM (UNIFIED_NAV_BAR_VISIBILITY) -->
// Was its own WindowInsetsController show()/hide() calls here, duplicating
// (and racing with) PlayerActivity.onStart()'s. Both now go through
// PlayerActivity.applySystemBarVisibility() - see that function's doc comment.
// <-- AM (UNIFIED_NAV_BAR_VISIBILITY)
// AM (PLAYER_SCREEN_HOSTING_ACTIVITY_AGNOSTIC_FIX) -->
// Was `LocalActivity.currentOrThrow as PlayerActivity`, calling that specific
// class's applySystemBarVisibility() instance method - broke with a
// ClassCastException the moment anything other than PlayerActivity hosted
// PlayerScreen (confirmed live on-device 2026-09-06, pushing
// PlayerVoyagerScreenSpike onto MainActivity's Navigator). The logic itself
// was never actually PlayerActivity-specific - it only ever needed a Window,
// which any Activity has - so it's a free function here instead, taking
// whatever Activity is actually hosting this composition.
// PlayerActivity.applySystemBarVisibility() now just delegates to this same
// function, so its own onStart()/other callers keep working unchanged.
// <-- AM (PLAYER_SCREEN_HOSTING_ACTIVITY_AGNOSTIC_FIX)
fun applyPlayerSystemBarVisibility(activity: Activity, show: Boolean) {
    val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (show) {
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    } else {
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@SuppressLint("WrongConstant")
@Composable
fun SystemBarOverlay(showStatusBar: Boolean) {
    val activity = LocalActivity.currentOrThrow

    LaunchedEffect(showStatusBar) {
        applyPlayerSystemBarVisibility(activity, show = showStatusBar)
    }

    DisposableEffect(Unit) {
        onDispose {
            applyPlayerSystemBarVisibility(activity, show = true)
        }
    }
}
