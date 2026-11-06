package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.Video
import tachiyomi.core.common.storage.nameWithoutExtension

// AM (EXTERNAL_PLAYBACK) -->
/**
 * A video handed to us from outside the app - a file manager, a share sheet, or
 * any other app that asks Vidi to open a video.
 *
 * The URI is already a playable address, so there is nothing to resolve: this
 * returns one [Hoster] holding one [Video], which is the same shape
 * EpisodeLoader.getHostersOnLocal() produces for a local-source episode. That
 * is what lets the existing loadVideo/hosterState pipeline play this with no
 * parallel code path beside it - see PlaybackSource's own doc comment.
 *
 * [title] is resolved eagerly by [create] rather than lazily here: reading a
 * display name out of a content URI is a ContentResolver query, and the player
 * UI and notification read the title synchronously on the main thread.
 */
class ExternalPlaybackSource private constructor(
    override val sessionKey: SessionKey.External,
    override val title: String,
    private val uri: Uri,
) : PlaybackSource {

    /**
     * True for http(s), false for a local file.
     *
     * Not the same question as "is this a library download" (which is what
     * LibraryPlaybackSource answers) but the same answer shape, and it drives
     * the same streaming-only UI affordances. A content:// URI from a cloud
     * provider technically streams; it is reported local here because what the
     * affordances care about is whether mpv is reading a network URL, and for a
     * content URI it is not - it reads a file descriptor the provider opens.
     */
    override fun isOnline(): Boolean = uri.scheme?.lowercase() in ONLINE_SCHEMES

    /**
     * One option, pointing at an owned `fd://N` where the session holds a
     * descriptor, and at the URI itself otherwise.
     *
     * The descriptor is preferred because the URI may already be unusable by the
     * time this runs: a reload after the task was swiped away has lost the grant
     * the launching app gave us (see PlayerMediaHolder.externalFd for the
     * confirmed failure). parseVideoUrl()/resolveUri() would then openFileDescriptor()
     * the URI, fail, and fall through to handing mpv a raw content:// string it
     * cannot open.
     *
     * Falling back to the URI rather than failing outright keeps the
     * first-ever open working even if claiming a descriptor somehow did not:
     * resolveUri() will open its own fd from a grant that is still live at that
     * point.
     */
    override suspend fun resolveStreams(): List<Hoster> {
        val path = PlayerMediaHolder.dupExternalFd(sessionKey.uri) ?: uri.toString()
        return listOf(Video(path, title)).toHosterList()
    }

    companion object {
        private val ONLINE_SCHEMES = setOf("http", "https", "rtmp", "rtsp")

        /**
         * Builds a source for [uri].
         *
         * [suppliedTitle] is the title the launching app gave us, when it gave
         * one - preferred over anything derived from the URI, since an app that
         * bothered to send a title knows more about what this is than a path
         * segment does.
         *
         * Touches storage (a display-name lookup), so call it off the main
         * thread.
         */
        fun create(context: Context, uri: Uri, suppliedTitle: String? = null): ExternalPlaybackSource {
            val title = suppliedTitle?.takeIf { it.isNotBlank() }
                ?: displayNameOf(context, uri)
                ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: uri.toString()
            return ExternalPlaybackSource(
                sessionKey = SessionKey.External(uri = uri.toString()),
                title = title,
                uri = uri,
            )
        }

        /**
         * The file's own display name, or null when storage cannot tell us.
         *
         * Wrapped because a URI handed over by another app can fail in several
         * uninteresting ways - permission already revoked, provider gone, a
         * tree URI that resolves to nothing - and none of them should stop
         * playback: a worse title is better than no video.
         */
        private fun displayNameOf(context: Context, uri: Uri): String? = try {
            UniFile.fromUri(context, uri)?.nameWithoutExtension?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
// <-- AM (EXTERNAL_PLAYBACK)
