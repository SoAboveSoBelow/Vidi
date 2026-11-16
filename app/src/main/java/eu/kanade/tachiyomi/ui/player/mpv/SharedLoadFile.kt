package eu.kanade.tachiyomi.ui.player.mpv

import `is`.xyz.mpv.MPV

/**
 * AM (SHARED_LOAD_FILE_FIX) -->
 * Extracted from what were two separately-written, independently-maintained copies of
 * this exact operation - PlayerViewModel.loadFile() (foreground) and
 * PlayerMediaHolder.performBackgroundSkipLoad() (background skip, no live Activity).
 * The background-skip path (and everything that called it) is gone now - see
 * PlayerMediaHolder's own removal notes - so this has one caller today, but is kept
 * as its own function rather than inlined back into loadFile(): "the same operation,
 * fixed once here" is still the right shape for whatever eventually calls this next.
 *
 * hasAttachedSurface means "has a valid Surface ever been attached to decode
 * into" - the caller passes player.hasAttachedSurfaceBefore (a one-way flag:
 * true once, forever, from the very first real attach), since the player's
 * SurfaceTexture is now created once and persists for the whole player
 * lifetime (see MPVPlayer.persistentSurfaceTexture) rather than being torn
 * down and recreated. See AUDIO_BLIP_FIX_2 at the call site for the full
 * reasoning.
 * <-- AM (SHARED_LOAD_FILE_FIX)
 */
fun MPV.loadFileWithHwdecGuard(
    url: String,
    options: String,
    hasAttachedSurface: Boolean,
    // AM (ADOPTED_SURFACE_KEEPS_HWDEC)
    hwdec: String,
) {
    // AM (ADOPTED_SURFACE_KEEPS_HWDEC) -->
    // Was `if (!hasAttachedSurface) setOptionString("hwdec", "no")` - a one-way
    // door. Turning it off for a surface-less load is right, but nothing ever
    // turned it back on, so an instance loaded once without a surface decoded in
    // software for the rest of its life. Confirmed on-device (2026-11-06): every
    // scroller card reported hwdec=no with frame drops climbing and avsync
    // reaching 3.1s, while the same content in the fullscreen player ran on
    // mediacodec.
    //
    // Setting it both ways here, immediately before the loadfile, is also the
    // only safe place to raise it: HWDEC_REATTACH_FIX records that changing
    // hwdec mid-stream tears MediaCodec down and can hang waiting for a
    // keyframe. A file boundary is not mid-stream.
    setOptionString("hwdec", if (hasAttachedSurface) hwdec else "no")
    // <-- AM (ADOPTED_SURFACE_KEEPS_HWDEC)
    command("loadfile", url, "replace", "0", options)
}
