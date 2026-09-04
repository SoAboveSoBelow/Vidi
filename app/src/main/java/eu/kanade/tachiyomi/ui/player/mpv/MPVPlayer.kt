package eu.kanade.tachiyomi.ui.player.mpv

import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.Handler
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

    // AM (ISEXITING_SPLIT_FIX) -->
    // isExiting used to mean three different things: a permanent one-way
    // teardown latch (release()), a resettable foreground/background toggle
    // (PlayerViewModel.setPlayerExiting(), driven by onPause()/onResume()),
    // and a transient reset on mpv's own MPV_EVENT_PLAYBACK_RESTART. Since
    // onPause() always runs before onDestroy(), the foreground toggle
    // poisoned release()'s own guard on every genuine close, silently
    // skipping mpv.close()/surface release - the same leak class
    // NATIVE_PLAYER_LEAK_FIX already fixed once, through a different call
    // path (see PlayerMediaHolder.release()). Split into the two fields
    // below so neither meaning can interfere with the other; the
    // PLAYBACK_RESTART reset matched neither and was removed (see that
    // handler's own note).
    @Volatile
    var isReleased = false
        private set

    var isForegroundSuspended = false
    // <-- AM (ISEXITING_SPLIT_FIX)
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

    // AM (PERSISTENT_SURFACE_ARCHITECTURE) -->
    /**
     * The single SurfaceTexture mpv is ever attached to (via wid) for this player's
     * entire lifetime, regardless of how many Activity/Composable instances come
     * and go. Root cause this exists to fix, confirmed via a native mpv diagnostic
     * (mp_option_change_callback logging co='wid'/co='vo' with UPDATE_VO flags
     * immediately preceding every single hr-seek observed on reopen, across many
     * captures): finish()-based backgrounding destroys the whole View hierarchy,
     * so MpvSurface's TextureView creates a genuinely NEW SurfaceTexture on every
     * reopen, and a new wid is - correctly, per mpv's own design - never a no-op:
     * it always costs a full VO reconfigure and refresh-seek. That's inherent to
     * ANY new SurfaceTexture, not fixable by guarding redundant property sets the
     * way vo/hwdec/vid already are elsewhere in this file, because the wid value
     * genuinely does change every time - there's nothing false to detect.
     *
     * The actual fix: don't let a new SurfaceTexture ever get created after the
     * first one. This is claimed once, on the very first real attach, and handed
     * to every subsequent TextureView via TextureView.setSurfaceTexture() (see
     * MpvSurface.kt) instead of letting each new TextureView create its own -
     * this is Android's own documented pattern for preserving a SurfaceTexture
     * across Activity recreation (AOSP's "Grafika Double Decode" sample;
     * source.android.com/docs/core/graphics/arch-tv). mpv keeps rendering into
     * the exact same target the entire time, whether or not anything is
     * currently displaying it - wid never changes again after this is first set,
     * for the life of this player object.
     */
    var persistentSurfaceTexture: SurfaceTexture? = null
    // <-- AM (PERSISTENT_SURFACE_ARCHITECTURE)

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
        // AM (HWDEC_MEDIACODEC_COPY_REMOVED) -->
        // Was "mediacodec,mediacodec-copy". mediacodec-copy (the AImageReader/
        // EGL-gated path, video/out/hwdec/hwdec_aimagereader.c in mpv's own
        // source) requires a live EGL context at the exact init moment
        // (eglGetCurrentContext() check, fails immediately if none exists) - on
        // reopen after backgrounding, this consistently lost the race against
        // vo=gpu's own EGL context still being re-established, failing with
        // "Both surface and native_window are NULL" and forcing a costly
        // decoder reinit + seek before mpv fell through to plain mediacodec
        // (which only needs the app-provided surface directly, no separate EGL
        // requirement). Removing mediacodec-copy from consideration avoids that
        // failure path entirely - plain mediacodec has been reliable across
        // everything tested.
        setSafeOptionString("hwdec", if (decoderPreferences.tryHWDecoding.get()) "mediacodec" else "no")
        // <-- AM (HWDEC_MEDIACODEC_COPY_REMOVED)
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
        // AM (AUDIO_UNDERRUN_ON_VO_RECONFIG_FIX) -->
        // mpv's default audio-buffer (~0.2s) is small enough that any transient
        // scheduling hiccup can starve it - confirmed via full logcat captures that
        // this happens on EVERY vo surface swap (both PlayerActivity finishing and
        // reopening), not just a specific reattach path: mpv logs its own internal
        // "event: playback-restart" -> "starting audio playback" -> immediately
        // "Audio device underrun detected." every single time, including on a
        // genuinely fresh cold start with no prior session at all. This is a known,
        // still-open upstream mpv/libplacebo behavior (see mpv-player/mpv#13676 -
        // same "vo=gpu-next" + backgrounding trigger, same underrun signature, no
        // upstream fix landed) rather than anything specific to this app's surface-
        // reattach logic - HWDEC_REATTACH_FIX/VO_REATTACH_FIX/VO_REATTACH_FIX above
        // already eliminate every REDUNDANT reconfigure; this is the one genuine,
        // first-time vo reconfigure per attach, which mpv itself always treats as a
        // playback-restart. A larger buffer doesn't stop mpv from restarting the
        // audio device, but the restart has a bigger cushion to refill from before
        // audibly running dry - mpv-player/mpv#13189 reports this working well for
        // an analogous underrun class. Needs on-device confirmation: does this
        // reduce/eliminate the underrun audibly, and does 1 second introduce any
        // perceptible added latency on seeks/track switches.
        mpv.setOptionString("audio-buffer", "1.0")
        // <-- AM (AUDIO_UNDERRUN_ON_VO_RECONFIG_FIX)

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
            if (isReleased) return@post
        }
    }

    override fun eventProperty(property: String, value: Long) {
        handler.post {
            if (isReleased) return@post
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        handler.post {
            if (isReleased) return@post
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
            if (isReleased) return@post
            when (property.substringBeforeLast("/")) {
                "user-data/aniyomi" -> _eventFlow.tryEmit(Event.LuaEvent(property, value))
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        handler.post {
            if (isReleased) return@post
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        handler.post {
            if (isReleased) return@post
        }
    }

    override fun event(eventId: Int, data: MPVNode) {
        handler.post {
            if (isReleased) return@post
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> _eventFlow.tryEmit(Event.FileLoaded)
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    // AM (MEDIASESSION_EVENT_DRIVEN_FIX) -->
                    // mpv fires this on every playback discontinuity - overwhelmingly
                    // a user seek, also after unpausing in some cases. This was
                    // already being received (for an isExiting reset - removed, see
                    // ISEXITING_SPLIT_FIX below) but silently discarded otherwise -
                    // nothing downstream could react to a seek actually happening.
                    // PlaybackState's speed+timestamp lets the OS interpolate the seek
                    // bar during ordinary steady playback, but a seek is exactly the
                    // one case interpolation can't predict - the position just jumped.
                    // This is the signal to push a corrected position immediately
                    // instead of waiting for a periodic backstop to eventually catch
                    // up.
                    // <-- AM (MEDIASESSION_EVENT_DRIVEN_FIX)
                    // AM (ISEXITING_SPLIT_FIX) -->
                    // Used to also reset isExiting = false here - removed. Matched
                    // neither replacement field's meaning; see their declaration
                    // near the top of this class for why.
                    // <-- AM (ISEXITING_SPLIT_FIX)
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
        if (isReleased) return
        isReleased = true

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
        // AM (PERSISTENT_SURFACE_ARCHITECTURE) -->
        // The one place the persistent SurfaceTexture actually gets released -
        // release() is only ever called when the player itself is being
        // permanently torn down, not on ordinary backgrounding (which never
        // touches the surface at all - see persistentSurfaceTexture's own doc
        // comment).
        persistentSurfaceTexture?.release()
        persistentSurfaceTexture = null
        // <-- AM (PERSISTENT_SURFACE_ARCHITECTURE)
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
