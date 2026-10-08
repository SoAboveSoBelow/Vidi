// AM (CUSTOM_EPISODE_ORDER) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One episode moved out of its default position in an entry's list (see
 * episode_order.sq).
 *
 * The row hangs off the entry it was reordered *in*, which for a merged entry is
 * the merge parent rather than the child owning the episode - hence
 * [animeSource] and [animeUrl], naming that owner stably so the episode can be
 * found again after ids are reassigned. Both override fields stay independently
 * optional, as in the database.
 */
@Serializable
data class BackupEpisodeOrder(
    @ProtoNumber(1) var animeSource: Long = 0,
    @ProtoNumber(2) var animeUrl: String = "",
    @ProtoNumber(3) var episodeUrl: String = "",
    @ProtoNumber(4) var seasonNumber: Long? = null,
    @ProtoNumber(5) var sortKey: Double? = null,
)
// <-- AM (CUSTOM_EPISODE_ORDER)
