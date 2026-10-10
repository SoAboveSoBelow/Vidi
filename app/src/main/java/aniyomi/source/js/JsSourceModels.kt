package aniyomi.source.js

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers

/**
 * The wire format between project code and the app's models.
 *
 * `url` and `title` have no defaults on purpose. A project that fails to produce
 * them is broken, and the repo's own conventions are explicit that mandatory
 * fields should fail loudly rather than be filled with a plausible placeholder -
 * a silently empty title breaks downloads and library management in ways that
 * are much harder to trace than a parse error.
 *
 * Everything else is optional and nullable, because a real listing is not
 * homogeneous: the live rule34video page serves one card in twenty-four with no
 * image at all.
 */
/**
 * Strict about keys it does not know, deliberately.
 *
 * This was lenient, and the leniency hid a bug for an entire build: a project
 * returning `animes` instead of `entries` decoded to an empty page and reported
 * success. For a format decoded from a stranger's server, ignoring unknown keys
 * is right; for one decoded from code the person is editing in an editor with a
 * console attached, it throws away the only signal that says why nothing
 * appeared. A misspelled field is now an error naming the field.
 *
 * [isLenient] stays on - that is about quoting, not about keys.
 */
val jsJson = Json {
    ignoreUnknownKeys = false
    isLenient = true
}

@Serializable
class JsAnimesPage(
    val entries: List<JsAnime> = emptyList(),
    val hasNextPage: Boolean = false,
)

@Serializable
class JsAnime(
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
)

@Serializable
class JsEpisode(
    val url: String,
    val name: String,
    val number: Float = UNSET_EPISODE_NUMBER,
    val dateUpload: Long = 0L,
    val scanlator: String? = null,
    val summary: String? = null,
)

/**
 * One playable stream.
 *
 * [headers] exists because the player is a separate process: it does not share
 * the app's cookie jar or user agent, so a CDN that checks a Referer or a
 * session needs to be told explicitly. See `http.cookiesFor`.
 */
@Serializable
class JsVideo(
    val url: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
    /**
     * mpv options for this stream, merged over [DEFAULT_MPV_ARGS].
     *
     * Rarely needed - the defaults cover the usual case - but a site with an
     * unusual container or a need for a specific demuxer setting has nowhere
     * else to say so.
     */
    val mpvArgs: Map<String, String> = emptyMap(),
)

/**
 * Applied to every stream a project returns.
 *
 * A CDN will occasionally drop a long transfer, and nothing in the app
 * re-fetches when it does: `resolveVideo` runs once before playback starts, so
 * a stream that dies mid-file stops and stays stopped - which looks like
 * buffering that never ends, intermittently, depending on whether the
 * connection happened to survive.
 *
 * ffmpeg's HTTP reader can resume a broken transfer with a range request, but
 * only when told to; these are the flags that tell it. They are defaults rather
 * than something each project remembers to set, because no project author would
 * think to until they had already been bitten by it.
 *
 * The delay ceiling is not a timeout, and it is not continuous. ffmpeg computes
 * the next delay as `1 + 2 * previous` from zero - 0s, 1s, 3s, 7s, 15s, 31s -
 * and gives up when the next delay would exceed this value, so the ceiling only
 * chooses how many attempts there are: 3 gives four tries over 4s, 7 gives five
 * over 11s, and anything from 15 to 120 gives six over 26s. The default 120 is
 * therefore the same as 15.
 *
 * 7 is the trade. The drop worth surviving on a phone is a network handover,
 * which takes a few seconds and would be given up on inside 4s; 26s of silent
 * spinner is the other failure, where the stream is simply gone and the player
 * should say so. Not set at all - the actual default for all three flags below
 * is off - means a mid-file drop gets no retry whatsoever and playback stops
 * dead, which is the behaviour these replace.
 *
 * reconnect_on_http_error is deliberately absent: when a signed CDN url expires
 * the retry is answered 403 forever, and the fix for that is re-resolving the
 * url, which only the project can do.
 */
private val DEFAULT_MPV_ARGS = mapOf(
    "stream-lavf-o" to listOf(
        "reconnect=1",
        "reconnect_streamed=1",
        "reconnect_on_network_error=1",
        "reconnect_delay_max=7",
    ).joinToString(","),
)

const val UNSET_EPISODE_NUMBER = -1f

fun JsAnime.toSAnime(): SAnime = SAnime.create().also { anime ->
    anime.url = url
    anime.title = title
    anime.thumbnail_url = thumbnail
    anime.description = description
    anime.genre = genre?.joinToString()
    anime.author = author
    anime.artist = artist
    anime.status = status.toAnimeStatus()
}

fun JsAnimesPage.toAnimesPage(): AnimesPage =
    AnimesPage(animes = entries.map { it.toSAnime() }, hasNextPage = hasNextPage)

/**
 * Merges the project's headers over the source's, rather than replacing them.
 *
 * The player resolves headers as `video.headers ?: source.headers`, so a video
 * carrying *any* header silently discards every default - including the user
 * agent. A project adding a Referer would hand the player a request with no
 * user agent at all, and a CDN that wants one stalls instead of failing, which
 * presents as a video that buffers forever.
 *
 * [defaults] therefore comes from the source, and the project's entries
 * override by name.
 */
fun JsVideo.toVideo(defaults: Headers? = null): Video = Video(
    videoUrl = url,
    videoTitle = quality,
    headers = mergedHeaders(defaults, headers),
    // The project's own options win, so a site that needs something different
    // can say so without losing the rest.
    mpvArgs = (DEFAULT_MPV_ARGS + mpvArgs).toList(),
)

private fun mergedHeaders(defaults: Headers?, extra: Map<String, String>): Headers? {
    if (defaults == null && extra.isEmpty()) return null

    val builder = defaults?.newBuilder() ?: Headers.Builder()
    // set, not add: a project naming a header the source already sends means to
    // replace that one, not to send it twice.
    extra.forEach { (name, value) -> builder.set(name, value) }
    return builder.build()
}

fun JsEpisode.toSEpisode(): SEpisode = SEpisode.create().also { episode ->
    episode.url = url
    episode.name = name
    episode.episode_number = number
    episode.date_upload = dateUpload
    episode.scanlator = scanlator
    episode.summary = summary
}

/**
 * Projects write a word, not a magic integer, so a definition stays readable and
 * does not depend on the host's constants.
 */
private fun String?.toAnimeStatus(): Int = when (this?.lowercase()) {
    "ongoing" -> SAnime.ONGOING
    "completed" -> SAnime.COMPLETED
    "licensed" -> SAnime.LICENSED
    "publishing_finished" -> SAnime.PUBLISHING_FINISHED
    "cancelled" -> SAnime.CANCELLED
    "on_hiatus" -> SAnime.ON_HIATUS
    "upcoming" -> SAnime.UPCOMING
    else -> SAnime.UNKNOWN
}
