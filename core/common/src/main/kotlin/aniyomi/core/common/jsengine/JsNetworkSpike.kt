package aniyomi.core.common.jsengine

import eu.kanade.tachiyomi.network.NetworkHelper
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spike 5 - does a project work as a real source against a live site?
 *
 * Everything so far ran against a synthetic 40KB fixture. This runs the same
 * contract against the actual listing page, through [NetworkHelper.client], and
 * answers the questions a fixture cannot:
 *
 *  - does the app's network stack carry a project for free? The DDoS-Guard
 *    interceptor, cookie jar, user agent and DoH settings are all on that client,
 *    so a project should get past the same protections a compiled extension does,
 *    without knowing they exist.
 *  - what does a real page cost? The fixture was 40KB of regular markup; the real
 *    one is ~160KB with scripts and 30 `tag_item` links, which recalibrates every
 *    timing measured so far.
 *  - is `Response.html()` worth it? It parses host-side and returns a node, so the
 *    body never crosses the boundary. `fetchOnly` calls `text().length` instead,
 *    which marshals the whole body, and the difference prices that choice.
 *  - does extraction still work on markup nobody tailored for it?
 *
 * Hits the network six times against one host. Note the one gap: the deadline is
 * checked before a request is issued, not during it, so a slow response is bounded
 * by OkHttp's own call timeout rather than by the project's budget.
 */
class JsNetworkSpike(private val network: NetworkHelper) {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    private suspend fun measure(): String {
        val runtime = JsSourceRuntime(PROJECT_SCRIPT, BASE_URL, network)

        return try {
            val loadMillis = millis { runtime.load() }

            val coldStart = System.nanoTime()
            val cold = runtime.call("popular", PAGE_ONE)
            val coldMillis = elapsed(coldStart)

            val warm = LongArray(WARM_RUNS)
            for (index in 0 until WARM_RUNS) {
                val start = System.nanoTime()
                runtime.call("popular", PAGE_ONE)
                warm[index] = System.nanoTime() - start
            }
            warm.sort()

            val fetchOnly = LongArray(FETCH_ONLY_RUNS)
            var fetchPayload = ""
            for (index in 0 until FETCH_ONLY_RUNS) {
                val start = System.nanoTime()
                fetchPayload = runtime.call("fetchOnly", PAGE_ONE)
                fetchOnly[index] = System.nanoTime() - start
            }
            fetchOnly.sort()

            report(
                loadMillis = loadMillis,
                coldMillis = coldMillis,
                warmMedian = warm[warm.size / 2] / NANOS,
                warmFastest = warm.first() / NANOS,
                fetchMedian = fetchOnly[fetchOnly.size / 2] / NANOS,
                payload = cold,
                fetchPayload = fetchPayload,
                requests = runtime.httpRequests(),
                bytes = runtime.httpBytes(),
                crossings = runtime.lastCallCrossings(),
                selectors = runtime.cachedSelectors(),
                logs = runtime.drainLogs(),
            )
        } catch (e: Throwable) {
            "JsNetworkSpike FAILED: ${e::class.simpleName}: ${e.message}\n\n" +
                e.stackTraceToString().lineSequence().take(14).joinToString("\n")
        } finally {
            runtime.close()
        }
    }

    @Suppress("LongParameterList")
    private fun report(
        loadMillis: Double,
        coldMillis: Double,
        warmMedian: Double,
        warmFastest: Double,
        fetchMedian: Double,
        payload: String,
        fetchPayload: String,
        requests: Int,
        bytes: Long,
        crossings: Int,
        selectors: Int,
        logs: List<String>,
    ): String = buildString {
        val entries = Regex("\"url\":\"").findAll(payload).count()
        val placeholders = Regex("\"thumbnail\":\"data:").findAll(payload).count()
        val nulls = Regex("\"thumbnail\":null").findAll(payload).count()
        val status = Regex("\"status\":(\\d+)").find(payload)?.groupValues?.get(1) ?: "?"
        val nextPage = payload.contains("\"hasNextPage\":true")
        val cards = field(payload, "cards")
        val skipped = field(payload, "skipped")
        val missingTitle = field(payload, "missingTitle")
        val missingThumbnail = field(payload, "missingThumbnail")

        appendLine(
            "JsNetworkSpike: HTTP $status, $entries of $cards cards, ${fmt(warmMedian)} ms warm, " +
                "$placeholders base64 / $nulls null thumbnails",
        )
        appendLine()
        appendLine("page shape: $cards cards, $skipped without a link, $missingTitle without a title, ")
        appendLine("  $missingThumbnail without an image - heterogeneous cards are the norm, not the exception")
        appendLine()
        appendLine("load: ${fmt(loadMillis)} ms")
        appendLine("cold call (first request, no cookies yet): ${fmt(coldMillis)} ms")
        appendLine("warm popular(): median ${fmt(warmMedian)} ms, fastest ${fmt(warmFastest)} ms")
        appendLine("fetchOnly() with text() marshalling: median ${fmt(fetchMedian)} ms")
        appendLine("  -> html() keeps the body host-side; the gap against warm popular() prices that")
        appendLine()
        appendLine("requests: $requests, body bytes read: $bytes")
        appendLine("crossings on last call: $crossings")
        appendLine("distinct selectors compiled: $selectors")
        appendLine("hasNextPage: $nextPage")
        appendLine("fetchOnly payload: $fetchPayload")
        appendLine()
        appendLine("first 500 chars of payload:")
        appendLine(payload.take(PAYLOAD_PREVIEW))
        if (logs.isNotEmpty()) {
            appendLine()
            appendLine("console:")
            logs.forEach { appendLine("  $it") }
        }
    }

    private inline fun millis(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return elapsed(start)
    }

    private fun elapsed(startNanos: Long): Double = (System.nanoTime() - startNanos) / NANOS

    private fun fmt(value: Double): String = String.format("%.1f", value)

    private fun field(payload: String, name: String): String =
        Regex("\"$name\":(-?\\d+)").find(payload)?.groupValues?.get(1) ?: "?"

    private companion object {
        const val NANOS = 1_000_000.0
        const val BASE_URL = "https://rule34video.com"
        const val WARM_RUNS = 3
        const val FETCH_ONLY_RUNS = 2
        const val PAYLOAD_PREVIEW = 500
        const val PAGE_ONE = """{"page":1}"""

        /**
         * The same extraction the real extension does, written against the
         * project contract. `absAttr`, `firstAttr` and the selectors are
         * unchanged from the fixture version - only the document source differs.
         */
        val PROJECT_SCRIPT = """
            var source = {
                meta: {
                    apiVersion: 1,
                    name: 'Rule34Video (live)',
                    lang: 'en',
                    baseUrl: 'https://rule34video.com',
                    nsfw: true
                },

                popular: function (args) {
                    var page = args.page || 1;
                    var res = http.get(this.meta.baseUrl + '/latest-updates/' + page + '/', {
                        'Referer': this.meta.baseUrl + '/'
                    });
                    var doc = res.html();
                    var cards = doc.select('div.item.thumb');
                    var entries = [];

                    var skipped = 0;
                    var missingTitle = 0;
                    var missingThumbnail = 0;

                    for (var i = 0; i < cards.length; i++) {
                        var card = cards[i];
                        var link = card.selectFirst('a.th');
                        if (link === null) { skipped++; continue; }

                        var title = card.textIn('div.thumb_title');
                        var thumbnail = card.attrIn('div.img img', 'data-rd-src', 'data-rd-jpg', 'src');
                        if (title === null) missingTitle++;
                        if (thumbnail === null) missingThumbnail++;

                        entries.push({ url: link.absAttr('href'), title: title, thumbnail: thumbnail });
                    }

                    return {
                        status: res.status(),
                        finalUrl: res.url(),
                        cards: cards.length,
                        skipped: skipped,
                        missingTitle: missingTitle,
                        missingThumbnail: missingThumbnail,
                        entries: entries,
                        hasNextPage: doc.selectFirst('div.item.pager.next a') !== null
                    };
                },

                fetchOnly: function (args) {
                    var page = args.page || 1;
                    var res = http.get(this.meta.baseUrl + '/latest-updates/' + page + '/');
                    return { status: res.status(), length: res.text().length };
                }
            };
        """.trimIndent()
    }
}
