package aniyomi.ui.browse.scroller

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.service.GesturePreferences
import animiru.domain.player.service.PlayerPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.media.MediaCache
import eu.kanade.tachiyomi.data.media.MediaProxyServer
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.domain.anime.model.Anime

// AM (BROWSE_SCROLLER) -->
/**
 * Playback for the browse scroller.
 *
 * Each card owns its own mpv instance for as long as it is near the card being
 * looked at, holding its video paused on the first frame, so arriving at a
 * card is an unpause of something already on screen.
 *
 * Ownership sits with the card, deliberately. A previous version had this
 * class keep a window of instances and release whatever fell out of it, and
 * every symptom that produced came from that one decision: a card's player
 * could be released while a load for it was still resolving, so the load
 * landed on a closed instance and nothing ever played; a card could draw a
 * surface for an instance that had just been released, which is a black box;
 * and scrolling back could leave a card holding no instance at all. A card
 * acquiring and releasing its own player cannot get into any of those states,
 * because the thing that decides a player should exist is the same thing that
 * draws it.
 */
class ScrollerPlaybackState internal constructor(
    private val pool: ScrollerPlayerPool,
    private val resolver: ScrollerVideoResolver,
    private val detailsFetcher: ScrollerDetailsFetcher,
    private val context: Context,
    private val playerPreferences: PlayerPreferences,
    private val gesturePreferences: GesturePreferences,
    /**
     * MPVPlayer takes this as a constructor argument and does not keep it, so
     * it is carried here for MpvSurface, which needs the same value.
     */
    val videoOutput: String,
) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var progressJob: Job? = null

    /**
     * Per-card watchers, so a card handing its instance back takes its own
     * polling with it. Left running, they go on reading properties from an
     * instance that has been stopped and reassigned to a different card.
     */
    private val watchJobs = mutableMapOf<Long, MutableList<Job>>()

    /** The card being looked at, which is the only one playing. */
    var activeAnimeId by mutableStateOf<Long?>(null)
        private set

    /** Bumped when a card's player appears or goes away, so cards redraw. */
    var poolRevision by mutableIntStateOf(0)
        private set

    var isMuted by mutableStateOf(ScrollerSession.isMuted)
        private set

    /**
     * Each card's media aspect ratio, so the box is as tall as its content is
     * at the screen's width.
     *
     * Best source first: a listing that states its dimensions
     * ([ScrollerFeedMedia]) gives this before anything loads. Otherwise the
     * card's own instance is asked once its file is open, and the cover's own
     * shape stands in until then.
     */
    val aspectRatios = mutableStateMapOf<Long, Float>().apply {
        putAll(ScrollerSession.aspectRatios)
    }

    /**
     * Ids whose ratio came from the media itself rather than from a cover.
     *
     * Compose-observable, because the initial gate needs to know when enough
     * cards have been measured to start drawing, and a plain set cannot be
     * observed.
     */
    private val measured = mutableStateMapOf<Long, Boolean>().apply {
        ScrollerSession.aspectRatios.keys.forEach { put(it, true) }
    }

    /** True once a card's height comes from its media rather than its cover. */
    fun isMeasured(animeId: Long): Boolean = measured[animeId] == true

    /**
     * Cards whose instance has actually rendered something.
     *
     * A TextureView with nothing decoded into it yet is an opaque black
     * rectangle, and it is composed as soon as the player exists - so drawing
     * it before this is what made cards further down the feed show as black
     * screens. The cover stays on top until the card is in here.
     */
    private val framesReady = mutableStateMapOf<Long, Boolean>()

    fun hasFrame(animeId: Long): Boolean = framesReady[animeId] == true

    fun playerFor(animeId: Long): ScrollerPlayer? {
        // Reading poolRevision is what subscribes a card to the pool changing.
        @Suppress("UNUSED_EXPRESSION")
        poolRevision
        return pool.get(animeId)
    }

    fun onCoverAspectRatio(animeId: Long, ratio: Float) {
        if (!ratio.isFinite() || ratio <= 0f) return
        if (isMeasured(animeId) || aspectRatios.containsKey(animeId)) return
        aspectRatios[animeId] = ratio
    }

    private fun setAspectRatio(animeId: Long, ratio: Float) {
        measured[animeId] = true
        aspectRatios[animeId] = ratio
        ScrollerSession.aspectRatios[animeId] = ratio
    }

    fun setActive(animeId: Long?) {
        if (activeAnimeId == animeId) return
        activeAnimeId = animeId
        applyPlayState()
    }

    /** Exactly one instance plays: the card being looked at, if it is loaded. */
    private fun applyPlayState() {
        val active = activeAnimeId
        pool.forEach { id, player ->
            // Mute state before play state: only the active card may hold
            // audio focus, and a non-active card holding it is what paused the
            // playing one for good.
            player.setMuted(isMuted, mayHoldAudioFocus = id == active)
            if (id == active) player.play() else player.pause()
        }

        progressJob?.cancel()
        val activePlayer = active?.let { pool.get(it) } ?: return
        progressJob = scope.launch {
            while (true) {
                delay(PROGRESS_TICK_MS)
                activePlayer.notePosition()
            }
        }
    }

    /**
     * Works out how tall [anime]'s card should be, without giving it a player.
     *
     * Run over a far wider range than the players are, because this is what
     * decides geometry and geometry has to be right before a card is seen. A
     * player measures its own video exactly, but players exist only either
     * side of the card being looked at, so leaving it to them means every card
     * keeps the shape of its cover until the moment it is reached and then
     * resizes under the user. Resolution is cached, and the probe reads a
     * container header or a playlist rather than decoding, so this costs no
     * decoder and no playback.
     */
    suspend fun prepareGeometry(anime: Anime, source: AnimeSource) {
        if (isMeasured(anime.id)) return

        // Best case: the listing already stated it, and nothing is fetched.
        ScrollerFeedMedia.from(anime)?.aspectRatio?.let {
            setAspectRatio(anime.id, it)
            return
        }

        val playable = resolver.resolve(anime, source) as? ScrollerVideoResolver.Result.Playable
            ?: return
        if (isMeasured(anime.id)) return
        ScrollerAspectProbe.probe(playable.url, playable.headers, playable.httpSource?.client)
            ?.let { setAspectRatio(anime.id, it) }
    }

    /**
     * Gives [anime] a player and loads its video, held on the first frame.
     *
     * Called by the card itself, and safe to call again: a card already
     * holding a loaded player does nothing.
     */
    suspend fun acquire(anime: Anime, source: AnimeSource) {
        val player = pool.acquire(anime.id)
        poolRevision++
        player.setMuted(isMuted, mayHoldAudioFocus = anime.id == activeAnimeId)

        if (player.isLoaded) {
            applyPlayState()
            return
        }

        val playable = resolver.resolve(anime, source) as? ScrollerVideoResolver.Result.Playable
            ?: return

        // The card may have been disposed while that resolved, in which case
        // its player is gone and loading would be pointed at a closed
        // instance - which is what made cards stay blank.
        if (pool.get(anime.id) !== player) return

        // Unconditional. This used to be skipped when the ratio was already
        // known, which quietly broke the feed once geometry moved into its own
        // pass: the ratio is now measured before a player exists, so the
        // skip became permanent - and because the frame-ready flag was set in
        // here too, it was never set at all, leaving a placeholder drawn over
        // every playing card. Watching playback and measuring the ratio are
        // two jobs and are no longer gated together.
        watchPlayback(anime.id, player)

        player.loadPaused(
            key = anime.id,
            url = cacheableUrl(anime.id, playable),
            audioUrl = playable.audioUrl,
            httpHeaderFields = playable.httpHeaderFields,
        )
        applyPlayState()
    }

    /**
     * Starts watching for the file to open BEFORE the load is issued.
     *
     * MPVPlayer's event flow has no replay buffer, so a collector that
     * subscribes after the event has been emitted never sees it. Subscribing
     * afterwards is why the measured aspect ratio almost never arrived and
     * cards kept the shape of their cover. The poll is a backstop for the case
     * where the event is missed anyway.
     */
    private fun watchPlayback(animeId: Long, player: ScrollerPlayer) {
        watchJobs.remove(animeId)?.forEach { it.cancel() }
        val jobs = mutableListOf<Job>()
        watchJobs[animeId] = jobs
        // The one that must always run: until this fires the surface is a
        // black rectangle, so the card shows a placeholder, and after it the
        // card shows its own video.
        jobs += scope.launch {
            // PlaybackRestart rather than FileLoaded: the file being open is
            // not a frame existing.
            player.player.eventFlow
                .filterIsInstance<MPVPlayer.Event.PlaybackRestart>()
                .first()
            framesReady[animeId] = true
            captureFirstFrame(animeId, player)
        }

        // Backstop for the same event being missed. The flow has no replay
        // buffer, and a missed PlaybackRestart means a placeholder sits over a
        // playing card for as long as it is on screen - which has happened
        // twice now, so it is worth polling for rather than trusting.
        jobs += scope.launch {
            repeat(FRAME_POLL_ATTEMPTS) {
                delay(FRAME_POLL_INTERVAL_MS)
                if (framesReady[animeId] == true) return@launch
                if (player.hasRenderedFrame()) {
                    framesReady[animeId] = true
                    captureFirstFrame(animeId, player)
                    return@launch
                }
            }
        }

        // The rest is only worth doing for a card whose shape is still a
        // guess from its cover.
        if (isMeasured(animeId)) return

        jobs += scope.launch {
            player.player.eventFlow
                .filterIsInstance<MPVPlayer.Event.FileLoaded>()
                .first()
            player.videoAspectRatio()?.let { setAspectRatio(animeId, it) }
        }
        jobs += scope.launch {
            repeat(ASPECT_POLL_ATTEMPTS) {
                delay(ASPECT_POLL_INTERVAL_MS)
                if (isMeasured(animeId)) return@launch
                player.videoAspectRatio()?.let {
                    setAspectRatio(animeId, it)
                    return@launch
                }
            }
        }
    }

    /**
     * The first frame each card rendered, kept so a card outside the window
     * shows where its video starts rather than its cover - which is what a
     * feed's placeholder should be.
     */
    fun firstFrame(animeId: Long) = ScrollerSession.firstFrame(animeId)

    /** Bumped when a frame is captured, so the cards showing one redraw. */
    var frameRevision by mutableIntStateOf(0)
        private set

    private fun captureFirstFrame(animeId: Long, player: ScrollerPlayer) {
        if (ScrollerSession.firstFrame(animeId) != null) return
        player.captureFrame()?.let { frame ->
            ScrollerSession.putFirstFrame(animeId, frame)
            frameRevision++
        }
    }

    /**
     * Called by a card leaving the window, or leaving composition entirely.
     *
     * Hands the instance back rather than destroying it - see
     * ScrollerPlayerPool - and cancels this card's own watchers first, so none
     * of them can call into an instance that is being stopped underneath them.
     */
    fun release(animeId: Long) {
        if (pool.get(animeId) == null) return
        watchJobs.remove(animeId)?.forEach { it.cancel() }
        framesReady.remove(animeId)
        MediaProxyServer.release(proxyOwner(animeId))
        pool.release(animeId)
        poolRevision++
        if (animeId == activeAnimeId) progressJob?.cancel()
    }

    suspend fun fetchDetails(anime: Anime, source: AnimeSource) = detailsFetcher.fetch(anime, source)

    fun toggleMuted() {
        val muted = !isMuted
        isMuted = muted
        // Every instance, not just the playing one: an instance paused now is
        // the one that will be playing shortly.
        pool.forEach { id, player ->
            player.setMuted(muted, mayHoldAudioFocus = id == activeAnimeId)
        }
    }

    /** A single tap on the playing card pauses or resumes it. */
    fun onTap(animeId: Long) {
        pool.get(animeId)?.togglePaused()
    }

    /**
     * The same preferences the real player's gestures use, so a double tap in
     * the feed does what a double tap in the player does - including doing
     * nothing, where that is what the user set.
     */
    fun onDoubleTap(animeId: Long, zone: TapZone) {
        val player = pool.get(animeId) ?: return
        val gesture = when (zone) {
            TapZone.Left -> gesturePreferences.leftDoubleTapGesture.get()
            TapZone.Center -> gesturePreferences.centerDoubleTapGesture.get()
            TapZone.Right -> gesturePreferences.rightDoubleTapGesture.get()
        }
        when (gesture) {
            SingleActionGesture.Seek -> {
                val amount = gesturePreferences.skipLengthPreference.get()
                player.seekBy(if (zone == TapZone.Left) -amount else amount)
            }
            SingleActionGesture.PlayPause -> player.togglePaused()
            // Switch, Custom, Screenshot and None all mean nothing here: there
            // is no episode to switch to in a feed, no key to send, and a
            // screenshot of a browse card is not worth doing.
            else -> Unit
        }
    }

    enum class TapZone { Left, Center, Right }

    /**
     * Routes the url through the app's own media cache proxy, so bytes already
     * fetched for a card are still there when the feed comes back to it.
     *
     * The cache key is the entry, not the episode: a feed's episodes are
     * transient objects never written to the database, so they have no id to
     * key on. The owner is per card as well, because several cards hold files
     * open at once and one shared owner would mean each new card tearing down
     * another's registration.
     */
    private fun cacheableUrl(animeId: Long, result: ScrollerVideoResolver.Result.Playable): String {
        val httpSource = result.httpSource ?: return result.url
        if (!result.url.startsWith("http")) return result.url
        if (result.url.contains("127.0.0.1") || result.url.contains("localhost")) return result.url

        // Same cache and same allowance as the player's own: whether anything
        // is retained at all is the "Episode cache limit" preference's
        // decision, not the feed's.
        val maxBytes = playerPreferences.retainRecentEpisodeMediaMaxBytes.get()
        MediaProxyServer.attachCache(MediaCache.get(context, maxBytes > 0, maxBytes))

        return MediaProxyServer.proxyUrlFor(
            url = result.url,
            headers = result.headers ?: httpSource.headers,
            httpClient = httpSource.client,
            cacheKey = "scroller-a$animeId",
            owner = proxyOwner(animeId),
        )?.second ?: result.url
    }

    private fun proxyOwner(animeId: Long) = "browse-scroller-$animeId"

    internal fun pauseAll() = pool.forEach { _, player -> player.pause() }

    internal fun releaseAll() {
        watchJobs.values.flatten().forEach { it.cancel() }
        watchJobs.clear()
        progressJob?.cancel()
        scope.cancel()
        pool.forEach { id, _ -> MediaProxyServer.release(proxyOwner(id)) }
        pool.releaseAll()
    }
}

/**
 * Builds the scroller's playback for as long as [source] is being browsed.
 */
@Composable
fun rememberScrollerPlayback(source: AnimeSource?): ScrollerPlaybackState? {
    val context = LocalContext.current
    // Every composable call below runs unconditionally, whatever [source] is:
    // an early return before remember()/DisposableEffect() would change the
    // call site's slot layout if source ever went from null to non-null.
    return remember(source?.id) {
        source?.let {
            ScrollerSession.playback(it.id) {
                val graph = context.appGraph
                val videoOutput = if (graph.decoderPreferences.gpuNext.get()) "gpu-next" else "gpu"
                ScrollerPlaybackState(
                    pool = ScrollerPlayerPool(graph.mpvPlayerFactory, videoOutput),
                    resolver = ScrollerVideoResolver(
                        episodeLoader = graph.episodeLoader,
                        hosterLoader = graph.hosterLoader,
                    ),
                    detailsFetcher = ScrollerDetailsFetcher(graph.updateAnime),
                    context = context,
                    playerPreferences = graph.playerPreferences,
                    gesturePreferences = graph.gesturePreferences,
                    videoOutput = videoOutput,
                )
            }
        }
    }
}

/** How often a playing card's position is written down. */
private const val PROGRESS_TICK_MS = 1_000L

private const val FRAME_POLL_ATTEMPTS = 40
private const val FRAME_POLL_INTERVAL_MS = 150L

private const val ASPECT_POLL_ATTEMPTS = 20
private const val ASPECT_POLL_INTERVAL_MS = 150L
// <-- AM (BROWSE_SCROLLER)
