package eu.kanade.tachiyomi.ui.player.mpv

import `is`.xyz.mpv.MPV

/**
 * AM (SHARED_LOAD_FILE_FIX) -->
 * Extracted from what were two separately-written, independently-maintained copies of
 * this exact operation - PlayerViewModel.loadFile() (foreground) and
 * PlayerMediaHolder.performBackgroundSkipLoad() (background skip, no live Activity).
 * Both disable hwdec first when there's no attached Surface (MediaCodec hwdec needs one
 * to initialize a decoder session) and then issue the same loadfile command - the
 * foreground path just has a real Surface to check for, while the background path
 * never has one at all, by definition.
 *
 * Having this in one place is specifically about preventing the kind of drift already
 * found once this session (see BACKGROUND_SKIP_PAUSE_SYMMETRY_FIX): two independently
 * maintained copies of "the same operation" silently disagreeing over time, each one
 * needing to be found and fixed separately rather than being fixed once, here.
 *
 * hasAttachedSurface means "does a valid Surface (real or the always-present dummy
 * one from MPVPlayer.ensureDummySurface()) currently exist to decode into" - both
 * current callers pass player.hasAttachedSurfaceBefore (a one-way flag: true once
 * the real Surface has ever attached, never reset) rather than checking the real
 * Surface's current attachment specifically, since the dummy surface (confirmed via
 * logcat to correctly support a fresh hardware decoder session - see
 * DUMMY_SURFACE_FORMAT_FIX) keeps a valid target continuously available from that
 * point on, foreground or backgrounded either way. See AUDIO_BLIP_FIX_2 at each call
 * site for the full reasoning.
 * <-- AM (SHARED_LOAD_FILE_FIX)
 */
fun MPV.loadFileWithHwdecGuard(url: String, options: String, hasAttachedSurface: Boolean) {
    if (!hasAttachedSurface) {
        setOptionString("hwdec", "no")
    }
    command("loadfile", url, "replace", "0", options)
}
