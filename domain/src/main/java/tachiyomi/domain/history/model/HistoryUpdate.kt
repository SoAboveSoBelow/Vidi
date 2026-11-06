package tachiyomi.domain.history.model

import java.util.Date

data class HistoryUpdate(
    val episodeId: Long,
    val seenAt: Date,
    // AM (HISTORY_ORIGIN)
    val origin: HistoryOrigin = HistoryOrigin.Player,
)
