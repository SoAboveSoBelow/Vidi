package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode

// AM (CONTENT_AGNOSTIC_SESSION) -->
/**
 * What a session plays, and how to turn it into something mpv can open.
 *
 * The second of the three responsibilities PlayerViewModel conflated into the
 * Anime/Episode pair (progress went to [PlaybackProgressStore]; the queue is
 * still to come). This one answers "what is the URL" - which for a library
 * episode means asking the source for hosters and resolving a quality out of
 * one, and for a file handed to us from outside means the URI we were given.
 *
 * [resolveStreams] returns hosters for BOTH kinds, which is the point rather
 * than a leak: a [Hoster] is only a named group of playable options, and the
 * pipeline below it - hosterState, selectBestVideo, loadVideo's failover - is
 * already written against that shape and is not library-specific. A single
 * local file is one group holding one option, so it flows through the existing
 * machinery untouched instead of needing a parallel path beside it. That is
 * also why this interface has no isLocal/supportsQualities capability flags:
 * a source with one stream and a source with twelve differ in their data, not
 * in what callers may ask of them.
 */
interface PlaybackSource {

    /** What this source plays. */
    val sessionKey: SessionKey

    /** Shown by the player UI and the notification. */
    val title: String

    /**
     * True when playback streams over the network, false for a downloaded or
     * local file. Drives the streaming-only UI affordances.
     */
    fun isOnline(): Boolean

    /**
     * The playable options, grouped. An empty list means the source resolved
     * successfully but has nothing to play, which callers treat as a failure
     * with their own message - this does not decide that for them.
     */
    suspend fun resolveStreams(): List<Hoster>
}

/**
 * A library episode - every session that exists today.
 *
 * Bound to one already-resolved (episode, anime, source) triple. For a merged
 * entry that triple is the CHILD the episode actually came from, not the merge
 * parent (see PlayerViewModel.resolveEpisodeAnimeAndSource) - resolving which
 * is the caller's job, done before this is constructed, so nothing downstream
 * has to re-derive it.
 *
 * [prefetched] is the already-fetched hoster list for this episode when the
 * next-episode prefetch has one, checked before going to the network - see
 * NEXT_EPISODE_PREFETCH. Null for every other construction.
 */
class LibraryPlaybackSource(
    override val sessionKey: SessionKey.Library,
    override val title: String,
    private val episode: Episode,
    private val anime: Anime,
    private val source: AnimeSource,
    private val episodeLoader: EpisodeLoader,
    private val prefetched: List<Hoster>? = null,
) : PlaybackSource {

    override fun isOnline(): Boolean =
        source is AnimeHttpSource && !episodeLoader.isDownload(episode, anime)

    override suspend fun resolveStreams(): List<Hoster> =
        prefetched ?: episodeLoader.getHosters(episode, anime, source)
}
// <-- AM (CONTENT_AGNOSTIC_SESSION)
