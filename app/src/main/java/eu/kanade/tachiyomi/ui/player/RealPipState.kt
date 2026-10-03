package eu.kanade.tachiyomi.ui.player

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

// AM (REAL_PIP_CONTROLS_LEAK_FIX) -->
// Whether the hosting Activity is currently in real (system) PiP.
//
// The exact analogue of isDummyPipActive, for the window the system owns. Both of the
// mechanisms that keep the player's controls out of the mini player need one boolean
// to key off, and this is real PiP's.
//
// False for any host that does not provide it, so the standalone PlayerActivity player
// and previews are unchanged.
// <-- AM (REAL_PIP_CONTROLS_LEAK_FIX)
val LocalInRealPip: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
