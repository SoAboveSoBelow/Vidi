package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import logcat.LogPriority
import mihon.domain.anime.model.toDomainAnime
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class SourceSearchPagingSource(
    source: AnimeSource,
    private val query: String,
    private val filters: AnimeFilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getSearchAnime(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: AnimeSource) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getPopularAnime(currentPage)
    }
}

class SourceLatestPagingSource(source: AnimeSource) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: AnimeSource,
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
) : SourcePagingSource() {

    private val seenAnime = hashSetOf<String>()

    // AY -->
    // Counts consecutive pages skipped due to a source's own parser crashing (see below).
    private var consecutiveSkippedPages = 0
    // <-- AY

    abstract suspend fun requestNextPage(currentPage: Int): AnimesPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Anime> {
        val page = params.key ?: 1

        return try {
            val animesPage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.animes.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            // AY -->
            consecutiveSkippedPages = 0
            // <-- AY

            val anime = animesPage.animes
                .map { it.toDomainAnime(source.id) }
                .filter { seenAnime.add(it.url) }
                .let { networkToLocalAnime(it) }

            LoadResult.Page(
                data = anime,
                prevKey = null,
                nextKey = if (animesPage.hasNextPage) page + 1 else null,
            )
            // AY -->
        } catch (e: IOException) {
            // Network-level failure (timeout, HTTP error, etc) - the same page may well
            // succeed on retry, so surface it normally via the retry snackbar.
            logcat(LogPriority.ERROR, e) { "SourcePagingSource.load() network failure, page=$page" }
            LoadResult.Error(e)
        } catch (e: NoResultsException) {
            LoadResult.Error(e)
        } catch (e: Exception) {
            // Parser crash; skip page
            logcat(LogPriority.ERROR, e) {
                "SourcePagingSource: source parser crashed on page=$page, skipping page"
            }
            consecutiveSkippedPages++
            if (consecutiveSkippedPages > MAX_CONSECUTIVE_SKIPPED_PAGES) {
                consecutiveSkippedPages = 0
                LoadResult.Error(e)
            } else {
                LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = page + 1,
                )
            }
        }
        // <-- AY
    }

    override fun getRefreshKey(state: PagingState<Long, Anime>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }

    // AY -->
    private companion object {
        const val MAX_CONSECUTIVE_SKIPPED_PAGES = 3
    }
    // <-- AY
}

class NoResultsException : Exception()
