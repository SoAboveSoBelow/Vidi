package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryOrigin
import java.util.Date

@Serializable
data class BackupHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastSeen: Long,
    // AM (A_ORIGIN_SURVIVES_A_BACKUP) -->
    /**
     * Which of the two wrote the row - see HistoryOrigin.
     *
     * Additive, and defaulted to Player's value, so this stays readable by
     * anything that does not know the field: a protobuf decoder skips an
     * unknown field number by its wire type, which is what already lets
     * BackupDetector read a file containing entries it does not declare. An
     * upstream restore therefore behaves exactly as it does today, and a backup
     * written by an upstream build arrives here as Player, which is what every
     * row meant before the distinction existed.
     */
    @ProtoNumber(3) var origin: Long = HistoryOrigin.Player.value,
    // <-- AM (A_ORIGIN_SURVIVES_A_BACKUP)
) {
    fun getHistoryImpl(): History {
        return History.create().copy(
            seenAt = Date(lastSeen),
            // AM (A_ORIGIN_SURVIVES_A_BACKUP)
            origin = HistoryOrigin.from(origin),
        )
    }
}
