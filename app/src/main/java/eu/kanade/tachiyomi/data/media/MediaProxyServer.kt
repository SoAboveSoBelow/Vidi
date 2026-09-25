package eu.kanade.tachiyomi.data.media

import eu.kanade.tachiyomi.ui.player.PlayerMediaHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// AM (MEDIA_PROXY_SERVER) -->
/**
 * A loopback HTTP server that media requests are routed through, so the app owns
 * the byte stream instead of handing a remote URL straight to a consumer that
 * fetches it on its own.
 *
 * This exists because downloads and playback share no transport: ffmpeg opens its
 * own connections in native code and mpv demuxes its own, which leaves nothing in
 * between to rank them. ffmpeg's `-readrate` is the only lever available on that
 * side and it is fixed for the life of a session - a download that started while
 * nothing was playing keeps its uncapped rate no matter what happens afterwards.
 * With the fetch happening here, the rate is re-read per chunk, so a download
 * yields the moment playback starts and takes the bandwidth back the moment it
 * stops.
 *
 * Deliberately scoped to the transport only. Caching the bytes that pass through -
 * which is what makes watch-while-downloading a single fetch, and what temporary
 * media retention would be built on - belongs on top of this, not inside it.
 *
 * Fails open: if the server cannot start, [proxyUrlFor] returns null and the
 * caller uses the upstream URL directly, which is exactly today's behaviour.
 */
object MediaProxyServer {

    private data class Upstream(
        val url: String,
        val headers: Headers,
        val cacheKey: String?,
        // AM (THROTTLE_DOWNLOADS_ONLY) -->
        // Which consumer registered this. The throttle exists to rank a download
        // below playback, so it must only ever slow a download: once the player
        // started reading through this same server it was being throttled by its
        // own starvation signal - 64 KB/s to the thing that was starving, which is
        // the exact opposite of the intent.
        val throttled: Boolean,
        // <-- AM (THROTTLE_DOWNLOADS_ONLY)
    )

    private val upstreams = ConcurrentHashMap<String, Upstream>()

    // AM (RELEASE_PREVIOUS_REGISTRATION) -->
    /** owner -> its current registration key, so re-registering replaces rather than adds. */
    private val owners = ConcurrentHashMap<String, String>()
    // <-- AM (RELEASE_PREVIOUS_REGISTRATION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var client: OkHttpClient? = null
    private var baseClient: OkHttpClient? = null

    @Volatile
    private var mediaCache: MediaCache? = null

    /**
     * Registers [url] and returns a loopback URL serving it, or null if the server
     * could not be started. [release] the returned key when the consumer is done.
     */
    @Synchronized
    fun proxyUrlFor(
        url: String,
        headers: Headers,
        httpClient: OkHttpClient,
        // AM (MEDIA_CACHE) -->
        // Identifies the CONTENT, not this registration: two consumers of the same
        // episode at the same quality pass the same cacheKey and share bytes, even
        // though each gets its own loopback URL. Null opts out of caching.
        cacheKey: String? = null,
        // <-- AM (MEDIA_CACHE)
        // AM (THROTTLE_DOWNLOADS_ONLY) -->
        /** True for a download, false for playback. Only downloads are rate-limited. */
        throttled: Boolean = false,
        // <-- AM (THROTTLE_DOWNLOADS_ONLY)
        // AM (RELEASE_PREVIOUS_REGISTRATION) -->
        /**
         * Identifies the consumer, so its previous registration can be dropped.
         *
         * The player never releases its registrations by design - mpv reopens the
         * URL itself on seeks and reconnects, so the mapping has to outlive any
         * single request. But it registers afresh on every setVideo(): each
         * episode, each quality switch, each reload. Without an owner those
         * entries accumulated for the life of the process, every one of them a
         * live URL and header set kept alive for a file nobody will ask for again.
         */
        owner: String? = null,
        // <-- AM (RELEASE_PREVIOUS_REGISTRATION)
    ): Pair<String, String>? {
        val port = ensureStarted(httpClient) ?: return null
        val key = UUID.randomUUID().toString().replace("-", "")
        // AM (RELEASE_PREVIOUS_REGISTRATION) -->
        if (owner != null) {
            owners.put(owner, key)?.let { upstreams.remove(it) }
        }
        // <-- AM (RELEASE_PREVIOUS_REGISTRATION)
        upstreams[key] = Upstream(url, headers, cacheKey, throttled)
        return key to "http://127.0.0.1:$port/media/$key"
    }

    // AM (MEDIA_CACHE) -->
    fun attachCache(cache: MediaCache) {
        mediaCache = cache
    }

    /** The cache key for an episode at a given quality - see [proxyUrlFor]. */
    fun cacheKeyFor(episodeId: Long?, quality: String): String? {
        if (episodeId == null) return null
        val safeQuality = quality.filter { it.isLetterOrDigit() }.take(MAX_QUALITY_KEY_LENGTH)
        return "ep$episodeId-$safeQuality"
    }
    // <-- AM (MEDIA_CACHE)

    fun release(key: String) {
        upstreams.remove(key)
    }

    /**
     * The shared client with its call timeout removed.
     *
     * NetworkHelper sets callTimeout(2 minutes) - an absolute ceiling on the whole
     * call including the body, which is right for the API requests it was tuned for
     * and wrong for a media transfer that legitimately runs for tens of minutes.
     * With it in place OkHttp aborted the response mid-body at the two-minute mark,
     * this server closed the connection cleanly, and ffmpeg read that as a normal
     * end of file - producing a truncated download that reported success.
     *
     * readTimeout stays and is raised: a transfer should fail on inactivity, which
     * is what a dead connection actually looks like, not on elapsed time.
     */
    private fun untimedClient(httpClient: OkHttpClient): OkHttpClient {
        client?.takeIf { baseClient === httpClient }?.let { return it }
        baseClient = httpClient
        return httpClient.newBuilder()
            .callTimeout(Duration.ZERO)
            .readTimeout(UPSTREAM_READ_TIMEOUT)
            .build()
            .also { client = it }
    }

    @Synchronized
    private fun ensureStarted(httpClient: OkHttpClient): Int? {
        untimedClient(httpClient)
        serverSocket?.takeIf { !it.isClosed }?.let { return it.localPort }

        return try {
            val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            scope.launch { acceptLoop(socket) }
            logcat(LogPriority.INFO) { "MEDIA_PROXY_SERVER listening on ${socket.localPort}" }
            socket.localPort
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "MEDIA_PROXY_SERVER failed to start" }
            serverSocket = null
            null
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                if (socket.isClosed) return
                logcat(LogPriority.WARN, e) { "MEDIA_PROXY_SERVER accept failed" }
                continue
            }
            scope.launch { serve(connection) }
        }
    }

    private class TruncatedUpstreamException(written: Long, expected: Long) :
        IOException("Upstream ended after $written of $expected bytes")

    private fun serve(connection: Socket) {
        connection.use { socket ->
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return respondError(output, 400)
                val method = parts[0]
                val path = parts[1]

                var range: String? = null
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Range:", ignoreCase = true)) {
                        range = header.substringAfter(':').trim()
                    }
                }

                val key = path.substringAfterLast('/')
                val upstream = upstreams[key] ?: return respondError(output, 404)
                val httpClient = client ?: return respondError(output, 503)

                // AM (MEDIA_CACHE) -->
                val cache = mediaCache
                val entry = upstream.cacheKey?.let { cache?.entryFor(it) }
                if (entry != null) {
                    serveCached(method, upstream, range, httpClient, output, entry)
                } else {
                    forward(method, upstream, range, httpClient, output)
                }
                // <-- AM (MEDIA_CACHE)
            } catch (e: TruncatedUpstreamException) {
                runCatching { socket.setSoLinger(true, 0) }
                logcat(LogPriority.ERROR) { "MEDIA_PROXY_SERVER resetting connection: ${e.message}" }
            } catch (e: IOException) {
                // The consumer hung up (a seek closes the old connection, and
                // cancelling a download closes it mid-stream). Routine, not an error.
                logcat(LogPriority.VERBOSE) { "MEDIA_PROXY_SERVER connection closed: ${e.message}" }
            }
        }
    }

    // AM (MEDIA_CACHE) -->
    /**
     * Serves [entry]'s range, reading each chunk from whichever source has it.
     *
     * The source is re-chosen per chunk rather than once per request, and that is
     * the whole mechanism: a player running behind a download of the same episode
     * reads entirely from disk and never opens a socket, and the instant it catches
     * up with what has been fetched it opens its own upstream request and carries
     * on. Neither side waits on the other, so a stalled download cannot freeze
     * playback - it just means the player fetches those bytes itself.
     */
    private fun serveCached(
        method: String,
        upstream: Upstream,
        range: String?,
        httpClient: OkHttpClient,
        output: OutputStream,
        entry: MediaCache.Entry,
    ) {
        val start = parseRangeStart(range)
        val total = entry.totalLength ?: probeTotalLength(upstream, httpClient, entry)
        if (total == null) {
            // Without a length there is no way to frame a correct response, so fall
            // back to a straight pass-through and leave the cache out of it.
            forward(method, upstream, range, httpClient, output)
            return
        }

        val end = parseRangeEnd(range) ?: (total - 1)
        if (start >= total || end < start) {
            respondError(output, 416)
            return
        }

        val length = end - start + 1
        output.write(
            buildString {
                if (range != null) {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Range: bytes ").append(start).append("-").append(end)
                        .append("/").append(total).append("\r\n")
                } else {
                    append("HTTP/1.1 200 OK\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(),
        )

        if (method.equals("HEAD", ignoreCase = true)) {
            output.flush()
            return
        }

        entry.acquire()
        try {
            var position = start
            val buffer = ByteArray(CHUNK_BYTES)
            var upstreamBody: InputStream? = null
            var upstreamPosition = -1L

            try {
                while (position <= end) {
                    val cached = entry.availableAt(position)
                    if (cached > 0) {
                        // Someone else already fetched this. Drop any socket we were
                        // holding - it is pointed at bytes we no longer need.
                        upstreamBody?.close()
                        upstreamBody = null
                        val want = minOf(cached, (end - position + 1), CHUNK_BYTES.toLong()).toInt()
                        val read = entry.read(position, buffer, want)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        position += read
                        continue
                    }

                    // AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS) -->
                    // One fetcher per region, and playback is the one that leads.
                    //
                    // Throttling was the wrong shape for this: ranking a download
                    // below playback still had both of them pulling the same bytes
                    // over one connection, just at different speeds. While playback
                    // is fetching from the network for this entry, the download
                    // reads nothing from the network at all - it takes what is
                    // already on disk and waits for the rest, following behind at
                    // whatever pace playback sets.
                    //
                    // No timeout, and none needed. A wait here is never a wait on
                    // something that might not happen: whatever playback leaves
                    // unfetched - a region it skipped, a gap it would only revisit
                    // if the user seeks back - stops being anyone's guess the
                    // moment playback stops. The download takes the entry then and
                    // fills the holes. The work is deferred, not dropped, so there
                    // is nothing for a clock to rescue.
                    //
                    // What made earlier versions need an escape hatch was trying to
                    // decide, while playback was still running, whether it would
                    // ever produce a given byte. That question has no answer -
                    // it depends on what the user does next - and both attempts at
                    // it (a position comparison, then a timer) were wrong in the
                    // cases they could not see. Waiting for playback to finish
                    // answers it by not asking.
                    //
                    // Liveness comes from FOLLOW_WINDOW alone: a session that ends,
                    // crashes or pauses stops registering fetches, and the download
                    // proceeds within that window regardless of how it ended.
                    if (upstream.throttled && entry.playbackIsFetching(FOLLOW_WINDOW_NANOS)) {
                        upstreamBody?.close()
                        upstreamBody = null
                        Thread.sleep(FOLLOW_POLL_MS)
                        continue
                    }
                    // <-- AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS)

                    if (upstreamBody == null || upstreamPosition != position) {
                        upstreamBody?.close()
                        upstreamBody = openUpstream(upstream, httpClient, position, end)
                        upstreamPosition = position
                    }

                    val read = upstreamBody?.read(buffer) ?: -1
                    if (read == -1) break
                    if (upstream.throttled) Throttle.acquire(read)
                    Throttle.record(read)
                    // AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS) -->
                    // Only playback's own fetches move the marker a download
                    // follows; a download's reads must not make it look like the
                    // leader and stall the real one.
                    if (!upstream.throttled) entry.notePlayerFetch(position + read)
                    // <-- AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS)
                    entry.write(position, buffer, read)
                    output.write(buffer, 0, read)
                    position += read
                    upstreamPosition = position
                }
            } finally {
                upstreamBody?.close()
            }

            output.flush()
            if (position <= end) {
                throw TruncatedUpstreamException(position - start, length)
            }
        } finally {
            entry.release()
            // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
            // Write the range map out now the transfer is over, so the bytes just
            // fetched are usable by a later process rather than being an
            // unreadable file with no record of which parts are real.
            mediaCache?.persist()
            // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
        }
    }

    private fun openUpstream(
        upstream: Upstream,
        httpClient: OkHttpClient,
        from: Long,
        to: Long,
    ): InputStream {
        val request = Request.Builder()
            .url(upstream.url)
            .headers(upstream.headers)
            .header("Range", "bytes=$from-$to")
            .build()
        val response = httpClient.newCall(request).execute()

        // AM (TRUST_ONLY_HONOURED_RANGES) -->
        // A range request is asked for, not guaranteed. A server that ignores the
        // header answers 200 with the body from byte zero, and this code then wrote
        // those bytes at `from` - filling the cache with data that is wrong, marked
        // as present, and indistinguishable from real bytes on the next read. Worse
        // than a failed fetch, because a failure retries and corruption plays.
        //
        // 206 means the range was honoured. A 200 for a request that started at
        // zero is the same bytes either way and fine. Anything else is refused
        // here rather than trusted.
        val honoured = response.code == HTTP_PARTIAL_CONTENT || (response.code == HTTP_OK && from == 0L)
        if (!honoured) {
            response.close()
            throw IOException(
                "Upstream ignored the range request: wanted bytes=$from-$to, got ${response.code}",
            )
        }
        // <-- AM (TRUST_ONLY_HONOURED_RANGES)

        return response.body.byteStream()
    }

    /**
     * Learns the resource's total size from a one-byte range request, which is what
     * makes a correct Content-Range possible for everything served afterwards.
     */
    private fun probeTotalLength(
        upstream: Upstream,
        httpClient: OkHttpClient,
        entry: MediaCache.Entry,
    ): Long? {
        val request = Request.Builder()
            .url(upstream.url)
            .headers(upstream.headers)
            .header("Range", "bytes=0-0")
            .build()
        val total = runCatching {
            httpClient.newCall(request).execute().use { response ->
                response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
            }
        }.getOrNull()
        entry.totalLength = total
        return total
    }

    private fun parseRangeStart(range: String?): Long {
        val spec = range?.substringAfter("bytes=", "") ?: return 0L
        return spec.substringBefore('-').trim().toLongOrNull() ?: 0L
    }

    private fun parseRangeEnd(range: String?): Long? {
        val spec = range?.substringAfter("bytes=", "") ?: return null
        return spec.substringAfter('-').trim().toLongOrNull()
    }
    // <-- AM (MEDIA_CACHE)

    private fun forward(
        method: String,
        upstream: Upstream,
        range: String?,
        httpClient: OkHttpClient,
        output: OutputStream,
    ) {
        val requestBuilder = Request.Builder()
            .url(upstream.url)
            .headers(upstream.headers)
        if (range != null) {
            requestBuilder.header("Range", range)
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body
            val expectedBytes = response.header("Content-Length")?.toLongOrNull()

            val responseHeaders = buildString {
                append("HTTP/1.1 ").append(response.code).append(" ").append(statusText(response.code))
                    .append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                response.header("Content-Type")?.let { append("Content-Type: ").append(it).append("\r\n") }
                response.header("Content-Length")?.let { append("Content-Length: ").append(it).append("\r\n") }
                response.header("Content-Range")?.let { append("Content-Range: ").append(it).append("\r\n") }
                // One response per connection: a consumer that seeks opens a new
                // one anyway, and this removes any chance of desyncing the stream
                // by mismanaging keep-alive framing.
                append("Connection: close\r\n\r\n")
            }
            output.write(responseHeaders.toByteArray())

            if (method.equals("HEAD", ignoreCase = true)) {
                output.flush()
                return
            }

            var written = 0L
            val buffer = ByteArray(CHUNK_BYTES)
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    if (upstream.throttled) Throttle.acquire(read)
                    Throttle.record(read)
                    output.write(buffer, 0, read)
                    written += read
                }
            }
            output.flush()

            // A body that ends early must not look like a clean end of file. The
            // headers are long gone by now, so the status cannot be retracted - but
            // closing with SO_LINGER 0 sends a reset instead of a FIN, which ffmpeg
            // reports as a failed read rather than silently remuxing a short file
            // into a download that claims to be complete.
            if (expectedBytes != null && written < expectedBytes) {
                throw TruncatedUpstreamException(written, expectedBytes)
            }
        }
    }

    private fun respondError(output: OutputStream, code: Int) {
        try {
            output.write(
                "HTTP/1.1 $code ${statusText(code)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            output.flush()
        } catch (_: IOException) {
            // Nothing useful to do if even the error response can't be written.
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        503 -> "Service Unavailable"
        else -> "Status $code"
    }

    /** Reads one CRLF-terminated line without buffering past it into the body. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(byte.toChar())
            if (line.length > MAX_REQUEST_LINE) return null
        }
    }

    /**
     * Rate limiter shared by every connection this server is serving.
     *
     * The limit is re-read on every chunk rather than fixed when a transfer starts,
     * which is the entire point of owning the fetch: the cap appears when playback
     * starts and disappears when it stops, part-way through a download.
     *
     * An absolute byte rate, not a multiple of the media's own bitrate: the thing
     * being protected is the connection, and a high-bitrate file would otherwise be
     * allowed to draw proportionally more of it.
     */
    /**
     * Shares measured bandwidth between downloads and playback.
     *
     * The earlier version handed downloads a fixed 1.5 MB/s while anything was
     * streaming, and 64 KB/s while it was starving. Both numbers were guesses, and
     * a fixed number is wrong in both directions at once: it throttles a download
     * pointlessly on a fast link where playback has bandwidth to spare, and still
     * lets it take too much on a slow one where playback is drowning.
     *
     * What it allocates from now is what the connection has actually been
     * delivering - every byte through this server over the last ten seconds,
     * whichever consumer pulled it - and what decides the share is how full the
     * player's own read-ahead buffer is. A stream the link comfortably outruns
     * (watching 720p on a connection good for 4K) keeps its buffer pegged full, so
     * the download is not capped at all; that is the independence a download
     * deserves when nothing is competing. A buffer that is falling behind hands the
     * download a proportionally smaller slice, and one that has run dry leaves it
     * only a trickle.
     */
    private object Throttle {
        private val lock = Any()
        private var tokens = 0.0
        private var lastRefillNanos = System.nanoTime()

        /** Rolling record of bytes served, as (nanoTime, bytes) buckets. */
        private val samples = ArrayDeque<Pair<Long, Long>>()

        /** Called for every byte this server fetches, throttled or not. */
        fun record(bytes: Int) {
            val now = System.nanoTime()
            synchronized(samples) {
                samples.addLast(now to bytes.toLong())
                val cutoff = now - MEASURE_WINDOW_NANOS
                while (samples.isNotEmpty() && samples.first().first < cutoff) {
                    samples.removeFirst()
                }
            }
        }

        /** Measured throughput over the window, in bytes per second. */
        private fun measuredBytesPerSecond(): Long {
            val now = System.nanoTime()
            val cutoff = now - MEASURE_WINDOW_NANOS
            val total = synchronized(samples) {
                samples.filter { it.first >= cutoff }.sumOf { it.second }
            }
            return (total / MEASURE_WINDOW_SECONDS).toLong()
        }

        /**
         * The download's allowance right now, or null for uncapped.
         *
         * Uncapped whenever playback is not streaming, or its buffer is at or near
         * target - the cases where a download costs playback nothing.
         */
        private fun limitBytesPerSecond(): Long? {
            if (PlayerMediaHolder.playbackStarved.value) return STARVED_FLOOR_BYTES_PER_SECOND
            val fill = PlayerMediaHolder.playbackBufferFill.value ?: return null
            if (fill >= HEALTHY_BUFFER_FILL) return null

            // Linear from the floor share at empty to the full measured rate at a
            // healthy buffer. Deliberately simple: the input is noisy and a
            // cleverer curve would be false precision.
            val share = MIN_DOWNLOAD_SHARE +
                (1f - MIN_DOWNLOAD_SHARE) * (fill / HEALTHY_BUFFER_FILL)
            val measured = measuredBytesPerSecond()
            if (measured <= 0L) return STARVED_FLOOR_BYTES_PER_SECOND
            return (measured * share).toLong().coerceAtLeast(STARVED_FLOOR_BYTES_PER_SECOND)
        }

        fun acquire(bytes: Int) {
            while (true) {
                val limit = limitBytesPerSecond() ?: return
                synchronized(lock) {
                    val now = System.nanoTime()
                    val elapsedSeconds = (now - lastRefillNanos) / NANOS_PER_SECOND
                    lastRefillNanos = now
                    val capacity = maxOf(limit.toDouble(), bytes.toDouble())
                    tokens = minOf(capacity, tokens + elapsedSeconds * limit)
                    if (tokens >= bytes) {
                        tokens -= bytes
                        return
                    }
                }
                Thread.sleep(THROTTLE_WAIT_MS)
            }
        }
    }

    private const val BACKLOG = 8
    private const val CHUNK_BYTES = 32 * 1024
    private const val SOCKET_TIMEOUT_MS = 30_000
    private val UPSTREAM_READ_TIMEOUT: Duration = Duration.ofSeconds(60)
    private const val MAX_REQUEST_LINE = 8 * 1024
    private const val THROTTLE_WAIT_MS = 20L
    private const val MAX_QUALITY_KEY_LENGTH = 24
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    // AM (MEASURED_BANDWIDTH_SHARE) -->
    /** Buffer fill at or above which a download is not capped at all. */
    private const val HEALTHY_BUFFER_FILL = 0.8f

    /** Share of measured throughput a download keeps with an empty buffer. */
    private const val MIN_DOWNLOAD_SHARE = 0.05f

    /** Absolute floor, so a throttled download still progresses and does not time out. */
    private const val STARVED_FLOOR_BYTES_PER_SECOND = 64_000L

    // AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS) -->
    /** How recently playback must have fetched to still count as leading. */
    private val FOLLOW_WINDOW_NANOS = 3L * 1_000_000_000L

    private const val FOLLOW_POLL_MS = 100L
    // <-- AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS)

    // AM (TRUST_ONLY_HONOURED_RANGES) -->
    private const val HTTP_OK = 200
    private const val HTTP_PARTIAL_CONTENT = 206
    // <-- AM (TRUST_ONLY_HONOURED_RANGES)

    private const val MEASURE_WINDOW_SECONDS = 10.0
    private const val MEASURE_WINDOW_NANOS = (MEASURE_WINDOW_SECONDS * 1_000_000_000L).toLong()
    // <-- AM (MEASURED_BANDWIDTH_SHARE)
}
// <-- AM (MEDIA_PROXY_SERVER)
