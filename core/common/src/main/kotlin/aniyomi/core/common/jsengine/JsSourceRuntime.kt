package aniyomi.core.common.jsengine

import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * One QuickJS context, pinned to one thread.
 *
 * `QuickJs` is not thread safe and must only be touched from the thread that
 * created it, while sources are called concurrently - a search, a library update
 * and cover loading can all be in flight at once. Pinning the context to a
 * single-thread executor serializes those calls structurally, rather than
 * leaving a shared context behind a lock where a missed call site is a crash.
 *
 * This is the only file that touches the QuickJS binding. If `set`/`get` or the
 * `IntArray` marshalling turn out to behave differently than assumed, the fix is
 * contained here and nothing else in the package needs to change.
 */
class JsSourceRuntime(
    private val script: String,
    private val baseUrl: String,
    private val network: NetworkHelper? = null,
    private val pageSource: () -> String = { "" },
) : Closeable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "js-source").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private val arena = DomArena()
    private val logs = CopyOnWriteArrayList<String>()
    private val http = network?.let { JsHttpBridge(it, arena, baseUrl) }

    private val inFlight = AtomicInteger(0)

    private var quickJs: QuickJs? = null
    private var entry: EntryBridge? = null
    private var check: CheckBridge? = null
    private var pinnedDocument = 0
    private var lineOffset: Int? = null

    @Volatile
    private var maxOverlap = 0

    suspend fun load() = withContext(dispatcher) {
        check(quickJs == null) { "Runtime already loaded" }

        // Parsed once and pinned, so the pre-parsed phase measures extraction
        // without jsoup's parse cost folded into it.
        pinnedDocument = arena.pin(Jsoup.parse(pageSource(), baseUrl))

        val js = QuickJs.create()
        js.set("__dom", DomBridge::class.java, arena)
        js.set(
            "__log",
            LogBridge::class.java,
            object : LogBridge {
                override fun log(message: String) {
                    logs += message
                }
            },
        )
        js.set(
            "__host",
            FixtureBridge::class.java,
            object : FixtureBridge {
                override fun page(): String = pageSource()

                override fun documentHandle(): Int = pinnedDocument
            },
        )

        js.set("__http", HttpBridge::class.java, http ?: UnavailableHttpBridge)
        js.set(
            "__source",
            SourceBridge::class.java,
            object : SourceBridge {
                override fun script(): String = this@JsSourceRuntime.script

                override fun baseUrl(): String = this@JsSourceRuntime.baseUrl
            },
        )

        // Published as soon as the prelude is up, before the project is
        // evaluated, because that is the point at which the engine is usable:
        // [checkSyntax] exists precisely for projects that fail the next line,
        // and would be unreachable if the context were only published on
        // success.
        js.evaluate(JS_PRELUDE, "prelude.js")
        check = js.get("__check", CheckBridge::class.java)
        quickJs = js

        js.evaluate(script, "project.js")
        entry = js.get("__entry", EntryBridge::class.java)
    }

    /**
     * Compiles the project inside the engine; null when it compiles.
     *
     * Usable after a failed [load], which is the whole point of it: the native
     * `evaluate` reports a compile failure with no JavaScript frame, so this is
     * the only route to a line number for a syntax error. Because it compiles
     * without executing, it is also cheap enough to run while someone is typing.
     */
    suspend fun syntaxError(): JsSyntaxError? = withContext(dispatcher) {
        val bridge = check ?: error("Runtime not prepared")
        val report = parseSyntaxReport(bridge.compile()) ?: return@withContext null
        if (report.ok) return@withContext null

        mapSyntaxReport(
            report = report,
            offset = calibratedOffset(bridge),
            lineCount = script.count { it == '\n' } + 1,
        )
    }

    /**
     * Measured once per context, not assumed - see [CALIBRATION_SOURCE].
     *
     * Cached rather than measured per check only because it cannot change under
     * a live context; a new runtime measures again.
     */
    private fun calibratedOffset(bridge: CheckBridge): Int? {
        lineOffset?.let { return it }
        val probe = parseSyntaxReport(bridge.probe(CALIBRATION_SOURCE)) ?: return null
        if (probe.ok || probe.line <= 0) return null
        return (probe.line - CALIBRATION_LINE).takeIf { it >= 0 }?.also { lineOffset = it }
    }

    /**
     * Runs an entry point by name and returns its JSON payload.
     *
     * The arena is cleared whether the call succeeded or threw, which is the
     * whole handle-lifetime contract in one line. [timeoutMillis] is enforced at
     * the host boundary rather than by the engine - see [DomArena.deadlineNanos].
     */
    suspend fun call(name: String, args: String = "{}", timeoutMillis: Long = 0): String = withContext(dispatcher) {
        val bridge = entry ?: error("Runtime not loaded")
        val overlap = inFlight.incrementAndGet()
        if (overlap > maxOverlap) {
            maxOverlap = overlap
        }

        // An abort applies to the call it was requested for, and this is the
        // only safe place to clear it: calls are serialized onto this one
        // thread, so reaching here means the call it was meant for is over.
        arena.aborted = false

        arena.deadlineNanos = if (timeoutMillis > 0) {
            System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        } else {
            DomArena.NO_DEADLINE
        }
        try {
            bridge.call(name, args)
        } finally {
            arena.deadlineNanos = DomArena.NO_DEADLINE
            arena.clear()
            http?.clear()
            inFlight.decrementAndGet()
        }
    }

    /**
     * Asks a running script to stop at its next host crossing. Safe to call from
     * any thread; that is the point of it.
     */
    fun requestAbort() {
        arena.aborted = true
    }

    /**
     * Highest number of calls observed inside [call] at once. Anything above 1
     * means the single-thread dispatch is not actually serializing.
     */
    fun maxObservedOverlap(): Int = maxOverlap

    /**
     * Flips the arena's optimizations. Dispatched onto the runtime's own thread
     * rather than set directly, so it cannot race a call already in flight.
     */
    suspend fun configure(cacheEvaluators: Boolean, fastAbsoluteUrls: Boolean) = withContext(dispatcher) {
        arena.cacheEvaluators = cacheEvaluators
        arena.fastAbsoluteUrls = fastAbsoluteUrls
    }

    fun peakHandles(): Int = arena.peakHandles()

    fun lastCallCrossings(): Int = arena.lastCallCrossings()

    fun cachedSelectors(): Int = arena.cachedSelectors()

    fun httpRequests(): Int = http?.requests() ?: 0

    fun httpBytes(): Long = http?.bytes() ?: 0L

    /** What this project asked the network for, oldest first. */
    fun httpExchanges(): List<JsExchange> = http?.exchanges().orEmpty()

    fun clearHttpExchanges() {
        http?.clearExchanges()
    }

    fun drainLogs(): List<String> {
        val drained = logs.toList()
        logs.clear()
        return drained
    }

    override fun close() {
        executor.execute {
            quickJs?.close()
            quickJs = null
        }
        executor.shutdown()
    }
}

/** Bound when a runtime was created without network access, so `http` fails loudly. */
private object UnavailableHttpBridge : HttpBridge {
    private const val MESSAGE = "This runtime was created without network access"

    override fun request(method: String, url: String, headerLines: String, body: String): Int = fail()

    override fun status(handle: Int): Int = fail()

    override fun finalUrl(handle: Int): String = fail()

    override fun header(handle: Int, name: String): String = fail()

    override fun bodyText(handle: Int): String = fail()

    override fun bodyDocument(handle: Int, baseUrl: String): Int = fail()

    override fun resolveUrl(url: String, headerLines: String): String = fail()

    override fun cookiesFor(url: String): String = fail()

    private fun fail(): Nothing = throw UnsupportedOperationException(MESSAGE)
}
