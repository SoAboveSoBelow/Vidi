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

        // AM (RETAIN_RECENT_EPISODE_MEDIA) -->
        /** The range map as "start-end,start-end", for the on-disk index. */
        @Synchronized
        fun serializeRanges(): String = ranges.joinToString(",") { "${it.first}-${it.last}" }

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
        if (!indexFile.exists()) return
        runCatching {
            indexFile.readLines().forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@forEach
                val key = parts[0]
                val file = File(cacheDir, key)
                if (!file.exists()) return@forEach
                val entry = Entry(key, file)
                entry.restoreRanges(parts[2], parts[1].toLongOrNull())
                entries[key] = entry
            }
        }.onFailure {
            logcat(LogPriority.WARN, it) { "MEDIA_CACHE index unreadable, starting empty" }
            entries.clear()
        }
    }

    /** Writes the range maps so a later process can tell which bytes are real. */
    @Synchronized
    fun persist() {
        runCatching {
            cacheDir.mkdirs()
            indexFile.writeText(
                entries.values.joinToString("\n") { entry ->
                    "${entry.key}\t${entry.totalLength ?: ""}\t${entry.serializeRanges()}"
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
            .filterNot { it.inUse }
            .filterNot { episodeIdOf(it.key) in episodeIds }
            .forEach { entry ->
                entries.remove(entry.key)
                runCatching { entry.file.delete() }
            }
        persist()
    }

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

    /**
     * Drops least-recently-used entries until the cache is back under its ceiling.
     * An entry with a live reader is never dropped - taking a cached range out from
     * under a player mid-read is a stall it has no way to explain.
     */
    @Synchronized
    private fun evictIfNeeded() {
        var total = entries.values.sumOf { it.cachedBytes() }
        if (total <= maxBytes) return

        entries.values
            .filterNot { it.inUse }
            .sortedBy { it.lastUsedAt }
            .forEach { entry ->
                if (total <= maxBytes) return
                total -= entry.cachedBytes()
                entries.remove(entry.key)
                runCatching { entry.file.delete() }
                logcat(LogPriority.INFO) { "MEDIA_CACHE evicted ${entry.key}" }
            }
    }

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
