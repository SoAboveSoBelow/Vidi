package aniyomi.ui.browse.scroller

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import aniyomi.player.clip.ClipVideoResolver
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.service.GesturePreferences
import animiru.domain.player.service.PlayerPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
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
    private val resolver: ClipVideoResolver,
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

    /**
     * AM (BROWSE_SCROLLER_FRAME_WATCH): which instance each card's watchers
     * are pointed at.
     *
     * Watching is per card AND per instance, because the pool reassigns
     * instances between cards. Without this, a card whose slot was taken kept
     * watchers running against an instance now holding someone else's file -
     * and they went on writing to that card: the other card's first rendered
     * frame became this card's placeholder, and the other card's aspect ratio
     * became this card's height, measured and cached for the session so it
     * could never be corrected.
     */
    private val watchOwners = mutableMapOf<Long, ScrollerPlayer>()

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
        // AM (BROWSE_SCROLLER_FULLSCREEN_HOLDS_ACTIVE) -->
        // Nothing takes the active card from a card that is expanded.
        //
        // The feed goes on composing underneath the fullscreen view, and a
        // rotation re-measures it - so the viewport settled on a different
        // card, that card became active, and the active card is the one that
        // plays: the expanded clip was paused and some other card started
        // playing behind it. Which card the viewport happens to cover while
        // something is expanded over it is not a statement about what the
        // user is watching.
        if (fullscreenAnimeId != null && animeId != fullscreenAnimeId) return
        // <-- AM (BROWSE_SCROLLER_FULLSCREEN_HOLDS_ACTIVE)
        if (activeAnimeId == animeId) return
        activeAnimeId = animeId
        // AM (BROWSE_SCROLLER_CONTROLS): the controls belong to the card they
        // were opened on, and that card is no longer the one being looked at.
        if (controlsForAnimeId != animeId) hideControls()
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

        val playable = resolver.resolve(anime, source) as? ClipVideoResolver.Result.Playable
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
        // AM (BROWSE_SCROLLER_RETENTION): this card is back before its
        // retention ran out, so there is nothing to tear down and nothing to
        // reload - the instance below is still holding its file.
        retainJobs.remove(anime.id)?.cancel()
        val player = pool.acquire(anime.id, onEvict = ::onSlotEvicted)
        poolRevision++
        player.setMuted(isMuted, mayHoldAudioFocus = anime.id == activeAnimeId)

        // AM (BROWSE_SCROLLER_FRAME_WATCH) -->
        // Before the already-loaded check below, not after it.
        //
        // The frame-ready flag this sets is the only thing that takes the
        // placeholder off a card, and it is set nowhere else. Sitting under
        // that check, it was skipped by exactly the cards that adopt an
        // instance which already holds their file - which the retention above
        // makes the ordinary way back to a card rather than a rare one - so
        // the card played underneath a cover it could never drop. The same
        // mistake one gate further down is what the comment below records.
        //
        // Idempotent per instance, so the card's own effect re-running (it is
        // keyed on whether the card has a player, which this call's own
        // poolRevision bump flips) re-affirms the watchers instead of
        // cancelling live ones and starting again.
        watchPlayback(anime.id, player)
        // <-- AM (BROWSE_SCROLLER_FRAME_WATCH)

        if (player.isLoaded) {
            applyPlayState()
            return
        }

        val playable = resolver.resolve(anime, source) as? ClipVideoResolver.Result.Playable
            ?: return

        // The card may have been disposed while that resolved, in which case
        // its player is gone and loading would be pointed at a closed
        // instance - which is what made cards stay blank.
        if (pool.get(anime.id) !== player) return

        // The watchers were started above, before the already-loaded check and
        // therefore before this load is issued, which is what they need: see
        // watchPlayback, whose subscription has to exist before the event it
        // waits for is emitted. Previously they were started here, after the
        // resolve - gated first on the ratio being unknown, then on the file
        // not being loaded - and each of those gates in turn left the
        // frame-ready flag unset on some class of card, with a placeholder
        // drawn over its playing video. There is no gate left to get wrong.
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
        // AM (BROWSE_SCROLLER_FRAME_WATCH): already watching this card ON THIS
        // INSTANCE, so there is nothing to restart. Identity, not presence: a
        // card whose instance was reassigned underneath it has watchers
        // pointed at another card's file and must have them rebuilt.
        if (watchOwners[animeId] === player) return
        watchJobs.remove(animeId)?.forEach { it.cancel() }
        watchOwners[animeId] = player
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
            // AM (CLIP_RELOAD_ONCE): it played, so the latch is released and
            // a later failure on this card gets its own reload.
            reloadedOnce.remove(animeId)
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

        // AM (CLIP_RELOAD_ONCE) -->
        // mpv saying the file failed is what starts a card over, and nothing
        // else does.
        //
        // The timeout this replaces - "no frame after six seconds, so reload"
        // - punished a card that was merely slow: a large clip on a weak
        // connection renders late and was restarted for it, which is the one
        // thing a reload must not do. An EndFile carrying a file_error is not
        // a guess about whether something is wrong, and it is the same signal
        // the player acts on (see PlayerViewModel.endFile).
        //
        // Releasing the instance is the whole action. The card's own effect is
        // keyed on whether it has a player, so losing one makes it ask for
        // another - the self-healing path already documented at that call
        // site - and because a failed resolution is no longer cached, that ask
        // resolves afresh. Once per load, as the player allows itself.
        jobs += scope.launch {
            player.player.eventFlow
                .filterIsInstance<MPVPlayer.Event.EndFile>()
                .collect { event ->
                    if (event.node.asMap()?.get("file_error") == null) return@collect
                    if (pool.get(animeId) !== player) return@collect
                    if (!reloadedOnce.add(animeId)) return@collect
                    // Off this job, because hardRelease cancels this card's
                    // watchers - this one among them - and must not be running
                    // inside one of them when it does.
                    scope.launch {
                        resolver.invalidate(animeId)
                        hardRelease(animeId)
                    }
                }
        }
        // <-- AM (CLIP_RELOAD_ONCE)

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

    // AM (CLIP_RELOAD_ONCE) -->
    /**
     * Cards that have already been given one reload for this load.
     *
     * A latch, not a count, and the same one the player keeps: see
     * PlayerViewModel's currentFileReachedEof, which lets a failure after a
     * file has played re-resolve exactly once and then falls through to
     * ordinary error handling. Cleared when the card renders, so its next
     * failure gets its own reload.
     */
    private val reloadedOnce = mutableSetOf<Long>()

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

    // AM (BROWSE_SCROLLER_RETENTION) -->
    /** Per-card timers: how long a card's file is kept after the card goes. */
    private val retainJobs = mutableMapOf<Long, Job>()

    /**
     * Called by a card leaving the window, or leaving composition entirely.
     *
     * The card's file is kept open and paused for [RETAIN_MS] rather than
     * stopped outright, because the commonest way for a card to leave
     * composition is the user opening the entry it is showing - and coming
     * straight back to a card that has to resolve, load and decode again is
     * the feed losing its place for several seconds over a few seconds away.
     *
     * Nothing is leaked by waiting: the pool reclaims a retained slot before
     * it takes one from a card on screen (see ScrollerPlayerPool.acquire), so
     * scrolling on is never slowed by what was left behind, and the timer
     * below is what ends it when the user does not come back.
     */
    fun release(animeId: Long) {
        if (pool.get(animeId) == null) return
        if (animeId == fullscreenAnimeId) return
        if (retainJobs.containsKey(animeId)) return

        pool.retain(animeId)
        poolRevision++
        if (animeId == activeAnimeId) progressJob?.cancel()
        if (animeId == controlsForAnimeId) hideControls()

        retainJobs[animeId] = scope.launch {
            delay(RETAIN_MS)
            retainJobs.remove(animeId)
            hardRelease(animeId)
        }
    }

    /** The pool took a retained card's slot for someone else. */
    private fun onSlotEvicted(animeId: Long) {
        retainJobs.remove(animeId)?.cancel()
        hardRelease(animeId)
    }

    /**
     * Actually hands the instance back - see ScrollerPlayerPool, which keeps
     * it alive to be loaded with something else rather than destroying it.
     *
     * This card's own watchers are cancelled first, so none of them can call
     * into an instance that is being stopped underneath them.
     */
    private fun hardRelease(animeId: Long) {
        watchJobs.remove(animeId)?.forEach { it.cancel() }
        // AM (BROWSE_SCROLLER_FRAME_WATCH)
        watchOwners.remove(animeId)
        framesReady.remove(animeId)
        MediaProxyServer.release(proxyOwner(animeId))
        pool.release(animeId)
        poolRevision++
    }
    // <-- AM (BROWSE_SCROLLER_RETENTION)

    suspend fun fetchDetails(anime: Anime, source: AnimeSource) = detailsFetcher.fetch(anime, source)

    fun toggleMuted() {
        val muted = !isMuted
        isMuted = muted
        // AM (BROWSE_SCROLLER_MUTE_PERSISTS): written through to the session,
        // which is where a newly built playback reads it from. Nothing wrote
        // it before, so ScrollerSession.isMuted stayed at its initial true
        // however often the feed was unmuted - and every playback built after
        // that (opening an entry and coming back, the process going to the
        // background) started muted again and then disagreed with the button
        // the user was looking at.
        ScrollerSession.isMuted = muted
        // Every instance, not just the playing one: an instance paused now is
        // the one that will be playing shortly.
        pool.forEach { id, player ->
            player.setMuted(muted, mayHoldAudioFocus = id == activeAnimeId)
        }
    }

    // AM (BROWSE_SCROLLER_CONTROLS) -->
    /**
     * The card whose transport controls are up, if any.
     *
     * A tap used to pause outright, which made the commonest gesture in the
     * feed the one no other player gives it: everywhere else a tap asks for
     * the controls and the controls decide what happens. Only one card's are
     * ever up, because only one card is ever being looked at.
     */
    var controlsForAnimeId by mutableStateOf<Long?>(null)
        private set

    /** The card expanded to fullscreen, if any - see ScrollerFullscreenPlayer. */
    var fullscreenAnimeId by mutableStateOf<Long?>(null)
        private set

    /** True while the seekbar's thumb is being dragged. */
    var isSeeking by mutableStateOf(false)
        private set

    /** Where the thumb is during a drag, which is not yet where the file is. */
    var seekPosition by mutableFloatStateOf(0f)
        private set

    /** The controlled card's position, duration and play state, polled while shown. */
    var controlsPosition by mutableFloatStateOf(0f)
        private set
    var controlsDuration by mutableFloatStateOf(0f)
        private set
    var controlsReadAhead by mutableFloatStateOf(0f)
        private set
    var controlsPaused by mutableStateOf(false)
        private set

    private var controlsPollJob: Job? = null

    /** How long the controls wait before hiding, the player's own setting. */
    val controlsTimeToDisappearMs: Int get() = playerPreferences.playerTimeToDisappear.get()

    /**
     * A single tap, by where it landed.
     *
     * [onPlayPauseSpot] is a tap where the play/pause button sits, and it
     * pauses or resumes outright with no controls raised: it is the gesture
     * the feed is used with most, and putting a panel of chrome over the clip
     * every time someone wants to stop it is the thing that makes a feed
     * tiring.
     *
     * Everywhere else raises the controls, or puts them away again. That is
     * the whole rest of the card rather than the outer thirds it used to be:
     * the middle third was a band the full height of the clip, so a tap on
     * the seekbar's own row - someone wanting to see where they are - paused
     * instead of showing them.
     */
    fun onTap(animeId: Long, onPlayPauseSpot: Boolean) {
        if (onPlayPauseSpot) {
            togglePlayPause(animeId)
            return
        }
        if (controlsForAnimeId == animeId) hideControls() else showControls(animeId)
    }

    fun showControls(animeId: Long) {
        if (pool.get(animeId) == null) return
        controlsForAnimeId = animeId
        startControlsPoll(animeId)
    }

    fun hideControls() {
        controlsForAnimeId = null
        isSeeking = false
        // The fullscreen player draws the same readouts, so the poll stops
        // only when nothing is left to read them.
        if (fullscreenAnimeId == null) stopControlsPoll()
    }

    /**
     * Reads position, duration and play state off the card being controlled.
     *
     * Polled rather than observed: mpv's own property events are per-instance
     * and this needs one card's at a time, so a poll that follows whichever
     * card is being controlled is both simpler and the thing that stops when
     * nobody is looking.
     */
    private fun startControlsPoll(animeId: Long) {
        controlsPollJob?.cancel()
        controlsPollJob = scope.launch {
            while (true) {
                val player = pool.get(animeId)
                if (player == null) {
                    hideControls()
                    exitFullscreen()
                    return@launch
                }
                if (!isSeeking) controlsPosition = player.positionSeconds
                controlsDuration = player.durationSeconds
                controlsReadAhead = player.positionSeconds + player.readAheadSeconds
                controlsPaused = player.isPaused
                delay(CONTROLS_TICK_MS)
            }
        }
    }

    private fun stopControlsPoll() {
        controlsPollJob?.cancel()
        controlsPollJob = null
    }

    /** The controls' own play/pause button, which is now the only tap that pauses. */
    fun togglePlayPause(animeId: Long) {
        val player = pool.get(animeId) ?: return
        player.togglePaused()
        controlsPaused = player.isPaused
    }

    /** Dragging the thumb: the readout follows the finger, the file follows the thumb. */
    fun onSeek(animeId: Long, seconds: Float) {
        isSeeking = true
        seekPosition = seconds
        controlsPosition = seconds
        pool.get(animeId)?.seekTo(seconds)
    }

    fun onSeekFinished(animeId: Long, seconds: Float) {
        pool.get(animeId)?.seekTo(seconds)
        controlsPosition = seconds
        isSeeking = false
    }

    /**
     * Expands a card to the real player's own controls over its own instance.
     *
     * Nothing is handed to PlayerActivity: a feed's episodes are transient and
     * never written to the database (see ClipVideoResolver), and a card's
     * separate audio track and per-source http headers have no place to travel
     * on an external playback request. The surface moves instead - mpv keeps
     * its SurfaceTexture across views by design (see MpvSurface's
     * PERSISTENT_SURFACE_ARCHITECTURE notes), so the same instance goes on
     * playing the same frame at full size.
     */
    fun enterFullscreen(animeId: Long) {
        if (pool.get(animeId) == null) return
        fullscreenAnimeId = animeId
        // AM (BROWSE_SCROLLER_FULLSCREEN_HOLDS_ACTIVE): the expanded card is
        // the one being watched, whatever the feed's own viewport says, and
        // setActive refuses to move it for as long as this is set.
        if (activeAnimeId != animeId) {
            activeAnimeId = animeId
            applyPlayState()
        }
        controlsForAnimeId = null
        startControlsPoll(animeId)
    }

    fun exitFullscreen() {
        if (fullscreenAnimeId == null) return
        fullscreenAnimeId = null
        isSeeking = false
        if (controlsForAnimeId == null) stopControlsPoll()
    }
    // <-- AM (BROWSE_SCROLLER_CONTROLS)

    /**
     * The same preferences the real player's gestures use, so a double tap in
     * the feed does what a double tap in the player does - including doing
     * nothing, where that is what the user set.
     *
     * AM (BROWSE_SCROLLER_CONTROLS): except in the centre, which expands the
     * card to fullscreen. The centre gesture's own default is PlayPause, and
     * pausing is what the controls are now for.
     */
    fun onDoubleTap(animeId: Long, zone: TapZone) {
        val player = pool.get(animeId) ?: return
        if (zone == TapZone.Center) {
            enterFullscreen(animeId)
            return
        }
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
    private fun cacheableUrl(animeId: Long, result: ClipVideoResolver.Result.Playable): String =
        // AM (CLIP_PLAYBACK): shared with the real player, under one content
        // key - see ClipVideoResolver.cacheableUrl.
        ClipVideoResolver.cacheableUrl(
            context = context,
            retainMaxBytes = playerPreferences.retainRecentEpisodeMediaMaxBytes.get(),
            animeId = animeId,
            playable = result,
            owner = proxyOwner(animeId),
        )

    private fun proxyOwner(animeId: Long) = "browse-scroller-$animeId"

    internal fun pauseAll() = pool.forEach { _, player -> player.pause() }

    internal fun releaseAll() {
        watchJobs.values.flatten().forEach { it.cancel() }
        watchJobs.clear()
        // AM (BROWSE_SCROLLER_FRAME_WATCH)
        watchOwners.clear()
        // AM (BROWSE_SCROLLER_RETENTION)
        retainJobs.values.forEach { it.cancel() }
        retainJobs.clear()
        progressJob?.cancel()
        // AM (BROWSE_SCROLLER_CONTROLS)
        controlsPollJob?.cancel()
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
    // AM (BROWSE_SCROLLER_SESSION_INVALIDATION) -->
    // Keyed on the session's revision as well as the source.
    //
    // The process going to the background releases every instance and throws
    // the playback away (ScrollerSession.clear), but it does not destroy the
    // activity - so a feed on screen when that happens comes back still
    // holding the state it remembered, with a cancelled scope and released
    // mpv instances behind it. Reading the revision here is what makes that
    // invalidation reach this composition: it recomposes, builds a fresh
    // state, and the cards acquire again.
    val revision = ScrollerSession.revision
    // <-- AM (BROWSE_SCROLLER_SESSION_INVALIDATION)
    // Every composable call below runs unconditionally, whatever [source] is:
    // an early return before remember()/DisposableEffect() would change the
    // call site's slot layout if source ever went from null to non-null.
    return remember(source?.id, revision) {
        source?.let {
            ScrollerSession.playback(it.id) {
                val graph = context.appGraph
                val videoOutput = if (graph.decoderPreferences.gpuNext.get()) "gpu-next" else "gpu"
                ScrollerPlaybackState(
                    pool = ScrollerPlayerPool(graph.mpvPlayerFactory, videoOutput),
                    resolver = ClipVideoResolver(
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

// AM (BROWSE_SCROLLER_CONTROLS)
/** How often the controls' readouts are refreshed while they are up. */
private const val CONTROLS_TICK_MS = 250L

// AM (BROWSE_SCROLLER_RETENTION)
/**
 * How long a card's file stays open after the card goes away.
 *
 * Long enough to cover opening an entry, reading it and coming back, which is
 * the trip this exists for; short enough that a feed left on an entry is not
 * still holding decoders when the user returns to something else.
 */
private const val RETAIN_MS = 120_000L

private const val FRAME_POLL_ATTEMPTS = 40
private const val FRAME_POLL_INTERVAL_MS = 150L

private const val ASPECT_POLL_ATTEMPTS = 20
private const val ASPECT_POLL_INTERVAL_MS = 150L
// <-- AM (BROWSE_SCROLLER)
