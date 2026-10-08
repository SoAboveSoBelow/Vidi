// AM (MERGED_SOURCES) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One entry contributing to a merged entry (see merge_children.sq).
 *
 * The child is named by source and url, not by anime id: membership is a join
 * between two rows whose ids are both reassigned on restore, which is why the
 * merge tables could not be carried by the backup before this existed.
 */
@Serializable
data class BackupMergeChild(
    @ProtoNumber(1) var animeSource: Long = 0,
    @ProtoNumber(2) var animeUrl: String = "",
    @ProtoNumber(3) var priority: Long = 0,
    @ProtoNumber(4) var seasonNumber: Long = 0,
)
// <-- AM (MERGED_SOURCES)
