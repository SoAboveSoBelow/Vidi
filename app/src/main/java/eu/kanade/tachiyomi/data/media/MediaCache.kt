package eu.kanade.tachiyomi.data.media

import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// AM (MEDIA_CACHE) -->
/**
 * A sparse, on-disk cache of the RAW SOURCE bytes that pass through
 * [MediaProxyServer], keyed by the episode and the quality rather than by URL.
 *
 * The problem it solves: downloading an episode and watching that same episode
 * fetched every byte twice, over one connection, with the two copies competing.
 * No amount of bandwidth arbitration fixes that - the consumers do not want
 * different bytes, so the answer is to fetch once and let both read it.
 *
 * Raw source bytes, deliberately, not the downloader's output. The download is
 * remuxing into mkv as it goes: those bytes are a different file, incomplete and
 * unindexed until it finishes, and [eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader.isDownload]
 * only switches to the local copy once the download is done. The stream is the one
 * thing both sides genuinely have in common.
 *
 * Keyed on (episode, quality) rather than on the URL because the same quality of
 * the same episode is routinely handed out under different URLs - CDN nodes,
 * signed tokens, expiries. A URL key would look like a cache and never hit. Two
 * different qualities are genuinely different content and get separate entries.
 *
 * Ranges, not a prefix. ffmpeg probes the head and the tail of a file before it
 * reads sequentially, and a seek leaves a hole either way, so "how much is cached"
 * is never a single number.
 */
class MediaCache(private val cacheDir: File) {

    /** A cached file plus the byte ranges of it that are actually present. */
    class Entry(val key: String, val file: File) {

        private val ranges = mutableListOf<LongRange>()
        private val readers = AtomicInteger(0)

        @Volatile
        var totalLength: Long? = null

        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()

        // AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS) -->
        /** Byte offset playback most recently pulled from the network for this entry. */
        @Volatile
        var playerOffset: Long = -1L

        /** When playback last pulled from the network, for deciding if it is still leading. */
        @Volatile
        var playerActiveAtNanos: Long = 0L

        fun notePlayerFetch(offset: Long) {
            playerOffset = offset
            playerActiveAtNanos = System.nanoTime()
        }

        /**
         * Whether playback is fetching from the network for this entry right now.
         *
         * Deliberately not "is playback ahead of this byte". That comparison was
         * trying to infer intent from a position and could not: playback sitting
         * behind an offset means both "it skipped that region permanently" and "it
         * seeked forward and will come back", which want opposite behaviour. Being
         * wrong in the second case is what put two fetches on one URL at once.
         *
         * This asks something knowable instead, and the rule it supports is stable
         * enough to state without reading the code: while playback is on the wire
         * for an entry, a download reads that entry from disk or waits. Regions
         * playback has already fetched are on disk and never waited for at all, so
         * waiting only ever applies to bytes that genuinely do not exist yet.
         */
        fun playbackIsFetching(windowNanos: Long): Boolean {
            if (playerOffset < 0L) return false
            return System.nanoTime() - playerActiveAtNanos <= windowNanos
        }

        /**
         * Whether playback will plausibly reach [offset] soon enough to be worth
         * waiting for.
         *
         * "Playback is fetching" alone was not enough, and the case that proved it
         * is the first thing every download does: ffmpeg reads the head of the
         * file, then seeks to the END for the index before it starts reading
         * sequentially. A linear stream is never going to produce those tail bytes,
         * so a download deferring on them waited for something that could not
         * arrive - with no timeout left to rescue it, until playback stopped
         * altogether. No bytes, no statistics, a progress spinner that never moved.
         *
         * Waiting is only ever right for bytes just ahead of where playback already
         * is. Anything behind it, or far enough ahead to be a different part of the
         * file, is the download's own to fetch.
         */
        fun playbackWillReach(offset: Long, lookaheadBytes: Long): Boolean {
            if (playerOffset < 0L) return false
            return offset >= playerOffset && offset - playerOffset <= lookaheadBytes
        }
        // <-- AM (PLAYER_LEADS_DOWNLOAD_FOLLOWS)

        val inUse: Boolean get() = readers.get() > 0

        fun acquire() {
            readers.incrementAndGet()
            lastUsedAt = System.currentTimeMillis()
        }

        fun release() {
            readers.decrementAndGet()
        }

        /**
         * How many contiguous bytes are cached starting at [offset]; 0 when that
         * offset is a hole.
         */
        @Synchronized
        fun availableAt(offset: Long): Long {
            val range = ranges.firstOrNull { offset in it } ?: return 0L
            return range.last - offset + 1
        }

        @Synchronized
        fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    raf.read(buffer, 0, length)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "MEDIA_CACHE read failed for $key at $offset" }
                -1
            }
        }

        @Synchronized
        fun write(offset: Long, buffer: ByteArray, length: Int) {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(offset)
                    raf.write(buffer, 0, length)
                }
                addRange(offset..(offset + length - 1))
            } catch (e: Exception) {
                // A cache write failing is not a playback or download failure: the
                // bytes have already been served to whoever asked for them.
                logcat(LogPriority.WARN, e) { "MEDIA_CACHE write failed for $key at $offset" }
            }
        }

        /** Adds [range], merging it with anything it touches or overlaps. */
        private fun addRange(range: LongRange) {
            var merged = range
            val iterator = ranges.iterator()
            while (iterator.hasNext()) {
                val existing = iterator.next()
                // +1 so adjacent ranges join rather than leaving a zero-length hole
                // that availableAt() would report as a miss.
                if (existing.first <= merged.last + 1 && merged.first <= existing.last + 1) {
                    merged = minOf(existing.first, merged.first)..maxOf(existing.last, merged.last)
                    iterator.remove()
                }
            }
            ranges.add(merged)
            ranges.sortBy { it.first }
        }

        @Synchronized
        fun cachedBytes(): Long = ranges.sumOf { it.last - it.first + 1 }

        // AM (RESUME_WINDOW_IS_PER_EPISODE_FILE) -->
        /**
         * Whether this entry is ONE SEGMENT of a segmented stream rather than
         * the episode's own file.
         *
         * MediaProxyServer mints a key per upstream file, so a progressive
         * episode is one entry spanning the whole timeline while an HLS episode
         * is one entry per .ts - the episode's key with "-s<hash>" appended (see
         * resolveSibling and warmSegments, the two places that build it).
         *
         * The distinction decides whether a position in milliseconds means
         * anything about which BYTES of this entry to keep. For the episode's
         * own file it does: length over duration is the bitrate. For a segment
         * it does not, and treating it as though it did was actively
         * destructive - resumeWindow computed the bitrate as the SEGMENT's
         * length over the EPISODE's duration, which for a half-megabyte segment
         * of a 45-second clip put the resume point a few hundred bytes in, so
         * the trim dropped the leading bytes of every segment in the episode.
         * Those few hundred bytes are the PAT/PMT and the first PES header: it
         * freed nothing worth freeing and left every segment a partial entry
         * with its header missing, which is what produced "mpegts: Packet
         * corrupt", timestamps jumping between 0.1s and 33s, audio and video
         * opening at unrelated positions, and seeks failing outright.
         */
        val isSegment: Boolean get() = SEGMENT_KEY_SUFFIX.containsMatchIn(key)
        // <-- AM (RESUME_WINDOW_IS_PER_EPISODE_FILE)

        // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
        /** The range map as "start-end,start-end", for the on-disk index. */
        @Synchronized
        fun serializeRanges(): String = ranges.joinToString(",") { "${it.first}-${it.last}" }

        // AM (TRIM_TO_RESUME_WINDOW) -->
        /**
         * Where a viewer would resume this entry, and the duration that position
         * is measured against. Both null until a position has been recorded.
         *
         * The duration is kept because it is the only way to turn a position in
         * MILLISECONDS into an offset in BYTES: the file's length over its
         * duration is the bitrate, and nothing else here knows it. It is also
         * what makes the pre-roll below an actual number of seconds rather than
         * a guessed number of megabytes.
         */
        @Volatile
        var resumePositionMs: Long? = null

        @Volatile
        var resumeDurationMs: Long? = null

        /**
         * The byte window worth keeping within [budgetBytes], or null when there
         * is nothing to anchor one to - no recorded position, no known length.
         *
         * Anchored [RESUME_PRE_ROLL_MS] BEFORE the resume point and extending
         * forward, not centred on it: playback resumes slightly behind where it
         * stopped and then moves forward, so forward bytes are the ones about to
         * be read. A null here means the caller has no basis for choosing which
         * bytes matter and should fall back to deleting the entry - keeping an
         * arbitrary prefix would be the "worthless data" case.
         */
        @Synchronized
        fun resumeWindow(budgetBytes: Long): LongRange? {
            // AM (RESUME_WINDOW_IS_PER_EPISODE_FILE) -->
            // A segment has no timeline of its own, so there is no position to
            // convert and no window to compute - see isSegment.
            if (isSegment) return null
            // <-- AM (RESUME_WINDOW_IS_PER_EPISODE_FILE)
            val length = totalLength?.takeIf { it > 0L } ?: return null
            val position = resumePositionMs?.takeIf { it >= 0L } ?: return null
            val duration = resumeDurationMs?.takeIf { it > 0L } ?: return null
            if (budgetBytes <= 0L) return null
            val bytesPerMs = length.toDouble() / duration
            val resumeAt = (position * bytesPerMs).toLong().coerceIn(0L, length - 1)
            val preRoll = (RESUME_PRE_ROLL_MS * bytesPerMs).toLong().coerceAtLeast(0L)
            val from = (resumeAt - preRoll).coerceAtLeast(0L)
            val to = (from + budgetBytes - 1).coerceAtMost(length - 1)
            return if (to >= from) from..to else null
        }

        /**
         * Whether every cached byte already lies inside [window].
         *
         * This is what makes trimming repeatable without compounding: a second
         * trim against an unchanged window has nothing to do and says so, so no
         * file is rewritten and no bytes are lost to rounding on each pass. A
         * window that has MOVED (the viewer watched on) reports false and the
         * entry is rewritten once against the new one.
         */
        @Synchronized
        fun isWithin(window: LongRange): Boolean =
            ranges.all { it.first >= window.first && it.last <= window.last }

        /** The cached ranges clipped to [window] - exactly what a trim keeps. */
        @Synchronized
        fun rangesWithin(window: LongRange): List<LongRange> =
            ranges.mapNotNull { range ->
                val from = maxOf(range.first, window.first)
                val to = minOf(range.last, window.last)
                if (to >= from) from..to else null
            }

        @Synchronized
        fun replaceRanges(kept: List<LongRange>) {
            ranges.clear()
            ranges.addAll(kept.sortedBy { it.first })
        }

        /** The 4th index field: the resume anchor, or empty when there is none. */
        @Synchronized
        fun serializeResume(): String {
            val position = resumePositionMs ?: return ""
            val duration = resumeDurationMs ?: return ""
            return "$position/$duration"
        }

        @Synchronized
        fun restoreResume(serialized: String) {
            resumePositionMs = serialized.substringBefore('/').toLongOrNull()
            resumeDurationMs = serialized.substringAfter('/', "").toLongOrNull()
        }
        // <-- AM (TRIM_TO_RESUME_WINDOW)

        @Synchronized
        fun restoreRanges(serialized: String, length: Long?) {
            ranges.clear()
            serialized.split(',').forEach { part ->
                val start = part.substringBefore('-').toLongOrNull() ?: return@forEach
                val end = part.substringAfter('-').toLongOrNull() ?: return@forEach
                if (end >= start) ranges.add(start..end)
            }
            ranges.sortBy { it.first }
            totalLength = length
        }
        // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    // AM (RETAIN_MEDIA_SIZE_LIMIT) -->
    /**
     * Disk ceiling for kept data, from the user's setting.
     *
     * A target rather than a hard bound: an entry being read right now is never
     * evicted, so a single episode larger than the whole ceiling will exceed it for
     * as long as it is playing. Bounding that properly would mean dropping bytes
     * out from under a live reader, which is a stall the player cannot explain.
     */
    @Volatile
    private var maxBytes: Long = DEFAULT_MAX_CACHE_BYTES

    /**
     * Zero is passed through untouched - it is the off state, not a size to clamp,
     * and coercing it up to the floor would quietly re-enable keeping. At zero,
     * anything not being read right now is evicted, so the cache still lets a
     * download and a playback of the same episode share bytes while both are live
     * but leaves nothing behind. Any non-zero value is floored, since an allowance
     * too small to hold a working window would thrash.
     */
    fun setMaxBytes(bytes: Long) {
        maxBytes = if (bytes <= 0L) 0L else bytes.coerceAtLeast(MIN_MAX_CACHE_BYTES)
        synchronized(this) { evictIfNeeded() }
    }
    // <-- AM (RETAIN_MEDIA_SIZE_LIMIT)

    // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
    private val indexFile = File(cacheDir, INDEX_FILE_NAME)

    /**
     * Rebuilds the in-memory range maps from the index written alongside the data.
     *
     * Without this the files on disk are bytes with no record of which parts are real,
     * which is why the cache used to be wiped at process start. An entry whose data
     * file has gone missing (storage pressure can delete from cacheDir) is dropped
     * rather than trusted.
     */
    @Synchronized
    fun restore() {
        runCatching {
            if (indexFile.exists()) {
                indexFile.readLines().forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@forEach
                    val key = parts[0]
                    val file = File(cacheDir, key)
                    if (!file.exists()) return@forEach
                    val entry = Entry(key, file)
                    entry.restoreRanges(parts[2], parts[1].toLongOrNull())
                    // AM (TRIM_TO_RESUME_WINDOW)
                    parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let(entry::restoreResume)
                    entries[key] = entry
                }
            }
        }.onFailure {
            logcat(LogPriority.WARN, it) { "MEDIA_CACHE index unreadable, starting empty" }
            entries.clear()
        }
        // AM (MEDIA_CACHE_ORPHAN_SWEEP): always, including the no-index and
        // unreadable-index paths above, which used to return before any of the
        // files those cases orphan could be dealt with.
        sweepOrphans()
    }

    // AM (MEDIA_CACHE_ORPHAN_SWEEP) -->
    /**
     * Deletes data files the index does not describe.
     *
     * The ceiling is enforced against [entries], so a file no entry points at is
     * not merely unusable - it is invisible: [evictIfNeeded] cannot count it and
     * will never delete it, so it sits in cacheDir for the rest of the install.
     * The index is only written by [persist], so every end of a process that did
     * not get to run it - a crash, a swipe-kill, the service being stopped -
     * orphans whatever had been written since the last one. That is how a 512MB
     * setting ends up with gigabytes on disk and stays there across restarts,
     * and why clearing the app's cache by hand was the only thing that helped.
     *
     * Deleting rather than adopting them, for the reason [restore]'s own comment
     * gives: bytes with no range record cannot be told apart from holes, so they
     * are not usable as cache - which is exactly why this cache was wiped at
     * process start before the index existed. This keeps that guarantee for the
     * part the index does not cover, instead of extending trust to it.
     */
    private fun sweepOrphans() {
        val known = entries.keys
        runCatching {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name == INDEX_FILE_NAME || file.name in known) return@forEach
                val size = file.length()
                if (file.delete()) {
                    logcat(LogPriority.INFO) { "MEDIA_CACHE swept orphan ${file.name} ($size bytes)" }
                }
            }
        }
    }
    // <-- AM (MEDIA_CACHE_ORPHAN_SWEEP)

    /** Writes the range maps so a later process can tell which bytes are real. */
    @Synchronized
    fun persist() {
        runCatching {
            cacheDir.mkdirs()
            indexFile.writeText(
                entries.values.joinToString("\n") { entry ->
                    "${entry.key}\t${entry.totalLength ?: ""}\t${entry.serializeRanges()}" +
                        // AM (TRIM_TO_RESUME_WINDOW): a 4th field, so an index
                        // written by an older build still parses - the reader
                        // only requires the first three.
                        "\t${entry.serializeResume()}"
                },
            )
        }.onFailure {
            logcat(LogPriority.WARN, it) { "MEDIA_CACHE index write failed" }
        }
    }

    /**
     * Drops every entry whose episode is no longer in [episodeIds], which is the
     * temporary-position table's current contents. That table is the definition of
     * "recent" the retention setting is described by, so an episode leaving it is
     * exactly when its bytes stop being worth keeping.
     *
     * An entry with a live reader is never dropped - see [evictIfNeeded].
     */
    @Synchronized
    fun retainOnly(episodeIds: Set<Long>) {
        entries.values.toList()
            // AM (ACTIVE_EPISODE_NEVER_EVICTED): same exemption as evictIfNeeded.
            // Turning retention off mid-episode must not pull the bytes out from
            // under the episode being watched.
            .filterNot { isProtected(it) }
            .filterNot { episodeIdOf(it.key) in episodeIds }
            .forEach { entry ->
                entries.remove(entry.key)
                runCatching { entry.file.delete() }
            }
        persist()
    }

    // AM (TRIM_TO_RESUME_WINDOW) -->
    /**
     * Records where [episodeId] would resume, so eviction can keep the bytes
     * around that point instead of the whole entry or none of it.
     *
     * Takes milliseconds rather than a byte offset because the caller has the
     * position and the duration and this has the length; splitting it any other
     * way would make one side guess the bitrate.
     */
    @Synchronized
    fun setResumePosition(episodeId: Long, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        entries.values
            .filter { episodeIdOf(it.key) == episodeId }
            // AM (RESUME_WINDOW_IS_PER_EPISODE_FILE): the episode's position is
            // measured against the episode's timeline, which only the episode's
            // own file has - see Entry.isSegment.
            .filterNot { it.isSegment }
            .forEach { entry ->
                entry.resumePositionMs = positionMs
                entry.resumeDurationMs = durationMs
            }
    }

    /**
     * Rewrites [entry] to hold only the bytes inside its resume window, freeing
     * the rest of the disk. Returns whether anything was kept.
     *
     * A rewrite rather than a hole-punch because there is no portable way to
     * free bytes from the middle of a file on Android - forgetting the ranges
     * alone would leave the disk exactly as full, which is the thing being
     * fixed. The window is small, so this copies megabytes, not gigabytes.
     *
     * What it guarantees:
     * - It does nothing when the entry is already inside its window, so running
     *   it repeatedly cannot shave the kept region a little smaller each time.
     * - It only ever reports success after the kept ranges have been read in
     *   full. A short read means the range map claimed bytes the file does not
     *   have, so the original is left untouched and the caller deletes it as
     *   before - a trim never turns a questionable entry into a confident one.
     * - The range map is replaced with what was actually copied, so a later read
     *   can only ask for bytes that are really there.
     *
     * What it does not guarantee: the delete-then-rename at the end is not
     * atomic. A process death inside that window leaves the entry's file
     * missing, which restore() already treats as a dropped entry and
     * sweepOrphans() cleans the partial file up. The failure mode is a lost
     * cache entry, never a file whose ranges describe someone else's bytes.
     */
    @Synchronized
    private fun trimToResumeWindow(entry: Entry, budgetBytes: Long): Boolean {
        val window = entry.resumeWindow(budgetBytes) ?: return false
        if (entry.isWithin(window)) return true
        val kept = entry.rangesWithin(window)
        if (kept.isEmpty()) return false

        val target = File(cacheDir, entry.key + TRIM_SUFFIX)
        runCatching { target.delete() }
        val copied = runCatching {
            RandomAccessFile(entry.file, "r").use { source ->
                RandomAccessFile(target, "rw").use { destination ->
                    val buffer = ByteArray(TRIM_COPY_BUFFER_BYTES)
                    kept.forEach { range ->
                        var offset = range.first
                        while (offset <= range.last) {
                            val chunk = minOf(buffer.size.toLong(), range.last - offset + 1).toInt()
                            source.seek(offset)
                            var read = 0
                            while (read < chunk) {
                                val count = source.read(buffer, read, chunk - read)
                                if (count < 0) return@runCatching false
                                read += count
                            }
                            destination.seek(offset)
                            destination.write(buffer, 0, chunk)
                            offset += chunk
                        }
                    }
                    destination.fd.sync()
                }
            }
            true
        }.getOrDefault(false)

        val expectedLength = kept.last().last + 1
        if (!copied || target.length() != expectedLength) {
            runCatching { target.delete() }
            logcat(LogPriority.WARN) { "MEDIA_CACHE trim of ${entry.key} failed, leaving it intact" }
            return false
        }

        val freed = entry.file.length() - target.length()
        if (!entry.file.delete() || !target.renameTo(entry.file)) {
            runCatching { target.delete() }
            logcat(LogPriority.WARN) { "MEDIA_CACHE trim of ${entry.key} could not be swapped in" }
            return false
        }
        entry.replaceRanges(kept)
        logcat(LogPriority.INFO) {
            "MEDIA_CACHE trimmed ${entry.key} to $window, freed $freed bytes"
        }
        return true
    }
    // <-- AM (TRIM_TO_RESUME_WINDOW)

    // AM (DOWNLOADED_NEEDS_NO_CACHE) -->
    /**
     * Drops every entry belonging to [episodeId].
     *
     * Called when an episode finishes downloading: the download is a permanent
     * copy that playback reads straight off disk (a local path is never proxied,
     * see Downloader.proxiedVideoUrl and the player's equivalent), so the cached
     * bytes are a second copy of the same file that can never be read again.
     * They also went on competing for the retention ceiling, evicting the
     * previous episode - which the cache does need - to keep bytes for one that
     * is already on disk.
     *
     * Protected entries are left alone, as everywhere else: if the episode is
     * being watched through the proxy at this moment, its bytes are being read
     * and the next sweep will take them instead.
     */
    @Synchronized
    fun dropEpisode(episodeId: Long) {
        entries.values.toList()
            .filterNot { isProtected(it) }
            .filter { episodeIdOf(it.key) == episodeId }
            .forEach { entry ->
                entries.remove(entry.key)
                runCatching { entry.file.delete() }
                logcat(LogPriority.INFO) { "MEDIA_CACHE dropped downloaded ${entry.key}" }
            }
        persist()
    }
    // <-- AM (DOWNLOADED_NEEDS_NO_CACHE)

    // AM (CACHED_BYTES_STAY_ADDRESSABLE) -->
    /**
     * Whether any bytes of [episodeId] are still held.
     *
     * Asked before a proxy registration is released: the registration is what
     * maps a request to an entry, so letting it go while the entry is still on
     * disk makes those bytes unreachable until something re-resolves the stream
     * and registers it again - a server round trip to read a file that never
     * left the device.
     */
    @Synchronized
    fun holdsEpisode(episodeId: Long): Boolean =
        entries.values.any { episodeIdOf(it.key) == episodeId && it.cachedBytes() > 0L }
    // <-- AM (CACHED_BYTES_STAY_ADDRESSABLE)

    /** "ep<id>-<quality>" - see MediaProxyServer.cacheKeyFor. */
    private fun episodeIdOf(key: String): Long? =
        key.removePrefix("ep").substringBefore('-').toLongOrNull()
    // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)

    @Synchronized
    fun entryFor(key: String): Entry {
        return entries.getOrPut(key) {
            cacheDir.mkdirs()
            Entry(key, File(cacheDir, key)).also { evictIfNeeded() }
        }
    }

    // AM (ACTIVE_EPISODE_NEVER_EVICTED) -->
    /**
     * The episode currently playing, which is never evicted whatever the ceiling.
     *
     * Protection used to rest entirely on [Entry.inUse], a refcount taken around
     * each range transfer in MediaProxyServer. That answers "is a transfer running
     * right now", which is not the same question: mpv reads in ranges and closes
     * the connection once it has what it asked for, then sits on a 64MB demuxer
     * buffer, so the playing episode's refcount is zero for most of its own
     * playback. It survived mostly by being the most-recently-used entry, which
     * is luck rather than a guarantee.
     *
     * Set by the player when the episode changes, cleared when playback stops.
     */
    @Volatile
    private var activeEpisodeId: Long? = null

    fun setActiveEpisode(episodeId: Long?) {
        activeEpisodeId = episodeId
    }

    /** Whether [entry] belongs to the playing episode or to a transfer in flight. */
    private fun isProtected(entry: Entry): Boolean {
        if (entry.inUse) return true
        val active = activeEpisodeId ?: return false
        return episodeIdOf(entry.key) == active
    }
    // <-- AM (ACTIVE_EPISODE_NEVER_EVICTED)

    /**
     * Drops least-recently-used entries until the cache is back under its ceiling.
     *
     * AM (ACTIVE_EPISODE_NEVER_EVICTED): the playing episode and any entry with a
     * transfer in flight are exempt - taking a cached range out from under a
     * player mid-read is a stall it has no way to explain. The ceiling therefore
     * bounds the kept remainder, not the active episode on top of it, which is
     * what the setting has always actually meant.
     */
    @Synchronized
    private fun evictIfNeeded() {
        // AM (CEILING_EXCLUDES_PROTECTED) -->
        // The total counts only what the ceiling actually governs.
        //
        // It used to sum every entry, the protected ones included, while only
        // the unprotected ones could be evicted to bring it down - so the
        // episode being watched was charged against a ceiling it is exempt from.
        // One HD episode is larger than any sane setting on its own, which made
        // the comparison true from the moment playback started and evicted the
        // whole remainder however small it was: the previous episode in the
        // queue was always deleted within seconds of the next one starting, and
        // going back to it re-downloaded from nothing. That is the opposite of
        // what the setting is for, and it is what the doc comment above has
        // always claimed this does - "the ceiling bounds the kept remainder,
        // not the active episode on top of it".
        //
        // Taken once rather than re-read per step, so the sum and the eviction
        // order describe the same set - entries is concurrent, and the old form
        // iterated it twice.
        val evictable = entries.values.filterNot { isProtected(it) }
        var total = evictable.sumOf { it.cachedBytes() }
        if (total <= maxBytes) return
        // <-- AM (CEILING_EXCLUDES_PROTECTED)

        // AM (TRIM_TO_RESUME_WINDOW) -->
        // The most recently used entry is trimmed rather than deleted; the ones
        // behind it are deleted as before.
        //
        // That entry is the episode just left - the one being come back to - and
        // on any realistic setting it does not fit whole: a ceiling smaller than
        // an HD episode made the old all-or-nothing pass delete it in full, so
        // the feature kept nothing at all in exactly the case it exists for.
        // Deleting the older entries first means the survivor gets the whole
        // budget rather than a share of it, which is what keeps the kept window
        // as long as the setting allows instead of trimming more than it has to.
        // AM (RESUME_WINDOW_IS_PER_EPISODE_FILE) -->
        // Only an entry that CAN be trimmed is spared from the pass below.
        //
        // A segmented episode's newest entry is one .ts, not the episode, so
        // sparing it bought nothing and the trim then mangled it. With no
        // trimmable entry there is no survivor, and this is the whole-entry
        // eviction by recency that it was before the trim existed - which for a
        // segmented stream is the better unit anyway: the segments still held
        // are the ones most recently read, so they are the ones around where the
        // viewer stopped, and a segment is either complete or absent rather than
        // complete-looking and short of its header.
        val ordered = evictable.sortedBy { it.lastUsedAt }
        val survivor = ordered.lastOrNull { !it.isSegment }
        // <-- AM (RESUME_WINDOW_IS_PER_EPISODE_FILE)
        ordered.forEach { entry ->
            if (total <= maxBytes) return
            if (entry === survivor) return@forEach
            total -= entry.cachedBytes()
            entries.remove(entry.key)
            runCatching { entry.file.delete() }
            logcat(LogPriority.INFO) { "MEDIA_CACHE evicted ${entry.key}" }
        }

        if (total <= maxBytes || survivor == null) return
        // Falls through to deleting it when there is no resume anchor to keep a
        // window around, or when the rewrite could not be trusted - an entry
        // whose kept bytes cannot be verified is not kept.
        if (trimToResumeWindow(survivor, maxBytes)) {
            persist()
            return
        }
        entries.remove(survivor.key)
        runCatching { survivor.file.delete() }
        logcat(LogPriority.INFO) { "MEDIA_CACHE evicted ${survivor.key}" }
        // <-- AM (TRIM_TO_RESUME_WINDOW)
    }

    // AM (EVICT_ON_WRITE) -->
    /**
     * Re-checks the ceiling after bytes have landed.
     *
     * Eviction used to run only when a new key was created, so nothing enforced
     * the limit while existing entries grew - the cache drifted past its setting
     * and stayed there until the next new episode happened to be proxied. A
     * 500MB setting holding 657MB is that gap, not an unusual case.
     *
     * Driven by bytes written rather than by a timer: the only thing that can
     * push the total over is a write, so that is the moment to look. The counter
     * keeps it from being a full sum on every chunk.
     */
    private val bytesSinceEvictionCheck = java.util.concurrent.atomic.AtomicLong(0L)

    fun noteBytesWritten(count: Int) {
        if (count <= 0) return
        if (bytesSinceEvictionCheck.addAndGet(count.toLong()) < EVICTION_CHECK_INTERVAL_BYTES) return
        bytesSinceEvictionCheck.set(0L)
        synchronized(this) { evictIfNeeded() }
    }
    // <-- AM (EVICT_ON_WRITE)

    /** Discards everything. The cache holds no state worth keeping across a restart yet. */
    @Synchronized
    fun clear() {
        entries.clear()
        runCatching { cacheDir.deleteRecursively() }
    }

    companion object {
        private const val DEFAULT_MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MIN_MAX_CACHE_BYTES = 256L * 1024 * 1024
        private const val INDEX_FILE_NAME = "index.tsv"

        // AM (EVICT_ON_WRITE) -->
        /** How many bytes may land between ceiling checks. */
        private const val EVICTION_CHECK_INTERVAL_BYTES = 8L * 1024 * 1024
        // <-- AM (EVICT_ON_WRITE)

        // AM (TRIM_TO_RESUME_WINDOW) -->
        /** How far before the resume point the kept window starts. */
        private const val RESUME_PRE_ROLL_MS = 10_000L
        private const val TRIM_SUFFIX = ".trim"
        private const val TRIM_COPY_BUFFER_BYTES = 1 shl 16
        // <-- AM (TRIM_TO_RESUME_WINDOW)

        // AM (RESUME_WINDOW_IS_PER_EPISODE_FILE)
        private val SEGMENT_KEY_SUFFIX = Regex("-s\\d+$")

        @Volatile
        private var instance: MediaCache? = null

        fun get(context: Context, retain: Boolean = false, maxBytes: Long? = null): MediaCache {
            return instance ?: synchronized(this) {
                instance ?: MediaCache(File(context.cacheDir, "media_cache")).also {
                    // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
                    // With retention on, the index is read back and last session's
                    // bytes stay usable. With it off, the previous session's data
                    // is discarded and the cache is only a within-session
                    // de-duplicator between the player and the downloader.
                    if (retain) it.restore() else it.clear()
                    // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
                    // AM (RETAIN_MEDIA_SIZE_LIMIT) -->
                    if (maxBytes != null) it.setMaxBytes(maxBytes)
                    // <-- AM (RETAIN_MEDIA_SIZE_LIMIT)
                    instance = it
                }
            }
        }

        // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
        /** The live cache, if one has been created. Null before any media is proxied. */
        fun peek(): MediaCache? = instance
        // <-- AM (RETAIN_RECENT_EPISODE_MEDIA)
    }
}
// <-- AM (MEDIA_CACHE)
