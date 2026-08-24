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
// Uses TextureView rather than SurfaceView: TextureView's surface is part of
// the normal View hierarchy's own rendering rather than a separate compositor
// window, so it isn't torn down just for a visibility change the way
// SurfaceView's is - attachSurface() only needs to run once, for real. The
// real, honest tradeoff: TextureView is composited through the normal GPU
// pipeline each frame rather than being hardware-overlaid directly, a real,
// permanent per-frame cost, though usually imperceptible for video playback
// on modern hardware.
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
                        // AM (PERSISTENT_SURFACE_ARCHITECTURE) -->
                        // Deliberately handled here, not in factory{}'s apply{}
                        // block - calling setSurfaceTexture() before this View is
                        // attached to a window doesn't prevent this callback from
                        // firing with a brand new default texture anyway. Instead:
                        // let this always-throwaway default texture get created as
                        // normal (Android silently releases it without even
                        // calling onSurfaceTextureDestroyed, per its own
                        // documented setSurfaceTexture() contract - harmless
                        // churn, mpv is never told about it), and swap to the real
                        // persistent one immediately, before doing anything else.
                        // Only reached on a genuinely fresh player - the very
                        // first, whole-player-lifetime attach - does this NOT find
                        // an existing persistent texture and go on to claim this
                        // one as the permanent one instead.
                        player.persistentSurfaceTexture?.let { existing ->
                            setSurfaceTexture(existing)
                            onSurfaceAttachedChanged(true)
                            return
                        }
                        player.persistentSurfaceTexture = surfaceTexture
                        // <-- AM (PERSISTENT_SURFACE_ARCHITECTURE)
                        mpv.attachSurface(Surface(surfaceTexture))
                        mpv.setPropertyString("android-surface-size", "${width}x$height")
                        mpv.setOptionString("force-window", "yes")
                        mpv.setPropertyString("vo", videoOutput)
                        mpv.setOptionString("vid", "auto")
                        mpv.setOptionString(
                            "hwdec",
                            if (decoderPreferences.tryHWDecoding.get()) "mediacodec" else "no",
                        )
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
                        onSurfaceAttachedChanged(false)
                        // <-- AM (AUDIO_BLIP_FIX)
                        // AM (PERSISTENT_SURFACE_ARCHITECTURE) -->
                        // This TextureView (a purely temporary, Activity-scoped
                        // display) is going away, but the SurfaceTexture itself is
                        // owned by the player (Service-scoped), not this View, and
                        // must survive this. Per Android's own documented contract
                        // for this callback's return value (confirmed against the
                        // actual framework source, not just the javadoc, since the
                        // two are worded ambiguously relative to each other):
                        // returning true means the TextureView releases/destroys
                        // it; returning false means the client keeps ownership and
                        // is responsible for eventually calling release() itself -
                        // this is the exact, official pattern AOSP's own "Grafika
                        // Double Decode" sample uses to keep a SurfaceTexture alive
                        // across Activity recreation. No mpv interaction here at
                        // all - that's the entire point: nothing about
                        // vo/wid/hwdec/vid ever needs to change again for the rest
                        // of this player's life, regardless of how many times this
                        // fires.
                        return false
                        // <-- AM (PERSISTENT_SURFACE_ARCHITECTURE)
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        // Called every frame; nothing to do here.
                    }
                }
            }
        },
        update = { textureView ->
            // AM (PERSISTENT_SURFACE_ARCHITECTURE) -->
            // Compose may reuse this exact TextureView instance across
            // recompositions rather than always calling factory again - re-affirm
            // the shared binding defensively if a persistent texture exists and
            // this View isn't already showing it.
            player.persistentSurfaceTexture?.let { existing ->
                if (textureView.getSurfaceTexture() !== existing) {
                    textureView.setSurfaceTexture(existing)
                }
            }
            // <-- AM (PERSISTENT_SURFACE_ARCHITECTURE)
        },
    )
}
