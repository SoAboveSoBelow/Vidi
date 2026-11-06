package aniyomi.ui.browse.scroller

import eu.kanade.tachiyomi.ui.player.RecentPositionManager
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer

// AM (BROWSE_SCROLLER) -->
/**
 * A fixed set of mpv instances, lent to whichever cards are near the one being
 * looked at.
 *
 * Fixed, and never destroyed while the feed is open, for a reason worth
 * recording. `MPV.close()` cancels a session scope that runs on
 * Dispatchers.Default and then calls nativeDestroy(), which frees the native
 * context - and cancellation is cooperative, so a session coroutine already
 * inside a native call keeps going while the context it is using is freed.
 * That is a use-after-free, and it shows up as "pthread_mutex_lock called on a
 * destroyed mutex" and a null SIGSEGV on Dispatchers.Default or on mpv's own
 * event threads.
 *
 * The app's real player never meets it because it builds one instance and
 * keeps it. An earlier version of this pool created and destroyed an instance
 * per card as the feed scrolled, which met it constantly. Instances are
 * created once here and reassigned by loading a different file into them,
 * which is an ordinary loadfile and touches none of that teardown path; they
 * are destroyed once, when the feed itself goes away.
 *
 * The proper fix belongs in mpv-android-vidi - joining the session scope
 * before nativeDestroy, and keeping the native context alive for the duration
 * of any call into it. Until then this keeps the churn out of the crash.
 *
 * Multiple instances are themselves fine: `is.xyz.mpv.MPV` keeps its
 * mpv_handle in a per-object field and dispatches events per instance, with
 * only the JavaVM pointer genuinely process-global (read from
 * mpv-android-vidi's mpv_context.cpp and globals.h).
 */
class ScrollerPlayerPool(
    private val factory: MPVPlayer.Factory,
    private val videoOutput: String,
    // AM (SESSION_KEYED_TEMP_POSITIONS): handed to each instance it builds -
    // see ScrollerPlayer's own parameter.
    private val positions: RecentPositionManager,
) {

    /** A slot holds one instance for the life of the feed. */
    private class Slot(val player: ScrollerPlayer) {
        var animeId: Long? = null

        /**
         * AM (BROWSE_SCROLLER_RETENTION): the card gave this instance back but
         * its file is still loaded, so coming back to the card is a resume
         * rather than a reload. A retained slot is invisible to [get] - its
         * card must not draw a surface it no longer owns - and is reclaimed
         * before any slot a card is actually using.
         */
        var retained: Boolean = false

        /** Bumped on every assignment, so the least recently used is findable. */
        var lastUsed: Long = 0
    }

    private val slots = mutableListOf<Slot>()
    private var useCounter = 0L

    private fun slotFor(animeId: Long): Slot? = slots.firstOrNull { it.animeId == animeId }

    fun get(animeId: Long): ScrollerPlayer? = slotFor(animeId)?.takeIf { !it.retained }?.player

    // AM (BROWSE_SCROLLER_RETENTION) -->
    /** The instance behind a retained slot, which [get] deliberately hides. */
    fun getRetained(animeId: Long): ScrollerPlayer? = slotFor(animeId)?.takeIf { it.retained }?.player

    /**
     * Keeps [animeId]'s file loaded while no card is drawing it.
     *
     * Nothing is stopped: the instance stays open, paused, on the frame it
     * reached, so the card returning to it resumes rather than reloads. The
     * slot is still reclaimable - [acquire] takes a retained slot before it
     * takes one from a card on screen - so a feed being scrolled is never
     * starved by what it left behind.
     */
    fun retain(animeId: Long) {
        val slot = slotFor(animeId) ?: return
        slot.retained = true
        slot.player.pause()
    }
    // <-- AM (BROWSE_SCROLLER_RETENTION)

    /**
     * Lends [animeId] an instance, creating one only while the pool is still
     * filling up. Beyond that the least recently used slot is reassigned,
     * which means a different file loaded into an instance that stays alive.
     *
     * [onEvict] names the card whose slot was taken, so the caller can drop
     * whatever it was holding for it (its frame state, its retention timer,
     * its cache registration). A reassignment is that card's instance ending,
     * and the only place it can be observed.
     */
    fun acquire(animeId: Long, onEvict: (Long) -> Unit = {}): ScrollerPlayer {
        slotFor(animeId)?.let { slot ->
            // AM (BROWSE_SCROLLER_RETENTION): a retained slot is this card's
            // own, still loaded - taking it back is the whole point.
            slot.retained = false
            slot.lastUsed = ++useCounter
            return slot.player
        }

        // A free slot before a used one, always. Taking the least recently
        // used without that distinction meant a card still on screen could
        // have its instance taken by a card entering the window - and since
        // the card scrolled back to is the one used longest ago, it was
        // reliably the victim. It then had no instance and nothing asked it to
        // load again.
        val slot = slots.firstOrNull { it.animeId == null }
            ?: if (slots.size < CAPACITY) {
                Slot(
                    ScrollerPlayer(
                        factory.create(videoOutput),
                        factory.create(videoOutput),
                        positions,
                    ),
                ).also { slots += it }
            } else {
                // AM (BROWSE_SCROLLER_RETENTION): retained slots first, oldest
                // of them, and only then one a card is still using.
                val victim = slots.filter { it.retained }.minByOrNull { it.lastUsed }
                    ?: slots.minByOrNull { it.lastUsed }!!
                victim.animeId?.let(onEvict)
                victim.also { it.player.reset() }
            }

        slot.retained = false
        slot.animeId = animeId
        slot.lastUsed = ++useCounter
        return slot.player
    }

    /**
     * Hands [animeId]'s instance back, stopping it but keeping it alive for
     * the next card that needs one.
     */
    fun release(animeId: Long) {
        val slot = slotFor(animeId) ?: return
        slot.animeId = null
        // AM (BROWSE_SCROLLER_RETENTION)
        slot.retained = false
        // Oldest, so a freed slot is the first one reused.
        slot.lastUsed = 0
        slot.player.reset()
    }

    /**
     * Every instance a card is currently drawing.
     *
     * AM (BROWSE_SCROLLER_RETENTION): retained slots are left out - they are
     * paused and belong to no card, so play state and audio focus are not
     * theirs to be given.
     */
    fun forEach(action: (Long, ScrollerPlayer) -> Unit) =
        slots.toList().forEach { slot -> if (!slot.retained) slot.animeId?.let { action(it, slot.player) } }

    fun ids(): Set<Long> = slots.mapNotNull { it.animeId }.toSet()

    /** The one place instances are actually destroyed: the feed closing. */
    fun releaseAll() {
        slots.toList().forEach { it.player.release() }
        slots.clear()
    }

    companion object {
        /**
         * Instances held for the life of the feed.
         *
         * One more than the window needs (the card being looked at, one
         * behind, two ahead). The spare matters: as the window moves, the card
         * entering it can ask for an instance before the card leaving has
         * given one back, and at exactly window size that request has to take
         * one from a card still on screen.
         *
         * The ceiling is hardware - MediaCodec instances are finite and fewer
         * at high resolutions - so this is the knob to turn down if a card
         * stops being able to open its file at all.
         */
        const val CAPACITY = 5
    }
}
// <-- AM (BROWSE_SCROLLER)
