// AM (MERGED_SOURCES) -->
package eu.kanade.tachiyomi.source

import aniyomi.domain.merge.model.MERGED_SOURCE_ID
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode

/**
 * Internal pseudo-source identifying a merged library entry: an anime row whose
 * `source` is [ID] is a merge parent with no episodes of its own - its children
 * (recorded in the `merge_children` join table, each keeping its own real
 * source, season parent, and favorite state) hold the actual episodes, unioned
 * together for display. See the "merge series" design notes; this is
 * schema/registration scaffolding only.
 *
 * Never appears in Browse (nothing filters it there yet - TODO once a Browse
 * source-list filter exists, add `source.id != MergedSource.ID` alongside the
 * existing Local/Stub exclusions). Never actually fetches episodes or videos
 * itself: playback (PlayerViewModel) and the anime detail screen
 * (GetAnimeWithEpisodesAndSeasons, AnimeViewModel.fetchAllFromSource) both
 * resolve to each episode's/child's real owning source instead of calling into
 * this class, so the methods below are unreachable in those paths and
 * intentionally throw rather than pretend to do something. Any OTHER caller
 * that resolves a merge parent's source directly and calls into it is a sign
 * that caller needs the same treatment, not that these should stop throwing.
 */
@Inject
@SingleIn(AppScope::class)
class MergedSource : AnimeSource {

    override val id: Long = ID

    // TODO: proper string resource once this is ever user-visible.
    override val name: String = "Merged"

    override val lang: String = ""

    override val supportsLatest: Boolean = false

    override suspend fun getPopularAnime(page: Int): AnimesPage = notUsed()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = notUsed()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = notUsed()

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate = notUsed()

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = notUsed()

    override val supportsRelatedAnime: Boolean = false

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = notUsed()

    private fun notUsed(): Nothing = throw IllegalStateException(
        "MergedSource has no direct source behavior - resolve to the episode's real owning source instead",
    )

    companion object {
        const val ID = MERGED_SOURCE_ID
    }
}
// <-- AM (MERGED_SOURCES)
