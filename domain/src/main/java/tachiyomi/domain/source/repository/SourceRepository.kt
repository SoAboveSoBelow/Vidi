package tachiyomi.domain.source.repository

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.model.Source

typealias SourcePagingSource = PagingSource<Long, Anime>

interface SourceRepository {

    fun getSources(): Flow<List<Source>>

    fun getOnlineSources(): Flow<List<Source>>

    // AM (MERGED_SOURCES): counts everything a source contributes to the
    // library, merge children included - see animes.sq.
    fun getSourcesWithLibraryCount(): Flow<List<Pair<Source, Long>>>

    fun search(sourceId: Long, query: String, filterList: AnimeFilterList): SourcePagingSource

    fun getPopular(sourceId: Long): SourcePagingSource

    fun getLatest(sourceId: Long): SourcePagingSource
}
