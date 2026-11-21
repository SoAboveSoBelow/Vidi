package tachiyomi.domain.source.repository

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.model.Source
// AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
import tachiyomi.domain.source.model.SourcePageCoverage

typealias SourcePagingSource = PagingSource<Long, Anime>

interface SourceRepository {

    fun getSources(): Flow<List<Source>>

    fun getOnlineSources(): Flow<List<Source>>

    // AM (MERGED_SOURCES): counts everything a source contributes to the
    // library, merge children included - see animes.sq.
    fun getSourcesWithLibraryCount(): Flow<List<Pair<Source, Long>>>

    // AM (A_SKIP_SERVES_THE_TRIM_ONLY): skipCovered is read at load time, not
    // captured, because the hide-viewed toggle changes without the pager being
    // rebuilt. It gates SKIPPING only - recording continues either way, since an
    // observation is valid whatever the toggle says and is what makes a later
    // skip possible. Default false: a caller that is not a user-driven browse
    // with the trim on has no business dropping pages.
    //
    // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED): listingKey names the ordering a
    // page's composition is recorded against - see listingKeyOf. Null records
    // nothing, which is what a caller that is not a user-driven browse should
    // pass.
    fun search(
        sourceId: Long,
        query: String,
        filterList: AnimeFilterList,
        listingKey: String? = null,
        skipCovered: () -> Boolean = { false },
        // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
        coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
    ): SourcePagingSource

    fun getPopular(
        sourceId: Long,
        listingKey: String? = null,
        skipCovered: () -> Boolean = { false },
        // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
        coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
    ): SourcePagingSource

    fun getLatest(
        sourceId: Long,
        listingKey: String? = null,
        skipCovered: () -> Boolean = { false },
        // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
        coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
    ): SourcePagingSource
}
