package eu.kanade.tachiyomi.ui.player.mpv

// AM (POOLED_PLAYER_INSTANCES) -->
/**
 * A small set of mpv instances, lent out and handed back rather than created
 * and destroyed.
 *
 * The rule this exists to enforce is the browse feed's, generalised: an
 * instance is created once and reassigned by loading a different file into it,
 * and `MPV.close()` is called only when the owner itself goes away. The feed
 * arrived at that rule the hard way - see ScrollerPlayerPool, which records the
 * use-after-free that destroying an instance per card kept hitting - and the
 * player has the same teardown path without the same protection: every
 * completed quality switch closes the instance it just swapped away from,
 * while that instance's own event threads are still winding down.
 *
 * Nothing here is feed-specific, and nothing here knows what an instance is
 * playing. Ownership only: who holds an instance right now, and which are
 * free.
 *
 * Reuse is safe for the surface because an instance keeps its own
 * SurfaceTexture for life (see MPVPlayer.persistentSurfaceTexture, whose `wid`
 * is bound once and never rebound). A view that attaches to a reused instance
 * adopts that same texture, which is exactly what the feed does when it hands
 * an instance from one card to another.
 */
class MpvInstancePool(
    private val factory: MPVPlayer.Factory,
    private val videoOutput: String,
) {

    private class Slot(val player: MPVPlayer) {
        var inUse: Boolean = false
    }

    private val slots = mutableListOf<Slot>()

    /** How many instances exist, for callers that log or assert on it. */
    val size: Int @Synchronized get() = slots.size

    /**
     * A free instance, or a new one when none is free.
     *
     * Deliberately uncapped: the caller decides how many instances it needs at
     * once, and a cap here would mean handing out an instance somebody else is
     * still rendering. What the pool bounds is how often one is CREATED, not
     * how many exist.
     */
    @Synchronized
    fun acquire(): MPVPlayer {
        slots.firstOrNull { !it.inUse }?.let {
            it.inUse = true
            return it.player
        }
        val created = factory.create(videoOutput)
        slots += Slot(created).also { it.inUse = true }
        return created
    }

    /**
     * Hands [player] back, stopping it but keeping it alive.
     *
     * An instance the pool has not seen before is adopted rather than ignored,
     * so a caller can give the pool ownership of something it created itself -
     * which is how the first quality switch returns the session's original
     * instance without that instance having to have come from here.
     *
     * Options outlive a file, so the ones a previous use may have set and the
     * next use may not clear are cleared here. Everything a caller sets on
     * every use - volume, speed, track selection, pause, loop - is left alone
     * deliberately: clearing those would be guessing at what the next caller
     * wants rather than undoing what the last one did.
     */
    @Synchronized
    fun release(player: MPVPlayer) {
        if (player.isReleased) {
            slots.removeAll { it.player === player }
            return
        }
        val slot = slots.firstOrNull { it.player === player }
            ?: Slot(player).also { slots += it }
        runCatching { player.mpv.command("stop") }
        runCatching { player.mpv.setPropertyBoolean("pause", true) }
        // A stale Referer or user agent from the last file fails the next one's
        // handshake, and a caller with no headers of its own never overwrites it.
        runCatching { player.mpv.setOptionString("http-header-fields", "") }
        slot.inUse = false
    }

    /** Destroys every instance. Only the owner going away does this. */
    @Synchronized
    fun releaseAll() {
        slots.forEach { runCatching { it.player.release() } }
        slots.clear()
    }
}
// <-- AM (POOLED_PLAYER_INSTANCES)
