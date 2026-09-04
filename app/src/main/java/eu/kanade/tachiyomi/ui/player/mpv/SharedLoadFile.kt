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
fun MPV.loadFileWithHwdecGuard(url: String, options: String, hasAttachedSurface: Boolean) {
    if (!hasAttachedSurface) {
        setOptionString("hwdec", "no")
    }
    command("loadfile", url, "replace", "0", options)
}
