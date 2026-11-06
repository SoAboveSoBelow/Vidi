package tachiyomi.domain.anime.interactor

import aniyomi.domain.anime.model.SeasonAnime
// AM (MERGED_SOURCES) -->
import aniyomi.domain.merge.model.MERGED_SOURCE_ID
import aniyomi.domain.merge.repository.MergeChildRepository
// <-- AM (MERGED_SOURCES)
// AM (CUSTOM_EPISODE_ORDER) -->
import aniyomi.domain.order.interactor.GetEpisodeOrder
import aniyomi.domain.order.model.ResolvedEpisodeOrder
// <-- AM (CUSTOM_EPISODE_ORDER)
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
// AM (MERGED_SOURCES) -->
import kotlinx.coroutines.flow.flowOf
// <-- AM (MERGED_SOURCES)
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.episode.model.Episode

@Inject
class GetAnimeWithEpisodesAndSeasons(
    private val animeRepository: AnimeRepository,
    // AM (MERGED_SOURCES) -->
    private val mergeChildRepository: MergeChildRepository,
    // <-- AM (MERGED_SOURCES)
    // AM (CUSTOM_EPISODE_ORDER) -->
    private val getEpisodeOrder: GetEpisodeOrder,
    // <-- AM (CUSTOM_EPISODE_ORDER)
) {

    // AM (CUSTOM_EPISODE_ORDER) -->
    // The episodes element is the resolved order rather than a bare list: it
    // carries each episode's season (for the season switcher) and whether the
    // order is already final, for merged and ordinary entries alike. Both kinds
    // now route through GetEpisodeOrder.subscribe, so a custom order - or a
    // merge gaining a child or moving one between seasons - shows up live
    // instead of only after reopening the screen.
    //
    // The order is resolved against the LIVE host, not a snapshot taken when
    // the screen opened. GetEpisodeOrder.resolve reads host.sorting to decide
    // whether the stored/union order outranks the user's sort (isPreordered),
    // so a snapshot left that decision frozen at whatever the sort was on
    // open: changing the sort re-emitted the anime but not the order, and a
    // merged entry - preordered under by-source, which is the default - kept
    // reporting isPreordered until the screen was reopened, leaving the sort
    // options doing nothing on it. Keyed on sorting alone so ordinary updates
    // (covers, last-update, library flags) don't tear the episode
    // subscriptions down and rebuild them.
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun subscribe(
        id: Long,
        applyScanlatorFilter: Boolean = false,
    ): Flow<Triple<Anime, ResolvedEpisodeOrder, List<SeasonAnime>>> {
        val animeFlow = animeRepository.getAnimeByIdAsFlow(id)
        val seasonsFlow = if (animeRepository.getAnimeById(id).source == MERGED_SOURCE_ID) {
            // Merged entries show their seasons through the in-list switcher,
            // not the native season grid - which is what a non-empty list here
            // would drive.
            flowOf(emptyList())
        } else {
            // AY -->
            animeRepository.getAnimeSeasonsByIdAsFlow(id)
            // <-- AY
        }
        return combine(
            animeFlow,
            animeFlow
                .distinctUntilChangedBy { it.sorting }
                .flatMapLatest { host -> getEpisodeOrder.subscribe(host, applyScanlatorFilter) },
            seasonsFlow,
            ::Triple,
        )
    }
    // <-- AM (CUSTOM_EPISODE_ORDER)

    suspend fun awaitAnime(id: Long): Anime {
        return animeRepository.getAnimeById(id)
    }

    suspend fun awaitEpisodes(id: Long, applyScanlatorFilter: Boolean = false): List<Episode> {
        // AM (CUSTOM_EPISODE_ORDER) -->
        // getEpisodeOrder covers both host kinds: the merged union, and an
        // ordinary entry's own episodes, each with any custom order applied.
        return getEpisodeOrder.await(animeRepository.getAnimeById(id), applyScanlatorFilter)
        // <-- AM (CUSTOM_EPISODE_ORDER)
    }

    // AY -->
    suspend fun awaitSeasons(id: Long): List<SeasonAnime> {
        // AM (MERGED_SOURCES) -->
        // Merged entries' seasons come from merge_children, not parent_id.
        if (animeRepository.getAnimeById(id).source == MERGED_SOURCE_ID) {
            return mergeChildRepository.getSeasonsByMergeParentId(id)
        }
        // <-- AM (MERGED_SOURCES)
        return animeRepository.getAnimeSeasonsById(id)
    }
    // <-- AY
}
