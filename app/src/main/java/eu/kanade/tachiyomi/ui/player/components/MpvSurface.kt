package eu.kanade.tachiyomi.ui.player.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import `is`.xyz.mpv.MPV
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// Reference: https://github.com/MakD/AFinity/blob/master/app/src/main/java/com/makd/afinity/ui/player/components/MpvSurface.kt
@Composable
fun MpvSurface(
    modifier: Modifier = Modifier,
    mpv: MPV,
    videoOutput: String,
    // AM (AUDIO_BLIP_FIX) -->
    onSurfaceAttachedChanged: (Boolean) -> Unit = {},
    // <-- AM (AUDIO_BLIP_FIX)
) {
    val decoderPreferences: DecoderPreferences = Injekt.get()

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            var hasAttachedSurfaceBefore = false

            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            mpv.setPropertyString("android-surface-size", "${width}x$height")
                        }

                        override fun surfaceCreated(holder: SurfaceHolder) {
                            mpv.attachSurface(holder.surface)
                            mpv.setOptionString("force-window", "yes")
                            // Force lighter "gpu" (not gpu-next) on reattach to cut
                            // reconfig cost/audio blip; use user's pref on first start.
                            mpv.setPropertyString(
                                "vo",
                                if (hasAttachedSurfaceBefore) "gpu" else videoOutput,
                            )
                            hasAttachedSurfaceBefore = true
                            mpv.setOptionString("vid", "auto")
                            mpv.setOptionString(
                                "hwdec",
                                if (decoderPreferences.tryHWDecoding.get()) "mediacodec,mediacodec-copy" else "no",
                            )
                            // Audio track re-selection after this reconfig is handled
                            // reactively in PlayerViewModel.onAudioTrackSelectChange.
                            // AM (AUDIO_BLIP_FIX) -->
                            onSurfaceAttachedChanged(true)
                            // <-- AM (AUDIO_BLIP_FIX)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // AM (AUDIO_BLIP_FIX) -->
                            // hwdec is deliberately NOT disabled here anymore. It used to be
                            // ("Plain mediacodec hwdec needs an attached Surface to init a new
                            // decoder session, so loading a fresh episode while backgrounded
                            // breaks unless hwdec is off"), but that unconditionally paid a
                            // heavyweight decoder-reinit cost (audible blip) on every single
                            // background transition, not just the rarer case of actually
                            // loading a new episode while backgrounded. PlayerViewModel.loadFile()
                            // now disables hwdec right before that specific operation instead,
                            // using isSurfaceAttached (set via the callback below) to know
                            // whether it's needed - so just continuing the current episode in
                            // the background no longer touches hwdec at all.
                            onSurfaceAttachedChanged(false)
                            // <-- AM (AUDIO_BLIP_FIX)
                            // Do NOT set vid="no": mpv runs a near-synchronous "anything
                            // selected?" check on file open, and only vid="auto" satisfies
                            // it in time (confirmed via logcat - root cause of "No video
                            // or audio streams selected" on new loads). vo="null" alone
                            // already blocks real rendering/GPU work, which is the goal.
                            mpv.setPropertyString("vo", "null")
                            mpv.setPropertyString("force-window", "no")
                            mpv.detachSurface()
                        }
                    },
                )
            }
        },
    )
}
