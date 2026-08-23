package eu.kanade.tachiyomi.ui.player.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import animiru.domain.player.service.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// Reference: https://github.com/MakD/AFinity/blob/master/app/src/main/java/com/makd/afinity/ui/player/components/MpvSurface.kt
// AM (SURFACE_TO_TEXTURE_VIEW_FIX) -->
// Switched from SurfaceView to TextureView. First attempted, then reverted, when
// this session mistakenly attributed the reattach blip to SurfaceView alone - a
// log at the time showed "creating a new TextureView" firing twice, which
// actually meant the whole Activity was being destroyed and recreated (a
// separate bug, since fixed - see PIP_BACKGROUND_PLAY_MODE_FIX), confounding
// that test entirely. Once the Activity-recreation bug was fixed and the same
// repro was re-run against plain SurfaceView, the blip still occurred - "Probing
// for best GPU context" and an audio underrun still fired on every PIP-hide/
// reopen, on the SAME, confirmed-surviving Activity instance (verified via
// identityHashCode logging staying constant across the whole repro). That
// isolates the real, remaining cause correctly this time: SurfaceView's surface
// itself is torn down and recreated on every visibility change, independent of
// whether the Activity survives - exactly why ensureDummySurface()'s dummy-
// surface-swap architecture exists in the first place, to have something to
// fall back to once the real one is gone. TextureView's surface is part of the
// normal View hierarchy's own rendering rather than a separate compositor
// window, so it generally is NOT torn down just for a visibility change -
// attachSurface() should only need to run once, for real, this time.
// The dummy-surface fallback (ensureDummySurface()) is kept as a safety net for
// onSurfaceTextureDestroyed, since it CAN still fire for a genuine Activity
// destruction even if it doesn't fire for ordinary backgrounding.
//
// The real, honest tradeoff: TextureView is composited through the normal GPU
// pipeline each frame rather than being hardware-overlaid directly the way
// SurfaceView is - a real, permanent per-frame cost, not a rounding error, even
// though it's usually imperceptible for video playback on modern hardware. This
// still needs actual device testing to confirm both that it fixes the blip now
// that the confounding Activity-recreation bug is gone, and that it doesn't
// introduce a different, worse regression - not something to trust as correct
// on the strength of this reasoning alone, especially given this exact change
// was tried and looked inconclusive once already this session.
// <-- AM (SURFACE_TO_TEXTURE_VIEW_FIX)
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
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        mpv.attachSurface(Surface(surfaceTexture))
                        mpv.setPropertyString("android-surface-size", "${width}x$height")
                        mpv.setOptionString("force-window", "yes")
                        // AM (VO_REATTACH_FIX) -->
                        // Mirrors HWDEC_REATTACH_FIX's own finding just below:
                        // setting an mpv property isn't a no-op just because the
                        // value happens to be unchanged, it can still force a
                        // reconfigure. Kept even after the TextureView switch as a
                        // second layer of protection - this callback is expected to
                        // fire only once per genuine session now, but if it's ever
                        // called again for any reason, this still avoids a redundant
                        // vo re-set rather than assuming it can't happen.
                        // <-- AM (VO_REATTACH_FIX)
                        when {
                            !player.hasAttachedSurfaceBefore -> {
                                mpv.setPropertyString("vo", videoOutput)
                            }
                            !player.hasSetLighterVoOnReattach -> {
                                mpv.setPropertyString("vo", "gpu")
                                player.hasSetLighterVoOnReattach = true
                            }
                        }
                        // AM (VID_REATTACH_FIX) -->
                        // Same reasoning as vo above - kept as a second layer of
                        // protection even though this should now only run once.
                        // <-- AM (VID_REATTACH_FIX)
                        if (!player.hasAttachedSurfaceBefore) {
                            mpv.setOptionString("vid", "auto")
                        }
                        // AM (HWDEC_REATTACH_FIX) -->
                        // Only set on the very first attach, not on every reattach.
                        // Setting "hwdec" isn't a no-op even when the value is
                        // unchanged - it forces MediaCodec to tear down and
                        // reinitialize its decoder session, which needs a fresh
                        // keyframe to resume cleanly. Kept as a second layer of
                        // protection past the TextureView switch, same reasoning as
                        // vo/vid above.
                        // <-- AM (HWDEC_REATTACH_FIX)
                        if (!player.hasAttachedSurfaceBefore) {
                            mpv.setOptionString(
                                "hwdec",
                                if (decoderPreferences.tryHWDecoding.get()) "mediacodec,mediacodec-copy" else "no",
                            )
                        }
                        player.hasAttachedSurfaceBefore = true
                        // Audio track re-selection after this reconfig is handled
                        // reactively in PlayerViewModel.onAudioTrackSelectChange.
                        // AM (AUDIO_BLIP_FIX) -->
                        onSurfaceAttachedChanged(true)
                        // <-- AM (AUDIO_BLIP_FIX)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        mpv.setPropertyString("android-surface-size", "${width}x$height")
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        // AM (AUDIO_BLIP_FIX) -->
                        // See SURFACE_TO_TEXTURE_VIEW_FIX's own doc comment above -
                        // expected to be the rare exception now (a genuine Activity
                        // destruction), not the routine background/PIP-hide path it
                        // used to be with SurfaceView.
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
                        // Returning true hands ownership of releasing this SurfaceTexture
                        // to the TextureView itself, the standard/expected contract - mpv
                        // has already switched away to the dummy surface by this point.
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        // Called every frame; nothing to do here.
                    }
                }
            }
        },
    )
}
