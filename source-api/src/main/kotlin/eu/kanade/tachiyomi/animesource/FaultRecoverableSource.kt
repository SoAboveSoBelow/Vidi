package eu.kanade.tachiyomi.animesource

// AM (JS_FAULT_MANUAL_RECOVERY) -->
/**
 * A source that can latch a runtime fault - JS projects, whose watchdog
 * refuses further calls once a project stops yielding (see JsProjectSource).
 *
 * The browse paging layer reads this so a faulted source's error is surfaced
 * as an ordinary retryable failure and a user-triggered retry is a genuine
 * fresh attempt, rather than an instant re-throw of the latched fault.
 */
interface FaultRecoverableSource {

    /** True while the source is refusing calls over a latched fault. */
    val isFaulted: Boolean

    /** Rebuilds whatever faulted so the next request starts fresh. */
    suspend fun clearFault()
}
// <-- AM (JS_FAULT_MANUAL_RECOVERY)
