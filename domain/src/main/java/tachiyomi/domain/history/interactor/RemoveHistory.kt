package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.source.repository.SourceCoverageRepository

@Inject
class RemoveHistory(
    private val repository: HistoryRepository,
    // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED)
    private val coverage: SourceCoverageRepository,
) {

    suspend fun awaitAll(): Boolean {
        // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED): page coverage is a statement
        // about which entries were viewed, and it is only ever safe to act on
        // because viewed never regresses. Clearing history is the one thing that
        // breaks that, so every observation goes with it. Rebuilding is free -
        // it happens as a side effect of browsing - so this is a delete rather
        // than anything cleverer.
        coverage.deleteAll()
        return repository.deleteAllHistory()
    }

    suspend fun await(history: HistoryWithRelations) {
        repository.resetHistory(history.id)
        // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED): one entry is enough to make
        // a recorded region wrong, and which region it falls in is not known
        // without the listing, so the whole set goes. Coarse, and correct.
        coverage.deleteAll()
    }

    suspend fun await(animeId: Long) {
        repository.resetHistoryByAnimeId(animeId)
        // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED)
        coverage.deleteAll()
    }
}
