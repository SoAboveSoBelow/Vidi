package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// AM (PLAYLIST_SEARCH_SCOPE) -->
/**
 * The submitted episode search - the one that decided what the playlist
 * contains, as opposed to whatever is currently typed.
 *
 * Two values exist in this feature. The DRAFT is local to whichever search
 * field is on screen and decides only what that list displays; it is thrown
 * away when the field goes. The SUBMITTED query is this, and it only changes
 * when the user picks an episode - that tap is the confirmation that the
 * narrowed list is what they want to play. So typing never moves the playlist,
 * and closing the dialog without picking anything leaves it exactly as it was.
 *
 * One pair, not a map. Only the playing playlist's search matters, so
 * submitting for another entry replaces it rather than accumulating per-entry
 * state. The anime id is what lets a screen tell whether the stored query is
 * about the entry it is showing: the entry list restores it when they match,
 * and starts empty when they don't, which is what keeps a search typed while
 * browsing a different series from scoping what is currently playing.
 *
 * Published at click time rather than threaded through session setup. The
 * playlist is then derived from (entry, submitted query) wherever it is built,
 * so it stays reproducible from state instead of being a frozen snapshot of
 * ids - which is also why the shuffle rebuild re-applies it without knowing it
 * exists. openEpisode()'s own doc comment records the same choice being forced
 * for the resume flag, after threading it through the Intent chain turned out
 * not to land reliably.
 */
object PlaylistSearchScope {

    data class Submitted(val animeId: Long, val query: String)

    private val _submitted = MutableStateFlow<Submitted?>(null)
    val submitted = _submitted.asStateFlow()

    /** The confirmation: the draft becomes what the playlist is built from. */
    fun submit(animeId: Long, query: String) {
        _submitted.value = Submitted(animeId, query.trim())
    }

    /** The stored query if it belongs to [animeId], otherwise no scope. */
    fun queryFor(animeId: Long): String {
        return _submitted.value?.takeIf { it.animeId == animeId }?.query.orEmpty()
    }

    /**
     * Drops the scope only if it belongs to [animeId].
     *
     * Emptying the search field is its own confirmation: an empty query has no
     * ambiguity about what the user meant by it, the way a half-typed one does,
     * so it needs no episode tap to take effect. Closing the field and clearing
     * it both unfilter, which is what makes "what you see is what plays" true
     * in the other direction too.
     *
     * Takes an id rather than clearing unconditionally: the entry screen for one
     * series stays composed while another plays, so an unguarded clear there
     * would silently unscope the playing playlist. There is deliberately no
     * unguarded variant - the only caller that ever wanted one was this, and
     * having both invites reaching for the wrong one.
     */
    fun clearFor(animeId: Long) {
        if (_submitted.value?.animeId == animeId) {
            _submitted.value = null
        }
    }
}
// <-- AM (PLAYLIST_SEARCH_SCOPE)
