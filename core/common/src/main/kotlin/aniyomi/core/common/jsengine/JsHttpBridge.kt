package aniyomi.core.common.jsengine

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/**
 * Host HTTP, exposed to project code.
 *
 * Responses cross as integer handles for the same reason DOM nodes do, and
 * because of one useful consequence: [bodyDocument] parses the body in Kotlin
 * and returns a *DOM* handle, so a 160KB page never crosses the boundary at all.
 * A project that calls `http.get(url).html()` pays zero marshalling for the
 * body.
 *
 * Requests go through [NetworkHelper.client], which is the whole point of
 * running in-app rather than in a side tool: the Cloudflare interceptor, the
 * cookie jar, the user-agent and the DoH settings all come free and behave
 * exactly as they do for a compiled extension.
 *
 * Calls block the runtime's own thread. That is deliberate - see
 * [JsSourceRuntime] - and safe because that thread exists only for this project.
 */
internal interface HttpBridge {
    /** Returns a response handle, or throws if the request could not be made. */
    fun request(method: String, url: String, headerLines: String, body: String): Int

    fun status(handle: Int): Int

    fun finalUrl(handle: Int): String

    fun header(handle: Int, name: String): String

    fun bodyText(handle: Int): String

    /** Parses the body and returns a DOM handle, keeping the body host-side. */
    fun bodyDocument(handle: Int, baseUrl: String): Int

    /**
     * Follows redirects without reading a body, and returns where they land.
     *
     * Exists because the alternative is catastrophic. A media link is usually a
     * redirect to a CDN, and the player needs the final URL - but asking for it
     * with an ordinary GET would download the file into memory to find out. This
     * reads `Location` headers and closes each response untouched.
     *
     * Returns the resolved URL, or an empty string if it could not be resolved.
     */
    fun resolveUrl(url: String, headerLines: String): String

    /**
     * The cookies the shared client would send to [url], as a header value.
     *
     * The player is a separate process with no access to the app's cookie jar,
     * so a video URL that needs a session has to carry it explicitly. Scoped to
     * the URL asked for, which is the same scope the client itself would use.
     */
    fun cookiesFor(url: String): String
}

internal class JsHttpBridge(
    private val network: NetworkHelper,
    private val arena: DomArena,
    private val baseUrl: String,
) : HttpBridge {

    private class Captured(
        val status: Int,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private val responses = HashMap<Int, Captured>()
    private var nextHandle = 1
    private var requestCount = 0
    private var bytesRead = 0L

    /**
     * Bounded on purpose. A project in a loop can make requests faster than
     * anyone reads them, and an editor pane only ever shows the tail; an
     * unbounded list would be a leak dressed up as a feature.
     *
     * Appended from the runtime's thread and read from whichever thread is
     * showing the inspector - including while a run the editor has already given
     * up on is still making requests - so every access is guarded.
     */
    private val recorded = ArrayDeque<JsExchange>()
    private val recordLock = Any()

    override fun request(method: String, url: String, headerLines: String, body: String): Int {
        arena.checkAbort()

        val builder = Request.Builder().url(resolve(url))
        parseHeaderLines(headerLines).forEach { (name, value) -> builder.addHeader(name, value) }
        val normalizedMethod = method.uppercase()
        when (normalizedMethod) {
            "GET" -> builder.get()
            "POST" -> builder.post(body.toRequestBody(null))
            else -> throw IllegalArgumentException("Unsupported method $method")
        }

        val startedAt = System.nanoTime()
        val captured = try {
            network.client.newCall(builder.build()).execute().use { response ->
                val text = response.body.string()
                Captured(
                    status = response.code,
                    url = response.request.url.toString(),
                    headers = response.headers.toMultimap().mapValues { it.value.joinToString() },
                    body = text,
                )
            }
        } catch (e: Exception) {
            // Recorded before rethrowing: a request that failed is the one the
            // person most needs to see in the inspector.
            record(
                JsExchange(
                    method = normalizedMethod,
                    url = url,
                    status = 0,
                    bytes = 0L,
                    durationMillis = elapsedMillis(startedAt),
                    failure = "${e::class.simpleName}: ${e.message}",
                ),
            )
            throw e
        }

        requestCount++
        bytesRead += captured.body.length.toLong()
        record(
            JsExchange(
                method = normalizedMethod,
                url = captured.url,
                status = captured.status,
                bytes = captured.body.length.toLong(),
                durationMillis = elapsedMillis(startedAt),
            ),
        )

        val handle = nextHandle++
        responses[handle] = captured
        return handle
    }

    override fun status(handle: Int): Int = response(handle).status

    override fun finalUrl(handle: Int): String = response(handle).url

    override fun header(handle: Int, name: String): String =
        response(handle).headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    override fun bodyText(handle: Int): String = response(handle).body

    override fun bodyDocument(handle: Int, baseUrl: String): Int {
        val captured = response(handle)
        val base = baseUrl.ifEmpty { captured.url }
        return arena.adopt(Jsoup.parse(captured.body, base))
    }

    override fun resolveUrl(url: String, headerLines: String): String {
        arena.checkAbort()

        var current = resolve(url).toString()
        repeat(MAX_REDIRECTS) {
            val builder = Request.Builder().url(current).get()
            parseHeaderLines(headerLines).forEach { (name, value) -> builder.addHeader(name, value) }

            val startedAt = System.nanoTime()
            // close() rather than use { body.string() }: reading the body is the
            // one thing this method exists to avoid.
            val response = noRedirects.newCall(builder.build()).execute()
            val location = response.header("location")
            val code = response.code
            val finalUrl = response.request.url
            response.close()

            record(
                JsExchange(
                    method = "HEAD",
                    url = current,
                    status = code,
                    bytes = 0L,
                    durationMillis = elapsedMillis(startedAt),
                ),
            )
            requestCount++

            if (location.isNullOrBlank()) {
                // Not a redirect: this is already the real location, as long as
                // the server was willing to serve it.
                return if (code in 200..299) finalUrl.toString() else ""
            }
            current = finalUrl.resolve(location)?.toString() ?: return ""
        }
        return ""
    }

    override fun cookiesFor(url: String): String {
        arena.checkAbort()
        val parsed = url.toHttpUrlOrNull() ?: return ""
        return network.client.cookieJar.loadForRequest(parsed)
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Resolves a request url, relative or absolute.
     *
     * A project writing `http.get('/latest/1')` is writing the natural thing,
     * and every scraping library resolves that against a base - jsoup does it
     * for `absAttr`, so it would be strange for the http side not to. Without
     * this the request reached okhttp as a path and failed with "expected url
     * scheme http or https", which says nothing about the actual mistake.
     *
     * And when there is no base url to resolve against, that *is* the mistake,
     * so it is what the error says.
     */
    private fun resolve(url: String): HttpUrl {
        url.toHttpUrlOrNull()?.let { return it }

        val base = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException(
                "Cannot request '$url': it is not an absolute url and this project has no " +
                    "base url set. Add one in Project settings, or request a full https:// url.",
            )

        return base.resolve(url)
            ?: throw IllegalArgumentException("Cannot resolve '$url' against '$baseUrl'")
    }

    /**
     * Shares the pool, the cookie jar and the interceptors with the main client
     * - newBuilder keeps all of it - and differs only in not following
     * redirects, which is the whole point.
     */
    private val noRedirects by lazy {
        network.client.newBuilder().followRedirects(false).build()
    }

    fun requests(): Int = requestCount

    fun bytes(): Long = bytesRead

    /** Oldest first, capped at [MAX_RECORDED]. */
    fun exchanges(): List<JsExchange> = synchronized(recordLock) { recorded.toList() }

    /**
     * Response handles die with the call that made them - see
     * [JsSourceRuntime.call] - but the exchange log is diagnostics and outlives
     * them deliberately: the editor shows what the last browse did, after it is
     * over.
     */
    fun clear() {
        responses.clear()
    }

    fun clearExchanges() {
        synchronized(recordLock) { recorded.clear() }
    }

    private fun record(exchange: JsExchange) = synchronized(recordLock) {
        if (recorded.size >= MAX_RECORDED) recorded.removeFirst()
        recorded.addLast(exchange)
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private fun response(handle: Int): Captured = responses[handle]
        ?: throw IllegalStateException("Stale response handle $handle - responses do not outlive their call")

    private fun parseHeaderLines(lines: String): List<Pair<String, String>> {
        if (lines.isEmpty()) return emptyList()
        return lines.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
            }
            .toList()
    }

    private companion object {
        const val MAX_RECORDED = 100

        /** More than any real media link needs; a loop would otherwise hang. */
        const val MAX_REDIRECTS = 5
    }
}
