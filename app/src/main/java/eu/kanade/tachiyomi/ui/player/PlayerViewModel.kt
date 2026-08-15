package eu.kanade.tachiyomi.ui.player

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import animiru.domain.player.interactor.TrackSelect
import animiru.domain.player.model.ArtType
import animiru.domain.player.model.BottomPlayerButton
import animiru.domain.player.model.CustomKeyCodes
import animiru.domain.player.model.PlayerOrientation
import animiru.domain.player.model.SetAsArt
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.model.VideoAspect
import animiru.domain.player.service.AudioPreferences
import animiru.domain.player.service.DecoderPreferences
import animiru.domain.player.service.GesturePreferences
import animiru.domain.player.service.PlayerPreferences
import animiru.domain.player.service.SubtitlePreferences
import animiru.feature.cast.CastProxyServerService
import aniyomi.core.common.torrent.TorrentPreferences
import aniyomi.core.common.torrent.TorrentServerApi
import aniyomi.core.common.torrent.TorrentServerUtils
import com.yubyf.truetypeparser.TTFFile
import dev.icerock.moko.resources.StringResource
import dev.vivvvek.seeker.Segment
import eu.kanade.domain.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connection.SyncPreferences
import eu.kanade.domain.episode.model.toDbEpisode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.TileInfo
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.connection.syncmiru.SyncDataJob
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.database.models.isRecognizedNumber
import eu.kanade.tachiyomi.data.database.models.toDomainEpisode
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.player.service.HttpServerService
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.torrent.service.TorrentServerService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.ui.anime.EpisodeShufflePreferences
import eu.kanade.tachiyomi.ui.anime.episodeShuffleSortKey
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.cast.CastDialog
import eu.kanade.tachiyomi.ui.player.cast.CastSheet
import eu.kanade.tachiyomi.ui.player.cast.CastUiData
import eu.kanade.tachiyomi.ui.player.components.HosterState
import eu.kanade.tachiyomi.ui.player.components.getChangedAt
import eu.kanade.tachiyomi.ui.player.controls.components.IndexedSegment
import eu.kanade.tachiyomi.ui.player.domain.AudioManager
import eu.kanade.tachiyomi.ui.player.domain.BrightnessManager
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.ui.player.mpv.ChapterNode
import eu.kanade.tachiyomi.ui.player.mpv.MPVPlayer
import eu.kanade.tachiyomi.ui.player.mpv.MpvVideoTrack
import eu.kanade.tachiyomi.ui.player.mpv.TrackNode
import eu.kanade.tachiyomi.ui.player.mpv.TrackState
import eu.kanade.tachiyomi.ui.player.utils.AniSkipApi
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils.Companion.getStringRes
import eu.kanade.tachiyomi.util.editBackground
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.editThumbnail
import eu.kanade.tachiyomi.util.episode.filterDownloaded
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.getWanIp
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.cast.CastEvent
import tachiyomi.cast.CastManager
import tachiyomi.cast.domain.TrackInformation
import tachiyomi.cast.domain.VideoInformation
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.custombutton.interactor.GetCustomButtons
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.service.getEpisodeSort
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.distinctBy
import kotlin.collections.orEmpty
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

class PlayerViewModel @JvmOverloads constructor(
    private val context: Application,
    private val savedState: SavedStateHandle,
    private val json: Json = Injekt.get(),

    private val getAnime: GetAnime = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),

    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    // AM (RECENT_EPISODE_POSITIONS) -->
    private val getEpisode: GetEpisode = Injekt.get(),
    // <-- AM (RECENT_EPISODE_POSITIONS)
    private val trackEpisode: TrackEpisode = Injekt.get(),
    private val setAnimeViewerFlags: SetAnimeViewerFlags = Injekt.get(),

    private val imageSaver: ImageSaver = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),

    private val torrentServerApi: TorrentServerApi = Injekt.get(),
    private val torrentServerUtils: TorrentServerUtils = Injekt.get(),
    private val torrentPreferences: TorrentPreferences = Injekt.get(),

    private val basePreferences: BasePreferences = Injekt.get(),
    private val episodeShufflePreferences: EpisodeShufflePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val playerPreferences: PlayerPreferences = Injekt.get(),
    private val decoderPreferences: DecoderPreferences = Injekt.get(),
    private val gesturePreferences: GesturePreferences = Injekt.get(),
    private val audioPreferences: AudioPreferences = Injekt.get(),
    private val subtitlePreferences: SubtitlePreferences = Injekt.get(),
    private val getCustomButtons: GetCustomButtons = Injekt.get(),
    private val trackSelect: TrackSelect = Injekt.get(),
    private val audioManager: AudioManager = Injekt.get(),
    private val brightnessManager: BrightnessManager = Injekt.get(),
    // AM (SYNC) -->
    private val syncPreferences: SyncPreferences = Injekt.get(),
    // <-- AM (SYNC)
    // AM --> (CAST)
    internal val castManager: CastManager = Injekt.get(),
    private val videoInformation: VideoInformation = Injekt.get(),
    // <-- AM (CAST)
) : AndroidViewModel(context) {
    val videoOutput = if (decoderPreferences.gpuNext.get()) "gpu-next" else "gpu"

    private var _player = MPVPlayer(context, videoOutput)
    val player get() = _player
    val mpv get() = _player.mpv

    // Prefs
    private val reduceMotion = playerPreferences.reduceMotion.get()
    private val playerTimeToDisappearMs = playerPreferences.playerTimeToDisappear.get()
    private val swapVolumeAndBrightness = gesturePreferences.swapVolumeBrightness.get()
    private val boostCap = audioPreferences.volumeBoostCap.get()
    private val displayVolumeAsPercentage = playerPreferences.displayVolPer.get()
    private val showLoadingCircle = playerPreferences.showLoadingCircle.get()
    private val invertDuration = playerPreferences.invertDuration.get()
    private val smoothSeeking = gesturePreferences.playerSmoothSeek.get()
    private val showChapterIndicator = playerPreferences.showCurrentChapter.get()
    private val enableCast = playerPreferences.enableCast.get()

    private val aniSkipEnabled = playerPreferences.aniSkipEnabled.get()
    private val disableAniSkipOnChapters = playerPreferences.disableAniSkipOnChapters.get()
    private val introSkipEnabled = playerPreferences.enableSkipIntro.get()
    private val autoSkip = playerPreferences.autoSkipIntro.get()
    private val netflixStyle = playerPreferences.enableNetflixStyleIntroSkip.get()
    private val defaultWaitingTime = playerPreferences.waitingTimeIntroSkip.get()
    private val leftDoubleTapGesture = gesturePreferences.leftDoubleTapGesture.get()
    private val centerDoubleTapGesture = gesturePreferences.centerDoubleTapGesture.get()
    private val rightDoubleTapGesture = gesturePreferences.rightDoubleTapGesture.get()
    // AM (MEDIA_CONTROLS) -->
    private val mediaPreviousGesture = gesturePreferences.mediaPreviousGesture.get()
    private val mediaPlayPauseGesture = gesturePreferences.mediaPlayPauseGesture.get()
    private val mediaNextGesture = gesturePreferences.mediaNextGesture.get()
    // <-- AM (MEDIA_CONTROLS)
    private val longPressGesture = gesturePreferences.longPressGesture.get()
    private val bottomPlayerButtons = gesturePreferences.bottomPlayerButtons.get()
    private val doubleTapToSeekDuration = gesturePreferences.skipLengthPreference.get()
    private val showSeekBar = gesturePreferences.showSeekBar.get()
    private val pipEpisodeToasts = playerPreferences.pipEpisodeToasts.get()
    private val showStatusBar = playerPreferences.showSystemStatusBar.get()
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileWatching.get()
    private val progress = playerPreferences.progressPreference.get()
    private val castProxy = playerPreferences.castProxy.get()
    private val castProxyPort = playerPreferences.castProxyPort.get().toInt()

    private val fontExtensionRegex = Regex($$""".*\.[ot]tf$""")
    private val maxVolume = audioManager.getMaxVolume()
    private val screenAspectRatio: Double by lazy {
        val metrics = context.resources.displayMetrics
        metrics.widthPixels.toDouble() / metrics.heightPixels.toDouble()
    }

    private val _stateData = MutableStateFlow(
        PlayerStateData(
            maxVolume = maxVolume,
        ),
    )
    val stateData = _stateData.asStateFlow()
    private val _uiData = MutableStateFlow(
        PlayerUiData(
            reduceMotion = reduceMotion,
            playerTimeToDisappearMs = playerTimeToDisappearMs,
            swapVolumeAndBrightness = swapVolumeAndBrightness,
            boostCap = boostCap,
            displayVolumeAsPercentage = displayVolumeAsPercentage,
            showLoadingCircle = showLoadingCircle,
            invertDuration = invertDuration,
            smoothSeeking = smoothSeeking,
            showChapterIndicator = showChapterIndicator,
            enableCast = enableCast,
            bottomPlayerButtons = bottomPlayerButtons,
            // AM (SYSTEM_BAR_SYNC) -->
            // controlsShown defaults to true, so statusBarShown must be seeded from the
            // preference here too - otherwise the system bar stays hidden on first open
            // until the next showControls()/hideControls() toggle syncs them.
            statusBarShown = showStatusBar,
            // <-- AM (SYSTEM_BAR_SYNC)
        ),
    )
    val uiData = _uiData.asStateFlow()
    private val _playbackData = MutableStateFlow(
        PlayerPlaybackData(
            currentVolume = if (playerPreferences.rememberPlayerVolume.get()) {
                playerPreferences.playerVolumeValue.get().takeUnless { it == -1 }
                    ?: audioManager.getVolume()
            } else {
                audioManager.getVolume()
            },
            currentBrightness = if (playerPreferences.rememberPlayerBrightness.get()) {
                playerPreferences.playerBrightnessValue.get().takeUnless { it == -1f }
                    ?: brightnessManager.getCurrentBrightness()
            } else {
                brightnessManager.getCurrentBrightness()
            },
        ),
    )
    val playbackData = _playbackData.asStateFlow()
    private val _castUiData = MutableStateFlow(
        CastUiData(
            invertDurationTimer = invertDuration,
            showChapterIndicator = showChapterIndicator,
        ),
    )
    val castUiData = _castUiData.asStateFlow()

    private val _aspectRatio = MutableStateFlow<Double?>(null)
    val aspectRatio = _aspectRatio.asStateFlow()

    private val _eventFlow = MutableSharedFlow<Event>()
    val eventFlow = _eventFlow.asSharedFlow()

    private var httpServer: HttpServer? = null
    private var timerJob: Job? = null
    private var getHosterVideoLinksJob: Job? = null
    private var episodeToDownload: Download? = null
    private var currentHosterList: List<Hoster>? = null
    private var thumbnailFetchJob: Job? = null
    private val thumbnailTileCache =
        object : LinkedHashMap<Int, Bitmap>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > 3
        }

    init {
        viewModelScope.launchIO {
            getCustomButtons.subscribeAll().collectLatest { buttons ->
                setupCustomButtons(buttons)
            }
        }

        viewModelScope.launchIO {
            subtitlePreferences.subtitleSystemFonts.changes().collectLatest { fonts ->
                updateUiData { it.copy(fontList = fetchFonts(fonts)) }
            }
        }
    }

    // AM (SERVICE_OWNED_PLAYER) -->
    // player/mpv are backed by a mutable field instead of a fixed val, so bindToService()
    // can swap this instance over to the Service's canonical player on the dedup path below -
    // without that, `player`/`mpv` would have stayed pointed at an orphaned, released instance
    // for this ViewModel's entire lifetime whenever a duplicate Activity instance got created
    // (the exact notification-relaunch bug this whole refactor exists to fix).
    private var _playerFlowsWired = false
    private val _playerReady = MutableStateFlow(false)
    val playerReady = _playerReady.asStateFlow()

    // AM (DUPLICATE_ACTIVITY_REINIT_FIX) -->
    // Kept so needsInit() (called from PlayerActivity.onNewIntent, well before this
    // synchronously-later bindToService call in a duplicate-instance reopen) can check
    // whether the Service already has a live session for the requested anime/episode -
    // see needsInit() below for why that matters.
    private var mediaHolder: PlayerMediaHolder? = null
    // <-- AM (DUPLICATE_ACTIVITY_REINIT_FIX)

    /** Called once PlayerActivity's Service connection delivers a live PlayerMediaHolder. */
    fun bindToService(holder: PlayerMediaHolder) {
        mediaHolder = holder
        val resolvedPlayer = holder.adopt(player)
        if (resolvedPlayer !== player) {
            // This instance built its own player, but the Service already had one
            // adopted from an earlier instance (e.g. a duplicate Activity spun up by
            // the notification's forced FLAG_ACTIVITY_NEW_TASK). Release our own
            // before it leaks, and switch this instance over to the canonical one -
            // wirePlayerFlows() below runs against whichever player we land on, so
            // nothing ends up wired to the one we're about to discard.
            val orphaned = player
            _player = resolvedPlayer
            orphaned.release()
            logcat(LogPriority.WARN) {
                "PlayerViewModel.bindToService: released orphaned player ($orphaned), " +
                    "switched to holder's canonical player ($resolvedPlayer)"
            }
        } else {
            logcat { "PlayerViewModel.bindToService: holder adopted this player ($player)" }
        }
        wirePlayerFlows()
        syncHolderSessionState()
        _playerReady.value = true
    }

    // AM (DUPLICATE_ACTIVITY_REINIT_FIX) -->
    // Publishes this instance's current anime/episode onto the holder so a LATER
    // duplicate instance's needsInit() call can recognize "this exact session is
    // already live" instead of reloading. Called both here (once the holder is known -
    // covers the case where binding finishes after the anime/episode are already set)
    // and from setupEpisode() (every episode switch, not just the initial one - without
    // that, needsInit() would keep matching whatever episode the session originally
    // started on, long after playback has moved past it).
    private fun syncHolderSessionState() {
        val anime = stateData.value.currentAnime ?: return
        val episode = stateData.value.currentEpisode ?: return
        mediaHolder?.updateState { it.copy(animeId = anime.id, episodeId = episode.id) }
    }
    // <-- AM (DUPLICATE_ACTIVITY_REINIT_FIX)

    /**
     * Wires every reactive subscription that reads from `player`/`mpv`. Deliberately NOT run
     * from init{} - it has to run after [bindToService] has resolved which player this
     * instance actually ends up using, so nothing gets wired up against a player that's
     * about to be discarded as an orphan. Idempotent: bindToService only ever calls this
     * once in practice, but a second call would otherwise double-subscribe everything.
     */
    private fun wirePlayerFlows() {
        if (_playerFlowsWired) return
        _playerFlowsWired = true

        viewModelScope.launch {
            player.eventFlow
                .onEach { handlePlayerFlow(it) }
                .launchIn(viewModelScope)

            castManager.castEvent
                .onEach { handleCastFlow(it) }
                .launchIn(viewModelScope)

            playerPreferences.autoplayEnabled.changes()
                .onEach { v -> updateUiData { it.copy(autoPlayEnabled = v) } }
                .launchIn(viewModelScope)

            playerPreferences.playerSpeed.changes()
                .onEach { v -> updateUiData { it.copy(playerSpeedPref = v) } }
                .launchIn(viewModelScope)

            combine(
                propFlow<Double>("video-params/aspect"),
                propFlow<Int>("video-params/rotate"),
            ) { aspect, rotation -> aspect to rotation }
                .onEach { (aspect, rotation) ->
                    _aspectRatio.update { _ ->
                        aspect?.let {
                            if (it < 0.001) return@update 0.0
                            if ((rotation ?: 0) % 180 == 90) 1.0 / it else it
                        }
                    }
                }
                .launchIn(viewModelScope)

            propFlow<Int>("video-params/w")
                .filterNotNull()
                .onEach { v -> updateStateData { it.copy(videoWidth = v) } }
                .launchIn(viewModelScope)

            propFlow<Int>("video-params/h")
                .filterNotNull()
                .onEach { v -> updateStateData { it.copy(videoHeight = v) } }
                .launchIn(viewModelScope)

            propFlow<MPVNode>("track-list")
                .filterNotNull()
                .onEach { onTrackListChanged(it) }
                .launchIn(viewModelScope)

            propFlow<MPVNode>("chapter-list")
                .filterNotNull()
                .onEach { onChapterListChanged(it) }
                .launchIn(viewModelScope)

            propFlow<Int>("chapter")
                .onEach { onChapterChanged(it) }
                .launchIn(viewModelScope)

            propFlow<Int>("duration")
                .filterNotNull()
                .onEach { v ->
                    updatePlaybackData { it.copy(duration = v) }
                }
                .launchIn(viewModelScope)

            propFlow<Int>("time-pos")
                .filterNotNull()
                .onEach { onSecondReached(it) }
                .launchIn(viewModelScope)

            propFlow<Boolean>("pause")
                .filterNotNull()
                .onEach { v ->
                    updatePlaybackData { it.copy(paused = v) }
                }
                .launchIn(viewModelScope)

            propFlow<Int>("volume-max")
                .filterNotNull()
                .onEach { v ->
                    updateStateData { it.copy(volumeBoostCap = v) }
                }
                .launchIn(viewModelScope)

            propFlow<MPVNode>("sid")
                .onEach { onSubtitleTrackSelectChange() }
                .launchIn(viewModelScope)

            propFlow<MPVNode>("secondary-sid")
                .onEach { onSubtitleTrackSelectChange() }
                .launchIn(viewModelScope)

            propFlow<MPVNode>("aid")
                .onEach { onAudioTrackSelectChange() }
                .launchIn(viewModelScope)

            propFlow<Long>("user-data/current-anime/intro-length")
                .filterNotNull()
                .onEach { setAnimeSkipIntroLength(it) }
                .launchIn(viewModelScope)
        }
    }
    // <-- AM (SERVICE_OWNED_PLAYER)

    fun isPlayerExiting(): Boolean {
        return player.isExiting
    }

    fun setPlayerExiting(value: Boolean) {
        player.isExiting = value
    }

    private fun updateStateData(update: (PlayerStateData) -> PlayerStateData) {
        _stateData.update { update(it) }
    }

    private fun updateUiData(update: (PlayerUiData) -> PlayerUiData) {
        _uiData.update { update(it) }
    }

    private fun updatePlaybackData(update: (PlayerPlaybackData) -> PlayerPlaybackData) {
        _playbackData.update { update(it) }
    }

    private fun updateCastUiData(update: (CastUiData) -> CastUiData) {
        _castUiData.update { update(it) }
    }

    inline fun <reified T> propFlow(name: String): StateFlow<T?> {
        return mpv.propFlow<T>(name)
    }

    fun setPropertyBoolean(property: String, value: Boolean) {
        mpv.setPropertyBoolean(property, value)
    }

    fun setPropertyInt(property: String, value: Int) {
        mpv.setPropertyInt(property, value)
    }

    fun setPropertyFloat(property: String, value: Float) {
        mpv.setPropertyFloat(property, value)
    }

    fun setPropertyDouble(property: String, value: Double) {
        mpv.setPropertyDouble(property, value)
    }

    fun setPropertyString(property: String, value: String) {
        mpv.setPropertyString(property, value)
    }

    fun setPropertyNode(property: String, value: MPVNode) {
        mpv.setPropertyNode(property, value)
    }

    fun mpvCommand(vararg command: String) {
        mpv.command(*command)
    }

    // AM (AUDIO_BLIP_FIX) -->
    // Flips true/false by MpvSurface's attach/detach callbacks (via PlayerScreen.kt).
    // Defaults true since a surface is normally attached when playback starts.
    var isSurfaceAttached: Boolean = true

    /**
     * loadfile, but disables hwdec first if there's currently no attached Surface -
     * MediaCodec hwdec needs one to initialize a decoder session, so loading while
     * backgrounded would otherwise fail. Previously this was handled by
     * unconditionally disabling hwdec on every surface detach (MpvSurface.kt's
     * surfaceDestroyed), which caused an audible blip on every single background
     * transition, not just the (much rarer) case of loading a new episode while
     * already backgrounded. Doing it here, right before the one operation that
     * actually needs it, means the common "just keep playing the same episode in
     * the background" case never touches hwdec at all.
     */
    private fun loadFile(url: String, options: String) {
        if (!isSurfaceAttached) {
            mpv.setOptionString("hwdec", "no")
        }
        mpvCommand("loadfile", url, "replace", "0", options)
    }
    // <-- AM (AUDIO_BLIP_FIX)

    fun handlePlayerEvent(event: PlayerEvent) {
        when (event) {
            PlayerEvent.ChangeAspect -> {
                cycleAspectRatio()
            }
            is PlayerEvent.ChangeSpeed -> {
                setSpeed(event.value)
            }
            PlayerEvent.CycleRotation -> {
                cycleRotations()
            }
            PlayerEvent.EnterPip -> {
                viewModelScope.launch {
                    _eventFlow.emit(Event.EnterPip)
                }
            }
            is PlayerEvent.ExecuteCustomButton -> {
                uiData.value.primaryButton?.let {
                    if (event.long) {
                        executeLongPressButton(it)
                    } else {
                        executeButton(it)
                    }
                }
            }
            is PlayerEvent.LockControls -> {
                updateUiData { it.copy(isControlsLocked = event.lock) }
            }
            is PlayerEvent.NextEpisode -> {
                // AM (MEDIA_CONTROLS) -->
                if (event.next) handleMediaNext() else handleMediaPrevious()
                // <-- AM (MEDIA_CONTROLS)
            }
            PlayerEvent.PlayPause -> {
                // AM (MEDIA_CONTROLS) -->
                handleMediaPlayPause()
                // <-- AM (MEDIA_CONTROLS)
            }
            is PlayerEvent.Seek -> {
                updateSeekPos(event.position.toFloat())
            }
            is PlayerEvent.SeekFinished -> {
                updatePlaybackData { it.copy(isSeeking = false) }
                seekTo(event.position)
            }
            is PlayerEvent.SetAutoPlay -> {
                setAutoPlay(event.value)
            }
            is PlayerEvent.SetPanel -> {
                setPanel(event.panel)
            }
            is PlayerEvent.SetSheet -> {
                setSheet(event.sheet)
            }
            is PlayerEvent.ShowBrightnessSlider -> {
                displayBrightnessSlider(event.show)
            }
            PlayerEvent.ShowEpisodeDialog -> {
                updateUiData { it.copy(dialogShown = Dialogs.EpisodeList) }
            }
            is PlayerEvent.ShowPlayerUpdate -> {
                updateUiData { it.copy(playerUpdate = event.update) }
            }
            is PlayerEvent.ShowVolumeSlider -> {
                displayVolumeSlider(event.show)
            }
            PlayerEvent.SkipIntro -> {
                onSkipIntro()
            }
            PlayerEvent.ToggleDurationTimer -> {
                toggleDurationTimer()
            }
        }
    }

    fun handlePlayerFlow(event: MPVPlayer.Event) {
        when (event) {
            is MPVPlayer.Event.EOF -> eofReached(event.value)
            is MPVPlayer.Event.EndFile -> endFile(event.node)
            MPVPlayer.Event.FileLoaded -> fileLoaded()
            is MPVPlayer.Event.LuaEvent -> handleLuaInvocation(event.property, event.value)
            is MPVPlayer.Event.TrackLoadFailure -> onTrackLoadedFailure(event.url)
        }
    }

    fun handleCastFlow(event: CastEvent) {
        val castState = castManager.castState.value
        when (event) {
            CastEvent.ConnectionError -> {
                updateStateData {
                    it.copy(
                        isCasting = false,
                        isLoadingCasting = false,
                        isErrorCasting = true,
                    )
                }
                stopHttpServerService()
            }
            CastEvent.ConnectionStart -> {
                updateStateData {
                    it.copy(
                        isCasting = false,
                        isLoadingCasting = true,
                        isErrorCasting = false,
                    )
                }
            }
            CastEvent.Connected -> {
                updateStateData {
                    it.copy(
                        isCasting = castState.isConnected,
                        isLoadingCasting = false,
                        isErrorCasting = false,
                    )
                }

                if (castState.isConnected && !castState.hasLoadedVideo) {
                    val position = playbackData.value.position.toLong()
                    startCasting(startPosition = position)
                }
            }
            is CastEvent.Disconnected -> {
                updateStateData {
                    it.copy(
                        isCasting = false,
                        isLoadingCasting = false,
                        isErrorCasting = false,
                    )
                }

                context.stopService(Intent(context, CastProxyServerService::class.java))
                stopHttpServerService()
            }
            is CastEvent.NextEpisode -> {
                nextEpisode(next = event.next)
            }
            is CastEvent.OnSecondReached -> {
                onSecondReached(position = event.position, isCasting = true)
            }
            is CastEvent.PlaybackError -> {
            }
            is CastEvent.TrackLoadResult -> {
                trackLoaded(event.trackId, event.success, event.isAudio)
            }
            CastEvent.Ready -> {
                updateCastUiData { it.copy(isLoadingEpisode = false) }
            }
            CastEvent.LoadingFailed -> {
                viewModelScope.launch {
                    _eventFlow.emit(Event.ToastResource(AMMR.strings.cast_server_load_failed))
                }
            }
        }
    }

    // === Setup ===

    /** Restored on process kill. */
    private var episodePosition = savedState.get<Long>("episode_position")
        set(value) {
            savedState["episode_position"] = value
            field = value
        }

    // AM (RECENT_EPISODE_POSITIONS) -->

    /**
     * Session-local temp-position cache for recently-departed episodes, so an accidental
     * next/previous click doesn't lose your place - even for an already-seen episode we
     * otherwise don't persist rewatch position for. Doesn't cover the currently-open
     * episode; that's already handled by the normal live-tracking/resume path.
     *
     * Keyed by episode id; each entry also records the playlist index it was saved at, so
     * entries are pruned by distance from wherever you're currently navigating rather than
     * by a raw insertion count - otherwise a forward-forward-back-back click pattern would
     * evict an entry before you ever get back to it.
     */
    private data class RecentPosition(val positionMs: Long, val playlistIndex: Int)
    private val recentEpisodePositions = mutableMapOf<Long, RecentPosition>()

    private fun rememberRecentEpisodePosition() {
        val episode = stateData.value.currentEpisode ?: return
        val id = episode.id ?: return
        val positionMs = (episodePosition ?: 0L) * 1000L
        if (positionMs <= 0L) return

        // AM (RECENT_POSITION_FINISHED_CLEAR) -->
        // A finished episode (position at or past its final second) shouldn't get cached
        // as a resume point - that would just replay the last second forever on return.
        // This is intentionally independent of the "mark as watched after %" setting;
        // clear strictly at the last second regardless of where that threshold sits.
        val durationMs = playbackData.value.duration.toLong() * 1000L
        if (durationMs > 0L && positionMs >= durationMs) {
            recentEpisodePositions.remove(id)
            return
        }
        // <-- AM (RECENT_POSITION_FINISHED_CLEAR)

        recentEpisodePositions[id] = RecentPosition(positionMs, stateData.value.currentPlaylistIndex)
    }

    private fun pruneRecentEpisodePositions(aroundPlaylistIndex: Int) {
        val maxSlots = playerPreferences.recentEpisodePositionSlots.get()
        recentEpisodePositions.entries.removeAll { (_, saved) ->
            kotlin.math.abs(saved.playlistIndex - aroundPlaylistIndex) > maxSlots
        }
    }
    // <-- AM (RECENT_EPISODE_POSITIONS)

    /** Restored on process kill. */
    private var qualityIndex = savedState.get<Pair<Int, Int>>("quality_index") ?: Pair(-1, -1)
        set(value) {
            savedState["quality_index"] = value
            field = value
        }

    /** Restored on process kill. */
    private var episodeId = savedState.get<Long>("episode_id") ?: -1L
        set(value) {
            savedState["episode_id"] = value
            field = value
        }

    fun fetchFonts(includeSystemFonts: Boolean): List<String> {
        val fontFiles = mutableListOf<String>()

        storageManager.getFontsDirectory()?.listFiles()?.filter { file ->
            file.name?.lowercase()?.matches(fontExtensionRegex) == true
        }?.mapNotNull {
            try {
                TTFFile.open(it.openInputStream()).families.values.first()
            } catch (_: Exception) {
                null
            }
        }?.let {
            fontFiles.addAll(it)
        }

        if (!includeSystemFonts) {
            return fontFiles.distinct()
        }

        val fontDirectories = listOf(
            "/system/fonts/",
            "/product/fonts/",
        )

        for (directory in fontDirectories) {
            val dir = File(directory)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()
                files?.filter { file ->
                    file.isFile && file.name.lowercase().matches(fontExtensionRegex)
                }?.forEach { file ->
                    try {
                        fontFiles.add(
                            TTFFile.open(file.inputStream()).families.values.first(),
                        )
                    } catch (_: Exception) { }
                }
            }
        }

        return fontFiles.distinct()
    }

    // === Initialize ===

    fun updateIsLoadingHosters(value: Boolean) {
        updateUiData { it.copy(isLoadingHosters = value) }
    }

    fun updateIsLoadingEpisode(value: Boolean) {
        updateUiData { it.copy(isLoadingEpisode = value) }
    }

    /**
     * Set when re-initializing after reopening from our own background-playback
     * notification - resumes wherever playback actually was, bypassing the normal
     * "already watched -> start at 0" rule. Consumed and reset by loadVideo().
     */
    var forceResumeFromLastPosition = false

    fun needsInit(animeId: Long, episodeId: Long): Boolean {
        val local = stateData.value
        if (local.currentAnime?.id == animeId && local.currentEpisode?.id == episodeId) {
            return false
        }
        // AM (DUPLICATE_ACTIVITY_REINIT_FIX) -->
        // This instance has no local state for the requested anime/episode - but if the
        // Service's holder already reports a live session for that exact pair, this is a
        // duplicate PlayerActivity/ViewModel reopened from the notification (its forced
        // FLAG_ACTIVITY_NEW_TASK spins up a fresh instance - see bindToService()), not a
        // genuinely new one. Without this, needsInit() returns true, init() reloads the
        // episode from scratch, and - if it's already marked seen - the "already watched,
        // start at 0" rule in setVideo() restarts playback from the beginning even though
        // the actual (canonical, still-adopted) player was mid-episode the whole time.
        val holderState = mediaHolder?.state?.value
        if (holderState?.animeId == animeId && holderState.episodeId == episodeId) {
            return false
        }
        // <-- AM (DUPLICATE_ACTIVITY_REINIT_FIX)
        return true
    }

    data class InitResult(
        val hosterList: List<Hoster>?,
        val videoIndex: Pair<Int, Int>,
        val position: Long?,
    )

    class ExceptionWithStringResource(
        message: String,
        val stringResource: StringResource,
    ) : Exception(message)

    suspend fun init(
        animeId: Long,
        initialEpisodeId: Long,
        hostList: String,
        hostIndex: Int,
        vidIndex: Int,
    ): Pair<InitResult, Result<Boolean>> {
        val defaultResult = InitResult(currentHosterList, qualityIndex, null)
        if (!needsInit(animeId, initialEpisodeId)) return Pair(defaultResult, Result.success(true))

        // AM (NEW_SESSION_AUTOPLAY_FIX) -->
        // This is a genuinely new anime/episode (needsInit passed), not a same-episode
        // hoster/quality switch - so, like changeEpisode(), it should always play once
        // loaded rather than inheriting previousPauseState from whatever was left over
        // by the PREVIOUS unrelated episode's checkFileLoaded()/loadVideo() cycle.
        // Without this, pausing the current episode and then opening a different one
        // (same PlayerViewModel/Activity instance, singleTask) carries that paused=true
        // into loadVideo()'s `it.previousPauseState ?: playbackData.value.paused` capture,
        // so the new video loads and then immediately re-pauses itself.
        updateUiData { it.copy(previousPauseState = false) }
        // <-- AM (NEW_SESSION_AUTOPLAY_FIX)

        return try {
            getAnime.await(animeId)?.let { anime ->
                sourceManager.isInitialized.first { it }
                val source = sourceManager.getOrStub(anime.source)
                val incognito = getIncognitoState.await(anime.source)

                updateStateData { it.copy(currentAnime = anime, currentSource = source, incognitoMode = incognito) }
                updateUiData { it.copy(animeTitle = anime.title) }
                episodeId = initialEpisodeId

                setupTrackers(anime.id)
                setupEpisodeList(anime)

                val episode = stateData.value.currentPlaylist.firstOrNull { it.id == episodeId }
                    ?: throw ExceptionWithStringResource("No episode loaded", AYMR.strings.no_episode_loaded)
                setupEpisode(episode)

                val skipIntroLength = getAnimeSkipIntroLength()
                updateCastUiData { it.copy(skipIntroLength = skipIntroLength.toLong()) }

                // Write to mpv table
                val parentTitle = anime.parentId?.let { getAnime.await(it)?.title } ?: ""
                setPropertyString("user-data/current-anime/anime-title", anime.title)
                setPropertyString("user-data/current-anime/parent-title", parentTitle)
                setPropertyInt("user-data/current-anime/intro-length", skipIntroLength)
                setPropertyString(
                    "user-data/current-anime/category",
                    getCategories.await(anime.id).joinToString {
                        it.name
                    },
                )

                // Load hosters
                if (hostList.isNotBlank()) {
                    currentHosterList = hostList.toHosterList().ifEmpty {
                        currentHosterList = null
                        throw ExceptionWithStringResource(
                            "Hoster selected from empty list",
                            AYMR.strings.select_hoster_from_empty_list,
                        )
                    }
                    qualityIndex = Pair(hostIndex, vidIndex)
                } else {
                    EpisodeLoader.getHosters(episode.toDomainEpisode()!!, anime, source)
                        .takeIf { it.isNotEmpty() }
                        ?.also { currentHosterList = it }
                        ?: run {
                            currentHosterList = null
                            throw ExceptionWithStringResource("Hoster list is empty", AYMR.strings.no_hosters)
                        }
                }

                val result = InitResult(
                    hosterList = currentHosterList,
                    videoIndex = qualityIndex,
                    position = episodePosition,
                )

                Pair(result, Result.success(true))
            } ?: Pair(defaultResult, Result.success(false)) // Unlikely but okay
        } catch (e: Throwable) {
            Pair(defaultResult, Result.failure(e))
        }
    }

    private fun setupCustomButtons(buttons: List<CustomButton>) {
        val primaryButton = buttons.firstOrNull { it.isFavorite }

        updateUiData {
            it.copy(
                customButtons = buttons,
                primaryButton = primaryButton ?: it.primaryButton,
                primaryButtonTitle = if (it.primaryButtonTitle.isEmpty() && primaryButton != null) {
                    primaryButton.name
                } else {
                    it.primaryButtonTitle
                },
            )
        }
    }

    private suspend fun setupTrackers(animeId: Long) {
        val tracks = getTracks.await(animeId)
        updateStateData { it.copy(hasTrackers = tracks.isNotEmpty()) }
    }

    private suspend fun setupEpisodeList(anime: Anime) {
        val episodes = getEpisodesByAnimeId.await(anime.id)
            .sortedWith(getEpisodeSort(anime, sortDescending = false))
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(anime)
                } else {
                    this
                }
            }
            .map { it.toDbEpisode() }
            .let { sorted ->
                // Mirrors the episode list screen's shuffle via the same persisted
                // per-anime seed (EpisodeShufflePreferences), no explicit wiring needed.
                val seed = episodeShufflePreferences.seed(anime.id).get()
                if (seed == 0L) {
                    sorted
                } else {
                    sorted.sortedBy { episodeShuffleSortKey(seed, it.id ?: 0L) }
                }
            }

        val selectedEpisode = episodes.find { it.id == episodeId }
            ?: error("Requested episode of id $episodeId not found in episode list")

        val filtered = episodes.filterNot {
            (anime.unseenFilterRaw == Anime.EPISODE_SHOW_SEEN && !it.seen) ||
                (anime.unseenFilterRaw == Anime.EPISODE_SHOW_UNSEEN && it.seen) ||
                (
                    anime.downloadedFilterRaw == Anime.EPISODE_SHOW_DOWNLOADED &&
                        !downloadManager.isEpisodeDownloaded(
                            it.name,
                            it.scanlator,
                            it.url,
                            // AM (CUSTOM_INFORMATION) -->
                            anime.ogTitle,
                            // <-- AM (CUSTOM_INFORMATION)
                            anime.source,
                        )
                    ) ||
                (
                    anime.downloadedFilterRaw == Anime.EPISODE_SHOW_NOT_DOWNLOADED &&
                        downloadManager.isEpisodeDownloaded(
                            it.name,
                            it.scanlator,
                            it.url,
                            // AM (CUSTOM_INFORMATION) -->
                            anime.ogTitle,
                            // <-- AM (CUSTOM_INFORMATION)
                            anime.source,
                        )
                    ) ||
                (
                    anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_BOOKMARKED &&
                        !it.bookmark
                    ) ||
                (
                    anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_NOT_BOOKMARKED &&
                        it.bookmark
                    ) ||
                (
                    anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_FILLERMARKED &&
                        !it.fillermark
                    ) ||
                (
                    anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_NOT_FILLERMARKED &&
                        it.fillermark
                    )
        }.toMutableList()

        if (filtered.all { it.id != episodeId }) {
            filtered += listOf(selectedEpisode)
        }

        updateStateData { it.copy(currentPlaylist = filtered.toList()) }
    }

    private fun isEpisodeOnline(episode: Episode): Boolean? {
        val currentState = stateData.value

        val anime = currentState.currentAnime ?: return null
        val source = currentState.currentSource ?: return null
        return source is AnimeHttpSource &&
            !EpisodeLoader.isDownload(
                episode.toDomainEpisode()!!,
                anime,
            )
    }

    private fun setupEpisode(episode: Episode) {
        val currentState = stateData.value

        val currentEpisodeIndex = currentState.currentPlaylist.indexOfFirst {
            episode.id == it.id
        }

        updateStateData {
            it.copy(
                currentEpisode = episode,
                currentPlaylistIndex = currentEpisodeIndex,
                isEpisodeOnline = isEpisodeOnline(episode) == true,
                hasPreviousEpisode = currentEpisodeIndex != 0,
                hasNextEpisode = currentEpisodeIndex != currentState.currentPlaylist.size - 1,
            )
        }

        updateUiData {
            it.copy(mediaTitle = episode.name)
        }

        setPropertyDouble("user-data/current-anime/episode-number", episode.episode_number.toDouble())

        // AM (DUPLICATE_ACTIVITY_REINIT_FIX) -->
        // setupEpisode() is the single choke point for every episode switch (both the
        // initial init() load and every later changeEpisode()) - without re-syncing here,
        // the holder's episodeId stays pinned to whatever episode the session originally
        // started on. needsInit() then wrongly treats a later request for THAT original
        // episode as "already live" and no-ops, leaving whatever's actually playing
        // untouched instead of switching to it.
        syncHolderSessionState()
        // <-- AM (DUPLICATE_ACTIVITY_REINIT_FIX)
    }

    fun setupPlayerOrientation() {
        if (player.isExiting) return
        val orientation = when (playerPreferences.defaultPlayerOrientationType.get()) {
            PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayerOrientation.Video -> if ((aspectRatio.value ?: 0.0) > 1.0) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        updatePlaybackData { it.copy(currentOrientation = orientation) }
    }

    private fun cycleRotations() {
        val orientation = when (playbackData.value.currentOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            -> {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            else -> {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        updatePlaybackData { it.copy(currentOrientation = orientation) }
    }

    // === Casting ===

    private fun stopHttpServerService() {
        context.stopService(Intent(context, HttpServerService::class.java))
    }

    private fun getProxyUrl(address: String, url: String, headers: Headers, isTorrent: Boolean): String {
        if (isTorrent) return url
        if (url.toHttpUrlOrNull()?.host?.startsWith(address) == true) return url

        return "http://$address:$castProxyPort".toHttpUrl().newBuilder().apply {
            addPathSegment("proxy")
            addQueryParameter("url", url)
            addQueryParameter("header", json.encodeToString(headers.toMap()))
        }.build().toString()
    }

    private fun getLocalUrl(address: String, url: String): String {
        return "http://$address:$castProxyPort".toHttpUrl().newBuilder().apply {
            addPathSegment("local")
            addQueryParameter("url", url)
        }.build().toString()
    }

    private fun String.setToLocal(): String {
        return toHttpUrl().newBuilder().apply {
            host("localhost")
            port(1)
        }.build().toString()
    }

    private fun startCasting(startPosition: Long = 0) {
        var video = stateData.value.currentVideo ?: return
        val source = stateData.value.currentSource ?: return
        val anime = stateData.value.currentAnime ?: return
        val episode = stateData.value.currentEpisode ?: return

        if (!player.isExiting) {
            mpvCommand("stop")
        }

        pause()
        updatePlaybackData {
            it.copy(currentOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
        updateStateData {
            it.copy(
                hasLoadedTracks = false,
                hasLoadedSubs = false,
                hasLoadedAudio = false,
            )
        }

        viewModelScope.launch {
            val address = context.getWanIp() ?: "127.0.0.1"
            val isTorrent = torrentPreferences.torrServerEnable.get() && isTorrent(video)

            // If the video already requires a http server, we need to relaunch it with the service.
            if (httpServer != null) {
                stopHttpServer()
                val (success, port) = MainActivity.startHttpServerService(context, source.id)
                if (!success) {
                    _eventFlow.emit(Event.ToastResource(AYMR.strings.http_server_start_failure))
                    return@launch
                }

                video = video
                    .copy(
                        videoUrl = video.videoUrl.setToLocal(),
                        subtitleTracks = video.subtitleTracks.map {
                            it.copy(url = it.url.setToLocal())
                        },
                        audioTracks = video.audioTracks.map {
                            it.copy(url = it.url.setToLocal())
                        },
                    )
                    .copyHttpServer(port)
            }

            if (isTorrent) {
                video = withIOContext {
                    TorrentServerService.start()
                    val videoUrl = getTorrentUrl(video.videoUrl, video.videoTitle).toHttpUrl().newBuilder()
                        .host(address)
                        .build()
                        .toString()
                    video.copy(
                        videoUrl = videoUrl,
                    )
                }
            } else if (stateData.value.isEpisodeOnline && castProxy) {
                context.startService(
                    Intent(context, CastProxyServerService::class.java)
                        .putExtra(CastProxyServerService.EXTRA_ADDRESS, address),
                )

                val isReady = withTimeoutOrNull(5.seconds) {
                    CastProxyServerService.isRunning.first { it }
                }

                if (isReady != true) {
                    _eventFlow.emit(Event.ToastResource(AMMR.strings.cast_server_start_failed))
                    stopCasting()
                    return@launch
                }
            }

            val headers = video.headers
                ?: (source as? AnimeHttpSource)?.headers
                ?: Headers.EMPTY

            val codecInformation = withContext(Dispatchers.IO) {
                videoInformation.getVideoInformation(
                    videoUrl = video.videoUrl,
                    headers = headers,
                )
            }

            val videoHeaders = video.headers ?: Headers.EMPTY

            val video = if (!stateData.value.isEpisodeOnline) {
                video.copy(
                    videoUrl = getLocalUrl(address, video.videoUrl),
                )
            } else if (castProxy) {
                video.copy(
                    videoUrl = getProxyUrl(address, video.videoUrl, videoHeaders, isTorrent),
                    subtitleTracks = video.subtitleTracks.map {
                        it.copy(url = getProxyUrl(address, it.url, videoHeaders, isTorrent))
                    },
                    audioTracks = video.audioTracks.map {
                        it.copy(url = getProxyUrl(address, it.url, videoHeaders, isTorrent))
                    },
                )
            } else {
                video
            }

            val maxIndex = codecInformation.tracks.maxByOrNull { it.index }?.index?.plus(1) ?: 1
            val externalSubtitleTracks = video.subtitleTracks.mapIndexed { index, track ->
                TrackInformation(
                    index = maxIndex + index,
                    type = "subtitle",
                    contentType = videoInformation.getSubtitleContentType(track.url),
                    title = track.lang,
                    language = "und",
                    contentId = track.url,
                )
            }
            val externalAudioTracks = video.audioTracks.mapIndexed { index, track ->
                TrackInformation(
                    index = maxIndex + externalSubtitleTracks.size + index,
                    type = "audio",
                    contentType = videoInformation.getAudioContentType(track.url),
                    title = track.lang,
                    language = "und",
                    contentId = track.url,
                )
            }
            val subtitleTracks = codecInformation.tracks.filter { it.type == "subtitle" } + externalSubtitleTracks
            val audioTracks = codecInformation.tracks.filter { it.type == "audio" } + externalAudioTracks
            val preferredSubtitle = trackSelect.getPreferredTrackIndex(subtitleTracks, subtitle = true)
            val preferredAudio = trackSelect.getPreferredTrackIndex(audioTracks, subtitle = false)

            val chapters = ChapterUtils.mergeChapters(
                currentChapters = codecInformation.chapters.sortedBy { it.startTime }.map {
                    IndexedSegment(
                        name = it.name,
                        start = it.startTime.toFloat(),
                    )
                },
                stamps = video.timestamps + stateData.value.aniskipChapters,
                codecInformation.duration?.toInt(),
            ).map { it.toSegment() }

            updateCastUiData {
                it.copy(
                    duration = codecInformation.duration?.toLong() ?: 0L,
                    subTracks = subtitleTracks,
                    audioTracks = audioTracks,
                    currentSubId = preferredSubtitle?.index ?: -1L,
                    currentAudioId = preferredAudio?.index ?: -1L,
                    chapters = chapters,
                )
            }

            loadAniSkip(codecInformation.chapters.size, codecInformation.duration?.toInt())

            castManager.stopRemoteMediaClient()
            castManager.startCasting(
                video = video,
                videoInformation = codecInformation,
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
                subtitleId = preferredSubtitle?.index,
                audioId = preferredAudio?.index,
                anime = anime,
                episodeTitle = episode.name,
                startPosition = startPosition,
                playbackRate = mpv.getPropertyDouble("speed") ?: 1.0,
            )
        }
    }

    fun stopCasting() {
        castManager.disconnect()
        updateStateData { it.copy(isCasting = false) }
        updateUiData { it.copy(isLoadingEpisode = true) }
        val video = stateData.value.currentVideo
        setVideo(video)
        unpause()
    }

    fun setCastSheet(sheet: CastSheet) {
        updateCastUiData { it.copy(sheetShown = sheet) }
        if (sheet == CastSheet.None) {
            resetDismissSheet()
        }
    }

    fun setCastDialog(dialog: CastDialog) {
        updateCastUiData { it.copy(dialogShown = dialog) }
    }

    fun selectTrack(track: TrackInformation, isAudio: Boolean) {
        if (isAudio) {
            updateCastUiData {
                val index = it.audioTracks.indexOfFirst { t -> track.index == t.index }
                it.copy(
                    audioTracks = it.audioTracks.toMutableList().apply {
                        this[index] = this[index].copy(loading = true, error = false)
                    }.toList(),
                )
            }
        } else {
            updateCastUiData {
                val index = it.subTracks.indexOfFirst { t -> track.index == t.index }
                it.copy(
                    subTracks = it.subTracks.toMutableList().apply {
                        this[index] = this[index].copy(loading = true, error = false)
                    }.toList(),
                )
            }
        }

        castManager.loadTrack(track.index, isAudio)
    }

    private fun trackLoaded(id: Long, success: Boolean, isAudio: Boolean) {
        if (isAudio) {
            updateCastUiData {
                val index = it.audioTracks.indexOfFirst { t -> id == t.index }
                it.copy(
                    audioTracks = it.audioTracks.toMutableList().apply {
                        this[index] = this[index].copy(loading = false, error = !success)
                    }.toList(),
                    currentAudioId = if (success) id else castManager.castState.value.lastLoadedAudioId,
                )
            }
        } else {
            updateCastUiData {
                val index = it.subTracks.indexOfFirst { t -> id == t.index }
                it.copy(
                    subTracks = it.subTracks.toMutableList().apply {
                        this[index] = this[index].copy(loading = false, error = !success)
                    }.toList(),
                    currentSubId = if (success) id else castManager.castState.value.lastLoadedSubId,
                )
            }
        }
    }

    fun castSetSkipIntroLength(value: Long) {
        updateCastUiData { it.copy(skipIntroLength = value) }
        setAnimeSkipIntroLength(value)
    }

    fun castOnSeekIntro() {
        castManager.seekBy(castUiData.value.skipIntroLength)
    }

    fun castSeekTo(value: Long) {
        castManager.seekTo(value)
    }

    fun castStartSeek(position: Float) {
        updateCastUiData {
            it.copy(
                seekPosition = position,
                isSeeking = true,
            )
        }
    }

    fun castEndSeek() {
        castManager.seekTo(castUiData.value.seekPosition.toLong())
        updateCastUiData {
            it.copy(isSeeking = false)
        }
    }

    private fun castOnChapterChanged(chapter: Segment?) {
        updateCastUiData { it.copy(currentChapter = chapter) }
        if (chapter == null) {
            updateCastUiData {
                it.copy(
                    skipIntroText = null,
                    netflixTimeout = null,
                )
            }
            return
        }

        val chapterType = chapter.getChapterType()
        if (chapterType == ChapterType.Other) {
            updateCastUiData {
                it.copy(
                    skipIntroText = null,
                    netflixTimeout = null,
                )
            }
        } else {
            if (netflixStyle) {
                // show a toast with the seconds before the skip
                viewModelScope.launch {
                    _eventFlow.emit(
                        Event.ToastString(
                            "Skip Intro: ${context.stringResource(
                                AYMR.strings.player_aniskip_dontskip_toast,
                                chapter.name.substringBeforeLast(ChapterUtils.ANIYOMI_CHAPTER_IDENTIFIER),
                                defaultWaitingTime,
                            )}",
                        ),
                    )
                }
                updateCastUiData {
                    it.copy(
                        skipIntroText = context.stringResource(AYMR.strings.player_aniskip_dontskip),
                        netflixTimeout = defaultWaitingTime,
                    )
                }
            } else if (autoSkip) {
                castSkipIntro(chapter)
            } else {
                updateSkipIntroButton(chapterType)
            }
        }
    }

    private fun Segment.getChapterType(): ChapterType {
        return name.substringAfterLast(
            delimiter = ChapterUtils.ANIYOMI_CHAPTER_IDENTIFIER,
            missingDelimiterValue = ChapterType.Other.ordinal.toString(),
        ).toInt().let { ChapterType.entries[it] }
    }

    fun castOnSkipIntro() {
        val chapter = castUiData.value.currentChapter ?: return
        if ((castUiData.value.netflixTimeout ?: 0) > 0 && netflixStyle) {
            updateCastUiData { it.copy(netflixTimeout = null) }
            updateSkipIntroButton(chapter.getChapterType())
            return
        }

        updateCastUiData { it.copy(netflixTimeout = null) }
        castSkipIntro(chapter)
    }

    private fun castSkipIntro(chapter: Segment) {
        val nextChapterStart = castUiData.value.chapters.filter { it.start > chapter.start }
            .minByOrNull { it.start }?.start?.toLong()
            ?: castUiData.value.duration
        castManager.seekTo(nextChapterStart)
    }

    // === Load ===

    fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
    }

    fun cancelHosterVideoLinksJob() {
        getHosterVideoLinksJob?.cancel()
    }

    fun loadHosters(hosterList: List<Hoster>, hosterIndex: Int, videoIndex: Int) {
        val hasFoundPreferredVideo = AtomicBoolean(false)

        updateStateData { it.copy(hosterList = hosterList) }
        updateUiData { it.copy(hosterExpandedList = List(hosterList.size) { true }) }

        val source = stateData.value.currentSource
            ?: throw Exception("No source available")

        getHosterVideoLinksJob?.cancel()
        getHosterVideoLinksJob = viewModelScope.launchIO {
            updateStateData {
                it.copy(
                    hosterState = hosterList.map { hoster ->
                        if (hoster.lazy) {
                            HosterState.Idle(hoster.hosterName)
                        } else if (hoster.videoList == null) {
                            HosterState.Loading(hoster.hosterName)
                        } else {
                            val videoList = hoster.videoList!!
                            HosterState.Ready(
                                hoster.hosterName,
                                videoList,
                                List(videoList.size) { Video.State.QUEUE },
                            )
                        }
                    },
                )
            }

            try {
                coroutineScope {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)

                            updateHosterStateAt(hosterIdx, hosterState)

                            if (hosterState is HosterState.Ready) {
                                if (hosterIdx == hosterIndex) {
                                    hosterState.videoList.getOrNull(videoIndex)?.let {
                                        hasFoundPreferredVideo.set(true)
                                        val success = loadVideo(it, hosterIndex, videoIndex)
                                        if (!success) {
                                            hasFoundPreferredVideo.set(false)
                                        }
                                    }
                                }

                                val prefIndex = hosterState.videoList.indexOfFirst { it.preferred }
                                if (prefIndex != -1 && hosterIndex == -1) {
                                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                                        if (uiData.value.selectedHosterVideoIndex == Pair(-1, -1)) {
                                            val success =
                                                loadVideo(
                                                    hosterState.videoList[prefIndex],
                                                    hosterIdx,
                                                    prefIndex,
                                                )
                                            if (!success) {
                                                hasFoundPreferredVideo.set(false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()

                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                        if (uiData.value.selectedHosterVideoIndex == Pair(-1, -1)) {
                            val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(stateData.value.hosterState)
                            if (hosterIdx == -1) {
                                throw ExceptionWithStringResource(
                                    "No available videos",
                                    AYMR.strings.no_available_videos,
                                )
                            }

                            val video = (stateData.value.hosterState[hosterIdx] as HosterState.Ready)
                                .videoList[videoIdx]
                            loadVideo(video, hosterIdx, videoIdx)
                        }
                    }
                }
            } catch (e: CancellationException) {
                updateStateData {
                    it.copy(
                        hosterState = it.hosterList.map { h ->
                            HosterState.Idle(h.hosterName)
                        },
                    )
                }

                throw e
            }
        }
    }

    fun loadBestVideo(): Boolean {
        val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(stateData.value.hosterState)
        if (hosterIdx == -1) return false
        val newVideo = (stateData.value.hosterState[hosterIdx] as HosterState.Ready).videoList[videoIdx]
        viewModelScope.launchIO {
            loadVideo(newVideo, hosterIdx, videoIdx)
        }
        return true
    }

    /** Loads [video]; returns true if successful. */
    private suspend fun loadVideo(video: Video, hosterIndex: Int, videoIndex: Int): Boolean {
        val source = stateData.value.currentSource
            ?: throw Exception("No source loaded")
        val currentUi = uiData.value
        val selectedHosterState = (stateData.value.hosterState[hosterIndex] as? HosterState.Ready)
            ?: return false

        val oldSelectedIndex = currentUi.selectedHosterVideoIndex
        updateUiData {
            it.copy(
                isLoadingEpisode = true,
                selectedHosterVideoIndex = Pair(hosterIndex, videoIndex),
                previousPauseState = it.previousPauseState ?: playbackData.value.paused,
            )
        }
        updateStateData {
            it.copy(
                hosterState = getHosterStateAt(
                    hosters = it.hosterState,
                    index = hosterIndex,
                    state = selectedHosterState.getChangedAt(videoIndex, video, Video.State.LOAD_VIDEO),
                ),
            )
        }

        // Pause until everything has loaded
        pause()

        val resolvedVideo = if (selectedHosterState.videoState[videoIndex] != Video.State.READY) {
            HosterLoader.getResolvedVideo(source, video)
        } else {
            video
        }

        if (resolvedVideo == null || resolvedVideo.videoUrl.isEmpty()) {
            if (stateData.value.currentVideo == null) {
                updateHosterStateAt(hosterIndex, selectedHosterState.getChangedAt(videoIndex, video, Video.State.ERROR))

                val (newHosterIdx, newVideoIdx) = HosterLoader.selectBestVideo(stateData.value.hosterState)
                if (newHosterIdx == -1) {
                    if (stateData.value.hosterState.any { it is HosterState.Loading }) {
                        updateUiData { it.copy(selectedHosterVideoIndex = Pair(-1, -1)) }
                        return false
                    } else {
                        throw ExceptionWithStringResource("No available videos", AYMR.strings.no_available_videos)
                    }
                }

                val newVideo = (stateData.value.hosterState[newHosterIdx] as HosterState.Ready).videoList[newVideoIdx]
                return loadVideo(newVideo, newHosterIdx, newVideoIdx)
            } else {
                updateStateData {
                    it.copy(
                        hosterState = getHosterStateAt(
                            hosters = it.hosterState,
                            index = hosterIndex,
                            state = selectedHosterState.getChangedAt(videoIndex, video, Video.State.ERROR),
                        ),
                    )
                }
                updateUiData {
                    it.copy(
                        selectedHosterVideoIndex = oldSelectedIndex,
                    )
                }
                return false
            }
        }

        updateHosterStateAt(
            index = hosterIndex,
            state = selectedHosterState.getChangedAt(videoIndex, resolvedVideo, Video.State.READY),
        )
        updateStateData { it.copy(currentVideo = resolvedVideo) }

        if (stateData.value.hasLoadedTracks) {
            clearTracks()
        }

        viewModelScope.launchIO {
            loadThumbnails(resolvedVideo, source)
        }

        qualityIndex = Pair(hosterIndex, videoIndex)
        setVideo(resolvedVideo)
        return true
    }

    private fun setVideo(video: Video?) {
        if (player.isExiting) return
        if (video == null) return
        stopHttpServer()

        val castState = castManager.castState.value
        val isLoadingEpisode = if (castState.hasLoadedVideo) {
            castUiData.value.isLoadingEpisode
        } else {
            uiData.value.isLoadingEpisode
        }
        val resumePosition = if (isLoadingEpisode) {
            stateData.value.currentEpisode?.let { episode ->
                val preservePos = playerPreferences.preserveWatchingPosition.get()
                // AM (RECENT_EPISODE_POSITIONS) -->
                val recentPosition = episode.id?.let { recentEpisodePositions.remove(it) }
                // <-- AM (RECENT_EPISODE_POSITIONS)
                forceResumeFromLastPosition.let { resumeFromLast ->
                    forceResumeFromLastPosition = false
                    when {
                        resumeFromLast -> episode.last_second_seen / 1000L
                        // AM (RECENT_EPISODE_POSITIONS) -->
                        recentPosition != null -> recentPosition.positionMs / 1000L
                        // <-- AM (RECENT_EPISODE_POSITIONS)
                        episode.seen && !preservePos -> 0L
                        else -> episode.last_second_seen / 1000L
                    }
                }
            }
        } else if (castState.hasLoadedVideo) {
            castState.position
        } else {
            playbackData.value.position.toLong()
        }

        if (stateData.value.isCasting) {
            startCasting(startPosition = resumePosition ?: 0L)
            return
        }

        updateStateData { it.copy(isStopped = false) }
        setHttpOptions(video)
        resumePosition?.let {
            mpvCommand("set", "start", it.toString())
        }

        // We handle selecting these in the viewmodel
        val mpvOpts = listOf(
            Pair("sid", "no"),
            Pair("aid", "no"),
            Pair("vid", "auto"),
        )
        val videoOptions = (video.mpvArgs + mpvOpts).joinToString(",") { (option, value) ->
            "$option=\"$value\""
        }

        if (torrentPreferences.torrServerEnable.get() && isTorrent(video)) {
            launchIO {
                TorrentServerService.start()
                torrentLinkHandler(video.videoUrl, video.videoTitle, videoOptions)
            }
        } else {
            launchIO {
                val httpSource = stateData.value.currentSource as? AnimeHttpSource
                var videoUrl: String = video.videoUrl
                if (video.usesHttpServer() && httpSource != null) {
                    val port = try {
                        httpServer = httpSource.createHttpServer()
                        httpServer?.start()
                        httpServer?.listeningPort ?: 0
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to start http server" }
                        _eventFlow.emit(Event.ToastResource(AYMR.strings.http_server_start_failure))
                        return@launchIO
                    }

                    val newVideo = video.copyHttpServer(port)
                    videoUrl = newVideo.videoUrl
                    updateStateData { it.copy(currentVideo = newVideo) }
                }

                loadFile(parseVideoUrl(videoUrl)!!, videoOptions)
            }
        }
    }

    private suspend fun torrentLinkHandler(videoUrl: String, title: String, videoOptions: String) {
        val videoTorrentUrl = getTorrentUrl(videoUrl, title)
        mpvCommand(
            "loadfile",
            videoTorrentUrl,
            "replace",
            "0",
            videoOptions,
        )
    }

    private suspend fun getTorrentUrl(videoUrl: String, title: String): String {
        var index = 0

        // check if link is from localSource
        if (videoUrl.startsWith("content://")) {
            val videoInputStream = context.contentResolver.openInputStream(videoUrl.toUri())
            val torrent = torrentServerApi.uploadTorrent(videoInputStream!!, title, false)
            return torrentServerUtils.getTorrentPlayLink(torrent, 0)
        }

        // check if link is from magnet, in that check if index is present
        if (videoUrl.startsWith("magnet")) {
            if (videoUrl.contains("index=")) {
                index = try {
                    videoUrl.substringAfter("index=").substringBefore("&").toInt()
                } catch (_: NumberFormatException) {
                    0
                }
            }
        }

        val currentTorrent = torrentServerApi.addTorrent(videoUrl, title, "", "", false)
        return torrentServerUtils.getTorrentPlayLink(currentTorrent, index)
    }

    private fun isTorrent(video: Video): Boolean {
        if (video.videoUrl.startsWith(torrentServerApi.hostUrl)) {
            return true
        }

        if (video.videoUrl.startsWith("magnet")) {
            return true
        }

        return video.videoUrl.endsWith("torrent")
    }

    private suspend fun loadThumbnails(video: Video, source: AnimeSource?) {
        if (source is AnimeHttpSource) {
            try {
                val thumbInfo = source.getVideoThumbnails(video)
                if (thumbInfo != null) {
                    updatePlaybackData {
                        it.copy(
                            thumbnailInfo = ThumbnailInfo(
                                tileInfo = thumbInfo.tileInfo.sortedBy { it.timeMs },
                                imageTileUrls = thumbInfo.imageTileUrls,
                            ),
                        )
                    }

                    // Preload first 2 tilemaps
                    thumbInfo.imageTileUrls.take(2).forEachIndexed { index, tileUrl ->
                        val bitmap = source.getImageTile(tileUrl)
                        if (bitmap != null) {
                            thumbnailTileCache[index] = bitmap
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to fetch thumbnails" }
            }
        }
    }

    private fun parseVideoUrl(videoUrl: String?): String? {
        return videoUrl?.toUri()?.resolveUri(context)
            ?: videoUrl
    }

    private fun setHttpOptions(video: Video) {
        if (!stateData.value.isEpisodeOnline) return
        val source = stateData.value.currentSource as? AnimeHttpSource
            ?: return

        val headers = (video.headers ?: source.headers)
            .toMultimap()
            .mapValues { it.value.firstOrNull() ?: "" }

        val httpHeaderString = headers.map {
            it.key + ": " + it.value.replace(",", "\\,")
        }.joinToString(",")

        mpv.setOptionString("http-header-fields", httpHeaderString)
    }

    private fun eofReached(eofReached: Boolean) {
        if (eofReached && uiData.value.autoPlayEnabled) {
            nextEpisode(next = true, autoplay = true)
        }
    }

    private fun endFile(node: MPVNode) {
        val errorNode = node.asMap()?.get("file_error") ?: return
        var errorMessage = errorNode.asString() ?: "Error: File ended"

        val httpError = player.getHttpError()
        if (!httpError.isNullOrEmpty()) {
            errorMessage += ": $httpError"
            player.resetHttpError()
        }

        logcat(LogPriority.ERROR) { errorMessage }
        viewModelScope.launch {
            _eventFlow.emit(Event.ToastString(errorMessage))
        }

        setCurrentVideoError()

        if (playerPreferences.switchOnFailure.get()) {
            if (!loadBestVideo()) {
                viewModelScope.launch { _eventFlow.emit(Event.Finish) }
            }
        } else {
            updateStateData { it.copy(isStopped = true) }
        }
    }

    fun setCurrentVideoError() {
        val (hosterIdx, videoIdx) = uiData.value.selectedHosterVideoIndex
        val currentHosterState = (stateData.value.hosterState[hosterIdx] as? HosterState.Ready) ?: return
        val currentVideo = currentHosterState.videoList[videoIdx]

        updateStateData {
            it.copy(
                currentVideo = null,
                hosterState = getHosterStateAt(
                    hosters = it.hosterState,
                    index = hosterIdx,
                    state = currentHosterState.getChangedAt(videoIdx, currentVideo, Video.State.ERROR),
                ),
            )
        }
    }

    fun onVideoClicked(hosterIndex: Int, videoIndex: Int) {
        val hosterState = stateData.value.hosterState[hosterIndex] as? HosterState.Ready
        val video = hosterState?.videoList
            ?.getOrNull(videoIndex)
            ?: return // How did we get here?

        val videoState = hosterState.videoState
            .getOrNull(videoIndex)
            ?: return

        if (videoState == Video.State.ERROR) {
            return
        }

        viewModelScope.launchIO {
            val success = loadVideo(video, hosterIndex, videoIndex)
            if (success) {
                if (uiData.value.sheetShown == Sheets.QualityTracks ||
                    castUiData.value.sheetShown == CastSheet.Quality
                ) {
                    dismissSheet()
                }
            }
        }
    }

    fun onHosterClicked(index: Int) {
        when (stateData.value.hosterState[index]) {
            is HosterState.Ready -> {
                updateUiData {
                    it.copy(
                        hosterExpandedList = it.hosterExpandedList.toMutableList().apply {
                            this[index] = !it.hosterExpandedList[index]
                        }.toList(),
                    )
                }
            }
            is HosterState.Idle -> {
                val source = stateData.value.currentSource
                    ?: throw Exception("Source not loaded")

                val hosterName = stateData.value.hosterList[index].hosterName
                updateHosterStateAt(index, HosterState.Loading(hosterName))

                viewModelScope.launchIO {
                    val hosterState = EpisodeLoader.loadHosterVideos(
                        source = source,
                        hoster = stateData.value.hosterList[index],
                        force = true,
                    )
                    updateHosterStateAt(index, hosterState)
                }
            }
            is HosterState.Error, is HosterState.Loading -> { }
        }
    }

    private fun getHosterStateAt(hosters: List<HosterState>, index: Int, state: HosterState): List<HosterState> {
        return hosters.toMutableList().apply {
            this[index] = state
        }.toList()
    }

    private fun updateHosterStateAt(index: Int, state: HosterState) {
        updateStateData {
            it.copy(
                hosterState = getHosterStateAt(it.hosterState, index, state),
            )
        }
    }

    private fun fileLoaded() {
        if (player.isExiting) return

        setMpvOptions()
        setMpvMediaTitle()
        setupChapters()
        setupPlayerOrientation()
        checkFileLoaded()
        generateEpisodeThumbnailIfMissing()

        // AniSkip stuff
        val chapterCount = mpv.getPropertyInt("chapter-list/count") ?: 0
        val duration = playbackData.value.duration
        loadAniSkip(chapterCount, duration)
    }

    private fun loadAniSkip(chapterCount: Int, duration: Int?) {
        viewModelScope.launchIO {
            if (introSkipEnabled && aniSkipEnabled && !(disableAniSkipOnChapters && chapterCount > 0)) {
                aniSkipResponse(duration)?.let { stamps ->
                    updateStateData { it.copy(aniskipChapters = stamps) }
                    if (!stateData.value.isCasting) {
                        addTimeStamps(stamps)
                    }
                } ?: run {
                    updateStateData { it.copy(aniskipChapters = emptyList()) }
                }
            } else {
                updateStateData { it.copy(aniskipChapters = emptyList()) }
            }
        }
    }

    // Signals when a thumbnail finishes generating, so notification/metadata artwork
    // (which only re-checks on episode/anime/duration change) re-checks preview_url too.
    private val _thumbnailGenerated = MutableStateFlow(0L)
    val thumbnailGenerated = _thumbnailGenerated.asStateFlow()

    /**
     * Lazily generates a missing thumbnail for local episodes only, a few seconds into
     * playback of this episode specifically. Streaming sources are skipped (no local
     * folder to save alongside).
     */
    private fun generateEpisodeThumbnailIfMissing() {
        val anime = stateData.value.currentAnime ?: return
        val episode = stateData.value.currentEpisode ?: return
        val episodeId = episode.id ?: return
        if (!anime.isLocal()) return
        if (!episode.preview_url.isNullOrBlank()) return

        viewModelScope.launchIO {
            // Give mpv a moment to decode a real frame, not a black/loading frame.
            delay(3000)
            if (player.isExiting) return@launchIO
            // Only capture if still on the episode this was scheduled for.
            if (stateData.value.currentEpisode?.id != episodeId) return@launchIO
            runCatching {
                val tempFile = File(context.cacheDir, "${episodeId}_auto_thumbnail_tmp.jpg")
                mpvCommand("screenshot-to-file", tempFile.absolutePath, "video")
                if (!tempFile.exists()) return@launchIO
                tempFile.inputStream().use { stream ->
                    episode.editThumbnail(anime, Injekt.get(), stream)
                }
                tempFile.delete()
            }.onSuccess {
                _thumbnailGenerated.update { episodeId }
            }
        }
    }

    private fun setMpvOptions() {
        val video = stateData.value.currentVideo ?: return

        // Only check for `MPV_ARGS_TAG` on downloaded videos
        if (listOf("file", "content", "data").none { video.videoUrl.startsWith(it) }) {
            return
        }

        try {
            val metadata = mpv.getPropertyNode("metadata")?.asMap()
                ?: return

            val opts = metadata[Video.MPV_ARGS_TAG]
                ?.asString()
                ?.split(";")
                ?.map { it.split("=", limit = 2) }
                ?: return

            opts.forEach { (option, value) ->
                setPropertyString(option, value)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read video metadata" }
        }
    }

    private fun setMpvMediaTitle() {
        val anime = stateData.value.currentAnime ?: return
        val episode = stateData.value.currentEpisode ?: return

        // Write to mpv table
        setPropertyString("user-data/current-anime/episode-title", episode.name)

        val epNumber = episode.episode_number.let { number ->
            if (ceil(number) == floor(number)) number.toInt() else number
        }.toString().padStart(2, '0')

        val title = context.stringResource(
            AYMR.strings.mpv_media_title,
            anime.title,
            epNumber,
            episode.name,
        )

        setPropertyString("force-media-title", title)
    }

    private fun setupChapters() {
        val timeStamps = stateData.value.currentVideo?.timestamps?.takeIf { it.isNotEmpty() }
            ?.map { timeStamp ->
                if (timeStamp.name.isEmpty() && timeStamp.type != ChapterType.Other) {
                    timeStamp.copy(
                        name = timeStamp.type.getStringRes()?.let { context.stringResource(it) } ?: "",
                    )
                } else {
                    timeStamp
                }
            }
            ?: return

        addTimeStamps(timeStamps)
    }

    private fun addTimeStamps(timeStamps: List<TimeStamp>) {
        if (timeStamps.isEmpty()) return

        val current = (
            mpv.getPropertyNode("chapter-list")
                ?.toObject<List<ChapterNode>>(json) ?: emptyList()
            )
            .map { IndexedSegment(name = it.chapterTitle, start = it.time, index = 0) }
        val merged = ChapterUtils.mergeChapters(current, timeStamps, playbackData.value.duration)
        val node = MPVNode.ArrayNode(
            merged.map { c ->
                MPVNode.MapNode(
                    value = mapOf(
                        "time" to MPVNode.DoubleNode(c.start.toDouble()),
                        "title" to MPVNode.StringNode(c.name),
                    ),
                )
            }.toTypedArray(),
        )
        setPropertyNode("chapter-list", node)
    }

    /** (Un)pauses once loading finishes; waits on external sub/audio tracks if selected. */
    private fun checkFileLoaded() {
        if (uiData.value.isLoadingEpisode && stateData.value.hasLoadedSubs && stateData.value.hasLoadedAudio) {
            uiData.value.previousPauseState?.let { shouldPause ->
                if (shouldPause) pause() else unpause()
            }

            updateUiData {
                it.copy(
                    isLoadingEpisode = false,
                    previousPauseState = null,
                )
            }
        }
    }

    fun clearTracks() {
        updateStateData {
            it.copy(
                externalSubtitleTracks = emptyList(),
                externalAudioTracks = emptyList(),
                hasLoadedTracks = false,
                hasLoadedSubs = false,
                hasLoadedAudio = false,
            )
        }
    }

    /** Selects the preferred subtitle/audio track once all tracks are loaded. */
    fun onTrackListChanged(tracks: MPVNode) {
        val tracks = tracks.toObject<List<TrackNode>>(json).ifEmpty { return }
        updateStateData {
            it.copy(
                subtitleTracks = tracks.filter { it.isSubtitle }
                    .filterNot { it.title?.startsWith(MpvVideoTrack.TRACK_TITLE_TAG) == true },
                audioTracks = tracks.filter { it.isAudio }
                    .filterNot { it.title?.startsWith(MpvVideoTrack.TRACK_TITLE_TAG) == true },
            )
        }

        if (stateData.value.hasLoadedTracks) {
            onTrackAdded(tracks)
            // mpv can report the embedded track list across multiple events (video
            // first, audio later); onTrackAdded() only handles external tracks, so
            // retry embedded selection here if it hasn't actually succeeded yet.
            if (!stateData.value.hasLoadedAudio || !stateData.value.hasLoadedSubs) {
                onTracksLoaded(tracks)
            }
        } else {
            updateStateData { it.copy(hasLoadedTracks = true) }
            onTracksLoaded(tracks)
        }
    }

    /** Tracks newly added external tracks internally. */
    private fun onTrackAdded(tracks: List<TrackNode>) {
        val externalSubtitle = tracks.filter {
            it.isSubtitle && it.title?.startsWith(MpvVideoTrack.TRACK_TITLE_TAG) == true
        }
        val externalAudio = tracks.filter {
            it.isAudio && it.title?.startsWith(MpvVideoTrack.TRACK_TITLE_TAG) == true
        }

        externalSubtitle.forEach { track ->
            val idx = track.title!!.split("=")[1].toInt()
            val external = stateData.value.externalSubtitleTracks[idx]

            if (external.id != null) {
                // External subtitle has already been added
                return@forEach
            }

            updateSubtitleTrackAt(idx) {
                it.copy(id = track.id, state = TrackState.Loaded, lang = track.getLanguage())
            }
            updateStateData { it.copy(hasLoadedSubs = true) }
            checkFileLoaded()
            selectSubById(track.id)
        }

        externalAudio.forEach { track ->
            val idx = track.title!!.split("=")[1].toInt()
            val external = stateData.value.externalAudioTracks[idx]

            if (external.id != null) {
                // External audio has already been added
                return@forEach
            }

            updateAudioTrackAt(idx) {
                it.copy(id = track.id, state = TrackState.Loaded, lang = track.getLanguage())
            }
            updateStateData { it.copy(hasLoadedAudio = true) }
            checkFileLoaded()
            selectAudioById(track.id, false)
        }
    }

    /** Called when embedded tracks are first loaded. */
    private fun onTracksLoaded(tracks: List<TrackNode>) {
        val embeddedSubs = tracks.filter { it.isSubtitle }
        val embeddedAudio = tracks.filter { it.isAudio }
        val currentVideo = stateData.value.currentVideo
        val externalSubs = currentVideo?.subtitleTracks.orEmpty().distinctBy { it.url }
            .mapIndexed { idx, track -> MpvVideoTrack.External(track, idx) }
        val externalAudio = currentVideo?.audioTracks.orEmpty().distinctBy { it.url }
            .mapIndexed { idx, track -> MpvVideoTrack.External(track, idx) }

        updateStateData {
            it.copy(
                externalSubtitleTracks = externalSubs,
                externalAudioTracks = externalAudio,
            )
        }

        val preferredSubtitle = trackSelect.getPreferredTrackIndex(
            tracks = embeddedSubs.map { MpvVideoTrack.Internal(it) } + externalSubs,
            subtitle = true,
        )
        if (preferredSubtitle == null) {
            updateStateData { it.copy(hasLoadedSubs = true) }
        } else {
            selectSub(preferredSubtitle)
        }

        val preferredAudio = trackSelect.getPreferredTrackIndex(
            tracks = embeddedAudio.map { MpvVideoTrack.Internal(it) } + externalAudio,
            subtitle = false,
        )
        if (preferredAudio == null) {
            // Deliberately not marking hasLoadedAudio = true: mpv can report the track
            // list before an embedded audio track is registered (see onTrackListChanged),
            // and this flag lets that retry logic try again on the next update.
        } else {
            selectAudio(preferredAudio, true)
        }
    }

    private fun updateSubtitleTrackAt(index: Int, transform: (MpvVideoTrack.External) -> MpvVideoTrack.External) {
        updateStateData {
            it.copy(
                externalSubtitleTracks = it.externalSubtitleTracks.toMutableList().apply {
                    this[index] = transform(this[index])
                }.toList(),
            )
        }
    }

    private fun updateAudioTrackAt(index: Int, transform: (MpvVideoTrack.External) -> MpvVideoTrack.External) {
        updateStateData {
            it.copy(
                externalAudioTracks = it.externalAudioTracks.toMutableList().apply {
                    this[index] = transform(this[index])
                }.toList(),
            )
        }
    }

    fun addSubtitle(uri: Uri) {
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        val path = (if (isContentUri) uri.openContentFd(context) else url)
            ?: return
        val name = if (isContentUri) uri.getFileName(context) else null
        if (name == null) {
            mpvCommand("sub-add", path, "cached")
        } else {
            mpvCommand("sub-add", path, "cached", name)
        }
    }

    fun selectSub(track: MpvVideoTrack) {
        when (track) {
            is MpvVideoTrack.External -> {
                if (track.id == null) {
                    updateSubtitleTrackAt(track.index) {
                        it.copy(state = TrackState.Loading)
                    }
                    viewModelScope.launchIO {
                        mpvCommand(
                            "sub-add",
                            track.data.url,
                            "auto",
                            "${MpvVideoTrack.TRACK_TITLE_TAG}=${track.index}",
                        )
                    }
                } else {
                    updateStateData { it.copy(hasLoadedSubs = true) }
                    checkFileLoaded()
                    selectSubById(track.id)
                }
            }
            is MpvVideoTrack.Internal -> {
                updateStateData { it.copy(hasLoadedSubs = true) }
                checkFileLoaded()
                selectSubById(track.data.id)
            }
        }
    }

    private fun selectSubById(id: Int) {
        val selectedSubs = Pair(mpv.getPropertyInt("sid"), mpv.getPropertyInt("secondary-sid"))
        when (id) {
            selectedSubs.first -> Pair(selectedSubs.second, null)
            selectedSubs.second -> Pair(selectedSubs.first, null)
            else -> if (selectedSubs.first != null) Pair(selectedSubs.first, id) else Pair(id, null)
        }.let {
            it.second?.let { setPropertyInt("secondary-sid", it) }
                ?: setPropertyBoolean("secondary-sid", false)
            it.first?.let { setPropertyInt("sid", it) } ?: setPropertyBoolean("sid", false)
        }
    }

    private fun onSubtitleTrackSelectChange() {
        val id = mpv.getPropertyInt("sid")
        val sid = mpv.getPropertyInt("secondary-sid")

        updateStateData {
            it.copy(
                externalSubtitleTracks = it.externalSubtitleTracks.map { tracks ->
                    tracks.copy(
                        mainSelection = when (tracks.id) {
                            null -> -1
                            id -> 0
                            sid -> 1
                            else -> -1
                        },
                    )
                },
            )
        }
    }

    fun addAudio(uri: Uri) {
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        val path = (if (isContentUri) uri.openContentFd(context) else url)
            ?: return
        val name = if (isContentUri) uri.getFileName(context) else null
        if (name == null) {
            mpvCommand("audio-add", path, "cached")
        } else {
            mpvCommand("audio-add", path, "cached", name)
        }
    }

    fun selectAudio(track: MpvVideoTrack, force: Boolean = false) {
        when (track) {
            is MpvVideoTrack.External -> {
                if (track.id == null) {
                    updateAudioTrackAt(track.index) {
                        it.copy(state = TrackState.Loading)
                    }
                    viewModelScope.launchIO {
                        mpvCommand(
                            "audio-add",
                            track.data.url,
                            "auto",
                            "${MpvVideoTrack.TRACK_TITLE_TAG}=${track.index}",
                        )
                    }
                } else {
                    updateStateData { it.copy(hasLoadedAudio = true) }
                    checkFileLoaded()
                    selectAudioById(track.id, force)
                }
            }
            is MpvVideoTrack.Internal -> {
                updateStateData { it.copy(hasLoadedAudio = true) }
                checkFileLoaded()
                selectAudioById(track.data.id, force)
            }
        }
    }

    private fun selectAudioById(id: Int, force: Boolean) {
        if (!force && id == mpv.getPropertyInt("aid")) {
            setPropertyBoolean("aid", false)
        } else {
            setPropertyInt("aid", id)
        }
    }

    // Tracked reactively as the freshest known-good audio track: mpv can spontaneously
    // clear it ~100-200ms after a decoder reconfig, so onAudioTrackSelectChange below
    // self-heals reactively from this value instead of timing a one-shot reapply.
    private var lastKnownAudioTrackId: Int? = null

    private fun onAudioTrackSelectChange() {
        val id = mpv.getPropertyInt("aid")
        if (id != null && id > 0) {
            lastKnownAudioTrackId = id
        } else if (!uiData.value.isLoadingEpisode) {
            // Guarded by isLoadingEpisode so this doesn't fight genuine "no track
            // selected yet" states during an actual new episode load.
            lastKnownAudioTrackId?.let { savedId ->
                if (savedId > 0) setPropertyInt("aid", savedId)
            }
        }

        updateStateData {
            it.copy(
                externalAudioTracks = it.externalAudioTracks.map { tracks ->
                    tracks.copy(
                        mainSelection = when (tracks.id) {
                            null -> -1
                            id -> 0
                            else -> -1
                        },
                    )
                },
            )
        }
    }

    fun onTrackLoadedFailure(url: String) {
        val subtitleIdx = stateData.value.externalSubtitleTracks.indexOfFirst {
            it.data.url == url
        }
        if (subtitleIdx != -1) {
            updateSubtitleTrackAt(subtitleIdx) {
                it.copy(state = TrackState.Error)
            }
            updateStateData { it.copy(hasLoadedSubs = true) }
            checkFileLoaded()
        }
        val audioIdx = stateData.value.externalAudioTracks.indexOfFirst {
            it.data.url == url
        }
        if (audioIdx != -1) {
            updateAudioTrackAt(audioIdx) {
                it.copy(state = TrackState.Error)
            }
            updateStateData { it.copy(hasLoadedAudio = true) }
            checkFileLoaded()
        }
    }

    fun onChapterListChanged(node: MPVNode) {
        val chapters = node.toObject<List<ChapterNode>>(json).map {
            it.toSegment()
        }
        updateStateData { it.copy(chapters = chapters) }
    }

    private data class EpisodeLoadResult(
        val hosterList: List<Hoster>?,
        val episodeTitle: String,
    )

    /** Loads an episode, returning its hoster list and title. */
    private suspend fun loadEpisode(episodeId: Long?): EpisodeLoadResult? {
        val anime = stateData.value.currentAnime ?: return null
        val source = sourceManager.getOrStub(anime.source)

        val chosenEpisode = stateData.value.currentPlaylist.firstOrNull { ep ->
            ep.id == episodeId
        } ?: return null

        setupEpisode(chosenEpisode)

        return withIOContext {
            try {
                currentHosterList = EpisodeLoader.getHosters(
                    episode = chosenEpisode.toDomainEpisode()!!,
                    anime,
                    source,
                )
                this@PlayerViewModel.episodeId = chosenEpisode.id!!
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { e.message ?: "Error getting links" }
            }

            EpisodeLoadResult(
                hosterList = currentHosterList,
                episodeTitle = "${anime.title} - ${chosenEpisode.name}",
            )
        }
    }

    fun nextEpisode(next: Boolean, autoplay: Boolean = false) {
        val currentIndex = stateData.value.currentPlaylistIndex
        val newIndex = if (next) currentIndex + 1 else currentIndex - 1

        if (newIndex !in stateData.value.currentPlaylist.indices) return
        val episodeId = stateData.value.currentPlaylist.getOrNull(newIndex)?.id ?: return

        changeEpisode(episodeId, autoplay)
    }

    /** Switches playback to [episodeId]; [autoPlay] indicates an automatic transition. */
    fun changeEpisode(episodeId: Long?, autoPlay: Boolean = false) {
        if (stateData.value.isCasting) {
            castManager.stopRemoteMediaClient()
            updateCastUiData { it.copy(isLoadingEpisode = true) }
        } else {
            pause()
            clearTracks()
        }

        // AM (RECENT_EPISODE_POSITIONS) -->
        rememberRecentEpisodePosition()
        stateData.value.currentPlaylist.indexOfFirst { it.id == episodeId }
            .takeIf { it != -1 }
            ?.let { pruneRecentEpisodePositions(it) }
        // <-- AM (RECENT_EPISODE_POSITIONS)

        updateStateData { it.copy(hosterList = emptyList()) }
        updateUiData {
            it.copy(
                sheetShown = Sheets.None,
                panelShown = Panels.None,
                isLoadingEpisode = true,
                isLoadingHosters = true,
                previousPauseState = false,
                hosterExpandedList = emptyList(),
                selectedHosterVideoIndex = Pair(-1, -1),
            )
        }
        cancelHosterVideoLinksJob()
        thumbnailTileCache.clear()
        thumbnailFetchJob?.cancel()
        lastThumbnailFetch = 0L

        viewModelScope.launch {
            val switchMethod = loadEpisode(episodeId)
            updateUiData { it.copy(isLoadingHosters = false) }

            if (switchMethod == null) {
                if (stateData.value.currentAnime != null && !autoPlay) {
                    _eventFlow.emit(Event.ToastResource(AYMR.strings.no_next_episode))
                }
                return@launch
            }

            if (switchMethod.hosterList != null) {
                when {
                    switchMethod.hosterList.isEmpty() -> _eventFlow.emit(
                        Event.InitialEpisodeError(
                            ExceptionWithStringResource(
                                "Hoster list is empty",
                                AYMR.strings.no_hosters,
                            ),
                        ),
                    )
                    else -> {
                        loadHosters(
                            hosterList = switchMethod.hosterList,
                            hosterIndex = -1,
                            videoIndex = -1,
                        )
                    }
                }
            } else {
                logcat(LogPriority.ERROR) { "Error getting links" }
            }

            if (pipEpisodeToasts) {
                _eventFlow.emit(Event.EpisodeTitle(switchMethod.episodeTitle))
            }
        }
    }

    // === Controls ===

    fun onKey(keyEvent: KeyEvent): Boolean {
        return player.onKey(keyEvent)
    }

    fun updateHasPip(value: Boolean) {
        updateStateData { it.copy(isPipAvailable = value) }
    }

    fun pauseUnpause() = mpvCommand("cycle", "pause")
    fun pause() {
        setPropertyBoolean("pause", true)

        // PiP needs the state immediately
        updatePlaybackData { it.copy(paused = true) }
    }
    fun unpause() {
        setPropertyBoolean("pause", false)
        updatePlaybackData { it.copy(paused = false) }
    }

    fun showControls() {
        val currentUi = uiData.value
        if (currentUi.sheetShown != Sheets.None ||
            currentUi.panelShown != Panels.None ||
            currentUi.dialogShown != Dialogs.None
        ) {
            return
        }
        updateUiData {
            it.copy(
                controlsShown = true,
                statusBarShown = showStatusBar,
            )
        }
    }

    fun hideControls() {
        updateUiData {
            it.copy(
                controlsShown = false,
                statusBarShown = false,
            )
        }
    }

    fun hideSeekBar() {
        updateUiData { it.copy(seekBarShown = false) }
    }

    fun showSeekBar() {
        if (uiData.value.sheetShown != Sheets.None) return
        updateUiData { it.copy(seekBarShown = true) }
    }

    fun toggleDurationTimer() {
        val newValue = !uiData.value.invertDuration
        playerPreferences.invertDuration.set(newValue)
        updateUiData { it.copy(invertDuration = newValue) }
        updateCastUiData { it.copy(invertDurationTimer = newValue) }
    }

    fun dismissSheet() {
        updateUiData { it.copy(dismissSheet = true) }
    }

    private fun resetDismissSheet() {
        updateUiData { it.copy(dismissSheet = false) }
    }

    fun setSheet(sheet: Sheets) {
        updateUiData { it.copy(sheetShown = sheet) }
        if (sheet == Sheets.None) {
            resetDismissSheet()
            showControls()
        } else {
            hideControls()
            updateUiData {
                it.copy(
                    panelShown = Panels.None,
                    dialogShown = Dialogs.None,
                )
            }
        }
    }

    fun setPanel(panel: Panels) {
        updateUiData { it.copy(panelShown = panel) }
        if (panel == Panels.None) {
            showControls()
        } else {
            hideControls()
            updateUiData {
                it.copy(
                    sheetShown = Sheets.None,
                    dialogShown = Dialogs.None,
                )
            }
        }
    }

    fun setDialog(dialog: Dialogs) {
        updateUiData { it.copy(dialogShown = dialog) }
        if (dialog == Dialogs.None) {
            showControls()
        } else {
            hideControls()
            updateUiData {
                it.copy(
                    sheetShown = Sheets.None,
                    panelShown = Panels.None,
                )
            }
        }
    }

    fun changeBrightnessTo(brightness: Float) {
        updatePlaybackData { it.copy(currentBrightness = brightness.coerceIn(-0.75f, 1f)) }
    }

    fun displayBrightnessSlider(show: Boolean) {
        updateUiData { it.copy(isBrightnessSliderShown = show) }
    }

    fun changeVolumeBy(change: Int) {
        val mpvVolume = mpv.getPropertyInt("volume")
        if ((stateData.value.volumeBoostCap ?: audioPreferences.volumeBoostCap.get()) > 0 &&
            playbackData.value.currentVolume == maxVolume
        ) {
            if (mpvVolume == 100 && change < 0) changeVolumeTo(playbackData.value.currentVolume + change)

            val finalMPVVolume = (mpvVolume?.plus(change))?.coerceAtLeast(100) ?: 100
            if (finalMPVVolume in
                100..(stateData.value.volumeBoostCap ?: audioPreferences.volumeBoostCap.get()) + 100
            ) {
                changeMPVVolumeTo(finalMPVVolume)
                return
            }
        }
        changeVolumeTo(playbackData.value.currentVolume + change)
    }

    fun setVolumeTo(volume: Int) {
        updatePlaybackData { it.copy(currentVolume = volume) }
    }

    fun changeVolumeTo(volume: Int) {
        val newVolume = volume.coerceIn(0..maxVolume)
        audioManager.setVolume(newVolume)
        playerPreferences.playerVolumeValue.set(newVolume)
        updatePlaybackData { it.copy(currentVolume = newVolume) }
    }

    fun changeMPVVolumeTo(volume: Int) {
        setPropertyInt("volume", volume)
    }

    fun displayVolumeSlider(show: Boolean) {
        updateUiData { it.copy(isVolumeSliderShown = show) }
    }

    private fun cycleAspectRatio() {
        val newAspectRatio = when (playerPreferences.aspectState.get()) {
            VideoAspect.Fit -> VideoAspect.Stretch
            VideoAspect.Stretch -> VideoAspect.Crop
            VideoAspect.Crop -> VideoAspect.Fit
        }

        setAspectRatio(newAspectRatio)
    }

    fun setAspectRatio(aspect: VideoAspect, showConfirmation: Boolean = true) {
        val (pan, ratio) = when (aspect) {
            VideoAspect.Crop -> {
                1.0 to -1.0
            }
            VideoAspect.Fit -> {
                0.0 to -1.0
            }
            VideoAspect.Stretch -> {
                0.0 to screenAspectRatio
            }
        }

        setPropertyDouble("panscan", pan)
        setPropertyDouble("video-aspect-override", ratio)
        playerPreferences.aspectState.set(aspect)
        if (showConfirmation) {
            updateUiData { it.copy(playerUpdate = PlayerUpdates.AspectRatio(aspect)) }
        }
    }

    private fun setSpeed(value: Float) {
        setPropertyFloat("speed", value)
        playerPreferences.playerSpeed.set(value)
    }

    private fun setAutoPlay(value: Boolean) {
        val textRes = if (value) {
            AYMR.strings.enable_auto_play
        } else {
            AYMR.strings.disable_auto_play
        }
        updateUiData { it.copy(playerUpdate = PlayerUpdates.ShowTextResource(textRes)) }
        playerPreferences.autoplayEnabled.set(value)
    }

    // === Custom buttons ===

    fun executeButton(button: CustomButton) {
        mpvCommand("script-message", "call_button_${button.id}")
    }

    fun executeLongPressButton(button: CustomButton) {
        mpvCommand("script-message", "call_button_${button.id}_long")
    }

    fun setPrimaryCustomButtonTitle(button: CustomButton) {
        updateUiData { it.copy(primaryButtonTitle = button.name) }
    }

    fun handleLuaInvocation(property: String, value: String) {
        val data = value
            .removePrefix("\"")
            .removeSuffix("\"")
            .ifEmpty { return }

        when (property.substringAfterLast("/")) {
            "show_text" -> updateUiData { it.copy(playerUpdate = PlayerUpdates.ShowText(data)) }
            "toggle_ui" -> {
                when (data) {
                    "show" -> showControls()
                    "toggle" -> if (uiData.value.controlsShown) hideControls() else showControls()
                    "hide" -> {
                        updateUiData {
                            it.copy(
                                sheetShown = Sheets.None,
                                panelShown = Panels.None,
                                dialogShown = Dialogs.None,
                            )
                        }
                        hideControls()
                    }
                }
            }
            "show_panel" -> {
                when (data) {
                    "subtitle_settings" -> setPanel(Panels.SubtitleSettings)
                    "subtitle_delay" -> setPanel(Panels.SubtitleDelay)
                    "audio_delay" -> setPanel(Panels.AudioDelay)
                    "video_filters" -> setPanel(Panels.VideoFilters)
                }
            }
            "set_button_title" -> {
                updateUiData { it.copy(primaryButtonTitle = data) }
            }
            "reset_button_title" -> {
                uiData.value.customButtons.firstOrNull { it.isFavorite }?.let {
                    setPrimaryCustomButtonTitle(it)
                }
            }
            "switch_episode" -> {
                when (data) {
                    "n" -> nextEpisode(next = true)
                    "p" -> nextEpisode(next = false)
                }
            }
            "launch_int_picker" -> {
                val (title, nameFormat, start, stop, step, pickerProperty) = data.split("|")
                val defaultValue = mpv.getPropertyInt(pickerProperty)!!
                setDialog(
                    Dialogs.IntegerPicker(
                        defaultValue = defaultValue,
                        minValue = start.toInt(),
                        maxValue = stop.toInt(),
                        step = step.toInt(),
                        nameFormat = nameFormat,
                        title = title,
                        onChange = { setPropertyInt(pickerProperty, it) },
                        onDismissRequest = { setDialog(Dialogs.None) },
                    ),
                )
            }
            "show_seek_text" -> {
                val (forward, text) = data.split("|", limit = 2)
                showSeekText(forward == "true", text)
            }
            "pause" -> {
                when (data) {
                    "pause" -> pause()
                    "unpause" -> unpause()
                    "pauseunpause" -> pauseUnpause()
                }
            }
            "seek_to_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekToWithText(seekValue.toInt(), text)
            }
            "seek_by_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekByWithText(seekValue.toInt(), text)
            }
            "seek_by" -> seekByWithText(data.toInt(), null)
            "seek_to" -> seekToWithText(data.toInt(), null)
            "toggle_button" -> {
                fun showButton() {
                    if (uiData.value.primaryButton == null) {
                        updateUiData {
                            it.copy(
                                primaryButton = it.customButtons.firstOrNull { it.isFavorite },
                            )
                        }
                    }
                }

                when (data) {
                    "show" -> showButton()
                    "hide" -> updateUiData { it.copy(primaryButton = null) }
                    "toggle" -> if (uiData.value.primaryButton == null) {
                        showButton()
                    } else {
                        updateUiData { it.copy(primaryButton = null) }
                    }
                }
            }
            "software_keyboard" -> {
                viewModelScope.launch {
                    when (data) {
                        "show" -> _eventFlow.emit(Event.SetKeyboard(true))
                        "hide" -> _eventFlow.emit(Event.SetKeyboard(false))
                        "toggle" -> _eventFlow.emit(Event.ToggleKeyboard)
                    }
                }
            }
        }

        setPropertyString(property, "")
    }

    private operator fun <T> List<T>.component6(): T = get(5)

    // === Seeking ===

    fun updateGestureSeekAmount(value: Pair<Int, Int>?) {
        updatePlaybackData { it.copy(gestureSeekAmount = value, isGestureSeeking = value != null) }
    }

    fun updateIsSeeking(value: Boolean) {
        updatePlaybackData { it.copy(isSeeking = value) }
        if (!value) {
            updatePlaybackData { it.copy(thumbnailImage = null) }
        }
    }

    fun updateSeekAmount(amount: Int) {
        updatePlaybackData { it.copy(doubleTapSeekAmount = amount) }
    }

    fun updateSeekText(value: String?) {
        updatePlaybackData { it.copy(seekText = value) }
    }

    fun handleLeftDoubleTap() {
        when (leftDoubleTapGesture) {
            SingleActionGesture.None -> { }
            SingleActionGesture.Seek -> {
                leftSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Switch -> {
                nextEpisode(next = false)
            }
            SingleActionGesture.Custom -> {
                mpvCommand("keypress", CustomKeyCodes.DoubleTapLeft.keyCode)
            }
            SingleActionGesture.Screenshot -> { }
        }
    }

    fun handleCenterDoubleTap() {
        when (centerDoubleTapGesture) {
            SingleActionGesture.None -> { }
            SingleActionGesture.Seek -> { }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Switch -> { }
            SingleActionGesture.Custom -> {
                mpvCommand("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
            }
            SingleActionGesture.Screenshot -> { }
        }
    }

    fun handleRightDoubleTap() {
        when (rightDoubleTapGesture) {
            SingleActionGesture.None -> { }
            SingleActionGesture.Seek -> {
                rightSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Switch -> {
                nextEpisode(next = true)
            }
            SingleActionGesture.Custom -> {
                mpvCommand("keypress", CustomKeyCodes.DoubleTapRight.keyCode)
            }
            SingleActionGesture.Screenshot -> { }
        }
    }

    fun handleLongPress() {
        when (longPressGesture) {
            SingleActionGesture.None -> { }
            SingleActionGesture.Seek -> { }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Switch -> { }
            SingleActionGesture.Custom -> {
                uiData.value.primaryButton?.let { executeButton(it) }
            }
            SingleActionGesture.Screenshot -> {
                pause()
                setSheet(Sheets.Screenshot)
            }
        }
    }
    // AM (MEDIA_CONTROLS) -->
    fun handleMediaPrevious() {
        when (mediaPreviousGesture) {
            SingleActionGesture.None -> { }
            SingleActionGesture.Seek -> {
                leftSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Switch -> {
                nextEpisode(next = false)
            }
            SingleActionGesture.Custom -> {
                mpvCommand("keypress", CustomKeyCodes.MediaPrevious.keyCode)
            }
            SingleActionGesture.Screenshot -> { }
        }
    }

    fun handleMediaPlayPause() {
        when (mediaPlayPauseGesture) {
            SingleActionGesture.None -> { }
            SingleActionGesture.Seek -> { }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Switch -> { }
            SingleActionGesture.Custom -> {
                mpvCommand("keypress", CustomKeyCodes.MediaPlay.keyCode)
            }
            SingleActionGesture.Screenshot -> { }
        }
    }

    fun handleMediaNext() {
        when (mediaNextGesture) {
            SingleActionGesture.None -> { }
            SingleActionGesture.Seek -> {
                rightSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Switch -> {
                nextEpisode(next = true)
            }
            SingleActionGesture.Custom -> {
                mpvCommand("keypress", CustomKeyCodes.MediaNext.keyCode)
            }
            SingleActionGesture.Screenshot -> { }
        }
    }
    // <-- AM (MEDIA_CONTROLS)

    fun leftSeek() {
        if (playbackData.value.position > 0) {
            updatePlaybackData { it.copy(doubleTapSeekAmount = it.doubleTapSeekAmount - doubleTapToSeekDuration) }
        }
        updatePlaybackData { it.copy(isSeekingForwards = false) }
        seekBy(-doubleTapToSeekDuration)
        if (showSeekBar) showSeekBar()
    }

    fun rightSeek() {
        if (playbackData.value.position < playbackData.value.duration) {
            updatePlaybackData { it.copy(doubleTapSeekAmount = it.doubleTapSeekAmount + doubleTapToSeekDuration) }
        }
        updatePlaybackData { it.copy(isSeekingForwards = true) }
        seekBy(doubleTapToSeekDuration)
        if (showSeekBar) showSeekBar()
    }

    private fun showSeekText(isForward: Boolean, text: String) {
        updatePlaybackData {
            it.copy(
                seekText = text,
                isSeekingForwards = isForward,
                doubleTapSeekAmount = if (isForward) 1 else -1,
            )
        }
        if (showSeekBar) showSeekBar()
    }

    private fun seekToWithText(seekValue: Int, text: String?) {
        updatePlaybackData {
            it.copy(
                seekText = text,
                isSeekingForwards = seekValue > 0,
                doubleTapSeekAmount = seekValue - it.position,
            )
        }
        seekTo(seekValue)
        if (showSeekBar) showSeekBar()
    }

    private fun seekByWithText(value: Int, text: String?) {
        updatePlaybackData {
            it.copy(
                seekText = text,
                isSeekingForwards = value > 0,
                doubleTapSeekAmount = if ((value < 0 && it.doubleTapSeekAmount < 0) ||
                    it.position + value > it.duration
                ) {
                    0
                } else {
                    it.doubleTapSeekAmount + value
                },
            )
        }
        seekBy(value)
        if (showSeekBar) showSeekBar()
    }

    fun seekBy(offset: Int) {
        mpvCommand("seek", offset.toString(), if (smoothSeeking) "relative+exact" else "relative")
    }

    fun seekTo(position: Int) {
        if (position !in 0..playbackData.value.duration) return
        mpvCommand("seek", position.toString(), if (smoothSeeking) "absolute" else "absolute+keyframes")
    }

    fun selectChapter(index: Int) {
        setPropertyInt("chapter", index)
        dismissSheet()
        unpause()
    }

    private var lastThumbnailFetch = 0L

    fun updateSeekPos(pos: Float) {
        updatePlaybackData { it.copy(seekPosition = pos, isSeeking = true) }

        val thumbInfo = playbackData.value.thumbnailInfo ?: return
        val info = thumbInfo.tileInfo.lastOrNull { it.timeMs <= pos * 1000L }
        if (info != null) {
            val tileBitmap = thumbnailTileCache[info.imageIndex]
            if (tileBitmap != null) {
                createThumbnail(tileBitmap, info)
            } else {
                val now = System.currentTimeMillis()
                if (now - lastThumbnailFetch < 2.seconds.inWholeMilliseconds) return
                lastThumbnailFetch = now

                thumbnailFetchJob?.cancel()
                thumbnailFetchJob = viewModelScope.launchIO {
                    val source = stateData.value.currentSource as? AnimeHttpSource ?: return@launchIO

                    try {
                        val tileUrl = thumbInfo.imageTileUrls[info.imageIndex]
                        val bitmap = source.getImageTile(tileUrl)
                        if (bitmap != null) {
                            withUIContext {
                                thumbnailTileCache[info.imageIndex] = bitmap
                                createThumbnail(bitmap, info)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "Failed to fetch thumbnails tiles" }
                    }
                }
            }
        }
    }

    private fun createThumbnail(tileBitmap: Bitmap, tileInfo: TileInfo) {
        val thumbnail = Bitmap.createBitmap(tileBitmap, tileInfo.x, tileInfo.y, tileInfo.width, tileInfo.height)
        updatePlaybackData {
            it.copy(thumbnailImage = thumbnail.asImageBitmap())
        }
    }

    // === Aniyomi ===

    /** Persists progress when the activity is saved without a config change. */
    fun onSaveInstanceStateNonConfigurationChange() {
        val currentEpisode = stateData.value.currentEpisode ?: return
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(currentEpisode)
        }
    }

    override fun onCleared() {
        stateData.value.currentEpisode?.let {
            // AM (PRESERVE_POSITION_SETTING) -->
            // Per-second saves during playback keep last_second_seen live in the DB so
            // background/notification playback can resume correctly if the process dies.
            // On true exit though, only keep that position for an already-seen episode if
            // the user opted in to preserving watch position on seen episodes.
            if (it.seen && !playerPreferences.preserveWatchingPosition.get()) {
                it.last_second_seen = 0L
            }
            // <-- AM (PRESERVE_POSITION_SETTING)
            saveWatchingProgress(it)
            episodeToDownload?.let { toDownload ->
                downloadManager.addDownloadsToStartOfQueue(listOf(toDownload))
            }
        }

        super.onCleared()
    }

    /** Called each second; marks episode seen, updates tracking, and queues next-episode download. */
    fun onSecondReached(position: Int, isCasting: Boolean = false) {
        updatePlaybackData { it.copy(position = position) }
        if (uiData.value.isLoadingEpisode) return
        val currentEpisode = stateData.value.currentEpisode ?: return
        if (episodeId == -1L) return
        val duration = if (isCasting) castUiData.value.duration.toInt() else playbackData.value.duration
        if (duration == 0) return

        if (isCasting) {
            val chapter = castUiData.value.chapters.filter { it.start <= position }.maxByOrNull { it.start }
            if (chapter != castUiData.value.currentChapter) {
                castOnChapterChanged(chapter)
            }
        }

        // Set netflix-style timeout
        if (isCasting) {
            castUiData.value.netflixTimeout?.let { timeout ->
                if (timeout > 0) {
                    updateCastUiData { it.copy(netflixTimeout = timeout - 1) }
                } else {
                    castOnSkipIntro()
                }
            }
        } else {
            playbackData.value.netflixTimeout?.let { timeout ->
                if (timeout > 0) {
                    updatePlaybackData { it.copy(netflixTimeout = timeout - 1) }
                } else {
                    onSkipIntro()
                }
            }
        }

        // It's called seconds, but it's supposed to be in milliseconds. WTF?
        // AM (PRESERVE_POSITION_SETTING) -->
        // Skip the live position write for an already-seen episode when the user hasn't
        // opted in to preserving watch position on seen episodes - otherwise the very
        // first tick of a rewatch clobbers the "fully watched" position with a low one.
        if (!currentEpisode.seen || playerPreferences.preserveWatchingPosition.get()) {
            currentEpisode.last_second_seen = position.toLong() * 1000L
        }
        // <-- AM (PRESERVE_POSITION_SETTING)
        currentEpisode.total_seconds = duration.toLong() * 1000L

        episodePosition = position.toLong()
        val shouldTrack = !stateData.value.incognitoMode || stateData.value.hasTrackers
        if (position >= duration * progress && shouldTrack) {
            viewModelScope.launchNonCancellable {
                updateEpisodeProgressOnComplete(currentEpisode)
            }
        }

        saveWatchingProgress(currentEpisode)

        val inDownloadRange = position.toDouble() / duration > 0.35
        if (inDownloadRange) {
            downloadNextEpisodes()
        }
    }

    private suspend fun updateEpisodeProgressOnComplete(currentEp: Episode) {
        currentEp.seen = true
        // Reset resume position now that it's finished, otherwise it stays near the
        // "seen" threshold (close to the end) for anything reading it later.
        currentEp.last_second_seen = 0L
        updateTrackEpisodeSeen(currentEp)
        deleteEpisodeIfNeeded(currentEp)
        // Persist explicitly - not guaranteed to run after onSecondReached()'s own call.
        saveWatchingProgress(currentEp)

        val markDuplicateAsSeen = libraryPreferences.markDuplicateSeenEpisodeAsSeen.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_EPISODE_SEEN_EXISTING)
        if (!markDuplicateAsSeen) return

        val duplicateUnseenEpisodes = stateData.value.currentPlaylist
            .mapNotNull { episode ->
                if (
                    !episode.seen &&
                    episode.isRecognizedNumber &&
                    episode.episode_number == currentEp.episode_number
                ) {
                    EpisodeUpdate(id = episode.id!!, seen = true)
                } else {
                    null
                }
            }
        updateEpisode.awaitAll(duplicateUnseenEpisodes)

        // AM (SYNC) -->
        val isSyncEnabled = syncPreferences.isSyncEnabled()
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        if (isSyncEnabled && syncTriggerOpt.syncOnEpisodeSeen) {
            SyncDataJob.startNow(context)
        }
        // <-- AM (SYNC)
    }

    private fun updateTrackEpisodeSeen(episode: Episode) {
        if (basePreferences.incognitoMode.get() || !stateData.value.hasTrackers) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val anime = stateData.value.currentAnime ?: return

        viewModelScope.launchNonCancellable {
            trackEpisode.await(context, anime.id, episode.episode_number.toDouble())
        }
    }

    fun saveCurrentEpisodeWatchingProgress() {
        stateData.value.currentEpisode?.let {
            saveWatchingProgress(it)
        }
    }

    /** Called on episode change or activity pause. */
    private fun saveWatchingProgress(episode: Episode) {
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(episode)
            saveEpisodeHistory(episode)
        }
    }

    /** Saves [episode] progress if not in incognito mode, or has at least one tracker. */
    private suspend fun saveEpisodeProgress(episode: Episode) {
        val stateData = stateData.value
        if (!stateData.incognitoMode || stateData.hasTrackers) {
            // AM (RECENT_EPISODE_POSITIONS) -->
            // currentPlaylist is loaded once per player session and never refreshed, so our
            // in-memory seen can go stale in EITHER direction if it's toggled elsewhere
            // while this player stays alive (e.g. from the anime screen during PIP). The
            // `version` column bumps on every real seen/bookmark/last_second_seen change
            // (see the episodes.sq trigger), so use it to detect a change we don't know
            // about and defer to the DB's seen instead of blindly overwriting it.
            val fresh = getEpisode.await(episode.id!!)
            if (fresh != null && fresh.version != episode.version) {
                episode.seen = fresh.seen
                episode.bookmark = fresh.bookmark
                episode.version = fresh.version
            }
            val willChangeTrackedFields = fresh != null && (
                episode.seen != fresh.seen ||
                    episode.bookmark != fresh.bookmark ||
                    episode.last_second_seen != fresh.lastSecondSeen
                )
            // <-- AM (RECENT_EPISODE_POSITIONS)
            updateEpisode.await(
                EpisodeUpdate(
                    id = episode.id!!,
                    seen = episode.seen,
                    bookmark = episode.bookmark,
                    fillermark = episode.fillermark,
                    lastSecondSeen = episode.last_second_seen,
                    totalSeconds = episode.total_seconds,
                ),
            )
            // AM (RECENT_EPISODE_POSITIONS) -->
            // Mirror the DB trigger's version bump locally so our next save's staleness
            // check compares against what the DB will actually have, not last tick's.
            if (willChangeTrackedFields) {
                episode.version = fresh!!.version + 1
            }
            // <-- AM (RECENT_EPISODE_POSITIONS)
            // AM (SYNC) -->
            val isSyncEnabled = syncPreferences.isSyncEnabled()
            val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
            if (isSyncEnabled && syncTriggerOpt.syncOnEpisodeOpen && episode.last_second_seen >= 1L) {
                SyncDataJob.startNow(context)
            }
            // <-- AM (SYNC)
        }
    }

    /** Saves [episode] last-seen history if not in incognito mode. */
    private suspend fun saveEpisodeHistory(episode: Episode) {
        if (!stateData.value.incognitoMode) {
            val episodeId = episode.id!!
            val seenAt = Date()
            upsertHistory.await(
                HistoryUpdate(episodeId, seenAt),
            )
        }
    }

    fun bookmarkEpisode(episodeId: Long?, bookmarked: Boolean) {
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    bookmark = bookmarked,
                ),
            )
        }
    }

    fun fillermarkEpisode(episodeId: Long?, fillermarked: Boolean) {
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    fillermark = fillermarked,
                ),
            )
        }
    }

    private fun downloadNextEpisodes() {
        if (downloadAheadAmount == 0) return
        val anime = stateData.value.currentAnime ?: return

        val currentPlaylist = stateData.value.currentPlaylist
        val currentPlaylistIndex = stateData.value.currentPlaylistIndex

        // Only download ahead if current + next episode are already downloaded (avoids jank)
        if (currentPlaylistIndex == currentPlaylist.lastIndex) return
        val currentEpisode = stateData.value.currentEpisode ?: return

        val nextEpisode = currentPlaylist[currentPlaylistIndex + 1]
        val episodesAreDownloaded =
            EpisodeLoader.isDownload(currentEpisode.toDomainEpisode()!!, anime) &&
                EpisodeLoader.isDownload(nextEpisode.toDomainEpisode()!!, anime)

        viewModelScope.launchIO {
            if (!episodesAreDownloaded) {
                return@launchIO
            }
            val episodesToDownload = getNextEpisodes.await(anime.id, nextEpisode.id!!)
                .take(downloadAheadAmount)
            downloadManager.downloadEpisodes(anime, episodesToDownload)
        }
    }

    /** Enqueues the nth-back episode for deletion if the delete-after-seen option is enabled. */
    private fun deleteEpisodeIfNeeded(chosenEpisode: Episode) {
        val currentEpisodePosition = stateData.value.currentPlaylist.indexOf(chosenEpisode)
        val removeAfterSeenSlots = downloadPreferences.removeAfterSeenSlots.get()
        val episodeToDelete = stateData.value.currentPlaylist.getOrNull(
            currentEpisodePosition - removeAfterSeenSlots,
        )
        episodeToDownload = null

        if (removeAfterSeenSlots != -1 && episodeToDelete != null) {
            enqueueDeleteSeenEpisodes(episodeToDelete)
        }
    }

    /** Enqueues [episode] for deletion (persisted across process death) until [deletePendingEpisodes] runs. */
    private fun enqueueDeleteSeenEpisodes(episode: Episode) {
        if (!episode.seen) return
        val anime = stateData.value.currentAnime ?: return
        viewModelScope.launchNonCancellable {
            downloadManager.enqueueEpisodesToDelete(listOf(episode.toDomainEpisode()!!), anime)
        }
    }

    /** Deletes all pending episodes in the background; errors are ignored. */
    fun deletePendingEpisodes() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingEpisodes()
        }
    }

    sealed class SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult()
        class Error(val error: Throwable) : SaveImageResult()
    }

    fun setAsArt(artType: ArtType, imageStream: () -> InputStream) {
        val anime = stateData.value.currentAnime ?: return
        val episode = stateData.value.currentEpisode ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                when (artType) {
                    ArtType.Cover -> anime.editCover(Injekt.get(), imageStream())
                    ArtType.Background -> anime.editBackground(Injekt.get(), imageStream())
                    ArtType.Thumbnail -> episode.editThumbnail(anime, Injekt.get(), imageStream())
                }

                if (anime.isLocal() || anime.favorite) {
                    SetAsArt.Success
                } else {
                    SetAsArt.AddToLibraryFirst
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to set art" }
                SetAsArt.Error
            }
            _eventFlow.emit(Event.SetArtResult(result, artType))
        }
    }

    /**
     * Copies the screenshot to internal storage first (source formats vary, some need
     * decompressing) and shares it. Only the last shared image is kept.
     */
    fun shareImage(imageStream: () -> InputStream) {
        val anime = stateData.value.currentAnime ?: return
        val pos = playbackData.value.position

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val seconds = Utils.prettyTime(pos)
        val filename = generateFilename(anime, seconds) ?: return

        try {
            viewModelScope.launchIO {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Screenshot(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                _eventFlow.emit(Event.ShareImage(uri, seconds))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /** Saves the screenshot to the pictures directory, with a share/delete notification. */
    fun saveImage(imageStream: () -> InputStream) {
        val anime = stateData.value.currentAnime ?: return
        val pos = playbackData.value.position

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val seconds = Utils.prettyTime(pos)
        val filename = generateFilename(anime, seconds) ?: return

        val relativePath = DiskUtil.buildValidFilename(anime.title)

        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Screenshot(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Pictures(relativePath),
                    ),
                )
                notifier.onComplete(uri)
                _eventFlow.emit(Event.SavedImage(SaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                _eventFlow.emit(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    // TODO: Make use of nodes instead of saving to cache
    fun takeScreenshot(showSubtitles: Boolean): InputStream? {
        val filename = context.cacheDir.path + "/${System.currentTimeMillis()}_mpv_screenshot_tmp.png"
        val subtitleFlag = if (showSubtitles) "subtitles" else "video"

        mpvCommand("screenshot-to-file", filename, subtitleFlag)
        val tempFile = File(filename).takeIf { it.exists() } ?: return null
        val newFile = File("${context.cacheDir.path}/mpv_screenshot.png")

        newFile.delete()
        tempFile.renameTo(newFile)
        return newFile.takeIf { it.exists() }?.inputStream()
    }

    private fun generateFilename(
        anime: Anime,
        timePos: String,
    ): String? {
        val episode = stateData.value.currentEpisode ?: return null
        val filenameSuffix = " - $timePos"
        return DiskUtil.buildValidFilename(
            "${anime.title} - ${episode.name}".takeBytes(
                DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
            ),
        ) + filenameSuffix
    }

    // === Skip intro ===

    fun onChapterChanged(chapterIndex: Int?) {
        if (chapterIndex == null) {
            updateStateData { it.copy(currentChapter = null) }
            return
        }

        val chapterList = mpv.getPropertyNode("chapter-list")?.toObject<List<ChapterNode>>(json)
            ?: emptyList()
        val chapter = if (chapterIndex == -1) {
            ChapterNode(
                time = 0.0f,
                "",
            )
        } else {
            chapterList.getOrNull(chapterIndex) ?: return
        }
        updateStateData { it.copy(currentChapter = chapter.toSegment()) }

        if (!introSkipEnabled) return
        val chapterType = chapter.chapterType

        if (chapterType == ChapterType.Other) {
            updateUiData { it.copy(skipIntroText = null) }
            updatePlaybackData { it.copy(netflixTimeout = null) }
        } else {
            if (netflixStyle) {
                // show a toast with the seconds before the skip
                viewModelScope.launch {
                    _eventFlow.emit(
                        Event.ToastString(
                            "Skip Intro: ${context.stringResource(
                                AYMR.strings.player_aniskip_dontskip_toast,
                                chapter.chapterTitle,
                                defaultWaitingTime,
                            )}",
                        ),
                    )
                }
                updateUiData { it.copy(skipIntroText = context.stringResource(AYMR.strings.player_aniskip_dontskip)) }
                updatePlaybackData { it.copy(netflixTimeout = defaultWaitingTime) }
            } else if (autoSkip) {
                skipIntro(chapter.chapterTitle)
            } else {
                updateSkipIntroButton(chapterType)
            }
        }
    }

    private fun skipIntro(chapterName: String) {
        mpvCommand("add", "chapter", "1")
        showSeekText(true, context.stringResource(AYMR.strings.player_intro_skipped, chapterName))
    }

    private fun updateSkipIntroButton(chapterType: ChapterType) {
        val skipButtonString = chapterType.getStringRes()
        val skipIntroText = skipButtonString?.let { s ->
            context.stringResource(
                AYMR.strings.player_skip_action,
                context.stringResource(s),
            )
        }

        updateUiData { it.copy(skipIntroText = skipIntroText) }
        updateCastUiData { it.copy(skipIntroText = skipIntroText) }
    }

    fun onSkipIntro() {
        val chapterIndex = mpv.getPropertyInt("chapter") ?: return
        val chapterList = mpv.getPropertyNode("chapter-list")?.toObject<List<ChapterNode>>(json)
            ?: emptyList()
        val chapter = chapterList.getOrNull(chapterIndex) ?: return

        if ((playbackData.value.netflixTimeout ?: 0) > 0 && netflixStyle) {
            updatePlaybackData { it.copy(netflixTimeout = null) }
            updateSkipIntroButton(chapter.chapterType)
            return
        }

        updatePlaybackData { it.copy(netflixTimeout = null) }
        skipIntro(chapter.chapterTitle)
    }

    fun getAnimeSkipIntroLength(): Int {
        val default = gesturePreferences.defaultIntroLength.get()
        val anime = stateData.value.currentAnime ?: return default
        val skipIntroLength = anime.skipIntroLength
        val skipIntroDisable = anime.skipIntroDisable
        return when {
            skipIntroDisable -> 0
            skipIntroLength <= 0 -> default
            else -> anime.skipIntroLength
        }
    }

    fun setAnimeSkipIntroLength(skipIntroLength: Long) {
        val anime = stateData.value.currentAnime ?: return
        if (!anime.favorite) return
        if (skipIntroLength == getAnimeSkipIntroLength().toLong()) return
        viewModelScope.launchIO {
            setAnimeViewerFlags.awaitSetSkipIntroLength(anime.id, skipIntroLength)
            val newAnime = getAnime.await(anime.id)
            updateStateData { it.copy(currentAnime = newAnime) }
        }
    }

    /** AniSkip response for this episode; only works if tracking is enabled. */
    suspend fun aniSkipResponse(playerDuration: Int?): List<TimeStamp>? {
        val animeId = stateData.value.currentAnime?.id ?: return null
        var malId: Long?
        val episodeNumber = stateData.value.currentEpisode?.episode_number?.toInt() ?: return null
        if (getTracks.await(animeId).isEmpty()) {
            logcat(LogPriority.DEBUG) { "AniSkip: No tracks found for anime $animeId" }
            return null
        }

        getTracks.await(animeId).forEach { track ->
            val tracker = trackerManager.get(track.trackerId)
            malId = when (tracker) {
                is MyAnimeList -> track.remoteId
                is Anilist -> AniSkipApi().getMalIdFromAL(track.remoteId)
                else -> null
            }
            val duration = playerDuration ?: return null
            return malId?.let {
                AniSkipApi().getResult(it.toInt(), episodeNumber, duration.toLong())
            }
        }
        return null
    }

    // === Misc ===

    /** Starts a sleep timer; cancels the current one if [seconds] < 1. */
    fun startTimer(seconds: Int) {
        timerJob?.cancel()
        updatePlaybackData { it.copy(remainingTime = seconds) }
        if (seconds < 1) return
        timerJob = viewModelScope.launch {
            for (time in seconds downTo 0) {
                updatePlaybackData { it.copy(remainingTime = time) }
                delay(1.seconds)
            }
            setPropertyBoolean("pause", true)
            _eventFlow.emit(Event.ToastResource(AYMR.strings.toast_sleep_timer_ended))
        }
    }

    // === Data ===
    @Stable
    data class PlayerStateData(
        val isCasting: Boolean = false,
        val isLoadingCasting: Boolean = false,
        val isErrorCasting: Boolean = false,
        val isStopped: Boolean = false,
        val hasTrackers: Boolean = false,
        val incognitoMode: Boolean = false,
        val currentPlaylist: List<Episode> = emptyList(),
        val currentPlaylistIndex: Int = -1,
        val hasPreviousEpisode: Boolean = false,
        val hasNextEpisode: Boolean = false,
        val isEpisodeOnline: Boolean = false,
        val currentEpisode: Episode? = null,
        val currentAnime: Anime? = null,
        val currentSource: AnimeSource? = null,
        val currentVideo: Video? = null,
        val videoHeight: Int = 0,
        val videoWidth: Int = 0,
        val maxVolume: Int,
        val volumeBoostCap: Int? = null,
        val hasLoadedTracks: Boolean = false,
        val hasLoadedSubs: Boolean = false,
        val hasLoadedAudio: Boolean = false,
        val chapters: List<Segment> = emptyList(),
        val currentChapter: Segment? = null,
        val aniskipChapters: List<TimeStamp> = emptyList(),
        val subtitleTracks: List<TrackNode> = emptyList(),
        val audioTracks: List<TrackNode> = emptyList(),
        val externalSubtitleTracks: List<MpvVideoTrack.External> = emptyList(),
        val externalAudioTracks: List<MpvVideoTrack.External> = emptyList(),
        val hosterList: List<Hoster> = emptyList(),
        val hosterState: List<HosterState> = emptyList(),
        val isPipAvailable: Boolean = false,
    )

    @Stable
    data class PlayerUiData(
        val isLoadingHosters: Boolean = false,
        val isLoadingEpisode: Boolean = false,
        val previousPauseState: Boolean? = false,
        val hosterExpandedList: List<Boolean> = emptyList(),
        val selectedHosterVideoIndex: Pair<Int, Int> = Pair(-1, -1),
        val mediaTitle: String = "",
        val animeTitle: String = "",
        val controlsShown: Boolean = true,
        val statusBarShown: Boolean = false,
        val seekBarShown: Boolean = true,
        val isControlsLocked: Boolean = false,
        val playerUpdate: PlayerUpdates = PlayerUpdates.None,
        val isBrightnessSliderShown: Boolean = false,
        val isVolumeSliderShown: Boolean = false,
        val sheetShown: Sheets = Sheets.None,
        val panelShown: Panels = Panels.None,
        val dialogShown: Dialogs = Dialogs.None,
        val dismissSheet: Boolean = false,
        val fontList: List<String> = emptyList(),
        val customButtons: List<CustomButton> = emptyList(),
        val primaryButtonTitle: String = "",
        val primaryButton: CustomButton? = null,
        val skipIntroText: String? = null,

        // Prefs
        val reduceMotion: Boolean = false,
        val playerTimeToDisappearMs: Int = 4000,
        val swapVolumeAndBrightness: Boolean = false,
        val boostCap: Int = 30,
        val displayVolumeAsPercentage: Boolean = true,
        val showLoadingCircle: Boolean = true,
        val invertDuration: Boolean = false,
        val smoothSeeking: Boolean = false,
        val autoPlayEnabled: Boolean = false,
        val showChapterIndicator: Boolean = true,
        val playerSpeedPref: Float = 1f,
        val bottomPlayerButtons: List<BottomPlayerButton?> = emptyList(),
        val enableCast: Boolean = false,
    )

    @Stable
    data class PlayerPlaybackData(
        val paused: Boolean = false,
        val position: Int = 0,
        val duration: Int = 0,
        val currentVolume: Int,
        val currentBrightness: Float,
        val currentOrientation: Int? = null,
        val isSeeking: Boolean = false,
        val isGestureSeeking: Boolean = false,
        val seekPosition: Float = 0f,
        val thumbnailImage: ImageBitmap? = null,
        val thumbnailInfo: ThumbnailInfo? = null,
        val seekText: String? = null,
        val doubleTapSeekAmount: Int = 0,
        val isSeekingForwards: Boolean = false,
        val gestureSeekAmount: Pair<Int, Int>? = null,
        val remainingTime: Int = 0,
        val netflixTimeout: Int? = null,
    )

    sealed interface PlayerEvent {
        data object ChangeAspect : PlayerEvent
        data class ChangeSpeed(val value: Float) : PlayerEvent
        data object CycleRotation : PlayerEvent
        data object EnterPip : PlayerEvent
        data class ExecuteCustomButton(val long: Boolean) : PlayerEvent
        data class LockControls(val lock: Boolean) : PlayerEvent
        data class NextEpisode(val next: Boolean) : PlayerEvent
        data object PlayPause : PlayerEvent
        data class Seek(val position: Int) : PlayerEvent
        data class SeekFinished(val position: Int) : PlayerEvent
        data class SetAutoPlay(val value: Boolean) : PlayerEvent
        data class SetPanel(val panel: Panels) : PlayerEvent
        data class SetSheet(val sheet: Sheets) : PlayerEvent
        data class ShowBrightnessSlider(val show: Boolean) : PlayerEvent
        data object ShowEpisodeDialog : PlayerEvent
        data class ShowPlayerUpdate(val update: PlayerUpdates) : PlayerEvent
        data class ShowVolumeSlider(val show: Boolean) : PlayerEvent
        data object SkipIntro : PlayerEvent
        data object ToggleDurationTimer : PlayerEvent
    }

    sealed interface Event {
        data object EnterPip : Event
        data class EpisodeTitle(val name: String) : Event
        data object Finish : Event
        data class InitialEpisodeError(val error: Throwable) : Event
        data class SavedImage(val result: SaveImageResult) : Event
        data class SetArtResult(val result: SetAsArt, val artType: ArtType) : Event
        data class SetKeyboard(val show: Boolean) : Event
        data class ShareImage(val uri: Uri, val seconds: String) : Event
        data class ToastResource(val stringRes: StringResource) : Event
        data class ToastString(val string: String) : Event
        data object ToggleKeyboard : Event
    }
}
