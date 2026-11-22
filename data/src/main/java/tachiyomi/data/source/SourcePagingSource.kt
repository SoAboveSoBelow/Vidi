package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.FaultRecoverableSource
import eu.kanade.tachiyomi.animesource.SnapshotInvalidatingSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.network.SourceBudgetExceededException
import eu.kanade.tachiyomi.network.SourceRequestBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import mihon.domain.anime.model.toDomainAnime
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.model.PageAction
import tachiyomi.domain.source.model.SourcePageCoverage
import tachiyomi.domain.source.model.WalkState
import tachiyomi.domain.source.model.actionFor
import tachiyomi.domain.source.repository.SourceCoverageRepository
import tachiyomi.domain.source.repository.SourcePagingSource
import java.io.IOException
import kotlin.time.Clock

class SourceSearchPagingSource(
    source: AnimeSource,
    private val query: String,
    private val filters: AnimeFilterList,
    networkToLocalAnime: NetworkToLocalAnime,
    requestBudget: SourceRequestBudget? = null,
    coverage: SourceCoverageRepository? = null,
    listingKey: String? = null,
    skipCovered: () -> Boolean = { false },
    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
    coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
) : BaseSourcePagingSource(
    source,
    networkToLocalAnime,
    requestBudget,
    coverage,
    listingKey,
    skipCovered,
    coveredFraction,
) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getSearchAnime(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(
    source: AnimeSource,
    networkToLocalAnime: NetworkToLocalAnime,
    requestBudget: SourceRequestBudget? = null,
    coverage: SourceCoverageRepository? = null,
    listingKey: String? = null,
    skipCovered: () -> Boolean = { false },
    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
    coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
) : BaseSourcePagingSource(
    source,
    networkToLocalAnime,
    requestBudget,
    coverage,
    listingKey,
    skipCovered,
    coveredFraction,
) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getPopularAnime(currentPage)
    }
}

class SourceLatestPagingSource(
    source: AnimeSource,
    networkToLocalAnime: NetworkToLocalAnime,
    requestBudget: SourceRequestBudget? = null,
    coverage: SourceCoverageRepository? = null,
    listingKey: String? = null,
    skipCovered: () -> Boolean = { false },
    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
    coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
) : BaseSourcePagingSource(
    source,
    networkToLocalAnime,
    requestBudget,
    coverage,
    listingKey,
    skipCovered,
    coveredFraction,
) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: AnimeSource,
    private val networkToLocalAnime: NetworkToLocalAnime,
    // AM (A_BROWSE_HAS_A_REQUEST_BUDGET): consulted before each page is
    // requested - see SourceRequestBudget for why this is here rather than in
    // an interceptor. Null leaves the listing unmetered, which is what a caller
    // that is not a user-driven browse walk should pass.
    private val requestBudget: SourceRequestBudget? = null,
    // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED) -->
    /**
     * Where each page's composition is recorded, or null to record nothing.
     *
     * This is the only place that sees a page as a page. The hide-viewed trim is
     * a PagingData.filter, which runs per item with no notion of where one page
     * ended and the next began, so the counting cannot live there.
     */
    private val coverage: SourceCoverageRepository? = null,
    /** The ordering coverage is relative to - see listingKeyOf. */
    private val listingKey: String? = null,
    // <-- AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED)
    // AM (A_SKIP_SERVES_THE_TRIM_ONLY) -->
    /**
     * Whether a covered page may be passed over, asked at each load.
     *
     * Skipping exists to reach unviewed entries sooner, which is the
     * hide-viewed trim's purpose and nothing else's. With the trim off the
     * viewed entries are wanted, so there is nothing to skip and a skip is a
     * hole in a listing the user asked to see whole.
     *
     * Called rather than captured: the toggle changes without the pager being
     * rebuilt, so a value read at construction would keep skipping for a
     * listing whose trim had since been turned off.
     *
     * Recording is deliberately NOT gated on this. An observation of what a
     * page held is true whatever the toggle says, and it is what makes a later
     * skip possible at all.
     */
    private val skipCovered: () -> Boolean = { false },
    // <-- AM (A_SKIP_SERVES_THE_TRIM_ONLY)
    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS) -->
    /**
     * How much of a page must be viewed before it may be skipped - the user's
     * setting, see SourcePageCoverage.COVERED_FRACTION for the default.
     *
     * Called rather than captured, for the same reason [skipCovered] is: the
     * setting changes without the pager being rebuilt, and a walk already in
     * progress should obey the value as it is now rather than the one that
     * happened to be set when the listing was opened.
     */
    private val coveredFraction: () -> Float = { SourcePageCoverage.COVERED_FRACTION },
    // <-- AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
) : SourcePagingSource() {

    // AM (LOCAL_SOURCE_SNAPSHOT) -->
    // Snapshot-served sources (the local source) refresh in the background;
    // when the snapshot changes they emit here and the page invalidates
    // itself, so the UI shows the fresh listing without a manual refresh.
    private val invalidateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        (source as? SnapshotInvalidatingSource)?.let { snapshotSource ->
            snapshotSource.snapshotRefreshes
                .onEach { invalidate() }
                .launchIn(invalidateScope)
        }
    }
    // <-- AM (LOCAL_SOURCE_SNAPSHOT)

    private val seenAnime = hashSetOf<String>()

    // AM (A_COVERED_PAGE_NEED_NOT_BE_FETCHED) -->

    /**
     * This listing's recorded pages, read once per paging source.
     *
     * Null until the first load; empty map if the listing has never been walked
     * or its records were just voided.
     */
    private var recordedPages: Map<Long, SourcePageCoverage>? = null

    /**
     * The highest page whose recorded anchors were CONFIRMED during this walk.
     *
     * Set only when a page was actually loaded and both its head and tail url
     * matched what was recorded for it. A page with no record does not advance
     * it: a record can only be trusted relative to a verified neighbour, and an
     * unrecorded page proves nothing about the one after it.
     *
     * AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED): this is the root a run of skips
     * hangs off, and SKIP_CHAIN_STRIDE is measured from it, so a skip must not
     * advance it - see [positionedThrough] for the one that a skip does.
     */
    private var verifiedThrough = 0L

    // AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED) -->

    /**
     * The highest page this walk has a position for, verified or carried.
     *
     * A skip positions the page it passed over, because the record it was made
     * from describes a page whose predecessor was positioned and whose size
     * matched. That is what lets the next page be skipped in turn; without it
     * the walk can only alternate and saves half the requests at best.
     */
    private var positionedThrough = 0L
    // <-- AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED)

    /** Cleared for the rest of the walk once any page contradicts its record. */
    private var maySkip = true

    /**
     * Entries per full page of this listing, taken from the first page loaded.
     *
     * A record whose entryCount differs is not skipped. That covers two cases
     * with one check: the final page of a listing is short, and a listing whose
     * page size changed is a listing whose records describe a different shape.
     */
    private var listingPageSize: Int? = null
    // <-- AM (A_COVERED_PAGE_NEED_NOT_BE_FETCHED)

    // AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED) -->

    /**
     * Whether this listing is worth tracking at all, resolved once per walk.
     *
     * Null until the first load. False suppresses reading AND writing coverage
     * for the rest of the walk - see SourceListingHealth for why they go
     * together.
     */
    private var tracked: Boolean? = null
    // <-- AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED)

    // AY -->
    // Counts consecutive pages skipped due to a source's own parser crashing (see below).
    private var consecutiveSkippedPages = 0
    // <-- AY

    abstract suspend fun requestNextPage(currentPage: Int): AnimesPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Anime> {
        val page = params.key ?: 1

        // AM (A_COVERED_PAGE_NEED_NOT_BE_FETCHED) -->
        // Read before the budget check, because a skip spends no permit: it
        // makes no request, and charging it one would make coverage compete
        // with the walk it exists to shorten.
        val coverageRepo = coverage
        val coverageKey = listingKey
        if (coverageRepo != null && coverageKey != null) {
            // AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED): resolved once,
            // and on failure treated as tracked - a health lookup that errored
            // is not evidence of instability, and the verification below is
            // what keeps a wrong guess here from costing anything.
            val isTracked = tracked
                ?: runCatching {
                    coverageRepo.health(source.id, coverageKey)
                        ?.isSuppressed(Clock.System.now().toEpochMilliseconds()) != true
                }
                    .getOrDefault(true)
                    .also { tracked = it }

            val records = if (!isTracked) {
                emptyMap()
            } else {
                recordedPages
                    ?: runCatching { coverageRepo.getByListing(source.id, coverageKey).associateBy { it.page } }
                        .getOrDefault(emptyMap())
                        .also { recordedPages = it }
            }

            // AM (A_SKIP_IS_A_PROMISE_ABOUT_WHAT_THE_USER_SEES): the decision
            // itself lives with the record it is made from, where the promises
            // a skip has to keep are written down. This supplies only the part
            // of the walk it cannot know: whether the trim is on, whether the
            // records are still trusted, how far the ordering has been re-proven
            // this walk, and what a full page of this listing looks like.
            val action = records[page].actionFor(
                page = page,
                walk = WalkState(
                    trimActive = skipCovered(),
                    recordsTrusted = maySkip && isTracked,
                    verifiedThrough = verifiedThrough,
                    positionedThrough = positionedThrough,
                    listingPageSize = listingPageSize,
                    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
                    coveredFraction = coveredFraction(),
                ),
            )
            if (action is PageAction.Skip) {
                // AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED): this page is now
                // positioned, so the next one may be skipped on the strength of
                // it. verifiedThrough is deliberately left alone - the stride is
                // measured from the last LOADED page, so a chain cannot extend
                // its own reach.
                positionedThrough = page
                // Empty page, as the parser-crash path below does it: Paging
                // asks for the next page immediately, so a covered region costs
                // a few map lookups instead of requests.
                return LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = action.nextPage,
                )
            }
        }
        // <-- AM (A_COVERED_PAGE_NEED_NOT_BE_FETCHED)

        // AM (A_BROWSE_HAS_A_REQUEST_BUDGET) -->
        // Refused before the request is made, not after it fails. Hide-viewed
        // filters downstream of cachedIn, so a heavily-viewed listing yields a
        // couple of visible entries per page and Paging issues the next append
        // the moment this one returns - a chain paced by response latency
        // rather than by the user. The budget is what stops that chain from
        // walking a source into a rate limit or a ban.
        //
        // Surfaced as an error rather than as the end of the listing, because
        // the two mean different things: there IS more, and continuing is the
        // user's call. LoadState.Error already gives them a retry, and
        // SourceBudgetExceededException carries how long until that retry can
        // succeed. Blocking instead would make the refusal invisible and turn
        // it back into the wait it exists to prevent.
        if (requestBudget != null && !requestBudget.tryAcquire(source.id)) {
            return LoadResult.Error(
                SourceBudgetExceededException(
                    sourceId = source.id,
                    retryIn = requestBudget.timeUntilPermit(source.id),
                ),
            )
        }
        // <-- AM (A_BROWSE_HAS_A_REQUEST_BUDGET)

        return try {
            val animesPage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.animes.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            // AY -->
            consecutiveSkippedPages = 0
            // <-- AY

            // AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED) -->
            // Resolved to local entries BEFORE the duplicate filter, so what is
            // recorded is what the page held rather than what survived a
            // de-duplication against earlier pages of this same walk. The
            // returned list is unchanged - networkToLocalAnime preserves order
            // and the url the filter keys on - at the cost of resolving the few
            // entries a page repeats, which it would have resolved anyway on
            // their first appearance.
            val pageAnime = animesPage.animes
                .map { it.toDomainAnime(source.id) }
                .let { networkToLocalAnime(it) }

            if (coverageRepo != null && coverageKey != null && tracked != false) {
                // AM (A_COVERED_PAGE_NEED_NOT_BE_FETCHED) -->
                // This page was loaded, so it can now be checked against what
                // was recorded for it - and this is the only place that check
                // can happen, because it needs the entries the source just
                // returned.
                //
                // Anchors are head AND tail. Head alone misses an insertion
                // INSIDE the page, which shifts the tail into the next page
                // while leaving the first entry where it was - precisely the
                // case that would silently skip unseen entries further down.
                val priorRecord = recordedPages?.get(page)
                if (listingPageSize == null) listingPageSize = pageAnime.size
                if (priorRecord != null) {
                    val held = priorRecord.headUrl == pageAnime.first().url &&
                        priorRecord.tailUrl == pageAnime.last().url
                    if (held) {
                        verifiedThrough = page
                        // AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED): a matched load
                        // both positions this page and re-plants the root the
                        // stride is measured from.
                        positionedThrough = page
                        // AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED)
                        runCatching { coverageRepo.noteStable(source.id, coverageKey) }
                            .onFailure { logcat(LogPriority.WARN, it) { "listing health reset failed" } }
                    } else {
                        // The ordering moved. Every record for this listing
                        // describes the old one, so none of them may be used
                        // again - and no further page of this walk may be
                        // skipped on the strength of them.
                        maySkip = false
                        verifiedThrough = 0
                        positionedThrough = 0
                        recordedPages = emptyMap()
                        // AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED) -->
                        // Recording stops for the REST OF THIS WALK too, which
                        // does two things. It leaves no rows behind: without
                        // it, the pages loaded after a shift are recorded
                        // again, so a suppressed listing would keep a full
                        // walk's worth of rows forever and this table would
                        // bound nothing. And it makes one walk count as one
                        // failure - several pages can mismatch in a single
                        // walk, and counting each would suppress a listing on
                        // its first bad walk rather than its third.
                        tracked = false
                        // <-- AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED)
                        runCatching { coverageRepo.deleteByListing(source.id, coverageKey) }
                            .onFailure { logcat(LogPriority.WARN, it) { "coverage void failed, page=$page" } }
                        // AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED): a
                        // shift counted against the listing, so a listing that
                        // does this every walk stops being recorded.
                        runCatching { coverageRepo.noteShift(source.id, coverageKey) }
                            .onFailure { logcat(LogPriority.WARN, it) { "listing health note failed" } }
                        logcat(LogPriority.DEBUG) {
                            "coverage: listing shifted at page=$page, records voided"
                        }
                    }
                }
                // <-- AM (A_COVERED_PAGE_NEED_NOT_BE_FETCHED)
                // AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED): re-checked
                // because the verification above may have just turned tracking
                // off. Recording the very page that exposed the shift would
                // leave one row describing an ordering already known to be
                // stale - a row nothing can read while the listing is
                // suppressed, and one that would mismatch on the next walk and
                // count a SECOND failure for what was a single shift.
                if (tracked != false) {
                    // Never allowed to fail a load: this is an observation, and
                    // a listing is worth more than a row in a table.
                    runCatching {
                        coverageRepo.record(
                            source.id,
                            coverageKey,
                            page,
                            pageAnime,
                            animesPage.hasNextPage,
                        )
                    }
                        .onFailure { logcat(LogPriority.WARN, it) { "coverage record failed, page=$page" } }
                }
            }

            val anime = pageAnime.filter { seenAnime.add(it.url) }
            // <-- AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED)

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
            // AM (JS_FAULT_MANUAL_RECOVERY) -->
            // A latched source fault (JS projects) is not a parser crash, and
            // treating it as one was doubly wrong: the page-skip below would
            // silently walk past the whole listing, and every retry the UI
            // offered just re-hit the latch - which is why a faulted source
            // looked like it could never be refreshed. Surface it like a
            // network failure, and reset the source's runtime so the retry
            // the user then triggers IS the manual refresh: a fresh attempt,
            // not an instant re-fault. On a genuinely wedged script each
            // retry costs one more hard-timeout wait and one orphaned thread
            // (see JsProjectSource.clearFault) - the documented price of a
            // recovery the user explicitly asked for.
            val faultedSource = (source as? FaultRecoverableSource)?.takeIf { it.isFaulted }
            if (faultedSource != null) {
                logcat(LogPriority.ERROR, e) {
                    "SourcePagingSource.load() source fault, page=$page - runtime reset for retry"
                }
                runCatching { faultedSource.clearFault() }
                LoadResult.Error(e)
            } else {
            // <-- AM (JS_FAULT_MANUAL_RECOVERY)
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
