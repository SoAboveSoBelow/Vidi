package aniyomi.ui.browse.scroller

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.anime.model.Anime

// AM (BROWSE_SCROLLER) -->
/**
 * What a source can state about a feed entry in its own browse listing.
 *
 * The reason this exists: a feed is only as quick as the listing that feeds
 * it. Resolving a card the ordinary way is a details fetch, an episode-list
 * fetch, a hoster fetch and a video-list fetch before a url exists, and the
 * media's shape is not knowable until the last of those - so the card cannot
 * even be laid out at the right height until it is nearly playing. Every
 * mechanism that tried to hide that (probing the container, reading playlists,
 * capturing frames, holding several decoders open) was compensation for a gap
 * that does not need to be there.
 *
 * A site like Coub has no such gap: one timeline response carries each clip's
 * dimensions and a direct url, so its feed lays out correctly and plays
 * immediately from the listing alone. A source whose API does the same can say
 * so here, and the feed then has nothing to resolve or guess.
 *
 * Carried in [Anime.memo], which already exists on SAnime, already survives
 * the browse insert and is already a persisted column - so a source needs no
 * change to the extension API to fill it, and anything that does not fill it
 * simply falls back to the ordinary resolution path.
 *
 * Shape, under the `feed` key:
 * ```json
 * "feed": {
 *   "video": "https://.../clip.mp4",
 *   "width": 1920,
 *   "height": 1080,
 *   "audio": "https://.../audio.mp3",
 *   "loop": true
 * }
 * ```
 * `video` is required; everything else is optional. `audio` is for a source
 * that serves its video and audio as separate files, which is Coub's own
 * arrangement.
 */
data class ScrollerFeedMedia(
    val videoUrl: String,
    val audioUrl: String?,
    val aspectRatio: Float?,
) {
    companion object {
        private const val KEY = "feed"

        fun from(anime: Anime): ScrollerFeedMedia? = from(anime.memo)

        fun from(memo: JsonObject): ScrollerFeedMedia? {
            val feed = memo[KEY]?.jsonObjectOrNull() ?: return null
            val video = feed["video"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return null

            val width = feed["width"]?.floatOrNull()
            val height = feed["height"]?.floatOrNull()
            val ratio = if (width != null && height != null && width > 0f && height > 0f) {
                width / height
            } else {
                null
            }

            return ScrollerFeedMedia(
                videoUrl = video,
                audioUrl = feed["audio"]?.stringOrNull()?.takeIf { it.isNotBlank() },
                aspectRatio = ratio,
            )
        }

        private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
            runCatching { jsonObject }.getOrNull()

        private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
            runCatching { jsonPrimitive.content }.getOrNull()

        private fun kotlinx.serialization.json.JsonElement.floatOrNull(): Float? =
            runCatching { jsonPrimitive.floatOrNull }.getOrNull()
    }
}
// <-- AM (BROWSE_SCROLLER)
