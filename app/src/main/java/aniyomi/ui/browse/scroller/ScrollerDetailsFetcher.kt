package aniyomi.ui.browse.scroller

import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.model.toSAnime
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import java.util.Collections

// AM (BROWSE_SCROLLER) -->
/**
 * Fills in the creator and tags a card shows.
 *
 * A browse listing is title, url and cover - `popularAnimeParse` and friends
 * populate nothing else - so author, artist and genre are empty on a browse
 * result until something fetches the entry's details. Opening an entry is what
 * normally does that, which is why a card in the feed showed neither.
 *
 * Calls the source's own `getAnimeDetails` and writes the four fields a card
 * reads onto the anime row the browse pager already created. Each card
 * subscribes to that row, so the creator and tags appear on their own once the
 * fetch lands; nothing has to push them into the UI.
 *
 * Deliberately not `UpdateAnimeFromRemote.awaitEpisodesUpdate(fetchDetails =
 * true)`, which looks like the obvious reuse and is not: it runs
 * `syncEpisodesWithSource` unconditionally afterwards, and that throws
 * `NoEpisodesException` on the empty episode list `fetchEpisodes = false`
 * produces. The details would still land, because the write happens before the
 * throw, but the call would fail every time and the feed would depend on that
 * ordering.
 *
 * Writes nothing else: no favorite flag, no title or cover overwrite, no
 * episodes, no history.
 */
class ScrollerDetailsFetcher(
    private val updateAnime: UpdateAnime,
) {

    /**
     * Ids already fetched in this scroller's lifetime, so scrolling back up
     * does not re-request, and so the active card and the card being
     * prefetched behind it cannot both fetch the same entry.
     */
    private val fetched = Collections.synchronizedSet(mutableSetOf<Long>())

    suspend fun fetch(anime: Anime, source: AnimeSource) {
        // Already has what the card displays, so there is nothing to fetch.
        if (anime.author != null || anime.artist != null || !anime.genre.isNullOrEmpty()) return
        // A source that fills its listing completely states the creator and
        // tags there too, so a details round trip per card would buy nothing.
        if (ScrollerFeedMedia.from(anime) != null) return
        if (!fetched.add(anime.id)) return

        try {
            val details = withIOContext { source.getAnimeDetails(anime.toSAnime()) }
            updateAnime.await(
                AnimeUpdate(
                    id = anime.id,
                    author = details.author,
                    artist = details.artist,
                    description = details.description,
                    genre = details.getGenres(),
                ),
            )
        } catch (e: CancellationException) {
            // Scrolled away mid-fetch. Forget it so the card can try again if
            // it comes back on screen.
            fetched.remove(anime.id)
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "BROWSE_SCROLLER details fetch failed for ${anime.title}" }
        }
    }
}
// <-- AM (BROWSE_SCROLLER)
