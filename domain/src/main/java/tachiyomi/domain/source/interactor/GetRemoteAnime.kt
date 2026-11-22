package tachiyomi.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
// AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
import tachiyomi.domain.source.model.SourcePageCoverage
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.repository.SourceRepository

@Inject
class GetRemoteAnime(
    private val repository: SourceRepository,
) {

    // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED): listingKey is forwarded, not
    // interpreted.
    operator fun invoke(
        sourceId: Long,
        query: String,
        filterList: AnimeFilterList,
        listingKey: String? = null,
        // AM (A_SKIP_SERVES_THE_TRIM_ONLY): forwarded, not interpreted.
        skipCovered: () -> Boolean = { false },
        // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS): likewise.
        coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
    ): SourcePagingSource {
        return when (query) {
            QUERY_POPULAR -> repository.getPopular(sourceId, listingKey, skipCovered, coveredFraction)
            QUERY_LATEST -> repository.getLatest(sourceId, listingKey, skipCovered, coveredFraction)
            else -> repository.search(sourceId, query, filterList, listingKey, skipCovered, coveredFraction)
        }
    }

    companion object {
        const val QUERY_POPULAR = "eu.kanade.domain.source.interactor.POPULAR"
        const val QUERY_LATEST = "eu.kanade.domain.source.interactor.LATEST"
    }
}
