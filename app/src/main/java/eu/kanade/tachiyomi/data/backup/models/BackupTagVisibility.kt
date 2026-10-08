// AM (TAG_LIMIT) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One tag the user pinned to the shown or hidden side of an entry's tag list
 * (see anime_tag_visibility.sq).
 *
 * Only moved tags have a row, here as in the database: a tag with no override
 * falls back to the split LibraryPreferences.maxTagsShown produces.
 */
@Serializable
data class BackupTagVisibility(
    @ProtoNumber(1) var tag: String = "",
    @ProtoNumber(2) var visible: Boolean = false,
)
// <-- AM (TAG_LIMIT)
