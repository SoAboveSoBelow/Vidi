package eu.kanade.tachiyomi.data.media

import eu.kanade.tachiyomi.animesource.model.Video
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

// AM (SHARED_LINK_RESOLUTION) -->
/**
 * Resolved hoster links, shared between the player and the downloader.
 *
 * [MediaCache] made the two share BYTES - watching an episode while downloading it
 * fetches the file once - but each side still resolved its own link to get there,
 * so the hoster was hit twice for the same content. On a source where extraction is
 * several requests, or where requests are rate-limited, that is the expensive half
 * of the work being done twice for no benefit.
 *
 * Keyed on (episode, quality) exactly as the byte cache is, for the same reason:
 * the same quality of the same episode arrives under different URLs routinely, so
 * a URL key would never hit.
 *
 * Deliberately short-lived. Hoster links expire, and a stale one shared between two
 * consumers fails both at once rather than one of them recovering independently -
 * so the TTL is set well below the point where links typically lapse, and both
 * callers already re-resolve on failure regardless.
 */
object ResolvedVideoCache {

    private data class Entry(val video: Video, val resolvedAtNanos: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun key(episodeId: Long?, quality: String): String? {
        if (episodeId == null) return null
        return "ep$episodeId-${quality.filter { it.isLetterOrDigit() }.take(MAX_QUALITY_KEY_LENGTH)}"
    }

    /** The cached resolution for [key], or null if absent or too old to trust. */
    fun get(key: String?): Video? {
        if (key == null) return null
        val entry = entries[key] ?: return null
        if (System.nanoTime() - entry.resolvedAtNanos > TTL_NANOS) {
            entries.remove(key)
            return null
        }
        return entry.video
    }

    fun put(key: String?, video: Video) {
        if (key == null) return
        if (video.videoUrl.isEmpty()) return
        entries[key] = Entry(video, System.nanoTime())
    }

    /**
     * Drops the cached resolution for [key], after a consumer has found it dead.
     *
     * The other consumer must not keep using a link that has already failed once:
     * this is the cost of sharing, and clearing on failure is what keeps it from
     * turning one expiry into two stuck consumers.
     */
    fun invalidate(key: String?) {
        if (key == null) return
        if (entries.remove(key) != null) {
            logcat(LogPriority.INFO) { "SHARED_LINK_RESOLUTION invalidated $key" }
        }
    }

    private const val MAX_QUALITY_KEY_LENGTH = 24
    private val TTL_NANOS = 3L * 60L * 1_000_000_000L
}
// <-- AM (SHARED_LINK_RESOLUTION)
