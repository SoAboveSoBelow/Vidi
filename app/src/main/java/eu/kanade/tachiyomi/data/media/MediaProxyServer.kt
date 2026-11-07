package eu.kanade.tachiyomi.data.media

import eu.kanade.tachiyomi.ui.player.PlayerMediaHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        // AM (PRELOAD_PLAYLISTS): something is being played now, so nothing
        // speculative should still be holding a connection.
        if (!throttled) cancelWarms()
        // AM (PROXY_RELATIVE_PATHS) -->
        // The URL keeps the upstream's own filename as its last path segment, so a
        // consumer resolving a relative reference from it lands on
        // /media/<key>/<that reference> - see serve().
        return key to "http://127.0.0.1:$port/media/$key/${url.fileName()}"
        // <-- AM (PROXY_RELATIVE_PATHS)
    }

    // AM (PROXY_RELATIVE_PATHS) -->
    /** The last path segment of a URL, with any query and fragment dropped. */
    private fun String.fileName(): String =
        substringBefore('?').substringBefore('#').substringAfterLast('/').ifEmpty { "index" }

    /**
     * This upstream's neighbour at [relative], or null if it does not resolve.
     *
     * Inherits the headers (a CDN that needs a Referer for the manifest needs it
     * for the segments too) and the throttle, and gets its own cache entry keyed
     * off the parent's - each segment is a contiguous resource, so everything the
     * cache does for a whole file works for one segment unchanged.
     */
    private fun Upstream.resolveSibling(relative: String): Upstream? {
        val absolute = url.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: return null
        return copy(
            url = absolute,
            cacheKey = cacheKey?.let { "$it-s${relative.hashCode().toUInt()}" },
        )
    }
    // <-- AM (PROXY_RELATIVE_PATHS)

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

    /** Drops a consumer's live mapping only after its player can no longer reopen it. */
    @Synchronized
    fun releaseOwner(owner: String) {
        owners.remove(owner)?.let { upstreams.remove(it) }
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

                // AM (PROXY_RELATIVE_PATHS) -->
                // The path is /media/<key>/<relative path>, and everything after
                // the key is resolved against the upstream URL rather than looked
                // up. A manifest (DASH .mpd, HLS .m3u8) is a text index whose
                // entries are RELATIVE, and a consumer resolves them against the
                // URL it fetched the manifest from - which is this server. Served
                // under a bare key those became paths with no registration, so
                // every segment 404'd and playback sat there loading forever
                // ("dash: Failed to open an initialization section").
                //
                // Resolving instead of parsing is what makes this work for both
                // formats and needs no manifest support at all: DASH
                // SegmentTemplate URLs are generated at playback time from
                // $Number$/$Time$ placeholders and cannot be enumerated up front,
                // so any scheme that rewrites a manifest's contents can only ever
                // cover the formats it knows how to parse. This covers whatever
                // the consumer asks for.
                val trimmed = path.trimStart('/').removePrefix("media/")
                val key = trimmed.substringBefore('/')
                val relative = trimmed.substringAfter('/', "")
                val parent = upstreams[key] ?: return respondError(output, 404)
                val httpClient = client ?: return respondError(output, 503)

                // The manifest itself is served under its own name so relative
                // resolution has a sibling to work from; anything else is a
                // resource beside it.
                val upstream = if (relative.isEmpty() || relative == parent.url.fileName()) {
                    parent
                } else {
                    parent.resolveSibling(relative) ?: return respondError(output, 404)
                }
                // <-- AM (PROXY_RELATIVE_PATHS)

                // AM (MEDIA_CACHE) -->
                val cache = mediaCache
                // AM (HLS_SERVE_ONE_VARIANT) -->
                // A playlist is a text index, not media. serveCached() frames a
                // byte range of a known total length and never passes through
                // forward(), so a cached manifest would be served unfiltered -
                // and sharing the bytes of a text file between a player and a
                // download buys nothing in the first place.
                val entry = upstream.cacheKey
                    ?.takeUnless { looksLikePlaylist(upstream.url, null) }
                    ?.let { cache?.entryFor(it) }
                // <-- AM (HLS_SERVE_ONE_VARIANT)
                // AM (PRELOAD_PLAYLISTS) -->
                // Playback leads: a warm still filling this entry stands down so
                // the two are never pulling the same bytes at once.
                upstream.cacheKey?.let { warmingKeys.remove(it) }
                val cachedPlaylist = if (range == null || range.trim().equals("bytes=0-", true)) {
                    cachedPlaylistFor(upstream.url)
                } else {
                    null
                }
                if (cachedPlaylist != null) {
                    logcat(LogPriority.INFO) { "PRELOAD_PLAYLISTS served from memory" }
                    respondPlaylist(method, range, cachedPlaylist, output)
                    // <-- AM (PRELOAD_PLAYLISTS)
                } else if (entry != null) {
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
                    // AM (EVICT_ON_WRITE): outside entry.write's own lock on
                    // purpose - the check takes the cache lock and reads every
                    // entry's byte count, so calling it while holding an entry
                    // would invert the two locks against the cache's own sweeps.
                    mediaCache?.noteBytesWritten(read)
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

            // AM (HLS_SERVE_ONE_VARIANT) -->
            // A Master Playlist is rewritten to the single variant that will be
            // played. Everything else passes through untouched.
            // mpv's stream callback opens every url with "Range: bytes=0-",
            // which asks for the whole body and gets a 206 back. An earlier
            // version required no Range header at all and so never examined a
            // single playlist - confirmed on-device, every one logged
            // "range=bytes=0- code=206" on its way past. A rangeless request
            // and a request for byte zero onwards want the same bytes, and a
            // partial range of a text index is not something to rewrite.
            val wantsWholeBody = range == null || range.trim().equals("bytes=0-", ignoreCase = true)
            val eligible = wantsWholeBody &&
                response.isSuccessful &&
                !method.equals("HEAD", ignoreCase = true) &&
                looksLikePlaylist(upstream.url, response.header("Content-Type"))
            if (!eligible && looksLikePlaylist(upstream.url, response.header("Content-Type"))) {
                // The only way a playlist is served without being examined, so
                // it says which of the three conditions kept it out.
                logcat(LogPriority.INFO) {
                    "HLS_SERVE_ONE_VARIANT playlist passed through: " +
                        "method=$method range=$range code=${response.code}"
                }
            }
            val playlistBody = if (eligible) {
                val text = body.string()
                val served = narrowMasterPlaylist(text) ?: text
                // AM (PRELOAD_PLAYLISTS): keep what was served, not what was
                // fetched, so a replay is handed the same narrowed master.
                val bytes = served.toByteArray()
                storePlaylist(upstream.url, served, bytes, response.header("Content-Type"))
                bytes
            } else {
                null
            }
            // <-- AM (HLS_SERVE_ONE_VARIANT)

            val responseHeaders = buildString {
                append("HTTP/1.1 ").append(response.code).append(" ").append(statusText(response.code))
                    .append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                response.header("Content-Type")?.let { append("Content-Type: ").append(it).append("\r\n") }
                // AM (HLS_SERVE_ONE_VARIANT) -->
                // Both length headers describe the playlist that was FETCHED.
                // A rewritten one is shorter, and a 206 whose Content-Range
                // promises more bytes than follow leaves the reader waiting on
                // a body that already ended.
                if (playlistBody != null) {
                    val last = (playlistBody.size - 1).coerceAtLeast(0)
                    append("Content-Length: ").append(playlistBody.size).append("\r\n")
                    if (response.code == 206) {
                        append("Content-Range: bytes 0-").append(last)
                            .append("/").append(playlistBody.size).append("\r\n")
                    }
                } else {
                    response.header("Content-Length")?.let { append("Content-Length: ").append(it).append("\r\n") }
                    response.header("Content-Range")?.let { append("Content-Range: ").append(it).append("\r\n") }
                }
                // <-- AM (HLS_SERVE_ONE_VARIANT)
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

            // AM (HLS_SERVE_ONE_VARIANT) -->
            // Already read in full above; there is no stream left to loop over,
            // and a text index is never large enough to want one. The truncation
            // check below does not apply either - the length served is this
            // body's own, not the upstream's.
            if (playlistBody != null) {
                Throttle.record(playlistBody.size)
                output.write(playlistBody)
                output.flush()
                return
            }
            // <-- AM (HLS_SERVE_ONE_VARIANT)

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

    // AM (PRELOAD_PLAYLISTS) -->
    private data class CachedPlaylist(val body: ByteArray, val contentType: String?)

    /**
     * Manifests already fetched this session, keyed by UPSTREAM url.
     *
     * Keyed that way on purpose: [proxyUrlFor] mints a fresh UUID on every
     * registration, so the same episode played twice arrives under two loopback
     * urls and anything keyed by those would never hit. The upstream url is what
     * is actually stable across a re-registration, a quality switch and a
     * switch back to an episode played a minute ago.
     *
     * Only immutable manifests are kept - a master, or a VOD media playlist that
     * has reached EXT-X-ENDLIST. A live playlist is a moving window and serving
     * a remembered copy of one would pin playback to a stale segment list.
     *
     * Memory, not disk, and tiny: a master is a few hundred bytes and a
     * media playlist is one EXTINF plus one uri per segment, so a 24-minute
     * episode is around 17 KB. [PLAYLIST_CACHE_ENTRIES] of them is well under a
     * megabyte, which is why this needs no interaction with MediaCache's ceiling
     * and no eviction policy beyond least-recently-used.
     */
    private val playlistCache = object : LinkedHashMap<String, CachedPlaylist>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPlaylist>): Boolean =
            size > PLAYLIST_CACHE_ENTRIES
    }

    /**
     * Cache keys a speculative warm is currently filling.
     *
     * Removed by [serve] the moment a real request arrives for that entry, which
     * is how the warm learns to stand down - see [streamSegmentInto].
     */
    private val warmingKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A client for speculative fetches only, sharing the caller's connection
     * pool but with a dispatcher of its own.
     *
     * OkHttp's dispatcher limits concurrent requests PER HOST, and a warm pulls
     * 20MB segments from the same CDN host playback uses. Sharing the player's
     * client let two of those occupy the host's slots while a transition's own
     * request queued behind them - confirmed on-device (2026-11-06): manifests
     * served from memory at 20:26:14, playback not starting until 20:26:19, and
     * a 21MB warm completing at 20:26:44. One request at a time here means a
     * warm can never be the reason playback is waiting for a connection.
     */
    @Volatile
    private var warmClient: OkHttpClient? = null

    private fun warmClientFrom(httpClient: OkHttpClient): OkHttpClient {
        warmClient?.let { return it }
        return synchronized(this) {
            warmClient ?: httpClient.newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = 1
                        maxRequestsPerHost = 1
                    },
                )
                .build()
                .also { warmClient = it }
        }
    }

    /**
     * Stops every warm in flight.
     *
     * Called when a new stream is registered, which means the user started
     * something: whatever was being speculatively fetched is now, at best, not
     * the thing in front of them. Per-entry standing down (see [serve]) only
     * covers a warm for the very episode being opened; this covers the far more
     * common case of having moved somewhere else entirely.
     */
    private fun cancelWarms() {
        if (warmingKeys.isNotEmpty()) {
            logcat(LogPriority.INFO) { "PRELOAD_PLAYLISTS cancelling ${warmingKeys.size} warm(s)" }
            warmingKeys.clear()
        }
    }

    private fun cachedPlaylistFor(url: String): CachedPlaylist? =
        synchronized(playlistCache) { playlistCache[url] }

    private fun storePlaylist(url: String, text: String, body: ByteArray, contentType: String?) {
        // A master is fixed for the life of the content; a media playlist is only
        // final once it says so.
        val immutable = text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-ENDLIST")
        if (!immutable) return
        synchronized(playlistCache) { playlistCache[url] = CachedPlaylist(body, contentType) }
    }

    /**
     * Fetches an HLS entry's manifests ahead of playback, so the loadfile finds
     * them already here.
     *
     * This is the half of preloading that a url resolve does not do. Resolving
     * the next episode's video url ahead of time removes the hoster round trips;
     * it leaves mpv to fetch the master, then the variant playlist, then the
     * first segments, at the moment the episode starts - measured on-device at
     * around half a second with everything else warm, and far worse cold. Doing
     * the two manifest fetches here moves that off the transition entirely, at a
     * cost of kilobytes.
     *
     * The master is narrowed before it is stored, so the copy mpv is handed has
     * one variant and the other renditions are never probed. The chosen
     * variant's own playlist is then fetched and stored too, because that is the
     * second round trip and it is just as fixed.
     *
     * Blocking: call it from an IO context. Failures are silent by design - a
     * warm that did not happen just means the normal path fetches as it always
     * did.
     */
    fun warmPlaylists(url: String, headers: Headers, httpClient: OkHttpClient, cacheKey: String? = null) {
        if (!looksLikePlaylist(url, null)) return
        val (masterText, masterType) = fetchPlaylist(url, headers, httpClient) ?: return
        val narrowed = narrowMasterPlaylist(masterText)
        val served = narrowed ?: masterText
        storePlaylist(url, served, served.toByteArray(), masterType)

        // Only a narrowed master names a single variant to follow.
        val variant = narrowed
            ?.lines()
            ?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: return
        val variantUrl = url.toHttpUrlOrNull()?.resolve(variant)?.toString() ?: return
        val (variantText, variantType) = fetchPlaylist(variantUrl, headers, httpClient) ?: return
        storePlaylist(variantUrl, variantText, variantText.toByteArray(), variantType)
        logcat(LogPriority.INFO) { "PRELOAD_PLAYLISTS warmed master and variant for $variant" }
        warmSegments(url, variantUrl, variantText, headers, httpClient, cacheKey)
    }

    /**
     * Fetches the opening segments into [MediaCache], under the exact keys this
     * server will look them up by.
     *
     * The manifests alone are not the preload. Measured on-device (2026-11-06):
     * with both manifests served from memory and the segments already on disk a
     * transition reached "Starting playback" in 85-97ms; with the same manifests
     * warm and the segments cold it took 2.0-2.5s, because ffmpeg reads two
     * segments before it starts and each one is about a second on a phone
     * connection. Media3's playlist preloading buffers the next item's samples
     * for the same reason, and this app's own scroller holds cards open at
     * CacheTier.Near rather than merely knowing their urls.
     *
     * [WARM_SEGMENT_COUNT] is two because two is what the demuxer waits for.
     * Beyond that it is bytes spent on an episode the user may never reach.
     *
     * The key has to match what [serve] computes for the same request, which is
     * [resolveSibling]'s derivation over the path mpv asks for - hence
     * relativising the absolute segment url against the MASTER, the registered
     * upstream. A segment that cannot be expressed that way, or whose url
     * carries a signed query, is simply not warmed: the key would not match, the
     * normal path fetches it, and nothing breaks.
     */
    private fun warmSegments(
        masterUrl: String,
        variantUrl: String,
        variantText: String,
        headers: Headers,
        httpClient: OkHttpClient,
        cacheKey: String?,
    ) {
        if (cacheKey == null) return
        val cache = mediaCache ?: return
        val segments = variantText.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(WARM_SEGMENT_COUNT)
        for (uri in segments) {
            val absolute = variantUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: continue
            val relative = relativeToSiblingOf(masterUrl, absolute) ?: continue
            val key = "$cacheKey-s${relative.hashCode().toUInt()}"
            val entry = cache.entryFor(key)
            val known = entry.totalLength
            if (known != null && entry.availableAt(0) >= known) continue
            // The active video comes first. Throttling alone was not enough: it
            // slowed a warm that was already competing, where the right answer
            // for a purely speculative fetch is not to start until what the
            // viewer is actually watching is comfortably ahead.
            if (!awaitHealthyBuffer()) {
                logcat(LogPriority.INFO) { "PRELOAD_PLAYLISTS buffer never settled - skipping segment warm" }
                break
            }
            warmingKeys.add(key)
            try {
                val written = streamSegmentInto(entry, key, absolute, headers, httpClient)
                logcat(LogPriority.INFO) { "PRELOAD_PLAYLISTS warmed segment $relative ($written bytes)" }
            } finally {
                warmingKeys.remove(key)
            }
            // A warm that was abandoned, because playback arrived or the fetch
            // failed, has no business starting the next segment.
            val complete = entry.totalLength?.let { entry.availableAt(0) >= it } == true
            if (!complete) break
        }
    }

    /**
     * Fills [entry] from the network as the bytes arrive, and stops the moment
     * playback wants the same entry.
     *
     * Both halves matter. Buffering the whole segment and writing it at the end
     * left the entry empty for the seconds the fetch took, so a transition into
     * that episode mid-warm found nothing cached and fetched the same 20MB
     * again alongside this one - confirmed on-device (2026-11-06), a transition
     * that otherwise took under 100ms took 1.4s because it collided with the
     * warm of the very segment it needed. Writing incrementally means a reader
     * arriving late consumes what has already landed.
     *
     * Abandoning on contact is the rule PLAYER_LEADS_DOWNLOAD_FOLLOWS already
     * sets out: one fetcher per region, and playback is the one that leads. A
     * warm is the most speculative fetcher there is, so it yields rather than
     * competing for the same bytes on the same connection.
     */
    private fun streamSegmentInto(
        entry: MediaCache.Entry,
        key: String,
        url: String,
        headers: Headers,
        httpClient: OkHttpClient,
    ): Long = runCatching {
        val client = warmClientFrom(httpClient)
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
            if (!response.isSuccessful) return@use 0L
            val length = response.header("Content-Length")?.toLongOrNull()
            if (length != null && length > WARM_SEGMENT_MAX_BYTES) return@use 0L
            if (length != null) entry.totalLength = length
            var offset = 0L
            val buffer = ByteArray(CHUNK_BYTES)
            response.body.byteStream().use { stream ->
                while (true) {
                    if (key !in warmingKeys) return@use offset
                    // Starving mid-warm means the active video lost the race for
                    // bandwidth. Stop outright rather than carry on at the
                    // throttle's floor; what has landed stays cached.
                    if (PlayerMediaHolder.playbackStarved.value) return@use offset
                    val read = stream.read(buffer)
                    if (read == -1) break
                    // Yields bandwidth the same way a download does - a warm is
                    // the lowest-priority fetcher in the system, and the rate
                    // limiter already ranks it below a starving player.
                    Throttle.acquire(read)
                    Throttle.record(read)
                    entry.write(offset, buffer, read)
                    offset += read
                    if (offset > WARM_SEGMENT_MAX_BYTES) return@use offset
                }
            }
            if (length == null) entry.totalLength = offset
            offset
        }
    }.getOrDefault(0L)

    /**
     * Blocks until the active session's buffer is comfortably ahead, or gives
     * up.
     *
     * Returns true when there is nothing streaming to protect, or its fill has
     * reached [HEALTHY_BUFFER_FILL] - the same threshold the rate limiter treats
     * as "no longer needs the bandwidth". Returns false if the buffer has not
     * settled within [WARM_BUFFER_WAIT_MS], which is the honest outcome on a
     * connection that cannot sustain one stream, let alone two: the warm is
     * abandoned and the next prefetch cycle tries again.
     */
    private fun awaitHealthyBuffer(): Boolean {
        val deadline = System.nanoTime() + WARM_BUFFER_WAIT_MS * 1_000_000L
        while (true) {
            if (!PlayerMediaHolder.streamingPlaybackActive.value) return true
            if (!PlayerMediaHolder.playbackStarved.value) {
                val fill = PlayerMediaHolder.playbackBufferFill.value
                if (fill == null || fill >= HEALTHY_BUFFER_FILL) return true
            }
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(WARM_BUFFER_POLL_MS)
        }
    }

    /** [absolute] expressed the way a consumer would ask this server for it. */
    private fun relativeToSiblingOf(parentUrl: String, absolute: String): String? {
        val parent = parentUrl.toHttpUrlOrNull() ?: return null
        val target = absolute.toHttpUrlOrNull() ?: return null
        if (parent.scheme != target.scheme || parent.host != target.host || parent.port != target.port) {
            return null
        }
        // A signed or otherwise parameterised segment url is left alone - the
        // request path this server sees would not carry the same query.
        if (absolute.contains('?')) return null
        val base = parentUrl.substringBefore('?').substringBeforeLast('/') + "/"
        if (!absolute.startsWith(base)) return null
        return absolute.removePrefix(base).takeIf { it.isNotEmpty() }
    }

    private fun fetchPlaylist(
        url: String,
        headers: Headers,
        httpClient: OkHttpClient,
    ): Pair<String, String?>? = runCatching {
        httpClient.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val type = response.header("Content-Type")
            val text = response.body.string()
            if (text.length > PLAYLIST_MAX_CHARS) null else text to type
        }
    }.getOrNull()

    /** Writes a manifest we already hold, framed to match what was asked for. */
    private fun respondPlaylist(
        method: String,
        range: String?,
        cached: CachedPlaylist,
        output: OutputStream,
    ) {
        val body = cached.body
        val last = (body.size - 1).coerceAtLeast(0)
        val partial = range != null
        output.write(
            buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Accept-Ranges: bytes\r\n")
                cached.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                append("Content-Length: ").append(body.size).append("\r\n")
                if (partial) {
                    append("Content-Range: bytes 0-").append(last).append("/").append(body.size).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }.toByteArray(),
        )
        if (!method.equals("HEAD", ignoreCase = true)) {
            Throttle.record(body.size)
            output.write(body)
        }
        output.flush()
    }
    // <-- AM (PRELOAD_PLAYLISTS)

    // AM (HLS_SERVE_ONE_VARIANT) -->
    /**
     * Whether this response is worth reading as text before serving it.
     *
     * Cheap and wrong-side-safe: a false positive costs one buffered text file,
     * and [narrowMasterPlaylist] then returns null and the bytes are served
     * unchanged. Nothing that is not announced as a playlist is ever buffered,
     * so media bodies keep streaming as they do now.
     */
    private fun looksLikePlaylist(url: String, contentType: String?): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            contentType?.contains("mpegurl", ignoreCase = true) == true

    /**
     * Rewrites an HLS Master Playlist down to its highest-bandwidth variant, or
     * returns null when [text] is not one.
     *
     * ffmpeg's HLS demuxer reads a master by opening EVERY variant's media
     * playlist and the first segments of each, then plays one and discards the
     * rest. Measured on-device (2026-11-06): a four-rendition master spent 11.7s
     * between the first open and "Starting playback", twelve fetches of which
     * eleven were thrown away. Serving one variant leaves it nothing to probe.
     *
     * This is the narrow exception to PROXY_RELATIVE_PATHS' "resolve, don't
     * parse" rule, and it does not weaken it. That rule exists because DASH
     * SegmentTemplate urls are generated at playback time and cannot be
     * enumerated, so a rewriting proxy could never be general. Nothing here
     * enumerates or rewrites a url: the variant lines are the playlist's own
     * text, kept verbatim, and every path in the result still resolves against
     * this server exactly as before.
     *
     * Dropped: the other EXT-X-STREAM-INF entries and their uri lines, and
     * EXT-X-I-FRAME-STREAM-INF, which only describes trick-play playlists.
     * Kept verbatim: EXT-X-MEDIA renditions - separate audio and subtitle
     * tracks belong to the variant that is played - and every other tag.
     *
     * Variants are not surfaced in the UI (MPVModels parses hls-bitrate and
     * program-id; nothing reads either), so highest bandwidth is the whole
     * policy. Quality is chosen by the source before a url reaches here.
     */
    private fun narrowMasterPlaylist(text: String): String? {
        // A BOM or leading blank line is common enough from a CDN, and a body
        // that is a playlist should not be passed through for the sake of one.
        val trimmed = text.removePrefix("﻿").trimStart()
        if (!trimmed.startsWith("#EXTM3U")) {
            logcat(LogPriority.INFO) { "HLS_SERVE_ONE_VARIANT not a playlist, serving unchanged" }
            return null
        }
        if (!trimmed.contains("#EXT-X-STREAM-INF")) {
            logcat(LogPriority.INFO) { "HLS_SERVE_ONE_VARIANT media playlist, serving unchanged" }
            return null
        }

        val lines = trimmed.lines()
        var bestTag = -1
        var bestBandwidth = -1L
        var variants = 0
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            if (uriLineAfter(lines, i) == -1) continue
            variants++
            val bandwidth = bandwidthOf(lines[i])
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                bestTag = i
            }
        }
        // One variant is already the narrow case, and a master whose entries
        // have no uri line is malformed - pass both through untouched rather
        // than rebuilding them from a parse that clearly did not understand it.
        if (variants < 2 || bestTag == -1) {
            logcat(LogPriority.INFO) {
                "HLS_SERVE_ONE_VARIANT $variants usable variants, serving unchanged"
            }
            return null
        }
        val bestUri = uriLineAfter(lines, bestTag)

        val kept = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> i++
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val uri = uriLineAfter(lines, i)
                    if (i == bestTag) {
                        kept.append(line).append('\n').append(lines[bestUri]).append('\n')
                    }
                    i = if (uri == -1) i + 1 else uri + 1
                }
                else -> {
                    kept.append(line).append('\n')
                    i++
                }
            }
        }
        logcat(LogPriority.INFO) {
            "HLS_SERVE_ONE_VARIANT serving 1 of $variants variants (bandwidth $bestBandwidth)"
        }
        return kept.toString()
    }

    /** The index of the uri line belonging to the stream tag at [tag], or -1. */
    private fun uriLineAfter(lines: List<String>, tag: Int): Int {
        for (i in tag + 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            return if (line.startsWith("#")) -1 else i
        }
        return -1
    }

    /** BANDWIDTH from a stream tag; AVERAGE-BANDWIDTH is deliberately ignored. */
    private fun bandwidthOf(tag: String): Long {
        val marker = "BANDWIDTH="
        var index = tag.indexOf(marker)
        while (index > 0) {
            // AVERAGE-BANDWIDTH ends with the same characters, so a match is
            // only the real attribute when what precedes it is a separator.
            val before = tag[index - 1]
            if (before == ',' || before == ':') break
            index = tag.indexOf(marker, index + 1)
        }
        if (index < 0) return -1
        return tag.substring(index + marker.length)
            .takeWhile { it.isDigit() }
            .toLongOrNull() ?: -1
    }
    // <-- AM (HLS_SERVE_ONE_VARIANT)

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

    // AM (PRELOAD_PLAYLISTS) -->
    /**
     * How many manifests to remember. At roughly 17 KB for a 24-minute episode
     * and a few hundred bytes for a master, 256 of them is a few megabytes -
     * enough to cover a long queue and every episode revisited in a session.
     */
    private const val PLAYLIST_CACHE_ENTRIES = 256

    /** A manifest larger than this is not one worth holding in memory. */
    private const val PLAYLIST_MAX_CHARS = 2 * 1024 * 1024

    /**
     * How many opening segments to fetch ahead. Two, because that is what the
     * HLS demuxer reads before it starts; a third would be bandwidth spent on an
     * episode that may never be reached.
     */
    private const val WARM_SEGMENT_COUNT = 2

    /**
     * A segment larger than this is not warmed. These go to disk through
     * MediaCache and are bounded by its own ceiling, but a single oversized
     * object should not be pulled in whole on an episode nobody asked for yet.
     */
    private const val WARM_SEGMENT_MAX_BYTES = 24L * 1024 * 1024

    /**
     * How long a warm waits for the active video's buffer to be comfortably
     * ahead before giving up on this cycle.
     *
     * A minute is long enough to cover an ordinary dip and short enough that a
     * thread is not parked for the length of an episode. Giving up costs
     * nothing: the prefetch loop comes back.
     */
    private const val WARM_BUFFER_WAIT_MS = 60_000L

    private const val WARM_BUFFER_POLL_MS = 500L
    // <-- AM (PRELOAD_PLAYLISTS)
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
