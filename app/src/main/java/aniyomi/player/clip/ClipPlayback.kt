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
 * has actually opened, so a fast video cannot run silently over an audio
 * stream still being opened, and a fast audio stream cannot play over a video
 * that has no frame yet.
 *
 * The loop, set on the instance before the file is loaded rather than passed
 * as one of loadfile's per-file options: those travel as a quoted string mpv's
 * option parser is not obliged to accept, and a loop silently not applied is
 * indistinguishable from an entry that ended.
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
     * Whether what is loaded repeats rather than ends.
     *
     * Read by the host that has an end-of-file reaction: an entry that loops
     * has no end to react to, and mpv reporting `eof-reached` at the moment a
     * loop turns over is not the entry finishing.
     */
    val isLooping: Boolean get() = looping

    var isMuted: Boolean = managesAudio
        private set

    /** The companion instance, for a host that has to reach it directly. */
    val audioInstance: MPVPlayer? get() = audio

    /**
     * Prepares the video instance for a clip that repeats, or for one that
     * does not.
     *
     * Call before the load, and call it for non-looping entries too: an
     * instance is reused between them, and an ordinary episode must not
     * inherit a loop left behind by the clip before it.
     */
    fun prepare(looping: Boolean) {
        this.looping = looping
        setLooping(video, looping)
        audio?.let { setLooping(it, looping) }
        if (looping) applyClipBuffer(video)
    }

    /** Caps what an entry nobody has reached yet may pull. */
    fun applyPreloadBuffer() {
        video.mpv.setOptionString("demuxer-max-bytes", PRELOAD_DEMUXER_BYTES)
        video.mpv.setOptionString("demuxer-readahead-secs", PRELOAD_READAHEAD_SECS)
    }

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
        awaitStreamsOpen(key, audioUrl != null)
        followHostPause(key, loadGeneration)

        val issue = {
            // Set before the load, not after: mpv reads these when it opens
            // the stream, and a source needing a Referer fails the handshake
            // without them. Cleared rather than left behind when an entry
            // carries none, so one entry's headers never go to another's host.
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
        setLooping(companion, looping)
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
    private fun awaitStreamsOpen(key: Long, hasSeparateAudio: Boolean) {
        val generation = ++loadGeneration
        videoReady = false
        audioReady = !hasSeparateAudio
        applyPlaybackState()

        loadScope.launch {
            // PlaybackRestart, not FileLoaded: the file being open is not a
            // frame existing, and an audio stream opens far quicker than a
            // video decodes - gated on the file, the sound started over a
            // picture that was not there yet.
            video.eventFlow.filterIsInstance<MPVPlayer.Event.PlaybackRestart>().first()
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
        // above merely make the common path immediate.
        loadScope.launch {
            while (generation == loadGeneration && loadedKey == key) {
                if (!videoReady && hasRenderedFrame()) videoReady = true
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

    fun play() {
        // The preload cap is about not spending bandwidth on entries nobody
        // has reached. This one has been reached.
        applyClipBuffer(video)
        shouldPlay = true
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
        // AM (CLIP_SEEK_READS_LIVE_LENGTHS): the lengths here are read from
        // mpv, not the latched pair below.
        //
        // The latch exists to stop the readout flickering between the two
        // streams' clocks, and reading it here as well changed what a seek
        // does rather than how it looks: with stable lengths this fold started
        // landing on positions it had previously skipped (a length of zero
        // fails the guards below), so every seek genuinely repositioned the
        // companion, and that jump in a stream feeding a live audio device is
        // an audible pop on every drag of the thumb. Pausing or muting across
        // it does not remove the pop - a paused instance resumes on each
        // intermediate seek of a scrub, which is the same click once per
        // movement.
        //
        // So the seek keeps the behaviour it had before the latch, exactly,
        // and the latch is confined to what it was for. A pair's seek is
        // imprecise where a length is not yet reported - the position it was
        // always imprecise about - and nothing the user hears changed.
        val target = seconds.coerceIn(0f, durationSeconds.takeIf { it > 0f } ?: seconds)
        val videoLength = liveVideoLength()
        val audioLength = liveAudioLength()
        val audioRunsLonger = audioLength > videoLength

        val videoTarget = if (audioRunsLonger && videoLength > 0f) target % videoLength else target
        val audioTarget = if (!audioRunsLonger && audioLength > 0f) target % audioLength else target

        video.mpv.command("seek", videoTarget.coerceAtLeast(0f).toString(), "absolute")
        companion.mpv.command("seek", audioTarget.coerceAtLeast(0f).toString(), "absolute")
        // <-- AM (CLIP_TIMELINE)
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

    /** How far the demuxer has read ahead of [positionSeconds], in seconds. */
    val readAheadSeconds: Float
        get() = video.mpv.getPropertyDouble("demuxer-cache-time")?.toFloat()?.takeIf { it.isFinite() } ?: 0f

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
        // re-request, which would make the app's own canonical player see a
        // focus loss (see MPVPlayer.requestAudioFocus's notes).
        if (managesAudio && !effectivelyMuted && !hasRequestedFocus) {
            video.requestAudioFocus()
            hasRequestedFocus = true
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
        // AM (CLIP_TIMELINE_LENGTHS_ARE_LATCHED): before the seek below, which
        // needs both lengths to fold a position onto them.
        if (videoReady && audioReady) latchLengths()
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
        video.mpv.setPropertyBoolean("mute", effectivelyMuted)
        audio?.mpv?.setPropertyBoolean("mute", effectivelyMuted)
        return effectivelyMuted
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

    companion object {
        private const val LOOP_FOREVER = "inf"
        private const val LOOP_OFF = "no"

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
        private fun applyClipBuffer(player: MPVPlayer) {
            player.mpv.setOptionString("demuxer-max-bytes", CLIP_DEMUXER_BYTES)
            player.mpv.setOptionString("demuxer-max-back-bytes", CLIP_DEMUXER_BYTES)
            player.mpv.setOptionString("demuxer-readahead-secs", CLIP_READAHEAD_SECS)
        }

        /**
         * What an entry being approached may buffer, as against what the one
         * being watched may. Preloading is not free: a host holding several
         * instances open before the user reaches them can otherwise pull a lot
         * of data for entries nobody watches.
         */
        private const val PRELOAD_DEMUXER_BYTES = "8MiB"
        private const val PRELOAD_READAHEAD_SECS = "2"

        private const val CLIP_DEMUXER_BYTES = "64MiB"
        private const val CLIP_READAHEAD_SECS = "20"

        private const val READY_POLL_INTERVAL_MS = 100L
    }
}
// <-- AM (CLIP_PLAYBACK)
