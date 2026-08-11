package tachiyomi.domain.anime.interactor

import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.model.EpisodeViewMode
import tachiyomi.domain.anime.repository.AnimeRepository

class SetAnimeEpisodeFlags(
    private val animeRepository: AnimeRepository,
) {

    suspend fun awaitSetDownloadedFilter(anime: Anime, flag: Long): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_DOWNLOADED_MASK),
            ),
        )
    }

    suspend fun awaitSetUnseenFilter(anime: Anime, flag: Long): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_UNSEEN_MASK),
            ),
        )
    }

    suspend fun awaitSetBookmarkFilter(anime: Anime, flag: Long): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_BOOKMARKED_MASK),
            ),
        )
    }

    // AY -->
    suspend fun awaitSetFillermarkFilter(anime: Anime, flag: Long): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_FILLERMARKED_MASK),
            ),
        )
    }
    // <-- AY

    suspend fun awaitSetDisplayMode(anime: Anime, flag: Long): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_DISPLAY_MASK),
            ),
        )
    }

    suspend fun awaitSetSortingModeOrFlipOrder(anime: Anime, flag: Long): Boolean {
        val newFlags = anime.episodeFlags.let {
            if (anime.sorting == flag) {
                // Just flip the order
                val orderFlag = if (anime.sortDescending()) {
                    Anime.EPISODE_SORT_ASC
                } else {
                    Anime.EPISODE_SORT_DESC
                }
                it.setFlag(orderFlag, Anime.EPISODE_SORT_DIR_MASK)
            } else {
                // Set new flag with ascending order
                it
                    .setFlag(flag, Anime.EPISODE_SORTING_MASK)
                    .setFlag(Anime.EPISODE_SORT_ASC, Anime.EPISODE_SORT_DIR_MASK)
            }
        }
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = newFlags,
            ),
        )
    }

// AY -->
    suspend fun awaitShowEpisodePreviews(anime: Anime, flag: Long): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_PREVIEWS_MASK),
            ),
        )
    }

    suspend fun awaitShowEpisodeSummaries(anime: Anime, flag: Long): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_SUMMARIES_MASK),
            ),
        )
    }
// <-- AY

    // AM (EPISODE_VIEW_MODE) -->

    /**
     * Applies one of the 3 named episode view modes, setting both the previews
     * and metadata bits together so the result always lands on exactly one of
     * SIMPLIFIED / PREVIEW / MINIMAL - never the unused 4th bit combination.
     */
    suspend fun awaitSetEpisodeViewMode(anime: Anime, viewMode: EpisodeViewMode): Boolean {
        val (previews, hideMetadata) = when (viewMode) {
            EpisodeViewMode.SIMPLIFIED -> Anime.EPISODE_SHOW_NOT_PREVIEWS to Anime.EPISODE_SHOW_METADATA
            EpisodeViewMode.PREVIEW -> Anime.EPISODE_SHOW_PREVIEWS to Anime.EPISODE_SHOW_METADATA
            EpisodeViewMode.MINIMAL -> Anime.EPISODE_SHOW_PREVIEWS to Anime.EPISODE_HIDE_METADATA
        }
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags
                    .setFlag(previews, Anime.EPISODE_PREVIEWS_MASK)
                    .setFlag(hideMetadata, Anime.EPISODE_METADATA_MASK),
            ),
        )
    }
    // <-- AM (EPISODE_VIEW_MODE)

    suspend fun awaitSetAllFlags(
        animeId: Long,
        unseenFilter: Long,
        downloadedFilter: Long,
        bookmarkedFilter: Long,
        // AY -->
        fillermarkedFilter: Long,
        // <-- AY
        sortingMode: Long,
        sortingDirection: Long,
        displayMode: Long,
        // AY -->
        showPreviews: Long,
        showSummaries: Long,
        // <-- AY
        // AM (EPISODE_VIEW_MODE) -->
        hideMetadata: Long,
        // <-- AM (EPISODE_VIEW_MODE)
    ): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = animeId,
                episodeFlags = 0L.setFlag(unseenFilter, Anime.EPISODE_UNSEEN_MASK)
                    .setFlag(downloadedFilter, Anime.EPISODE_DOWNLOADED_MASK)
                    .setFlag(bookmarkedFilter, Anime.EPISODE_BOOKMARKED_MASK)
                    // AY -->
                    .setFlag(fillermarkedFilter, Anime.EPISODE_FILLERMARKED_MASK)
                    // <-- AY
                    .setFlag(sortingMode, Anime.EPISODE_SORTING_MASK)
                    .setFlag(sortingDirection, Anime.EPISODE_SORT_DIR_MASK)
                    .setFlag(displayMode, Anime.EPISODE_DISPLAY_MASK)
                    // AY -->
                    .setFlag(showPreviews, Anime.EPISODE_PREVIEWS_MASK)
                    .setFlag(showSummaries, Anime.EPISODE_SUMMARIES_MASK)
                    // <-- AY
                    // AM (EPISODE_VIEW_MODE) -->
                    .setFlag(hideMetadata, Anime.EPISODE_METADATA_MASK),
                // <-- AM (EPISODE_VIEW_MODE)
            ),
        )
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
