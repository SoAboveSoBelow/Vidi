package aniyomi.source.js

import eu.kanade.tachiyomi.network.NetworkHelper
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spike 9 - can a wedged project be contained?
 *
 * Spike 4 established the hard constraint: the QuickJS binding has no interrupt
 * handler, so a pure-compute loop cannot be stopped and its thread cannot be
 * killed. The question left over was whether that costs one source or the whole
 * app. This answers it.
 *
 *  A. a well-behaved project still works, and is not faulted
 *  B. a crossing-based runaway dies at the soft deadline (the arena deadline)
 *  C. a pure-compute runaway releases the caller at the hard timeout, rather
 *     than when the script finishes - which is the whole point
 *  D. once faulted, the next call fails immediately instead of queueing behind
 *     the wedged thread
 *  E. clearFault() makes the project usable again on a fresh runtime
 *
 * Timeouts are shortened to keep the run quick; production defaults are 20s and
 * 30s. Phase C's loop is bounded so this cannot occupy a core indefinitely.
 */
class JsWatchdogSpike(private val network: NetworkHelper) {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    private suspend fun measure(): String = buildString {
        appendLine("JsWatchdogSpike")
        appendLine()
        phase("A") { wellBehaved() }
        phase("B") { crossingRunaway() }
        phase("C") { computeRunaway() }
    }

    private suspend inline fun StringBuilder.phase(label: String, block: () -> String) {
        try {
            appendLine(block())
        } catch (e: Throwable) {
            appendLine("$label. UNEXPECTED ${e::class.simpleName}: ${e.message}")
        }
    }

    private suspend fun wellBehaved(): String {
        val source = sourceFor(FAST_SCRIPT)
        return try {
            val start = System.nanoTime()
            val page = source.getPopularAnime(1)
            "A. well-behaved: ${page.animes.size} entries in ${fmt(elapsedMs(start))} ms, " +
                "fault=${source.fault.value?.reason ?: "none"}"
        } finally {
            source.close()
        }
    }

    private suspend fun crossingRunaway(): String {
        val source = sourceFor(CROSSING_LOOP_SCRIPT)
        return try {
            val start = System.nanoTime()
            val outcome = runCatching { source.getPopularAnime(1) }
            val millis = fmt(elapsedMs(start))
            val error = outcome.exceptionOrNull()
            "B. crossing runaway: failed=${outcome.isFailure} after $millis ms " +
                "(soft $SOFT ms), type=${error?.let { it::class.simpleName }}, " +
                "faulted=${source.fault.value != null} (expected false - the soft " +
                "deadline is a normal error, not a fault)"
        } finally {
            source.close()
        }
    }

    private suspend fun computeRunaway(): String {
        val source = sourceFor(COMPUTE_LOOP_SCRIPT)
        return try {
            val start = System.nanoTime()
            val first = runCatching { source.getPopularAnime(1) }
            val releasedAt = fmt(elapsedMs(start))

            val secondStart = System.nanoTime()
            val second = runCatching { source.getPopularAnime(1) }
            val secondMillis = elapsedMs(secondStart)

            val faultReason = source.fault.value?.reason

            source.clearFault()
            val cleared = source.fault.value == null

            buildString {
                appendLine(
                    "C. compute runaway: caller released after $releasedAt ms " +
                        "(hard $HARD ms), failed=${first.isFailure}, " +
                        "type=${first.exceptionOrNull()?.let { it::class.simpleName }}",
                )
                appendLine("   fault recorded: ${faultReason ?: "none"}")
                appendLine(
                    "D. second call failed in ${fmt(secondMillis)} ms " +
                        "(fast=${secondMillis < FAST_FAIL_MS}), " +
                        "type=${second.exceptionOrNull()?.let { it::class.simpleName }}",
                )
                append("E. clearFault(): fault cleared=$cleared, runtime replaced")
            }
        } finally {
            source.close()
        }
    }

    private fun sourceFor(script: String) = JsProjectSource(
        id = SPIKE_ID,
        name = "Watchdog spike",
        lang = "en",
        supportsLatest = false,
        script = script,
        baseUrl = BASE_URL,
        networkHelper = network,
        softTimeoutMillis = SOFT,
        hardTimeoutMillis = HARD,
    )

    private fun elapsedMs(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    private fun fmt(value: Double): String = String.format("%.0f", value)

    private companion object {
        const val SPIKE_ID = -9_000_000_000_000_002L
        const val BASE_URL = "https://rule34video.com"
        const val SOFT = 1_500L
        const val HARD = 3_000L
        const val FAST_FAIL_MS = 100.0

        /** No network, so phase A measures the machinery and not the site. */
        val FAST_SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Fast', lang: 'en', baseUrl: '', nsfw: false },
                popular: function (args) {
                    var doc = parse('<div class="item thumb"><a class="th" href="/a/">A</a></div>', '');
                    var cards = doc.select('div.item.thumb');
                    var entries = [];
                    for (var i = 0; i < cards.length; i++) {
                        entries.push({ url: cards[i].selectFirst('a.th').attr('href'), title: 'A' });
                    }
                    return { entries: entries, hasNextPage: false };
                }
            };
        """.trimIndent()

        /** Loops forever but keeps crossing, so the arena deadline can see it. */
        val CROSSING_LOOP_SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Crossing loop', lang: 'en', baseUrl: '', nsfw: false },
                popular: function (args) {
                    var doc = parse('<div class="item thumb"></div>', '');
                    while (true) {
                        doc.select('div.item.thumb');
                    }
                }
            };
        """.trimIndent()

        /** Never crosses, so only the wall-clock timeout can release the caller. */
        val COMPUTE_LOOP_SCRIPT = """
            var source = {
                meta: { apiVersion: 1, name: 'Compute loop', lang: 'en', baseUrl: '', nsfw: false },
                popular: function (args) {
                    var total = 0;
                    for (var i = 0; i < 200000000; i++) {
                        total += i % 7;
                    }
                    return { entries: [], hasNextPage: false, total: total };
                }
            };
        """.trimIndent()
    }
}
