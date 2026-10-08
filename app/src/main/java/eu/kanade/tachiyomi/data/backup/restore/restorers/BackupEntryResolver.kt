// AM -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import tachiyomi.domain.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId

/**
 * Turns the stable keys a backup carries - (source, url) for an entry, plus an
 * episode url - back into the row ids this device just assigned.
 *
 * Everything structural in a backup is a reference between two rows: a merge
 * child points at its parent, an exclusion at an episode, a reordered episode at
 * the entry it was reordered in. None of those ids survive a restore, so each
 * reference is carried as a key and resolved here, after the rows exist.
 *
 * Constructed per restore rather than injected, because it holds caches that are
 * only sound for the length of one pass: the same entry and the same episode list
 * are asked for repeatedly - once per child of a merge, once per exclusion in it -
 * and nothing can change underneath them while the pass runs. A graph-scoped
 * instance would keep a stale library in memory between restores.
 */
class BackupEntryResolver(
    private val getAnimeByUrlAndSourceId: GetAnimeByUrlAndSourceId,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
) {

    private val animeByKey = mutableMapOf<Pair<Long, String>, Anime?>()
    private val episodeIdsByAnimeId = mutableMapOf<Long, Map<String, Long>>()

    /** The restored entry for a backup's (source, url), or null if it was not restored. */
    suspend fun anime(source: Long, url: String): Anime? {
        if (url.isEmpty()) return null
        return animeByKey.getOrPut(source to url) {
            getAnimeByUrlAndSourceId.await(url, source)
        }
    }

    /**
     * The restored episode id for [episodeUrl] under the entry [source]/[url].
     *
     * Null when either the entry or the episode is missing - a backup can name an
     * episode the source no longer lists, or an entry the user chose not to
     * restore, and neither is an error worth failing the restore over.
     */
    suspend fun episodeId(source: Long, url: String, episodeUrl: String): Long? {
        val anime = anime(source, url) ?: return null
        return episodeId(anime.id, episodeUrl)
    }

    /** As [episodeId], for an entry already resolved. */
    suspend fun episodeId(animeId: Long, episodeUrl: String): Long? {
        if (episodeUrl.isEmpty()) return null
        val byUrl = episodeIdsByAnimeId.getOrPut(animeId) {
            getEpisodesByAnimeId.await(animeId).associate { it.url to it.id }
        }
        return byUrl[episodeUrl]
    }
}
// <-- AM
