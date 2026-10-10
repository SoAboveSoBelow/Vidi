package aniyomi.core.common.jsengine

import logcat.LogPriority
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.jsoup.select.Evaluator
import org.jsoup.select.QueryParser
import tachiyomi.core.common.util.system.logcat

private const val NANOS_PER_MILLI = 1_000_000.0

/**
 * Spike 3 - where does the time actually go, and can the engine beat a compiled
 * extension at its own job?
 *
 * Spike 1b answered the original question: the handle boundary costs ~25us per
 * crossing and JavaScript extraction runs at 1.16x Kotlin, so handles are fine
 * and serialization is unnecessary. It also turned up something more
 * interesting - jsoup itself costs ~160us per operation, because
 * `Element.select(String)` re-parses the CSS query every call and `absUrl`
 * constructs a `java.net.URL` every call. Every compiled extension pays that
 * too.
 *
 * Both are fixable in [DomArena], in one place, for every project at once. This
 * measures whether that is worth doing, by timing the same extraction six ways
 * against a document parsed once up front, so there is no subtraction of noisy
 * medians this time:
 *
 *  1. jsoup parse only, Kotlin - context for what a page costs to parse at all
 *  2. extract, Kotlin, naive - what a compiled extension costs today
 *  3. extract, Kotlin, cached selectors + absolute-url fast path
 *  4. extract, JavaScript, naive arena
 *  5. extract, JavaScript, cached selectors
 *  6. extract, JavaScript, cached selectors + absolute-url fast path
 *
 * Phase 6 against phase 2 is the number that matters: engine versus the status
 * quo. Phase 6 against phase 3 is the honest boundary cost once both sides are
 * optimized equally.
 *
 * The payloads of phases 4, 5 and 6 are compared for exact equality, so an
 * optimization that changes results fails loudly instead of looking fast.
 *
 * The fixture is the real listing markup from rule34video - five eager cards
 * carrying a usable `src`, the rest lazy with a base64 placeholder in `src` and
 * the real url in `data-rd-src`. Every run is therefore also a regression test
 * for the thumbnail bug.
 *
 * Throwaway code, and no network: this is a question about CPU.
 */
class JsSpike {

    /**
     * Runs the spike, logs the full report under the `JsSpike` tag, and returns
     * it so a caller can surface the headline.
     */
    suspend fun run(iterations: Int = 50): String = measure(iterations)
        .also { logcat(LogPriority.INFO) { it } }

    @Suppress("LongMethod")
    private suspend fun measure(iterations: Int): String {
        val runtime = JsSourceRuntime(PROJECT_SCRIPT, BASE_URL) { FIXTURE }

        return try {
            val loadNanos = timed { runtime.load() }

            val document = Jsoup.parse(FIXTURE, BASE_URL)
            val kotlinCache = SelectorCache()

            // Warm-up for every path, so first-call class loading and JIT do not
            // land inside the samples.
            kotlinExtract(document, cache = null, fastAbsolute = false)
            kotlinExtract(document, cache = kotlinCache, fastAbsolute = true)

            runtime.configure(cacheEvaluators = false, fastAbsoluteUrls = false)
            val naivePayload = runtime.call("popularPreparsed")
            val crossings = runtime.lastCallCrossings()

            runtime.configure(cacheEvaluators = true, fastAbsoluteUrls = false)
            val cachedPayload = runtime.call("popularPreparsed")

            runtime.configure(cacheEvaluators = true, fastAbsoluteUrls = true)
            val fastPayload = runtime.call("popularPreparsed")

            val parseOnly = sample(iterations) { Jsoup.parse(FIXTURE, BASE_URL) }
            val kotlinNaive = sample(iterations) {
                kotlinExtract(document, cache = null, fastAbsolute = false)
            }
            val kotlinFast = sample(iterations) {
                kotlinExtract(document, cache = kotlinCache, fastAbsolute = true)
            }

            runtime.configure(cacheEvaluators = false, fastAbsoluteUrls = false)
            val jsNaive = sampleSuspending(iterations) { runtime.call("popularPreparsed") }

            runtime.configure(cacheEvaluators = true, fastAbsoluteUrls = false)
            val jsCached = sampleSuspending(iterations) { runtime.call("popularPreparsed") }

            runtime.configure(cacheEvaluators = true, fastAbsoluteUrls = true)
            val jsFast = sampleSuspending(iterations) { runtime.call("popularPreparsed") }

            report(
                loadMillis = loadNanos / NANOS_PER_MILLI,
                parseOnly = parseOnly,
                kotlinNaive = kotlinNaive,
                kotlinFast = kotlinFast,
                jsNaive = jsNaive,
                jsCached = jsCached,
                jsFast = jsFast,
                crossings = crossings,
                cachedSelectors = runtime.cachedSelectors(),
                payloads = listOf(naivePayload, cachedPayload, fastPayload),
                peakHandles = runtime.peakHandles(),
                logs = runtime.drainLogs(),
            )
        } catch (e: Throwable) {
            "JsSpike FAILED: ${e::class.simpleName}: ${e.message}\n\n" +
                e.stackTraceToString().lineSequence().take(12).joinToString("\n")
        } finally {
            runtime.close()
        }
    }

    /** The extraction a compiled extension would do, in both variants. */
    private fun kotlinExtract(document: Document, cache: SelectorCache?, fastAbsolute: Boolean): Int {
        var parsed = 0
        for (card in all(document, "div.item.thumb", cache)) {
            val link = first(card, "a.th", cache) ?: continue
            val url = absolute(link, "href", fastAbsolute)
            val title = first(card, "div.thumb_title", cache)?.text().orEmpty()
            val image = first(card, "div.img img", cache)
            val thumbnail = THUMBNAIL_ATTRS.firstNotNullOfOrNull { attribute ->
                image?.let { absolute(it, attribute, fastAbsolute) }
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            }
            if (url.isNotEmpty() && title.isNotEmpty() && thumbnail != null) {
                parsed++
            }
        }
        first(document, "div.item.pager.next a", cache)
        return parsed
    }

    private fun all(element: Element, query: String, cache: SelectorCache?): Elements =
        if (cache != null) element.select(cache.get(query)) else element.select(query)

    private fun first(element: Element, query: String, cache: SelectorCache?): Element? =
        if (cache != null) element.selectFirst(cache.get(query)) else element.selectFirst(query)

    private fun absolute(element: Element, name: String, fast: Boolean): String {
        if (fast) {
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

    @Suppress("LongParameterList", "LongMethod")
    private fun report(
        loadMillis: Double,
        parseOnly: Stats,
        kotlinNaive: Stats,
        kotlinFast: Stats,
        jsNaive: Stats,
        jsCached: Stats,
        jsFast: Stats,
        crossings: Int,
        cachedSelectors: Int,
        payloads: List<String>,
        peakHandles: Int,
        logs: List<String>,
    ): String = buildString {
        val payloadsAgree = payloads.distinct().size == 1
        val payload = payloads.first()
        val entries = Regex("\"url\":").findAll(payload).count()
        val placeholders = Regex("\"thumbnail\":\"data:").findAll(payload).count()
        val nulls = Regex("\"thumbnail\":null").findAll(payload).count()

        val vsStatusQuo = ratio(kotlinNaive.median, jsFast.median)
        val boundary = jsFast.median - kotlinFast.median
        val perCrossing = if (crossings > 0) boundary * 1000.0 / crossings else Double.NaN

        appendLine(
            "JsSpike: engine ${fmt(jsFast.median)} ms vs compiled-today ${fmt(kotlinNaive.median)} ms " +
                "(${fmt(vsStatusQuo)}x), payloads ${if (payloadsAgree) "agree" else "DIFFER"}",
        )
        appendLine()
        appendLine("load (parse + pin + create + bind + eval): ${fmt(loadMillis)} ms")
        appendLine()
        appendLine("medians over ${jsFast.count} runs, extraction only, one pre-parsed document:")
        appendLine("  1. jsoup parse only, Kotlin        ${fmt(parseOnly.median)} ms")
        appendLine("  2. extract, Kotlin, naive          ${fmt(kotlinNaive.median)} ms")
        appendLine("  3. extract, Kotlin, cache + fast   ${fmt(kotlinFast.median)} ms")
        appendLine("  4. extract, JS, naive              ${fmt(jsNaive.median)} ms")
        appendLine("  5. extract, JS, cache              ${fmt(jsCached.median)} ms")
        appendLine("  6. extract, JS, cache + fast       ${fmt(jsFast.median)} ms")
        appendLine()
        appendLine("what each optimization bought:")
        appendLine("  selector cache, JS   (4 -> 5)      ${fmt(jsNaive.median - jsCached.median)} ms")
        appendLine("  absolute fast path   (5 -> 6)      ${fmt(jsCached.median - jsFast.median)} ms")
        appendLine("  both, Kotlin         (2 -> 3)      ${fmt(kotlinNaive.median - kotlinFast.median)} ms")
        appendLine()
        appendLine("comparisons:")
        appendLine("  engine vs compiled today  (6 / 2)  ${fmt(vsStatusQuo)}x")
        appendLine("  engine vs Kotlin optimized (6 / 3) ${fmt(ratio(kotlinFast.median, jsFast.median))}x")
        appendLine("  naive JS vs naive Kotlin  (4 / 2)  ${fmt(ratio(kotlinNaive.median, jsNaive.median))}x")
        appendLine("  boundary cost, both optimized      ${fmt(boundary)} ms over $crossings crossings")
        appendLine("  per crossing                       ${fmt(perCrossing)} us")
        appendLine("  distinct selectors compiled        $cachedSelectors")
        appendLine()
        appendLine("spread (fastest / slowest ms):")
        appendLine("  parse only    ${fmt(parseOnly.fastest)} / ${fmt(parseOnly.slowest)}")
        appendLine("  Kotlin naive  ${fmt(kotlinNaive.fastest)} / ${fmt(kotlinNaive.slowest)}")
        appendLine("  Kotlin fast   ${fmt(kotlinFast.fastest)} / ${fmt(kotlinFast.slowest)}")
        appendLine("  JS naive      ${fmt(jsNaive.fastest)} / ${fmt(jsNaive.slowest)}")
        appendLine("  JS cache      ${fmt(jsCached.fastest)} / ${fmt(jsCached.slowest)}")
        appendLine("  JS fast       ${fmt(jsFast.fastest)} / ${fmt(jsFast.slowest)}")
        appendLine()
        appendLine("peak live handles: $peakHandles")
        appendLine("entries parsed: $entries (expected $EXPECTED_CARDS)")
        appendLine("thumbnails still base64: $placeholders (expected 0)")
        appendLine("thumbnails null: $nulls (expected 0)")
        if (!payloadsAgree) {
            appendLine()
            appendLine("PAYLOADS DIFFER - an optimization changed the result, timings are meaningless")
        }
        if (logs.isNotEmpty()) {
            appendLine()
            appendLine("console:")
            logs.forEach { appendLine("  $it") }
        }
    }

    /** Compiled selectors held across calls, as a real source would hold fields. */
    private class SelectorCache {
        private val cache = HashMap<String, Evaluator>()

        fun get(query: String): Evaluator = cache.getOrPut(query) { QueryParser.parse(query) }
    }

    private class Stats(samples: LongArray) {
        val count = samples.size
        val median = samples[samples.size / 2] / NANOS_PER_MILLI
        val fastest = samples.first() / NANOS_PER_MILLI
        val slowest = samples.last() / NANOS_PER_MILLI
    }

    private inline fun sample(iterations: Int, block: () -> Unit): Stats {
        val samples = LongArray(iterations)
        for (index in 0 until iterations) {
            samples[index] = timed(block)
        }
        samples.sort()
        return Stats(samples)
    }

    private suspend fun sampleSuspending(iterations: Int, block: suspend () -> Unit): Stats {
        val samples = LongArray(iterations)
        for (index in 0 until iterations) {
            val start = System.nanoTime()
            block()
            samples[index] = System.nanoTime() - start
        }
        samples.sort()
        return Stats(samples)
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun ratio(baseline: Double, candidate: Double): Double =
        if (baseline > 0.0) candidate / baseline else Double.NaN

    private fun fmt(value: Double): String = if (value.isNaN()) "n/a" else String.format("%.2f", value)

    private companion object {
        const val EXPECTED_CARDS = 24
        const val EAGER_CARDS = 5
        const val BASE_URL = "https://rule34video.com"

        val THUMBNAIL_ATTRS = listOf("data-rd-src", "data-rd-jpg", "src")

        /** 1x1 transparent gif, exactly as the site serves it on lazy cards. */
        const val PLACEHOLDER =
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

        val FIXTURE: String by lazy { buildFixture() }

        private fun buildFixture(): String = buildString {
            append("<html><head><title>Latest</title></head><body>")
            append("""<div class="thumbs clearfix" id="custom_list_videos_latest_videos_list_items">""")
            repeat(EXPECTED_CARDS) { index ->
                append(card(index, eager = index < EAGER_CARDS))
            }
            append("</div>")
            append(
                """<div class="item pager next"><a href="/latest-updates/2/" data-action="ajax">Next</a></div>""",
            )
            append("</body></html>")
        }

        private fun card(index: Int, eager: Boolean): String {
            val id = 4653000 + index
            val shot = "https://rule34video.com/contents/videos_screenshots/4653000/$id"
            val img = if (eager) {
                """<img class="thumb" src="$shot/336x189/3.jpg" data-rd-jpg="$shot/320x180/3.jpg" """ +
                    """alt="Entry $index" width="320" height="180" loading="eager" fetchpriority="high"/>"""
            } else {
                """<img class="thumb rd-lz" src="$PLACEHOLDER" data-rd-src="$shot/336x189/3.jpg" """ +
                    """data-rd-jpg="$shot/320x180/3.jpg" alt="Entry $index" width="320" height="180" """ +
                    """loading="lazy" decoding="async"/>"""
            }
            return """<div class="item thumb video_1   " data-video-card-id="$id">""" +
                """<a class="th" href="https://rule34video.com/video/$id/entry-$index/" title="Entry $index">""" +
                """<div class="img wrap_image" data-preview="$shot/${id}_preview.mp4/">$img""" +
                """<div class="time">2:30</div></div>""" +
                """<div class="thumb_title">Entry $index</div>""" +
                """<div class="thumb_info video-card-meta"><div class="added">right now</div></div>""" +
                """</a></div>"""
        }

        /**
         * A project written against the contract: a global `source` object with
         * `meta` and the entry points. `extract` is shared so the phases differ
         * only in where the document comes from and how the arena is configured.
         */
        val PROJECT_SCRIPT = """
            var source = {
                meta: {
                    apiVersion: 1,
                    name: 'Spike',
                    lang: 'en',
                    baseUrl: 'https://rule34video.com',
                    nsfw: true
                },

                extract: function (doc) {
                    var cards = doc.select('div.item.thumb');
                    var entries = [];

                    for (var i = 0; i < cards.length; i++) {
                        var card = cards[i];
                        var link = card.selectFirst('a.th');
                        if (link === null) continue;

                        entries.push({
                            url: link.absAttr('href'),
                            title: card.selectFirst('div.thumb_title').text(),
                            thumbnail: card.selectFirst('div.img img')
                                .firstAttr('data-rd-src', 'data-rd-jpg', 'src')
                        });
                    }

                    return {
                        entries: entries,
                        hasNextPage: doc.selectFirst('div.item.pager.next a') !== null
                    };
                },

                popular: function (args) {
                    return this.extract(parse(__host.page(), this.meta.baseUrl));
                },

                popularPreparsed: function (args) {
                    return this.extract(pinnedDocument());
                }
            };
        """.trimIndent()
    }
}
