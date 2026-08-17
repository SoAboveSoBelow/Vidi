package eu.kanade.tachiyomi.ui.player.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import animiru.domain.player.service.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// Reference: https://github.com/MakD/AFinity/blob/master/app/src/main/java/com/makd/afinity/ui/player/components/MpvSurface.kt
@Composable
fun MpvSurface(
    modifier: Modifier = Modifier,
    player: MPVPlayer,
    videoOutput: String,
    // AM (AUDIO_BLIP_FIX) -->
    onSurfaceAttachedChanged: (Boolean) -> Unit = {},
    // <-- AM (AUDIO_BLIP_FIX)
) {
    val decoderPreferences: DecoderPreferences = Injekt.get()
    val mpv = player.mpv

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
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
                                if (player.hasAttachedSurfaceBefore) "gpu" else videoOutput,
                            )
                            mpv.setOptionString("vid", "auto")
                            // AM (HWDEC_REATTACH_FIX) -->
                            // Only set on the very first attach, not on every reattach.
                            // Setting "hwdec" isn't a no-op even when the value is
                            // unchanged - it forces MediaCodec to tear down and
                            // reinitialize its decoder session, which needs a fresh
                            // keyframe to resume cleanly. Without one, playback can get
                            // stuck waiting for frames that never arrive - "buffers
                            // indefinitely" after a reattach (e.g. returning from the
                            // app-lock biometric prompt, including via notification
                            // reopen). The AUDIO_BLIP_FIX comment below already
                            // recognized hwdec-toggling as disruptive enough to avoid
                            // touching on detach; this was the same problem on the
                            // reattach side, just missed. player.hasAttachedSurfaceBefore
                            // (not a local var here) is what makes this correct even
                            // across a fresh Composable/Activity instance for the same
                            // underlying player - see its doc comment on MPVPlayer.
                            if (!player.hasAttachedSurfaceBefore) {
                                mpv.setOptionString(
                                    "hwdec",
                                    if (decoderPreferences.tryHWDecoding.get()) "mediacodec,mediacodec-copy" else "no",
                                )
                            }
                            // <-- AM (HWDEC_REATTACH_FIX)
                            player.hasAttachedSurfaceBefore = true
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
                            // AM (HOT_VIDEO_BACKGROUND) -->
                            // Swap onto the always-present dummy surface instead of nulling vo
                            // and fully detaching - see MPVPlayer.ensureDummySurface()'s doc
                            // comment for why: doing that while a video is actively mid-playback
                            // (not mid-load) is an abrupt stop of the whole pipeline mid-stream,
                            // and a plausible source of it never cleanly resuming. vo, hwdec, and
                            // vid are deliberately left completely untouched here now - the whole
                            // point is that backgrounding no longer touches any of them, only
                            // where the frames land. force-window also stays "yes" permanently
                            // after the first attach, for the same reason: a window/surface
                            // always conceptually exists now, real or dummy.
                            mpv.attachSurface(player.ensureDummySurface())
                            // <-- AM (HOT_VIDEO_BACKGROUND)
                        }
                    },
                )
            }
        },
    )
}
