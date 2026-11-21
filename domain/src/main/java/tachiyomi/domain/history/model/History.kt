package tachiyomi.domain.history.model

import java.util.Date

data class History(
    val id: Long,
    val episodeId: Long,
    val seenAt: Date?,
    // AM (A_ORIGIN_SURVIVES_A_BACKUP): what wrote this row - see HistoryOrigin.
    // Defaulted to Player so the many places that build a History unchanged keep
    // the meaning every row had before an origin was recorded at all.
    val origin: HistoryOrigin = HistoryOrigin.Player,
) {
    companion object {
        fun create() = History(
            id = -1L,
            episodeId = -1L,
            seenAt = null,
        )
    }
}
