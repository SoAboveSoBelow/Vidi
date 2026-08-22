package eu.kanade.tachiyomi.ui.player.mpv

import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.graphics.ImageFormat
import android.media.AudioManager
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Surface
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import animiru.domain.player.model.Debanding
import animiru.domain.player.model.VideoFilters
import animiru.domain.player.service.AdvancedPlayerPreferences
import animiru.domain.player.service.AudioPreferences
import animiru.domain.player.service.DecoderPreferences
import animiru.domain.player.service.PlayerPreferences
import animiru.domain.player.service.SubtitleAssOverride
import animiru.domain.player.service.SubtitlePreferences
import animiru.feature.mpvfiles.MpvConfig
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.player.controls.components.panels.toColorHexString
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.collections.component1
import kotlin.collections.component2

class MPVPlayer(
    context: Context,
    videoOutput: String,
    playerPreferences: PlayerPreferences = Injekt.get(),
    decoderPreferences: DecoderPreferences = Injekt.get(),
    networkPreferences: NetworkPreferences = Injekt.get(),
    advancedPreferences: AdvancedPlayerPreferences = Injekt.get(),
    private val subtitlePreferences: SubtitlePreferences = Injekt.get(),
    private val audioPreferences: AudioPreferences = Injekt.get(),
) : MPV.EventObserver, MPV.LogObserver, AudioManager.OnAudioFocusChangeListener {

    val mpv: MPV
    private val handler = Handler(context.mainLooper)

    private val audioManager by lazy { context.getSystemService(AUDIO_SERVICE) as AudioManager }
    private var restoreAudioFocus: () -> Unit = {}
    private var audioFocusRequest: AudioFocusRequestCompat? = null

    @Volatile
    var isExiting = false
    private var httpError: String? = null

    // AM (HWDEC_REATTACH_FIX) -->
    /**
     * Tracks whether a real UI Surface has ever been attached to this MPVPlayer
     * instance, across every MpvSurface Composable that's ever wrapped it - not
     * scoped to any single Composable/Activity instance. This used to live as a
     * local var inside MpvSurface's factory closure, which meant it reset to false
     * whenever a fresh Composable/AndroidView got created for the same underlying
     * player (e.g. during the notification-reopen flow), even though the actual
     * decode session was continuing, not starting fresh. That falsely-first-attach
     * state caused hwdec to get re-set on what was really just a reattach - forcing
     * MediaCodec to tear down and reinitialize its decoder mid-stream, which can get
     * stuck waiting for a keyframe that never arrives ("buffers indefinitely").
     * Living here instead, on the object that actually persists across all of that,
     * fixes it regardless of how many Composable/Activity instances come and go.
     */
    var hasAttachedSurfaceBefore = false
    // <-- AM (HWDEC_REATTACH_FIX)

    // AM (HOT_VIDEO_BACKGROUND) -->
    /**
     * Off-screen Surface (backed by an ImageReader that immediately discards every
     * frame it receives) that mpv gets swapped onto instead of having its video
     * output nulled out. Backgrounding a video that's actively mid-playback (not
     * mid-load) by setting vo="null" and fully detaching the Surface is an abrupt
     * stop of the whole video pipeline mid-stream - a plausible way for mpv's
     * internal video state to end up wedged in a way that doesn't cleanly resume
     * once a real Surface comes back, even though audio (unaffected by vo) keeps
     * playing the entire time, making the break invisible until the video is shown
     * again ("buffers indefinitely" on reopen, with no warning while backgrounded,
     * since there's nothing visible to notice it on). Swapping onto this dummy
     * surface instead means vo/hwdec/vid are never touched on background/detach at
     * all - the pipeline keeps running exactly as before, just with nowhere
     * visible to render to; MpvSurface.kt's surfaceCreated() swaps the real
     * Surface back in later exactly the same way it always has (unaffected by
     * this - reattach logic is untouched).
     *
     * AM (DUMMY_SURFACE_SIZE_FIX) -->
     * Sized generously (1920x1080), not 2x2. Confirmed via logcat: a fresh
     * hardware decoder session established while attached to this surface (e.g.
     * loading a new episode while hidden, or any other path that needs a real
     * decoder init rather than a simple render-target swap on an
     * already-decoding stream) needs to negotiate an output buffer large enough
     * for actual video frames - ImageReader's buffer queue is dimension-locked
     * at creation time, unlike a SurfaceView, which resizes dynamically. A 2x2
     * target caused that negotiation to fail outright ("Unsupported output
     * color format for c2d" / "Setting color format failed" from the Qualcomm
     * OMX decoder), which is a very plausible route to mpv/the whole pipeline
     * ending up in a broken state. 1920x1080 comfortably covers real-world
     * video resolutions without needing to dynamically resize this reader to
     * match whatever's currently playing.
     * <-- AM (DUMMY_SURFACE_SIZE_FIX)
     */
    private var dummyImageReader: ImageReader? = null
    private var dummyThread: HandlerThread? = null

    fun ensureDummySurface(): Surface {
        dummyImageReader?.let { return it.surface }
        val thread = HandlerThread("MPVPlayer-DummySurface").apply { start() }
        dummyThread = thread
        // AM (DUMMY_SURFACE_FORMAT_FIX) -->
        // ImageFormat.PRIVATE, not an explicit RGB PixelFormat. Confirmed via logcat:
        // a fresh hardware decoder session attached to this surface (via mpv's
        // "aimagereader" hwdec interop path) produces opaque, GPU-native buffers -
        // not CPU-readable RGB pixels. Requesting an explicit RGB format from
        // ImageReader asks the decoder to actually convert into that format, which
        // this specific hwdec path doesn't support ("Unsupported output color format
        // for c2d" / "Setting color format failed" from the Qualcomm OMX decoder).
        // PRIVATE is the correct, documented contract for "receive hardware buffers
        // without reading pixels" - exactly this dummy surface's actual use case,
        // since every frame gets discarded immediately regardless of format.
        val reader = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 2).apply {
            // <-- AM (DUMMY_SURFACE_FORMAT_FIX)
            setOnImageAvailableListener({ ir -> ir.acquireLatestImage()?.close() }, Handler(thread.looper))
        }
        dummyImageReader = reader
        return reader.surface
    }
    // <-- AM (HOT_VIDEO_BACKGROUND)

    private val _eventFlow = MutableSharedFlow<Event>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val eventFlow = _eventFlow.asSharedFlow()

    init {
        val cachePath: String = context.cacheDir.path

        val mpvDir = UniFile.fromFile(context.filesDir)!!.createDirectory(MPV_DIR)!!

        val mpvConfFile = mpvDir.createFile("mpv.conf")!!
        advancedPreferences.mpvConf.get().let { mpvConfFile.writeText(it) }
        val mpvInputFile = mpvDir.createFile("input.conf")!!
        advancedPreferences.mpvInput.get().let { mpvInputFile.writeText(it) }

        mpv = MPV(context) {
            it.setOptionString("config", "yes")
            it.setOptionString("config-dir", context.filesDir.resolve(MPV_DIR).toString())
            it.setOptionString("gpu-shader-cache-dir", cachePath)
            it.setOptionString("icc-cache-dir", cachePath)
            it.setOptionString("keep-open", "yes")
        }

        val optionNameRegex = Regex("""^(?:--)?([\w-]+)(?:=|$)""", RegexOption.MULTILINE)
        val mpvOptionNames = optionNameRegex.findAll(advancedPreferences.mpvConf.get()).map {
            it.groupValues[1].removePrefix("no-")
        }.toSet()

        // Set mpv option unless it's present in mpv.conf
        fun setSafeOptionString(name: String, value: String) {
            if (name in mpvOptionNames) return
            mpv.setOptionString(name, value)
        }

        mpv.setOptionString("vo", videoOutput)
        setSafeOptionString("profile", "fast")
        setSafeOptionString("hwdec", if (decoderPreferences.tryHWDecoding.get()) "mediacodec,mediacodec-copy" else "no")
        if (decoderPreferences.useYUV420P.get()) {
            mpv.setOptionString("vf", "format=yuv420p")
        }

        setSafeOptionString("msg-level", "all=" + if (networkPreferences.verboseLogging.get()) "v" else "warn")
        mpv.setPropertyBoolean("input-default-bindings", true)
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("ytdl", "no")
        setSafeOptionString("tls-verify", "yes")
        setSafeOptionString("tls-ca-file", "${context.filesDir.path}/${MpvConfig.MPV_DIR}/cacert.pem")

        // Selection is handled in viewmodel
        mpv.setOptionString("sid", "no")
        mpv.setOptionString("aid", "no")

        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        setSafeOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        setSafeOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")

        val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).also {
            it.mkdirs()
        }
        mpv.setOptionString("screenshot-directory", screenshotDir.path)

        VideoFilters.entries.forEach {
            setSafeOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
        }

        mpv.setOptionString("speed", playerPreferences.playerSpeed.get().toString())
        // workaround for <https://github.com/mpv-player/mpv/issues/14651>
        setSafeOptionString("vd-lavc-film-grain", "cpu")

        when (decoderPreferences.debanding.get()) {
            Debanding.None -> {}
            Debanding.CPU -> mpv.setOptionString("vf", "gradfun=radius=12")
            Debanding.GPU -> mpv.setOptionString("deband", "yes")
        }

        advancedPreferences.playerStatisticsPage.get().let {
            if (it != 0) {
                mpv.command("script-binding", "stats/display-stats-toggle")
                mpv.command("script-binding", "stats/display-page-$it")
            }
        }

        mpv.addObserver(this)
        mpv.addLogObserver(this)

        setupSubtitlesOptions()
        setupAudio()

        mapOf(
            "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            // AM (MEDIASESSION_EVENT_DRIVEN_FIX) -->
            // Added so pause/resume becomes a genuine, immediate mpv event
            // (Event.PauseChanged below) instead of something callers had to poll
            // for or infer from their own state copies.
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            // <-- AM (MEDIASESSION_EVENT_DRIVEN_FIX)

            "user-data/aniyomi/show_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/toggle_ui" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/show_panel" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/software_keyboard" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/set_button_title" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/reset_button_title" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/toggle_button" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/switch_episode" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/pause" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/seek_by" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/seek_to" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/seek_by_with_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/seek_to_with_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/launch_int_picker" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "user-data/aniyomi/show_seek_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
        ).forEach { (name, format) ->
            mpv.observeProperty(name, format)
        }
    }

    private fun UniFile.writeText(text: String) {
        this.openOutputStream().use {
            it.write(text.toByteArray())
        }
    }

    private fun setupAudio() {
        mpv.setOptionString("alang", audioPreferences.preferredAudioLanguages.get())
        mpv.setOptionString("audio-delay", (audioPreferences.audioDelay.get() / 1000.0).toString())
        mpv.setOptionString("audio-pitch-correction", audioPreferences.enablePitchCorrection.get().toString())
        mpv.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())

        audioPreferences.audioChannels.get().let {
            mpv.setPropertyString(it.property, it.value)
        }
    }

    // AM (AUDIO_FOCUS_ORPHAN_FIX) -->
    /**
     * Deliberately NOT called automatically from init{} (unlike the rest of
     * setupAudio() above, which is - those are just mpv option config, harmless
     * even for a player that turns out to be an immediately-discarded orphan).
     * Requesting audio focus is different: it's a live, OS-visible claim, and
     * every MPVPlayer construction used to make one unconditionally at
     * construction time - including for a player that PlayerMediaHolder.adopt()
     * is about to determine is an orphan (a duplicate ViewModel's own player,
     * discarded in favor of an already-adopted canonical one). The Service's own
     * bind is asynchronous, so there's a real gap between "orphan is constructed
     * and requests focus" and "orphan is identified and released" - during which
     * the actually-playing canonical player receives a live AUDIOFOCUS_LOSS
     * notification from the OS (since it's this same app requesting focus again),
     * forcing onAudioFocusChange() to pause it. A plausible match for reported
     * audio "flickering in and out" right around a PIP re-entry attempt, since
     * that's exactly the kind of moment a fresh ViewModel/player construction can
     * occur. PlayerViewModel.bindToService() calls this explicitly now, only once
     * it's confirmed (via PlayerMediaHolder.hasAdoptedPlayer, checked before
     * calling adopt()) that this is a genuinely first-ever adoption - an
     * already-canonical player from a prior session already holds focus from when
     * it was originally constructed and never needs to re-request it.
     */
    fun requestAudioFocus() {
        val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN).also {
            it.setAudioAttributes(
                AudioAttributesCompat.Builder().setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC).build(),
            )
            it.setOnAudioFocusChangeListener(this)
        }.build()
        AudioManagerCompat.requestAudioFocus(audioManager, request).let {
            if (it == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return@let
            audioFocusRequest = request
        }
    }
    // <-- AM (AUDIO_FOCUS_ORPHAN_FIX)

    private fun setupSubtitlesOptions() {
        mpv.setOptionString("sub-delay", (subtitlePreferences.subtitlesDelay.get() / 1000.0).toString())
        mpv.setOptionString("sub-speed", subtitlePreferences.subtitlesSpeed.get().toString())
        mpv.setOptionString(
            "secondary-sub-delay",
            (subtitlePreferences.subtitlesSecondaryDelay.get() / 1000.0).toString(),
        )

        mpv.setOptionString("sub-font", subtitlePreferences.subtitleFont.get())
        subtitlePreferences.overrideSubsASS.get().let {
            mpv.setOptionString("sub-ass-override", it.value)
            if (it != SubtitleAssOverride.No) {
                mpv.setOptionString("sub-ass-justify", "yes")
            }
        }
        mpv.setOptionString("sub-font-size", subtitlePreferences.subtitleFontSize.get().toString())
        mpv.setOptionString("sub-bold", if (subtitlePreferences.boldSubtitles.get()) "yes" else "no")
        mpv.setOptionString("sub-italic", if (subtitlePreferences.italicSubtitles.get()) "yes" else "no")
        mpv.setOptionString("sub-justify", subtitlePreferences.subtitleJustification.get().value)
        mpv.setOptionString("sub-color", subtitlePreferences.textColorSubtitles.get().toColorHexString())
        mpv.setOptionString(
            "sub-back-color",
            subtitlePreferences.backgroundColorSubtitles.get().toColorHexString(),
        )
        mpv.setOptionString("sub-outline-color", subtitlePreferences.borderColorSubtitles.get().toColorHexString())
        mpv.setOptionString("sub-outline-size", subtitlePreferences.subtitleBorderSize.get().toString())
        mpv.setOptionString("sub-border-style", subtitlePreferences.borderStyleSubtitles.get().value)
        mpv.setOptionString("sub-shadow-offset", subtitlePreferences.shadowOffsetSubtitles.get().toString())
        mpv.setOptionString("sub-pos", subtitlePreferences.subtitlePos.get().toString())
        mpv.setOptionString("sub-scale", subtitlePreferences.subtitleFontScale.get().toString())

        val showBlackBars = if (subtitlePreferences.subtitleBlackBars.get()) "yes" else "no"
        mpv.setOptionString("sub-ass-force-margins", showBlackBars)
        mpv.setOptionString("sub-use-margins", showBlackBars)
    }

    override fun eventProperty(property: String) {
        handler.post {
            if (isExiting) return@post
        }
    }

    override fun eventProperty(property: String, value: Long) {
        handler.post {
            if (isExiting) return@post
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        handler.post {
            if (isExiting) return@post
            when (property) {
                "eof-reached" -> _eventFlow.tryEmit(Event.EOF(value))
                // AM (MEDIASESSION_EVENT_DRIVEN_FIX) -->
                // A genuine mpv property-change event, not a copy of some other
                // component's own state - fires exactly once per real pause/resume
                // toggle, whatever the source (UI tap, MediaSession callback, an
                // internal freeze-pause around an episode switch). Consumers that
                // only care about genuine user-facing pause/resume (as opposed to
                // internal transitional pauses) can filter on their own context if
                // that distinction matters to them - this event just reports what
                // mpv itself actually did, honestly.
                // <-- AM (MEDIASESSION_EVENT_DRIVEN_FIX)
                "pause" -> _eventFlow.tryEmit(Event.PauseChanged(value))
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        handler.post {
            if (isExiting) return@post
            when (property.substringBeforeLast("/")) {
                "user-data/aniyomi" -> _eventFlow.tryEmit(Event.LuaEvent(property, value))
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        handler.post {
            if (isExiting) return@post
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        handler.post {
            if (isExiting) return@post
        }
    }

    override fun event(eventId: Int, data: MPVNode) {
        handler.post {
            if (isExiting) return@post
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> _eventFlow.tryEmit(Event.FileLoaded)
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    isExiting = false
                    // AM (MEDIASESSION_EVENT_DRIVEN_FIX) -->
                    // mpv fires this on every playback discontinuity - overwhelmingly
                    // a user seek, also after unpausing in some cases. This was
                    // already being received (for the isExiting reset above) but
                    // silently discarded otherwise - nothing downstream could react
                    // to a seek actually happening. PlaybackState's speed+timestamp
                    // lets the OS interpolate the seek bar during ordinary steady
                    // playback, but a seek is exactly the one case interpolation
                    // can't predict - the position just jumped. This is the signal
                    // to push a corrected position immediately instead of waiting
                    // for a periodic backstop to eventually catch up.
                    // <-- AM (MEDIASESSION_EVENT_DRIVEN_FIX)
                    _eventFlow.tryEmit(Event.PlaybackRestart)
                }
                MPV.mpvEvent.MPV_EVENT_END_FILE -> _eventFlow.tryEmit(Event.EndFile(data))
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level == MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR) {
            if (text.startsWith(TRACK_LOAD_FAILURE)) {
                val url = text.removePrefix(TRACK_LOAD_FAILURE).substringBeforeLast(".")
                _eventFlow.tryEmit(Event.TrackLoadFailure(url))
            }
        }

        val logPriority = when (level) {
            MPV.mpvLogLevel.MPV_LOG_LEVEL_FATAL, MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        if (text.contains("HTTP error")) httpError = text.removePrefix("http: ")
        logcat("$TAG/$prefix", logPriority) { text }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val oldRestore = restoreAudioFocus
                val wasPlayerPaused = mpv.getPropertyBoolean("pause") ?: true
                mpv.setPropertyBoolean("pause", true)
                restoreAudioFocus = {
                    oldRestore()
                    if (!wasPlayerPaused) mpv.setPropertyBoolean("pause", false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mpv.command("multiply", "volume", "0.5")
                restoreAudioFocus = {
                    mpv.command("multiply", "volume", "2")
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreAudioFocus()
                restoreAudioFocus = {}
            }

            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                logcat(TAG, LogPriority.DEBUG) { "didn't get audio focus" }
            }
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) {
            return false
        }

        var mapped = KeyMapping[event.keyCode]
        if (mapped == null) {
            // Fallback to produced glyph
            if (!event.isPrintingKey) {
                if (event.repeatCount == 0) {
                    logcat(TAG, LogPriority.DEBUG) { "Unmapped non-printable key ${event.keyCode}" }
                }
                return false
            }

            val ch = event.unicodeChar
            if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) {
                return false // dead key
            }
            mapped = ch.toChar().toString()
        }

        if (event.repeatCount > 0) {
            return true // eat event but ignore it, mpv has its own key repeat
        }

        val mod: MutableList<String> = mutableListOf()
        event.isShiftPressed && mod.add("shift")
        event.isCtrlPressed && mod.add("ctrl")
        event.isAltPressed && mod.add("alt")
        event.isMetaPressed && mod.add("meta")

        val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
        mod.add(mapped)
        mpv.command(action, mod.joinToString("+"))

        return true
    }

    // ===== End events =====

    fun getHttpError(): String? {
        return httpError
    }

    fun resetHttpError() {
        httpError = null
    }

    fun release() {
        if (isExiting) return
        isExiting = true

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, it)
        }
        audioFocusRequest = null

        handler.removeCallbacksAndMessages(null)
        mpv.removeObserver(this)
        mpv.removeLogObserver(this)
        // AM (RELEASE_SURFACE_DETACH_FIX) -->
        // Explicitly detach before close(), not left to whatever surfaceDestroyed()
        // callback may or may not still be pending. This matters most for the
        // orphan-dedup path in PlayerViewModel.bindToService(): the orphaned
        // player's mpv can still have a real, live Android Surface attached to it
        // from MpvSurface's first, pre-bind Compose composition - that Surface only
        // gets properly torn down later, reactively, once playerReady flips and
        // Compose rebuilds away from this player, which happens strictly after
        // release() already ran here. Without this, close() tears down the native
        // mpv/EGL context while a real hardware Surface is still attached to it -
        // an abrupt teardown, not a clean one, and a plausible source of a wedged
        // GPU/decoder driver state that can affect the very next Surface attached
        // moments later (the canonical player's own reattach) - matching a
        // permanent "buffers indefinitely" stall on reopen that a fresh episode
        // load (which gets a fresh decoder session) sidesteps. Safe to call
        // unconditionally: detaching an already-detached/never-attached surface is
        // the same no-op MpvSurface's own surfaceDestroyed() already relies on.
        mpv.detachSurface()
        // <-- AM (RELEASE_SURFACE_DETACH_FIX)
        mpv.close()
        // AM (HOT_VIDEO_BACKGROUND) -->
        dummyImageReader?.close()
        dummyImageReader = null
        dummyThread?.quitSafely()
        dummyThread = null
        // <-- AM (HOT_VIDEO_BACKGROUND)
    }

    sealed interface Event {
        data object FileLoaded : Event
        data class EOF(val value: Boolean) : Event
        data class TrackLoadFailure(val url: String) : Event
        data class EndFile(val node: MPVNode) : Event
        data class LuaEvent(val property: String, val value: String) : Event
        // AM (MEDIASESSION_EVENT_DRIVEN_FIX) -->
        data class PauseChanged(val paused: Boolean) : Event
        data object PlaybackRestart : Event
        // <-- AM (MEDIASESSION_EVENT_DRIVEN_FIX)
    }

    companion object {
        private const val TAG = "mpv"
        private const val MPV_DIR = "mpv"
        const val TRACK_LOAD_FAILURE = "Can not open external file "
    }
}
