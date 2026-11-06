package aniyomi.player.clip

import aniyomi.ui.browse.scroller.ScrollerFeedMedia
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import eu.kanade.tachiyomi.ui.player.mpv.loadFileWithHwdecGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime
import kotlin.math.abs

// AM (CLIP_PLAYBACK) -->
/**
 * Playing one entry: its video, its separate audio where it has one, and the
 * rules that keep the two in step.
 *
 * This is the browse feed's playback, moved out of the feed. It was written
 * there first because that is where the entries that need it turned up, but
 * nothing in it is about a feed - it is what "play this entry correctly"
 * means, and the real player plays the same entries. Keeping a second answer
 * to the same question in the session player is what produced a clip that
 * looped seamlessly in the feed and stopped dead at its own end in the player,
 * and audio that the feed played and the player did not have at all.
 *
 * Two hosts, two ways in, one set of rules:
 *
 * - The feed owns its instances outright and calls [loadPaused], which issues
 *   both loads itself.
 * - The session player calls the same [loadPaused] for an entry whose listing
 *   states its streams, over the instance PlayerMediaHolder adopted - so
 *   MediaSession, PIP, background playback, position tracking and cast go on
 *   reading the same object while the load itself is this one. Its own pause
 *   and seek reach the companion by watching the instance's own pause
 *   property and through [mirrorCommand].
 *
 * What both get:
 *
 * Separate audio on its own instance, because mpv's `audio-file` shares the
 * video's timeline - the video's loop restarts the audio with it, so a clip
 * whose audio is longer than its video can never play through.
 *
 * A readiness gate. Neither instance plays until every stream this entry needs
 * has opened. Frame display is a separate host concern, so a short paused file
 * cannot deadlock the gate by waiting for a restart that never arrives.
 *
 * Only the shorter stream loops, once both lengths are known. The longer one
 * runs once and defines the entry's duration and completion; a single-stream
 * entry does not repeat merely because its source labels it a looping clip.
 *
 * And the buffer that makes the loop seamless. A loop is a seek backwards to
 * the start; a seek outside what the demuxer still holds is a re-read, which
 * drops the forward cache, refills it from the network and - with
 * `cache-pause-wait` - stops playback while it does. Enough buffer to hold a
 * short clip whole makes the loop a cache seek instead.
 */
class ClipPlayback(
    val video: MPVPlayer,
    /**
     * The companion audio instance, where this host has one.
     *
     * The feed builds both up front, since every card may need one. The
     * session player creates one only for an entry that turns out to have
     * separate audio, which is why this is a factory rather than an instance -
     * an idle mpv instance per session, for the entries that do not, is a
     * decoder nobody asked for.
     */
    private val audioFactory: (() -> MPVPlayer)? = null,
    /**
     * True for a host that decides itself whether this entry makes sound -
     * the feed, where only the card being looked at does, and where the
     * instances are its own to take audio focus with.
     *
     * False for a host whose instance already holds focus and is already
     * unmuted on its own terms: the session player, where requesting focus
     * again would hand the holder an AUDIOFOCUS_LOSS (see MPVPlayer's own
     * notes) and where starting muted meant an entry that played silently.
     */
    private val managesAudio: Boolean = false,
    /** The longer stream finished, for a host whose normal EOF is on video. */
    private val onEntryEnded: (() -> Unit)? = null,
) {

    private var audio: MPVPlayer? = null

    private var loadedKey: Long? = null
    private var hasRequestedFocus = false
    private var shouldPlay = false
    /** True unless a host that manages audio says otherwise - see [managesAudio]. */
    private var mayHoldAudioFocus = !managesAudio
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadGeneration = 0
    private var videoReady = false
    private var audioReady = true

    // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS) -->
    /**
     * Where this load is to be put once both streams are open, for a pair that
     * cannot be opened there directly.
     *
     * `start=` is a per-file option, and the two files of a pair are not at
     * the same time as each other: the position belongs to the longer stream,
     * which runs once, while the shorter one is some number of repetitions
     * into its own length at that moment. Handing both the same raw `start=`
     * opened the shorter file past its own end - and for a short video under a
     * longer audio track that is most of the entry, so the video sat at EOF
     * rendering nothing: no PlaybackRestart, no decoded frame, the readiness
     * gate below never opened, and the host went on showing a loading
     * indicator over a picture that was never going to arrive. The shorter the
     * video, the more of the entry does this, which is why it looked as though
     * short clips in particular were broken.
     *
     * [seekTo] already knows how one time maps onto two streams of different
     * lengths - it folds the target onto each stream's own length - so a pair
     * opens at zero and is seeked once ready, rather than having a second,
     * disagreeing rule for the same question at load time.
     */
    private var pendingResume: Float? = null
    // <-- AM (CLIP_RESUME_FOLDS_ONTO_STREAMS)

    /** True once a surface is attached, which is when loading can use hwdec. */
    private var surfaceReady = false
    private var pendingLoad: (() -> Unit)? = null

    /** What [prepare] last said, so a companion created later matches it. */
    private var looping = false

    /**
     * Whether the video is the shorter, repeating stream. Its EOF is a lap,
     * not completion; the longer stream is the entry's single-play timeline.
     */
    val videoIsRepeating: Boolean get() = looping && isSplit && audioIsLonger

    var isMuted: Boolean = managesAudio
        private set

    /** The companion instance, for a host that has to reach it directly. */
    val audioInstance: MPVPlayer? get() = audio

    /**
     * Prepares an entry whose shorter stream may repeat under its longer one.
     * Neither stream repeats until their lengths are known; an entry with one
     * stream, or two of equal length, simply plays once.
     *
     * Call before the load, and call it for non-looping entries too: an
     * instance is reused between them, and an ordinary episode must not
     * inherit a loop left behind by the clip before it.
     */
    fun prepare(looping: Boolean) {
        this.looping = looping
        setLooping(video, false)
        audio?.let { setLooping(it, false) }
        if (looping) applyPlaybackBuffer(preloading = false)
    }

    // AM (SCROLLER_PRELOAD_TIERS) -->
    /**
     * Which cache tier this entry gets while it is NOT the one being watched -
     * see [MPVPlayer.CacheTier].
     *
     * Kept rather than passed per call, because the thing that knows how close
     * an entry is to being watched (the feed) and the things that re-apply the
     * cache (a load, a reset, a companion being created) are not the same
     * callers. Starts at [MPVPlayer.CacheTier.Far], which is what an entry
     * nobody has placed yet deserves.
     */
    private var preloadTier = MPVPlayer.CacheTier.Far

    /** Caps what an entry nobody has reached yet may pull. */
    fun applyPreloadBuffer() {
        applyPlaybackBuffer(preloading = true)
    }

    /**
     * Says how close this entry now is to being watched.
     *
     * Applied immediately unless the entry is already playing, which is a tier
     * of its own and outranks any of this.
     */
    fun setPreloadTier(tier: MPVPlayer.CacheTier) {
        if (preloadTier == tier) return
        preloadTier = tier
        if (!shouldPlay) applyPlaybackBuffer(preloading = true)
    }
    // <-- AM (SCROLLER_PRELOAD_TIERS)

    /**
     * Readies the companion instance for use, creating it if this host builds
     * them on demand. Returns null where the host has no companion to give.
     */
    private fun companion(): MPVPlayer? {
        audio?.let { return it }
        val created = audioFactory?.invoke() ?: return null
        created.mpv.setOptionString("aid", "auto")
        created.mpv.setOptionString("vid", "no")
        created.mpv.setPropertyBoolean("mute", true)
        // AM (SCROLLER_PRELOAD_TIERS): the same tier its video half is on - a
        // companion left at Far under a Near video is the audio stalling on an
        // entry whose picture is held whole.
        created.setScrollerCache(if (shouldPlay) MPVPlayer.CacheTier.Playing else preloadTier)
        audio = created
        return created
    }

    /**
     * Called by the surface as it attaches and detaches.
     *
     * A load issued before a surface exists is forced to software decoding by
     * [loadFileWithHwdecGuard], so a load that arrives first is held here and
     * issued once there is something to decode into.
     */
    fun onSurfaceAttachedChanged(attached: Boolean) {
        surfaceReady = attached
        if (attached) {
            pendingLoad?.let { load ->
                pendingLoad = null
                load()
            }
        }
    }

    /**
     * Loads [url] and holds it on its first frame.
     *
     * [audioUrl] may be [url] itself, for a single file whose audio track
     * outlasts its video track - a muxed clip. No container can say that the
     * video repeats under the audio, and one demuxer cannot loop one of its
     * tracks without the other, so the file is opened twice: the video here
     * and the audio on the companion, each looping on its own length.
     *
     * Compared by [key] rather than by url: a url can go through
     * MediaProxyServer and carry a fresh registration every time it is built,
     * so comparing urls would see every pass as a new file and reload what is
     * already playing.
     *
     * [reload] skips that comparison, for a host whose call is always a
     * deliberate load rather than one of many passes over the same entry. The
     * session player's is: it asks once per episode load, and the instance it
     * shares can have been stopped or loaded with something else in between -
     * so "this key is already loaded" could be true of a key whose file is no
     * longer there, which is an entry that then played nothing at all.
     */
    fun loadPaused(
        key: Long,
        url: String,
        audioUrl: String?,
        httpHeaderFields: String?,
        resumeAt: Double? = null,
        reload: Boolean = false,
    ) {
        if (!reload && loadedKey == key) return
        loadedKey = key
        // Whatever was playing before is this load's to end: a companion left
        // running is the previous entry's audio still going under the new one.
        stopCompanion()
        // A reused instance may have repeated the previous entry's shorter
        // stream. This load starts unlooped; onStreamReady enables only its
        // own shorter stream once both lengths are known.
        setLooping(video, false)
        // Before the gate, not inside the load below: the gate subscribes to
        // the companion's own event flow, and a companion that does not exist
        // yet when it does leaves that subscription watching nothing - the
        // poll would still catch it, a tenth of a second later, every time.
        if (audioUrl != null) companion()
        // AM (CLIP_SPLIT_IS_THE_LOAD_NOT_THE_INSTANCE)
        splitLoad = audioUrl != null
        // AM (CLIP_TIMELINE_LENGTHS_ARE_LATCHED): this load's streams are not
        // the last one's, so neither are their lengths.
        latchedVideoLength = null
        latchedAudioLength = null
        // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS)
        pendingResume = resumeAt?.toFloat()?.takeIf { it > 0f && audioUrl != null }
        awaitStreamsOpen(key, url, audioUrl != null)
        followHostPause(key, loadGeneration)

        val issue = {
            // Set before the load, not after: mpv reads these when it opens
            // the stream, and a source needing a Referer fails the handshake
            // without them. Cleared rather than left behind when an entry
            // carries none, so one entry's headers never go to another's host.
            applyPlaybackBuffer(preloading = !shouldPlay)
            video.mpv.setOptionString("http-header-fields", httpHeaderFields ?: "")
            audio?.mpv?.setOptionString("http-header-fields", httpHeaderFields ?: "")
            // pause=yes in the load's own options, so the file is never
            // playing even for a frame before being paused.
            val options = buildList {
                add("pause=yes")
                // The same file on both instances: this one takes the video
                // and the companion takes the audio, so the video can repeat
                // on its own length while the audio plays through. Without
                // aid=no the file's audio would play on both.
                if (audioUrl == url) add("aid=no")
                // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS): a pair is put in place
                // by the seek once both streams are open - see pendingResume.
                if (resumeAt != null && audioUrl == null) add("start=$resumeAt")
            }.joinToString(",")
            video.mpv.loadFileWithHwdecGuard(
                url = url,
                options = options,
                hasAttachedSurface = video.hasAttachedSurfaceBefore,
            )
            loadCompanion(audioUrl, httpHeaderFields)
            // A surface-delayed load must not overwrite the requested state
            // with the loadfile pause=yes option. This is most visible on the
            // first entry, whose play request precedes its surface and whose
            // separate audio has no later video event to repair it.
            applyPlaybackState()
            applyMuteState()
            Unit
        }

        if (surfaceReady || video.hasAttachedSurfaceBefore) issue() else pendingLoad = issue
    }

    private fun loadCompanion(audioUrl: String?, httpHeaderFields: String?) {
        if (audioUrl == null) {
            audio?.mpv?.command("stop")
            return
        }
        val companion = companion() ?: return
        companion.mpv.setOptionString("http-header-fields", httpHeaderFields ?: "")
        setLooping(companion, false)
        val audioOptions = buildList {
            add("pause=yes")
            add("vid=no")
        }.joinToString(",")
        companion.mpv.command("loadfile", audioUrl, "replace", "0", audioOptions)
    }

    /**
     * Separate mpv instances finish opening independently. Neither starts
     * until every stream this entry needs has reached FileLoaded; otherwise a
     * fast video runs silently or a fast audio track plays over a placeholder.
     */
    private fun awaitStreamsOpen(key: Long, url: String, hasSeparateAudio: Boolean) {
        val generation = ++loadGeneration
        videoReady = false
        audioReady = !hasSeparateAudio
        applyPlaybackState()

        loadScope.launch {
            // FileLoaded releases the playback gate; it is not the frame-ready
            // signal. A very short file loaded with pause=yes can reach EOF
            // without ever emitting PlaybackRestart or decoding a frame while
            // paused. Waiting for either here therefore kept both streams
            // paused forever, and only the user's seek produced the restart
            // that finally opened the gate. Hosts still wait for an actual
            // rendered frame before replacing their cover with the surface.
            video.eventFlow.filterIsInstance<MPVPlayer.Event.FileLoaded>().first()
            if (generation != loadGeneration || loadedKey != key) return@launch
            videoReady = true
            // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS)
            onStreamReady()
        }
        if (hasSeparateAudio) {
            loadScope.launch {
                val companion = audio ?: return@launch
                companion.eventFlow.filterIsInstance<MPVPlayer.Event.FileLoaded>().first()
                if (generation != loadGeneration || loadedKey != key) return@launch
                audioReady = true
                // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS)
                onStreamReady()
            }
        }

        // MPV's event flow has no replay value. A native event can therefore
        // be missed during a surface or instance transition even with an early
        // collector, which used to leave exactly one entry paused. Properties
        // are the source of truth on this bounded-per-load poll; the events
        // above merely make the common path immediate. Match the path as well
        // as the tracks so a reused instance's previous file cannot open this
        // load's gate before its own loadfile has even been issued.
        loadScope.launch {
            while (generation == loadGeneration && loadedKey == key) {
                if (!videoReady) {
                    val tracks = video.mpv.getPropertyInt("track-list/count") ?: 0
                    if (tracks > 0 && video.mpv.getPropertyString("path") == url) videoReady = true
                }
                if (hasSeparateAudio && !audioReady) {
                    val tracks = audio?.mpv?.getPropertyInt("track-list/count") ?: 0
                    if (tracks > 0) audioReady = true
                }
                if (videoReady && audioReady) {
                    // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS)
                    onStreamReady()
                    return@launch
                }
                delay(READY_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the companion audio, for a host about to play something this is
     * not loading - its own next episode, or anything else that is not an
     * entry with streams of its own.
     *
     * Without it the previous entry's audio went on playing underneath,
     * because nothing else in the app knows this instance exists.
     */
    fun stopCompanion() {
        audioReady = true
        // AM (CLIP_SPLIT_IS_THE_LOAD_NOT_THE_INSTANCE): nothing of this host's
        // is a pair any more, whatever instance is still standing by.
        splitLoad = false
        latchedAudioLength = null
        runCatching { audio?.mpv?.command("stop") }
    }

    /** True once this instance has a file loaded, whether playing or held. */
    val isLoaded: Boolean get() = loadedKey != null

    /** Both streams have opened; a pending quality cannot hand off before this. */
    val isReady: Boolean get() = videoReady && audioReady

    fun play() {
        // The preload cap is about not spending bandwidth on entries nobody
        // has reached. This one has been reached.
        shouldPlay = true
        applyPlaybackBuffer(preloading = false)
        applyPlaybackState()
    }

    fun pause() {
        shouldPlay = false
        applyPlaybackState()
    }

    fun togglePaused() {
        if (isPaused) play() else pause()
    }

    val isPaused: Boolean
        get() = video.mpv.getPropertyBoolean("pause") ?: false

    // AM (CLIP_TIMELINE) -->
    /**
     * True while this is playing two streams of different lengths - the case
     * the timeline below exists for.
     *
     * AM (CLIP_SPLIT_IS_THE_LOAD_NOT_THE_INSTANCE): what this load was given,
     * not whether a companion instance exists. The companion is created on
     * demand and then kept for the life of the host - stopping it does not
     * destroy it, deliberately, because building one costs a native mpv
     * context. So `audio != null` stayed true for every later episode once any
     * pair had played, and those episodes were then treated as pairs: the
     * session's own time-pos and duration observers stayed switched off in
     * favour of a combined timeline read off a companion that had been
     * stopped, which is a position of zero against a length of zero. One
     * entry's audio track was enough to break the timeline of everything
     * played after it in that session.
     */
    val isSplit: Boolean get() = audio != null && splitLoad

    /** Whether the file currently loaded here came with a companion stream. */
    private var splitLoad = false

    /** The audio stream's own length, 0 when there is no companion. */
    private val audioDurationSeconds: Float
        get() = latchedAudioLength ?: liveAudioLength()

    /** The video stream's own length. */
    private val videoDurationSeconds: Float
        get() = latchedVideoLength ?: liveVideoLength()

    // AM (CLIP_TIMELINE_LENGTHS_ARE_LATCHED) -->
    /**
     * The two lengths, measured once this load's streams were both open.
     *
     * Asking mpv for `duration` on every read looks harmless and is not: the
     * property is per instance and can come back null or zero for a moment -
     * around a loop turning over, during a seek, while an instance reopens -
     * and every derived answer flips with it. [audioIsLonger] decides which
     * clock the position is read from, so a flip swapped the timeline between
     * the long stream's clock and the short one's, and the seek bar jumped
     * back and forth between them: the rubberbanding. The same flip changed
     * [durationSeconds] between the two lengths, and that is the number
     * written to an episode's total_seconds, so the entry's own progress timer
     * was recording whichever length happened to be reported that second.
     *
     * A file's length does not change while it is open, so it is measured once
     * per load and kept. Cleared at the start of a load, taken at the moment
     * the readiness gate opens, and read live only in the window before that -
     * where there is nothing to be consistent with yet.
     */
    private var latchedVideoLength: Float? = null
    private var latchedAudioLength: Float? = null

    private fun liveVideoLength(): Float = video.mpv.getPropertyDouble("duration")?.toFloat()
        ?.takeIf { it.isFinite() && it > 0f } ?: 0f

    private fun liveAudioLength(): Float = audio?.mpv?.getPropertyDouble("duration")?.toFloat()
        ?.takeIf { it.isFinite() && it > 0f } ?: 0f

    /** Takes both lengths, once, for the load that has just become ready. */
    private fun latchLengths() {
        val videoLength = liveVideoLength()
        val audioLength = liveAudioLength()
        // Only once each is actually reported: latching a zero would make a
        // stream that is open look like one that is not, permanently.
        if (videoLength > 0f) latchedVideoLength = videoLength
        if (audio == null || audioLength > 0f) latchedAudioLength = audioLength
    }

    /** Regular feed clips repeat; split entries repeat only their shorter stream. */
    private fun applyStreamLooping() {
        if (!looping) {
            setLooping(video, false)
            audio?.let { setLooping(it, false) }
            return
        }
        if (!isSplit) {
            // The scroller's default is a looping clip. managesAudio scopes
            // that default to the feed; the canonical player retains its
            // single-play behavior for an ordinary episode.
            setLooping(video, managesAudio)
            return
        }
        val videoLength = videoDurationSeconds
        val audioLength = audioDurationSeconds
        if (videoLength <= 0f || audioLength <= 0f) {
            setLooping(video, false)
            audio?.let { setLooping(it, false) }
            return
        }
        setLooping(video, audioLength > videoLength)
        audio?.let { setLooping(it, videoLength > audioLength) }
    }
    // <-- AM (CLIP_TIMELINE_LENGTHS_ARE_LATCHED)

    /**
     * Which of the two streams the timeline follows: the longer one.
     *
     * The shorter stream repeats underneath it, so its own clock returns to
     * zero several times over the entry and is not a position in the entry at
     * all. The longer one runs once, start to end, which is the only clock
     * that describes where the viewer is.
     */
    private val audioIsLonger: Boolean
        get() = audio != null && audioDurationSeconds > videoDurationSeconds

    /** Relative seek, through the absolute one so both streams stay together. */
    fun seekBy(seconds: Int) {
        if (!isSplit) {
            video.mpv.command("seek", seconds.toString(), "relative")
            return
        }
        seekTo(positionSeconds + seconds)
    }
    // <-- AM (CLIP_TIMELINE)

    /**
     * Absolute seek.
     *
     * Both instances, with the same target: a separate audio stream is its own
     * timeline, so seeking only the video would leave the two running apart
     * for the rest of the clip.
     */
    fun seekTo(seconds: Float) {
        val companion = audio
        if (companion == null) {
            video.mpv.command("seek", seconds.coerceAtLeast(0f).toString(), "absolute")
            return
        }

        // AM (CLIP_TIMELINE) -->
        // The longer stream goes to the target; the shorter one goes to where
        // it would be at that point, which is the target folded into its own
        // length - some whole number of repetitions plus the remainder.
        // Seeking both to the same absolute time instead left the shorter one
        // clamped at its own end for the rest of the entry.
        //
        // Read the actual stream lengths for the fold. Neither stream needs a
        // reload or track change: the longer one takes the combined target and
        // the shorter one takes that target modulo its own length. In
        // particular, do not send a redundant seek to the audio companion when
        // the folded target is already where it is playing - a redundant seek
        // still flushes that instance's decoder and audio output.
        val target = seconds.coerceIn(0f, durationSeconds.takeIf { it > 0f } ?: seconds)
        val videoLength = liveVideoLength()
        val audioLength = liveAudioLength()
        val audioRunsLonger = audioLength > videoLength

        val videoTarget = if (audioRunsLonger && videoLength > 0f) target % videoLength else target
        val audioTarget = if (!audioRunsLonger && audioLength > 0f) target % audioLength else target

        video.seekToMappedPosition(videoTarget)
        companion.seekToMappedPosition(audioTarget)
        // <-- AM (CLIP_TIMELINE)
    }

    private fun MPVPlayer.seekToMappedPosition(target: Float) {
        val mappedTarget = target.coerceAtLeast(0f)
        val current = mpv.getPropertyDouble("time-pos")?.toFloat()
        if (current != null && abs(current - mappedTarget) < SEEK_POSITION_EPSILON) return
        mpv.command("seek", mappedTarget.toString(), "absolute")
    }

    /**
     * Where the viewer is, in seconds.
     *
     * AM (CLIP_TIMELINE): read off the longer stream, which runs once - the
     * shorter one repeats, so its clock says where it is within a repetition
     * rather than where the entry is.
     */
    val positionSeconds: Float
        get() {
            val source = if (audioIsLonger) audio ?: video else video
            return source.mpv.getPropertyDouble("time-pos")?.toFloat()?.takeIf { it.isFinite() } ?: 0f
        }

    /**
     * How long the entry is.
     *
     * AM (CLIP_TIMELINE): the longer of the two streams, because that is when
     * the entry ends - the shorter one repeating under it does not shorten it.
     */
    val durationSeconds: Float
        get() = maxOf(videoDurationSeconds, audioDurationSeconds)

    /**
     * How far the entry is held, as a position on the entry's own timeline.
     *
     * AM (SCROLLER_BUFFER_IS_A_CACHED_RANGE): read as a cached range endpoint
     * rather than built out of the playhead. A duration added to the playhead
     * cannot reach the cache's end: mpv measures its forward queue from the
     * decoder's read position, which leads the displayed position by whatever
     * is sitting in the output and audio queues, so `position + cache-duration`
     * lands that much short of where the data actually ends - the permanently
     * missing tail. `seekable-ranges` is the same thing a browser hands a web
     * player as `HTMLMediaElement.buffered`: absolute start/end pairs, from
     * which the range holding the playhead is the one to draw to.
     *
     * The last packet's timestamp is still a frame shy of the stream's stated
     * length, so a stream whose demuxer has reached the end of the file - and
     * whose cached range runs that far - reports the full duration instead.
     * That is the "fully buffered but never quite full" tail.
     *
     * The companion stream is an endpoint on its own clock only while it runs
     * once alongside the entry. A shorter companion repeating underneath has a
     * clock that returns to zero, so for that one the forward queue, which is
     * clock-agnostic, is still the only honest thing to measure.
     */
    val bufferedPositionSeconds: Float
        get() {
            val position = positionSeconds
            val duration = durationSeconds
            val source = if (audioIsLonger) audio ?: video else video
            val companion = audio?.takeIf { splitLoad && it !== source }

            var buffered = cachedThroughSeconds(source, position, duration, repeats = false)
            if (companion != null) {
                val companionBuffered =
                    cachedThroughSeconds(companion, position, duration, repeats = shorterStreamRepeats)
                buffered = minOf(buffered, companionBuffered)
            }
            return buffered.coerceAtLeast(position)
                .let { if (duration > 0f) it.coerceAtMost(duration) else it }
        }

    /** True while the entry's shorter stream is looping under its longer one. */
    private val shorterStreamRepeats: Boolean
        get() {
            val videoLength = videoDurationSeconds
            val audioLength = audioDurationSeconds
            return looping && videoLength > 0f && audioLength > 0f && videoLength != audioLength
        }

    /** Where [player]'s cache runs out, on the entry's timeline. */
    private fun cachedThroughSeconds(
        player: MPVPlayer,
        position: Float,
        duration: Float,
        repeats: Boolean,
    ): Float {
        if (!repeats) {
            cachedRangeEndSeconds(player, position, duration)?.let { return it }
        }
        return position + readAheadSeconds(player)
    }

    /**
     * The end of the cached range the playhead sits in, or null where this
     * build reports no ranges at all.
     *
     * A range that runs to the end of a fully demuxed file is reported as the
     * stream's whole length: its last timestamp is the last packet's, one
     * frame short of the length every other readout uses, and a seek bar that
     * stops a frame early for a file held in its entirety is the complaint
     * this is here to answer. A later range means there is a hole ahead, so
     * only the one the playhead is in may be drawn to - the same rule a web
     * player follows over `buffered`.
     */
    private fun cachedRangeEndSeconds(player: MPVPlayer, position: Float, duration: Float): Float? {
        val count = player.mpv.getPropertyInt("demuxer-cache-state/seekable-ranges/count")
            ?.coerceAtMost(MAX_SEEK_RANGES)
            ?: return cacheEndSeconds(player)
        if (count <= 0) return cacheEndSeconds(player)

        var containing: Float? = null
        var furthest = Float.NEGATIVE_INFINITY
        for (index in 0 until count) {
            val path = "demuxer-cache-state/seekable-ranges/$index"
            val start = player.mpv.getPropertyDouble("$path/start")?.toFloat()?.takeIf { it.isFinite() } ?: continue
            val end = player.mpv.getPropertyDouble("$path/end")?.toFloat()?.takeIf { it.isFinite() } ?: continue
            if (end > furthest) furthest = end
            if (position >= start - RANGE_EDGE_EPSILON && position <= end + RANGE_EDGE_EPSILON) containing = end
        }

        val end = containing ?: return cacheEndSeconds(player)
        val atFileEnd = duration > 0f &&
            end >= furthest - RANGE_EDGE_EPSILON &&
            player.mpv.getPropertyBoolean("demuxer-cache-state/eof") == true
        return if (atFileEnd) duration else end
    }

    /** The absolute end of what the demuxer holds, where ranges are unavailable. */
    private fun cacheEndSeconds(player: MPVPlayer): Float? =
        player.mpv.getPropertyDouble("demuxer-cache-state/cache-end")?.toFloat()?.takeIf { it.isFinite() }
            ?: player.mpv.getPropertyDouble("demuxer-cache-time")?.toFloat()?.takeIf { it.isFinite() }

    val isBuffering: Boolean
        get() = video.isCacheStarved() || audio?.takeIf { splitLoad }?.isCacheStarved() == true

    private fun MPVPlayer.isCacheStarved(): Boolean =
        mpv.getPropertyBoolean("paused-for-cache") == true ||
            mpv.getPropertyBoolean("demuxer-cache-state/underrun") == true

    /**
     * How many seconds [player] holds ahead of its own read position.
     *
     * AM (SCROLLER_BUFFER_IS_A_CACHED_RANGE): `demuxer-cache-time` is not a
     * duration - it is the timestamp of the end of the buffered range, on the
     * file's own clock (demux_ctrl_ts_info.end). Adding it to a position
     * counted the position twice, which is why the bar ran at roughly double
     * the playhead and widened as the entry went on. `cache-duration` is
     * end-minus-reader, which is what a readahead is.
     *
     * No starved special case: cache-duration stays correct while the cache is
     * underrunning - it reports the little that is actually held, which is the
     * honest answer. The old branch substituted mpv's refill percentage scaled
     * by cache-pause-wait, so a starved stream drew up to 1.5s of buffer that
     * did not exist.
     */
    private fun readAheadSeconds(player: MPVPlayer): Float {
        val cached = player.mpv.getPropertyDouble("demuxer-cache-state/cache-duration")?.toFloat()
            ?.takeIf { it.isFinite() }
            ?: player.mpv.getPropertyDouble("demuxer-cache-duration")?.toFloat()
                ?.takeIf { it.isFinite() }
        if (cached != null) return cached.coerceAtLeast(0f)

        val cacheEnd = cacheEndSeconds(player) ?: return 0f
        val reader = player.mpv.getPropertyDouble("demuxer-cache-state/reader-pts")?.toFloat()
            ?.takeIf { it.isFinite() }
            ?: player.mpv.getPropertyDouble("time-pos")?.toFloat()?.takeIf { it.isFinite() }
            ?: return 0f
        return (cacheEnd - reader).coerceAtLeast(0f)
    }

    /**
     * True once mpv has a decoded frame to show.
     *
     * Read as a property rather than waited for as an event, as a backstop:
     * the event flow has no replay buffer and a missed event leaves a
     * placeholder over a playing video.
     */
    fun hasRenderedFrame(): Boolean =
        (video.mpv.getPropertyInt("dwidth") ?: 0) > 0 &&
            (video.mpv.getPropertyInt("dheight") ?: 0) > 0

    /** The video's own aspect ratio, readable as soon as the file is open. */
    fun videoAspectRatio(): Float? =
        video.mpv.getPropertyDouble("video-params/aspect")
            ?.toFloat()
            ?.takeIf { it.isFinite() && it > 0f }

    /**
     * Mutes or unmutes this entry.
     *
     * [mayHoldAudioFocus] must be true for the entry being watched and false
     * for every other one. Audio focus is exclusive, and a focus request by
     * one instance hands the holder an AUDIOFOCUS_LOSS, which MPVPlayer
     * answers by pausing and only un-pausing on a later GAIN that never
     * arrives. With several instances alive, letting each request focus meant
     * whichever one was playing got paused by the next one's request and
     * stayed paused.
     */
    fun setMuted(muted: Boolean, mayHoldAudioFocus: Boolean) {
        isMuted = muted
        this.mayHoldAudioFocus = mayHoldAudioFocus
        val effectivelyMuted = applyMuteState()

        // Requested lazily and then kept: an unmute/mute/unmute cycle must not
        // re-request after a grant, which would make the app's own canonical
        // player see a focus loss. A rejected request is not latched: Android
        // can reject focus during a background/foreground transition, and the
        // next explicit unmute must be able to retry it.
        if (managesAudio && !effectivelyMuted && !hasRequestedFocus) {
            hasRequestedFocus = video.requestAudioFocus()
        }
    }

    /**
     * Follows the video instance's own pause state, for as long as this load
     * lasts.
     *
     * Watched rather than mirrored from the call sites that pause. Pause
     * reaches that instance from more places than the view model's own
     * helpers - the media session, the audio-focus handler, PIP, the
     * notification - and every one of those left the companion playing on its
     * own. The property is the one thing all of them end at.
     *
     * The readiness gate still applies: a resume that arrives before the
     * streams are open is remembered and acted on when they are, rather than
     * starting one instance without the other.
     */
    private fun followHostPause(key: Long, generation: Int) {
        // The video instance ending or being stopped by anything else ends
        // this entry: a looping clip does not reach EndFile on its own, so
        // when it arrives the host has stopped or replaced the file and the
        // companion has nothing left to be in step with.
        //
        // Only once this load's own video has started, which is the fix for
        // the obvious trap: issuing a load makes mpv end the file it replaces,
        // so the first EndFile after this subscription is the PREVIOUS entry's
        // - and acting on it stopped the companion this load had just started,
        // which is an entry that played with no sound at all. The generation
        // guard does not catch it, because that EndFile arrives against this
        // generation.
        loadScope.launch {
            video.eventFlow.filterIsInstance<MPVPlayer.Event.PlaybackRestart>().first()
            if (generation != loadGeneration || loadedKey != key) return@launch
            video.eventFlow
                .filterIsInstance<MPVPlayer.Event.EndFile>()
                .collect {
                    if (generation != loadGeneration || loadedKey != key) return@collect
                    stopCompanion()
                }
        }
        // When audio is the longer stream its EOF, not the repeating video's,
        // completes the entry. Start watching only after this load's audio is
        // open so the previous file's replacement EndFile cannot finish it.
        loadScope.launch {
            val companion = audio ?: return@launch
            while (generation == loadGeneration && loadedKey == key && !audioReady) {
                delay(READY_POLL_INTERVAL_MS)
            }
            if (generation != loadGeneration || loadedKey != key || !audioReady) return@launch
            companion.eventFlow
                .filterIsInstance<MPVPlayer.Event.EndFile>()
                .collect {
                    if (generation != loadGeneration || loadedKey != key || !audioIsLonger) return@collect
                    shouldPlay = false
                    applyPlaybackState()
                    onEntryEnded?.invoke()
                }
        }
        loadScope.launch {
            video.eventFlow
                .filterIsInstance<MPVPlayer.Event.PauseChanged>()
                .collect { event ->
                    if (generation != loadGeneration || loadedKey != key) return@collect
                    // Only what this did not ask for: the gate's own pause
                    // must not be read back as the host wanting a pause.
                    val gated = !videoReady || !audioReady
                    if (gated && event.paused) return@collect
                    if (shouldPlay == !event.paused) return@collect
                    shouldPlay = !event.paused
                    applyPlaybackState()
                }
        }
    }

    /**
     * The host issued a command to its own instance; the companion follows the
     * ones that move the timeline.
     *
     * Seeks only. A command that loads, stops or configures the video belongs
     * to the host's own file and would be wrong to replay against an audio
     * stream that is not it - the companion's own load goes through
     * [loadPaused].
     */
    fun mirrorCommand(command: Array<out String>) {
        val companion = audio ?: return
        if (command.firstOrNull() != "seek") return
        companion.mpv.command(*command)
    }

    // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS) -->
    /**
     * One stream of this load reported itself open.
     *
     * Once both have, a pair that was asked to resume is put where it was
     * asked to be - by [seekTo], the only thing that knows how one time maps
     * onto two streams of different lengths. Consumed, so that happens once
     * per load rather than on every later readiness report.
     */
    private fun onStreamReady() {
        // Track selection belongs to stream readiness, not only the unmute
        // click. A click can arrive before the new file's track list exists;
        // applying aid then is discarded by the subsequent load.
        if (managesAudio) restoreAudioTrackSelection()
        // AM (CLIP_TIMELINE_LENGTHS_ARE_LATCHED): before the seek below, which
        // needs both lengths to fold a position onto them.
        if (videoReady && audioReady) {
            latchLengths()
            applyStreamLooping()
        }
        pendingResume?.takeIf { videoReady && audioReady }?.let { resume ->
            pendingResume = null
            // The durations are known now, the files being open - which is
            // what made this the wrong thing to express as a load option.
            if (durationSeconds > 0f) seekTo(resume)
        }
        applyPlaybackState()
    }

    private fun applyPlaybackState() {
        val paused = !shouldPlay || !videoReady || !audioReady
        // Both instances, together: this only ever runs for an entry this
        // loaded, and the two streams of one entry start and stop as one. The
        // host's own intent reaches here through followHostPause.
        video.mpv.setPropertyBoolean("pause", paused)
        audio?.mpv?.setPropertyBoolean("pause", paused)
    }

    private fun applyMuteState(): Boolean {
        // Only the entry being watched makes sound, whatever the mute setting
        // says.
        val effectivelyMuted = isMuted || !mayHoldAudioFocus
        if (managesAudio && !effectivelyMuted) restoreAudioTrackSelection()
        video.mpv.setPropertyBoolean("mute", effectivelyMuted)
        audio?.mpv?.setPropertyBoolean("mute", effectivelyMuted)
        return effectivelyMuted
    }

    /**
     * The scroller has no ViewModel track observer to repair a cleared aid.
     * Select on the stream that actually owns sound: the companion for a split
     * load, otherwise the video. In particular, never re-enable aid on the
     * video half of a same-file split, which would play its audio twice.
     */
    private fun restoreAudioTrackSelection() {
        val target = audio ?: video.takeUnless { splitLoad } ?: return
        val selected = target.mpv.getPropertyString("aid")
        if (selected == null || selected == "no") {
            target.mpv.setPropertyString("aid", "auto")
        }
    }

    /**
     * Stops both instances and forgets what they were playing, keeping them
     * alive to be loaded with something else.
     *
     * This, not [release], is what happens when a host hands an entry back.
     * Destroying an instance per entry is what met the teardown race described
     * on ScrollerPlayerPool.
     */
    fun reset() {
        loadGeneration++
        videoReady = false
        audioReady = true
        shouldPlay = false
        pendingLoad = null
        loadedKey = null
        // AM (SCROLLER_PRELOAD_TIERS): this instance is about to hold a
        // different entry, and how close the previous one was to being watched
        // says nothing about the next.
        preloadTier = MPVPlayer.CacheTier.Far
        applyPlaybackBuffer(preloading = true)
        runCatching { video.mpv.command("stop") }
        runCatching { audio?.mpv?.command("stop") }
    }

    /** Destroys the instances. Only the host going away does this. */
    fun release() {
        loadGeneration++
        loadScope.cancel()
        pendingLoad = null
        loadedKey = null
        video.release()
        audio?.release()
        audio = null
    }

    /**
     * Releases only the companion, for a host whose video instance is owned by
     * something else and outlives this.
     */
    fun releaseCompanion() {
        loadGeneration++
        loadScope.cancel()
        audio?.release()
        audio = null
    }

    private fun applyPlaybackBuffer(preloading: Boolean) {
        // AM (SCROLLER_PRELOAD_TIERS)
        val tier = if (preloading) preloadTier else MPVPlayer.CacheTier.Playing
        video.setScrollerCache(tier)
        audio?.setScrollerCache(tier)
    }

    companion object {
        private const val LOOP_FOREVER = "inf"
        private const val LOOP_OFF = "no"
        private const val SEEK_POSITION_EPSILON = 0.05f
        private const val RANGE_EDGE_EPSILON = 0.25f
        private const val MAX_SEEK_RANGES = 10

        /**
         * Whether [anime]'s listing says its clip repeats rather than ends.
         *
         * The flag has always been part of the feed's own schema (see
         * [ScrollerFeedMedia]); this is the one place that asks for it, so the
         * feed and the player cannot disagree about which entries loop.
         */
        fun declaredLooping(anime: Anime?): Boolean =
            anime?.let { ScrollerFeedMedia.from(it) }?.loops == true

        private fun setLooping(player: MPVPlayer, looping: Boolean) {
            player.mpv.setOptionString("loop-file", if (looping) LOOP_FOREVER else LOOP_OFF)
        }

        /**
         * Enough buffer to hold a short clip whole, so its loop is a cache
         * seek rather than a re-read with playback stopped while it refills.
         */

        /**
         * What an entry being approached may buffer, as against what the one
         * being watched may. Preloading is not free: a host holding several
         * instances open before the user reaches them can otherwise pull a lot
         * of data for entries nobody watches.
         */


        private const val READY_POLL_INTERVAL_MS = 100L
    }
}
// <-- AM (CLIP_PLAYBACK)
