package aniyomi.core.common.jsengine

/**
 * One request a project made, recorded where it was actually made.
 *
 * The obvious way to build a network inspector is an OkHttp `EventListener`, and
 * it is the wrong way here: the project shares [eu.kanade.tachiyomi.network.NetworkHelper.client]
 * with the rest of the app, so a listener on that client sees every cover load
 * and library update too, and giving each project its own client would discard
 * the shared connection pool, cookie jar and Cloudflare state that are the
 * reason project code runs in-app at all.
 *
 * [JsHttpBridge] is the only place a project's request can originate, so the
 * record is taken there and attributes nothing it did not cause.
 */
class JsExchange(
    val method: String,
    val url: String,
    val status: Int,
    val bytes: Long,
    val durationMillis: Long,
    /** Non-null when the call threw instead of returning a response. */
    val failure: String? = null,
) {
    override fun toString(): String = buildString {
        append(method).append(' ')
        append(if (failure == null) status.toString() else "ERR")
        append(' ').append(url)
        if (failure == null) {
            append(" (").append(bytes).append("B, ").append(durationMillis).append("ms)")
        } else {
            append(" - ").append(failure)
        }
    }
}
