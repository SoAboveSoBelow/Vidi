package aniyomi.player.clip

import android.content.Context
import aniyomi.ui.browse.scroller.ScrollerFeedMedia
import eu.kanade.tachiyomi.data.media.MediaCache
import eu.kanade.tachiyomi.data.media.MediaProxyServer

import eu.kanade.domain.anime.model.toSAnime
import eu.kanade.domain.episode.model.copyFromSEpisode
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import okhttp3.Headers
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode

// AM (BROWSE_SCROLLER) -->
/**
 * Resolves an entry to the streams that play it.
 *
 * AM (CLIP_PLAYBACK): moved out of the browse feed, which wrote it first. The
 * real player needs the same answer for the same entries - most of all the
 * first branch below, where a listing states its own media and the pair it
 * states is what should be played. The session player resolving those entries
 * its own way is what left it playing a source's pre-muxed convenience file
 * whose audio runs only as long as its video, while the feed played the full
 * track beside the clip.
 *
 * Deliberately writes nothing. [EpisodeLoader] and [HosterLoader] are the
 * app's existing resolution path and are reused as-is, but the episode list
 * comes straight off the source rather than through
 * `UpdateAnimeFromRemote.awaitEpisodesUpdate()`: persisting episode rows for
 * every entry a user flicks past would fill the database with entries they
 * never chose, and the seen/progress rows that follow playback are exactly
 * what a feed must not produce. A transient [Episode] (id -1, never inserted)
 * carries the url the loaders need and nothing else, so "scrolling past
 * something leaves no trace" is structural here rather than a flag that has
 * to be remembered at every write site.
 *
 * Successful results are cached for the lifetime of one scroller, keyed by
 * anime id, so scrolling back up does not re-request. Failures are not cached:
 * see the note in [resolve].
 */
class ClipVideoResolver(
    private val episodeLoader: EpisodeLoader,
    private val hosterLoader: HosterLoader,
) {

    sealed interface Result {
        /**
         * [httpHeaderFields] is mpv's own `http-header-fields` form, built the
         * same way PlayerViewModel.setHttpOptions() builds it. A source that
         * requires a Referer or its own user agent fails the TLS handshake or
         * the first segment fetch without them, so the url alone is not enough
         * to play anything.
         */
        data class Playable(
            val url: String,
            /**
             * A separate audio track, for a source that serves video and
             * audio as different files - Coub's own arrangement.
             */
            val audioUrl: String? = null,
            val httpHeaderFields: String?,
            /** Non-null only for an http source, which is what the cache proxy needs. */
            val httpSource: AnimeHttpSource?,
            val headers: Headers?,
        ) : Result
        data object Unresolved : Result
    }

    private val cache = LinkedHashMap<Long, Result>()

    fun cached(animeId: Long): Result? = cache[animeId]

    /** Forgets [animeId]'s result, so the next ask resolves it again. */
    fun invalidate(animeId: Long) {
        cache.remove(animeId)
    }

    suspend fun resolve(anime: Anime, source: AnimeSource): Result {
        // AM (CLIP_RESOLVE_ONLY_CACHES_SUCCESS) -->
        // Successes only. A failure used to be cached here for the life of
        // the feed, which is the whole reason a card could sit black while
        // the same entry opened in the player played immediately: the player
        // caches no failures at all, and HosterLoader.getBestVideo already
        // walks every hoster and video on each call - so asking again IS the
        // retry, and it is the same retry the player gets. Caching the
        // failure meant the second ask never happened.
        (cache[anime.id] as? Result.Playable)?.let { return it }
        // <-- AM (CLIP_RESOLVE_ONLY_CACHES_SUCCESS)

        // A source that states its media in the listing needs none of what
        // follows - no details fetch, no episode list, no hoster, no video
        // list. See ScrollerFeedMedia for why that distinction is the whole
        // difference between a feed that keeps up and one that does not.
        statedBy(anime, source)?.let { result ->
            cache[anime.id] = result
            return result
        }

        val result = try {
            withIOContext {
                val episode = source.getEpisodeList(anime.toSAnime())
                    .firstOrNull()
                    ?.let { Episode.create().copyFromSEpisode(it).copy(animeId = anime.id) }
                    ?: return@withIOContext Result.Unresolved

                val hosters = episodeLoader.getHosters(episode, anime, source)
                val video = hosterLoader.getBestVideo(source, hosters)
                    ?: return@withIOContext Result.Unresolved

                val resolved = hosterLoader.getResolvedVideo(source, video) ?: video
                // videoUrl is a non-null String that defaults to empty, and
                // the "null" sentinel is what Video's own factory writes when
                // a source hands it nothing.
                val url = resolved.videoUrl.takeIf { it.isNotEmpty() && it != "null" }
                    ?: return@withIOContext Result.Unresolved

                val httpSource = source as? AnimeHttpSource
                Result.Playable(
                    url = url,
                    httpHeaderFields = httpHeaderFields(resolved, httpSource),
                    httpSource = httpSource,
                    headers = resolved.headers ?: httpSource?.headers,
                )
            }
        } catch (e: CancellationException) {
            // Scrolled away before this finished. Nothing is cached, so the
            // item resolves again if it comes back on screen.
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "BROWSE_SCROLLER resolve failed for ${anime.title}" }
            Result.Unresolved
        }

        // AM (CLIP_RESOLVE_ONLY_CACHES_SUCCESS)
        if (result is Result.Playable) {
            cache[anime.id] = result
            if (cache.size > MAX_CACHED) {
                cache.keys.firstOrNull()?.let { invalidate(it) }
            }
        }
        return result
    }

    companion object {
        /**
         * The streams an entry's own listing states, or null where it states
         * none.
         *
         * Public and free of everything else this class does, because it is
         * the one part the session player needs: it has its own resolution for
         * entries the listing says nothing about, and needs no episode list,
         * hoster or video list for the ones it does.
         */
        fun statedBy(anime: Anime, source: AnimeSource?): Result.Playable? {
            val feed = ScrollerFeedMedia.from(anime) ?: return null
            val httpSource = source as? AnimeHttpSource
            return Result.Playable(
                url = feed.videoUrl,
                audioUrl = feed.audioUrl,
                httpHeaderFields = httpSource?.let { httpHeaderFields(null, it) },
                httpSource = httpSource,
                headers = httpSource?.headers,
            )
        }

        /**
         * Mirrors PlayerViewModel's own http options: the video's headers if
         * it carries any, otherwise the source's, flattened into the
         * comma-separated form mpv expects with commas inside values escaped.
         */
        fun httpHeaderFields(video: Video?, httpSource: AnimeHttpSource?): String? {
            httpSource ?: return null
            return (video?.headers ?: httpSource.headers)
                .toMultimap()
                .mapValues { it.value.firstOrNull() ?: "" }
                .map { it.key + ": " + it.value.replace(",", "\\,") }
                .joinToString(",")
                .takeIf { it.isNotEmpty() }
        }

        /**
         * Routes [playable]'s video through the app's media cache proxy, so
         * bytes already fetched for an entry are still there the next time
         * anything plays it.
         *
         * The cache key is the entry, shared by every consumer of it: the feed
         * pulls a clip as you scroll past it, and opening that same clip in
         * the player then reads those bytes instead of fetching the file
         * again. That sharing is the whole reason this is one function rather
         * than one per host - the player fetching its own copy under its own
         * key is why opening an entry from the feed buffered as though nothing
         * had ever been fetched.
         *
         * [owner] identifies the consumer, not the content, so two hosts
         * holding the same entry open do not drop each other's registration.
         */
        fun cacheableUrl(
            context: Context,
            retainMaxBytes: Long,
            animeId: Long,
            playable: Result.Playable,
            owner: String,
        ): String {
            val httpSource = playable.httpSource ?: return playable.url
            if (!playable.url.startsWith("http")) return playable.url
            if (playable.url.contains("127.0.0.1") || playable.url.contains("localhost")) return playable.url

            // Whether anything is retained at all is the "Episode cache limit"
            // preference's decision, not any one host's.
            MediaProxyServer.attachCache(MediaCache.get(context, retainMaxBytes > 0, retainMaxBytes))

            return MediaProxyServer.proxyUrlFor(
                url = playable.url,
                headers = playable.headers ?: httpSource.headers,
                httpClient = httpSource.client,
                cacheKey = cacheKey(animeId),
                owner = owner,
            )?.second ?: playable.url
        }

        /** The entry's content key, which every host of it shares. */
        fun cacheKey(animeId: Long) = "scroller-a$animeId"

        /**
         * Generous enough that scrolling back through what was just watched is
         * free, bounded so a long session does not hold every url it ever saw.
         */
        private const val MAX_CACHED = 128

    }
}
// <-- AM (BROWSE_SCROLLER)
