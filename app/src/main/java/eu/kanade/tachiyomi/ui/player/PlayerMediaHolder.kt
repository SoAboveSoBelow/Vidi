package eu.kanade.tachiyomi.ui.player

// AM (SERVICE_OWNED_PLAYER) -->
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import animiru.domain.player.model.CustomKeyCodes
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.service.GesturePreferences
import aniyomi.core.common.torrent.TorrentPreferences
import aniyomi.core.common.torrent.TorrentServerApi
import aniyomi.core.common.torrent.TorrentServerUtils
import eu.kanade.domain.connection.SyncPreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.connection.syncmiru.SyncDataJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.torrent.service.TorrentServerService
import eu.kanade.tachiyomi.ui.player.mpv.ChapterNode
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import eu.kanade.tachiyomi.ui.player.mpv.loadFileWithHwdecGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
import eu.kanade.domain.episode.model.toSEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.components.HosterState
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.util.editThumbnail
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
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
    private val torrentServerApi: TorrentServerApi = Injekt.get(),
    private val torrentServerUtils: TorrentServerUtils = Injekt.get(),
    private val torrentPreferences: TorrentPreferences = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val trackEpisode: TrackEpisode = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val json: Json = Injekt.get(),
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
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG PlayerMediaHolder.init setting current: newHolder=${System.identityHashCode(this)} " +
                "previousCurrent=${current?.let { System.identityHashCode(it) }} at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
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

    // AM (MEDIA_SESSION_SERVICE_ONLY) -->
    /**
     * The MediaSession's one and only callback, for the whole life of this holder -
     * no more Activity-owned "primary" callback that this used to hand off to/from
     * depending on lifecycle state (see this section's own git history for that
     * design and why it was replaced).
     *
     * That dual-callback split was real, confirmed-in-production fragility: a
     * [eu.kanade.tachiyomi.ui.player.PlayerActivity] instance merely being stopped
     * (not destroyed) - which Recents can do without ever calling onDestroy() -
     * left MediaSession still pointed at a callback closing over a ViewModel that
     * wasn't reliably processing anything, and hardware/notification commands
     * arriving in that window went nowhere. Checked against VLC's own real,
     * shipped MediaSessionCallback: it's owned entirely by VLC's PlaybackService,
     * permanently, with no Activity-bound equivalent to keep in sync at all - the
     * dual-callback design wasn't a requirement of Android's own APIs, it was this
     * app's own choice, and the one actually causing the fragility.
     *
     * Every gesture preference (previous/play-pause/next, each independently
     * configurable to None/Seek/PlayPause/Custom keypress/Switch-episode/
     * Screenshot) that used to only be honored by the Activity-owned callback is
     * implemented here too, operating directly on [mpv]/[gesturePreferences]
     * rather than through a ViewModel - the underlying player is already
     * Service-owned, so nothing here needs a live Activity to do the same thing
     * the old primary callback did. Screenshot is the one gesture option with no
     * meaningful Service-only equivalent (it's inherently a foreground,
     * UI-visible action) - silently does nothing for that specific option when
     * no Activity is attached, same as it always effectively would have anyway.
     */
    private val gesturePreferences: GesturePreferences = Injekt.get()

    // AM (PIP_PAUSE_SEEK_UNIFY) -->
    // No longer private - PlayerViewModel's own leftSeek()/rightSeek()/seekBy()
    // now delegate here instead of independently reimplementing the same mpv
    // seek command. See PlayerViewModel.seekBy()'s own doc comment for why.
    // <-- AM (PIP_PAUSE_SEEK_UNIFY)
    fun seekBy(offsetSeconds: Int) {
        mpv.command(
            "seek",
            offsetSeconds.toString(),
            if (gesturePreferences.playerSmoothSeek.get()) "relative+exact" else "relative",
        )
    }

    // AM (BACKGROUND_SKIP_RACE_FIX) -->
    // Rapid repeated skip presses (lock-screen/Bluetooth next/next/next quickly)
    // used to each launch their own, fully independent resolveAndLoadTarget()
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

    private fun switchEpisode(next: Boolean) {
        currentSkipJob?.cancel()
        currentSkipJob = holderScope.launch {
            if (!switchToAdjacentEpisode(next = next)) {
                requestSkip(next = next)
            }
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback() {
        // AM (STATIC_PLAY_BUTTON_FIX) -->
        // Was gated behind gesturePreferences.mediaPlayPauseGesture, the SAME
        // preference used to interpret a generic hardware media button (a
        // headset's single multi-function click, genuinely ambiguous - could
        // mean play/pause, seek, switch episode, depending on what the user
        // configured). onPlay()/onPause() are a different signal entirely:
        // Android's own dedicated Play/Pause notification action, which is
        // never ambiguous - the icon IS the action. Gating it behind a
        // preference meant for an ambiguous button meant pressing the
        // notification's own play/pause icon did nothing at all unless that
        // unrelated preference happened to be set to exactly PlayPause -
        // a static play button for anyone who'd set their headset gesture to
        // Seek/Switch/Custom/anything else. Unconditional now, matching what
        // the icon actually says it does.
        override fun onPlay() {
            setPaused(false)
        }

        override fun onPause() {
            setPaused(true)
        }
        // <-- AM (STATIC_PLAY_BUTTON_FIX)

        override fun onSkipToPrevious() {
            logcat(LogPriority.DEBUG) { "mediaSessionCallback.onSkipToPrevious fired" }
            when (gesturePreferences.mediaPreviousGesture.get()) {
                SingleActionGesture.None -> {}
                SingleActionGesture.Seek -> seekBy(-gesturePreferences.skipLengthPreference.get())
                SingleActionGesture.PlayPause -> mpv.command("cycle", "pause")
                SingleActionGesture.Custom -> mpv.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
                SingleActionGesture.Switch -> switchEpisode(next = false)
                SingleActionGesture.Screenshot -> {}
            }
        }

        override fun onSkipToNext() {
            logcat(LogPriority.DEBUG) { "mediaSessionCallback.onSkipToNext fired" }
            when (gesturePreferences.mediaNextGesture.get()) {
                SingleActionGesture.None -> {}
                SingleActionGesture.Seek -> seekBy(gesturePreferences.skipLengthPreference.get())
                SingleActionGesture.PlayPause -> mpv.command("cycle", "pause")
                SingleActionGesture.Custom -> mpv.command("keypress", CustomKeyCodes.MediaNext.keyCode)
                SingleActionGesture.Switch -> switchEpisode(next = true)
                SingleActionGesture.Screenshot -> {}
            }
        }

        // AM (MEDIASESSION_STOP_SAFETY_FIX) -->
        // An external "stop" transport command (Bluetooth/AVRCP, a system media
        // widget, etc.) should behave like the notification's own Stop button -
        // reusing that exact same ACTION_STOP path rather than needing a direct
        // Activity reference to finish() (which this Service-only callback
        // doesn't have, and shouldn't need - stopping playback doesn't require
        // closing any particular window).
        // <-- AM (MEDIASESSION_STOP_SAFETY_FIX)
        override fun onStop() {
            super.onStop()
            setPaused(true)
            context.startService(
                PlayerBackgroundPlaybackService.newIntent(context)
                    .setAction(PlayerBackgroundPlaybackService.ACTION_STOP),
            )
        }
    }
    // <-- AM (MEDIA_SESSION_SERVICE_ONLY)

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

    // AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
    // Shared with PlayerViewModel - see RecentEpisodePositionManager's own doc comment
    // for why this used to be two separate in-memory maps kept in sync by hand, and
    // isn't anymore.
    private val recentEpisodePositionManager: RecentEpisodePositionManager = Injekt.get()
    // <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)

    /**
     * Resolves and loads the next/previous episode directly, without needing a live
     * PlayerActivity/ViewModel - the actual fix for skip-while-backgrounded doing
     * nothing at all. Covers the common case only: a direct-URL HTTP or local
     * source. Deliberately does NOT attempt torrent-based sources or sources
     * requiring a local HTTP proxy server (Video.usesHttpServer()) - replicating
     * that handling correctly without the ability to test against real sources
     * risked silently mishandling it, so those fall back to the pre-existing
     * queue-until-reopen behavior instead (see the callers in [mediaSessionCallback]).
     *
     * Returns false on anything not resolved (source unsupported, resolution
     * failure, no player adopted) - callers fall back to [requestSkip].
     *
     * AM (PIP_SKIP_REBUILD) -->
     * Pause handling used to be split across three different points (this
     * function's own bracket, plus a second setPaused(false) buried inside
     * resolveAndLoadTarget() right before it returned true) - confirmed by
     * direct testing that the intended end state (playing) wasn't reliably
     * happening despite that. Consolidated into a single try/finally here: the
     * try body's own return value decides what "succeeded" means, and finally
     * is the ONE place that ever restores playback, unconditionally, exactly
     * once, regardless of which branch got there or what the actual final mpv
     * pause state already was. A single fun that unconditionally does
     * "resume, then log what mpv itself now reports" can't accidentally be
     * skipped by an early return, unlike code embedded partway through a much
     * longer resolution function.
     * <-- AM (PIP_SKIP_REBUILD)
     */
    // AM (BACKGROUND_THUMBNAIL_BACKFILL_FIX) -->
    // Ported from PlayerViewModel.generateEpisodeThumbnailIfMissing() (see
    // MODULAR_MERGING_SCOPE.md's thumbnail/artwork cluster item #2) - confirmed
    // gap: a local episode reached only through a background skip never got this
    // backfill, since PlayerMediaHolder had no equivalent at all. Logic is a
    // straight port, not a reimplementation - same 3s delay-for-a-real-frame
    // rationale, same "still on this episode" guard, same episode.editThumbnail()
    // write - just using the holder's own mpv/anime/episode handles
    // (getAnime/getEpisode's domain Episode needs .toSEpisode() first, since
    // editThumbnail() is an SEpisode extension) and holderScope instead of
    // viewModelScope. No UI-facing signal to replicate: the foreground
    // _thumbnailGenerated StateFlow this mirrors isn't actually observed
    // anywhere in the app today (checked), so there's nothing to wire up here
    // that the working foreground path itself relies on.
    private fun generateEpisodeThumbnailIfMissing(anime: Anime, episode: Episode, episodeId: Long) {
        if (!anime.isLocal()) return
        if (!episode.previewUrl.isNullOrBlank()) return

        holderScope.launch {
            delay(3000)
            // Only capture if still on the episode this was scheduled for.
            if (state.value.episodeId != episodeId) return@launch
            runCatching {
                val tempFile = File(context.cacheDir, "${episodeId}_auto_thumbnail_tmp.jpg")
                player.mpv.command("screenshot-to-file", tempFile.absolutePath, "video")
                if (!tempFile.exists()) return@launch
                tempFile.inputStream().use { stream ->
                    episode.toSEpisode().editThumbnail(anime, episodeThumbnailManager, stream)
                }
                tempFile.delete()
            }.onFailure {
                logcat(LogPriority.DEBUG) { "generateEpisodeThumbnailIfMissing: failed - $it" }
            }
        }
    }
    // <-- AM (BACKGROUND_THUMBNAIL_BACKFILL_FIX)

    private suspend fun switchToAdjacentEpisode(next: Boolean): Boolean {
        if (!hasAdoptedPlayer) {
            logcat(LogPriority.DEBUG) { "switchToAdjacentEpisode: no adopted player" }
            return false
        }
        val currentState = state.value
        val animeId = currentState.animeId ?: run {
            logcat(LogPriority.DEBUG) { "switchToAdjacentEpisode: holder state has no animeId" }
            return false
        }
        val currentEpisodeId = currentState.episodeId ?: run {
            logcat(LogPriority.DEBUG) { "switchToAdjacentEpisode: holder state has no episodeId" }
            return false
        }

        val playlist = playlistEpisodeIds
        val currentIndex = playlist.indexOf(currentEpisodeId)
        if (currentIndex == -1) {
            logcat(LogPriority.DEBUG) {
                "switchToAdjacentEpisode: episode $currentEpisodeId not in mirrored playlist " +
                    "(size=${playlist.size})"
            }
            return false
        }
        val newIndex = if (next) currentIndex + 1 else currentIndex - 1
        if (newIndex !in playlist.indices) {
            logcat(LogPriority.DEBUG) { "switchToAdjacentEpisode: newIndex $newIndex out of range (size=${playlist.size})" }
            return false
        }
        val targetEpisodeId = playlist[newIndex]

        return switchToEpisode(animeId, targetEpisodeId)
    }

    // AM (SHARED_EPISODE_SWITCH_FIX) -->
    // Factored out of switchToAdjacentEpisode() above so both that (background
    // skip, computing the adjacent id itself) and PlayerViewModel.changeEpisode()
    // (an explicit target id from the playlist screen or autoplay) can call the
    // exact same implementation instead of two independently-maintained copies
    // of "pause, resolve, load, restore pause state on any outcome." Was the
    // actual point of this whole unification pass - see MODULAR_MERGING_SCOPE.md's
    // own "episode switching/loading" item, and the whole reason
    // resolveAndLoadTarget() below already took an explicit (animeId,
    // targetEpisodeId) pair rather than "next/previous" in the first place: it
    // was always general enough for this, just not exposed as its own entry
    // point until now.
    suspend fun switchToEpisode(animeId: Long, targetEpisodeId: Long): Boolean {
        // Mirrors PlayerViewModel.changeEpisode()'s own pause() call, made at the
        // same point - once a skip is confirmed valid, before any of the (possibly
        // slow) resolution work begins, not at the very top where an ultimately
        // invalid/no-op skip attempt would needlessly interrupt playback for
        // nothing.
        setPaused(true)

        val currentState = state.value
        val currentEpisodeId = currentState.episodeId
        if (currentEpisodeId != null) {
            recentEpisodePositionManager.remember(
                animeId = animeId,
                episodeId = currentEpisodeId,
                positionMs = currentState.positionMs.toLong(),
                durationMs = currentState.durationMs.toLong(),
            )
        }

        var wasCancelled = false
        return try {
            resolveAndLoadTarget(animeId, targetEpisodeId)
        } catch (e: CancellationException) {
            // AM (BACKGROUND_SKIP_RACE_FIX) -->
            // Must rethrow, not treat as a normal failure - this specifically
            // means a NEWER skip request superseded this one (see
            // currentSkipJob's own doc comment). wasCancelled skips this job's
            // own pause-restore/log below - see BACKGROUND_SKIP_PAUSE_RACE_FIX
            // just below for why that matters now.
            // <-- AM (BACKGROUND_SKIP_RACE_FIX)
            wasCancelled = true
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.DEBUG, e) { "switchToEpisode: threw" }
            false
        } finally {
            // AM (BACKGROUND_SKIP_PAUSE_RACE_FIX) -->
            // Was unconditional, on the reasoning that a cancelled job's own
            // setPaused(false)/log here was harmless since "the newer job's own
            // pause handling is what actually matters." That reasoning missed
            // that finally still runs on cancellation - so a job cancelled by a
            // NEWER request could still race that newer job's own
            // setPaused(true): if the cancelled job's finally happened to run
            // AFTER the newer job had already (re-)paused for its own attempt,
            // this would silently un-pause it mid-flight. Confirmed as the
            // actual mechanism behind rapid-skip pause/unpause flicker, and
            // separately explains the misleading back-to-back "done" logs seen
            // testing the thumbnail-backfill/shared-resolution work tonight -
            // this always logged "done" even for a job that got cancelled
            // before resolveAndLoadTarget ever confirmed a real load. Only the
            // job that actually reaches this point WITHOUT being superseded
            // should get to decide the final pause state or claim to be "done."
            if (!wasCancelled) {
                setPaused(false)
                logcat(LogPriority.DEBUG) {
                    "switchToEpisode: done, mpv pause=${player.mpv.getPropertyBoolean("pause")}"
                }
            } else {
                logcat(LogPriority.DEBUG) { "switchToEpisode: cancelled by a newer skip request" }
            }
            // <-- AM (BACKGROUND_SKIP_PAUSE_RACE_FIX)
        }
    }
    // <-- AM (SHARED_EPISODE_SWITCH_FIX)

    // AM (SHARED_VIDEO_RESOLUTION_FIX) -->
    // Ported from PlayerViewModel.setVideo()'s torrent/HTTP-proxy branching -
    // see MODULAR_MERGING_SCOPE.md's "episode switching/loading" item. This was
    // the confirmed capability gap: resolveAndLoadTarget() used to hard-refuse
    // both torrent sources and sources needing a local HTTP proxy server
    // outright, while the foreground path has always handled both. Rather than
    // leaving that a second, independently-maintained copy once ported, this is
    // the ONE place both paths call for it: PlayerViewModel.setVideo() should
    // be repointed at this too (see this file's own TODO comment on that
    // function once that repointing lands) so a future fix here doesn't need
    // to be re-applied there by hand a second time.
    //
    // Returns the resolved Video (with videoUrl pointing at whatever's actually
    // loadable - a torrent play link, an http-proxy URL, or just the original
    // URL passed through the same resolveUri() step parseVideoUrl() used to do)
    // or null if resolution failed (e.g. HTTP proxy server couldn't start) -
    // logged here either way, since this has no UI to show a toast through; a
    // caller with a live screen can still show one itself based on a null
    // return. Returns the whole Video, not just a URL string, because the
    // HTTP-proxy case needs to be reflected in state (see setVideo()'s own use
    // of this - startCasting() reads currentVideo.videoUrl and needs the real,
    // castable proxied URL, not the original).
    suspend fun resolveFinalVideoUrl(video: Video, source: AnimeSource): Video? {
        return if (torrentPreferences.torrServerEnable.get() && isTorrentVideo(video)) {
            TorrentServerService.start()
            val torrentUrl = getTorrentPlayUrl(video.videoUrl, video.videoTitle)
            video.copy(videoUrl = torrentUrl)
        } else {
            val httpSource = source as? AnimeHttpSource
            if (video.usesHttpServer() && httpSource != null) {
                try {
                    val server = httpSource.createHttpServer()
                    server?.start()
                    video.copyHttpServer(server?.listeningPort ?: 0)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "resolveFinalVideoUrl: failed to start http server" }
                    null
                }
            } else {
                val resolvedUrl = video.videoUrl.toUri().resolveUri(context) ?: video.videoUrl
                video.copy(videoUrl = resolvedUrl)
            }
        }
    }

    private fun isTorrentVideo(video: Video): Boolean {
        if (video.videoUrl.startsWith(torrentServerApi.hostUrl)) return true
        if (video.videoUrl.startsWith("magnet")) return true
        return video.videoUrl.endsWith("torrent")
    }

    private suspend fun getTorrentPlayUrl(videoUrl: String, title: String): String {
        var index = 0

        if (videoUrl.startsWith("content://")) {
            val videoInputStream = context.contentResolver.openInputStream(videoUrl.toUri())
            val torrent = torrentServerApi.uploadTorrent(videoInputStream!!, title, false)
            return torrentServerUtils.getTorrentPlayLink(torrent, 0)
        }

        if (videoUrl.startsWith("magnet") && videoUrl.contains("index=")) {
            index = try {
                videoUrl.substringAfter("index=").substringBefore("&").toInt()
            } catch (_: NumberFormatException) {
                0
            }
        }

        val currentTorrent = torrentServerApi.addTorrent(videoUrl, title, "", "", false)
        return torrentServerUtils.getTorrentPlayLink(currentTorrent, index)
    }
    // <-- AM (SHARED_VIDEO_RESOLUTION_FIX)

    // AM (SHARED_HOSTER_RACE_FIX) -->
    // The actual shared "which video do we play" core - ported from
    // PlayerViewModel.loadHosters()'s own racing loop, which this now IS the
    // implementation of (loadHosters() calls this too - see its own updated
    // comment) rather than a second, independently-maintained copy of it. See
    // MODULAR_MERGING_SCOPE.md's "episode switching/loading" item and its own
    // "Recommended approach" paragraph for why the boundary is drawn here:
    // everything PAST finding the winning video - actually issuing the mpv
    // load, UI-state bookkeeping, retry-on-LOAD-failure (as opposed to
    // retry-on-RESOLUTION-failure, which this still covers) - still
    // legitimately differs per caller, since one has a real screen to update
    // and one doesn't, so it stays owned by each caller, wrapped around this
    // shared core instead of folded into it.
    //
    // [preferredHosterIndex]/[preferredVideoIndex] mirror loadHosters()'s own
    // manual-reselection parameters, unchanged. Left at -1/-1 (every
    // background skip, and every ordinary foreground episode switch) means
    // "just find the best one": any video the source itself flagged preferred
    // wins the race (whichever hoster answers first, same as loadHosters()
    // always did), falling back to HosterLoader.selectBestVideo() across
    // whatever came back Ready if nothing was flagged preferred - exactly
    // loadHosters()'s own original fallback logic, now actually shared instead
    // of separately reimplemented (or, background's case until tonight, not
    // implemented at all).
    //
    // [onHosterStateUpdate] is an optional per-hoster progress callback - the
    // foreground hoster-picker dialog's live Loading/Ready/Error display wires
    // into it; background has no UI to show this to and leaves it a no-op.
    //
    // Known, deliberate behavior difference from loadHosters()'s OLD inline
    // version: that version interleaved racing and loading - if the winning
    // hoster's video then failed at actual LOAD time, it reset the race flag
    // and let a later-arriving hoster's video win instead. Separating "find a
    // winner" from "load it" (needed to let both callers share this step)
    // means that specific retry-on-load-failure path no longer happens
    // automatically - a resolution-time failure (this function's own concern)
    // still falls through to the next hoster correctly, but a LOAD-time
    // failure of an already-resolved winner does not currently retry a
    // different hoster. Flagged rather than silently dropped - worth deciding
    // whether that retry path matters enough to rebuild across both callers,
    // or whether resolution-time coverage already handles the common case.
    suspend fun raceHostersForVideo(
        source: AnimeSource,
        hosters: List<Hoster>,
        preferredHosterIndex: Int = -1,
        preferredVideoIndex: Int = -1,
        onHosterStateUpdate: (Int, HosterState) -> Unit = { _, _ -> },
    ): Triple<Int, Int, Video>? {
        val hasFoundPreferredVideo = AtomicBoolean(false)
        val readyStates = arrayOfNulls<HosterState.Ready>(hosters.size)
        var winner: Triple<Int, Int, Video>? = null

        coroutineScope {
            hosters.mapIndexed { hosterIdx, hoster ->
                async {
                    val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)
                    onHosterStateUpdate(hosterIdx, hosterState)
                    if (hosterState !is HosterState.Ready) return@async
                    readyStates[hosterIdx] = hosterState

                    if (hosterIdx == preferredHosterIndex) {
                        hosterState.videoList.getOrNull(preferredVideoIndex)?.let { video ->
                            if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                                val resolved = HosterLoader.getResolvedVideo(source, video) ?: video
                                winner = Triple(hosterIdx, preferredVideoIndex, resolved)
                            }
                        }
                    }

                    val prefIndex = hosterState.videoList.indexOfFirst { it.preferred }
                    if (prefIndex != -1 && preferredHosterIndex == -1) {
                        if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                            val prefVideo = hosterState.videoList[prefIndex]
                            val resolved = HosterLoader.getResolvedVideo(source, prefVideo) ?: prefVideo
                            winner = Triple(hosterIdx, prefIndex, resolved)
                        }
                    }
                }
            }.awaitAll()
        }

        if (winner == null && hasFoundPreferredVideo.compareAndSet(false, true)) {
            val readyList = hosters.indices.map { idx ->
                readyStates[idx] ?: HosterState.Idle(hosters[idx].hosterName)
            }
            val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(readyList)
            if (hosterIdx != -1) {
                val chosen = (readyList[hosterIdx] as HosterState.Ready).videoList[videoIdx]
                val resolved = HosterLoader.getResolvedVideo(source, chosen) ?: chosen
                winner = Triple(hosterIdx, videoIdx, resolved)
            }
        }

        return winner
    }
    // <-- AM (SHARED_HOSTER_RACE_FIX)

    /**
     * Does the actual resolution + mpv load for [switchToEpisode] - split out
     * so its own early-return-on-failure points only exit THIS function, letting
     * the caller's single finally block handle pause restoration uniformly instead
     * of every early-return needing to do it individually.
     */
    private suspend fun resolveAndLoadTarget(animeId: Long, targetEpisodeId: Long): Boolean {
            val anime = getAnime.await(animeId) ?: run {
                logcat(LogPriority.DEBUG) { "resolveAndLoadTarget: getAnime($animeId) returned null" }
                return false
            }
            val episode = getEpisode.await(targetEpisodeId) ?: run {
                logcat(LogPriority.DEBUG) { "resolveAndLoadTarget: getEpisode($targetEpisodeId) returned null" }
                return false
            }
            val source = sourceManager.getOrStub(anime.source)

            // AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
            // Resume position priority mirrors PlayerViewModel.setVideo()'s own rules -
            // the shared recent-positions cache first (flipping back to an episode you
            // were just on, however briefly, is the strongest signal available - and
            // now survives a cold start too), otherwise resolveResumePositionMs()'s
            // shared DB-fallback decision (see its own doc comment).
            val preservePosition = playerPreferences.preserveWatchingPosition.get()
            val tempPositionMs = recentEpisodePositionManager.consume(animeId, targetEpisodeId)
            val resumePositionMs = resolveResumePositionMs(
                tempPositionMs = tempPositionMs,
                episodeSeen = episode.seen,
                episodeLastSecondSeenMs = episode.lastSecondSeen,
                preservePosition = preservePosition,
            )
            // <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
            // AM (RESUME_POSITION_DIAGNOSTIC) -->
            // Temporary - shows exactly which source won and what it produced, so a
            // "resumed at a weird time" report can be traced to a specific cause
            // instead of guessed at. Remove once confirmed working correctly.
            logcat(LogPriority.DEBUG) {
                "RESUME_POSITION_DIAGNOSTIC: episodeId=$targetEpisodeId tempPositionMs=$tempPositionMs " +
                    "episode.seen=${episode.seen} preservePosition=$preservePosition " +
                    "episode.lastSecondSeen=${episode.lastSecondSeen} -> resumePositionMs=$resumePositionMs"
            }
            // <-- AM (RESUME_POSITION_DIAGNOSTIC)
            // <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)

            // AM (BACKGROUND_SKIP_LOAD_CONFIRM_FIX) -->
            // Used to wait here for mpv to confirm the file actually loaded before
            // committing this to session state, on the reasoning that showing the
            // new episode's info before it was confirmed playing would be
            // committing to something not genuinely true yet. Deleted, not fixed
            // further - see the FILE_LOADED_RACE_FIX note in adopt() for the full
            // reasoning and the debugging session that led here, but the short
            // version: PlayerViewModel.setupEpisode() calls
            // syncHolderSessionState() - this exact function's own equivalent -
            // immediately upon deciding to switch, before hoster resolution even
            // starts, let alone before mpv confirms anything. Foreground has never
            // waited for confirmation before committing episode info, including on
            // paths that go on to fail entirely (a failed hoster/video resolution
            // still leaves the attempted episode's title showing, no rollback) -
            // this now matches that exactly, rather than being uniquely more
            // "correct" via background-only machinery with nothing on the other
            // side to justify its existence, machinery which was also the actual
            // source of tonight's entire debugging session.
            // <-- AM (BACKGROUND_SKIP_LOAD_CONFIRM_FIX)
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
            // Duration deliberately left untouched (previous episode's value
            // persists) until the real new duration arrives via the propFlow
            // observer - resetting it to 0 here previously made the lock-screen's
            // seek bar disappear entirely (Android's media widget appears to treat
            // duration=0 as "no seekable content"), worse than the staleness this
            // was meant to fix.
            // <-- AM (BACKGROUND_SKIP_DURATION_FIX)

            val hosters = EpisodeLoader.getHosters(episode, anime, source)
            if (hosters.isEmpty()) {
                logcat(LogPriority.DEBUG) { "resolveAndLoadTarget: no hosters returned" }
                return false
            }

            // AM (SHARED_HOSTER_RACE_FIX) -->
            // Was, in order: hosters.firstOrNull() with no retry at all; then a
            // sequential per-hoster retry loop; then a PARALLEL retry loop with
            // its own deterministic-priority winner selection - each version an
            // improvement, but still a second, independently-maintained
            // implementation of "which video do we actually play" alongside
            // PlayerViewModel.loadHosters()'s own. raceHostersForVideo() is that
            // shared core now - see its own doc comment. This closes the actual
            // duplication, not just narrows the behavioral gap between two
            // separate copies.
            val (_, _, video) = raceHostersForVideo(source, hosters) ?: run {
                logcat(LogPriority.DEBUG) { "resolveAndLoadTarget: no hoster produced a playable video" }
                return false
            }
            // <-- AM (SHARED_HOSTER_RACE_FIX)

            val httpSource = source as? AnimeHttpSource
            if (httpSource != null) {
                val headers = (video.headers ?: httpSource.headers)
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
            val videoOptions = (video.mpvArgs + mpvOpts).joinToString(",") { (option, value) ->
                "$option=\"$value\""
            }

            // AM (SHARED_VIDEO_RESOLUTION_FIX) -->
            // Was two unsupported-checks (usesHttpServer()/torrent-URL) that
            // returned false outright. Now calls the same function
            // PlayerViewModel.setVideo()'s torrent/proxy branching is meant to be
            // repointed at too (see resolveFinalVideoUrl()'s own comment) -
            // closing the other confirmed capability gap from
            // MODULAR_MERGING_SCOPE.md, through one shared implementation instead
            // of two.
            val resolvedVideo = resolveFinalVideoUrl(video, source) ?: run {
                logcat(LogPriority.DEBUG) { "resolveAndLoadTarget: resolveFinalVideoUrl failed" }
                return false
            }
            val resolvedUrl = resolvedVideo.videoUrl
            // <-- AM (SHARED_VIDEO_RESOLUTION_FIX)

            if (resumePositionMs > 0L) {
                player.mpv.command("set", "start", (resumePositionMs / 1000L).toString())
            }

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

            // AM (BACKGROUND_THUMBNAIL_BACKFILL_FIX) -->
            // Called unconditionally now, not gated behind a load-confirmed check -
            // matches foreground's own fileLoaded() trigger in spirit (mpv should
            // have had time to decode a real frame), but relies on this function's
            // own internal 3s delay plus its "still on this episode" guard for
            // correctness rather than an external confirmation wait, the same way
            // the rest of this function no longer waits for one either.
            generateEpisodeThumbnailIfMissing(anime, episode, targetEpisodeId)
            // <-- AM (BACKGROUND_THUMBNAIL_BACKFILL_FIX)

            logcat(LogPriority.DEBUG) { "resolveAndLoadTarget: issued load for episode $targetEpisodeId" }
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

    // See BACKGROUND_PLAYBACK_ERROR_LOOP_GUARD in adopt()
    private var lastPlaybackErrorRetryAt = 0L
    // See handleEpisodeCompletion()'s own guard note
    private var lastCompletedEpisodeId: Long? = null

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
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG PlayerMediaHolder.adopt() holder=${System.identityHashCode(this)} " +
                "incomingPlayer=${System.identityHashCode(existing)} currentPlayer=${System.identityHashCode(_player)} " +
                "willAccept=${_player == null} at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
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
            // switchToAdjacentEpisode() changed episodeTitle - independent of, and
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
            // AM (SHARED_WATCH_PROGRESS_FIX) -->
            // Was a manual delay(1_000) loop approximating foreground's cadence
            // with a wall-clock timer. This is the actual mechanism instead:
            // PlayerViewModel.onSecondReached() isn't driven by a timer at all -
            // it's mpv.propFlow<Int>("time-pos"), a native property-change
            // observer, tied to real playback progress rather than wall-clock
            // time (naturally pauses when playback pauses, can't drift). mpv is
            // this holder's own object regardless of Activity (same one
            // player.mpv.command()/getPropertyInt() calls already use
            // throughout this file), so this is the SAME trigger foreground
            // uses, not an approximation of it - genuinely one driver for both,
            // not two independent ones that happen to behave similarly.
            // PlayerViewModel.onSecondReached() no longer calls
            // saveWatchingProgress() itself at all (see its own updated
            // comment) - this collector now covers that for every session,
            // foreground included, so a second, redundant call per tick would
            // just be wasted work.
            //
            // AM (WATCH_PROGRESS_TIMER_DIAGNOSTIC) -->
            // Temporary - confirming this collector (and this write) keep
            // firing for a whole session, not just for a while and then going
            // silent the way a persistent eventFlow collector did earlier
            // (never root-caused, only worked around by deleting what depended
            // on it) - if holderScope-hosted work has a general tendency to
            // die after some point, this could be silently affected the same
            // way. Remove once confirmed either way.
            var watchProgressTick = 0
            existing.mpv.propFlow<Int>("time-pos")
                .filterNotNull()
                .onEach { position ->
                    watchProgressTick++
                    if (watchProgressTick % 15 == 0) {
                        logcat(LogPriority.DEBUG) {
                            "WATCH_PROGRESS_TIMER_DIAGNOSTIC: tick=$watchProgressTick, about to save"
                        }
                    }
                    saveWatchingProgress()
                    if (watchProgressTick % 15 == 0) {
                        logcat(LogPriority.DEBUG) {
                            "WATCH_PROGRESS_TIMER_DIAGNOSTIC: tick=$watchProgressTick, save completed"
                        }
                    }

                    // AM (SHARED_EPISODE_COMPLETION_FIX) -->
                    // Same trigger foreground's onSecondReached() uses - a
                    // percentage-of-duration threshold, not waiting for EOF,
                    // matching its own progressPreference setting exactly.
                    // See handleEpisodeCompletion()'s own doc comment for the
                    // gap this closes and its repeat-call guard.
                    val duration = existing.mpv.getPropertyInt("duration") ?: 0
                    val progress = playerPreferences.progressPreference.get()
                    if (duration > 0 && position >= duration * progress) {
                        val currentState = state.value
                        val animeId = currentState.animeId
                        val episodeId = currentState.episodeId
                        if (animeId != null && episodeId != null) {
                            holderScope.launch { handleEpisodeCompletion(animeId, episodeId) }
                        }
                    }
                    // <-- AM (SHARED_EPISODE_COMPLETION_FIX)

                    // AM (SHARED_DOWNLOAD_AHEAD_FIX) -->
                    // Same 35%-through-the-episode trigger foreground's own
                    // inDownloadRange check uses. See
                    // downloadNextEpisodes()'s own doc comment.
                    val currentStateForDownload = state.value
                    val downloadAnimeId = currentStateForDownload.animeId
                    val downloadEpisodeId = currentStateForDownload.episodeId
                    if (duration > 0 && downloadAnimeId != null && downloadEpisodeId != null &&
                        position.toDouble() / duration > 0.35
                    ) {
                        holderScope.launch { downloadNextEpisodes(downloadAnimeId, downloadEpisodeId) }
                    }
                    // <-- AM (SHARED_DOWNLOAD_AHEAD_FIX)
                }
                .launchIn(holderScope)
            // <-- AM (WATCH_PROGRESS_TIMER_DIAGNOSTIC)
            // <-- AM (SHARED_WATCH_PROGRESS_FIX)
            // <-- AM (BACKGROUND_SEEKBAR_TICK_FIX)

            // AM (SHARED_AUTO_SKIP_INTRO_FIX) -->
            // Another confirmed autonomous-behavior gap, same shape as
            // autoplay/download-ahead/episode-completion - PlayerViewModel's
            // onChapterChanged() auto-skips an intro/outro chapter when
            // autoSkip is enabled, with no user interaction at all, but
            // reacts to mpv's own "chapter" property (a propFlow observer,
            // same mechanism as "time-pos"). Zero equivalent existed here, so
            // auto-skip-intro simply never happened for anything watched
            // purely in the background, regardless of the preference.
            //
            // Only the plain autoSkip branch is ported - foreground's
            // netflixStyle mode (a cancelable countdown toast) is genuinely
            // UI-only; there's no meaningful "countdown you can cancel"
            // without a screen. Rather than default that mode to either
            // auto-skip or do-nothing arbitrarily, doing nothing matches its
            // own semantics more faithfully: netflix-style's whole point is
            // "wait for permission before skipping," and no permission can be
            // given without a screen to ask on.
            existing.mpv.propFlow<Int>("chapter")
                .filterNotNull()
                .onEach { chapterIndex ->
                    if (!playerPreferences.enableSkipIntro.get()) return@onEach
                    if (playerPreferences.enableNetflixStyleIntroSkip.get()) return@onEach
                    if (!playerPreferences.autoSkipIntro.get()) return@onEach

                    val chapterList = existing.mpv.getPropertyNode("chapter-list")
                        ?.toObject<List<ChapterNode>>(json) ?: return@onEach
                    val chapter = chapterList.getOrNull(chapterIndex) ?: return@onEach
                    if (chapter.chapterType == ChapterType.Other) return@onEach

                    existing.mpv.command("add", "chapter", "1")
                }
                .launchIn(holderScope)
            // <-- AM (SHARED_AUTO_SKIP_INTRO_FIX)

            // AM (BACKGROUND_AUTOPLAY_FIX) -->
            // Mirrors PlayerViewModel.eofReached() - which only ever reacted to
            // player.eventFlow while a ViewModel existed to collect it
            // (wirePlayerFlows(), viewModelScope-bound). player.eventFlow itself
            // lives on the MPVPlayer object, not the ViewModel, so it's just as
            // observable from here - reusing the already-working
            // switchToAdjacentEpisode() (see MEDIA_SESSION_FALLBACK_CALLBACK) to
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
                        switchToAdjacentEpisode(next = true)
                    }
                }
                .launchIn(holderScope)
            // <-- AM (BACKGROUND_AUTOPLAY_FIX)
            //
            // AM (BACKGROUND_PLAYBACK_ERROR_FIX) -->
            // Confirmed real gap while actually tracing handlePlayerFlow() (see
            // MODULAR_MERGING_SCOPE.md's own suggested next step) - foreground's
            // endFile() reacts to a mid-stream playback failure (file_error
            // present on EndFile - a dropped connection, an expired hoster
            // link) with a toast, an error UI state, and - if switchOnFailure
            // is enabled - an automatic retry. PlayerMediaHolder had NO
            // reaction to this event at all: a background session whose
            // playback failed mid-stream just silently stopped, nothing
            // retried, nothing updated, the notification left showing
            // whatever it last had - exactly the kind of "frozen/stuck"
            // symptom this whole session has been chasing, just from a
            // different cause than any of the ones already found tonight.
            //
            // Not a full port of loadBestVideo() - that cycles through
            // specific hoster/video UI state (uiData.selectedHosterVideoIndex,
            // the hosterState array) this holder has no equivalent of and has
            // no reason to build one for. The actual retry need is simpler:
            // re-run resolution for the SAME episode, which raceHostersForVideo()
            // already does a full job of (tries every hoster fresh, not just
            // the one that just failed). Reuses resolveAndLoadTarget() for
            // that rather than reimplementing hoster iteration a third time.
            existing.eventFlow
                .filterIsInstance<MPVPlayer.Event.EndFile>()
                .onEach { event ->
                    val errorNode = event.node.asMap()?.get("file_error") ?: return@onEach
                    val errorMessage = errorNode.asString() ?: "Error: File ended"
                    logcat(LogPriority.ERROR) { "resolveAndLoadTarget: playback failed - $errorMessage" }
                    player.resetHttpError()

                    // AM (BACKGROUND_PLAYBACK_ERROR_LOOP_GUARD) -->
                    // If every hoster is genuinely down (no network), a retry can
                    // itself fail and trigger another EndFile, retrying again
                    // indefinitely. A short cooldown between retries turns a tight
                    // infinite loop into, at worst, one attempt every few seconds -
                    // still recovers promptly from a single transient failure, still
                    // stops hammering a genuinely dead connection.
                    val now = System.currentTimeMillis()
                    if (now - lastPlaybackErrorRetryAt < 3_000L) return@onEach
                    lastPlaybackErrorRetryAt = now
                    // <-- AM (BACKGROUND_PLAYBACK_ERROR_LOOP_GUARD)

                    val currentState = state.value
                    val animeId = currentState.animeId
                    val episodeId = currentState.episodeId
                    if (playerPreferences.switchOnFailure.get() && animeId != null && episodeId != null) {
                        holderScope.launch { resolveAndLoadTarget(animeId, episodeId) }
                    }
                }
                .launchIn(holderScope)
            // <-- AM (BACKGROUND_PLAYBACK_ERROR_FIX)
            // AM (FILE_LOADED_RACE_FIX) -->
            // A persistent collector feeding a "load confirmed" generation
            // counter used to live here, backing resolveAndLoadTarget()'s own
            // synchronous wait for mpv's FileLoaded confirmation before
            // committing episode info. Deleted entirely, not fixed further -
            // see BACKGROUND_SKIP_LOAD_CONFIRM_FIX's replacement comment in
            // resolveAndLoadTarget() for why: that whole synchronous-wait
            // mechanism had no foreground equivalent to begin with.
            // PlayerViewModel.setupEpisode() calls syncHolderSessionState() -
            // the exact function resolveAndLoadTarget()'s own syncSessionState()
            // call mirrors - immediately upon deciding to switch, before hoster
            // resolution even starts, let alone before mpv confirms anything.
            // Foreground has never waited for confirmation before committing
            // episode info; this was purely background-only complexity with
            // nothing on the other side to justify it, and was also the actual
            // source of tonight's whole debugging session (a confirmed dropped-
            // event race, then a confirmed-but-still-mysterious collector-death
            // bug on top of the fix for that) - removing it removes the entire
            // bug class at once, rather than continuing to debug machinery that
            // shouldn't have existed as a background-only mechanism in the
            // first place.
            // <-- AM (FILE_LOADED_RACE_FIX)
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
    // AM (MEDIA_SESSION_SERVICE_ONLY) -->
    // No longer takes an external callback or context parameter - always uses
    // this holder's own mediaSessionCallback and its own stored context, since
    // there's no more Activity-owned callback to install instead. Also now
    // sets up the session's PlaybackState itself (used to be done separately,
    // each time PlayerActivity's setupMediaSessionCallback() ran) - the same
    // actions every time, regardless of caller, so there's no reason for this
    // to live outside the one place that actually owns the session.
    // <-- AM (MEDIA_SESSION_SERVICE_ONLY)
    fun ensureMediaSession(): MediaSession {
        mediaSession?.let {
            it.setCallback(mediaSessionCallback)
            return it
        }
        return MediaSession(context, "PlayerMediaHolder").apply {
            setCallback(mediaSessionCallback)
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT,
                    )
                    .build(),
            )
            isActive = true
        }.also { mediaSession = it }
    }

    fun updateState(transform: (PlayerMediaState) -> PlayerMediaState) {
        _state.value = transform(_state.value)
    }

    // AM (SHARED_SESSION_SYNC_FIX) -->
    // Replaces two separately-written copies of this exact update -
    // PlayerViewModel.syncHolderSessionState() (foreground) and this holder's own
    // resolveAndLoadTarget() (background skip, no live Activity) each built
    // their own it.copy(...) call against the same PlayerMediaState fields. That's
    // exactly how the two silently drifted apart once already this session -
    // resolveAndLoadTarget() simply never included animeTitle in its copy(),
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
    // AM (SHARED_WATCH_PROGRESS_FIX) -->
    // The single writer for watch progress/history now lives here, not in
    // PlayerViewModel - confirmed real, silent data-loss bug: PlayerViewModel's
    // own onSecondReached() drove the only DB write (last_second_seen/seen/
    // history), launched in viewModelScope, which dies the instant the Activity
    // does. Any playback happening purely in the background (headphones, screen
    // off, notification controls only, Activity never reopened) was tracked in
    // this holder's own state.positionMs for UI purposes only - explicitly, per
    // this file's own LIVE_POSITION_TRACKING comment, "completely independent
    // of any ViewModel" - meaning it never reached the database. If the process
    // died before the app was ever reopened, that progress was gone for good.
    //
    // Moved here rather than also writing from the ViewModel, deliberately -
    // two independent writers to the same DB rows is exactly the class of bug
    // this codebase has hit more than once already (BACKGROUND_SEEKBAR_FIGHT_
    // FIX, HOLDER_PAUSE_STATE_SYNC_FIX). This holder is the correct single
    // owner: it's alive for the entire time anything is actually playing -
    // video can't play without the notification/MediaSession existing, and
    // both depend on this holder - while PlayerViewModel is only alive some of
    // that time. Every PlayerViewModel call site that used to write directly
    // now delegates to this instead of keeping its own separate implementation.
    //
    // Always reads the episode fresh via getEpisode.await() rather than
    // trusting a long-lived cached copy - this holder never keeps one, so the
    // staleness-reconciliation PlayerViewModel's own old version needed (a
    // separately-tracked Episode object going stale against concurrent DB
    // changes) doesn't apply here at all; there's nothing to go stale.
    //
    // [writeSeenState] mirrors the original TICK_NEVER_OWNS_SEEN rule: false
    // for every routine call (a tick only owns position/duration), true only
    // for a call that's intentionally marking the episode seen right now.
    // [writeHistory] exists only for onSaveInstanceStateNonConfigurationChange()'s
    // original behavior (progress only, no history entry on a config-change
    // save) - true everywhere else.
    // AM (SHARED_RESUME_POSITION_FIX) -->
    // The actual shared tail-end decision, not just matching logic ported into
    // two separate implementations (an earlier pass tonight added the missing
    // preserveWatchingPosition condition to background's OWN copy, matching
    // foreground's outcome without the two actually sharing any code - this
    // is the real fix). Both PlayerViewModel.setVideo() and
    // resolveAndLoadTarget() call this now for the final decision.
    //
    // [tempPositionMs] is whatever higher-priority live/temp source each
    // caller already checked, or null if none applied - background passes
    // recentEpisodePositionManager.consume()'s result directly; foreground
    // layers its own extra, legitimately foreground-only priority tier on
    // top first (a live-holder-state check gated to a cold-start/process-
    // death reinit scenario, with no equivalent for an in-session background
    // skip - see setVideo()'s own RESUME_PRIORITY_SYSTEM comment) before
    // ever reaching this shared tail.
    fun resolveResumePositionMs(
        tempPositionMs: Long?,
        episodeSeen: Boolean,
        episodeLastSecondSeenMs: Long,
        preservePosition: Boolean,
    ): Long {
        return if (tempPositionMs != null && tempPositionMs > 0) {
            tempPositionMs
        } else if (episodeSeen && !preservePosition) {
            0L
        } else {
            episodeLastSecondSeenMs
        }
    }
    // <-- AM (SHARED_RESUME_POSITION_FIX)

    suspend fun saveWatchingProgress(writeSeenState: Boolean = false, writeHistory: Boolean = true) {
        val currentState = state.value
        val animeId = currentState.animeId ?: run {
            logcat(LogPriority.DEBUG) { "WATCH_PROGRESS_TIMER_DIAGNOSTIC: bailed - animeId null" }
            return
        }
        val episodeId = currentState.episodeId ?: run {
            logcat(LogPriority.DEBUG) { "WATCH_PROGRESS_TIMER_DIAGNOSTIC: bailed - episodeId null" }
            return
        }
        val positionMs = currentState.positionMs.toLong()
        val durationMs = currentState.durationMs.toLong()
        if (durationMs <= 0L) {
            logcat(LogPriority.DEBUG) { "WATCH_PROGRESS_TIMER_DIAGNOSTIC: bailed - durationMs=$durationMs" }
            return
        }

        val anime = getAnime.await(animeId) ?: run {
            logcat(LogPriority.DEBUG) { "WATCH_PROGRESS_TIMER_DIAGNOSTIC: bailed - getAnime($animeId) null" }
            return
        }
        val incognito = getIncognitoState.await(anime.source)
        val hasTrackers = getTracks.await(animeId).isNotEmpty()
        if (incognito && !hasTrackers) {
            logcat(LogPriority.DEBUG) { "WATCH_PROGRESS_TIMER_DIAGNOSTIC: bailed - incognito with no trackers" }
            return
        }

        val fresh = getEpisode.await(episodeId) ?: run {
            logcat(LogPriority.DEBUG) { "WATCH_PROGRESS_TIMER_DIAGNOSTIC: bailed - getEpisode($episodeId) null" }
            return
        }

        // AM (PRESERVE_POSITION_SETTING) -->
        // Mirrors both of PlayerViewModel's own guards under this same tag: a
        // routine tick skips writing a live position over an already-seen
        // episode unless the user opted in to preserving it; a writeSeenState
        // call (marking the episode seen right now) resets position to 0 for
        // the same reason "finished" clears near-the-end position - unless
        // that same preference says to keep it.
        // <-- AM (PRESERVE_POSITION_SETTING)
        val preservePosition = playerPreferences.preserveWatchingPosition.get()
        val newLastSecondSeen = when {
            writeSeenState -> if (preservePosition) positionMs else 0L
            !fresh.seen || preservePosition -> positionMs
            else -> fresh.lastSecondSeen
        }

        updateEpisode.await(
            EpisodeUpdate(
                id = episodeId,
                seen = if (writeSeenState) true else null,
                lastSecondSeen = newLastSecondSeen,
                totalSeconds = durationMs,
            ),
        )
        // AM (WATCH_PROGRESS_TIMER_DIAGNOSTIC) -->
        logcat(LogPriority.DEBUG) {
            "WATCH_PROGRESS_TIMER_DIAGNOSTIC: WROTE episodeId=$episodeId lastSecondSeen=$newLastSecondSeen"
        }
        // <-- AM (WATCH_PROGRESS_TIMER_DIAGNOSTIC)

        if (writeHistory && !incognito) {
            upsertHistory.await(HistoryUpdate(episodeId, Date()))
        }

        if (syncPreferences.isSyncEnabled() &&
            syncPreferences.getSyncTriggerOptions().syncOnEpisodeOpen &&
            newLastSecondSeen >= 1L
        ) {
            SyncDataJob.startNow(context)
        }
    }
    // <-- AM (SHARED_WATCH_PROGRESS_FIX)

    // AM (SHARED_EPISODE_COMPLETION_FIX) -->
    // Confirmed real, more fundamental gap while tracing episode management
    // (see MODULAR_MERGING_SCOPE.md's own untraced list) - PlayerMediaHolder's
    // EOF collector only ever advanced to the next episode when autoplay was
    // on, and even then never marked the JUST-FINISHED episode seen first.
    // With autoplay off, an episode finishing purely in the background never
    // got marked watched at all - not a tracker-sync/auto-delete gap
    // specifically, more basic than that: continue-watching lists and
    // library "unwatched" badges would all be wrong for anything watched
    // entirely in the background, regardless of any other preference.
    //
    // Ported from PlayerViewModel.updateEpisodeProgressOnComplete() +
    // updateTrackEpisodeSeen() + deleteEpisodeIfNeeded() - genuinely portable,
    // no UI dependency in any of the three (plain interactor/repository
    // calls), unlike the resolution-retry logic inside loadVideo() that
    // turned out to have no equivalent need in the first place. Duplicate-
    // episode-marking (markDuplicateSeenEpisodeAsSeen) NOT ported - that one
    // reads stateData.value.currentPlaylist, a full ordered list of Episode
    // domain objects PlayerViewModel keeps loaded for its own UI (episode
    // list display) that this holder has no equivalent of (playlistEpisodeIds
    // is IDs only) - a real, additional piece of state to build just for this
    // one feature, not yet worth it for how narrow the feature itself is.
    //
    // Guards against firing on every tick once past the completion threshold
    // (foreground's own onSecondReached() doesn't guard this either - same
    // condition stays true every subsequent tick - but that's a much smaller
    // problem there: a foreground session is rarely left sitting past
    // episode-end unattended for long, while a background one very much can
    // be) - lastCompletedEpisodeId tracks the last episode this already ran
    // for, skipping repeat calls until a genuinely different episode
    // completes.
    suspend fun handleEpisodeCompletion(animeId: Long, episodeId: Long) {
        if (lastCompletedEpisodeId == episodeId) return
        lastCompletedEpisodeId = episodeId

        saveWatchingProgress(writeSeenState = true)

        val anime = getAnime.await(animeId) ?: return
        val incognito = getIncognitoState.await(anime.source)
        val hasTrackers = getTracks.await(animeId).isNotEmpty()

        if (!incognito && hasTrackers && trackPreferences.autoUpdateTrack.get()) {
            val episode = getEpisode.await(episodeId) ?: return
            trackEpisode.await(context, animeId, episode.episodeNumber)
        }

        val removeAfterSeenSlots = downloadPreferences.removeAfterSeenSlots.get()
        if (removeAfterSeenSlots != -1) {
            val currentPosition = playlistEpisodeIds.indexOf(episodeId)
            val episodeIdToDelete = playlistEpisodeIds.getOrNull(currentPosition - removeAfterSeenSlots)
            val episodeToDelete = episodeIdToDelete?.let { getEpisode.await(it) }
            if (episodeToDelete != null && episodeToDelete.seen) {
                downloadManager.enqueueEpisodesToDelete(listOf(episodeToDelete), anime)
            }
        }
    }
    // <-- AM (SHARED_EPISODE_COMPLETION_FIX)

    // AM (SHARED_DOWNLOAD_AHEAD_FIX) -->
    // Another confirmed gap while tracing episode management - unlike
    // bookmarkEpisode()/fillermarkEpisode() (genuinely foreground-only: a
    // manual UI toggle has no autonomous trigger to share), this one isn't
    // user-triggered at all - foreground's own downloadNextEpisodes() fires
    // automatically off playback progress (35% through the current episode),
    // the exact same shape as the episode-completion threshold check above.
    // PlayerMediaHolder had no equivalent, meaning auto-download-ahead simply
    // didn't happen for anything watched purely in the background.
    //
    // Uses playlistEpisodeIds (IDs only) plus a couple of getEpisode.await()
    // calls rather than needing a cached list of full Episode objects the
    // way foreground's stateData.currentPlaylist does - narrower data
    // requirement than markDuplicateSeenEpisodeAsSeen's, which is why that
    // one wasn't worth building for and this one was.
    private suspend fun downloadNextEpisodes(animeId: Long, currentEpisodeId: Long) {
        val downloadAheadAmount = downloadPreferences.autoDownloadWhileWatching.get()
        if (downloadAheadAmount == 0) return

        val currentIndex = playlistEpisodeIds.indexOf(currentEpisodeId)
        if (currentIndex == -1 || currentIndex == playlistEpisodeIds.lastIndex) return
        val nextEpisodeId = playlistEpisodeIds[currentIndex + 1]

        val anime = getAnime.await(animeId) ?: return
        val currentEpisode = getEpisode.await(currentEpisodeId) ?: return
        val nextEpisode = getEpisode.await(nextEpisodeId) ?: return

        val episodesAreDownloaded = EpisodeLoader.isDownload(currentEpisode, anime) &&
            EpisodeLoader.isDownload(nextEpisode, anime)
        if (!episodesAreDownloaded) return

        val episodesToDownload = getNextEpisodes.await(animeId, nextEpisodeId).take(downloadAheadAmount)
        downloadManager.downloadEpisodes(anime, episodesToDownload)
    }
    // <-- AM (SHARED_DOWNLOAD_AHEAD_FIX)

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
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG PlayerMediaHolder.release() holder=${System.identityHashCode(this)} " +
                "player=${System.identityHashCode(_player)} hasAdoptedPlayer=$hasAdoptedPlayer " +
                "at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
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
        // SVC_RACE_DEBUG -->
        logcat {
            "SVC_RACE_DEBUG PlayerMediaHolder.release() clearing current: holder=${System.identityHashCode(this)} " +
                "current===this=${current === this} currentIs=${current?.let { System.identityHashCode(it) }} " +
                "at=${android.os.SystemClock.elapsedRealtime()}"
        }
        // <-- SVC_RACE_DEBUG
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
