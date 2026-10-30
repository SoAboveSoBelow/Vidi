package aniyomi.ui.browse.scroller

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

// AM (BROWSE_SCROLLER) -->
/**
 * Reads a video's dimensions without decoding it.
 *
 * A card's height is its width divided by this ratio. mpv can state it, but
 * only for a card that has a player, and players exist only for the card being
 * looked at and its immediate neighbours - so relying on mpv alone means every
 * card is the shape of its cover until the moment it is reached, and then
 * resizes. This is what makes the geometry right before a card is ever seen,
 * for the cards a player will not reach.
 *
 * Two ways in, because neither covers everything. A progressive file states
 * its dimensions in its container header, which [MediaMetadataRetriever] reads
 * with a few range requests and no decode. A playlist has no container header
 * at all, but states `RESOLUTION` for its variants, which is a single text
 * fetch.
 *
 * Rotation is read as well: a portrait recording is often stored as a
 * landscape frame with a rotation flag, and taking width over height at face
 * value for one of those gives the box exactly the wrong shape.
 *
 * Probed against the source url rather than the proxied one: a proxy
 * registration belongs to a card that is playing, and probing through it would
 * tear down that registration.
 */
object ScrollerAspectProbe {

    suspend fun probe(url: String, headers: Headers?, client: OkHttpClient?): Float? {
        if (client != null && url.substringBefore('?').endsWith(".m3u8")) {
            fromPlaylist(url, headers, client)?.let { return it }
        }
        return fromContainer(url, headers)
            ?: client?.let { fromPlaylist(url, headers, it) }
    }

    /**
     * Takes the largest variant: every variant of one stream is the same
     * shape, and the largest is the least likely to be a rounded-off low
     * rendition.
     */
    private suspend fun fromPlaylist(url: String, headers: Headers?, client: OkHttpClient): Float? =
        withIOContext {
            try {
                val request = Request.Builder()
                    .url(url)
                    .apply { headers?.let { headers(it) } }
                    .build()
                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withIOContext null
                    response.body.string()
                }
                RESOLUTION_REGEX.findAll(body)
                    .mapNotNull { match ->
                        val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                        val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                        if (width <= 0 || height <= 0) null else width to height
                    }
                    .maxByOrNull { (width, height) -> width.toLong() * height.toLong() }
                    ?.let { (width, height) -> width.toFloat() / height.toFloat() }
                    ?.takeIf { it.isFinite() && it > 0f }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.VERBOSE, e) { "BROWSE_SCROLLER playlist aspect read failed" }
                null
            }
        }

    private suspend fun fromContainer(url: String, headers: Headers?): Float? = withIOContext {
        val retriever = MediaMetadataRetriever()
        try {
            val headerMap = headers
                ?.toMultimap()
                ?.mapNotNull { (key, values) -> values.firstOrNull()?.let { key to it } }
                ?.toMap()
                .orEmpty()
            retriever.setDataSource(url, headerMap)

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: return@withIOContext null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: return@withIOContext null
            if (width <= 0 || height <= 0) return@withIOContext null

            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
            val quarterTurned = rotation == 90 || rotation == 270

            val ratio = if (quarterTurned) {
                height.toFloat() / width.toFloat()
            } else {
                width.toFloat() / height.toFloat()
            }
            ratio.takeIf { it.isFinite() && it > 0f }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.VERBOSE, e) { "BROWSE_SCROLLER container aspect read failed" }
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private val RESOLUTION_REGEX = Regex("RESOLUTION=(\\d+)x(\\d+)")
}
// <-- AM (BROWSE_SCROLLER)
