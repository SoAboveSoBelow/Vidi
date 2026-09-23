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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    suspend fun subscribe(
        id: Long,
        applyScanlatorFilter: Boolean = false,
    ): Flow<Triple<Anime, ResolvedEpisodeOrder, List<SeasonAnime>>> {
        val anime = animeRepository.getAnimeById(id)
        val seasonsFlow = if (anime.source == MERGED_SOURCE_ID) {
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
            animeRepository.getAnimeByIdAsFlow(id),
            getEpisodeOrder.subscribe(anime, applyScanlatorFilter),
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
