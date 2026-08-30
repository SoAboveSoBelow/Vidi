package animiru.domain.player.model

// AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
/**
 * A single row of the persisted temp-position cache - the DB-backed counterpart to what
 * used to be a purely in-memory map on PlayerViewModel (see
 * RecentEpisodePositionManager). Survives process death; pruned to the N most-recently
 * updated rows, where N is [animiru.domain.player.service.PlayerPreferences.recentEpisodePositionSlots].
 */
data class EpisodeTempPosition(
    val animeId: Long,
    val episodeId: Long,
    val positionMs: Long,
    val updatedAt: Long,
)
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
