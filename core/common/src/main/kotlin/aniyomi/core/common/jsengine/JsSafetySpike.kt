package aniyomi.core.common.jsengine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spikes 3 and 4 - can a runaway project be stopped, and does one context
 * survive concurrent calls?
 *
 * These are the two remaining ways the design fails, and neither is about
 * performance. The QuickJS binding in this build exposes no interrupt handler,
 * so the host boundary is the only place a running script can be stopped. That
 * is enough for anything doing real work, because scraping cannot proceed
 * without fetching or parsing - but it is provably not enough for a pure-compute
 * loop. These phases find exactly where that line falls instead of assuming it.
 *
 *  A. deadline, with host calls - a loop that selects forever, 200ms budget
 *  B. abort, with host calls - the same loop stopped from another thread
 *  C. runaway, no host calls - a spin loop no crossing can reach
 *  D. concurrency - eight simultaneous calls against one runtime
 *  E. isolation - two runtimes in parallel, checked for cross-talk
 *
 * Phase C's loop is bounded rather than truly infinite, so the spike cannot
 * leave a core spinning for the life of the process. The finding still lands:
 * the caller abandons it long before it finishes, and the thread stays occupied.
 */
class JsSafetySpike {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    private suspend fun measure(): String = buildString {
        appendLine("JsSafetySpike")
        appendLine()
        phase("A") { deadlineWithHostCalls() }
        phase("B") { abortWithHostCalls() }
        phase("C") { runawayWithoutHostCalls() }
        phase("D") { concurrentCalls() }
        phase("E") { twoRuntimes() }
    }

    private suspend inline fun StringBuilder.phase(label: String, block: () -> String) {
        try {
            appendLine(block())
        } catch (e: Throwable) {
            appendLine("$label. UNEXPECTED ${describe(e)}")
        }
    }

    /** A: a script looping over host calls should die at its deadline. */
    private suspend fun deadlineWithHostCalls(): String {
        val runtime = runtime(LOOPING_SCRIPT)
        return try {
            runtime.load()
            val start = System.nanoTime()
            val outcome = runCatching { runtime.call("loopForever", timeoutMillis = BUDGET_MILLIS) }
            val elapsed = elapsedMillis(start)
            "A. deadline with host calls: stopped after ${fmt(elapsed)} ms (budget $BUDGET_MILLIS ms), " +
                "threw=${outcome.isFailure}, abort=${isAbort(outcome.exceptionOrNull())}, " +
                "type=${outcome.exceptionOrNull()?.let { it::class.simpleName } ?: "none"}"
        } finally {
            runtime.close()
        }
    }

    /** B: the same loop, stopped from another thread mid-flight. */
    private suspend fun abortWithHostCalls(): String = coroutineScope {
        val runtime = runtime(LOOPING_SCRIPT)
        try {
            runtime.load()
            val start = System.nanoTime()
            launch {
                delay(BUDGET_MILLIS)
                runtime.requestAbort()
            }
            val outcome = runCatching { runtime.call("loopForever") }
            "B. abort from another thread: stopped after ${fmt(elapsedMillis(start))} ms, " +
                "threw=${outcome.isFailure}, abort=${isAbort(outcome.exceptionOrNull())}"
        } finally {
            runtime.close()
        }
    }

    /**
     * C: a spin loop with no host calls, expected to outlive its coroutine.
     *
     * The useful results are that an unrelated coroutine keeps running on time
     * (so the app is unaffected) and that a follow-up call on the same runtime
     * cannot get through (so that thread is genuinely still occupied). The
     * second part is what an engine-level interrupt would buy.
     */
    private suspend fun runawayWithoutHostCalls(): String = coroutineScope {
        val runtime = runtime(SPIN_SCRIPT)
        try {
            runtime.load()
            val start = System.nanoTime()
            val spin = async { runCatching { runtime.call("spin", SPIN_ARGS, timeoutMillis = BUDGET_MILLIS) } }

            // While it spins: is an unrelated coroutine still on time?
            val probeStart = System.nanoTime()
            delay(PROBE_MILLIS)
            val drift = elapsedMillis(probeStart) - PROBE_MILLIS

            // And can a second call on the same runtime get in? It only has to
            // reach the dispatcher, so a null here means the thread is occupied.
            val followUp = withTimeoutOrNull(PROBE_MILLIS) { runtime.call("spin", "{\"limit\":1}") }
            val blockedAt = elapsedMillis(start)

            spin.await()
            "C. runaway without host calls: deadline ignored, ran ${fmt(elapsedMillis(start))} ms " +
                "against a $BUDGET_MILLIS ms budget; unrelated coroutine drift ${fmt(drift)} ms, " +
                "follow-up call got through=${followUp != null} after ${fmt(blockedAt)} ms - " +
                "no crossing exists to interrupt it"
        } finally {
            runtime.close()
        }
    }

    /** D: eight concurrent calls against one context must serialize, not crash. */
    private suspend fun concurrentCalls(): String = coroutineScope {
        val runtime = runtime(COUNTING_SCRIPT)
        try {
            runtime.load()
            val results = (1..CONCURRENT_CALLS)
                .map { async { runCatching { runtime.call("entries") } } }
                .awaitAll()
            val failures = results.count { it.isFailure }
            val payloads = results.mapNotNull { it.getOrNull() }.distinct()
            val firstFailure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            "D. $CONCURRENT_CALLS concurrent calls: failures=$failures, " +
                "distinct payloads=${payloads.size} (expected 1), " +
                "max overlap=${runtime.maxObservedOverlap()} (expected 1)" +
                (firstFailure?.let { ", first=${describe(it)}" } ?: "") +
                ", payload=${payloads.firstOrNull()}"
        } finally {
            runtime.close()
        }
    }

    /** E: two runtimes in parallel must not see each other's state. */
    private suspend fun twoRuntimes(): String = coroutineScope {
        val first = runtime(namedScript("first"))
        val second = runtime(namedScript("second"))
        try {
            first.load()
            second.load()
            val results = listOf(
                async { first.call("name") },
                async { second.call("name") },
            ).awaitAll()
            val isolated = results[0].contains("first") && results[1].contains("second")
            "E. two runtimes in parallel: isolated=$isolated, got ${results.joinToString()}"
        } finally {
            first.close()
            second.close()
        }
    }

    private fun runtime(script: String) = JsSourceRuntime(script, BASE_URL) { FIXTURE }

    /**
     * Spike 4 established that the binding passes host exceptions through
     * unwrapped, so the type is reliable and the old message-marker hack is
     * gone. The cause chain is still walked in case that ever changes.
     */
    private fun isAbort(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is JsAbortException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun describe(error: Throwable): String =
        "${error::class.simpleName}: ${error.message?.take(DESCRIBE_LIMIT)}"

    private fun elapsedMillis(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    private fun fmt(value: Double): String = String.format("%.1f", value)

    private companion object {
        const val BASE_URL = "https://rule34video.com"
        const val BUDGET_MILLIS = 200L
        const val PROBE_MILLIS = 150L
        const val CONCURRENT_CALLS = 8
        const val SPIN_ITERATIONS = 40_000_000
        val SPIN_ARGS = """{"limit":$SPIN_ITERATIONS}"""
        const val DESCRIBE_LIMIT = 160

        /** Small enough to keep these phases about control flow, not parsing. */
        val FIXTURE = """
            <html><body>
            <div class="item thumb"><a class="th" href="/a/">A</a></div>
            <div class="item thumb"><a class="th" href="/b/">B</a></div>
            </body></html>
        """.trimIndent()

        /** Loops forever, but touches the host every iteration. */
        val LOOPING_SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Looper', lang: 'en', baseUrl: '', nsfw: false },
                loopForever: function () {
                    var doc = pinnedDocument();
                    var spins = 0;
                    while (true) {
                        doc.select('div.item.thumb');
                        spins++;
                    }
                    return spins;
                }
            };
        """.trimIndent()

        /** Spins without ever calling the host, so no crossing can stop it. */
        val SPIN_SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Spinner', lang: 'en', baseUrl: '', nsfw: false },
                spin: function (args) {
                    var limit = args.limit;
                    var total = 0;
                    for (var i = 0; i < limit; i++) {
                        total += i % 7;
                    }
                    return total;
                }
            };
        """.trimIndent()

        /** Ordinary work, for the concurrency phase. */
        val COUNTING_SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Counter', lang: 'en', baseUrl: '', nsfw: false },
                entries: function () {
                    var doc = pinnedDocument();
                    var cards = doc.select('div.item.thumb');
                    var urls = [];
                    for (var i = 0; i < cards.length; i++) {
                        urls.push(cards[i].selectFirst('a.th').attr('href'));
                    }
                    return urls;
                }
            };
        """.trimIndent()

        fun namedScript(name: String) = """
            var source = {
                meta: { apiVersion: 1, name: '$name', lang: 'en', baseUrl: '', nsfw: false },
                name: function () { return this.meta.name; }
            };
        """.trimIndent()
    }
}
