package eu.kanade.tachiyomi.ui.player

// AM (SERVICE_OWNED_PLAYER) -->
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import eu.kanade.tachiyomi.ui.player.mpv.loadFileWithHwdecGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalEpisodeThumbnailManager
import tachiyomi.source.local.isLocal
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
    private val coverManager: LocalCoverManager = Injekt.get(),
    private val episodeThumbnailManager: LocalEpisodeThumbnailManager = Injekt.get(),
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
    // AM (BACKGROUND_SKIP_RACE_FIX) -->
    // Rapid repeated skip presses (lock-screen/Bluetooth next/next/next quickly)
    // used to each launch their own, fully independent performBackgroundSkipLoad()
    // coroutine with nothing coordinating between them. Since each one waits on
    // mpv's own FileLoaded event (BACKGROUND_SKIP_LOAD_CONFIRM_FIX) - a generic
    // event that doesn't say WHICH load it's confirming - an earlier, slower call
    // could still have its wait resolve (matching a LATER load that had already
    // replaced its own) and go on to commit ITS episode's info as if it were the
    // one actually playing. Tracking and cancelling any still-in-flight skip
    // before starting a new one means only the most recently requested skip is
    // ever allowed to reach the point of committing anything - matching mpv's own
    // "replace" load semantics, where the latest request is what actually wins.
    // <-- AM (BACKGROUND_SKIP_RACE_FIX)
    private var currentSkipJob: Job? = null

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
            currentSkipJob?.cancel()
            currentSkipJob = holderScope.launch {
                if (!skipToAdjacentEpisode(next = true)) {
                    requestSkip(next = true)
                }
            }
        }

        override fun onSkipToPrevious() {
            logcat(LogPriority.DEBUG) { "fallbackCallback.onSkipToPrevious fired" }
            currentSkipJob?.cancel()
            currentSkipJob = holderScope.launch {
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

        // AM (BACKGROUND_SKIP_PAUSE_SYMMETRY_FIX) -->
        // Mirrors PlayerViewModel.changeEpisode()'s own pause() call, made at the
        // same point - once a skip is confirmed valid, before any of the (possibly
        // slow) resolution work begins, not at the very top where an ultimately
        // invalid/no-op skip attempt would needlessly interrupt playback for
        // nothing. The foreground path's pause-then-resume around a switch was
        // originally just PIP visual polish (freezing the frame during the async
        // load) - but it has a second effect this path was missing entirely: it's
        // what makes Event.PauseChanged actually fire during a foreground switch.
        // This path only ever called setPaused(false) after loading, which is a
        // no-op (no event fires at all) if mpv was already unpaused going in - the
        // ordinary case for a background skip. That's not a cosmetic difference;
        // it meant a background skip's notification update depended entirely on
        // the artwork flow's own completion with no earlier nudge, while a
        // foreground switch got one for free - the same underlying update
        // eventually happens either way, but there's no reason for the two paths
        // to genuinely behave differently when they don't have to.
        // <-- AM (BACKGROUND_SKIP_PAUSE_SYMMETRY_FIX)
        setPaused(true)

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

        val succeeded = try {
            performBackgroundSkipLoad(animeId, targetEpisodeId)
        } catch (e: CancellationException) {
            // AM (BACKGROUND_SKIP_RACE_FIX) -->
            // Must rethrow, not treat as a normal failure - this specifically
            // means a NEWER skip request superseded this one (see
            // currentSkipJob's own doc comment) and cancelled it deliberately.
            // Broadly catching Throwable below would otherwise also catch this,
            // and this now-stale job would go on to run its own failure handling
            // (restore pause, fall back to requestSkip) - actively interfering
            // with the newer job that's already taken over, undoing its progress
            // out from under it. Rethrowing lets cancellation propagate normally
            // and does nothing else at all.
            // <-- AM (BACKGROUND_SKIP_RACE_FIX)
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.DEBUG, e) { "skipToAdjacentEpisode: threw" }
            false
        }

        // AM (BACKGROUND_SKIP_PAUSE_SYMMETRY_FIX) -->
        // Restore playback if the skip ultimately failed - setPaused(true) above
        // ran before any of this resolution work, so a failed attempt (bad
        // hoster, unsupported source, any exception) must not leave the ORIGINAL,
        // still-valid episode stuck paused for no reason. A failed skip should
        // look like nothing happened, not like playback silently stopped.
        // <-- AM (BACKGROUND_SKIP_PAUSE_SYMMETRY_FIX)
        if (!succeeded) {
            setPaused(false)
        }
        return succeeded
    }

    /**
     * Does the actual resolution + mpv load for [skipToAdjacentEpisode] - split out
     * so its own early-return-on-failure points only exit THIS function, letting
     * the caller reliably restore pause state (see BACKGROUND_SKIP_PAUSE_SYMMETRY_FIX)
     * on any failure path without needing to handle each one individually.
     */
    private suspend fun performBackgroundSkipLoad(animeId: Long, targetEpisodeId: Long): Boolean {
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

            // AM (SHARED_LOAD_FILE_FIX) -->
            // Mirrors PlayerViewModel.loadFile()'s exact pattern - previously
            // duplicated inline here; now both call the same shared function (see
            // its own doc comment for why).
            // <-- AM (SHARED_LOAD_FILE_FIX)
            // AM (AUDIO_BLIP_FIX_2) -->
            // Was unconditionally false, reasoning "background skip by definition
            // only ever runs with no live Activity/Surface, so there's nothing to
            // check" - true for the REAL surface, but this holder shares the same
            // MPVPlayer instance that was alive before the Activity was destroyed
            // (the whole point of the Service-owned-player architecture), and that
            // instance's dummy surface (see MpvSurface.kt's surfaceDestroyed) is
            // still attached from the last time it backgrounded. If the real
            // surface was ever attached at all this session,
            // player.hasAttachedSurfaceBefore is still true here too - same fix,
            // same reasoning as PlayerViewModel.loadFile()'s own doc comment.
            // <-- AM (AUDIO_BLIP_FIX_2)
            player.mpv.loadFileWithHwdecGuard(
                resolvedUrl,
                videoOptions,
                hasAttachedSurface = player.hasAttachedSurfaceBefore,
            )

            // AM (BACKGROUND_SKIP_LOAD_CONFIRM_FIX) -->
            // BACKGROUND_SKIP_STATE_ORDER_FIX moved syncSessionState() to run before
            // this load, to close a metadata-mismatch window - but that assumed the
            // load would basically always succeed once resolution had already
            // succeeded, which isn't actually guaranteed: mpv.command("loadfile",...)
            // only ISSUES the load, it doesn't confirm the resolved URL is actually
            // playable. If it isn't (an expired/bad URL from this specific hoster),
            // playback would genuinely fail to start while the notification had
            // already committed to showing the new episode as if it had - exactly
            // "title/thumbnail changed, but nothing plays" with no fallback,
            // because this function still reported success. Waiting here for mpv's
            // own confirmation that the file is actually loaded - not just assuming
            // it after issuing the command - is what makes committing to the new
            // episode's info actually conditional on the switch having genuinely
            // worked, the same way it already effectively is for the foreground
            // path (which reacts to this same event through handlePlayerFlow()).
            // <-- AM (BACKGROUND_SKIP_LOAD_CONFIRM_FIX)
            val loadConfirmed = withTimeoutOrNull(10_000) {
                player.eventFlow.filterIsInstance<MPVPlayer.Event.FileLoaded>().first()
            } != null
            if (!loadConfirmed) {
                logcat(LogPriority.DEBUG) {
                    "skipToAdjacentEpisode: mpv never confirmed the file loaded, treating as failed"
                }
                return false
            }

            // AM (BACKGROUND_SKIP_STATE_ORDER_FIX) -->
            // Called here, now confirmed AFTER a genuine load success (see
            // BACKGROUND_SKIP_LOAD_CONFIRM_FIX above) but still BEFORE the resume
            // below - this used to run AFTER loadFileWithHwdecGuard()+
            // setPaused(false) entirely, meaning the resume's own PauseChanged-
            // triggered push could fire while state still held the OLD episode's
            // values, but mpv had ALREADY loaded the new file - so that push
            // paired the new episode's real position/duration with the old
            // episode's title/artist, and the resolvedEpisodeKey gate couldn't
            // catch it (animeId/episodeId hadn't changed yet at that point, so the
            // check was trivially comparing old-to-old). Doing this before resume
            // also gives the artwork-resolution flow (which reacts to this exact
            // animeId/episodeId change) a head start before playback actually
            // resumes, instead of starting only once loading was already complete.
            // <-- AM (BACKGROUND_SKIP_STATE_ORDER_FIX)
            // AM (SHARED_SESSION_SYNC_FIX) -->
            // Previously duplicated inline here - now calls the same shared
            // function PlayerViewModel's foreground path also calls (see its own
            // doc comment for why). Duration is deliberately left out of both -
            // see BACKGROUND_SKIP_DURATION_FIX below for why resetting it here
            // specifically caused its own problem; it's left to update through the
            // normal propFlow/timer path instead, same as the foreground path.
            // <-- AM (SHARED_SESSION_SYNC_FIX)
            syncSessionState(
                animeId = animeId,
                episodeId = targetEpisodeId,
                animeTitle = anime.title,
                episodeTitle = episode.name,
                animeThumbnailUrl = anime.thumbnailUrl,
                episodePreviewUrl = episode.previewUrl,
                positionMs = resumePositionMs.toInt(),
            )
            // AM (BACKGROUND_SKIP_DURATION_FIX) -->
            // Reverted: resetting this to 0 here was meant to avoid ever showing a
            // stale wrong duration, but instead made the lock-screen's seek bar
            // disappear entirely rather than just show stale data - Android's
            // media widget appears to treat duration=0 as "no seekable content"
            // and hides the whole progress UI, worse than the staleness this was
            // meant to fix. Left untouched (previous episode's value persists)
            // until the real new duration arrives via the propFlow observer below -
            // see its own logging for why that isn't happening reliably.
            // <-- AM (BACKGROUND_SKIP_DURATION_FIX)

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

            logcat(LogPriority.DEBUG) { "skipToAdjacentEpisode: loaded episode $targetEpisodeId directly" }
            return true
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

    // AM (ARTWORK_WIPE_FIX) -->
    // See pushLiveMediaState()'s own doc comment - these track the last artwork the
    // artwork flow successfully resolved so other metadata pushes can preserve it
    // instead of silently wiping it back out.
    //
    // AM (ARTWORK_VISIBILITY_FIX) -->
    // @Volatile added: these are written by the artwork flow and read by
    // pushLiveMediaState(), which run as separate coroutines on holderScope's
    // Dispatchers.Default - a thread POOL, not a single thread. Without this, a
    // write on one thread isn't guaranteed to be visible to a read on another
    // thread promptly - a classic memory-visibility gap, not a scheduling race,
    // which matches the reported symptom precisely: mostly fails, occasionally
    // succeeds, no consistent timing pattern to it.
    // <-- AM (ARTWORK_VISIBILITY_FIX)
    @Volatile
    private var lastArtwork: Bitmap? = null
    @Volatile
    private var lastArtworkKey: Pair<Long?, Long?>? = null
    // <-- AM (ARTWORK_WIPE_FIX)

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
            // AM (MEDIASESSION_SINGLE_WRITER_FIX) -->
            // Previously gated behind !PlayerActivity.hasLiveInstance, deferring to
            // PlayerActivity's own separate PlaybackState/MediaMetadata-pushing observers
            // whenever an Activity existed. That left THREE independent, uncoordinated
            // setMetadata()/setPlaybackState() callers alive simultaneously during ordinary
            // foreground playback (this timer, this holder's own artwork flow below, and
            // PlayerActivity's combine()-based metadata push) - since both calls fully
            // replace the previous object, whichever fired last won, regardless of which
            // had the freshest data. That's what produced metadata/notification data
            // appearing to lag behind by a full episode switch. This timer (plus the
            // artwork flow below) is now the SOLE writer of MediaSession state, live
            // Activity or not - PlayerActivity no longer pushes any of this itself (see its
            // own MEDIASESSION_SINGLE_WRITER_FIX removal notes). Title/artist come from
            // syncHolderSessionState(), called the instant any switch happens regardless of
            // Activity liveness.
            // <-- AM (MEDIASESSION_SINGLE_WRITER_FIX)
            // AM (COLD_START_METADATA_DELAY_FIX) -->
            // This used to be a delay(1000)-gated loop only - meaning duration (and
            // PlaybackState) genuinely didn't get pushed at all for up to a full second
            // after cold start or any switch, not "wrong", just not sent yet. Whether
            // that first second's worth of blank duration/artwork was visible depended
            // entirely on how quickly something checked the lock screen after opening -
            // exactly the "sometimes shows up, sometimes doesn't" inconsistency reported.
            // Factored the push itself out into pushLiveMediaState() so it can be called
            // both by this timer (ongoing, every 1s, for position tracking - unchanged)
            // AND immediately whenever animeId/episodeId change (below) - the same
            // distinctUntilChanged() trigger the artwork flow already uses, so this fires
            // exactly once per switch, not continuously, and can't develop the same
            // double-writer race BACKGROUND_SEEKBAR_FIGHT_FIX already ruled out for a
            // continuously-reactive position/pause push. Duration specifically still
            // isn't observed as an mpv property-change event (see
            // BACKGROUND_DURATION_DESYNC_FIX above for why that's unreliable) - this
            // reads it directly, same as the timer always has, just also on switch
            // instead of only on the next tick.
            // AM (ARTWORK_WIPE_FIX) -->
            // Tracks the last artwork the artwork flow below successfully resolved,
            // keyed to which episode it belongs to. pushLiveMediaState() used to build
            // its own MediaMetadata with only TITLE/ARTIST/DURATION - never artwork -
            // and since setMetadata() fully replaces the previous object, any call to
            // pushLiveMediaState() AFTER the artwork flow had already successfully
            // pushed a bitmap (from the 15s backstop timer, or a PauseChanged/
            // PlaybackRestart event, both of which call this function) silently wiped
            // the artwork back out with nothing to indicate it happened or to redraw
            // afterward. That's what made artwork look like it "only loads on the
            // second pass" - the first pass's artwork push was real and correct, it
            // just kept getting overwritten by the very next unrelated metadata push.
            // Keyed to (animeId, episodeId) rather than just caching the Bitmap alone
            // so a stale image from a previous episode can never leak into a push for
            // a different one if this races against a switch.
            // <-- AM (ARTWORK_WIPE_FIX)
            suspend fun pushLiveMediaState() {
                val current = state.value
                val positionMs = (player.mpv.getPropertyInt("time-pos") ?: return) * 1000
                val durationMs = (player.mpv.getPropertyInt("duration") ?: return) * 1000
                val paused = player.mpv.getPropertyBoolean("pause") ?: false
                updateState { it.copy(positionMs = positionMs, durationMs = durationMs) }
                // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
                // PlaybackState (position/pause) is deliberately NOT gated by
                // resolvedEpisodeKey - unlike metadata below, showing the live,
                // correct position/pause for whatever is ACTUALLY playing right now
                // is always safe regardless of whether title/artwork resolution for
                // a pending switch has finished. Gating this too (an earlier version
                // of this fix did) meant the progress bar simply stopped updating
                // for however long resolution took, then jumped to catch up all at
                // once the moment the gate opened - visually indistinguishable from
                // the double-writer rubber-banding this codebase already fixed once
                // (BACKGROUND_SEEKBAR_FIGHT_FIX), just from freeze-then-snap instead
                // of two writers disagreeing.
                // <-- AM (WAIT_FOR_COMPLETE_DATA_FIX)
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
                // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
                // Metadata (title/artist/duration/art) IS still gated here - this is
                // the part that actually needs to wait, since it's the combination
                // of these specific fields that must never show a mismatched
                // pairing (new episode's duration next to the old episode's title,
                // etc.). Refuses to push anything at all until artwork resolution
                // has genuinely finished for the CURRENT episode (see
                // resolvedEpisodeKey's own doc comment) - whatever metadata was
                // last fully pushed (the previous episode's complete state) simply
                // keeps showing, untouched, until this matches.
                // <-- AM (WAIT_FOR_COMPLETE_DATA_FIX)
                if (current.resolvedEpisodeKey != (current.animeId to current.episodeId)) return
                if (current.animeTitle.isNotEmpty() || current.episodeTitle.isNotEmpty()) {
                    val artworkForCurrentEpisode = lastArtwork
                        .takeIf { lastArtworkKey == (current.animeId to current.episodeId) }
                    mediaSession?.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, current.episodeTitle)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, current.animeTitle)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
                            .apply {
                                if (artworkForCurrentEpisode != null) {
                                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artworkForCurrentEpisode)
                                }
                            }
                            .build(),
                    )
                }
            }

            // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
            // Removed: this was a separate, unconditional "push whatever we have right
            // now" trigger, firing the instant animeId/episodeId changed - before
            // artwork had any chance to resolve. Since setMetadata() fully replaces,
            // this is what created the whole class of bugs this session kept finding:
            // an incomplete (title-only) push landing, then getting silently wiped or
            // raced against once artwork resolved moments later. The merged flow below
            // (see WAIT_FOR_COMPLETE_DATA_FIX there) now owns every switch entirely -
            // it waits for artwork to resolve (bounded, so a genuinely slow/missing
            // cover can't hang a switch forever) and pushes everything together,
            // atomically, exactly once. The previous episode's complete state stays
            // showing the whole time a switch is in flight, instead of being replaced
            // by an incomplete new one and then patched up later.
            // <-- AM (WAIT_FOR_COMPLETE_DATA_FIX)

            existing.eventFlow
                .filterIsInstance<MPVPlayer.Event.PauseChanged>()
                .onEach { event ->
                    // AM (HOLDER_PAUSE_STATE_SYNC_FIX) -->
                    // state.paused used to only ever get written by setPaused()
                    // (the fallback/no-Activity MediaSession callback path) -
                    // meaning any OTHER way pause could change (PlayerViewModel's
                    // own pause()/unpause(), the sleep timer, MPVPlayer's own
                    // audio-focus-loss handling, and any future caller not yet
                    // written) left this holder's copy stale and wrong,
                    // regardless of what was actually happening. This event
                    // already carries mpv's own real, live pause value directly
                    // from its property-change notification - using it here
                    // makes state.paused correct for every possible cause of a
                    // pause change, not just the ones this file happens to call
                    // directly, without needing to hunt down and individually
                    // patch every current and future call site.
                    // <-- AM (HOLDER_PAUSE_STATE_SYNC_FIX)
                    updateState { it.copy(paused = event.paused) }
                    pushLiveMediaState()
                }
                .launchIn(holderScope)

            existing.eventFlow
                .filterIsInstance<MPVPlayer.Event.PlaybackRestart>()
                .onEach { pushLiveMediaState() }
                .launchIn(holderScope)

            holderScope.launch {
                while (true) {
                    delay(15_000)
                    pushLiveMediaState()
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
            // AM (MEDIASESSION_SINGLE_WRITER_FIX) -->
            // Previously gated behind !PlayerActivity.hasLiveInstance - see the timer's own
            // note above for the full reasoning. This is now the sole artwork/rich-metadata
            // writer, live Activity or not, replacing PlayerActivity's own equivalent
            // combine()-based flow (which used to duplicate this exact fetch-and-push logic
            // and race against it).
            // <-- AM (MEDIASESSION_SINGLE_WRITER_FIX)
            // AM (ARTWORK_SOURCE_OF_TRUTH_FIX) -->
            // This used to call getAnime.await(animeId) itself, an independent DB read
            // separate from whatever already-working anime object the rest of the app
            // (playlist, episode list) resolved successfully. For local sources,
            // thumbnail_url is resolved dynamically via coverManager.find() whenever an
            // anime is fetched THROUGH the source's own pipeline (see
            // LocalSource.getSAnime()/getOldAnimeDetails()) - it isn't guaranteed to be
            // sitting on a raw AnimeRepository.getAnimeById() row, which is all this
            // independent read was doing. That's why the exact same episode could fail
            // once and then succeed on a later revisit with nothing else changing: two
            // independent reads of the same id have no reason to agree on a value
            // that isn't reliably persisted in the first place.
            //
            // Now uses state.animeThumbnailUrl/episodePreviewUrl directly - the same
            // already-resolved values syncHolderSessionState() populates from the same
            // anime/episode objects that make the title correct (see PlayerViewModel).
            // <-- AM (ARTWORK_SOURCE_OF_TRUTH_FIX)
            // AM (ARTWORK_REACTIVE_WAIT_FIX) -->
            // On cold start with a large playlist, episode-thumbnail generation can be
            // actively scanning/writing many local files at the same moment the anime's
            // own thumbnail_url gets resolved and persisted (confirmed via logcat:
            // repeated MediaProvider opens against .thumbnails/*.jpg right around cold
            // start) - so state.animeThumbnailUrl can genuinely still be blank the
            // first time this fires, not because anything is broken, just because that
            // write hasn't landed yet. A short bounded retry (previous version of this
            // fix) guessed at how long that takes and was wrong for a large playlist.
            // Instead of guessing, subscribe to the DB row directly via
            // getAnime.subscribe() and react to it actually changing - correct
            // regardless of how long the background job takes, bounded only by an
            // overall timeout so this can't wait forever if the anime genuinely has no
            // cover at all.
            // <-- AM (ARTWORK_REACTIVE_WAIT_FIX)
            state
                .map { it.animeId to it.episodeId }
                .distinctUntilChanged()
                .onEach { (animeId, episodeId) ->
                    if (animeId == null || episodeId == null) return@onEach
                    val current = state.value

                    // AM (RESOLUTION_EXCEPTION_FIX) -->
                    // The whole body is now wrapped in try/catch, not just the two
                    // specific file-check calls known to be risky - any uncaught
                    // exception ANYWHERE in this Flow's collector body previously
                    // killed the collector permanently (Flow collection simply stops
                    // on an exception, silently, for the rest of the session), meaning
                    // nothing would ever resolve for ANY later episode either, not
                    // "eventually" - never. This is what "just doesn't load, at all"
                    // actually was: not a timing problem, a crashed collector. Logging
                    // on catch instead of silently swallowing, since a genuinely broken
                    // resolution path should be visible, not just quietly give up.
                    // <-- AM (RESOLUTION_EXCEPTION_FIX)
                    try {

                    // episode.preview_url (when present) is safe to pass as a raw string -
                    // that's how this already worked before any of these fixes, unchanged
                    // here. anime.thumbnailUrl needs the AnimeCover/AnimeImageFetcher/
                    // UniFile path below instead - see ARTWORK_UNIFILE_ROUTING_FIX.
                    val previewUrl = current.episodePreviewUrl?.takeIf { it.isNotBlank() }

                    // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
                    // No push happens until this entire resolution finishes - not a
                    // bounded "fast attempt with a text-only fallback" (an earlier
                    // version of this fix did that, and it was still wrong: pushing
                    // title-only after a timeout is exactly the "render an incomplete
                    // state" behavior this fix exists to eliminate). The previous
                    // episode's complete state (title, duration, artwork) simply keeps
                    // showing on the lock screen for however long resolution takes -
                    // fast in the common case (files already exist on disk - see this
                    // whole thread's earlier findings), or up to the patient reactive
                    // wait below in the rare case a cover doesn't exist yet at all.
                    // Either way, exactly one push happens, once, with everything
                    // together - never a partial render in between.
                    // <-- AM (WAIT_FOR_COMPLETE_DATA_FIX)
                    val preDecodedArtwork: Bitmap?
                    val coverData: Any? = if (previewUrl != null) {
                        preDecodedArtwork = null
                        previewUrl
                    } else {
                        val anime = getAnime.await(animeId) ?: return@onEach

                        // AM (EPISODE_THUMBNAIL_MISSING_FIX) -->
                        // episodeThumbnailManager was injected but never actually called
                        // anywhere in this file - a genuine dead-code gap, not a timing
                        // bug. Without this, EVERY episode of the same anime fell through
                        // to the anime-level cover below, which resolves to the SAME
                        // image (the anime's own cover.jpg) regardless of which episode
                        // is playing - looking exactly like "stuck on the previous
                        // episode's image" on every single switch, since it never
                        // changes at all between episodes of the same anime. This checks
                        // the episode's own thumbnail file directly - the same file this
                        // app's own thumbnail-generation feature creates per-episode -
                        // before ever falling back to the anime's generic cover. No
                        // dedicated Coil fetcher is registered for episode-level
                        // thumbnails (only Fetcher.Factory<Anime>/<AnimeCover> exist),
                        // so this decodes the UniFile directly instead of routing
                        // through Coil at all for this specific case - preDecodedArtwork
                        // carries the result past this block; coverData stays null here
                        // since there's nothing left for Coil to fetch when this succeeds.
                        // <-- AM (EPISODE_THUMBNAIL_MISSING_FIX)
                        preDecodedArtwork = if (anime.isLocal()) {
                            // AM (RESOLUTION_EXCEPTION_FIX) -->
                            // Uncaught here previously - any exception from find() or
                            // openInputStream() (a permission hiccup, a file mid-write,
                            // any I/O failure) would propagate out of this Flow's
                            // collector entirely. Flow collection on an exception simply
                            // stops - permanently, for the rest of the session, not just
                            // for this one episode - meaning nothing would ever resolve
                            // again afterward, not "eventually", never. This wasn't a
                            // timing problem masquerading as one; it needed to not crash
                            // in the first place, not a longer wait or another retry.
                            // <-- AM (RESOLUTION_EXCEPTION_FIX)
                            // AM (COLD_START_FILE_CHECK_RETRY_FIX) -->
                            // The bitmap DECODE step elsewhere in this same flow
                            // already retries (2 attempts, 300ms apart) for
                            // transient failures - this file-EXISTENCE check never
                            // did, despite being just as vulnerable to the same
                            // kind of brief unreadiness (SAF/DocumentsProvider not
                            // immediately able to enumerate files the instant the
                            // app's process starts, on cold start specifically).
                            // A single failed attempt fell straight through to the
                            // much slower reactive-wait fallback below instead of
                            // just trying again a moment later - not genuinely
                            // waiting for something that was normally already
                            // there, just briefly not-yet-ready.
                            // <-- AM (COLD_START_FILE_CHECK_RETRY_FIX)
                            var episodeArtwork: Bitmap? = null
                            for (attempt in 0 until 2) {
                                if (attempt > 0) delay(300)
                                episodeArtwork = try {
                                    withContext(Dispatchers.IO) {
                                        episodeThumbnailManager.find(anime.url, "${current.episodeTitle}-thumbnail")
                                            ?.openInputStream()?.use { BitmapFactory.decodeStream(it) }
                                    }
                                } catch (e: Throwable) {
                                    null
                                }
                                if (episodeArtwork != null) break
                            }
                            episodeArtwork
                        } else {
                            null
                        }

                        if (preDecodedArtwork != null) {
                            null
                        } else {
                        // AM (ARTWORK_DIRECT_RESOLVE_FIX) -->
                        // Previously waited (bounded by a 15s timeout) for the anime's
                        // thumbnail_url to eventually appear in the DB, written by some
                        // OTHER, unrelated background job (this app's own episode-
                        // thumbnail generation, or a library refresh) - passive, and
                        // wrong on a cold start with a large playlist: that background
                        // work can genuinely take longer than 15s, and since this only
                        // fires once per episode, a timed-out attempt never retried
                        // until leaving and returning re-triggered it (confirmed: that's
                        // exactly the reported symptom - works on return, not on first
                        // open).
                        //
                        // For local sources specifically, the actual cover resolution
                        // (LocalCoverManager.find()) is a cheap, synchronous file-system
                        // scan for a "cover.*" file in the anime's own folder - it has no
                        // dependency on any background job at all. Calling it directly,
                        // right here, resolves the cover the instant it's needed instead
                        // of passively waiting for someone else to have already done so.
                        // <-- AM (ARTWORK_DIRECT_RESOLVE_FIX)
                        val resolvedUrl = current.animeThumbnailUrl?.takeIf { it.isNotBlank() }
                            ?: anime.thumbnailUrl?.takeIf { it.isNotBlank() }
                            ?: if (anime.isLocal()) {
                                // AM (ARTWORK_RESOLVE_WAIT_FIX) -->
                                // Tries the direct file-system check first (instant, covers
                                // the common case where a cover already exists). If that
                                // comes up empty, the anime genuinely has no cover yet - this
                                // waits for the real completion signal instead of guessing at
                                // a retry schedule. UpdateAnimeFromRemote.awaitUpdateFromSource()
                                // persists a freshly-generated cover straight to the anime's
                                // DB row the moment extraction finishes (confirmed by reading
                                // that code directly) - subscribing to that row and waiting
                                // for thumbnailUrl to actually appear reacts to the real
                                // event, however long it takes, rather than polling on a
                                // fixed interval. The generous 2-minute ceiling is a genuine
                                // safety valve only (e.g. a broken/corrupt video that can
                                // never extract a frame), not a guess at the typical case.
                                // <-- AM (ARTWORK_RESOLVE_WAIT_FIX)
                                // AM (RESOLUTION_EXCEPTION_FIX) -->
                                // Same fix as the episode-level check above - uncaught,
                                // this could kill the whole Flow's collector permanently
                                // on any I/O exception, not just fail this one attempt.
                                // <-- AM (RESOLUTION_EXCEPTION_FIX)
                                // AM (COLD_START_FILE_CHECK_RETRY_FIX) -->
                                // See the episode-level check above for the full
                                // reasoning - same fix, same reason: a single
                                // failed attempt shouldn't fall all the way through
                                // to the 2-minute reactive-wait fallback below
                                // without first just trying again a moment later.
                                // <-- AM (COLD_START_FILE_CHECK_RETRY_FIX)
                                var coverFileUrl: String? = null
                                for (attempt in 0 until 2) {
                                    if (attempt > 0) delay(300)
                                    coverFileUrl = try {
                                        withContext(Dispatchers.IO) {
                                            coverManager.find(anime.url)?.uri?.toString()
                                        }
                                    } catch (e: Throwable) {
                                        null
                                    }
                                    if (coverFileUrl != null) break
                                }
                                coverFileUrl ?: withTimeoutOrNull(120_000) {
                                    getAnime.subscribe(animeId)
                                        .map { it.thumbnailUrl }
                                        .filter { !it.isNullOrBlank() }
                                        .first()
                                }
                            } else {
                                null
                            }
                        // AM (ARTWORK_UNIFILE_ROUTING_FIX) -->
                        // anime.thumbnailUrl for local sources is a SAF tree content://
                        // URI (confirmed via logcat:
                        // "content://com.android.externalstorage.documents/tree/...");
                        // AnimeImageFetcher.fileUriLoader() reads that specifically via
                        // UniFile.fromUri(), which handles SAF tree-URI resolution
                        // properly - Coil's own generic content-URI handling does not
                        // reliably resolve this URI shape the same way (confirmed:
                        // passing the raw string directly bypassed AnimeImageFetcher/
                        // UniFile entirely and silently failed to load). Still need an
                        // Anime object to route through the correct, UniFile-aware
                        // fetcher (Fetcher.Factory<AnimeCover> is registered for
                        // AnimeCover, not a bare String) - but overriding its cover url
                        // with the resolvedUrl above (instead of trusting whatever
                        // getAnime.await() itself returns) keeps the actual fix: this
                        // independent read is only for the anime object's OTHER
                        // required fields (sourceId/favorite/lastModified), not for the
                        // URL value itself.
                        // <-- AM (ARTWORK_UNIFILE_ROUTING_FIX)
                        resolvedUrl?.let { anime.asAnimeCover().copy(url = it) }
                        }
                    }

                    var artwork: Bitmap? = preDecodedArtwork
                    if (artwork == null && coverData != null) {
                        for (attempt in 0 until 2) {
                            if (attempt > 0) delay(300)
                            artwork = try {
                                val request = ImageRequest.Builder(context)
                                    .data(coverData)
                                    .size(Size.ORIGINAL)
                                    .build()
                                context.imageLoader.execute(request).image
                                    ?.asDrawable(context.resources)
                                    ?.toBitmap()
                            } catch (e: Throwable) {
                                null
                            }
                            if (artwork != null) break
                        }
                    }

                    // Guard against the episode having moved on during the (up to
                    // 2-minute) wait above - without this, artwork resolved for the
                    // episode this onEach started on could get pushed alongside a
                    // DIFFERENT, newer episode's title/duration if the user switched
                    // again in the meantime, since Flow's onEach here doesn't cancel
                    // in-flight work when a new value arrives, only queues it for
                    // after this returns.
                    val latest = state.value
                    if (latest.animeId != animeId || latest.episodeId != episodeId) return@onEach
                    // AM (ARTWORK_WIPE_FIX) -->
                    // Cache this before pushing - see pushLiveMediaState()'s doc comment
                    // for why other pushes need this to avoid wiping the artwork back out.
                    if (artwork != null) {
                        lastArtwork = artwork
                        lastArtworkKey = animeId to episodeId
                    }
                    // <-- AM (ARTWORK_WIPE_FIX)
                    // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
                    // Marks resolution as genuinely finished for this episode - with or
                    // without artwork, either way this is the decision, not "still
                    // pending" - which is what unblocks pushLiveMediaState() for every
                    // caller (see its own doc comment), not just this call right below.
                    // Now part of shared state (not a holder-private var) so
                    // PlayerBackgroundPlaybackService's separate notification-text
                    // writer can gate on the same signal - see PlayerMediaState's own
                    // doc comment on this field for why that matters.
                    // <-- AM (WAIT_FOR_COMPLETE_DATA_FIX)
                    updateState { it.copy(resolvedEpisodeKey = animeId to episodeId) }
                    pushLiveMediaState()
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) {
                            "Artwork resolution threw for animeId=$animeId episodeId=$episodeId"
                        }
                    }
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

    // AM (SHARED_SESSION_SYNC_FIX) -->
    // Replaces two separately-written copies of this exact update -
    // PlayerViewModel.syncHolderSessionState() (foreground) and this holder's own
    // performBackgroundSkipLoad() (background skip, no live Activity) each built
    // their own it.copy(...) call against the same PlayerMediaState fields. That's
    // exactly how the two silently drifted apart once already this session -
    // performBackgroundSkipLoad() simply never included animeTitle in its copy(),
    // an omission that went unnoticed for a long time specifically because nothing
    // structurally prevented it. One shared function both callers go through means
    // that class of bug isn't something to catch by comparison anymore - there's
    // only one place these fields get written together at all.
    //
    // Takes primitive values rather than whole Anime/Episode objects because the
    // two callers don't share an Episode type: PlayerViewModel's currentEpisode is
    // the legacy eu.kanade.tachiyomi.data.database.models.Episode
    // (preview_url, underscore), while the background path's getEpisode.await()
    // returns the domain tachiyomi.domain.episode.model.Episode (previewUrl,
    // camelCase) - unifying THAT would be its own, separate refactor. Each caller
    // extracts its own fields using whichever accessor its own type actually has;
    // this function only owns what happens once those values exist.
    //
    // positionMs is optional and defaults to leaving the existing value alone -
    // only the background path needs to seed a fresh resume position as part of
    // this same update; the foreground path has its own separate, continuous
    // position-tracking timer and was never touching this field here at all.
    // <-- AM (SHARED_SESSION_SYNC_FIX)
    fun syncSessionState(
        animeId: Long,
        episodeId: Long,
        animeTitle: String,
        episodeTitle: String,
        animeThumbnailUrl: String?,
        episodePreviewUrl: String?,
        positionMs: Int? = null,
    ) {
        updateState {
            it.copy(
                animeId = animeId,
                episodeId = episodeId,
                animeTitle = animeTitle,
                episodeTitle = episodeTitle,
                animeThumbnailUrl = animeThumbnailUrl,
                episodePreviewUrl = episodePreviewUrl,
                positionMs = positionMs ?: it.positionMs,
            )
        }
    }

    /** Tears down the player and MediaSession. Only called when playback genuinely ends. */
    fun release() {
        mediaSession?.release()
        mediaSession = null
        // AM (NATIVE_PLAYER_LEAK_FIX) -->
        // Was `_player?.isExiting = true` directly, bypassing the real release().
        // MPVPlayer.release() guards itself with `if (isExiting) return`, so that
        // permanently disarmed the real teardown - mpv.close()/surface release
        // never ran, leaking the native mpv/GPU context every time. Confirmed via
        // dumpsys meminfo: Native Heap and EGL mtrack ballooned and never
        // recovered (not a Java-heap leak, so GC never touched it).
        _player?.release()
        // <-- AM (NATIVE_PLAYER_LEAK_FIX)
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
        // AM (ARTWORK_SOURCE_OF_TRUTH_FIX) -->
        // Populated from the SAME already-resolved anime/episode objects
        // syncHolderSessionState() uses for the titles above (see PlayerViewModel),
        // not from a second, independent DB read. The artwork flow below used to call
        // getAnime.await(animeId)/getEpisode.await(episodeId) itself, which for local
        // sources can race against LocalSource's own dynamic thumbnail_url resolution
        // (see LocalSource.getSAnime()/getOldAnimeDetails() - thumbnail_url is found
        // via coverManager.find() when an anime is fetched THROUGH the source, not
        // guaranteed present on a raw AnimeRepository.getAnimeById() row) - the exact
        // path that already works correctly everywhere else in the app (playlist,
        // episode list) resolves it once and holds onto that reference; a second,
        // independent read has no reason to see the same result. Reusing the
        // already-correct value here instead of re-deriving it removes that
        // discrepancy entirely rather than papering over it with a retry.
        // <-- AM (ARTWORK_SOURCE_OF_TRUTH_FIX)
        val animeThumbnailUrl: String? = null,
        val episodePreviewUrl: String? = null,
        // AM (WAIT_FOR_COMPLETE_DATA_FIX) -->
        // Moved from a PlayerMediaHolder-private var into the shared state itself so
        // PlayerBackgroundPlaybackService's own notification-text writer
        // (updateEpisodeInfo(), a completely separate Android API surface from
        // MediaSession - the actual posted Notification's title/subtitle, not
        // MediaSession's metadata) can gate on the exact same "has resolution
        // genuinely finished for this episode" signal pushLiveMediaState() already
        // does. Without this, the notification's visible TEXT updated instantly on
        // every switch while its artwork waited - technically two different Android
        // systems, but perceived by anyone looking at the notification as one thing
        // updating incrementally, which is exactly the behavior this whole fix
        // exists to eliminate everywhere, not just within MediaSession's own fields.
        // <-- AM (WAIT_FOR_COMPLETE_DATA_FIX)
        val resolvedEpisodeKey: Pair<Long?, Long?>? = null,
    )
}
// <-- AM (SERVICE_OWNED_PLAYER)
