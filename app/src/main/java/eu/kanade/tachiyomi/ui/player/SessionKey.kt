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

// AM (SESSION_KEYED_TEMP_POSITIONS) -->
/**
 * This key as `session_temp_positions` stores it - and, through that, as
 * [RecentPositionManager] keys its cache.
 *
 * The storage layer holds the string opaquely (see
 * [animiru.domain.player.model.SessionTempPosition]), so this is the only place
 * that knows the encoding and a new session kind costs a branch here rather
 * than a migration.
 *
 * A library session encodes the EPISODE ONLY, deliberately, and deserves the
 * note migration 148 left: episodes._id is globally unique and a playback
 * position is a property of the episode, not of the entry it was reached
 * through. A merged entry and one of its children carry different anime ids for
 * the same episode, so including the anime id would give that episode two
 * independent positions again and revert to a stale one on switching back.
 *
 * External sessions take the URI whole, after a prefix parsed off at the first
 * separator - a URI brings separators of its own and must not need escaping.
 */
val SessionKey.storageKey: String
    get() = when (this) {
        is SessionKey.Library -> "$LIBRARY_KIND$KIND_SEPARATOR$episodeId"
        is SessionKey.External -> "$EXTERNAL_KIND$KIND_SEPARATOR$uri"
    }

/**
 * The episode a stored key belongs to, or null when it is not a library one.
 *
 * Exists for the media cache, which is genuinely episode-scoped. Nothing else
 * looks inside a stored key.
 */
fun storageKeyEpisodeId(value: String): Long? =
    value.removePrefix("$LIBRARY_KIND$KIND_SEPARATOR")
        .takeIf { it.length != value.length }
        ?.toLongOrNull()

private const val KIND_SEPARATOR = ':'
private const val LIBRARY_KIND = "library"
private const val EXTERNAL_KIND = "external"
// <-- AM (SESSION_KEYED_TEMP_POSITIONS)

/** The library ids of this key, or nulls when the session isn't a library one. */
val SessionKey?.libraryAnimeId: Long?
    get() = (this as? SessionKey.Library)?.animeId

val SessionKey?.libraryEpisodeId: Long?
    get() = (this as? SessionKey.Library)?.episodeId
// <-- AM (CONTENT_AGNOSTIC_SESSION)
