// AM (MERGE_SETTINGS) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * A merged entry's own settings (see merge_settings.sq).
 *
 * [dedupeMode] is DedupeMode.value rather than an ordinal, since that enum
 * already carries explicit stable values. The info entry is named by source and
 * url like any other child; both empty means the default, which is for the
 * highest-priority child to supply the details.
 */
@Serializable
data class BackupMergeSettings(
    @ProtoNumber(1) var dedupeMode: Long = 0,
    @ProtoNumber(2) var infoAnimeSource: Long = 0,
    @ProtoNumber(3) var infoAnimeUrl: String = "",
)
// <-- AM (MERGE_SETTINGS)
