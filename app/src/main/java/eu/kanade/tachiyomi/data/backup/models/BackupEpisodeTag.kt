// AM (EPISODE_TAGS) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One tag the user set on a single episode (see episode_tags.sq).
 *
 * [visible] true is a tag they placed on that episode, false one they took off
 * it. Only episodes the user has actually touched carry any of these; the rest
 * inherit their entry's tags and store nothing, here as in the database.
 *
 * Carried on the episode rather than on the entry, because that is how the rows
 * are keyed: a tag belongs to the episode and reads the same in every merge the
 * episode's entry is a source of.
 */
@Serializable
data class BackupEpisodeTag(
    @ProtoNumber(1) var tag: String = "",
    @ProtoNumber(2) var visible: Boolean = false,
)
// <-- AM (EPISODE_TAGS)
