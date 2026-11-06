package animiru.domain.player.model

// AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
/**
 * A single row of the persisted temp-position cache - the DB-backed counterpart to what
 * used to be a purely in-memory map on PlayerViewModel (see
 * RecentPositionManager). Survives process death; pruned to the N most-recently
 * updated rows, where N is [animiru.domain.player.service.PlayerPreferences.recentEpisodePositionSlots].
 *
 * AM (CONTENT_AGNOSTIC_SESSION): [sessionKey] is opaque here on purpose. What a
 * session is - a library episode, an external file, a feed card - is the player's
 * concern, and encoding it into this layer would mean a migration every time another
 * kind of session appears.
 */
data class SessionTempPosition(
    val sessionKey: String,
    val positionMs: Long,
    val updatedAt: Long,
)
// <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
