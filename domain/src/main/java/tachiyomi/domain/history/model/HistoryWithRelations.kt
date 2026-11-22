package tachiyomi.domain.history.model

import tachiyomi.domain.anime.interactor.GetCustomAnimeInfo
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.anime.model.CustomAnimeInfoHolder
import java.util.Date

data class HistoryWithRelations(
    val id: Long,
    val episodeId: Long,
    val animeId: Long,
    // AM (CUSTOM_INFORMATION) -->
    val ogTitle: String,
    // <-- AM (CUSTOM_INFORMATION)
    val episodeNumber: Double,
    val seenAt: Date?,
    val coverData: AnimeCover,
    // AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS) -->
    /**
     * What the download cache needs to be asked whether this episode is on
     * disk: it is keyed by these, the entry's [ogTitle] and the source in
     * [coverData], because a download is identified by its folder rather than
     * by any id.
     *
     * Carried on the row rather than fetched per row when the filter runs - the
     * query already has the episode joined, and asking again would be a query
     * per visible item.
     */
    val episodeName: String,
    val scanlator: String?,
    val episodeUrl: String,
    // <-- AM (A_HISTORY_FILTERS_ON_WHAT_IT_KNOWS)
) {
    // AM (CUSTOM_INFORMATION) -->
    val title: String = getCustomAnimeInfo.get(animeId)?.title ?: ogTitle

    companion object {
        private val getCustomAnimeInfo: GetCustomAnimeInfo by lazy {
            CustomAnimeInfoHolder.getCustomAnimeInfo
        }
    }
    // <-- AM (CUSTOM_INFORMATION)
}
