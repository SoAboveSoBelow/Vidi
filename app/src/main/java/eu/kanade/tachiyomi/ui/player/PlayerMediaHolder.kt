package eu.kanade.tachiyomi.ui.player

// AM (SERVICE_OWNED_PLAYER) -->
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
// <-- AM (SERVICE_OWNED_PLAYER)
// AM (BACKGROUND_SKIP_FIX) -->
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import animiru.domain.player.service.PlayerPreferences
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.components.HosterState
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
// <-- AM (BACKGROUND_SKIP_FIX)

/**
 * Owns the single, long-lived [MPVPlayer] instance and its [MediaSession].
 *
 * [PlayerMediaHolder] is the future home for everything that currently lives on
 * [PlayerViewModel] and dies with it - the mpv instance, the MediaSession, and
 * current playback/episode state. It's constructed once by
 * [PlayerBackgroundPlaybackService] and outlives any single [PlayerActivity]
 * instance, so reattaching a new or recreated Activity never has to fight over
 * player identity.
 *
 * AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX) -->
 * Ownership model, updated: the class doc above originally described
 * PlayerViewModel unconditionally constructing its own MPVPlayer at
 * construction time and handing it off via [adopt] once the (always-async)
 * Service bind completed, discarding it as an orphan if a canonical player
 * already existed - deferred as "the migration plan's step 3" at the time.
 * That gap turned out to be a genuine, confirmed source of native mpv/JNI
 * crashes: constructing a full native mpv context (MPVPlayer's own init{}
 * eagerly builds one, not a lightweight placeholder) only to immediately
 * discard it moments later, while the actual canonical player is concurrently
 * alive and active, is exactly the kind of rapid construct-then-destroy cycle
 * against shared native/OS state (audio focus, hardware decoder slots, the GL/
 * EGL output target) that isn't safe to assume is race-free. This happened on
 * literally every reopen after backgrounding, since the Service (and this
 * holder) are specifically kept alive across that transition.
 *
 * [current] closes that gap without the disruption of making `player`
 * genuinely async everywhere it's read throughout PlayerViewModel (a much
 * larger, riskier change) - PlayerBackgroundPlaybackService and this holder
 * run in the same process as the Activity, so there's no real reason
 * PlayerViewModel needs to wait for bindService()'s async Binder round-trip
 * just to find out whether a live holder already exists; a same-process
 * static reference answers that synchronously, at the exact point
 * PlayerViewModel's own property initializer needs to decide whether
 * constructing a new MPVPlayer is even necessary. [adopt]'s existing
 * first-wins/de-dup contract is unchanged and still runs as the safety net
 * for any case this synchronous check doesn't catch (e.g. two ViewModels
 * genuinely racing to construct at the same instant).
 * <-- AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX)
 */
class PlayerMediaHolder(
    private val context: Context,
    // AM (BACKGROUND_SKIP_FIX) -->
    private val getAnime: GetAnime = Injekt.get(),
    private val getEpisode: GetEpisode = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val playerPreferences: PlayerPreferences = Injekt.get(),
    // <-- AM (BACKGROUND_SKIP_FIX)
) {
    // AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX) -->
    companion object {
        /**
         * The currently-alive holder for this process, if any. Set the moment a
         * holder is constructed, cleared on [release] - self-managed rather than
         * requiring [PlayerBackgroundPlaybackService] to remember to do it, since
         * this holder's own construction/release lifecycle is already the
         * authoritative signal for whether a live session exists.
         */
        var current: PlayerMediaHolder? = null
            private set
    }
    // <-- AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX)

    init {
        // AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX) -->
        current = this
        // <-- AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX)
    }

    private var _player: MPVPlayer? = null
    val player: MPVPlayer
        get() = _player ?: error("PlayerMediaHolder.player accessed before any PlayerViewModel adopted into it")
    val mpv get() = player.mpv

    // AM (AUDIO_FOCUS_ORPHAN_FIX) -->
    /**
     * Must be checked by callers BEFORE calling adopt(), not after - adopt()
     * itself may mutate _player as a side effect of the very call being checked
     * against. PlayerViewModel.bindToService() uses this to know whether its own
     * adopt() call is resolving a genuinely first-ever adoption (audio focus
     * needs requesting) versus returning an already-established canonical player
     * from a prior session (which already holds focus from back when it was
     * originally constructed, and must not request it again).
     */
    val hasAdoptedPlayer: Boolean get() = _player != null
    // <-- AM (AUDIO_FOCUS_ORPHAN_FIX)

    var mediaSession: MediaSession? = null
        private set

    // AM (MEDIA_SESSION_FALLBACK_CALLBACK) -->
    /**
     * Installed on [mediaSession] whenever no [eu.kanade.tachiyomi.ui.player.PlayerActivity]
     * instance is currently attached (see [restoreFallbackCallback]). Without this, the
     * session's callback stays pointed at whichever Activity instance's
     * `setupMediaSessionCallback()` last set it - a closure over that instance's
     * [eu.kanade.tachiyomi.ui.player.PlayerViewModel], which is cleared the moment the
     * Activity is genuinely destroyed. Hardware/notification media-button presses
     * arriving in that gap were silently firing against a dead ViewModel: coroutines
     * launched in an already-cancelled viewModelScope never completing, leaving episode
     * navigation stuck mid-transition and this holder's own animeId/episodeId (used to
     * build the notification's reopen intent) never updated to match.
     *
     * This callback intentionally does the minimum that's safe to do without a live
     * ViewModel: play/pause operate on mpv directly (no DB/UI-state involvement needed).
     * Skip requests attempt an immediate resolve-and-load via [skipToAdjacentEpisode]
     * for the common case (direct-URL HTTP/local sources); anything that isn't
     * covered there (torrent sources, sources needing a local HTTP proxy server)
     * falls back to the original behavior - queued via [requestSkip] and applied for
     * real once a live Activity/ViewModel reattaches, through the full pipeline - see
     * PlayerActivity's onNewIntent().
     */
    private val fallbackCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            setPaused(false)
        }

        override fun onPause() {
            setPaused(true)
        }

        // AM (BACKGROUND_SKIP_FIX) -->
        // Attempt the skip for real, immediately, without needing a live Activity -
        // see skipToAdjacentEpisode(). Falls back to the original queue-until-reopen
        // behavior (requestSkip) if that fails or isn't supported for this specific
        // content (e.g. a torrent source, or one needing a local HTTP proxy server) -
        // nothing regresses versus the pre-existing behavior in that case.
        override fun onSkipToNext() {
            logcat(LogPriority.DEBUG) { "fallbackCallback.onSkipToNext fired" }
            holderScope.launch {
                if (!skipToAdjacentEpisode(next = true)) {
                    requestSkip(next = true)
                }
            }
        }

        override fun onSkipToPrevious() {
            logcat(LogPriority.DEBUG) { "fallbackCallback.onSkipToPrevious fired" }
            holderScope.launch {
                if (!skipToAdjacentEpisode(next = false)) {
                    requestSkip(next = false)
                }
            }
        }
        // <-- AM (BACKGROUND_SKIP_FIX)
    }

    /**
     * Hands [mediaSession]'s callback back to [fallbackCallback]. Called from
     * PlayerActivity.onDestroy() whenever this session is being preserved across the
     * Activity's destruction (background playback continuing) - the mirror image of
     * [ensureMediaSession]'s redirect-to-the-reattaching-instance behavior.
     */
    fun restoreFallbackCallback() {
        mediaSession?.setCallback(fallbackCallback)
    }

    /** Directly pauses/resumes mpv, independent of any ViewModel. Safe to call with no Activity attached. */
    fun setPaused(paused: Boolean) {
        _player?.mpv?.setPropertyBoolean("pause", paused)
        updateState { it.copy(paused = paused) }
    }

    private var pendingSkipDirection: Boolean? = null

    // AM (BACKGROUND_SKIP_FIX) -->
    /** Mirrored from PlayerActivity's own observer on the ViewModel's already-sorted/
     * filtered currentPlaylist - see PlayerActivity's BACKGROUND_SKIP_FIX observer. */
    private var playlistEpisodeIds: List<Long> = emptyList()

    fun updatePlaylist(episodeIds: List<Long>) {
        logcat(LogPriority.DEBUG) { "updatePlaylist: mirrored ${episodeIds.size} episode ids" }
        playlistEpisodeIds = episodeIds
    }

    // AM (BACKGROUND_SKIP_POSITION_MEMORY_FIX) -->
    /**
     * Session-local, Service-scoped equivalent of PlayerViewModel's own
     * recentEpisodePositions - that one only exists on the ViewModel, unreachable
     * from here, so flipping between episodes via background skip had nothing
     * remembering where you'd left off in one you'd already visited this session,
     * always restarting at 0 regardless.
     *
     * Now mirrors the ViewModel's own pruning too (see [pruneRecentPositions]) -
     * without it this just grew unbounded across a long backgrounded session with
     * many skips, since entries were only ever removed individually on actually
     * being consumed for a resume.
     */
    private data class RecentPosition(val positionMs: Long, val playlistIndex: Int)
    private val recentPositions = mutableMapOf<Long, RecentPosition>()

    private fun pruneRecentPositions(aroundPlaylistIndex: Int) {
        val maxSlots = playerPreferences.recentEpisodePositionSlots.get()
        recentPositions.entries.removeAll { (_, saved) ->
            kotlin.math.abs(saved.playlistIndex - aroundPlaylistIndex) > maxSlots
        }
    }
    // <-- AM (BACKGROUND_SKIP_POSITION_MEMORY_FIX)

    /**
     * Resolves and loads the next/previous episode directly, without needing a live
     * PlayerActivity/ViewModel - the actual fix for skip-while-backgrounded doing
     * nothing at all. Covers the common case only: a direct-URL HTTP or local
     * source. Deliberately does NOT attempt torrent-based sources or sources
     * requiring a local HTTP proxy server (Video.usesHttpServer()) - replicating
     * that handling correctly without the ability to test against real sources
     * risked silently mishandling it, so those fall back to the pre-existing
     * queue-until-reopen behavior instead (see the callers in [fallbackCallback]).
     *
     * Returns false on anything not resolved (source unsupported, resolution
     * failure, no player adopted) - callers fall back to [requestSkip].
     */
    private suspend fun skipToAdjacentEpisode(next: Boolean): Boolean {
        if (!hasAdoptedPlayer) {
            logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: no adopted player" }
            return false
        }
        val currentState = state.value
        val animeId = currentState.animeId ?: run {
            logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: holder state has no animeId" }
            return false
        }
        val currentEpisodeId = currentState.episodeId ?: run {
            logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: holder state has no episodeId" }
            return false
        }

        val playlist = playlistEpisodeIds
        val currentIndex = playlist.indexOf(currentEpisodeId)
        if (currentIndex == -1) {
            logcat(LogPriority.DEBUG) {
                "skipToAdjacentEpisode: episode $currentEpisodeId not in mirrored playlist " +
                    "(size=${playlist.size})"
            }
            return false
        }
        val newIndex = if (next) currentIndex + 1 else currentIndex - 1
        if (newIndex !in playlist.indices) {
            logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: newIndex $newIndex out of range (size=${playlist.size})" }
            return false
        }
        val targetEpisodeId = playlist[newIndex]

        // AM (BACKGROUND_SKIP_POSITION_MEMORY_FIX) -->
        // Remember where this episode was left, so flipping back to it later in the
        // same backgrounded session resumes correctly instead of restarting at 0 -
        // mirrors PlayerViewModel.rememberRecentEpisodePosition()'s same finished-
        // episode guard (don't cache a position at or past the end, which would
        // otherwise just replay the last second forever on return). Then prunes,
        // exactly like PlayerViewModel.changeEpisode() pairs remember+prune on
        // every switch - without this, entries only ever got removed individually
        // on being consumed, so the cache just grew unbounded across a long
        // backgrounded session with many skips.
        val currentDurationMs = currentState.durationMs.toLong()
        val currentPositionMs = currentState.positionMs.toLong()
        if (currentPositionMs > 0L && !(currentDurationMs > 0L && currentPositionMs >= currentDurationMs)) {
            recentPositions[currentEpisodeId] = RecentPosition(currentPositionMs, currentIndex)
        } else {
            recentPositions.remove(currentEpisodeId)
        }
        pruneRecentPositions(newIndex)
        // <-- AM (BACKGROUND_SKIP_POSITION_MEMORY_FIX)

        return try {
            val anime = getAnime.await(animeId) ?: run {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: getAnime($animeId) returned null" }
                return false
            }
            val episode = getEpisode.await(targetEpisodeId) ?: run {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: getEpisode($targetEpisodeId) returned null" }
                return false
            }
            val source = sourceManager.getOrStub(anime.source)

            val hosters = EpisodeLoader.getHosters(episode, anime, source)
            val hoster = hosters.firstOrNull() ?: run {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: no hosters returned" }
                return false
            }
            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)
            val videoList = (hosterState as? HosterState.Ready)?.videoList ?: run {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: hoster state not Ready ($hosterState)" }
                return false
            }
            val video = videoList.firstOrNull { it.preferred } ?: videoList.firstOrNull() ?: run {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: hoster returned an empty video list" }
                return false
            }
            val resolvedVideo = HosterLoader.getResolvedVideo(source, video) ?: video
            if (resolvedVideo.videoUrl.isEmpty()) {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: resolved video has an empty url" }
                return false
            }

            // Deliberately unsupported here - see this function's doc comment.
            if (resolvedVideo.usesHttpServer()) {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: video requires a local HTTP proxy server, unsupported" }
                return false
            }
            if (resolvedVideo.videoUrl.endsWith("torrent") || resolvedVideo.videoUrl.startsWith("magnet")) {
                logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: video is torrent-based, unsupported" }
                return false
            }

            val httpSource = source as? AnimeHttpSource
            if (httpSource != null) {
                val headers = (resolvedVideo.headers ?: httpSource.headers)
                    .toMultimap()
                    .mapValues { it.value.firstOrNull() ?: "" }
                val httpHeaderString = headers.entries.joinToString(",") {
                    it.key + ": " + it.value.replace(",", "\\,")
                }
                mpv.setOptionString("http-header-fields", httpHeaderString)
            }

            // AM (BACKGROUND_SKIP_AUDIO_FIX) -->
            // aid was originally "no", copied from PlayerViewModel.setVideo()'s
            // exact pattern - there, that's a deliberate placeholder: audio is
            // explicitly disabled on load, then separately, elsewhere (preference-
            // based track selection reacting to the file being loaded), the
            // ViewModel sets the real audio track index. This background-skip path
            // has no equivalent follow-up step, so "no" left audio permanently
            // disabled - confirmed directly in mpv's own log: "playback restart
            // complete @ 0.000000, audio=eof, video=playing" on every background
            // skip. "auto" (matching what vid already correctly uses) lets mpv pick
            // a reasonable default track itself instead of deliberately turning
            // audio off with nothing left to turn it back on.
            val mpvOpts = listOf(Pair("sid", "no"), Pair("aid", "auto"), Pair("vid", "auto"))
            // <-- AM (BACKGROUND_SKIP_AUDIO_FIX)
            val videoOptions = (resolvedVideo.mpvArgs + mpvOpts).joinToString(",") { (option, value) ->
                "$option=\"$value\""
            }
            val resolvedUrl = resolvedVideo.videoUrl.toUri().resolveUri(context) ?: resolvedVideo.videoUrl

            // AM (BACKGROUND_SKIP_POSITION_MEMORY_FIX) -->
            // Resume position priority mirrors PlayerViewModel.setVideo()'s own rules,
            // simplified: this session's own recent-positions cache first (flipping
            // back to an episode you were just on, however briefly, is the strongest
            // signal available), otherwise the DB-persisted last-seen position unless
            // the episode's already marked fully watched (matching the same
            // already-watched -> start at 0 rule the rest of the app uses).
            val resumePositionMs = recentPositions.remove(targetEpisodeId)?.positionMs
                ?: if (!episode.seen) episode.lastSecondSeen else 0L
            if (resumePositionMs > 0L) {
                player.mpv.command("set", "start", (resumePositionMs / 1000L).toString())
            }
            // <-- AM (BACKGROUND_SKIP_POSITION_MEMORY_FIX)

            // AM (AUDIO_BLIP_FIX) -->
            // Mirrors PlayerViewModel.loadFile()'s exact pattern: MediaCodec hwdec
            // needs an attached Surface to initialize a decoder session. Unlike that
            // function, this is never conditional here - background skip by
            // definition only ever runs with no live Activity/Surface at all, so
            // there's nothing to check.
            player.mpv.setOptionString("hwdec", "no")
            // <-- AM (AUDIO_BLIP_FIX)
            player.mpv.command("loadfile", resolvedUrl, "replace", "0", videoOptions)

            // AM (BACKGROUND_SKIP_LOAD_STUCK_FIX) -->
            // The ViewModel's own equivalent path relies on live reactive event-flow
            // collection (handlePlayerFlow(), wired only while a ViewModel exists) to
            // notice mpv's own file-loaded/pause-state transitions and react
            // accordingly - none of that is running here. Without an explicit resume,
            // whatever pause state mpv's new file starts in after a "replace" load is
            // whatever it stays in indefinitely - loaded and silent, never resumed.
            setPaused(false)
            logcat(LogPriority.DEBUG) {
                "skipToAdjacentEpisode: post-load mpv pause=${player.mpv.getPropertyBoolean("pause")}"
            }
            // <-- AM (BACKGROUND_SKIP_LOAD_STUCK_FIX)

            updateState {
                it.copy(
                    animeId = animeId,
                    episodeId = targetEpisodeId,
                    episodeTitle = episode.name,
                    positionMs = resumePositionMs.toInt(),
                    // AM (BACKGROUND_SKIP_DURATION_FIX) -->
                    // Reverted: resetting this to 0 here was meant to avoid ever
                    // showing a stale wrong duration, but instead made the
                    // lock-screen's seek bar disappear entirely rather than just
                    // show stale data - Android's media widget appears to treat
                    // duration=0 as "no seekable content" and hides the whole
                    // progress UI, worse than the staleness this was meant to fix.
                    // Left untouched (previous episode's value persists) until the
                    // real new duration arrives via the propFlow observer below -
                    // see its own logging for why that isn't happening reliably.
                    // <-- AM (BACKGROUND_SKIP_DURATION_FIX)
                )
            }
            logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: loaded episode $targetEpisodeId directly" }
            true
        } catch (e: Throwable) {
            logcat(LogPriority.DEBUG, e) { "skipToAdjacentEpisode: threw" }
            false
        }
    }
    // <-- AM (BACKGROUND_SKIP_FIX)

    /** Queues a skip request for the next Activity/ViewModel reattach to apply for real. */
    fun requestSkip(next: Boolean) {
        pendingSkipDirection = next
    }

    /** Returns and clears any pending skip request. Null if none is queued. */
    fun consumePendingSkip(): Boolean? {
        return pendingSkipDirection.also { pendingSkipDirection = null }
    }
    // <-- AM (MEDIA_SESSION_FALLBACK_CALLBACK)

    private val _state = MutableStateFlow(PlayerMediaState())
    val state = _state.asStateFlow()

    // AM (LIVE_POSITION_TRACKING) -->
    // Scoped to this holder, not any PlayerViewModel - cancelled in release() and
    // replaced with a fresh one on the next real adopt(). PlayerViewModel's own
    // position tracking (the per-second DB write in onSecondReached()) is launched
    // in viewModelScope, which gets cancelled the instant that ViewModel is
    // cleared - routine while this architecture is specifically designed to let
    // playback itself continue afterward via this Service-owned canonical player.
    // Any playback that happens after the old ViewModel dies but before a new one
    // attaches was going completely unobserved: never reflected here, and - since
    // the DB write lived in that same dead flow - never persisted either, leaving
    // episode.last_second_seen stale by however long the gap lasted. This observer
    // has zero business-logic dependencies (no DB, no repositories, no sync/tracker
    // logic) - it only keeps state.positionMs/durationMs current in memory, cheaply,
    // for as long as the player is alive, completely independent of any ViewModel.
    // PlayerViewModel.setVideo() prefers this over the DB value when resuming a
    // reattached live session - see its forceResumeFromLastPosition handling.
    // A var, not a val: release() can run on this same holder instance while it's
    // still alive and about to be reused by a fast reopen (see STALE_HOLDER_STATE_FIX
    // below) - reusing an already-cancelled scope there would silently no-op every
    // launchIn() in the next adopt() instead of throwing, so a fresh scope each real
    // adoption is what makes that reuse actually work rather than fail invisibly.
    private var holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // <-- AM (LIVE_POSITION_TRACKING)

    /**
     * Registers [existing] as this holder's player if none has been adopted yet, otherwise
     * returns the already-adopted one unchanged. Always returns the player callers should
     * actually use - callers must compare the result against what they passed in to detect
     * whether their own instance was accepted or discarded.
     */
    fun adopt(existing: MPVPlayer): MPVPlayer {
        if (_player == null) {
            _player = existing
            // AM (LIVE_POSITION_TRACKING) -->
            // Started only on the very first, real adoption - never for a later
            // duplicate instance's call, which just returns the already-adopted
            // player unchanged and shouldn't start a second observer against it.
            // Fresh scope in case a prior release() on this same holder instance
            // already cancelled the old one (see that var's doc comment).
            holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // AM (BACKGROUND_SEEKBAR_FIGHT_FIX) -->
            // This used to also update state.positionMs directly from mpv's
            // "time-pos" property-change events - a second, uncoordinated writer to
            // the exact same field the periodic timer below (BACKGROUND_SEEKBAR_TICK_FIX)
            // also writes to, independently and on a different cadence. Two writers
            // racing on the same field is what was causing both the visible seek-bar
            // rubberbanding AND inconsistent reads elsewhere (e.g. the recent-
            // positions cache occasionally saving whichever writer's value happened
            // to land last) - there should only be one source of truth for
            // positionMs, and the periodic timer already owns that job. This flow's
            // subscription itself is gone; the timer reads mpv's "time-pos" directly
            // each tick instead of relying on property-change events at all.
            // <-- AM (BACKGROUND_SEEKBAR_FIGHT_FIX)
            // AM (BACKGROUND_DURATION_DESYNC_FIX) -->
            // Also removed reliance on the "duration" property-change event, for the
            // same underlying reason: mpv only emits a change notification when the
            // value differs from what mpv ITSELF last reported - not from whatever
            // this holder's own durationMs last held. Going back to a previously-
            // visited episode whose duration mpv still has cached internally (never
            // actually cleared just because a different episode played in between)
            // doesn't look like a change from mpv's perspective, so no event fires,
            // and durationMs stays stuck at whatever the intervening episode's
            // duration was - "the timer desyncs and never updates" going backward
            // specifically. The periodic timer now reads "duration" directly each
            // tick too, an absolute read-and-compare against this holder's own
            // tracked value rather than trusting mpv's own change-notification
            // bookkeeping.
            // <-- AM (BACKGROUND_DURATION_DESYNC_FIX)
            // <-- AM (LIVE_POSITION_TRACKING)
            // AM (BACKGROUND_SKIP_METADATA_FIX) -->
            // Previously, PlaybackState and MediaMetadata were ONLY ever set from
            // PlayerActivity's own lifecycleScope-bound observers (viewModel.playbackData/
            // stateData) - meaning the lock-screen/notification's rich media controls
            // (title, artist, play/pause state, progress) stayed completely frozen at
            // whatever they showed right before backgrounding, even after a successful
            // background skip actually changed what's playing. updateEpisodeInfo() (see
            // PlayerBackgroundPlaybackService's own reactive observer) only refreshes the
            // notification's own plain text - this is the separate piece that actually
            // drives what the lock screen shows, sourced the same way position tracking
            // above is: directly off this holder's own state, with zero dependency on any
            // ViewModel/Activity existing.
            //
            // Deliberately does NOT set album art - that requires a live network image
            // fetch (see PlayerActivity's artwork observer), which is a meaningfully
            // bigger, riskier thing to replicate here; art simply stays whatever it was
            // last set to rather than attempting and possibly getting it wrong.
            // AM (BACKGROUND_SEEKBAR_FIGHT_FIX) -->
            // The reactive state.map{paused to positionMs}...onEach{ setPlaybackState }
            // observer that used to live here was a SECOND writer racing against the
            // periodic timer below - both pushing PlaybackState in close succession
            // with slightly different position/timestamp bases (the timer's own
            // updateState() call was itself triggering this reactive observer to fire
            // again immediately after). That's what was making the lock-screen seek
            // bar visibly jump back and forth. The periodic timer is now the sole
            // writer - see BACKGROUND_SEEKBAR_TICK_FIX below. Accepts up to ~1s of lag
            // reflecting a pause/resume toggle on the lock screen as the trade-off for
            // eliminating the double-write race entirely.
            // <-- AM (BACKGROUND_SEEKBAR_FIGHT_FIX)
            // AM (BACKGROUND_SEEKBAR_TICK_FIX) -->
            // Supplements the reactive observer above with an explicit 1-second
            // timer that reads mpv's own "time-pos" directly and pushes it to the
            // MediaSession - the reactive observer alone depends on "time-pos"
            // property-CHANGE events firing at some steady cadence, which may not
            // hold true while backgrounded with no active Surface/rendering target;
            // this guarantees a regular push to the lock screen regardless of
            // whether those change events are actually firing on any predictable
            // rhythm. Reads position directly rather than through state.positionMs
            // so it reflects mpv's live truth even if the reactive property-flow
            // observer genuinely isn't keeping state.positionMs current.
            // AM (BACKGROUND_MEDIASESSION_DOUBLE_WRITER_FIX) -->
            // Critical gap in the fix above: PlayerActivity has its OWN, original,
            // still-existing PlaybackState/MediaMetadata-pushing flow
            // (viewModel.playbackData/stateData observers, lifecycleScope-bound) that
            // runs the entire time the Activity is alive. This timer - and the
            // metadata/artwork observers below - were written assuming they'd only
            // matter while backgrounded, but they actually run unconditionally from
            // the moment of first adoption for the holder's ENTIRE lifetime,
            // including whenever the app is in the foreground with a live Activity.
            // That means every single session, not just background-skip ones, has
            // had TWO independent, uncoordinated writers pushing to the same
            // MediaSession simultaneously since this was added - the exact
            // double-writer bug BACKGROUND_SEEKBAR_FIGHT_FIX addressed for state
            // internally, just never actually eliminated at the MediaSession level.
            // Gating every push here behind !PlayerActivity.hasLiveInstance makes
            // this holder defer entirely to the Activity's own, already-correct flow
            // whenever one exists, and only take over once it's genuinely gone -
            // internal state tracking (updateState calls) stays unconditional, since
            // nothing else writes positionMs/durationMs and LIVE_POSITION_TRACKING's
            // reattach-resume logic needs it current regardless of Activity liveness.
            // <-- AM (BACKGROUND_MEDIASESSION_DOUBLE_WRITER_FIX)
            // AM (BACKGROUND_METADATA_DURATION_SYNC_FIX) -->
            // Title/artist used to update via a SEPARATE reactive observer
            // (distinctUntilChanged on state's title fields), firing the instant
            // skipToAdjacentEpisode() changed episodeTitle - independent of, and
            // faster than, this timer's own duration correction, which only lands on
            // its next 1-second tick. That split meant every skip pushed the NEW
            // title paired with the PREVIOUS episode's still-stale duration for
            // roughly a second - and skipping faster than that window let the
            // mismatch persist indefinitely, never catching up. Reading and pushing
            // title/artist/duration together, every tick, in the same call, makes
            // them atomic - there's no longer a window where one has updated and the
            // other hasn't.
            // <-- AM (BACKGROUND_METADATA_DURATION_SYNC_FIX)
            holderScope.launch {
                while (true) {
                    delay(1000)
                    val positionMs = (player.mpv.getPropertyInt("time-pos") ?: continue) * 1000
                    val durationMs = (player.mpv.getPropertyInt("duration") ?: continue) * 1000
                    val paused = player.mpv.getPropertyBoolean("pause") ?: false
                    val current = state.value
                    updateState { it.copy(positionMs = positionMs, durationMs = durationMs) }
                    if (PlayerActivity.hasLiveInstance) continue
                    mediaSession?.setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(
                                PlaybackState.ACTION_PLAY or
                                    PlaybackState.ACTION_PAUSE or
                                    PlaybackState.ACTION_STOP or
                                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                    PlaybackState.ACTION_SKIP_TO_NEXT,
                            )
                            .setState(
                                if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                                positionMs.toLong(),
                                1f,
                            )
                            .build(),
                    )
                    if (current.animeTitle.isNotEmpty() || current.episodeTitle.isNotEmpty()) {
                        mediaSession?.setMetadata(
                            MediaMetadata.Builder()
                                .putString(MediaMetadata.METADATA_KEY_TITLE, current.episodeTitle)
                                .putString(MediaMetadata.METADATA_KEY_ARTIST, current.animeTitle)
                                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
                                .build(),
                        )
                    }
                }
            }
            // <-- AM (BACKGROUND_SEEKBAR_TICK_FIX)
            // AM (BACKGROUND_AUTOPLAY_FIX) -->
            // Mirrors PlayerViewModel.eofReached() - which only ever reacted to
            // player.eventFlow while a ViewModel existed to collect it
            // (wirePlayerFlows(), viewModelScope-bound). player.eventFlow itself
            // lives on the MPVPlayer object, not the ViewModel, so it's just as
            // observable from here - reusing the already-working
            // skipToAdjacentEpisode() (see MEDIA_SESSION_FALLBACK_CALLBACK) to
            // actually perform the advance once autoplay fires with no Activity
            // around to do it. playerPreferences read directly, same preference
            // store PlayerViewModel itself reads from, no ViewModel needed.
            existing.eventFlow
                .filterIsInstance<MPVPlayer.Event.EOF>()
                .filter { it.value }
                .onEach {
                    // AM (BACKGROUND_MEDIASESSION_DOUBLE_WRITER_FIX) -->
                    // PlayerViewModel's own eofReached() already handles autoplay
                    // whenever an Activity is alive. Without this guard, both fire on
                    // every episode-end while foregrounded too - not just a wasted
                    // duplicate call like the notification/metadata cases above, but
                    // a genuine race: two independent attempts to advance to the next
                    // episode at once, one through the full ViewModel pipeline and
                    // one through this simplified background path.
                    if (PlayerActivity.hasLiveInstance) return@onEach
                    // <-- AM (BACKGROUND_MEDIASESSION_DOUBLE_WRITER_FIX)
                    if (playerPreferences.autoplayEnabled.get()) {
                        skipToAdjacentEpisode(next = true)
                    }
                }
                .launchIn(holderScope)
            // <-- AM (BACKGROUND_AUTOPLAY_FIX)
            // AM (BACKGROUND_ARTWORK_FIX) -->
            // Kept deliberately separate from the fast title/artist/duration observer
            // above - that one exists specifically to update instantly, with no
            // network dependency (see REOPEN_TARGET_STALENESS_FIX's reasoning, which
            // applies identically here). This one does the slow part: fetching
            // episode/anime artwork over the network, same ImageRequest/Coil pattern
            // PlayerActivity's own (Activity-only) artwork flow already used. Re-reads
            // title/artist/duration fresh from state at the moment the fetch
            // completes, rather than closing over the values from when the fetch
            // started, since setMetadata() fully replaces the previous metadata
            // object - a stale closure here would silently revert a title/duration
            // update that happened while the fetch was in flight.
            state
                .map { it.animeId to it.episodeId }
                .distinctUntilChanged()
                .onEach { (animeId, episodeId) ->
                    if (PlayerActivity.hasLiveInstance) return@onEach
                    if (animeId == null || episodeId == null) return@onEach
                    val anime = getAnime.await(animeId) ?: return@onEach
                    val episode = getEpisode.await(episodeId) ?: return@onEach
                    val artwork = try {
                        val request = ImageRequest.Builder(context)
                            .data(episode.previewUrl?.takeIf { it.isNotBlank() } ?: anime)
                            .size(Size.ORIGINAL)
                            .build()
                        context.imageLoader.execute(request).image
                            ?.asDrawable(context.resources)
                            ?.toBitmap()
                    } catch (e: Throwable) {
                        null
                    } ?: return@onEach

                    val current = state.value
                    mediaSession?.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, current.episodeTitle)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, current.animeTitle)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, current.durationMs.toLong())
                            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                            .build(),
                    )
                }
                .launchIn(holderScope)
            // <-- AM (BACKGROUND_ARTWORK_FIX)
        }
        return _player!!
    }

    /**
     * Creates this holder's MediaSession on first call; on every later call (a new or
     * recreated PlayerActivity instance re-binding), redirects the existing session's
     * callback to the one just passed in instead of creating a second session. Without
     * this redirect, media-button presses after a reattach would keep routing to
     * whichever PlayerViewModel happened to build the session first - which, per the
     * same dedup problem the player itself had, could be an already-orphaned instance.
     */
    fun ensureMediaSession(context: Context, callback: MediaSession.Callback): MediaSession {
        mediaSession?.let {
            it.setCallback(callback)
            return it
        }
        return MediaSession(context, "PlayerMediaHolder").apply {
            setCallback(callback)
            isActive = true
        }.also { mediaSession = it }
    }

    fun updateState(transform: (PlayerMediaState) -> PlayerMediaState) {
        _state.value = transform(_state.value)
    }

    /** Tears down the player and MediaSession. Only called when playback genuinely ends. */
    fun release() {
        mediaSession?.release()
        mediaSession = null
        _player?.isExiting = true
        // AM (STALE_HOLDER_STATE_FIX) -->
        // Clearing the player reference and session state here (not just marking
        // isExiting) matters because this holder can outlive any single PlayerActivity
        // instance and be reused by a fast reopen (e.g. tapping the always-on
        // notification) before the Service's own onDestroy() gets around to running -
        // stopService() from PlayerActivity.onDestroy() is asynchronous, so there's a
        // real window where a reopen binds to this same still-alive holder before it's
        // torn down. Without clearing _state here too, needsInit() on that reopen would
        // see the stale animeId/episodeId still "matching" and skip reinitializing
        // entirely, leaving playback permanently stuck against an already-released
        // player.
        _player = null
        _state.value = PlayerMediaState()
        // <-- AM (STALE_HOLDER_STATE_FIX)
        // AM (MEDIA_SESSION_FALLBACK_CALLBACK) -->
        pendingSkipDirection = null
        // <-- AM (MEDIA_SESSION_FALLBACK_CALLBACK)
        // AM (LIVE_POSITION_TRACKING) -->
        holderScope.cancel()
        // <-- AM (LIVE_POSITION_TRACKING)
        // AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX) -->
        // Guarded: only clear if this is genuinely still the current holder - a
        // stale instance's release() running after a newer holder has already
        // self-registered (an unlikely but possible ordering during rapid
        // Service restarts) must never wipe out that newer, genuinely live
        // registration.
        if (current === this) {
            current = null
        }
        // <-- AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX)
    }

    /** Minimal state a reattaching [PlayerActivity] needs to reconstruct its UI on create/resume. */
    data class PlayerMediaState(
        val animeId: Long? = null,
        val episodeId: Long? = null,
        val paused: Boolean = true,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val animeTitle: String = "",
        val episodeTitle: String = "",
    )
}
// <-- AM (SERVICE_OWNED_PLAYER)
