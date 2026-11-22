package tachiyomi.data.history

import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryOrigin
import tachiyomi.domain.history.model.HistoryWithRelations
import java.util.Date

object HistoryMapper {
    fun mapHistory(
        id: Long,
        episodeId: Long,
        seenAt: Date?,
        // AM (A_ORIGIN_SURVIVES_A_BACKUP)
        origin: Long,
    ): History = History(
        id = id,
        episodeId = episodeId,
        seenAt = seenAt,
        // AM (A_ORIGIN_SURVIVES_A_BACKUP)
        origin = HistoryOrigin.from(origin),
    )

    fun mapHistoryWithRelations(
        historyId: Long,
        animeId: Long,
        episodeId: Long,
        title: String,
        thumbnailUrl: String?,
        sourceId: Long,
        isFavorite: Boolean,
        coverLastModified: Long,
        episodeNumber: Double,
        seenAt: Date?,
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
        episodeName: String,
        scanlator: String?,
        episodeUrl: String,
    ): HistoryWithRelations = HistoryWithRelations(
        id = historyId,
        episodeId = episodeId,
        animeId = animeId,
        // AM (CUSTOM_INFORMATION) -->
        ogTitle = title,
        // <-- AM (CUSTOM_INFORMATION)
        episodeNumber = episodeNumber,
        seenAt = seenAt,
        coverData = AnimeCover(
            animeId = animeId,
            sourceId = sourceId,
            isAnimeFavorite = isFavorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
        // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
        episodeName = episodeName,
        scanlator = scanlator,
        episodeUrl = episodeUrl,
    )
}
