package aniyomi.core.common.jsengine

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Evaluator
import org.jsoup.select.QueryParser

/**
 * The handle table behind [DomBridge], and the answer to handle lifetime.
 *
 * Handles live for exactly one entry-point call. The runtime calls [clear] in a
 * `finally` when the call returns, dropping every node at once, so there is no
 * reference counting on the JavaScript side and no way to leak a document
 * between calls.
 *
 * The cost of that choice is that project code must not stash a node in a global
 * and reuse it on the next call. [node] turns that into an immediate, named
 * error rather than a silent wrong answer, which is the failure mode worth
 * having.
 *
 * [pin] is the deliberate exception: a handle that survives [clear], for a
 * document the host wants to keep across calls. Nothing in project code can
 * create one.
 *
 * ## Why the two optimization flags exist
 *
 * Spike 1b showed the boundary costs ~25us per crossing while jsoup itself costs
 * ~160us per operation, because `Element.select(String)` re-parses the CSS query
 * on every call and `absUrl` builds a `java.net.URL` every time. Both are fixable
 * here, in one place, for every project - which is something 250 separately
 * compiled extensions cannot share. The flags exist so spike 3 can measure each
 * one against the unoptimized path instead of assuming.
 *
 * Reusing an [Evaluator] is safe because a runtime owns one arena pinned to one
 * thread; the structural evaluators that memoize do so per thread.
 */
internal class DomArena : DomBridge {

    /** Reuse compiled selectors instead of re-parsing the query string. */
    var cacheEvaluators = false

    /** Skip URL resolution when the attribute is already an absolute http(s) url. */
    var fastAbsoluteUrls = false

    private val nodes = HashMap<Int, Element>()
    private val pinned = HashMap<Int, Element>()
    private val evaluators = HashMap<String, Evaluator>()
    private var nextHandle = FIRST_HANDLE
    private var peak = 0

    private var crossings = 0
    private var lastCrossings = 0

    /**
     * Abort state, read on every crossing.
     *
     * The QuickJS binding exposes no interrupt handler, so the host boundary is
     * the only place a running script can be stopped: every crossing checks
     * these and throws, which surfaces in JavaScript as an exception and unwinds
     * the call. That covers any script doing real work - scraping cannot proceed
     * without fetching or parsing - but it cannot touch a pure-compute loop that
     * never calls out. [JsSafetySpike] measures exactly where that line falls.
     *
     * Both are written from other threads and read on the runtime's thread.
     *
     * [aborted] belongs to one call and is cleared at the start of the next -
     * see [JsSourceRuntime.call]. It used to persist for the life of the
     * runtime, which made a single cancelled browse fatal: every later crossing
     * threw "aborted by host" and the source never worked again. Cancellation is
     * routine - collectLatest, scrolling away, a superseded search - so in
     * practice a project poisoned itself within seconds of being used.
     */
    @Volatile
    var aborted = false

    @Volatile
    var deadlineNanos = NO_DEADLINE

    override fun parse(html: String, baseUrl: String): Int {
        enter()
        return store(Jsoup.parse(html, baseUrl))
    }

    override fun select(handle: Int, query: String): IntArray {
        enter()
        val element = node(handle)
        val found = if (cacheEvaluators) element.select(evaluator(query)) else element.select(query)
        return IntArray(found.size) { store(found[it]) }
    }

    override fun selectFirst(handle: Int, query: String): Int {
        enter()
        val element = node(handle)
        val found = if (cacheEvaluators) element.selectFirst(evaluator(query)) else element.selectFirst(query)
        return found?.let(::store) ?: NULL_HANDLE
    }

    override fun attr(handle: Int, name: String): String {
        enter()
        return node(handle).attr(name)
    }

    override fun absAttr(handle: Int, name: String): String {
        enter()
        val element = node(handle)
        if (fastAbsoluteUrls) {
            val raw = element.attr(name)
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                return raw
            }
            if (raw.isEmpty()) {
                return ""
            }
        }
        return element.absUrl(name)
    }

    override fun text(handle: Int): String {
        enter()
        return node(handle).text()
    }

    override fun ownText(handle: Int): String {
        enter()
        return node(handle).ownText()
    }

    override fun html(handle: Int): String {
        enter()
        return node(handle).html()
    }

    /** Stores [element] under a handle that [clear] will not drop. */
    fun pin(element: Element): Int {
        val handle = nextHandle++
        pinned[handle] = element
        return handle
    }

    /** Highest number of simultaneously live per-call handles seen so far. */
    fun peakHandles(): Int = peak

    /** Boundary crossings during the most recently cleared call. */
    fun lastCallCrossings(): Int = lastCrossings

    /** Distinct selectors compiled so far, when [cacheEvaluators] is on. */
    fun cachedSelectors(): Int = evaluators.size

    fun clear() {
        lastCrossings = crossings
        crossings = 0
        nodes.clear()
    }

    /**
     * Abort check without counting a DOM crossing, for other host bridges that
     * must honour the same deadline - see [JsHttpBridge].
     */
    fun checkAbort() {
        if (aborted) {
            throw JsAbortException("aborted by host")
        }
        if (deadlineNanos != NO_DEADLINE && System.nanoTime() > deadlineNanos) {
            throw JsAbortException("deadline exceeded")
        }
    }

    /** Takes ownership of an element parsed elsewhere in the host. */
    fun adopt(element: Element): Int = store(element)

    private fun enter() {
        checkAbort()
        crossings++
    }

    private fun evaluator(query: String): Evaluator = evaluators.getOrPut(query) { QueryParser.parse(query) }

    private fun store(element: Element): Int {
        val handle = nextHandle++
        nodes[handle] = element
        if (nodes.size > peak) {
            peak = nodes.size
        }
        return handle
    }

    private fun node(handle: Int): Element = nodes[handle] ?: pinned[handle] ?: throw IllegalStateException(
        "Stale DOM handle $handle - nodes do not outlive the call that created them",
    )

    companion object {
        const val NO_DEADLINE = 0L

        private const val NULL_HANDLE = 0
        private const val FIRST_HANDLE = 1
    }
}

/** Thrown from the host boundary to unwind a script that must stop. */
class JsAbortException(message: String) : RuntimeException(message)
