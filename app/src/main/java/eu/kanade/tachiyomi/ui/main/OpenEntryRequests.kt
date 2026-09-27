package eu.kanade.tachiyomi.ui.main

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// AM (OPEN_ENTRY_FROM_OVERLAY) -->
/**
 * Requests to open a library entry from somewhere with no Navigator of its own
 * - today the player overlay, which is hosted at MainActivity's root outside
 * the Navigator entirely (see PlayerHostScreen's doc comment).
 *
 * Deliberately not HomeScreen.openTab(): that sends on a rendezvous Channel
 * collected inside HomeScreen's own Content, so the collector only runs while
 * HomeScreen is the composed screen. With any other screen pushed on top -
 * another entry, an extension - nothing receives, the send parks, and the tap
 * appears to do nothing until HomeScreen happens to come back and the stale
 * request lands late. It also switches tabs, which is wrong for "open this on
 * top of what I'm looking at".
 *
 * A buffered SharedFlow instead, collected in MainActivity next to the
 * Navigator itself, which is composed for the life of the Activity regardless
 * of the current screen. [open] is non-suspending, so callers need no scope.
 */
object OpenEntryRequests {

    private val _requests = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val requests = _requests.asSharedFlow()

    fun open(animeId: Long) {
        _requests.tryEmit(animeId)
    }
}
// <-- AM (OPEN_ENTRY_FROM_OVERLAY)
