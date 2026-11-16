package eu.kanade.tachiyomi.ui.player.components

import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import animiru.domain.player.service.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.LocalDummyPipCornerRadiusPx
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import mihon.app.di.appGraph

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
    // AM (AN_OFFSCREEN_INSTANCE_FORCES_NO_WINDOW) -->
    /**
     * Whether this instance should hold a video output open with no video in it.
     *
     * force-window is why the live player keeps its output - and so its surface
     * and hwdec setup - across a file boundary instead of tearing it down per
     * episode, which PERSISTENT_SURFACE_ARCHITECTURE depends on.
     *
     * It is wrong for an instance that is being PREPARED. mpv honours it by
     * configuring a dummy 960x540 RGBA video the moment a surface attaches, well
     * before the real file opens, and that dummy frame lands in the instance's
     * persistent texture. The promote then hands that texture to the live
     * surface, so every prepared switch opened with a half-resolution blank
     * stretched across the screen until real frames replaced it - the blur at
     * the start of a switch, and new in this session because nothing but the
     * live instance ever had a surface before the prepared path existed.
     *
     * A prepared instance has nothing to keep a window for: it is off screen, it
     * is discarded if the preparation fails, and when it succeeds the promote is
     * what gives it the real surface.
     */
    forceWindow: Boolean = true,
    // <-- AM (AN_OFFSCREEN_INSTANCE_FORCES_NO_WINDOW)
    // AM (BROWSE_SCROLLER) -->
    /**
     * Hands out the TextureView, for a consumer that needs to read the frame
     * being displayed. mpv's own screenshot-raw reads the decoded frame, which
     * a hardware decoder need not expose; the view always has the composited
     * result. Null, and the view never escapes, for every other caller.
     */
    onViewAvailable: ((TextureView) -> Unit)? = null,
    // <-- AM (BROWSE_SCROLLER)
) {
    val context = LocalContext.current
    val decoderPreferences: DecoderPreferences = context.appGraph.decoderPreferences
    val mpv = player.mpv
    // AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX) -->
    // Non-null only when hosted inside the dummy pip (see DummyPipWindow.kt).
    // The Local itself never changes, so this read never recomposes us; the
    // radius floatValue is read in update{} below, which re-runs on change.
    // <-- AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX)
    val pipCornerRadiusPx = LocalDummyPipCornerRadiusPx.current

    // AM (ATTACH_IS_A_VIEW_PROPERTY) -->
    // "This player has a valid texture to render into" is a property of the
    // view, and it is published from one place here rather than from whichever
    // callback happens to run.
    //
    // It was announced only from onSurfaceTextureAvailable. That callback does
    // not fire when the view is handed a texture it already has: update{} below
    // calls setSurfaceTexture() for a player that already owns a persistent
    // texture, and Android then reports the adoption through
    // onSurfaceTextureSizeChanged instead - which said nothing about
    // attachment. So an instance holding a persistent texture went on rendering
    // correctly while never announcing that it had attached.
    //
    // Nothing noticed until something waited on the announcement. The prepared
    // episode switch waits for it before issuing its load (see
    // startQualitySwitch), and a prepared instance is a pooled one: the first
    // two switches get a freshly built instance with no texture and take the
    // Available path, while the third gets a recycled instance - which carries
    // its texture - and silently waited out QUALITY_SWITCH_TIMEOUT_MS before
    // falling back to loading in place. That 30s wait was the "buffering on the
    // third video", and it is why downloaded episodes were not exempt and why
    // the feed, which waits on nothing, was unaffected.
    val attachState = remember(player) { SurfaceAttachState() }
    fun publishAttach(view: TextureView, width: Int, height: Int) {
        val texture = view.surfaceTexture
        val attached = texture != null && player.hasAttachedSurfaceBefore
        if (texture != null && width > 0 && height > 0 &&
            (attachState.width != width || attachState.height != height)
        ) {
            attachState.width = width
            attachState.height = height
            // The adopted texture carries the previous view's buffer
            // dimensions, and nothing else updates them - see FIXED_SURFACE_SIZE.
            texture.setDefaultBufferSize(width, height)
            mpv.setPropertyString("android-surface-size", "${width}x$height")
        }
        if (attachState.attached == attached) return
        attachState.attached = attached
        onSurfaceAttachedChanged(attached)
    }
    fun publishDetach() {
        attachState.width = 0
        attachState.height = 0
        if (attachState.attached == false) return
        attachState.attached = false
        onSurfaceAttachedChanged(false)
    }
    // <-- AM (ATTACH_IS_A_VIEW_PROPERTY)

    // AM (ONE_TEXTUREVIEW_PER_PLAYER) -->
    // One TextureView per player, never shared between two.
    //
    // setSurfaceTexture() releases whatever the view is currently holding,
    // unconditionally and without calling onSurfaceTextureDestroyed for it
    // (AOSP TextureView: `if (mSurface != null) mSurface.release()` right
    // before the assignment, and the javadoc says the previous texture is
    // released and no longer usable). That is fine for the throwaway texture
    // this view creates for itself - the churn the factory below accounts
    // for - but it is fatal when the view is holding ANOTHER player's
    // persistent texture: that texture gets released while the player it
    // belongs to still points at it, and the next view to adopt it dies on
    // setSurfaceTexture's own isReleased() check.
    //
    // Compose reuses an AndroidView's view instance across recompositions,
    // so without this the same TextureView was handed a second player's
    // texture whenever `player` changed under it - which is routine in the
    // scroller, where cards are rebound to pooled instances. Keying the view
    // to the player is what the rest of this file already assumes: a view
    // only ever holds the texture of the player it was built for, and a new
    // player gets a new view. The outgoing view's detach keeps the texture,
    // as onSurfaceTextureDestroyed returning false has always arranged.
    key(player) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).apply {
                    // AM (BROWSE_SCROLLER)
                    onViewAvailable?.invoke(this)
                    // AM (ATTACH_IS_A_VIEW_PROPERTY)
                    val view = this
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
                            // firing with a brand new default texture anyway.
                            //
                            // AM (ATTACH_IS_A_VIEW_PROPERTY): that last sentence is
                            // only half right, and the half that is wrong cost a
                            // 30-second stall. TextureView#getTextureLayer() calls
                            // onSurfaceTextureAvailable only on the branch where it
                            // creates the SurfaceTexture itself (createNewSurface),
                            // so a view whose texture was supplied by
                            // setSurfaceTexture() is never told its texture is
                            // available - it hears about it through
                            // onSurfaceTextureSizeChanged instead. This branch
                            // therefore runs only when the texture really was
                            // recreated; the ordinary adoption is the one in update{}
                            // below, and both now publish the same thing. Instead:
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
                                // AM (FIXED_SURFACE_SIZE) -->
                                // Tell mpv the size on this path too.
                                //
                                // This branch adopts a texture that was sized for
                                // whatever view last held it, and it used to return
                                // without pushing android-surface-size - so whenever a
                                // new view adopted the persistent texture at a different
                                // size, mpv kept rendering for the OLD dimensions and
                                // letterboxed the video inside them. That letterboxed
                                // result then got mapped onto the new buffer, which looks
                                // like bars on all four sides and the video not filling
                                // the space it is given.
                                //
                                // It went unnoticed while the view was always
                                // fillMaxSize, because the old and new sizes were then
                                // almost always the same.
                                //
                                // setDefaultBufferSize as well: the adopted texture
                                // carries the previous buffer dimensions, and nothing
                                // else updates them when the view is not the one that
                                // created it.
                                // AM (ATTACH_IS_A_VIEW_PROPERTY): both statements now
                                // belong to publishAttach, which every path shares.
                                // <-- AM (FIXED_SURFACE_SIZE)
                                // AM (ADOPTED_SURFACE_KEEPS_HWDEC) -->
                                // This branch ends with a valid Surface attached, the
                                // same as the fresh one below, so it has to make the
                                // same two statements - and it was making neither.
                                //
                                // hasAttachedSurfaceBefore means "a valid Surface has
                                // ever been attached" and loadFileWithHwdecGuard reads
                                // it to decide whether there is anything to decode
                                // into: false means the load is issued with hwdec
                                // disabled. An instance that only ever reaches this
                                // branch never set the flag, so EVERY load it issued
                                // turned hardware decoding off, permanently - nothing
                                // sets hwdec back afterwards.
                                //
                                // The fullscreen player never showed it because its
                                // first attach is always the fresh path below. A
                                // scroller card is a pooled instance reassigned
                                // between cards, so its texture already exists and it
                                // adopts from the start. Confirmed on-device
                                // (2026-11-06): every feed card reported hwdec=no with
                                // frame-drop-count climbing and avsync reaching 3.1s
                                // on a 29.97fps clip, while the same content in the
                                // player ran on mediacodec.
                                //
                                // Only the flag. hwdec is deliberately NOT re-set
                                // here: HWDEC_REATTACH_FIX records that doing it on a
                                // reattach tears MediaCodec down mid-stream and can
                                // hang waiting for a keyframe. Restoring it belongs at
                                // the next load instead, which is a file boundary -
                                // see loadFileWithHwdecGuard.
                                player.hasAttachedSurfaceBefore = true
                                // <-- AM (ADOPTED_SURFACE_KEEPS_HWDEC)
                                // AM (AN_OFFSCREEN_INSTANCE_FORCES_NO_WINDOW) -->
                                // Here too, not only on the fresh branch. A pooled
                                // instance adopts, so it would otherwise keep the
                                // setting from whatever it was last used for - a
                                // prepared instance inheriting "yes" from its
                                // previous life as the live player, and a promoted
                                // one never getting it back.
                                mpv.setOptionString("force-window", if (forceWindow) "yes" else "no")
                                // <-- AM (AN_OFFSCREEN_INSTANCE_FORCES_NO_WINDOW)
                                // AM (ATTACH_IS_A_VIEW_PROPERTY)
                                publishAttach(view, width, height)
                                return
                            }
                            player.persistentSurfaceTexture = surfaceTexture
                            // <-- AM (PERSISTENT_SURFACE_ARCHITECTURE)
                            mpv.attachSurface(Surface(surfaceTexture))
                            // AM (ATTACH_IS_A_VIEW_PROPERTY): stays here, ahead of the
                            // vo, which reads it when it is created - noting it so the
                            // shared publisher does not send the same size twice.
                            attachState.width = width
                            attachState.height = height
                            mpv.setPropertyString("android-surface-size", "${width}x$height")
                            // AM (AN_OFFSCREEN_INSTANCE_FORCES_NO_WINDOW)
                            mpv.setOptionString("force-window", if (forceWindow) "yes" else "no")
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
                            // AM (ATTACH_IS_A_VIEW_PROPERTY)
                            publishAttach(view, width, height)
                            // <-- AM (AUDIO_BLIP_FIX)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            // AM (ATTACH_IS_A_VIEW_PROPERTY): this is also how an
                            // adopted texture arrives, so it carries the attachment.
                            publishAttach(view, width, height)
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            // AM (AUDIO_BLIP_FIX) -->
                            // AM (ATTACH_IS_A_VIEW_PROPERTY)
                            publishDetach()
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
                    // AM (ATTACH_IS_A_VIEW_PROPERTY): this is the adoption that
                    // onSurfaceTextureAvailable never reports. Idempotent, which
                    // matters because this block re-runs on every recomposition.
                    publishAttach(textureView, textureView.width, textureView.height)
                }
                // <-- AM (PERSISTENT_SURFACE_ARCHITECTURE)
                // AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX) -->
                // Rounded corners DURING a dummy-pip resize/tilt, applied on
                // the TextureView's OWN render node: an outline lives in the
                // view's local space, so the ancestor Compose layer's scale/
                // rotation transforms it together with the video - unlike the
                // Compose layer clip (applied in a space that doesn't scale
                // with the layer, cropping the pinch overshoot) and unlike an
                // offscreen blend mask (never touches a TextureView, whose
                // content is composited as a separate hardware layer). Radius
                // 0 = gesture over / not in the pip: clip off, pre-fix
                // rendering exactly. The floatValue read is what subscribes
                // this update block to the per-frame radius changes.
                // <-- AM (DUMMY_PIP_VIEW_OUTLINE_CORNERS_FIX)
                val pipRadius = pipCornerRadiusPx?.floatValue ?: 0f
                textureView.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, pipRadius)
                    }
                }
                textureView.clipToOutline = pipRadius > 0f
                textureView.invalidateOutline()
            },
        )
    }
    // <-- AM (ONE_TEXTUREVIEW_PER_PLAYER)
}

// AM (ATTACH_IS_A_VIEW_PROPERTY) -->
/**
 * What this view has already told mpv and the player about its texture, so the
 * shared publisher can be called from every path without repeating itself.
 * Remembered per player, not per composition: the texture outlives the view.
 */
private class SurfaceAttachState {
    var attached: Boolean? = null
    var width: Int = 0
    var height: Int = 0
}
// <-- AM (ATTACH_IS_A_VIEW_PROPERTY)
