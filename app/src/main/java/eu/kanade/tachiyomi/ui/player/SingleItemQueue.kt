package eu.kanade.tachiyomi.ui.player

// AM (EXTERNAL_PLAYBACK) -->
/**
 * A queue of exactly one thing - next and previous both report nothing.
 *
 * What an externally-opened video gets when there is no discoverable order
 * around it, which on modern Android is the ordinary case rather than the
 * exception: an app that sends us a video sends a grant for that one document,
 * and the folder it sits in is not ours to read. See the folder-siblings note
 * in BRANCH_NOTES / the discussion attached to EXTERNAL_PLAYBACK for what it
 * would take to do better and why it is a separate decision.
 *
 * Deliberately not a special case inside the player: it is a real [PlaybackQueue]
 * that answers "no" to next and previous, so the navigation code has one shape
 * to deal with instead of a null queue meaning something different.
 */
class SingleItemQueue(private val key: SessionKey) : PlaybackQueue {

    override val size: Int get() = 1

    override fun keyAt(index: Int): SessionKey? = key.takeIf { index == 0 }

    override fun indexOf(key: SessionKey): Int = if (key == this.key) 0 else -1
}
// <-- AM (EXTERNAL_PLAYBACK)
