// AM (NAMED_SEASONS) -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One named or reordered season of an entry (see entry_seasons.sq).
 *
 * [number] is the season's stable identity within its entry and is what
 * [BackupMergeChild.seasonNumber] and [BackupEpisodeOrder.seasonNumber] point
 * at, so it is carried verbatim rather than renumbered. [name] null means
 * unnamed, labelled by display position.
 */
@Serializable
data class BackupEntrySeason(
    @ProtoNumber(1) var number: Long = 0,
    @ProtoNumber(2) var name: String? = null,
    @ProtoNumber(3) var sortOrder: Long = 0,
)
// <-- AM (NAMED_SEASONS)
