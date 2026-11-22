package aniyomi.player.clip

import aniyomi.ui.browse.scroller.ScrollerFeedMedia
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import eu.kanade.tachiyomi.ui.player.mpv.loadFileWithHwdecGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
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
 *   reaches the companion by watching the instance's own pause property, and
 *   its seeks are folded onto both streams through [seekCommand].
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
 * One rule for what repeats, whichever host is playing (AM (ONE_ENTRY_RULE)).
 * Once both lengths are known the longer stream is the entry: its clock is the
 * position, its length the duration, its end the entry's end. The shorter one
 * always repeats underneath it. Whether the ENTRY repeats is the one setting -
 * the feed holds it on, the player's loop control sets it - and on, the longer
 * stream wraps and the shorter one is put back to its own start with it, so the
 * entry comes round as the same pairing each time. A single-stream entry does
 * not repeat merely because its source labels it a looping clip.
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
    /**
     * A two-stream entry reached its end: the longer stream finished with the
     * entry not repeating. The host's own EOF cannot say this - its instance
     * holds the video, which for most pairs is the shorter, repeating half.
     */
    private val onEntryEnded: (() -> Unit)? = null,
    // AM (A_A_TIMELINE_IS_NOT_KNOWN_UNTIL_BOTH_HALVES_ARE): raised once both
    // halves have reported a length, which is the first moment a host can ask
    // how long the entry is without being told the bed's length instead.
    private val onTimelineReady: (() -> Unit)? = null,
    // AM (A_AN_ENTRY_WITH_NO_SOUND_HAS_NOT_LOADED): this load's audio could
    // not be opened. The entry is let through to play without it; this exists
    // so the host can drop the resolution that produced the url, which would
    // otherwise be handed back for the life of the session.
    private val onAudioLoadFailed: (() -> Unit)? = null,
    // AM (A_PAIRS_BUFFER_IS_BOTH_HALVES): the companion's cache state changed -
    // the host's own observers see only its own instance. See pairBufferFill.
    private val onBufferChanged: (() -> Unit)? = null,
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

    // AM (ONE_ENTRY_RULE) -->
    /**
     * Whether a two-stream entry has played to its end and is holding there.
     *
     * Replaces videoIsRepeating, which asked the host to work out from its own
     * instance's EOF whether that EOF was the entry's. It could only ever answer
     * for the video half, and the entry's end belongs to whichever stream is
     * longer - for most pairs the audio, whose end then reached nothing. Which
     * stream ended the entry is decided here, where the lengths are, and the host
     * reads the result rather than re-deriving it.
     *
     * Cleared by anything that moves the entry off its end: a seek, a load, a
     * reset.
     */
    var entryEnded: Boolean = false
        private set

    /**
     * The last remaining-file-loops value each instance reported for this load,
     * so a drop - a wrap - can be told from a value merely being reported. See
     * MPVPlayer.Event.RemainingFileLoops.
     */
    private var videoLoopsLeft: Long? = null
    private var audioLoopsLeft: Long? = null

    /** The files this load gave each instance, for [hasEnded]. */
    private var videoPath: String? = null
    private var audioPath: String? = null
    // <-- AM (ONE_ENTRY_RULE)

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
    // AM (A_LOOPING_IS_THE_USERS_CALL) -->
    /**
     * Turns the loop on or off for the entry already playing.
     *
     * [prepare] answers the same question for an entry about to load and
     * cannot serve both: it clears the loop off both streams on the assumption
     * that a file is about to replace whatever is there, so called
     * mid-playback it would drop the running entry's loop and not restore it.
     */
    fun setLoopEnabled(enabled: Boolean) {
        if (looping == enabled) return
        looping = enabled
        applyStreamLooping()
        onTimelineReady?.invoke()
    }
    // <-- AM (A_LOOPING_IS_THE_USERS_CALL)

    fun prepare(looping: Boolean) {
        this.looping = looping
        setLooping(video, LOOP_OFF)
        audio?.let { setLooping(it, LOOP_OFF) }
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
        // AM (A_AN_OPTION_OUTLIVES_THE_FILE): options, not properties.
        // These are set on an instance with no file in it, and must hold
        // for every file it loads - a property write here lands on nothing
        // and is then overridden per file by MPVPlayer's own aid=no, which
        // leaves a companion with no audio track and, with vid=no beside
        // it, no streams at all.
        created.mpv.setOptionString("aid", "auto")
        created.mpv.setOptionString("vid", "no")
        // AM (A_STREAM_ENDS_WHEN_ITS_SOUND_DOES): the pause keep-open puts on a
        // file once its output has drained is how this instance says its sound
        // has finished - see outputFinished. Stated rather than inherited, so a
        // user mpv.conf cannot take that signal away.
        created.mpv.setOptionString("keep-open-pause", "yes")
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
        // stream. This load starts unlooped; onStreamReady applies the entry
        // rule once both lengths are known.
        setLooping(video, LOOP_OFF)
        // AM (ONE_ENTRY_RULE): this load has not ended, and the loop counts the
        // instances last reported belong to the file before it.
        entryEnded = false
        videoLoopsLeft = null
        audioLoopsLeft = null
        videoPath = url
        audioPath = audioUrl
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
        // AM (A_AN_ENDED_ENTRY_HAS_NO_POSITION)
        // AM (A_A_LOAD_IS_MEASURED_ONCE)
        measuredThisLoad = false
        // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS)
        pendingResume = resumeAt?.toFloat()?.takeIf { it > 0f && audioUrl != null }
        awaitStreamsOpen(key, url, audioUrl)
        followHostPause(key, loadGeneration)
        // AM (ONE_ENTRY_RULE)
        followEntryEnd(key, loadGeneration)
        // AM (A_PAIRS_BUFFER_IS_BOTH_HALVES): only a pair has a companion to
        // follow - companion() above has made it, if this load needs one.
        if (audioUrl != null) followCompanionCache(key, loadGeneration)
        // AM (A_PAIRS_SOUND_FOLLOWS_THE_PLAYER)
        if (audioUrl != null) followAudioSettings(key, loadGeneration)

        val issue = {
            // Set before the load, not after: mpv reads these when it opens
            // the stream, and a source needing a Referer fails the handshake
            // without them. Cleared rather than left behind when an entry
            // carries none, so one entry's headers never go to another's host.
            applyPlaybackBuffer(preloading = !shouldPlay)
            // AM (A_AN_OPTION_OUTLIVES_THE_FILE): the demuxer reads this
            // when it opens the stream, which is after this.
            video.mpv.setOptionString("http-header-fields", httpHeaderFields ?: "")
            audio?.mpv?.setOptionString("http-header-fields", httpHeaderFields ?: "")
            // pause=yes in the load's own options, so the file is never
            // playing even for a frame before being paused.
            val options = buildList {
                add("pause=yes")
                // AM (ONE_DECODE_POLICY) -->
                // Was `if (managesAudio || audioUrl != null) add("hwdec=no")`.
                //
                // managesAudio is true for every scroller card, so that line put
                // the whole feed on SOFTWARE decoding, and because it rode in the
                // loadfile options it also overrode whatever the guard had just
                // set. Measured on-device (2026-11-06): every card reported
                // hwdec=no, and the heavier clips could not be decoded in real
                // time - frame-drop-count climbed steadily while avsync grew
                // monotonically past three seconds, so the picture fell further
                // behind the audio the longer a card played. Which clips were
                // affected was fixed per clip, because it is purely a function of
                // that clip's resolution and bitrate: a 29.97fps card collapsed
                // while a 59.94fps one beside it was clean. Nothing was ever late
                // to the screen (vo-delayed-frame-count stayed at zero), so this
                // was never a presentation problem.
                //
                // The reason given for it was a decoder consuming a sub-second
                // H.264 file without emitting a first frame. That is not vendor
                // specific whatever the old comment said - a codec rendering
                // straight into a surface holds frames until it has enough input,
                // and drains the remainder only on end-of-stream, so a file short
                // enough to be queued in full before the first output appears can
                // be consumed entirely with every frame still inside the codec.
                // Any Android device can do that; only the depth at which it
                // starts varies.
                //
                // So the condition was keyed on the ROLE of the player, while the
                // defect belongs to a file property interacting with a frame
                // DELIVERY mode. mediacodec-copy addresses that delivery mode
                // directly (see the hwdec argument below) and costs one frame copy
                // against decoding every frame on the CPU, which is why the policy
                // now lives in one place for player and clips alike instead of
                // being overridden here.
                // <-- AM (ONE_DECODE_POLICY)
                // AM (A_SPLIT_LOAD_HAS_ONE_AUDIO_OWNER) -->
                // In any split load the companion owns the audio and this
                // instance owns the picture. audioOwner() already says so for
                // every split, and loadCompanion has no path that falls back to
                // this instance's audio - a companion that cannot be built
                // returns and the entry plays silent.
                //
                // Was keyed on `audioUrl == url`, which is only the same-file
                // split. A separate-URL split therefore left this instance free
                // to select the audio of a muxed video rendition: a second
                // AudioTrack held open for a stream nothing reads, and that
                // audio playing under the companion's whenever the card was
                // unmuted, since applyMuteState clears mute on both.
                //
                // The same-file case the option was written for still holds and
                // is the reason the companion exists: one file on both
                // instances, so the video can repeat on its own length while
                // the audio plays through, and without aid=no the file's audio
                // would play on both.
                // AM (A_EVERY_LOAD_STATES_ITS_OWN_TRACKS): both branches
                // stated, so this instance's leftover aid cannot decide it.
                if (audioUrl != null) add("aid=no") else add("aid=auto")
                // <-- AM (A_SPLIT_LOAD_HAS_ONE_AUDIO_OWNER)
                // AM (CLIP_RESUME_FOLDS_ONTO_STREAMS): a pair is put in place
                // by the seek once both streams are open - see pendingResume.
                if (resumeAt != null && audioUrl == null) add("start=$resumeAt")
            }.joinToString(",")
            video.mpv.loadFileWithHwdecGuard(
                url = url,
                options = options,
                hasAttachedSurface = video.hasAttachedSurfaceBefore,
                // AM (ONE_DECODE_POLICY): the same value the fullscreen player
                // uses, for the reasons recorded on preferredHwdec itself.
                hwdec = video.preferredHwdec(),
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
        // AM (A_AN_OPTION_OUTLIVES_THE_FILE)
        companion.mpv.setOptionString("http-header-fields", httpHeaderFields ?: "")
        setLooping(companion, LOOP_OFF)
        val audioOptions = buildList {
            add("pause=yes")
            add("vid=no")
            // AM (A_EVERY_LOAD_STATES_ITS_OWN_TRACKS): the companion owns this
            // entry's sound and has no video, so a load that selects no audio
            // track has selected nothing - which mpv reports as having no
            // playable data. Stated here rather than inherited from the
            // instance, whose aid is set to no every time this card stops
            // being the audible one.
            add("aid=auto")
        }.joinToString(",")
        companion.mpv.command("loadfile", audioUrl, "replace", "0", audioOptions)
    }

    /**
     * Separate mpv instances finish opening independently. Neither starts
     * until every stream this entry needs has reached FileLoaded; otherwise a
     * fast video runs silently or a fast audio track plays over a placeholder.
     */
    // AM (A_A_DEAD_AUDIO_STREAM_IS_NOT_A_DEAD_ENTRY): the url rather than a
    // flag, because the poll below has to compare the companion's path against
    // it - a reused instance's previous track list opened this gate otherwise.
    private fun awaitStreamsOpen(key: Long, url: String, audioUrl: String?) {
        val hasSeparateAudio = audioUrl != null
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
            // AM (A_A_DEAD_AUDIO_STREAM_IS_NOT_A_DEAD_ENTRY) -->
            // The other way a stream open can finish: it can fail.
            //
            // The gate above has no failure branch, and the poll below only
            // ever looks for a track list that is never going to appear - so
            // an audio url that does not open (an expired hoster link, a 403)
            // left audioReady false forever, both instances paused by the
            // gate, and the card sat there with its video open and its first
            // frame decoded, never starting.
            //
            // Keyed on file_error being present, not on an end arriving: a
            // companion is reused between entries, so issuing this load's
            // loadfile ends whatever it held before and that replacement end
            // arrives against this generation. Only a genuine failure carries
            // file_error.
            loadScope.launch {
                val companion = audio ?: return@launch
                companion.eventFlow
                    .filterIsInstance<MPVPlayer.Event.EndFile>()
                    .collect { event ->
                        if (generation != loadGeneration || loadedKey != key) return@collect
                        if (audioReady) return@collect
                        event.node.asMap()?.get("file_error") ?: return@collect
                        // Open, not shut: a card with no sound is worse than
                        // one that plays, and better than one that never does.
                        stopCompanion()
                        onAudioLoadFailed?.invoke()
                        onStreamReady()
                    }
            }
            // <-- AM (A_A_DEAD_AUDIO_STREAM_IS_NOT_A_DEAD_ENTRY)
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
                    // AM (A_A_DEAD_AUDIO_STREAM_IS_NOT_A_DEAD_ENTRY): match the
                    // path as well as the tracks, which this half did not. A
                    // companion is reused between entries and is not stopped
                    // between them, so a track list left over from the previous
                    // entry's audio opened this load's gate before its own
                    // loadfile had been issued - and the card then played the
                    // entry before's sound. The video half has always compared
                    // its path for this reason.
                    val companion = audio
                    val tracks = companion?.mpv?.getPropertyInt("track-list/count") ?: 0
                    if (tracks > 0 && companion?.mpv?.getPropertyString("path") == audioUrl) {
                        audioReady = true
                    }
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
        // AM (ONE_ENTRY_RULE): no pair, so no pair's end to be holding at.
        entryEnded = false
        runCatching { audio?.mpv?.command("stop") }
    }


    /** AM (A_A_LOAD_IS_MEASURED_ONCE): whether this load's lengths are taken. */
    private var measuredThisLoad = false



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

    // AM (ONE_ENTRY_RULE) -->
    /**
     * What repeats, for every host - the feed and the player differ only in
     * whether the entry loop is on, which the feed always holds and the player's
     * control sets.
     *
     * There used to be a separate branch for the feed that put `loop-file=inf` on
     * both streams. That is not a loop of the entry: mpv wraps each file on its
     * own instance with no relation to the other, so the shorter stream is part
     * way through a lap whenever the longer one wraps, and the pairing of picture
     * and sound is only the same on every pass while one length divides the
     * other. It is the player's rule with the loop on, minus the re-pairing.
     *
     * The shorter stream repeats underneath the longer one regardless - that is
     * what a pair of different lengths is FOR; left unlooped it runs out part way
     * and the rest of the entry is a frozen frame or silence. The loop setting
     * decides only the longer stream: off, it plays once and its end ends the
     * entry; on, it wraps on a finite count - gapless, mpv's own wrap seeks with
     * MPSEEK_FLAG_NOFLUSH - and each wrap is reported, which is what lets the
     * shorter one be put back to its start with it (see onStreamLooped).
     */
    private fun applyStreamLooping() {
        if (!isSplit) {
            // AM (A_LOOPING_IS_THE_USERS_CALL): one stream has no partner to put
            // back in step, so it needs no wrap reported and loops forever.
            setLooping(video, if (looping) LOOP_FOREVER else LOOP_OFF)
            audio?.let { setLooping(it, LOOP_OFF) }
            return
        }
        if (!pairLengthsDiffer) {
            // No longer stream to define the entry by: the lengths are not known
            // yet, or they are equal - which includes a same-file pair, whose two
            // instances both report the one container's duration however long
            // its tracks are. Each half then plays as itself: both repeat when
            // the entry loops, as the feed always had them, and otherwise both
            // play once and the entry ends when both have.
            val value = if (looping) LOOP_FOREVER else LOOP_OFF
            setLooping(video, value)
            audio?.let { setLooping(it, value) }
            return
        }
        setLooping(longerStream(), if (looping) LOOP_ENTRY else LOOP_OFF)
        otherStream()?.let { setLooping(it, LOOP_FOREVER) }
    }

    /** True when a pair has a longer stream: both lengths known, and not equal. */
    private val pairLengthsDiffer: Boolean
        get() {
            val videoLength = videoDurationSeconds
            val audioLength = audioDurationSeconds
            return videoLength > 0f && audioLength > 0f && videoLength != audioLength
        }

    /** The stream that is the entry. Meaningful only while [pairLengthsDiffer]. */
    private fun longerStream(): MPVPlayer = if (audioIsLonger) audio ?: video else video

    /** The stream that repeats underneath [longerStream]. */
    private fun otherStream(): MPVPlayer? = if (audioIsLonger) video else audio

    /**
     * The streams whose ends the entry's end waits for: the longer one where
     * there is one, otherwise every half - none of them repeats to outlast it.
     */
    private fun endingStreams(): List<MPVPlayer> =
        if (pairLengthsDiffer) listOf(longerStream()) else listOfNotNull(video, audio)

    /**
     * Whether [instance] is held at the end of THIS load's file.
     *
     * Read off mpv's latched eof-reached, which keep-open holds true for as long
     * as the file sits at its end, and checked against the path this load gave
     * it: a companion is reused between entries rather than destroyed, so an end
     * belonging to the previous entry's file must not count as this one's.
     */
    private fun hasEnded(instance: MPVPlayer): Boolean {
        val path = if (instance === video) videoPath else audioPath
        return path != null &&
            instance.mpv.getPropertyBoolean("eof-reached") == true &&
            instance.mpv.getPropertyString("path") == path
    }
    // <-- AM (ONE_ENTRY_RULE)

    // AM (A_STREAM_ENDS_WHEN_ITS_SOUND_DOES) -->
    /**
     * Whether [instance] has finished what the viewer sees and hears, not just
     * what it decodes.
     *
     * [hasEnded] is the decoder's end. For the companion that comes early: under
     * mpv's default gapless audio, audio_status turns EOF the moment the last
     * samples are handed to the output (player/audio.c, "In the gapless case, we
     * trigger EOF immediately, and let it play asynchronously"), while the
     * AudioTrack still holds them - measured on device at 1.08s. Ending the entry
     * there paused the video on its last loop with that second of sound still to
     * come, which is the frozen picture under audio that played out.
     *
     * The moment the sound actually stops is keep-open's own pause:
     * handle_keep_open (player/playloop.c) holds back while the output is still
     * playing and pauses the file only once it has drained - the companion has
     * keep-open-pause set for exactly this. The video instance of a pair has no
     * audio output (aid=no), so its decoder's end already is the last frame.
     */
    private fun outputFinished(instance: MPVPlayer): Boolean =
        hasEnded(instance) && (instance !== audio || instance.mpv.getPropertyBoolean("pause") == true)

    /**
     * Pauses or resumes one instance - except that a pause is never written to
     * an instance held at its end.
     *
     * mpv drains the output before pausing a file that has reached EOF
     * (ao_set_paused, audio/out/buffer.c: "be sure to drain the ao first for
     * gapless"), and that drain runs while the caller is inside
     * mpv_set_property holding the core lock. Called from here that caller is the
     * main thread: on device the companion's pause took from 16.677 to 17.760,
     * the UI froze for the whole of it, and nothing the host shows could move.
     * An instance at its end has nothing left to pause - keep-open pauses it
     * itself once its sound has played out - so the write is skipped rather than
     * made somewhere it cannot block.
     */
    private fun setPaused(instance: MPVPlayer, paused: Boolean) {
        if (paused && hasEnded(instance)) return
        instance.mpv.setPropertyBoolean("pause", paused)
    }
    // <-- AM (A_STREAM_ENDS_WHEN_ITS_SOUND_DOES)
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
        // AM (ONE_ENTRY_RULE): wherever this lands, it is not the held end.
        entryEnded = false
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
            // AM (ONE_ENTRY_RULE): the other half whichever instance it is. This
            // took the companion and dropped it when it was the source, so for a
            // pair whose audio is the longer stream - the usual shape - the
            // repeating video's buffer was never counted at all.
            val other = otherStream()?.takeIf { splitLoad && it !== source }

            var buffered = cachedThroughSeconds(source, position, duration, repeats = false)
            if (other != null) {
                val otherBuffered =
                    cachedThroughSeconds(other, position, duration, repeats = otherStreamRepeats)
                buffered = minOf(buffered, otherBuffered)
            }
            return buffered.coerceAtLeast(position)
                .let { if (duration > 0f) it.coerceAtMost(duration) else it }
        }

    /**
     * True while [otherStream] is repeating under the longer one.
     *
     * AM (ONE_ENTRY_RULE): which is whenever a pair has a longer stream, loop
     * or no loop - see applyStreamLooping - and otherwise only while the entry
     * loops. This was keyed on the entry looping alone, so with the loop off a
     * repeating companion's range end was read on a clock that keeps returning
     * to zero, and the buffer drew to wherever its current lap was.
     */
    private val otherStreamRepeats: Boolean
        get() = isSplit && (pairLengthsDiffer || looping)

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
        // AM (A_HELD_REPEAT_LASTS_THE_ENTRY) -->
        // A repeating half whose whole file is held repeats from memory for as
        // long as the entry runs: every lap is a seek inside its own cache. Its
        // forward queue says otherwise - it never runs past the current lap, so
        // for an 8-second video under a song it read as a few seconds ahead
        // however long the song, and the pair's buffer, and the fill the proxy
        // ranks downloads by, sat near empty with nothing left to fetch. The
        // limiter answered that by holding every download at its floor for as
        // long as the pair played.
        if (duration > 0f && holdsWholeFile(player)) return duration
        // <-- AM (A_HELD_REPEAT_LASTS_THE_ENTRY)
        return position + readAheadSeconds(player)
    }

    // AM (A_HELD_REPEAT_LASTS_THE_ENTRY) -->
    /**
     * Whether [player]'s demuxer holds its file from start to end in one
     * seekable range: read to the end, and the range that reaches the end
     * also starts at the file's beginning.
     */
    private fun holdsWholeFile(player: MPVPlayer): Boolean {
        if (player.mpv.getPropertyBoolean("demuxer-cache-state/eof") != true) return false
        val count = player.mpv.getPropertyInt("demuxer-cache-state/seekable-ranges/count")
            ?.coerceAtMost(MAX_SEEK_RANGES)
            ?: return false
        val fileStart = player.mpv.getPropertyDouble("demuxer-start-time")?.toFloat()
            ?.takeIf { it.isFinite() }
            ?: 0f
        var furthest = Float.NEGATIVE_INFINITY
        var startOfFurthest = Float.POSITIVE_INFINITY
        for (index in 0 until count) {
            val path = "demuxer-cache-state/seekable-ranges/$index"
            val start = player.mpv.getPropertyDouble("$path/start")?.toFloat()?.takeIf { it.isFinite() } ?: continue
            val end = player.mpv.getPropertyDouble("$path/end")?.toFloat()?.takeIf { it.isFinite() } ?: continue
            if (end > furthest) {
                furthest = end
                startOfFurthest = start
            }
        }
        return startOfFurthest <= fileStart + RANGE_EDGE_EPSILON
    }
    // <-- AM (A_HELD_REPEAT_LASTS_THE_ENTRY)

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

    // AM (A_PAIRS_BUFFER_IS_BOTH_HALVES) -->
    /**
     * Whether either half of a pair has paused for cache - the proxy rate
     * limiter's starved signal, for a pair.
     *
     * The host published its own instance's `paused-for-cache`, which is the
     * video. The half that streams for the length of the entry is usually the
     * audio, and its stalls never reached the limiter: downloads and warms kept
     * their bandwidth while the sound stopped. The same property, from both.
     */
    val pairPausedForCache: Boolean
        get() = isSplit && (video.pausedForCache() || audio?.pausedForCache() == true)

    private fun MPVPlayer.pausedForCache(): Boolean = mpv.getPropertyBoolean("paused-for-cache") == true

    /**
     * How full a pair's buffer is, 0..1, against [targetSeconds] - the limiter's
     * fill signal, for a pair - or null where there is no pair or no target.
     *
     * The host's version is its own instance's `demuxer-cache-duration` over the
     * target, which a pair cannot answer with: the video of a pair is usually a
     * few seconds long, so its readahead can never reach the target, and the
     * limiter read an entry held whole as one that was starving. This is the
     * entry's own buffer ahead - [bufferedPositionSeconds], which takes the
     * lesser of the two halves and counts a fully demuxed file as reaching its
     * end - against what is left to need: the target, or the rest of the entry
     * where that is less.
     */
    fun pairBufferFill(targetSeconds: Float): Float? {
        if (!isSplit || targetSeconds <= 0f) return null
        val position = positionSeconds
        val duration = durationSeconds
        val ahead = (bufferedPositionSeconds - position).coerceAtLeast(0f)
        return bufferFill(ahead, (duration - position).takeIf { duration > 0f }, targetSeconds)
    }

    // AM (A_PAIRS_SOUND_FOLLOWS_THE_PLAYER) -->
    /**
     * Copies the sound settings written to [video] onto the companion, for as
     * long as this load lasts.
     *
     * Everything that adjusts sound writes to the host's own instance - the
     * player's volume and boost, its speed control, the pitch setting, a custom
     * button, a script, a conditional profile. For a pair that instance is the
     * video, opened with aid=no, so none of it reached what was actually
     * playing: volume did nothing, and a speed change ran the picture faster
     * than the sound and pulled the two apart.
     *
     * Read off the properties rather than taken from each writer, because the
     * property is where every one of those writers ends up - which is also what
     * lets the companion run with no scripts of its own. Each flow hands over its
     * current value on subscription, so the companion starts this load already
     * matching.
     *
     * audio-delay is not among them: it moves audio against video inside one
     * instance, and the companion has no video to move against. For a pair it
     * would have to be an offset between the two instances' positions, which
     * is behaviour of its own rather than a copy.
     */
    private fun followAudioSettings(key: Long, generation: Int) {
        val companion = audio ?: return
        // The types are the ones the player's own UI observes these as, and must
        // stay so: the mpv lib caches one flow per property NAME, so a second
        // type asked for the same name is handed the first one's flow cast to
        // it - a ClassCastException on whichever side asked second.
        follow(key, generation, video.mpv.propFlow<Int>("volume")) {
            companion.mpv.setPropertyInt("volume", it)
        }
        follow(key, generation, video.mpv.propFlow<Float>("speed")) {
            companion.mpv.setPropertyFloat("speed", it)
        }
        follow(key, generation, video.mpv.propFlow<Boolean>("audio-pitch-correction")) {
            companion.mpv.setPropertyBoolean("audio-pitch-correction", it)
        }
        follow(key, generation, video.mpv.propFlow<String>("af")) {
            companion.mpv.setPropertyString("af", it)
        }
    }

    /** Collects [flow] for this load only, handing each value it has to [write]. */
    private fun <T> follow(key: Long, generation: Int, flow: Flow<T?>, write: (T) -> Unit) {
        loadScope.launch {
            flow
                .takeWhile { generation == loadGeneration && loadedKey == key }
                .collect { value -> if (value != null) write(value) }
        }
    }
    // <-- AM (A_PAIRS_SOUND_FOLLOWS_THE_PLAYER)

    /**
     * Tells the host whenever the companion's cache state moves, for as long as
     * this load lasts - the counterpart of the host's own observers on its
     * instance. The same two properties, from the other instance.
     */
    private fun followCompanionCache(key: Long, generation: Int) {
        val notify = onBufferChanged ?: return
        val companion = audio ?: return
        val flows = listOf(
            companion.mpv.propFlow<Boolean>("paused-for-cache"),
            companion.mpv.propFlow<Float>("demuxer-cache-duration"),
        )
        for (flow in flows) {
            loadScope.launch {
                flow
                    .takeWhile { generation == loadGeneration && loadedKey == key }
                    .collect { notify() }
            }
        }
    }
    // <-- AM (A_PAIRS_BUFFER_IS_BOTH_HALVES)

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
        // AM (ONE_ENTRY_RULE): the companion EndFile watcher that used to sit
        // here is replaced by followEntryEnd - see there for why it never fired.
        loadScope.launch {
            video.eventFlow
                .filterIsInstance<MPVPlayer.Event.PauseChanged>()
                .collect { event ->
                    if (generation != loadGeneration || loadedKey != key) return@collect
                    // Only what this did not ask for: the gate's own pause
                    // must not be read back as the host wanting a pause.
                    val gated = !videoReady || !audioReady
                    if (gated && event.paused) return@collect
                    // AM (ONE_ENTRY_RULE): nor keep-open's. mpv pauses a file it
                    // holds at its end (player/playloop.c, handle_keep_open), and
                    // that pause is the video's own file finishing, not the host
                    // asking. Read as the host's, it paused the other half too -
                    // a pair whose halves both play once froze there with its
                    // audio part way through, and an entry waiting for both ends
                    // would never have seen the second. Whether the entry is over
                    // is onStreamEnded's to say.
                    // AM (A_STREAM_ENDS_WHEN_ITS_SOUND_DOES): and only where the
                    // video is a stream that ends. A repeating video never stops
                    // at its end, so it is never keep-open's to pause - but its
                    // eof-reached blinks true at every wrap, and a real pause
                    // landing in that instant was swallowed here, freezing the
                    // picture with the sound still playing.
                    if (event.paused && video in endingStreams() && hasEnded(video)) return@collect
                    if (shouldPlay == !event.paused) return@collect
                    shouldPlay = !event.paused
                    applyPlaybackState()
                }
        }
    }

    // AM (ONE_ENTRY_RULE) -->
    /**
     * Watches both instances for the two moments the entry rule turns on: the
     * longer stream ending, and the longer stream wrapping.
     *
     * The watcher this replaces listened for EndFile on the companion, which
     * never arrives at a natural end. Every instance is built with
     * keep-open=yes, and under it mpv holds the file at its last frame, pauses
     * it and latches eof-reached instead of ending the file
     * (player/playloop.c, handle_keep_open). EndFile comes only when something
     * else replaces or stops the file. So an entry whose audio outlasted its
     * video had no ending at all: the audio stopped, the video went on lapping
     * in silence, and nothing advanced. eof-reached is what mpv does report -
     * re-notified as the core goes idle (MP_EVENT_CORE_IDLE in
     * player/command.c) - and MPVPlayer already publishes it on every instance
     * as Event.EOF.
     *
     * Bounded by the load: takeWhile ends each collector at the first event
     * after a later [loadPaused] or [reset] has replaced this one, rather than
     * keeping one alive per entry ever played. A host's own non-clip load in
     * between does not move the generation, so they idle through it - inertly,
     * since stopCompanion clears the split and every handler below starts by
     * checking it.
     */
    private fun followEntryEnd(key: Long, generation: Int) {
        for (instance in listOfNotNull(video, audio)) {
            loadScope.launch {
                instance.eventFlow
                    .takeWhile { generation == loadGeneration && loadedKey == key }
                    .collect { event ->
                        when (event) {
                            is MPVPlayer.Event.EOF -> if (event.value) onStreamEnded(instance)
                            // AM (A_STREAM_ENDS_WHEN_ITS_SOUND_DOES): keep-open's
                            // pause after the drain, which for the companion is
                            // the later of the two and the one that counts.
                            is MPVPlayer.Event.PauseChanged -> if (event.paused) onStreamEnded(instance)
                            is MPVPlayer.Event.RemainingFileLoops -> onStreamLooped(instance, event.value)
                            else -> Unit
                        }
                    }
            }
        }
    }

    /**
     * [instance] reached the end of its file.
     *
     * The entry is over once every stream in [endingStreams] has ended - the
     * longer one, where there is one, since the shorter repeats and has no end of
     * its own; otherwise both halves.
     */
    private fun onStreamEnded(instance: MPVPlayer) {
        // AM (A_STREAM_ENDS_WHEN_ITS_SOUND_DOES): finished, not merely decoded -
        // see outputFinished. Reached twice for the companion, at its decoder's
        // end and again at keep-open's pause, and only the second passes.
        if (!isSplit || entryEnded || !outputFinished(instance)) return
        if (looping) {
            // Only a counted longer stream can end while the entry loops, and
            // only once mpv's ceiling of LOOP_ENTRY wraps has run out. Re-armed
            // and started again, which is what one more wrap would have done.
            if (!pairLengthsDiffer || instance !== longerStream()) return
            setLooping(instance, LOOP_ENTRY)
            seekTo(0f)
            applyPlaybackState()
            return
        }
        if (instance !in endingStreams() || endingStreams().any { !outputFinished(it) }) return
        entryEnded = true
        // keep-open paused only the instance that ended. Anything still running -
        // the repeating half - is this rule's to stop: nothing else knows the
        // entry is over.
        shouldPlay = false
        applyPlaybackState()
        onEntryEnded?.invoke()
    }

    /**
     * [instance] reported how many loops its file has left.
     *
     * A wrap of the longer stream while the entry loops puts the shorter one
     * back to its own start with it, so every pass of the entry pairs the same
     * picture with the same sound. Left alone, the shorter stream carries on
     * through whatever lap it is in.
     *
     * A wrap is a drop of exactly one - mpv lowers the count by one per wrap.
     * Every report is recorded so the next compares against the latest, and
     * only that drop is acted on. The loop writes made here either raise the
     * count or drop it to zero with the loop going off, and a stream whose loop
     * is off cannot be wrapping, which is what the [looping] check says.
     */
    private fun onStreamLooped(instance: MPVPlayer, loopsLeft: Long) {
        val previous = if (instance === video) videoLoopsLeft else audioLoopsLeft
        if (instance === video) videoLoopsLeft = loopsLeft else audioLoopsLeft = loopsLeft
        if (previous == null || previous - loopsLeft != 1L) return
        if (!looping || !isSplit || !pairLengthsDiffer) return
        if (instance !== longerStream()) return
        otherStream()?.seekToMappedPosition(0f)
    }

    /**
     * Carries out a host's `seek` command on a pair, folded onto both streams.
     *
     * Replaces mirrorCommand, which replayed the host's raw command against the
     * companion after the host had already sent it to the video: an absolute
     * target landed unfolded on both - the defect seekTo's fold exists to
     * prevent - and a relative one was measured from each instance's own clock.
     * Taking the command rather than following it makes it one seek, issued
     * once, by the one thing that knows how a time maps onto two streams.
     *
     * Returns false for anything this does not handle - not a seek, not a pair,
     * or a form it cannot read - which the host then issues as it always did.
     */
    fun seekCommand(command: Array<out String>): Boolean {
        if (!isSplit || command.firstOrNull() != "seek") return false
        val amount = command.getOrNull(1)?.toFloatOrNull() ?: return false
        val flags = command.getOrNull(2).orEmpty()
        val duration = durationSeconds
        val target = when {
            "absolute-percent" in flags -> duration * amount / 100f
            "relative-percent" in flags -> positionSeconds + duration * amount / 100f
            "absolute" in flags -> amount
            else -> positionSeconds + amount
        }
        seekTo(target)
        return true
    }
    // <-- AM (ONE_ENTRY_RULE)

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
        // AM (A_A_LOAD_IS_MEASURED_ONCE): and only once. Each half reports its
        // own readiness and the bounded poll reports both, so this ran twice
        // for every pair - measuring again, re-applying the loop, and telling
        // the host its timeline was ready a second time.
        if (videoReady && audioReady && !measuredThisLoad) {
            latchLengths()
            measuredThisLoad = latchedVideoLength != null && latchedAudioLength != null
            applyStreamLooping()
            // AM (ONE_ENTRY_RULE): the counts the rule just armed, read rather
            // than waited for. mpv sends an observed property only when it
            // differs from what it last sent, so an arm that lands on the value
            // the previous entry was armed with is never reported - and the
            // first wrap would then have nothing to be a drop from.
            videoLoopsLeft = video.mpv.getPropertyInt("remaining-file-loops")?.toLong()
            audioLoopsLeft = audio?.mpv?.getPropertyInt("remaining-file-loops")?.toLong()
            // AM (A_A_TIMELINE_IS_NOT_KNOWN_UNTIL_BOTH_HALVES_ARE)
            onTimelineReady?.invoke()
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
        // AM (A_STREAM_ENDS_WHEN_ITS_SOUND_DOES): through setPaused, which never
        // pauses an instance already held at its end.
        setPaused(video, paused)
        audio?.let { setPaused(it, paused) }
    }

    private fun applyMuteState(): Boolean {
        // Only the entry being watched makes sound, whatever the mute setting
        // says.
        val effectivelyMuted = isMuted || !mayHoldAudioFocus
        // AM (A_THE_PLAYING_CARD_KEEPS_ITS_TRACK): keyed on playing, not on
        // being audible. Selecting a track is not free - mpv opens the stream,
        // syncs it and starts an output - and keying it on audibility put that
        // between reaching a card and hearing it. The feed plays one card at a
        // time, so this still leaves a track selected on one instance, which
        // is what the release below was measured to be worth.
        if (managesAudio && (shouldPlay || !effectivelyMuted)) restoreAudioTrackSelection()
        video.mpv.setPropertyBoolean("mute", effectivelyMuted)
        audio?.mpv?.setPropertyBoolean("mute", effectivelyMuted)
        // AM (A_SILENT_IS_NOT_THE_SAME_AS_OFF) -->
        // Muting an instance does not stop it producing audio: mute is a
        // volume of zero, so mpv keeps the track selected, keeps decoding it,
        // and keeps an AudioTrack started and writing. A feed holds a pool of
        // instances and mutes all but one, so every one of them was running an
        // audio output nobody could hear.
        //
        // Measured on a release build, 20s of scrolling: five AudioTracks in
        // `dumpsys audio`, all for this process, all state:started. The profile
        // put 20.18% of all thread time in one chain - libmpv -> CallIntMethod
        // -> AudioTrack.write -> JNI -> pthread_mutex_unlock -> futex - with
        // only one underrun in the whole run, so it was contention between the
        // tracks rather than any of them starving. Decode was not the cost:
        // libmpv.so was 6.62% in total and libavcodec did not place.
        //
        // Deselecting the track tears the output down, which is what makes an
        // inactive card free. The pairing with restoreAudioTrackSelection above
        // is deliberate: that one already knows which instance owns sound for a
        // split load, and this one has to make exactly the same choice or a
        // split card goes permanently silent.
        // AM (A_THE_PLAYING_CARD_KEEPS_ITS_TRACK): released from the cards
        // that are neither playing nor audible - the preloaded ones either
        // side, which is where the outputs nobody could hear were.
        if (managesAudio && effectivelyMuted && !shouldPlay) clearAudioTrackSelection()
        // <-- AM (A_SILENT_IS_NOT_THE_SAME_AS_OFF)
        return effectivelyMuted
    }

    /**
     * The scroller has no ViewModel track observer to repair a cleared aid.
     * Select on the stream that actually owns sound: the companion for a split
     * load, otherwise the video. In particular, never re-enable aid on the
     * video half of a same-file split, which would play its audio twice.
     */
    private fun restoreAudioTrackSelection() {
        // AM (A_TRACK_SELECTION_BELONGS_TO_AN_OPEN_STREAM)
        if (!videoReady || !audioReady) return
        val target = audioOwner() ?: return
        val selected = target.mpv.getPropertyString("aid")
        if (selected == null || selected == "no") {
            target.mpv.setPropertyString("aid", "auto")
        }
    }

    // AM (A_SILENT_IS_NOT_THE_SAME_AS_OFF) -->
    /**
     * Deselects the audio track, so an instance nobody is listening to stops
     * holding an audio output. The inverse of [restoreAudioTrackSelection],
     * and it picks the same instance for the same reason.
     *
     * Only reached for a host that manages audio - see [managesAudio] - where
     * exactly one card is audible at a time. A host that does not is never
     * silenced this way, so an ordinary player cannot lose its audio here.
     */
    private fun clearAudioTrackSelection() {
        // AM (A_TRACK_SELECTION_BELONGS_TO_AN_OPEN_STREAM) -->
        // There is nothing to release until the streams are open, and writing
        // aid before they are does not postpone the release - it breaks the
        // open. applyPlaybackState runs immediately after the loadfile
        // commands go out and every card the feed loads is muted at that
        // moment, so this fired on a companion whose file was still opening
        // and mpv then selected its tracks with aid=no in force: no audio
        // track, vid=no beside it, no streams at all, and the card never
        // started. Deferred rather than dropped - onStreamReady applies the
        // playback state again once both halves are open.
        if (!videoReady || !audioReady) return
        // <-- AM (A_TRACK_SELECTION_BELONGS_TO_AN_OPEN_STREAM)
        val target = audioOwner() ?: return
        val selected = target.mpv.getPropertyString("aid")
        if (selected != null && selected != "no") {
            target.mpv.setPropertyString("aid", "no")
        }
    }

    /**
     * The instance that owns sound for the current load: the companion for a
     * split, otherwise the video - never the video half of a same-file split,
     * whose audio would then play twice.
     */
    private fun audioOwner(): MPVPlayer? = audio ?: video.takeUnless { splitLoad }
    // <-- AM (A_SILENT_IS_NOT_THE_SAME_AS_OFF)

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
        // AM (ONE_ENTRY_RULE)
        entryEnded = false
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

        // AM (ONE_FILL_RULE) -->
        /**
         * How full a buffer [aheadSeconds] deep is, 0..1: against [targetSeconds],
         * or against what is left of the media where that is less.
         *
         * One rule for a pair and for an ordinary file. The cap is what lets a
         * short file, or the last stretch of a long one, ever read as full: a
         * buffer cannot hold more than what remains, and measured against the
         * whole target it read as behind for as long as it played - which the
         * proxy's limiter answers by holding downloads at their floor.
         * [remainingSeconds] is null where the length is unknown.
         */
        fun bufferFill(aheadSeconds: Float, remainingSeconds: Float?, targetSeconds: Float): Float {
            val needed = remainingSeconds?.let { minOf(targetSeconds, it) } ?: targetSeconds
            if (needed <= 0f) return 1f
            return (aheadSeconds / needed).coerceIn(0f, 1f)
        }
        // <-- AM (ONE_FILL_RULE)

        // AM (ONE_ENTRY_RULE) -->
        /**
         * The longer stream's loop while the entry repeats: mpv's largest finite
         * loop-file count (options/options.c, M_RANGE(0, 10000)).
         *
         * Finite on purpose. Only a finite count is lowered at each wrap, and
         * that lowering is what reports the wrap - `inf` wraps just as
         * seamlessly but says nothing. Ten thousand passes of even a short
         * track is hours of one entry; running out is handled in
         * onStreamEnded by arming it again.
         */
        private const val LOOP_ENTRY = "10000"
        // <-- AM (ONE_ENTRY_RULE)
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

        // AM (ONE_ENTRY_RULE): takes the loop-file value itself - off, forever,
        // or the entry's counted loop - since a yes/no can no longer say which.
        private fun setLooping(player: MPVPlayer, value: String) {
            // AM (A_A_LOOP_IS_SET_AT_BOTH_SCOPES): the option is what the
            // next file inherits, the property is the file open now, and
            // this is called at both moments.
            player.mpv.setOptionString("loop-file", value)
            player.mpv.setPropertyString("loop-file", value)
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
