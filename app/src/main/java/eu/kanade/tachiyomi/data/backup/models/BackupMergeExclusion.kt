// AM (MERGED_SOURCES) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One episode the user removed from a merged entry's combined list (see
 * merge_episode_exclusions.sq).
 *
 * The excluded episode belongs to a child, so it is named by that child's source
 * and url plus the episode's own url - the same stable triple
 * [BackupEpisodeOrder] uses.
 */
@Serializable
data class BackupMergeExclusion(
    @ProtoNumber(1) var animeSource: Long = 0,
    @ProtoNumber(2) var animeUrl: String = "",
    @ProtoNumber(3) var episodeUrl: String = "",
)
// <-- AM (MERGED_SOURCES)
