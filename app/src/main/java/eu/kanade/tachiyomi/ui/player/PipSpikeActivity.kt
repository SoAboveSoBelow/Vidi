package eu.kanade.tachiyomi.ui.player

// AM (PIP_HANDOFF_SPIKE) -->
// Throwaway prototype for scoping doc work item 1 ("Prototype the surface
// handoff first") - see vidi-single-activity-scoping.md. Purpose: find out
// whether MpvSurface's existing persistentSurfaceTexture reattach (proven
// today only SEQUENTIALLY, across a dead-then-recreated Activity - see that
// property's own doc comment in MPVPlayer.kt) also works cleanly when a
// second, concurrently-alive Activity attaches to it while the first
// Activity hosting the player Screen is still alive, and how the visual
// handoff times against the real PIP transition animation. Deliberately NOT
// wired into any real trigger (Home/Recents/headphones) and NOT the target
// architecture's real PipActivity - that's work item 3, once this is
// derisked. Launch manually while a session is live in PlayerActivity:
//   adb shell am start -n xyz.Quickdev.Vidi.mi.dev.dev/eu.kanade.tachiyomi.ui.player.PipSpikeActivity
// (drop the trailing .dev for a release-variant test).
// <-- AM (PIP_HANDOFF_SPIKE)

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.tachiyomi.ui.player.components.MpvSurface
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat

class PipSpikeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val holder = PlayerMediaHolder.current
        if (holder == null || !holder.hasAdoptedPlayer) {
            logcat(LogPriority.WARN) {
                "PIP_HANDOFF_SPIKE no live PlayerMediaHolder/player - nothing to hand off, finishing"
            }
            Toast.makeText(this, "No live playback session to hand off", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val player = holder.player
        val videoOutput = if (appGraph.decoderPreferences.gpuNext.get()) "gpu-next" else "gpu"

        // Only meaningful on a genuinely fresh attach, which this spike should
        // never hit (see the null/hasAdoptedPlayer guard above) - logged so a
        // "true" here in logcat is an immediate signal something upstream
        // about the trigger timing/assumption above is wrong.
        logcat(LogPriority.INFO) {
            "PIP_HANDOFF_SPIKE onCreate: player=${System.identityHashCode(player)} " +
                "hasAttachedSurfaceBefore=${player.hasAttachedSurfaceBefore} " +
                "persistentSurfaceTexture=${player.persistentSurfaceTexture != null} " +
                "at=${SystemClock.elapsedRealtime()}"
        }

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                MpvSurface(
                    modifier = Modifier.fillMaxSize(),
                    player = player,
                    videoOutput = videoOutput,
                    onSurfaceAttachedChanged = { attached ->
                        logcat(LogPriority.INFO) {
                            "PIP_HANDOFF_SPIKE surface attached=$attached at=${SystemClock.elapsedRealtime()}"
                        }
                    },
                )
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        // AM (PIP_HANDOFF_SPIKE_TIMING_FIX) -->
        // v2 called this from onResume() guarded by
        // `lifecycle.currentState != Lifecycle.State.RESUMED` (copying
        // PlayerActivity.enterPipIfEligible()'s guard against its own
        // confirmed crash). That guard doesn't belong here: androidx's
        // Lifecycle dispatches ON_RESUME via ReportFragment slightly AFTER
        // Activity.onResume() returns, not synchronously inside it, so
        // lifecycle.currentState still read STARTED at that point and the
        // guard silently returned before ever calling enterSpikePip() - no
        // crash, no log line, PIP just never got requested. onPostResume()
        // is the framework's own guarantee that resume is fully complete, so
        // no extra guard is needed here.
        // <-- AM (PIP_HANDOFF_SPIKE_TIMING_FIX)
        enterSpikePip()
    }

    private fun enterSpikePip() {
        val builder = PictureInPictureParams.Builder()
            // Real aspect ratio / source-rect-hint wiring belongs to item 3's
            // actual PipActivity, once this spike has answered its one
            // question - fixed 16:9 is enough to observe transition timing.
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setTitle("PIP handoff spike")
        }

        logcat(LogPriority.INFO) {
            "PIP_HANDOFF_SPIKE requesting enterPictureInPictureMode at=${SystemClock.elapsedRealtime()}"
        }
        try {
            enterPictureInPictureMode(builder.build())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "PIP_HANDOFF_SPIKE enterPictureInPictureMode failed: $e" }
            Toast.makeText(this, "PIP entry failed - see logcat tag PIP_HANDOFF_SPIKE", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        logcat(LogPriority.INFO) {
            "PIP_HANDOFF_SPIKE onPictureInPictureModeChanged isInPip=$isInPictureInPictureMode " +
                "at=${SystemClock.elapsedRealtime()}"
        }
        if (!isInPictureInPictureMode) {
            // Dismissed (swipe-away/X) or never actually entered - this spike
            // hands nothing back to PlayerActivity, just clean up.
            finish()
        }
    }
}
