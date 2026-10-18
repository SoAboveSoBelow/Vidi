package eu.kanade.tachiyomi.ui.player

// AM (CONTENT_AGNOSTIC_SESSION) -->
/**
 * Opaque identity of whatever the player session is currently playing.
 *
 * Replaces the bare `(animeId, episodeId)` pair that [PlayerMediaHolder],
 * [PlayerBackgroundPlaybackService] and [PlayerViewModel.needsInit] used to key
 * on directly. That pair was never really an identifier - it was a library
 * lookup that happened to double as one, which is why nothing the player holds
 * could represent content that isn't a library episode.
 *
 * Everything that only needs to answer "is this the same session as that one"
 * - the duplicate-instance guard, needsInit()'s holder fallback, the artwork
 * flow's re-resolve trigger, the notification's reopen target - compares keys
 * and never looks inside them. Those mechanisms carry a lot of hard-won
 * correctness (see the task-affinity and duplicate-instance comments in the
 * manifest and in PlayerActivity.newIntent) and must not have to learn about
 * each new kind of content; making the key opaque is what keeps them out of it.
 *
 * Code that genuinely needs the library row - a DB read, trackers, history -
 * matches on [Library] explicitly. That match is the containment boundary: an
 * unhandled branch is a compile error at exactly the places that care, instead
 * of a sentinel id silently flowing into a `getAnime.await()` that returns null.
 */
sealed interface SessionKey {

    /** An episode of a library entry - every session that exists today. */
    data class Library(
        val animeId: Long,
        val episodeId: Long,
    ) : SessionKey

    /**
     * A video opened from outside the app (file manager, share sheet, a link
     * handed to us as the default video player), identified by its URI alone.
     *
     * Not yet produced by anything - [ExternalPlayerActivity] and the external
     * PlaybackSource land in a later step. Declared here because it is the
     * reason this type exists at all: the library branch below should read as
     * one case among several, not as the only shape a session can take.
     */
    data class External(
        val uri: String,
    ) : SessionKey
}

/** The library ids of this key, or nulls when the session isn't a library one. */
val SessionKey?.libraryAnimeId: Long?
    get() = (this as? SessionKey.Library)?.animeId

val SessionKey?.libraryEpisodeId: Long?
    get() = (this as? SessionKey.Library)?.episodeId
// <-- AM (CONTENT_AGNOSTIC_SESSION)
