package aniyomi.ui.browse.scroller

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
 * Resolves a browse result to something the scroller can play.
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
 * Results are cached for the lifetime of one scroller, keyed by anime id, so
 * scrolling back up does not re-request. Failures cache as [Unresolved] -
 * a source that cannot produce a video should not be retried once per frame.
 */
class ScrollerVideoResolver(
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

    suspend fun resolve(anime: Anime, source: AnimeSource): Result {
        cache[anime.id]?.let { return it }

        // A source that states its media in the listing needs none of what
        // follows - no details fetch, no episode list, no hoster, no video
        // list. See ScrollerFeedMedia for why that distinction is the whole
        // difference between a feed that keeps up and one that does not.
        ScrollerFeedMedia.from(anime)?.let { feed ->
            val httpSource = source as? AnimeHttpSource
            val result = Result.Playable(
                url = feed.videoUrl,
                audioUrl = feed.audioUrl,
                httpHeaderFields = httpSource?.let { httpHeaderFields(null, it) },
                httpSource = httpSource,
                headers = httpSource?.headers,
            )
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

        cache[anime.id] = result
        if (cache.size > MAX_CACHED) {
            cache.remove(cache.keys.first())
        }
        return result
    }

    /**
     * Mirrors PlayerViewModel.setHttpOptions(): the video's own headers if it
     * carries any, otherwise the source's, flattened into the comma-separated
     * form mpv expects with commas inside values escaped.
     */
    private fun httpHeaderFields(video: Video?, httpSource: AnimeHttpSource?): String? {
        httpSource ?: return null
        return (video?.headers ?: httpSource.headers)
            .toMultimap()
            .mapValues { it.value.firstOrNull() ?: "" }
            .map { it.key + ": " + it.value.replace(",", "\\,") }
            .joinToString(",")
            .takeIf { it.isNotEmpty() }
    }

    private companion object {
        /**
         * Generous enough that scrolling back through what was just watched is
         * free, bounded so a long session does not hold every url it ever saw.
         */
        const val MAX_CACHED = 128
    }
}
// <-- AM (BROWSE_SCROLLER)
