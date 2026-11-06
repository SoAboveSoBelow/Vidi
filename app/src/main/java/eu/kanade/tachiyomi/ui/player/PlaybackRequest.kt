package eu.kanade.tachiyomi.ui.player

import android.content.Intent
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList

// AM (CONTENT_AGNOSTIC_SESSION) -->
/**
 * A request to play something - "what the user asked for", as opposed to
 * [SessionKey], which is "what is loaded right now".
 *
 * Was a single data class of six library fields living as an odd top-level
 * neighbour inside PlayerMediaHolder.kt (see PLAYER_OVERLAY_MIGRATION - it was
 * top-level, not nested in the companion, because a nested version wouldn't
 * resolve from MainActivity.kt). It keeps the top-level placement that sidestepped
 * that, in a file of its own now that it is a hierarchy rather than one class.
 *
 * Two things flow through this type and both needed widening for the same reason:
 * the in-app overlay path (PlayerMediaHolder.requestPlayback ->
 * playbackRequestFlow -> PlayerOverlayHost) and PlayerActivity's Intent extras.
 * The Intent side used five loose extras with -1 sentinels read back out
 * individually, where "the request didn't parse" and "the request says episode
 * -1" were the same value; [fromIntent] returns null instead, which is what
 * onNewIntent's bail-out check always meant.
 */
sealed interface PlaybackRequest {

    /** What this request will be playing, once loaded. */
    val sessionKey: SessionKey

    /**
     * See AnimeScreen.openEpisode()'s forceResume param - only "Continue" and
     * the Recents reselect gesture, the two actions that specifically mean
     * "resume", pass true.
     */
    val forceResume: Boolean

    // AM (PLAYBACK_QUEUE) -->
    /**
     * Whether this request continues the queue rather than picking something.
     *
     * The distinction cannot be inferred downstream, which is why it travels
     * here. Opening something is "queue it and skip to it", so its block is
     * repositioned to play next and, under clear-on-episode-selection, the
     * queue is emptied first (see PlayerViewModel.setupEpisodeList). Resuming
     * is the opposite: the queue already says what to play and where, so it
     * must be left exactly as it is - a resume that repositioned would pull
     * its own entry in front of the items the user had lined up ahead of it.
     */
    val continuesQueue: Boolean
    // <-- AM (PLAYBACK_QUEUE)

    data class Library(
        val animeId: Long,
        val episodeId: Long,
        val hosterList: List<Hoster>? = null,
        val hosterIndex: Int = -1,
        val videoIndex: Int = -1,
        override val forceResume: Boolean = false,
        // AM (PLAYBACK_QUEUE)
        override val continuesQueue: Boolean = false,
    ) : PlaybackRequest {
        override val sessionKey: SessionKey
            get() = SessionKey.Library(animeId = animeId, episodeId = episodeId)
    }

    /**
     * A video handed to us from outside the app. Nothing produces this yet -
     * ExternalPlayerActivity and the external PlaybackSource land in a later
     * step; consumers below branch on it now so that step adds a case to an
     * existing `when` rather than widening a type everything already assumed.
     *
     * No hoster fields: hosters are how a library source resolves an episode to
     * a stream, which an already-resolved URI has no equivalent of. That
     * asymmetry is why this is a hierarchy and not one class with nullables.
     */
    data class External(
        val uri: String,
        val title: String? = null,
        /**
         * Where this file was last playing, in milliseconds.
         *
         * An external session has no episode row, so the resume position cannot
         * come from the DB the way a library episode's does. It travels on the
         * request instead - which means it travels in the reopen Intent, and so
         * lives exactly as long as the notification holding that PendingIntent
         * does. That is the lifetime it needs: while the notification exists,
         * playback must be resumable from where it stopped.
         */
        val positionMs: Long = 0L,
        // AM (EXTERNAL_APP_LABEL)
        /** The app this was opened from, for naming where a save of it lands. */
        val appLabel: String? = null,
        override val forceResume: Boolean = false,
        // AM (PLAYBACK_QUEUE)
        override val continuesQueue: Boolean = false,
    ) : PlaybackRequest {
        override val sessionKey: SessionKey
            get() = SessionKey.External(uri = uri)
    }

    companion object {
        /**
         * Reads a request back out of [intent], or null when it carries none.
         *
         * The kind is written explicitly rather than inferred from which extras
         * are present: inference would make a malformed Intent (an external one
         * whose uri extra got dropped, say) silently parse as the other kind
         * with default ids, which is precisely the class of failure the -1
         * sentinels used to produce.
         *
         * Intents built before this existed carry no kind extra and are read as
         * library requests, so a PendingIntent the system still holds from an
         * older build keeps working instead of being dropped as unparseable.
         */
        fun fromIntent(intent: Intent): PlaybackRequest? {
            val extras = intent.extras ?: return null
            val forceResume = extras.getBoolean(EXTRA_FORCE_RESUME, false)
            // AM (PLAYBACK_QUEUE)
            val continuesQueue = extras.getBoolean(EXTRA_CONTINUES_QUEUE, false)
            return when (extras.getString(EXTRA_KIND) ?: KIND_LIBRARY) {
                KIND_LIBRARY -> {
                    val animeId = extras.getLong(EXTRA_ANIME_ID, -1L)
                    val episodeId = extras.getLong(EXTRA_EPISODE_ID, -1L)
                    if (animeId == -1L || episodeId == -1L) return null
                    Library(
                        animeId = animeId,
                        episodeId = episodeId,
                        hosterList = extras.getString(EXTRA_HOST_LIST)
                            ?.takeIf { it.isNotBlank() }
                            ?.toHosterList()
                            ?.takeIf { it.isNotEmpty() },
                        hosterIndex = extras.getInt(EXTRA_HOST_INDEX, -1),
                        videoIndex = extras.getInt(EXTRA_VID_INDEX, -1),
                        forceResume = forceResume,
                        // AM (PLAYBACK_QUEUE)
                        continuesQueue = continuesQueue,
                    )
                }
                KIND_EXTERNAL -> {
                    val uri = extras.getString(EXTRA_URI)?.takeIf { it.isNotBlank() } ?: return null
                    External(
                        uri = uri,
                        title = extras.getString(EXTRA_TITLE)?.takeIf { it.isNotBlank() },
                        positionMs = extras.getLong(EXTRA_POSITION_MS, 0L),
                        // AM (EXTERNAL_APP_LABEL)
                        appLabel = extras.getString(EXTRA_APP_LABEL)?.takeIf { it.isNotBlank() },
                        forceResume = forceResume,
                        // AM (PLAYBACK_QUEUE)
                        continuesQueue = continuesQueue,
                    )
                }
                else -> null
            }
        }
    }
}

/**
 * Writes this request onto [intent] as extras, returning the same intent.
 *
 * The library extras keep their original names and shapes deliberately -
 * PlayerBackgroundPlaybackService's reopen PendingIntent and MainActivity's
 * REOPEN_PLAYER_HOST_SCREEN handler both read "animeId"/"episodeId" directly, and
 * a PendingIntent the system is already holding from a previous build must keep
 * resolving. Only the kind discriminator is new on that path.
 */
fun PlaybackRequest.writeToIntent(intent: Intent): Intent = intent.apply {
    putExtra(EXTRA_FORCE_RESUME, forceResume)
    // AM (PLAYBACK_QUEUE)
    putExtra(EXTRA_CONTINUES_QUEUE, continuesQueue)
    when (this@writeToIntent) {
        is PlaybackRequest.Library -> {
            putExtra(EXTRA_KIND, KIND_LIBRARY)
            putExtra(EXTRA_ANIME_ID, animeId)
            putExtra(EXTRA_EPISODE_ID, episodeId)
            hosterList?.let { putExtra(EXTRA_HOST_LIST, it.serialize()) }
            if (hosterIndex != -1) putExtra(EXTRA_HOST_INDEX, hosterIndex)
            if (videoIndex != -1) putExtra(EXTRA_VID_INDEX, videoIndex)
        }
        is PlaybackRequest.External -> {
            putExtra(EXTRA_KIND, KIND_EXTERNAL)
            putExtra(EXTRA_URI, uri)
            title?.let { putExtra(EXTRA_TITLE, it) }
            if (positionMs > 0L) putExtra(EXTRA_POSITION_MS, positionMs)
            // AM (EXTERNAL_APP_LABEL)
            appLabel?.let { putExtra(EXTRA_APP_LABEL, it) }
        }
    }
}

// Extra names kept at file scope so [PlaybackRequest.fromIntent] and
// [writeToIntent] cannot drift apart - the two halves of one wire format.
private const val EXTRA_KIND = "playbackRequestKind"

// AM (EXTERNAL_APP_LABEL)
private const val EXTRA_APP_LABEL = "externalAppLabel"
private const val KIND_LIBRARY = "library"
private const val KIND_EXTERNAL = "external"

private const val EXTRA_ANIME_ID = "animeId"
private const val EXTRA_EPISODE_ID = "episodeId"
private const val EXTRA_HOST_LIST = "hostList"
private const val EXTRA_HOST_INDEX = "hostIndex"
private const val EXTRA_VID_INDEX = "vidIndex"
private const val EXTRA_FORCE_RESUME = "forceResume"

// AM (PLAYBACK_QUEUE)
private const val EXTRA_CONTINUES_QUEUE = "continuesQueue"
private const val EXTRA_URI = "externalUri"
private const val EXTRA_TITLE = "externalTitle"
private const val EXTRA_POSITION_MS = "externalPositionMs"
// <-- AM (CONTENT_AGNOSTIC_SESSION)
