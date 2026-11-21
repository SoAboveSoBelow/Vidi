package eu.kanade.tachiyomi.network

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// AM (A_BROWSE_HAS_A_REQUEST_BUDGET) -->
/**
 * A ceiling on how many requests browsing may make to one source in a window.
 *
 * The thing this protects against is not slowness, it is being rate limited or
 * IP banned - a cost that does not average out, because one ban is as bad
 * whichever listing provoked it. That makes it a property worth guaranteeing
 * rather than optimising towards, and a guarantee has to live where the
 * requests are issued, not in a cache that happens to be warm.
 *
 * WHY NOT AN INTERCEPTOR. [RateLimitInterceptor] and
 * [eu.kanade.tachiyomi.network.interceptor.rateLimitHost] already exist, but
 * extensions apply those to their OWN clients, and an app-level interceptor on
 * NetworkHelper.client sees only a Request - it cannot tell a browse walk from
 * a library update or a download, both of which legitimately issue many
 * requests and are already paced by the extension's own limiter. Throttling
 * those would break background work to fix a browse problem. So this is
 * consulted by the browse paths themselves, which are app code and know what
 * they are doing.
 *
 * WHY BROWSE BURSTS AT ALL. Two reasons, and neither is a bug to be fixed
 * elsewhere:
 *
 *  - Hide-viewed filters AFTER paging, so a page of 25 can yield two visible
 *    entries. Paging then needs more immediately and issues the next append as
 *    soon as the previous one returns - a chain paced only by response latency,
 *    not by the user. The deeper the viewed fraction, the longer the chain:
 *    roughly 1/(1 - viewed fraction) requests per screenful.
 *  - The browse feed fetches details per card displayed, so a fast scroll is
 *    one request per card with nothing between them.
 *
 * WHAT HAPPENS WHEN IT TRIPS is the caller's choice, because the right answer
 * differs: a listing load surfaces it so the user can decide to continue, while
 * an optional enrichment like a card's details should simply not happen. Both
 * are better than blocking, which would turn a burst into an invisible wait and
 * reintroduce exactly the latency the budget is not about.
 *
 * Per source rather than per host: the callers know their source, and a source
 * sharing a host with another is still covered by that extension's own
 * interceptor. In-memory and per process - a restart resets the window, which
 * is a small hole in exchange for not writing request counts to disk; the
 * window is short enough that a cold start cannot bank a burst worth having.
 */
@Inject
@SingleIn(AppScope::class)
class SourceRequestBudget {

    private val windows = HashMap<Long, ArrayDeque<TimeSource.Monotonic.ValueTimeMark>>()

    /**
     * Takes a permit for [sourceId], or returns false when the window is full.
     *
     * Monotonic marks, so a clock change cannot widen or collapse a window.
     */
    fun tryAcquire(
        sourceId: Long,
        permits: Int = DEFAULT_PERMITS,
        window: Duration = DEFAULT_WINDOW,
    ): Boolean {
        val now = TimeSource.Monotonic.markNow()
        synchronized(windows) {
            val marks = windows.getOrPut(sourceId) { ArrayDeque() }
            while (marks.isNotEmpty() && marks.first().elapsedNow() > window) {
                marks.removeFirst()
            }
            if (marks.size >= permits) return false
            marks.addLast(now)
            return true
        }
    }

    /** How long until [sourceId] has a permit again, or ZERO if it has one now. */
    fun timeUntilPermit(
        sourceId: Long,
        permits: Int = DEFAULT_PERMITS,
        window: Duration = DEFAULT_WINDOW,
    ): Duration {
        synchronized(windows) {
            val marks = windows[sourceId] ?: return Duration.ZERO
            while (marks.isNotEmpty() && marks.first().elapsedNow() > window) {
                marks.removeFirst()
            }
            if (marks.size < permits) return Duration.ZERO
            return window - marks.first().elapsedNow()
        }
    }

    /** Forgets [sourceId]'s window, for a continue the user explicitly asked for. */
    fun reset(sourceId: Long) {
        synchronized(windows) { windows.remove(sourceId) }
    }

    companion object {
        /**
         * Starting values, not measured ones.
         *
         * This is a BACKSTOP on total consumption, not a cure for a runaway -
         * there is no runaway to cure. Paging appends only when the user
         * approaches the end of what is loaded, so a chain is bounded per
         * scroll action at roughly 1/(1 - viewed fraction) requests per
         * screenful and then stops until the user moves again. What it guards
         * against is the TOTAL a long session spends on one source, which is
         * proportional to how far the user scrolls and has no ceiling of its
         * own.
         *
         * So the window has to be loose enough not to refuse real browsing.
         * At a 90% viewed rate one screenful costs about ten requests and about
         * four seconds of sequential network, so sustained scrolling is roughly
         * fifteen requests a minute per screenful-and-a-half; sixty permits
         * across two minutes leaves real headroom above that while still
         * capping what a session can spend. A tighter 20-per-minute refused
         * ordinary scrolling outright when tested against those rates.
         *
         * Constants rather than preferences because the right numbers depend on
         * what real sources tolerate, which is a thing to measure against them
         * and then set - not to guess once and bury behind a settings toggle.
         */
        const val DEFAULT_PERMITS = 60
        val DEFAULT_WINDOW = 120.seconds
    }
}

/**
 * Thrown when a listing load is refused by [SourceRequestBudget].
 *
 * Deliberately not an IOException: this is not a failed request, it is a
 * request that was never made, and the two should not look the same to a retry
 * path or to a log.
 */
class SourceBudgetExceededException(
    val sourceId: Long,
    val retryIn: Duration,
) : Exception("Browse request budget reached for source $sourceId; retry in $retryIn")
// <-- AM (A_BROWSE_HAS_A_REQUEST_BUDGET)
