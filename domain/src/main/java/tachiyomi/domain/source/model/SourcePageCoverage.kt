package tachiyomi.domain.source.model

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

// AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED) -->
/**
 * One page of a listing, as it was when it was loaded.
 *
 * [viewedCount] against [entryCount] is what says whether the page was covered.
 * The threshold is still not fixed here - nothing reads coverage yet, so a
 * constant would be dead - but it has now been measured, and the measurement
 * corrected the reason this comment used to give.
 *
 * The shortfall is not cards scrolled past too fast to be recorded. It is
 * MIN_WATCHED_SECONDS in ScrollerPlayback: a card held less than that writes no
 * history row, so it counts as unviewed however deliberately it was dismissed.
 * One 9-page walk of a heavily-viewed listing measured 0.71 viewed at the
 * median and 0.88 at the maximum - not one page reached 0.90, so both
 * thresholds under discussion would have skipped nothing at all. The threshold
 * is therefore a consequence of that constant, not an independent knob, and
 * 0.85 is the intended value against the lowered (3s) one.
 *
 * Page size is a property of the LISTING, not the source: the same source
 * measured 23 entries per page on its popular listing and 24 on a filtered one,
 * both stable across every page of their own listing. A guessed page size would
 * have been wrong on both, which is why [entryCount] is recorded per page. The
 * final page of any listing is short, so a consumer has to read a short page as
 * the end of the listing rather than as a listing with a different page size.
 *
 * [headUrl] and [tailUrl] bound the page. A later skip has to prove a recorded
 * region still sits where it did, and those two urls are the only durable
 * identifiers a listing hands out - positions move the moment anything is
 * prepended, and sources give no total count to convert positions with.
 */
data class SourcePageCoverage(
    val sourceId: Long,
    val listingKey: String,
    val page: Long,
    val entryCount: Int,
    val viewedCount: Int,
    val headUrl: String,
    val tailUrl: String,
    /** Whether the page's LAST entry was viewed - see [isSkippable]. */
    val tailViewed: Boolean,
    /**
     * What the load of this page reported for hasNextPage.
     *
     * AM (A_SKIPPING_A_PAGE_MUST_NOT_LOSE_ITS_CONTINUATION): a skip answers
     * Paging without calling the source, so it has to report the same
     * continuation the load would have. Guessing "there is a next page" ends a
     * listing whose final page is full on an error rather than an end.
     */
    val hasNext: Boolean,
    val updatedAt: Long,
) {
    val viewedFraction: Float
        get() = if (entryCount == 0) 0f else viewedCount.toFloat() / entryCount

    /**
     * Whether this page may be passed over.
     *
     * AM (A_AN_UNVIEWED_TAIL_IS_A_STOPPING_POINT) -->
     * Two conditions, and the second is not a refinement of the first.
     *
     * [COVERED_FRACTION] asks how MUCH of the page was viewed. [tailViewed]
     * asks WHERE the unviewed entries are, and the answer changes what they
     * mean. Unviewed entries in the middle of a page were scrolled past: the
     * user reached the end and chose not to open them. Unviewed entries at the
     * tail are where the user stopped - they are the frontier, the next thing
     * that would have been seen, and they are unviewed because nobody got to
     * them yet rather than because they were rejected.
     *
     * A ratio cannot tell those apart. 21 of 24 viewed is skippable when the
     * three gaps are interior and is the wrong page to skip when they are the
     * last three, because skipping it resumes the user past their own stopping
     * point - the exact opposite of what the hide-viewed trim is for.
     *
     * The page's last entry answers it: viewed means the walk went through the
     * whole page, so the gaps behind it were passed over. On a linear walk this
     * protects only the page the user stopped on, so the skipping still applies
     * to everything behind it.
     * <-- AM (A_AN_UNVIEWED_TAIL_IS_A_STOPPING_POINT)
     */
    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS) -->
    /**
     * @param coveredFraction how much of the page must be viewed, from the
     * user's setting - see [COVERED_FRACTION] for what the default is and why.
     * Taken as an argument rather than read here because a domain model has no
     * preferences to read, and because the walk already carries every other
     * part of the decision (see [WalkState]).
     */
    fun isSkippable(coveredFraction: Float): Boolean =
        tailViewed && viewedFraction >= coveredFraction
    // <-- AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)

    companion object {
        /**
         * How much of a page must be viewed before it may be skipped, by
         * default - the value SourcePreferences.browsePageSkipThreshold starts
         * at, and what a caller with no setting to read falls back to.
         *
         * 0.85, not 0.90 or 1.00, and the difference is measured rather than
         * chosen: a card held less than MIN_WATCHED_SECONDS writes no history
         * row, so a page can be finished and still read as 0.88 covered. A
         * 9-page walk of a heavily-viewed listing came in at 0.71 median and
         * 0.88 maximum, so both higher thresholds would have skipped nothing.
         *
         * The cost of lowering it is the part that matters: at 0.85, skipping a
         * 24-entry page can pass over 3 entries that were genuinely never seen.
         * That is the price of the trim finding new entries faster, and it is
         * why this is not 1.00 - a threshold nothing ever reaches is a feature
         * that does not run.
         *
         * AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS): and it is why this is now a
         * default rather than the rule. The measurement that produced 0.85 was
         * taken against a 3-second history threshold, which is itself a setting
         * now (PlayerPreferences.historyThresholdSeconds) - so the number this
         * one should be depends on a number the user can move, and reading one
         * without the other is how a listing ends up skipping pages nobody
         * finished. Raising this trades fewer skipped-over entries for more
         * requests; lowering it trades the reverse.
         */
        const val COVERED_FRACTION = 0.85f
    }
}

// AM (A_SKIP_IS_A_PROMISE_ABOUT_WHAT_THE_USER_SEES) -->

/**
 * What a walk should do with one page.
 *
 * [Skip] carries the continuation because a skipped page still has to answer
 * Paging, and the answer is the one the load it replaced would have given.
 */
sealed interface PageAction {
    data object Load : PageAction

    /** Pass the page over, continuing at [nextPage]; null ends the listing. */
    data class Skip(val nextPage: Long?) : PageAction
}

/**
 * Everything about a walk in progress that bears on skipping.
 *
 * @param trimActive whether hide-viewed is on, so that skipping is wanted at all
 * @param recordsTrusted false once anything has contradicted a record, or when
 *   the listing has been suppressed for repeated reordering
 * @param verifiedThrough highest page LOADED this walk whose recorded anchors
 *   both matched - the root of the chain, see [SKIP_CHAIN_STRIDE]
 * @param positionedThrough highest page this walk has established a position
 *   for, by loading and matching it or by passing it over on the strength of
 *   the page before it
 * @param listingPageSize entries per full page, from the first page loaded this
 *   walk, or null before one has been
 * @param coveredFraction how much of a page must be viewed for it to be
 *   skippable - the user's setting, see [SourcePageCoverage.COVERED_FRACTION].
 *   Part of the walk rather than of the record because it is a question about
 *   what the user wants NOW, not about what the page held when it was observed:
 *   a record written under one threshold stays a truthful observation when the
 *   threshold moves.
 */
data class WalkState(
    val trimActive: Boolean,
    val recordsTrusted: Boolean,
    val verifiedThrough: Long,
    val positionedThrough: Long,
    val listingPageSize: Int?,
    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
    val coveredFraction: Float = SourcePageCoverage.COVERED_FRACTION,
)

// AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED) -->

/**
 * How many pages one loaded page may carry before another has to be loaded.
 *
 * A skipped page is positioned by the page before it, so a run of skips is a
 * chain hanging off the last page that was actually loaded and matched. The
 * chain is what makes skipping worth anything - requiring a LOADED predecessor
 * forces a walk to alternate, load, skip, load, skip, which caps the saving at
 * half the requests however thoroughly a listing has been covered.
 *
 * It cannot be unbounded, and the reason is not the chain's own reasoning. An
 * insertion inside a skipped page shifts that page's tail into the next one
 * while leaving its head where it was, and the tail anchor is what catches
 * that - so a page that is never loaded is a page whose shift is never seen.
 * With no bound, a fully covered listing loads page 1 and nothing else, which
 * puts the whole walk on one page's anchors and leaves the health suppression
 * in SourceListingHealth watching a listing it can no longer detect a shift in.
 *
 * At 4 a walk loads one page in four and is never more than three consecutive
 * pages away from a check, which cuts three quarters of the requests - the part
 * that actually costs a rate limit - while keeping the reordering detection the
 * records depend on. It is a sampling interval, not a timeout: nothing here
 * depends on when the pages were loaded, only on how far the chain has run.
 */
const val SKIP_CHAIN_STRIDE = 4L
// <-- AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED)

/**
 * Whether [page] can be passed over, given what was recorded for it.
 *
 * A skip stands in for a load, so it has to preserve everything the load would
 * have produced. Four promises, and every condition below is one of them:
 *
 *  1. THE SAME VISIBLE ENTRIES. Only true where the trim is on and would have
 *     removed what the page held: the ratio says how much was viewed, and
 *     [SourcePageCoverage.tailViewed] says the unviewed remainder was passed
 *     over rather than never reached. With the trim OFF nothing is removed, so
 *     no page can be skipped without leaving a hole.
 *  2. THE SAME CONTINUATION. [SourcePageCoverage.hasNext], not an assumption,
 *     or a listing ending on a full page ends on an error.
 *  3. THE SAME PAGE. A record is about a page of a given size in a given
 *     ordering: the size has to match what this walk is seeing, and the page
 *     immediately before this one has to have been positioned this walk, by a
 *     matched load or by a skip of its own. Page 1 can never satisfy that and
 *     is always loaded, which is also what makes it the page that proves the
 *     rest.
 *  4. NOTHING KNOWN TO BE STALE. [WalkState.recordsTrusted] is cleared the
 *     moment a loaded page disagrees with its record, and
 *     [SKIP_CHAIN_STRIDE] bounds how long a chain may run before a page is
 *     loaded to give it the chance to.
 *
 * Anything that cannot be shown from the record and the walk is a reason to
 * load, never to skip: the cost of loading a page that could have been skipped
 * is one request, and the cost of the reverse is content the user never sees.
 */
fun SourcePageCoverage?.actionFor(page: Long, walk: WalkState): PageAction {
    val record = this ?: return PageAction.Load
    // AM (A_THE_SKIP_THRESHOLD_IS_THE_USERS)
    val sameVisibleEntries = walk.trimActive && record.isSkippable(walk.coveredFraction)
    val samePage = page > 1 &&
        walk.positionedThrough == page - 1 &&
        record.entryCount == walk.listingPageSize
    // AM (A_CHAIN_NEEDS_ITS_ROOT_REPLANTED): distance from the last page that
    // was loaded AND matched, not from the last page positioned - a chain of
    // skips does not extend its own reach.
    val withinChain = page - walk.verifiedThrough < SKIP_CHAIN_STRIDE
    return if (walk.recordsTrusted && sameVisibleEntries && samePage && withinChain) {
        PageAction.Skip(nextPage = if (record.hasNext) page + 1 else null)
    } else {
        PageAction.Load
    }
}
// <-- AM (A_SKIP_IS_A_PROMISE_ABOUT_WHAT_THE_USER_SEES)

/**
 * The identity of one listing ORDERING, which is what coverage is relative to.
 *
 * A filtered listing is a different ordering, so it gets its own key and its
 * own rows - which is what stops one listing's coverage from ever being applied
 * to another. [AnimeFilter.Sort] is itself a filter, so changing the sort
 * changes the key without anything having to detect that the order changed.
 *
 * Built from each filter's name and state rather than from equality:
 * [AnimeFilterList.equals] always returns false by design, so it cannot be used
 * for this. Filters left at their default contribute nothing, so that merely
 * opening the filter sheet does not create a new key.
 *
 * ONE KNOWN WEAKNESS. [AnimeFilter.Group] carries a `List<V>` of
 * source-defined types, and if an extension's element class has no toString of
 * its own the state stringifies by identity and the key changes between
 * processes. That costs a record that is never read again - the key fails to
 * MATCH rather than failing to be distinct - so it loses a reuse and can never
 * apply the wrong coverage to the wrong listing. Worth knowing before trusting
 * a hit rate on sources with group filters.
 */
fun listingKeyOf(query: String?, filters: AnimeFilterList): String {
    val filterPart = filters
        .mapNotNull { filter -> filter.keyPart() }
        .sorted()
        .joinToString(",")
    return "${query.orEmpty()}|$filterPart"
}

private fun AnimeFilter<*>.keyPart(): String? {
    if (this is AnimeFilter.Header || this is AnimeFilter.Separator) return null
    val state = state
    val isDefault = when (state) {
        null -> true
        is Boolean -> !state
        is Int -> state == 0
        is String -> state.isEmpty()
        is Collection<*> -> state.isEmpty()
        else -> false
    }
    if (isDefault) return null
    return when (state) {
        is Collection<*> -> "$name=${state.joinToString("+") { it.stateString() }}"
        else -> "$name=${state.stateString()}"
    }
}

private fun Any?.stateString(): String = when (this) {
    null -> ""
    // A group's elements are usually filters themselves, so their own name and
    // state are the stable description rather than toString().
    is AnimeFilter<*> -> "$name:$state"
    else -> toString()
}
// <-- AM (A_COVERAGE_IS_OBSERVED_NOT_INFERRED)
