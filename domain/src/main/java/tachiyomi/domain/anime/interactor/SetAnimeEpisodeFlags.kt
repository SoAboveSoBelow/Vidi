package tachiyomi.domain.anime.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.model.EpisodeViewMode
import tachiyomi.domain.anime.repository.AnimeRepository

@Inject
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
                // AM (ENTRY_SCROLLER): the whole view-mode field, not the
                // previews bit alone. Choosing previews IS choosing a view, so
                // leaving the scroller bit set would have the switch report a
                // change the list never made.
                episodeFlags = anime.episodeFlags.setFlag(flag, Anime.EPISODE_VIEW_MODE_MASK),
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
     * Applies one of the named episode view modes.
     *
     * AM (ENTRY_SCROLLER): two bits rather than one - see
     * [Anime.episodeViewMode]. Written as one masked field so every mode
     * clears the others, whichever one is being left.
     */
    suspend fun awaitSetEpisodeViewMode(anime: Anime, viewMode: EpisodeViewMode): Boolean {
        return animeRepository.update(
            AnimeUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(viewMode.flag(), Anime.EPISODE_VIEW_MODE_MASK),
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
                    // AM (ENTRY_SCROLLER): the whole view-mode field.
                    .setFlag(showPreviews, Anime.EPISODE_VIEW_MODE_MASK)
                    .setFlag(showSummaries, Anime.EPISODE_SUMMARIES_MASK),
                // <-- AY
            ),
        )
    }

    // AM (ENTRY_SCROLLER) -->
    /** A view mode as the pair of bits that stores it. */
    private fun EpisodeViewMode.flag(): Long = when (this) {
        EpisodeViewMode.SIMPLIFIED -> Anime.EPISODE_SHOW_NOT_PREVIEWS or Anime.EPISODE_SHOW_NOT_SCROLLER
        EpisodeViewMode.PREVIEW -> Anime.EPISODE_SHOW_PREVIEWS or Anime.EPISODE_SHOW_NOT_SCROLLER
        EpisodeViewMode.SCROLLER -> Anime.EPISODE_SHOW_SCROLLER
    }
    // <-- AM (ENTRY_SCROLLER)

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
