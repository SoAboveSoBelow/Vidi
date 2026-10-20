// AM (EXTERNAL_STREAM_SOURCE) -->
package eu.kanade.tachiyomi.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource

/**
 * Internal source backing a video opened from outside the app over the network.
 *
 * A streamed external video used to run as a parallel session: no entry, no
 * episode, its own queue and its own dialog, and every feature that works on an
 * episode - the episode list, downloading, next/previous - had to be taught
 * about it separately or did not work at all. It is, as far as playback is
 * concerned, a url that returns a video, which is what an extension is, so it
 * is modelled as one here and nothing downstream needs to know where it came
 * from.
 *
 * The entry's url is the stream url itself, and the single episode repeats it
 * (see [getEpisodeList]), so resolving the episode never needs state held
 * anywhere else - a reload, or a resume days later, plays from the same url the
 * row was created with.
 *
 * Nothing is fetched or parsed: [getVideoList] hands back the url it was given.
 * The inherited browse methods would request [baseUrl], which is empty, and are
 * unreachable because the source is never browsed - it has no catalogue to
 * list, and entries reach it only by being created against [ID].
 */
@Inject
@SingleIn(AppScope::class)
class ExternalStreamSource : AnimeHttpSource() {

    override val id: Long = ID

    override val name: String = "External"

    // Empty, like MergedSource: GetEnabledSources keeps a source whose lang is
    // in the user's enabled languages, so "all" put this in the source list.
    // Nothing browses it - entries reach it by being created against ID.
    override val lang: String = ""

    override val baseUrl: String = ""

    override val supportsLatest: Boolean = false

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = episodeNameFor(anime.url).ifBlank { anime.title }
                episode_number = 1f
            },
        )
    }

    /**
     * The file's own name, which is what a saved video should be called.
     *
     * Taken from the last path segment with the query and fragment dropped, so
     * `https://host/dir/video.mkv?token=x` is `video`. Blank when the url ends
     * in a directory or carries no path at all, which the caller falls back
     * from.
     */
    private fun episodeNameFor(url: String): String {
        return url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .trim()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return listOf(Video(episode.url, name))
    }

    companion object {
        /** Alongside the local source at 0 and the merge parent at -1. */
        const val ID: Long = -2L
    }
}
// <-- AM (EXTERNAL_STREAM_SOURCE)
