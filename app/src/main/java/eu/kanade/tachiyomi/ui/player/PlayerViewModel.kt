package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import animiru.domain.player.interactor.TrackSelect
import animiru.domain.player.model.ArtType
import animiru.domain.player.model.BottomPlayerButton
import animiru.domain.player.model.CustomKeyCodes
import animiru.domain.player.model.PlayerOrientation
import animiru.domain.player.model.QueueCleaning
import animiru.domain.player.model.SetAsArt
import animiru.domain.player.model.SingleActionGesture
import animiru.domain.player.model.VideoAspect
import animiru.domain.player.model.VideoAspectOverride
import animiru.domain.player.service.AudioPreferences
import animiru.domain.player.service.DecoderPreferences
import animiru.domain.player.service.GesturePreferences
import animiru.domain.player.service.PlayerPreferences
import animiru.domain.player.service.SubtitlePreferences
import animiru.feature.cast.CastProxyServerService
import aniyomi.core.common.torrent.TorrentPreferences
import aniyomi.core.common.torrent.TorrentServerApi
import aniyomi.core.common.torrent.TorrentServerUtils
// AM (MERGED_SOURCES) -->
import aniyomi.domain.episode.repository.EpisodeNameRepository
// <-- AM (MERGED_SOURCES)
// AM (CUSTOM_EPISODE_ORDER) -->
import aniyomi.domain.order.interactor.GetEpisodeOrder
import aniyomi.domain.queue.interactor.AddToQueue
import aniyomi.domain.queue.interactor.TrimQueue
import aniyomi.domain.queue.repository.QueueRepository
import aniyomi.domain.season.model.EntrySeason
// <-- AM (CUSTOM_EPISODE_ORDER)
import com.yubyf.truetypeparser.TTFFile
import dev.icerock.moko.resources.StringResource
import dev.vivvvek.seeker.Segment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connection.SyncPreferences
import eu.kanade.domain.episode.model.toDbEpisode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.TileInfo
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.media.MediaCache
import eu.kanade.tachiyomi.data.media.ResolvedVideoCache
import eu.kanade.tachiyomi.data.media.MediaProxyServer
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.data.cache.BackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.connection.syncmiru.SyncDataJob
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.database.models.isRecognizedNumber
import eu.kanade.tachiyomi.data.database.models.toDomainEpisode
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.model.LocalSourceTarget
import eu.kanade.tachiyomi.data.player.service.HttpServerService
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.torrent.service.TorrentServerService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
// AM (MERGED_SOURCES) -->
import eu.kanade.tachiyomi.source.ExternalStreamSource
import eu.kanade.tachiyomi.source.MergedSource
// <-- AM (MERGED_SOURCES)
import eu.kanade.tachiyomi.ui.anime.EpisodeShufflePreferences
import eu.kanade.tachiyomi.ui.anime.shuffledWithinSeasons
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.cast.CastDialog
import eu.kanade.tachiyomi.ui.player.cast.CastSheet
import eu.kanade.tachiyomi.ui.player.cast.CastUiData
import eu.kanade.tachiyomi.ui.player.components.HosterState
import eu.kanade.tachiyomi.ui.player.components.MAX_BRIGHTNESS
import eu.kanade.tachiyomi.ui.player.components.MIN_BRIGHTNESS
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
import eu.kanade.tachiyomi.ui.player.mpv.loadFileWithHwdecGuard
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
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.anime.model.toDomainAnime
import mihon.domain.source.interactor.UpdateAnimeFromRemote
import mihon.domain.library.model.search.QueryNode
import mihon.feature.library.matches
import animiru.domain.episode.interactor.GetEpisodeTags
import mihon.feature.library.EpisodeSearchFields
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.cast.CastEvent
import tachiyomi.cast.CastManager
import tachiyomi.cast.CastManagerEvent
import tachiyomi.cast.domain.TrackInformation
import tachiyomi.cast.domain.VideoInformation
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.anime.interactor.GetFavorites
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.custombutton.interactor.GetCustomButtons
import tachiyomi.domain.custombutton.model.CustomButton
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.Episode as DomainEpisode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.service.getEpisodeSort
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.image.LocalBackgroundManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalEpisodeThumbnailManager
import tachiyomi.source.local.isLocal
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class PlayerViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val json: Json,
    private val mpvFactory: MPVPlayer.Factory,

    private val getAnime: GetAnime,
    private val getNextEpisodes: GetNextEpisodes,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    // AM (EXTERNAL_PREFERS_LIBRARY) -->
    private val getAnimeByUrlAndSourceId: GetAnimeByUrlAndSourceId,
    private val getFavorites: GetFavorites,
    private val downloadProvider: DownloadProvider,
    // <-- AM (EXTERNAL_PREFERS_LIBRARY)
    // AM (PLAYBACK_QUEUE): mergeChildRepository was here only to re-derive the
    // playlist's owning entries, which GetEpisodeOrder now reports itself.
    // AM (CUSTOM_EPISODE_ORDER) -->
    private val getEpisodeOrder: GetEpisodeOrder,
    // AM (EPISODE_TAGS)
    private val getEpisodeTags: GetEpisodeTags,
    // <-- AM (CUSTOM_EPISODE_ORDER)
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val getIncognitoState: GetIncognitoState,

    private val updateAnime: UpdateAnime,
    // AM (PLAYER_ADD_TO_LIBRARY) -->
    private val setAnimeCategories: SetAnimeCategories,
    private val addTracks: AddTracks,
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime,
    // <-- AM (PLAYER_ADD_TO_LIBRARY)
    // AM (LIVE_EPISODE_NAMES)
    private val episodeNameRepository: EpisodeNameRepository,
    private val upsertHistory: UpsertHistory,
    private val updateEpisode: UpdateEpisode,
    // AM (RECENT_EPISODE_POSITIONS) -->
    private val getEpisode: GetEpisode,
    // AM (PLAYBACK_QUEUE) -->
    private val queueRepository: QueueRepository,
    private val addToQueue: AddToQueue,
    private val trimQueue: TrimQueue,
    // <-- AM (PLAYBACK_QUEUE)
    // <-- AM (RECENT_EPISODE_POSITIONS)
    private val trackEpisode: TrackEpisode,
    private val setAnimeViewerFlags: SetAnimeViewerFlags,
    private val downloadCache: DownloadCache,
    private val coverCache: CoverCache,
    private val backgroundCache: BackgroundCache,

    private val imageSaver: ImageSaver,
    private val downloadManager: DownloadManager,
    // AM (MEDIA_CACHE) -->
    private val networkService: NetworkHelper,
    // <-- AM (MEDIA_CACHE)
    private val sourceManager: SourceManager,
    private val storageManager: StorageManager,
    private val trackerManager: TrackerManager,
    private val episodeLoader: EpisodeLoader,
    private val hosterLoader: HosterLoader,
    private val aniSkipApi: AniSkipApi,

    private val torrentServerApi: TorrentServerApi,
    private val torrentServerUtils: TorrentServerUtils,
    private val torrentPreferences: TorrentPreferences,

    private val basePreferences: BasePreferences,
    private val episodeShufflePreferences: EpisodeShufflePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
    private val trackPreferences: TrackPreferences,
    private val playerPreferences: PlayerPreferences,
    decoderPreferences: DecoderPreferences,
    private val gesturePreferences: GesturePreferences,
    private val audioPreferences: AudioPreferences,
    private val subtitlePreferences: SubtitlePreferences,
    private val getCustomButtons: GetCustomButtons,
    private val trackSelect: TrackSelect,
    private val audioManager: AudioManager,
    brightnessManager: BrightnessManager,
    private val coverManager: LocalCoverManager,
    private val backgroundManager: LocalBackgroundManager,
    private val thumbnailManager: LocalEpisodeThumbnailManager,
    // AM (SYNC) -->
    private val syncPreferences: SyncPreferences,
    // <-- AM (SYNC)
    // AM --> (CAST)
    internal val castManager: CastManager,
    private val videoInformation: VideoInformation,
    // <-- AM (CAST)
    // AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
    private val recentEpisodePositionManager: RecentEpisodePositionManager,
    // <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)
    // AM (EXTERNAL_FOLDER_GRANT) -->
    // Registering a granted folder as a local source entry is the whole point of
    // the grant, so these are the local source's own two persistence steps - the
    // same ones a library refresh uses - rather than anything player-specific.
    private val storagePreferences: StoragePreferences,
    private val localSourceFileSystem: LocalSourceFileSystem,
    private val networkToLocalAnime: NetworkToLocalAnime,
    private val updateAnimeFromRemote: UpdateAnimeFromRemote,
    // <-- AM (EXTERNAL_FOLDER_GRANT)
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(PlayerViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): PlayerViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): PlayerViewModel
    }

    val videoOutput = if (decoderPreferences.gpuNext.get()) "gpu-next" else "gpu"

    // AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX) -->
    // Check the same-process holder synchronously before constructing a new
    // MPVPlayer at all - see PlayerMediaHolder's class doc comment for the full
    // reasoning. If a live holder already has an adopted (canonical) player, this
    // reuses that exact object instead of building a brand-new native mpv context
    // that bindToService()'s adopt() call would just discard as an orphan moments
    // later. Falls back to constructing a genuinely new MPVPlayer, exactly as
    // before, whenever no live holder/adopted player exists yet - a true fresh
    // session, where there's nothing to reuse.
    private val reusableHolder = PlayerMediaHolder.current?.takeIf { it.hasAdoptedPlayer }
    private var _player = reusableHolder?.player ?: mpvFactory.create(videoOutput)
    // AM (REUSED_PLAYER_SYNC_FIX) -->
    // Exposed for PlayerActivity.onNewIntent() to check: this fresh ViewModel's
    // own local bookkeeping (currentPlaylist, etc.) starts empty regardless of
    // whether the underlying player object was reused - needsInit() still
    // correctly says "this ViewModel needs initializing" in that case. But if the
    // reused player's own holder already reports the same target anime/episode,
    // the actual video content is already correct and playing - only the DB-
    // derived bookkeeping needs syncing, not a full mpv reload. See
    // syncSessionStateFromDb().
    // AM (REUSED_PLAYER_TARGET_MISMATCH_FIX) -->
    // var, not val: init() below can correct this to false once the actual
    // target anime/episode is known and turns out not to match what was reused -
    // see that fix for why.
    var wasPlayerReusedFromLiveHolder = reusableHolder != null
    // <-- AM (REUSED_PLAYER_TARGET_MISMATCH_FIX)
    // <-- AM (REUSED_PLAYER_SYNC_FIX)
    // <-- AM (SYNCHRONOUS_HOLDER_LOOKUP_FIX)
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
                playerPreferences.playerBrightnessValue.get()
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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

    // AM (DUMMY_PIP_REAL_VIDEO_RECT) -->
    // Where mpv is ACTUALLY drawing the video inside the surface right now,
    // straight from its own osd-dimensions rather than derived from the video's
    // dimensions. The dummy pip's entry/exit morph needs this rect to scale the
    // right thing, and every derivation of it is wrong in cases that ship:
    // video-params/w,h are STORAGE dimensions (anamorphic content is drawn at a
    // different shape), rotation metadata turns the rect on its side, and the
    // Crop/Stretch aspect modes set panscan/video-aspect-override so the drawn
    // rect is not the video's aspect at all. mpv already knows the answer after
    // applying all of it, so nothing here recomputes it.
    // <-- AM (DUMMY_PIP_REAL_VIDEO_RECT)
    private val _videoOutputRect = MutableStateFlow<VideoOutputRect?>(null)
    val videoOutputRect = _videoOutputRect.asStateFlow()

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

        // AM (SHUFFLE_FOLLOWS_SEED) -->
        observeShuffleSeed()
        // <-- AM (SHUFFLE_FOLLOWS_SEED)

        // AM (PLAYBACK_QUEUE)
        observeQueue()
    }

    // AM (PLAYLIST_SEARCH_SCOPE) -->
    // There is deliberately no collector on PlaylistSearchScope. Every way a
    // search gets submitted is followed by starting an episode - from the
    // dialog that is changeEpisode(), from the entry list it is session setup -
    // and both build the playlist from the submitted query themselves. A
    // collector added nothing except a second writer racing them, and it also
    // meant submitting for one entry rebuilt a different entry's playlist
    // unscoped, because queryFor() returned empty for the one still playing.
    // <-- AM (PLAYLIST_SEARCH_SCOPE)

    // AM (SHUFFLE_FOLLOWS_SEED) -->
    /**
     * Keeps the playlist's order following the stored shuffle seed.
     *
     * currentPlaylist has one writer, setupEpisodeList, and it runs when a
     * session starts. Sessions here are service-owned and outlive the player
     * UI, so toggling shuffle from the entry screen while something is playing
     * changed the list - which recomputes from state - and left the playlist
     * built before the toggle. Play order kept following the old order for the
     * rest of the session.
     *
     * Compared against the seed the playlist was actually built with rather
     * than by dropping the first emission: changes() replays the current value
     * on subscribe, and a count-based skip would break the moment this
     * resubscribes or the session changes anime.
     */
    private fun observeShuffleSeed() {
        viewModelScope.launchIO {
            stateData
                .map { it.currentAnime?.id }
                .distinctUntilChanged()
                .collectLatest { animeId ->
                    if (animeId == null) return@collectLatest
                    episodeShufflePreferences.seed(animeId).changes().collectLatest { seed ->
                        if (seed != stateData.value.playlistShuffleSeed) {
                            rebuildPlaylistPreservingCurrent()
                        }
                    }
                }
        }
    }

    /**
     * Rebuilds the playlist under the episode that is playing. Nothing about
     * playback is touched - only which episode comes next changes, so the
     * index is re-anchored on the current episode's id rather than kept.
     */
    private suspend fun rebuildPlaylistPreservingCurrent() {
        val anime = stateData.value.currentAnime ?: return
        val playing = stateData.value.currentEpisode ?: return
        setupEpisodeList(anime)
        // AM (CONTENT_AGNOSTIC_SESSION) -->
        val queue = playbackQueue ?: return
        val playingId = playing.id ?: return
        val index = queue.indexOf(SessionKey.Library(animeId = anime.id, episodeId = playingId))
        if (index == -1) return
        updateStateData {
            it.copy(
                currentPlaylistIndex = index,
                hasPreviousEpisode = queue.hasPrevious(index),
                hasNextEpisode = queue.hasNext(index),
            )
        }
        // <-- AM (CONTENT_AGNOSTIC_SESSION)
    }
    // <-- AM (SHUFFLE_FOLLOWS_SEED)

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
        // AM (SERVICE_OWNED_VIEWMODEL) -->
        // Mirrors adopt(player) just below, one level up: a cold-started instance
        // built its own PlayerViewModel directly (see that property's own removal
        // notes in PlayerActivity), since no holder existed yet to reuse one from.
        // Registers it now that a holder genuinely exists - a no-op if some other
        // instance's ViewModel already got here first (adoptViewModel() only
        // accepts the first one, same as adopt() already does for the player).
        holder.adoptViewModel(this)
        // <-- AM (SERVICE_OWNED_VIEWMODEL)
        // AM (MEDIA_SESSION_UNIFIED_CALLBACK) -->
        // Was setupMediaSessionCallback() in PlayerActivity, swapped away to a
        // separate, simplified fallback implementation whenever no Activity was
        // considered "live" - see buildMediaSessionCallback()'s own doc comment
        // for why that swap (and the fallback it swapped to) is gone entirely now.
        // Installed once, here, the first time this persistent ViewModel binds -
        // never needs reinstalling on Activity onStart()/onStop() the way the old
        // per-instance callback did, since this callback closes over `this`, the
        // same persistent object regardless of which (or whether any) Activity
        // instance is currently attached. A later reattach's own bindToService()
        // call rebuilding an equivalent callback is harmless, redundant work, not
        // incorrect - ensureMediaSession() just re-registers it.
        holder.ensureMediaSession(context, buildMediaSessionCallback()).apply {
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
        }
        // <-- AM (MEDIA_SESSION_UNIFIED_CALLBACK)
        // AM (AUDIO_FOCUS_ORPHAN_FIX) -->
        // Checked BEFORE adopt() - see PlayerMediaHolder.hasAdoptedPlayer's doc
        // comment for why the ordering matters here.
        val isFirstEverAdoption = !holder.hasAdoptedPlayer
        // <-- AM (AUDIO_FOCUS_ORPHAN_FIX)
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
            // AM (AUDIO_FOCUS_ORPHAN_FIX) -->
            // Only request audio focus for a genuinely first-ever adoption - see
            // MPVPlayer.requestAudioFocus()'s doc comment for the full reasoning. An
            // already-canonical player being reconfirmed here (isFirstEverAdoption
            // false, but still resolvedPlayer === player because there was nothing
            // to discard) already holds focus from when it was originally
            // constructed and must not request it again.
            if (isFirstEverAdoption) {
                player.requestAudioFocus()
            }
            // <-- AM (AUDIO_FOCUS_ORPHAN_FIX)
        }
        wirePlayerFlows()
        syncHolderSessionState()
        _playerReady.value = true
    }

    // AM (MEDIA_SESSION_UNIFIED_CALLBACK) -->
    /**
     * Was PlayerActivity.setupMediaSessionCallback()'s inner `callback` object,
     * moved here unchanged in behavior - only the receiver changed, from
     * PlayerActivity's `viewModel.xxx()` to this ViewModel's own `xxx()`
     * directly. This is now the ONLY MediaSession.Callback this app ever
     * installs: PlayerMediaHolder's separate fallbackCallback (and everything
     * that only existed to swap to/from it - restoreFallbackCallback(),
     * skipToAdjacentEpisode(), performBackgroundSkipLoad()) is gone. That
     * fallback existed because the old callback closed over an Activity
     * instance's own soon-to-be-dead ViewModel; this one closes over `this`,
     * the same persistent, Service-owned ViewModel regardless of whether any
     * Activity is currently attached at all - there's no state left where a
     * live UI is needed for this callback to safely do its job.
     *
     * The one genuinely Activity-only action - Stop needing to visually close
     * the player screen if one happens to be open - no longer calls finish()
     * on a held Activity reference at all. It emits the exact same Event.Finish
     * already used elsewhere for this (see PlayerActivity's existing eventFlow
     * collector) - a live Activity reacts and closes itself; if none exists,
     * the emission is simply consumed by nothing, which is correct.
     */
    fun buildMediaSessionCallback(): MediaSession.Callback {
        val previousAction = gesturePreferences.mediaPreviousGesture.get()
        val playAction = gesturePreferences.mediaPlayPauseGesture.get()
        val nextAction = gesturePreferences.mediaNextGesture.get()

        return object : MediaSession.Callback() {
            override fun onPlay() {
                when (playAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {}
                    SingleActionGesture.PlayPause -> {
                        super.onPlay()
                        unpause()
                    }
                    SingleActionGesture.Custom -> {
                        mpvCommand("keypress", CustomKeyCodes.MediaPlay.keyCode)
                    }

                    SingleActionGesture.Switch -> {}
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onPause() {
                when (playAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {}
                    SingleActionGesture.PlayPause -> {
                        super.onPause()
                        pause()
                    }
                    SingleActionGesture.Custom -> {
                        mpvCommand("keypress", CustomKeyCodes.MediaPlay.keyCode)
                    }

                    SingleActionGesture.Switch -> {}
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onSkipToPrevious() {
                when (previousAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {
                        leftSeek()
                    }
                    SingleActionGesture.PlayPause -> {
                        pauseUnpause()
                    }
                    SingleActionGesture.Custom -> {
                        mpvCommand("keypress", CustomKeyCodes.MediaPrevious.keyCode)
                    }

                    SingleActionGesture.Switch -> nextEpisode(next = false)
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onSkipToNext() {
                when (nextAction) {
                    SingleActionGesture.None -> {}
                    SingleActionGesture.Seek -> {
                        rightSeek()
                    }
                    SingleActionGesture.PlayPause -> {
                        pauseUnpause()
                    }
                    SingleActionGesture.Custom -> {
                        mpvCommand("keypress", CustomKeyCodes.MediaNext.keyCode)
                    }

                    SingleActionGesture.Switch -> nextEpisode(next = true)
                    SingleActionGesture.Screenshot -> {}
                }
            }

            override fun onStop() {
                super.onStop()
                // AM (MEDIASESSION_STOP_SAFETY_FIX) -->
                // An external "stop" transport command (Bluetooth/AVRCP, a system
                // media widget, etc.) reaching this callback is meant to behave
                // like the notification's own Stop button - pause, stop the
                // Service, and end the session for real. context.stopService()
                // works identically via Application context, no Activity needed.
                // finish() on a specific Activity instance is replaced by
                // Event.Finish, per this function's own doc comment above.
                // <-- AM (MEDIASESSION_STOP_SAFETY_FIX)
                pause()
                context.stopService(PlayerBackgroundPlaybackService.newIntent(context))
                viewModelScope.launch { _eventFlow.emit(Event.Finish) }
            }
        }
    }
    // <-- AM (MEDIA_SESSION_UNIFIED_CALLBACK)

    // AM (DUPLICATE_ACTIVITY_REINIT_FIX) -->
    // Publishes this instance's current anime/episode onto the holder so a LATER
    // duplicate instance's needsInit() call can recognize "this exact session is
    // already live" instead of reloading. Called both here (once the holder is known -
    // covers the case where binding finishes after the anime/episode are already set)
    // and from setupEpisode() (every episode switch, not just the initial one - without
    // that, needsInit() would keep matching whatever episode the session originally
    // started on, long after playback has moved past it).
    //
    // AM (MEDIASESSION_SINGLE_WRITER_FIX) -->
    // Also publishes animeTitle/episodeTitle now, not just the ids - this is THE single
    // choke point for every episode switch, live-Activity or not, so it's the correct
    // (and only) place to keep the holder's title fields current. PlayerMediaHolder's own
    // periodic timer and artwork flow are now the sole writers of MediaSession
    // metadata/notification text (see their own MEDIASESSION_SINGLE_WRITER_FIX comments) -
    // this function feeding them fresh titles the instant a switch happens, regardless of
    // whether an Activity exists to notice, is what replaces the several separate
    // Activity-side pushes that used to race against them.
    private fun syncHolderSessionState() {
        val anime = stateData.value.currentAnime ?: return
        val episode = stateData.value.currentEpisode ?: return
        // AM (SHARED_SESSION_SYNC_FIX) -->
        // Was its own separate mediaHolder?.updateState { it.copy(...) } call, built
        // independently from - and once genuinely out of sync with - the equivalent
        // call in PlayerMediaHolder's own performBackgroundSkipLoad(). Both now go
        // through PlayerMediaHolder.syncSessionState() (see its own doc comment) -
        // one place both paths write these fields from, not two.
        //
        // episode.id is nullable here specifically because currentEpisode is the
        // legacy eu.kanade.tachiyomi.data.database.models.Episode type (a
        // pre-insertion DB entity can genuinely lack an id) - the domain Episode
        // type the background path uses doesn't have this nullability at all. A
        // real, currently-playing episode should always have one; bailing out
        // rather than force-unwrapping if it somehow doesn't.
        // <-- AM (SHARED_SESSION_SYNC_FIX)
        val episodeId = episode.id ?: return
        // AM (MERGED_NOTIFICATION_ARTWORK_FIX) -->
        // The artwork fallback follows the EPISODE's owner, not the library
        // entry: on a merged entry currentAnime is the MergedSource parent, so
        // every episode of every child fell back to the parent's cover - one
        // image for the whole playlist, which is exactly what the cover-as-
        // thumbnail complaint was. currentEpisodeAnime is the child the playing
        // episode actually came from (see its own doc comment - thumbnails are
        // named there as one of the things that must read it), so its cover is
        // the right stand-in when the episode has no thumbnail of its own.
        // Titles deliberately stay on the parent: the notification names the
        // library entry the user started, not the child source behind it.
        // <-- AM (MERGED_NOTIFICATION_ARTWORK_FIX)
        val episodeAnime = stateData.value.currentEpisodeAnime ?: anime
        mediaHolder?.syncSessionState(
            sessionKey = SessionKey.Library(animeId = anime.id, episodeId = episodeId),
            animeTitle = anime.title,
            episodeTitle = displayNameOf(episode),
            animeThumbnailUrl = episodeAnime.thumbnailUrl,
            episodePreviewUrl = episode.preview_url,
        )
    }
    // <-- AM (MEDIASESSION_SINGLE_WRITER_FIX)
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

            // AM (DUMMY_PIP_REAL_VIDEO_RECT) -->
            // osd-dimensions is a node map; its sub-properties are observable
            // individually the same way video-params' are just below. w/h are the
            // surface mpv rendered into, ml/mt/mr/mb the letterbox margins left
            // around the video inside it - so the drawn rect is the surface minus
            // the margins, already accounting for display aspect, rotation,
            // panscan and any aspect override.
            combine(
                propFlow<Int>("osd-dimensions/w"),
                propFlow<Int>("osd-dimensions/h"),
                propFlow<Int>("osd-dimensions/ml"),
                propFlow<Int>("osd-dimensions/mt"),
                propFlow<Int>("osd-dimensions/mr"),
                propFlow<Int>("osd-dimensions/mb"),
            ) { values -> values }
                .onEach { values ->
                    val surfaceWidth = values[0] ?: return@onEach
                    val surfaceHeight = values[1] ?: return@onEach
                    if (surfaceWidth <= 0 || surfaceHeight <= 0) return@onEach
                    _videoOutputRect.value = VideoOutputRect(
                        surfaceWidth = surfaceWidth,
                        surfaceHeight = surfaceHeight,
                        marginLeft = values[2] ?: 0,
                        marginTop = values[3] ?: 0,
                        marginRight = values[4] ?: 0,
                        marginBottom = values[5] ?: 0,
                    )
                }
                .launchIn(viewModelScope)
            // <-- AM (DUMMY_PIP_REAL_VIDEO_RECT)

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

            // AM (PLAYBACK_STARVED_SIGNAL) -->
            // mpv's own buffering state, published for MediaProxyServer's throttle.
            // "Is a session streaming" was the wrong thing for it to watch: it says
            // the player is using the network, not that the player is in trouble.
            // This one says the player has run dry and is waiting, which is when a
            // download should get out of the way completely.
            propFlow<Boolean>("paused-for-cache")
                .onEach { PlayerMediaHolder.setPlaybackStarved(it == true) }
                .launchIn(viewModelScope)

            // AM (MEASURED_BANDWIDTH_SHARE) -->
            // demuxer-cache-time is how many seconds ahead the demuxer has read.
            // Against the configured target it becomes a fill fraction, which is
            // what the proxy's rate limiter allocates from. Local playback
            // publishes null - there is no stream to protect.
            propFlow<Float>("demuxer-cache-time")
                .onEach { readAhead ->
                    val target = playerPreferences.networkBufferSeconds.get().toFloat()
                    val streaming = PlayerMediaHolder.streamingPlaybackActive.value
                    PlayerMediaHolder.setPlaybackBufferFill(
                        if (!streaming || readAhead == null || target <= 0f) {
                            null
                        } else {
                            (readAhead / target).coerceIn(0f, 1f)
                        },
                    )
                }
                .launchIn(viewModelScope)
            // <-- AM (MEASURED_BANDWIDTH_SHARE)
            // <-- AM (PLAYBACK_STARVED_SIGNAL)

            propFlow<Boolean>("pause")
                .filterNotNull()
                .onEach { v ->
                    updatePlaybackData { it.copy(paused = v) }
                    // AM (STREAMING_PLAYBACK_SIGNAL) -->
                    // mpv's own property, so this covers every pause source -
                    // UI, MediaSession, PIP action, the freeze around a switch.
                    publishStreamingPlaybackState()
                    // <-- AM (STREAMING_PLAYBACK_SIGNAL)
                    _eventFlow.emit(Event.UpdateDiscordRPC(v))
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

    fun isPlayerForegroundSuspended(): Boolean {
        return player.isForegroundSuspended
    }

    fun setPlayerForegroundSuspended(value: Boolean) {
        player.isForegroundSuspended = value
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

    // AM (PROP_FLOW_CACHED) -->
    /**
     * One flow per property name, for the life of this ViewModel.
     *
     * All 32 call sites are inside composables and none of them remember the
     * result, so propFlow() ran on every recomposition - and every call went
     * through to the mpv layer to build a new flow and register a new property
     * observer for a property that was already being observed. Playing for a while
     * therefore accumulated observers that nothing ever removes, and every mpv
     * property change had to fan out across all of them: the app getting slower
     * the longer it ran, rather than a single allocation leaking.
     *
     * Cached here rather than by wrapping each call site in remember(), because
     * this fixes every existing call site and every future one, and a composable
     * that forgets the wrapper cannot reintroduce the problem.
     *
     * Safe to key on the name alone: no property in this codebase is requested at
     * two different types, so a cached flow's element type always matches what the
     * caller asks for.
     */
    @PublishedApi
    internal val propFlowCache = java.util.concurrent.ConcurrentHashMap<String, StateFlow<*>>()

    inline fun <reified T> propFlow(name: String): StateFlow<T?> {
        @Suppress("UNCHECKED_CAST")
        return propFlowCache.getOrPut(name) { mpv.propFlow<T>(name) } as StateFlow<T?>
    }
    // <-- AM (PROP_FLOW_CACHED)

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
     *
     * AM (AUDIO_BLIP_FIX_2) -->
     * hasAttachedSurface checks player.hasAttachedSurfaceBefore, not just
     * isSurfaceAttached - the player's SurfaceTexture is created once and
     * persists for its whole lifetime (see MPVPlayer.persistentSurfaceTexture),
     * so once a real surface has ever attached, a valid target exists
     * continuously from then on, foreground or backgrounded either way.
     * isSurfaceAttached alone would answer "is a TextureView currently
     * displaying it right now", which isn't the question that matters for
     * hwdec init - hasAttachedSurfaceBefore (a one-way flag, set once and
     * never unset) is. This means loading a new episode while already
     * backgrounded doesn't need to disable hwdec at all, except in the
     * genuinely narrow window before the surface has ever been attached even
     * once (very early cold start, before Compose has rendered the TextureView
     * for the first time).
     *
     * REVERT NOTE: tonight's session briefly changed this to isSurfaceAttached
     * alone, theorizing the surface genuinely goes invalid while backgrounded.
     * A separate, related fix (forcing MpvSurface to re-attach on Compose
     * recomposition) turned out to be actively harmful - confirmed by testing,
     * it froze playback on a static frame after reopening from the
     * notification, because TextureView survives visibility changes by design
     * and that recomposition was never a real detach to begin with. Reverting
     * both together: this flag's original premise (isSurfaceAttached rarely if
     * ever actually goes false during backgrounding, so this line was likely
     * already correct/inert either way) hasn't been disproven, only the separate
     * reattach change has - so restoring the original, unmodified logic here is
     * the safe choice pending an actual confirmed cause for the underlying bug,
     * which is still open.
     * <-- AM (AUDIO_BLIP_FIX_2)
     */
    private fun loadFile(url: String, options: String) {
        // AM (SHARED_LOAD_FILE_FIX) -->
        // See loadFileWithHwdecGuard's own doc comment - this used to duplicate that
        // exact logic inline; now both this and PlayerMediaHolder's background-skip
        // equivalent call the same shared function.
        // <-- AM (SHARED_LOAD_FILE_FIX)
        mpv.loadFileWithHwdecGuard(
            url,
            options,
            hasAttachedSurface = isSurfaceAttached || player.hasAttachedSurfaceBefore,
        )
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
            PlayerEvent.EnterPipFromBack -> {
                viewModelScope.launch {
                    _eventFlow.emit(Event.EnterPipFromBack)
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
                // AM (EXTERNAL_FOLDER_GRANT) -->
                // The folder dialog exists to ask for the grant, so it opens only
                // where the grant is on offer. Routing on externalSource instead
                // sent every external session there, including streams, which
                // have nothing to grant - leaving a dialog with nothing in it and
                // no way through to the list.
                val dialog = if (canGrantExternalFolder()) Dialogs.ExternalFolder else Dialogs.EpisodeList
                updateUiData { it.copy(dialogShown = dialog) }
                // <-- AM (EXTERNAL_FOLDER_GRANT)
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
            // AM (MEDIASESSION_EVENT_DRIVEN_FIX) -->
            // No-ops here deliberately - PlayerMediaHolder collects these two
            // directly off the same eventFlow itself (see its own
            // MEDIASESSION_EVENT_DRIVEN_FIX) to drive MediaSession pushes. This
            // ViewModel-level dispatcher has no reason to also react to them.
            // <-- AM (MEDIASESSION_EVENT_DRIVEN_FIX)
            is MPVPlayer.Event.PauseChanged -> {}
            MPVPlayer.Event.PlaybackRestart -> {}
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
                    showMessage(AMMR.strings.cast_server_load_failed)
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

    // AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
    /**
     * Session-local convenience for recently-departed episodes, so an accidental
     * next/previous click doesn't lose your place - even for an already-seen episode we
     * otherwise don't persist rewatch position for. Doesn't cover the currently-open
     * episode; that's already handled by the normal live-tracking/resume path.
     *
     * Backed by [RecentEpisodePositionManager] - a single app-process-wide, DB-backed
     * cache shared with PlayerMediaHolder, rather than a ViewModel-local map. See that
     * class's own doc comment for why (persists across process death; no more keeping
     * two independent in-memory copies in sync by hand).
     */

    private fun rememberRecentEpisodePosition() {
        val episode = stateData.value.currentEpisode ?: return
        val episodeId = episode.id ?: return
        val positionMs = (episodePosition ?: 0L) * 1000L
        val durationMs = playbackData.value.duration.toLong() * 1000L
        // AM (MERGED_SOURCES) -->
        // Keyed on the episode alone - see RecentEpisodePositionManager.consume.
        recentEpisodePositionManager.remember(episodeId, positionMs, durationMs)
        // <-- AM (MERGED_SOURCES)
    }
    // <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)

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

    // AM (CONTENT_AGNOSTIC_SESSION) -->
    // Takes a [SessionKey] rather than two longs. The holder comparison below is
    // pure identity - it asks whether the Service is already holding the session
    // being requested - so it compares keys and never unpacks them. The local
    // comparison still reads currentAnime/currentEpisode because this
    // ViewModel's own bookkeeping is still library-shaped; that is what the
    // PlaybackSource/Queue extraction takes care of next.
    fun needsInit(key: SessionKey): Boolean {
        val local = stateData.value
        if (key is SessionKey.Library &&
            local.currentAnime?.id == key.animeId &&
            local.currentEpisode?.id == key.episodeId
        ) {
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
        //
        // AM (NEEDSINIT_STALE_HOLDER_FIX) -->
        // That reasoning assumed the ORIGINAL, still-alive instance's ViewModel was the
        // one actually holding real playlist/episode data, with this being a redundant
        // second instance safe to leave uninitialized. That stopped holding once
        // backgrounding started genuinely destroying the Activity (see
        // isBackgroundPlayTransitionFinish) - holder.state's animeId/episodeId (written
        // by syncHolderSessionState(), never cleared except in release()) now survives
        // completely unchanged across that destruction, and the notification's reopen
        // intent is built from that same pair (see
        // PlayerBackgroundPlaybackService.animeId/episodeId). So on the now-common case -
        // a single, genuinely fresh instance reattaching after the old one was fully
        // destroyed - this branch was matching every single time and returning "no init
        // needed" for a ViewModel that had never actually loaded anything: currentPlaylist
        // stayed permanently empty, hosters never loaded, and playback state fell back to
        // stale defaults. Requiring this instance to already have a non-empty playlist
        // restricts the fast path back to its original, genuine intent - skipping
        // redundant reinit only when THIS ViewModel instance has real, already-loaded data
        // to skip reloading - while a truly empty, freshly constructed instance always
        // falls through to a real init(), regardless of what the holder separately
        // remembers from a prior instance's session.
        val holderState = mediaHolder?.state?.value
        if (holderState?.sessionKey == key && local.currentPlaylist.isNotEmpty()) {
            return false
        }
        // <-- AM (NEEDSINIT_STALE_HOLDER_FIX)
        // <-- AM (DUPLICATE_ACTIVITY_REINIT_FIX)
        return true
    }

    // AM (REUSED_PLAYER_SYNC_FIX) -->
    /**
     * Populates local playlist/episode bookkeeping from the DB without touching
     * mpv's loaded file at all - for the case where the underlying player was
     * already reused from a live [PlayerMediaHolder] (see
     * [wasPlayerReusedFromLiveHolder] / SYNCHRONOUS_HOLDER_LOOKUP_FIX) and already
     * has the correct content loaded and playing. Running the full [init]/
     * loadFile() pipeline in that case would needlessly reload an already-correct
     * file from scratch - a genuine `loadfile` mpv command, not a no-op, causing
     * an audible interruption on every reopen after backgrounding even though
     * nothing about the actual video content needed to change.
     *
     * Deliberately duplicates [init]'s DB-bookkeeping steps rather than
     * refactoring that function to share code with this one - [init] is an
     * existing, carefully-hardened function with a lot of accumulated
     * correctness fixes; this avoids any risk of regressing it for the cases it
     * already handles correctly, at the cost of a little duplication.
     *
     * Returns false (safe to fall back to the full [init] pipeline) if anything
     * about the DB state doesn't resolve cleanly - never partially applies state.
     */
    suspend fun syncSessionStateFromDb(animeId: Long, targetEpisodeId: Long): Boolean {
        return try {
            val anime = getAnime.await(animeId) ?: return false
            sourceManager.isInitialized.first { it }
            val source = sourceManager.getOrStub(anime.source)
            val incognito = getIncognitoState.await(anime.source)

            updateStateData { it.copy(currentAnime = anime, currentSource = source, incognitoMode = incognito) }
            updateUiData { it.copy(animeTitle = anime.title) }
            episodeId = targetEpisodeId

            setupTrackers(anime.id)
            setupEpisodeList(anime)

            val episode = stateData.value.currentPlaylist.firstOrNull { it.id == episodeId } ?: return false
            setupEpisode(episode)

            val skipIntroLength = getAnimeSkipIntroLength()
            updateCastUiData { it.copy(skipIntroLength = skipIntroLength.toLong()) }

            val parentTitle = anime.parentId?.let { getAnime.await(it)?.title } ?: ""
            setPropertyString("user-data/current-anime/anime-title", anime.title)
            setPropertyString("user-data/current-anime/parent-title", parentTitle)
            setPropertyInt("user-data/current-anime/intro-length", skipIntroLength)
            setPropertyString(
                "user-data/current-anime/category",
                getCategories.await(anime.id).joinToString { it.name },
            )
            true
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }
    // <-- AM (REUSED_PLAYER_SYNC_FIX)

    data class InitResult(
        val hosterList: List<Hoster>?,
        val videoIndex: Pair<Int, Int>,
        val position: Long?,
    )

    class ExceptionWithStringResource(
        message: String,
        val stringResource: StringResource,
    ) : Exception(message)

    // AM (LOAD_FAILURE_NOT_FATAL) -->
    /**
     * Writes the mpv `user-data/current-anime` table for [anime]. Reads
     * stateData for the skip-intro length, so currentAnime must already BE
     * [anime] by the time this runs.
     */
    private suspend fun writeAnimeUserData(anime: Anime) {
        val skipIntroLength = getAnimeSkipIntroLength()
        updateCastUiData { it.copy(skipIntroLength = skipIntroLength.toLong()) }

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
    }

    /**
     * Everything a cross-anime switch overwrites that says WHICH session this
     * is. Captured before [switchToAnime] touches anything, so a switch whose
     * load fails can put the live session back as it was instead of leaving it
     * half-switched - the old file still loaded and paused in mpv, wrapped in
     * the new anime's title, playlist, episode and hoster state.
     */
    private data class SessionSnapshot(
        val state: PlayerStateData,
        val ui: PlayerUiData,
        val cast: CastUiData,
        val wasPlaying: Boolean,
        val episodeId: Long,
        val hosterList: List<Hoster>?,
        val qualityIndex: Pair<Int, Int>,
        val episodePosition: Long?,
    )

    private fun captureSession() = SessionSnapshot(
        state = stateData.value,
        ui = uiData.value,
        cast = castUiData.value,
        wasPlaying = !playbackData.value.paused,
        episodeId = episodeId,
        hosterList = currentHosterList,
        qualityIndex = qualityIndex,
        episodePosition = episodePosition,
    )

    /**
     * Rolls the session back to [snapshot] after a failed switch.
     *
     * The state objects go back wholesale rather than field by field. Between
     * the capture and here the only writer is the switch itself - it runs
     * inside episodeChangeJob, with the player paused and no file loading - so
     * everything the snapshot holds is still true of what mpv actually has
     * loaded, and a hand-picked field list would silently stop covering
     * whatever gets added to PlayerStateData next.
     *
     * Not restored: the prefetched next episode, which switchToAnime dropped.
     * It belongs to this playlist and is still valid, but re-prefetching it is
     * cheap and getting it wrong is not.
     */
    private suspend fun restoreSession(snapshot: SessionSnapshot) {
        updateStateData { snapshot.state }
        updateUiData { snapshot.ui.copy(isLoadingEpisode = false, isLoadingHosters = false) }
        updateCastUiData { snapshot.cast.copy(isLoadingEpisode = false) }

        episodeId = snapshot.episodeId
        currentHosterList = snapshot.hosterList
        qualityIndex = snapshot.qualityIndex
        episodePosition = snapshot.episodePosition

        snapshot.state.currentAnime?.let { writeAnimeUserData(it) }
        snapshot.state.currentEpisode?.let {
            setPropertyDouble("user-data/current-anime/episode-number", it.episode_number.toDouble())
        }
        // setupEpisode() pinned the holder to the episode that was being switched
        // to; needsInit() reads that, so leaving it pointing at an episode this
        // session never loaded would make a later request for the restored one
        // no-op.
        syncHolderSessionState()

        if (snapshot.wasPlaying) unpause()
    }

    /**
     * Reports a load failure to the user instead of letting it leave a
     * coroutine through the uncaught handler and take the process with it.
     *
     * With nothing playable behind it (a fresh open) the Activity still
     * finishes - there is nothing to fall back to, which is what
     * setInitialEpisodeError() has always done. On top of a session that is
     * still playing it is reported and no more: destroying a working session
     * over a switch that did not happen is the same over-reaction
     * REOPEN_LOAD_FAILURE_PRESERVE_SESSION_FIX removed from the reopen path.
     *
     * [ExceptionWithStringResource] already names its own reason, so it is
     * shown as-is. Anything else is an opaque failure from the source - the 404
     * a deleted entry's page returns, a timeout, a layout change - and reads as
     * nothing to the user, so it becomes "No source found" and the real
     * exception goes to logcat.
     */
    private suspend fun reportLoadFailure(error: Throwable) {
        logcat(LogPriority.ERROR, error) { "Load failed" }
        showMessage((error as? ExceptionWithStringResource)?.stringResource ?: AMMR.strings.no_source_found)
    }

    /**
     * Shows a message to the user.
     *
     * Deliberately NOT an [Event]. The event channel has exactly one
     * subscriber in the codebase - PlayerActivity.onCreate() - and the
     * single-activity player never builds that Activity, so every message the
     * ViewModel raised was emitted into a MutableSharedFlow with no
     * subscribers, which discards it. Collecting it from the player host
     * would only narrow the hole: that composable exists while a session is
     * on screen, and a failure can land as a session ends or after it has.
     * Toasting on the injected application context depends on no UI being
     * alive at all, which is what "visible in and out of the player" needs.
     *
     * Known limit: Android does not display toasts over a real PIP window, so
     * a failure that lands while in system PIP is still logged and unseen.
     */
    private suspend fun showMessage(resource: StringResource, vararg args: Any) = withUIContext {
        context.toast(
            if (args.isEmpty()) context.stringResource(resource) else context.stringResource(resource, *args),
        )
    }

    private suspend fun showMessage(text: String) = withUIContext { context.toast(text) }
    // <-- AM (LOAD_FAILURE_NOT_FATAL)

    suspend fun init(
        animeId: Long,
        initialEpisodeId: Long,
        hostList: String,
        hostIndex: Int,
        vidIndex: Int,
        // AM (PLAYBACK_QUEUE)
        // See PlaybackRequest.continuesQueue - a resume leaves the queue's
        // order alone, where an open repositions what it opened.
        continuesQueue: Boolean = false,
    ): Pair<InitResult, Result<Boolean>> {
        val defaultResult = InitResult(currentHosterList, qualityIndex, null)
        if (!needsInit(SessionKey.Library(animeId, initialEpisodeId))) {
            return Pair(defaultResult, Result.success(true))
        }

        // AM (REUSED_PLAYER_TARGET_MISMATCH_FIX) -->
        // wasPlayerReusedFromLiveHolder only means "a live holder with an adopted
        // player existed at construction time" - the actual target wasn't known
        // yet then to check against. needsInit() passing above confirms this IS a
        // different anime/episode, so if the player was reused, it's still the
        // OTHER, still-playing session's real player, not a throwaway - sharing it
        // would load new content into a native mpv context that session depends
        // on. Build a genuinely fresh MPVPlayer instead. Must NOT .release() the
        // discarded one - it's still in use elsewhere.
        //
        // This only covers a DIFFERENT ViewModel instance's construction-time
        // reuse. The other case - THIS instance already alive and playing
        // something when a different anime is requested via onNewIntent()
        // redelivery - is handled separately, by a real teardown-and-relaunch in
        // PlayerActivity.onNewIntent() rather than a hot-swap here (swapping
        // _player in that case would leave the Service/holder still wired to the
        // old player object, since PlayerMediaHolder.adopt() only runs its setup
        // once per holder).
        if (wasPlayerReusedFromLiveHolder) {
            val liveState = PlayerMediaHolder.current?.state?.value
            if (liveState?.sessionKey != SessionKey.Library(animeId, initialEpisodeId)) {
                _player = mpvFactory.create(videoOutput)
                wasPlayerReusedFromLiveHolder = false
            }
        }
        // <-- AM (REUSED_PLAYER_TARGET_MISMATCH_FIX)

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
                // AM (PLAYBACK_QUEUE): opening something is "add it to the
                // queue and skip to it", so its block goes in after whatever
                // is playing - unless this is the queue being resumed, which
                // is the one case where the order is already what the user
                // asked for.
                setupEpisodeList(anime, repositionQueueBlock = !continuesQueue)

                val episode = stateData.value.currentPlaylist.firstOrNull { it.id == episodeId }
                    ?: throw ExceptionWithStringResource("No episode loaded", AYMR.strings.no_episode_loaded)

                // AM (MERGED_SOURCES) -->
                val (episodeAnime, episodeSource) = resolveEpisodeAnimeAndSource(anime, episode)
                    ?: throw ExceptionWithStringResource("No episode loaded", AYMR.strings.no_episode_loaded)
                updateStateData { it.copy(currentEpisodeAnime = episodeAnime, currentEpisodeSource = episodeSource) }
                // <-- AM (MERGED_SOURCES)

                setupEpisode(episode)

                // AM (LOAD_FAILURE_NOT_FATAL) -->
                // Extracted so a failed cross-anime switch can rewrite these for
                // the anime it rolls back to - see restoreSession().
                writeAnimeUserData(anime)
                // <-- AM (LOAD_FAILURE_NOT_FATAL)

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
                    // AM (CONTENT_AGNOSTIC_SESSION) -->
                    // Was a direct episodeLoader.getHosters() call. Same work,
                    // asked of the session's own source instead - MERGED_SOURCES'
                    // episodeAnime/episodeSource (the child, not the merge parent)
                    // are resolved above and handed over at construction, so the
                    // merged case is decided in one place rather than at each
                    // getHosters call site.
                    librarySourceFor(
                        episode = episode,
                        anime = episodeAnime,
                        source = episodeSource,
                        displayAnime = anime,
                    ).resolveStreams()
                    // <-- AM (CONTENT_AGNOSTIC_SESSION)
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

    // AM (PLAYBACK_QUEUE) -->
    /**
     * Writes [episodes] as [anime]'s block in the queue.
     *
     * [reposition] is an open: the block is placed directly after whatever is
     * playing - or first, when nothing is - through AddToQueue, which also
     * drops its previous position so re-opening moves rather than duplicates. Picking something to watch
     * interrupts the queue, so it goes next and the rest stays ahead of it,
     * rather than being appended behind everything already lined up.
     *
     * Without it the block is rewritten where it already sits, so a rebuild -
     * a shuffle-seed change, the queue changing elsewhere - cannot reorder the
     * queue under the user.
     */
    private suspend fun syncQueueBlock(anime: Anime, episodes: List<Episode>, reposition: Boolean) {
        val episodeIds = episodes.mapNotNull { it.id }
        if (episodeIds.isEmpty()) return

        val existing = queueRepository.getBlocks().filter { it.animeId == anime.id }
        if (reposition || existing.isEmpty()) {
            // currentEpisode is still the PREVIOUS episode here: the open path
            // sets the playlist up before setupEpisode switches to the new
            // one, which is exactly the anchor wanted. Null on a fresh
            // session, where appending is the only option anyway.
            addToQueue.awaitEpisodeIdsNext(
                anime = anime,
                episodeIds = episodeIds,
                afterEpisodeId = stateData.value.currentEpisode?.id,
            )
            return
        }
        // Several blocks for one entry is possible - it was queued, then
        // opened. The first keeps its place; the rest are duplicates an append
        // left behind, and they go.
        addToQueue.refreshBlock(existing.first().id, anime, episodeIds)
        existing.drop(1).forEach { queueRepository.removeBlock(it.id) }
    }

    /** The queue, in play order, as playlist entries. */
    private suspend fun queueEpisodes(): List<Episode> {
        return queueRepository.getItems()
            .mapNotNull { it.episodeId }
            .distinct()
            .mapNotNull { getEpisode.await(it)?.toDbEpisode() }
    }

    /**
     * The entries the queue draws on.
     *
     * Read off the queue rows directly: an item records the entry it was
     * queued as, so this needs no episode lookups.
     */
    private suspend fun queuedOwnerIds(): List<Long> {
        return queueRepository.getItems().mapNotNull { it.animeId }.distinct()
    }

    /** The queue's episode ids, in play order - see [PlayerStateData.playlistQueueIds]. */
    private suspend fun queueIds(): List<Long> {
        return queueRepository.getItems().mapNotNull { it.episodeId }.distinct()
    }

    /**
     * Keeps the playlist following the queue.
     *
     * The same shape as [observeShuffleSeed], and for the same reason: the
     * playlist is derived from the queue at setup time, sessions are
     * service-owned and outlive the player UI, so queueing something while
     * playback is running left the playlist built before the addition. Compared
     * against the ids the playlist was actually built with rather than by
     * counting emissions - the flow replays its current value on subscribe,
     * and a count-based skip would break the moment this resubscribes or the
     * session changes anime.
     *
     * [rebuildPlaylistPreservingCurrent] re-anchors the index on the episode
     * that is playing, so nothing about playback is touched: only what comes
     * next changes.
     */
    private fun observeQueue() {
        viewModelScope.launchIO {
            queueRepository.getItemsAsFlow()
                .map { items -> items.mapNotNull { it.episodeId }.distinct() }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    if (stateData.value.currentAnime == null) return@collectLatest
                    if (ids != stateData.value.playlistQueueIds) {
                        rebuildPlaylistPreservingCurrent()
                    }
                }
        }
    }
    // <-- AM (PLAYBACK_QUEUE)

    private suspend fun setupTrackers(animeId: Long) {
        val tracks = getTracks.await(animeId)
        updateStateData { it.copy(hasTrackers = tracks.isNotEmpty()) }
    }

    /**
     * Builds the session's play order.
     *
     * AM (PLAYBACK_QUEUE): the queue IS that order. This entry's own episodes
     * are written into the queue as its block - in the order worked out below,
     * which is the entry's sort, shuffle and filters exactly as before - and
     * the playlist is then read back from the queue so everything already
     * queued plays in one list with it.
     *
     * [repositionQueueBlock] separates opening something from rebuilding the
     * playlist under it. Opening is "add to the queue and skip to it", so the
     * block is placed after whatever is playing; a rebuild (a shuffle-seed
     * change, the queue changing elsewhere) must leave the block where it is
     * and only refresh its contents.
     */
    private suspend fun setupEpisodeList(anime: Anime, repositionQueueBlock: Boolean = false) {
        // AM (CUSTOM_EPISODE_ORDER) -->
        // One source of truth for playlist order - the merged union, an
        // ordinary entry's own episodes, and any custom order the user has
        // set, all resolved in GetEpisodeOrder. The playlist plays what it is
        // handed, in the order it is handed: re-sorting by plain episode
        // number would interleave a merged entry's seasons back together
        // (S1E1, S2E1, ...) and would discard a custom order outright.
        val isMerged = anime.source == MergedSource.ID
        // AM (SHUFFLE_FOLLOWS_SEED) -->
        // Read once and recorded in state below. The playlist is derived from
        // this value, so it has to be possible to tell later whether the
        // playlist still matches the stored seed.
        val shuffleSeed = episodeShufflePreferences.seed(anime.id).get()
        // <-- AM (SHUFFLE_FOLLOWS_SEED)
        // AM (PLAYBACK_QUEUE) -->
        // The queue's other entries are declared as contributors to this
        // resolve, so every per-episode fact it returns - display names and
        // the single-episode rule, covers, custom names, the owning entry
        // itself - already covers them. Nothing about queued episodes is
        // special-cased below or merged in afterwards.
        // Read before the write below, for the guard on the playing episode.
        val liveQueueIds = queueIds()
        val resolvedOrder = getEpisodeOrder.awaitResolved(anime, extraOwnerIds = queuedOwnerIds())
        // <-- AM (PLAYBACK_QUEUE)
        val rawEpisodes = resolvedOrder.episodes
        val isPreordered = resolvedOrder.isPreordered
        // Each episode of a merged entry belongs to a child, so download
        // checks must resolve the episode's real entry, not the merge parent.
        // AM (PLAYBACK_QUEUE): from the resolve rather than a second query of
        // the merge children, which is where this came from - and which could
        // never have known about a queued episode's entry.
        val ownerById = resolvedOrder.contributors
        // <-- AM (CUSTOM_EPISODE_ORDER)
        // AM (EPISODE_TAG_SEARCH) -->
        // One stub lookup per source, not per episode. Needed both by the
        // search scope below and by the state it is published into.
        val ownerSourceNames = ownerById.values
            .map { it.source }
            .distinct()
            .associateWith { sourceManager.getOrStub(it).name }
        // <-- AM (EPISODE_TAG_SEARCH)

        val episodes = rawEpisodes
            .let {
                // AM (CUSTOM_EPISODE_ORDER) -->
                // AM (EPISODE_NAMES): alphabetical sorts on the playlist row's
                // own label, the same names the entry screen lists, so the two
                // cannot disagree about what alphabetical means.
                if (isPreordered) {
                    it
                } else {
                    it.sortedWith(
                        getEpisodeSort(anime, sortDescending = false) { episode ->
                            resolvedOrder.displayNameByEpisodeId[episode.id] ?: episode.name
                        },
                    )
                }
                // <-- AM (CUSTOM_EPISODE_ORDER)
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    // AM (MERGED_SOURCES) -->
                    if (isMerged) {
                        filter {
                            val owner = ownerById[it.animeId] ?: anime
                            downloadCache.isEpisodeDownloaded(
                                it.name,
                                it.scanlator,
                                it.url,
                                // AM (CUSTOM_INFORMATION) -->
                                owner.ogTitle,
                                // <-- AM (CUSTOM_INFORMATION)
                                owner.source,
                                false,
                            )
                        }
                    } else {
                        filterDownloaded(anime, downloadCache)
                    }
                    // <-- AM (MERGED_SOURCES)
                } else {
                    this
                }
            }
            .map { it.toDbEpisode() }
            .let { sorted ->
                // Mirrors the episode list screen's shuffle via the same persisted
                // per-anime seed (EpisodeShufflePreferences), no explicit wiring needed.
                val seed = shuffleSeed
                if (seed == 0L) {
                    sorted
                } else {
                    // AM (SEASON_SCOPED_SHUFFLE) -->
                    sorted.shuffledWithinSeasons(
                        seed = seed,
                        idOf = { it.id ?: 0L },
                        seasonRankOf = { episode ->
                            episode.id
                                ?.let(resolvedOrder.seasonByEpisodeId::get)
                                ?.let(resolvedOrder.seasonRankByNumber::get)
                        },
                    )
                    // <-- AM (SEASON_SCOPED_SHUFFLE)
                }
            }

        // AM (PLAYLIST_SEARCH_SCOPE) -->
        // Read here, applied after the user filters below: the search is the
        // last thing that narrows the list, and the dialog needs the list as it
        // stands before it.
        val scopeQuery = PlaylistSearchScope.queryFor(anime.id)
        // <-- AM (PLAYLIST_SEARCH_SCOPE)

        // AM (PLAYBACK_QUEUE) -->
        // The episode this session is being set up for may
        // be a QUEUED one belonging to another entry, and `episodes` holds only
        // this entry's own. Reached by playing into the queue and then anything
        // rebuilding the playlist under it - a queue change, or a shuffle
        // toggle - and before this guard it threw.
        val selectedEpisode = episodes.find { it.id == episodeId }
        if (selectedEpisode == null && episodeId !in liveQueueIds) {
            error("Requested episode of id $episodeId not found in episode list")
        }
        // <-- AM (PLAYBACK_QUEUE)

        val filtered = episodes.filterNot {
            // AM (MERGED_SOURCES) -->
            val owner = ownerById[it.anime_id] ?: anime
            // <-- AM (MERGED_SOURCES)
            (anime.unseenFilterRaw == Anime.EPISODE_SHOW_SEEN && !it.seen) ||
                (anime.unseenFilterRaw == Anime.EPISODE_SHOW_UNSEEN && it.seen) ||
                (
                    anime.downloadedFilterRaw == Anime.EPISODE_SHOW_DOWNLOADED &&
                        !downloadManager.isEpisodeDownloaded(
                            it.name,
                            it.scanlator,
                            it.url,
                            // AM (CUSTOM_INFORMATION) -->
                            owner.ogTitle,
                            // <-- AM (CUSTOM_INFORMATION)
                            owner.source,
                        )
                    ) ||
                (
                    anime.downloadedFilterRaw == Anime.EPISODE_SHOW_NOT_DOWNLOADED &&
                        downloadManager.isEpisodeDownloaded(
                            it.name,
                            it.scanlator,
                            it.url,
                            // AM (CUSTOM_INFORMATION) -->
                            owner.ogTitle,
                            // <-- AM (CUSTOM_INFORMATION)
                            owner.source,
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

        // AM (PLAYBACK_QUEUE): only for an episode of this entry. A queued one
        // is appended by the tail below, and forcing it in here would list it
        // twice and inside the entry's own episodes rather than after them.
        if (selectedEpisode != null && filtered.all { it.id != episodeId }) {
            filtered += listOf(selectedEpisode)
        }

        // AM (EPISODE_TAGS): resolved once for the host, then used both by the
        // scope below and by the playlist dialog's own search.
        val episodeTags = getEpisodeTags.await(anime)

        // AM (PLAYLIST_SEARCH_SCOPE) -->
        // The submitted search, last. Derived here rather than handed a frozen
        // list of ids, so a rehost or the shuffle rebuild re-applies it without
        // knowing it exists.
        //
        // filtered is kept as the unscoped list: the dialog searches that, not
        // the scoped result. Searching what the scope already produced can only
        // narrow further - you could never broaden or change a filter, because
        // the episodes you were trying to reach were not in the list being
        // searched.
        val unscoped = filtered.toList()
        // AM (PLAYBACK_QUEUE) -->
        // This entry's block, then the whole queue as the playlist. The block
        // carries `unscoped` - filters applied, search scope not - so the
        // scope stays a live narrowing of the playlist below rather than
        // something baked into stored rows.
        //
        // Episodes of OTHER entries need no special handling to play: merged
        // entries already put foreign episodes in this list, and
        // resolveEpisodeAnimeAndSource resolves each one's real anime and
        // source from its own anime_id rather than from the session's. The
        // session keeps reporting the entry it was opened on, exactly as it
        // does while playing a merge child.
        // Clear-on-episode-selection empties the queue at exactly this point:
        // the user has picked an episode from an entry (which is what
        // repositionQueueBlock marks), so the queue starts again from it.
        // Navigating the player's own playlist does not come through here.
        if (repositionQueueBlock &&
            playerPreferences.queueCleaning.get() == QueueCleaning.CLEAR_ON_EPISODE_SELECTION
        ) {
            queueRepository.clear()
        }
        syncQueueBlock(anime, unscoped, reposition = repositionQueueBlock)
        val queuePlaylist = queueEpisodes()
        val playlistIds = queuePlaylist.mapNotNull { it.id }
        // <-- AM (PLAYBACK_QUEUE)
        // AM (PLAYBACK_QUEUE): over the queue playlist, not this entry's own
        // list - the scope narrows what plays, and what plays is the queue.
        val scoped = if (scopeQuery.isBlank()) {
            queuePlaylist
        } else {
            val queryNode = QueryNode.from(scopeQuery)
            val hostSourceName = sourceManager.getOrStub(anime.source).name
            queuePlaylist.filter { episode ->
                val owner = episode.anime_id?.let { ownerById[it] } ?: anime
                queryNode.matches(
                    EpisodeSearchFields(
                        displayName = episode.id
                            ?.let(resolvedOrder.displayNameByEpisodeId::get)
                            ?: episode.name,
                        episodeNumber = episode.episode_number.toDouble(),
                        scanlator = episode.scanlator,
                        anime = owner,
                        sourceName = ownerSourceNames[owner.source] ?: hostSourceName,
                        // AM (EPISODE_TAGS): the same resolved tags the entry
                        // screen matches on, so a playlist scope reads the same
                        // as the episode list it came from.
                        tags = episode.id?.let { episodeTags.byEpisodeId[it] }
                            ?: owner.genre.orEmpty(),
                    ),
                )
            }.let { scopedEpisodes ->
                // AM (PLAYBACK_QUEUE): selectedEpisode is nullable now, for a
                // session playing a QUEUED episode of another entry - see the
                // lookup above. Null means the playing episode is not one of
                // this entry's, so there is nothing for this fallback to
                // rescue from the scope filter: the queue tail carries it.
                when {
                    selectedEpisode == null -> scopedEpisodes
                    scopedEpisodes.any { episode -> episode.id == episodeId } -> scopedEpisodes
                    else -> scopedEpisodes + selectedEpisode
                }
            }
        }
        // <-- AM (PLAYLIST_SEARCH_SCOPE)

        // AM (CUSTOM_EPISODE_ORDER) -->
        // AM (SEASON_SCOPED_SHUFFLE) -->
        // The shuffle exclusion here is gone. It existed because a flat
        // shuffle crossed a season boundary on nearly every step, which would
        // have prompted constantly; shuffledWithinSeasons keeps seasons in
        // sequence and scrambles only within them, so boundaries are
        // monotonic again and the prompt fires once per season as it does
        // unshuffled.
        // <-- AM (SEASON_SCOPED_SHUFFLE)
        val playlistSeasonById = if (resolvedOrder.seasonNumbers.size <= 1) {
            emptyMap()
        } else {
            resolvedOrder.seasonByEpisodeId
        }
        updateStateData {
            it.copy(
                // AM (PLAYBACK_QUEUE) -->
                currentPlaylist = scoped,
                playlistQueueIds = playlistIds,
                // <-- AM (PLAYBACK_QUEUE)
                // AM (PLAYLIST_SEARCH_SCOPE) -->
                // AM (PLAYBACK_QUEUE): the queue before the scope narrows it -
                // the dialog searches this, so it searches everything queued.
                playlistUnscoped = queuePlaylist,
                // <-- AM (PLAYLIST_SEARCH_SCOPE)
                // AM (EPISODE_TAG_SEARCH) -->
                playlistOwnerByAnimeId = ownerById,
                playlistOwnerSourceNames = ownerSourceNames,
                // <-- AM (EPISODE_TAG_SEARCH)
                // AM (SHUFFLE_FOLLOWS_SEED) -->
                playlistShuffleSeed = shuffleSeed,
                // <-- AM (SHUFFLE_FOLLOWS_SEED)
                // AM (PLAYLIST_SEARCH_SCOPE) -->
                playlistSearchQuery = scopeQuery,
                // <-- AM (PLAYLIST_SEARCH_SCOPE)
                playlistSeasonById = playlistSeasonById,
                // AM (NAMED_SEASONS) -->
                playlistSeasons = resolvedOrder.seasons,
                // <-- AM (NAMED_SEASONS)
                // AM (EPISODE_NAMES) -->
                playlistEpisodeNames = resolvedOrder.displayNameByEpisodeId,
                // <-- AM (EPISODE_NAMES)
                // AM (EPISODE_TAGS)
                playlistEpisodeTags = episodeTags.byEpisodeId,
            )
        }
        // AM (LIVE_EPISODE_NAMES)
        observePlaylistNames(resolvedOrder.displayNameByEpisodeId, ownerById.keys.ifEmpty { setOf(anime.id) })
        // <-- AM (CUSTOM_EPISODE_ORDER)
    }

    // AM (CONTENT_AGNOSTIC_SESSION) -->
    // The "is this streaming or a local file" test moved onto PlaybackSource,
    // where it belongs: it is a fact about where the stream comes from, which
    // is the source's whole subject. Still returns null when the session has no
    // resolved episode anime/source yet, exactly as before.
    private fun isEpisodeOnline(episode: Episode): Boolean? {
        val currentState = stateData.value

        // AM (MERGED_SOURCES) -->
        val anime = currentState.currentEpisodeAnime ?: return null
        val source = currentState.currentEpisodeSource ?: return null
        // <-- AM (MERGED_SOURCES)
        val displayAnime = currentState.currentAnime ?: return null
        return librarySourceFor(
            episode = episode,
            anime = anime,
            source = source,
            displayAnime = displayAnime,
        ).isOnline()
    }

    // AM (CONTENT_AGNOSTIC_SESSION) -->
    /**
     * The current session's queue.
     *
     * Derived from state on each read rather than held, so it can never
     * disagree with the playlist the UI is showing - which is rebuilt whenever
     * the shuffle seed changes and re-scoped by playlist search. Returns null
     * before an entry is loaded, where there is no order to speak of.
     */
    private val playbackQueue: PlaybackQueue?
        get() {
            externalQueue?.let { return it }
            val state = stateData.value
            val animeId = state.currentAnime?.id ?: return null
            return LibraryPlaybackQueue(animeId, state.currentPlaylist.map { it.id })
        }
    // <-- AM (CONTENT_AGNOSTIC_SESSION)

    // AM (EXTERNAL_PLAYBACK) -->
    /**
     * The externally-opened video this session is playing, or null for an
     * ordinary library session.
     *
     * Deliberately NOT in [PlayerStateData]: every library feature in this
     * ViewModel already guards on `currentAnime`/`currentEpisode` being
     * non-null (there is not one force-unwrap among the 46 reads of
     * currentAnime), so an external session leaving both null makes trackers,
     * history, the episode list, prefetch, season advance and add-to-library
     * no-op on their own, without a single `if (isExternal)` added to any of
     * them. That is the payoff for steps 1-3: external playback is the absence
     * of library state, not a second mode running alongside it.
     */
    private var externalSource: ExternalPlaybackSource? = null

    /** The external session's order - see [playbackQueue]. */
    private var externalQueue: PlaybackQueue? = null

    // AM (EXTERNAL_FOLDER_GRANT) -->
    /** The opened file's own name, for finding it inside a granted folder. */
    private var externalFileName: String? = null

    /**
     * Publishes whether offering folder access would do anything.
     *
     * This was a plain getter read straight from the dialog, which Compose cannot
     * observe: it was evaluated once during composition - before loadExternal had
     * set externalSource - captured false and never recomposed, so the Grant
     * action never appeared and the dialog only ever offered OK. Anything the UI
     * decides on has to live in state that it collects.
     */
    private fun publishCanGrantExternalFolder() {
        // AM (EXTERNAL_FOLDER_GRANT_LOCAL_ONLY) -->
        // Only a file has a folder. The grant exists so the directory holding
        // the opened video can be listed for next/previous and registered as a
        // local source entry - a stream has no directory to pick, the picker
        // would open at nothing (externalFolderPickerStart is null for a
        // remote url, since realFilePath cannot resolve one), and whatever the
        // user chose could not contain the video anyway.
        //
        // isOnline() is the source's own answer to "is mpv reading a network
        // url", already used to drive the streaming-only affordances - so this
        // asks the existing question rather than re-deriving it from the
        // scheme here.
        updateStateData { it.copy(canGrantExternalFolder = canGrantExternalFolder()) }
    }

    /**
     * Whether a folder grant is worth offering for the session in hand.
     *
     * Only a file has a folder to grant. A stream has no directory to pick, and
     * a folder already granted has nothing left to ask for. Both the dialog's
     * Grant action and the choice of which dialog to open ask this, so the two
     * cannot disagree about what an external session is.
     */
    private fun canGrantExternalFolder(): Boolean =
        externalSource?.isOnline() == false && externalQueue !is ExternalFolderQueue
    // <-- AM (EXTERNAL_FOLDER_GRANT_LOCAL_ONLY)

    /**
     * Where the folder picker should open, or null when it cannot be worked out.
     *
     * ACTION_OPEN_DOCUMENT_TREE takes EXTRA_INITIAL_URI, which
     * ActivityResultContracts.OpenDocumentTree passes as its input - so the
     * picker can open already showing the folder the video is in, leaving the
     * user to confirm rather than navigate to a location the app already knows.
     *
     * Captured at load time, like [externalFileName], because deriving it needs
     * the file's real path and the launching app's grant may be gone by the time
     * the dialog is opened.
     */
    private var externalFolderPickerStart: Uri? = null

    /** Read by the UI to open the picker at the right place - see above. */
    val externalFolderPickerStartUri: Uri? get() = externalFolderPickerStart

    /**
     * A storage-provider document URI for [realPath]'s parent directory.
     *
     * The storage provider names a document `<volume>:<path within volume>`, so
     * this is a translation of a path already in hand rather than a lookup -
     * `/storage/emulated/0/Movies/x.mkv` becomes `primary:Movies`, and
     * `/storage/75D7-DC5F/Anime/x.mkv` becomes `75D7-DC5F:Anime`.
     *
     * Only a starting point for the picker: a wrong guess costs the user some
     * navigating, which is exactly what they faced before, so anything
     * unrecognised returns null rather than being forced into a shape.
     */
    private fun folderPickerStartFor(realPath: String): Uri? {
        val docId = parentDocumentIdFor(realPath) ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, docId)
        }.getOrNull()
    }

    /** The storage provider's document id for [realPath]'s parent, or null. */
    private fun parentDocumentIdFor(realPath: String): String? {
        val parent = realPath.substringBeforeLast('/', "").takeIf { it.isNotBlank() } ?: return null
        val (volume, relative) = when {
            parent.startsWith(PRIMARY_VOLUME_PATH) ->
                "primary" to parent.removePrefix(PRIMARY_VOLUME_PATH).trim('/')
            parent.startsWith("/storage/") -> {
                val rest = parent.removePrefix("/storage/")
                rest.substringBefore('/') to rest.substringAfter('/', "")
            }
            else -> return null
        }
        if (volume.isBlank()) return null
        return if (relative.isBlank()) "$volume:" else "$volume:$relative"
    }

    /**
     * The already-granted folder containing [realPath], or null when none is.
     *
     * A grant taken through [applyExternalFolderGrant] is persisted, so a later
     * video in the same folder - or anywhere under the same granted tree - needs
     * no second ask. Without this, "persisted" was only true in principle: every
     * load started from a single-item queue and the user had to grant the same
     * folder again.
     *
     * Any persisted READ tree whose document id is the folder's or an ancestor of
     * it counts, which is what makes a grant on a parent directory cover the
     * folders inside it. The app's own storage tree is one of these, so files
     * under it are covered too.
     */
    private fun persistedFolderUriFor(realPath: String): Uri? {
        val parentDocId = parentDocumentIdFor(realPath) ?: return null
        return context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .mapNotNull { permission ->
                val treeDocId = runCatching {
                    DocumentsContract.getTreeDocumentId(permission.uri)
                }.getOrNull() ?: return@mapNotNull null
                if (parentDocId != treeDocId && !parentDocId.startsWith("$treeDocId/")) {
                    return@mapNotNull null
                }
                runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(permission.uri, parentDocId)
                }.getOrNull()
            }
            .firstOrNull()
    }


    /**
     * Registers a folder the user has just granted as a local source entry.
     *
     * This is what the grant is for. The local source already turns a directory
     * of videos into an entry with episodes, already has a queue dialog for those
     * episodes, and already plays them - so a granted folder becomes one of its
     * entries and every one of those behaviours arrives with it. The session then
     * switches onto the episode the opened file now is, which is why the queue the
     * user sees afterwards is the real episode list and not a second one built for
     * external files.
     *
     * Three steps, in order, each the app's own: persist the tree (the same
     * persisted-tree mechanism the configured storage directory uses, not a new
     * permission), register it in [StoragePreferences.extraLocalFolders] so the
     * local source covers it from now on, and refresh it through
     * [UpdateAnimeFromRemote], which is the one path every other entry in the app
     * is filled by - cover and background extracted from the video, episode list
     * synced, episode thumbnails generated in the background.
     *
     * Falls back to the folder-queue behaviour if any of that does not come off -
     * an unlistable tree, a file that is not in the granted folder - so the grant
     * is never a dead end. next/previous work either way; what the fallback loses
     * is the episode list and everything else a library entry has.
     */
    fun applyExternalFolderGrant(treeUri: Uri) {
        val source = externalSource ?: return
        viewModelScope.launchNonCancellable {
            withIOContext {
                runCatching {
                    // Write as well as read, because a local source entry is
                    // written INTO its own folder: cover.jpg by LocalCoverManager,
                    // the background and the episode thumbnails the same way. The
                    // picker returns both flags, so nothing extra is asked of the
                    // user - a read-only grant just made every one of those writes
                    // fail and the entry came up with no artwork.
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.onFailure {
                    logcat(LogPriority.INFO, it) { "EXTERNAL_FOLDER could not persist grant for $treeUri" }
                }
            }

            updateUiData { it.copy(dialogShown = Dialogs.None) }

            // The switch is done here rather than inside the registration so that
            // a failure in it cannot land in the fallback below with the session
            // already half moved onto a library entry.
            val registered = registerGrantedFolderAsLocalEntry(
                treeUri = treeUri,
                resumeSeconds = playbackData.value.position.toLong(),
            )
            if (registered != null) {
                val (animeId, episodeId) = registered
                externalSource = null
                externalQueue = null
                publishCanGrantExternalFolder()
                switchToAnime(
                    animeId = animeId,
                    episodeId = episodeId,
                    hostList = "",
                    hostIndex = -1,
                    vidIndex = -1,
                )
                return@launchNonCancellable
            }

            val queue = withIOContext {
                ExternalFolderQueue.create(
                    context = context,
                    treeUri = treeUri,
                    openedUri = source.sessionKey.uri,
                    openedFileName = externalFileName,
                )
            }
            if (queue == null) {
                showMessage(AYMR.strings.no_available_videos)
                return@launchNonCancellable
            }

            externalQueue = queue
            publishCanGrantExternalFolder()
            val index = queue.indexOf(source.sessionKey)
            updateStateData {
                it.copy(
                    currentPlaylistIndex = index,
                    hasPreviousEpisode = queue.hasPrevious(index),
                    hasNextEpisode = queue.hasNext(index),
                )
            }
        }
    }

    /**
     * Scans [treeUri] in as a local source entry, returning its anime and episode.
     *
     * Returns null - leaving nothing registered behind it - whenever the folder
     * does not come out as an entry holding the file being played, which includes
     * the user having picked some other folder entirely.
     *
     * The playing position is written onto the episode row before returning - see
     * the note at that write.
     */
    // AM (EXTERNAL_STREAM_SOURCE) -->
    /**
     * The anime and episode ids now standing for [source], or null if the entry
     * could not be made.
     *
     * The url written on the entry is the stream url, and
     * [ExternalStreamSource.getEpisodeList] gives the entry one episode holding
     * that same url, so the ordinary episode-sync path produces the row and
     * nothing has to be remembered about this session for the episode to resolve
     * later.
     */
    private suspend fun registerStreamAsEntry(
        source: ExternalPlaybackSource,
        resumeSeconds: Long,
    ): Pair<Long, Long>? {
        val streamSource = sourceManager.get(ExternalStreamSource.ID) as? AnimeHttpSource ?: return null
        val entry = networkToLocalAnime(
            SAnime.create().apply {
                url = source.sessionKey.uri.toString()
                title = source.title
            }.toDomainAnime(ExternalStreamSource.ID),
        )
        updateAnimeFromRemote.awaitEpisodesUpdate(
            source = streamSource,
            anime = entry,
            fetchDetails = false,
            fetchEpisodes = true,
            manualFetch = true,
        ).onFailure {
            logcat(LogPriority.INFO, it) { "EXTERNAL_STREAM episode sync failed for ${entry.id}" }
        }

        val episodeId = getEpisodesByAnimeId.await(entry.id).firstOrNull()?.id ?: return null

        // Written onto the row rather than threaded through the switch, for the
        // same reason the folder case does it: setVideo resumes from what the
        // database says, so this is the one resume path every episode uses.
        if (resumeSeconds > 0L) {
            runCatching {
                updateEpisode.await(
                    EpisodeUpdate(
                        id = episodeId,
                        lastSecondSeen = resumeSeconds,
                        totalSeconds = playbackData.value.duration.toLong()
                            .takeIf { it > resumeSeconds } ?: 0L,
                    ),
                )
            }
        }
        return entry.id to episodeId
    }
    // <-- AM (EXTERNAL_STREAM_SOURCE)

    private suspend fun registerGrantedFolderAsLocalEntry(
        treeUri: Uri,
        resumeSeconds: Long,
    ): Pair<Long, Long>? {
        val fileName = externalFileName
        if (fileName.isNullOrBlank()) {
            logcat(LogPriority.INFO) { "EXTERNAL_FOLDER no file name to look for in $treeUri" }
            return null
        }
        val local = sourceManager.get(LocalSource.ID) as? LocalSource ?: return null

        val treeUriString = treeUri.toString()
        val animeUrl = localSourceFileSystem.entryUrlForGrantedFolder(treeUriString)

        // Registered before the scan, not after: resolving animeUrl back to a
        // directory is a membership test against this very preference, so the
        // folder has to be in it for the scan to be able to list anything. Undone
        // again below if the folder turns out not to be what was wanted, so a
        // picked-by-mistake folder does not linger in the local source.
        storagePreferences.extraLocalFolders.set(
            storagePreferences.extraLocalFolders.get() + treeUriString,
        )

        val resolved = try {
            scanGrantedFolderEntry(local, animeUrl, fileName)
        } catch (e: Exception) {
            logcat(LogPriority.INFO, e) { "EXTERNAL_FOLDER could not register $treeUri" }
            null
        }

        if (resolved == null) {
            storagePreferences.extraLocalFolders.set(
                storagePreferences.extraLocalFolders.get() - treeUriString,
            )
            return null
        }

        // Written onto the episode row rather than threaded through the switch:
        // setVideo resumes an episode from what the database says, so this is how
        // the reload lands where the video already is, through the one resume path
        // every other episode uses. Passed in rather than read from playbackData
        // because the two callers mean different things by it - one is interrupting
        // a playing file, the other has not started one yet, where playbackData
        // still describes whatever played last.
        if (resumeSeconds > 0L) {
            runCatching {
                updateEpisode.await(
                    EpisodeUpdate(
                        id = resolved.second,
                        lastSecondSeen = resumeSeconds,
                        totalSeconds = playbackData.value.duration.toLong()
                            .takeIf { it > resumeSeconds } ?: 0L,
                    ),
                )
            }
        }

        logcat(LogPriority.INFO) {
            "EXTERNAL_FOLDER $treeUri registered as local entry ${resolved.first}, " +
                "episode ${resolved.second}"
        }
        return resolved
    }

    /**
     * The anime and episode ids [animeUrl] now addresses, or null when the folder
     * holds no episode named [fileName].
     *
     * Separate from its caller only so that each bail-out can be a plain return
     * while the caller still gets one place to catch from and one place to undo
     * the registration.
     */
    private suspend fun scanGrantedFolderEntry(
        local: LocalSource,
        animeUrl: String,
        fileName: String,
    ): Pair<Long, Long>? {
        val title = withIOContext { localSourceFileSystem.getEntryTitle(animeUrl) }
        if (title.isBlank()) {
            logcat(LogPriority.INFO) { "EXTERNAL_FOLDER $animeUrl resolved to no directory" }
            return null
        }

        // Checked against the directory rather than by scanning it for episodes:
        // the scan belongs to the refresh below, and all this has to decide is
        // whether the folder is the one holding the file being played.
        val holdsFile = withIOContext {
            localSourceFileSystem.getFilesInAnimeDirectory(animeUrl).any { it.name == fileName }
        }
        if (!holdsFile) {
            logcat(LogPriority.INFO) { "EXTERNAL_FOLDER $fileName is not in $animeUrl" }
            return null
        }

        val anime = networkToLocalAnime(
            SAnime.create().apply {
                url = animeUrl
                this.title = title
            }.toDomainAnime(LocalSource.ID),
        )

        // The app's own refresh, not a hand-rolled sync. It runs
        // getAnimeEpisodeUpdate (which extracts the cover and background from the
        // video), writes those onto the entry, syncs the episode list, and kicks
        // off LocalSource.generateMissingThumbnails in the background - every one
        // of which an episode list built here by hand would have skipped, which is
        // exactly why the first version came up with no artwork.
        updateAnimeFromRemote.awaitEpisodesUpdate(
            source = local,
            anime = anime,
            fetchDetails = true,
            fetchEpisodes = true,
            manualFetch = true,
        ).onFailure {
            logcat(LogPriority.INFO, it) { "EXTERNAL_FOLDER refresh failed for $animeUrl" }
        }

        val episodeId = getEpisodesByAnimeId.await(anime.id)
            .firstOrNull { it.url == "$animeUrl/$fileName" }
            ?.id
            ?: return null

        return anime.id to episodeId
    }

    /**
     * Steps to the adjacent file in the granted folder.
     *
     * Runs on episodeChangeJob like every other load, so a rapid next-next
     * cancels the first cleanly instead of racing two loads against one mpv, and
     * calls [loadExternal] rather than [changeToExternal] - which would cancel
     * the very job this runs on.
     *
     * Position starts from zero rather than carrying over: a different file has
     * nothing to do with where this one had got to, which is also why
     * changeEpisode() does not carry one.
     */
    private fun nextExternal(next: Boolean) {
        episodeChangeJob?.cancel()
        episodeChangeJob = viewModelScope.launch {
            val current = externalSource?.sessionKey ?: return@launch
            val queue = externalQueue ?: return@launch
            val index = queue.indexOf(current)
            if (index == -1) return@launch
            val target = queue.keyAt(index + if (next) 1 else -1) as? SessionKey.External
                ?: return@launch
            loadExternal(uri = target.uri, title = null, positionMs = 0L)
        }
    }
    // <-- AM (EXTERNAL_FOLDER_GRANT)

    // AM (EXTERNAL_RESUME_POSITION) -->
    /**
     * Where an external session should start, in milliseconds, pending its load.
     *
     * Deliberately the same shape as RecentEpisodePositionManager.consume(): a
     * one-shot value the resume decision takes if it is there. Clearing on read
     * is what makes it one-shot - without that, a later hoster/quality switch on
     * the same file (which also runs through setVideo) would jump back to the
     * position this session originally resumed from.
     */
    private var pendingExternalResumeMs: Long? = null

    private fun consumePendingExternalResume(): Long? {
        val pending = pendingExternalResumeMs ?: return null
        pendingExternalResumeMs = null
        return pending / 1000L
    }
    // <-- AM (EXTERNAL_RESUME_POSITION)

    /**
     * What this session has actually settled on, whatever kind it is.
     *
     * PlayerHostScreen's reconciliation effect needs to record where a load
     * ENDED UP rather than what was asked for (see its recordSettledTarget -
     * a failed load rolls the session back, and writing the request would
     * leave the host reconciling against an entry the ViewModel abandoned).
     * It used to read currentAnime/currentEpisode for that, which an external
     * session has neither of; a key answers for both kinds.
     */
    val settledSessionKey: SessionKey?
        get() {
            externalSource?.let { return it.sessionKey }
            val state = stateData.value
            val animeId = state.currentAnime?.id ?: return null
            val episodeId = state.currentEpisode?.id ?: return null
            return SessionKey.Library(animeId = animeId, episodeId = episodeId)
        }

    // AM (EXTERNAL_PREFERS_LIBRARY) -->
    /**
     * The library episode [uri] already is, or null when it is not one.
     *
     * The local source stores an entry's directory name as `anime.url` and
     * `"<anime.url>/<fileName>"` as `episode.url` (see LocalSource.getEpisodeList
     * and EpisodeLoader.getHostersOnLocal), so a real path under the local source
     * directory identifies both rows with no scan and no new state.
     *
     * Located by the local source directory's NAME inside the resolved path
     * rather than by comparing against that directory's own path, which - being a
     * SAF tree - generally has none. Season subfolders fall out for free:
     * `anime.url` is every segment between the local source directory and the
     * file, which is exactly how LocalSource builds a season entry's url.
     */
    private suspend fun resolveLibraryEpisode(uri: String): Pair<Long, Long>? {
        val realPath = uri.toUri().realFilePath(
            context,
            heldFd = PlayerMediaHolder.heldExternalFd(uri),
        ) ?: return null
        return try {
            resolveLocalSourceEpisode(realPath)
                ?: resolveGrantedFolderEpisode(realPath)
                ?: resolveDownloadedEpisode(realPath)
        } catch (e: Exception) {
            logcat(LogPriority.INFO, e) { "EXTERNAL_PREFERS_LIBRARY lookup failed for $realPath" }
            null
        }
    }

    /** Segments of [realPath] below the directory named [dirName], or null. */
    private fun segmentsUnder(realPath: String, dirName: String?): List<String>? {
        if (dirName.isNullOrBlank()) return null
        val marker = "/$dirName/"
        val markerAt = realPath.indexOf(marker)
        if (markerAt == -1) return null
        return realPath.substring(markerAt + marker.length).split('/')
    }

    private suspend fun resolveLocalSourceEpisode(realPath: String): Pair<Long, Long>? {
        val segments = segmentsUnder(realPath, storageManager.getLocalSourceDirectory()?.name)
            ?: return null
        if (segments.size < 2) return null

        val animeUrl = segments.dropLast(1).joinToString("/")
        val episodeUrl = segments.joinToString("/")

        val anime = getAnimeByUrlAndSourceId.await(animeUrl, LocalSource.ID) ?: return null
        val episodeId = getEpisodesByAnimeId.await(anime.id)
            .firstOrNull { it.url == episodeUrl }
            ?.id
            ?: return null
        return anime.id to episodeId
    }

    /**
     * The granted-folder episode [realPath] is, or null when it is not one.
     *
     * A folder granted through [applyExternalFolderGrant] is a local source entry
     * from then on, so the SECOND video opened from it is a library episode and
     * must resolve as one - without this, every video in a granted folder came up
     * external again and the grant was worth exactly one video.
     *
     * Matched on the storage provider's document id rather than on the path: the
     * entry's url is a percent-encoded tree uri, which no path can be turned into,
     * but the registered tree's document id and the file's parent's document id
     * are the same string for the same directory.
     */
    private suspend fun resolveGrantedFolderEpisode(realPath: String): Pair<Long, Long>? {
        val parentDocId = parentDocumentIdFor(realPath) ?: return null
        val fileName = realPath.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null

        val animeUrl = storagePreferences.extraLocalFolders.get()
            .firstOrNull { registered -> folderDocumentIdOf(registered) == parentDocId }
            ?.let { localSourceFileSystem.entryUrlForGrantedFolder(it) }
            ?: return null

        val anime = getAnimeByUrlAndSourceId.await(animeUrl, LocalSource.ID) ?: return null
        val episodeId = getEpisodesByAnimeId.await(anime.id)
            .firstOrNull { it.url == "$animeUrl/$fileName" }
            ?.id
            ?: return null
        return anime.id to episodeId
    }

    /**
     * Which directory a registered folder uri names, as a document id.
     *
     * A registered uri is whichever uri the folder was reached by, and those come
     * in two shapes: a tree uri the user picked directly, where the tree document
     * IS the folder, and a document-under-tree uri built from an ancestor grant,
     * where the tree document is the ancestor and the document is the folder. The
     * document id answers for the folder in both, which the tree id does not.
     */
    private fun folderDocumentIdOf(uri: String): String? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
    }

    /**
     * The downloaded episode [realPath] is, or null when it is not one.
     *
     * A download lives at `<downloads>/<sourceDir>/<animeDir>/<episodeDir>`, and
     * all three names are sanitised through DiskUtil.buildValidFilename (see
     * DownloadProvider), so the path cannot be reversed into titles. The names
     * are computed FORWARD for candidates instead and compared - which is also
     * how DownloadCache matches them, so the two agree by construction rather
     * than by a second guess at the sanitising rules.
     *
     * Candidates come from the favourites, because a download only exists for a
     * library entry. The episode segment is either `<episodeDir>` (a folder with
     * the video inside) or `<episodeDir>.mkv` (a single file), so both shapes are
     * accepted - see Downloader, which writes either depending on the download.
     */
    private suspend fun resolveDownloadedEpisode(realPath: String): Pair<Long, Long>? {
        val segments = segmentsUnder(realPath, storageManager.getDownloadsDirectory()?.name)
            ?: return null
        if (segments.size < 3) return null

        val sourceDirName = segments[0]
        val animeDirName = segments[1]
        val episodeSegment = segments[2]

        val candidates = getFavorites.await()
            .filter { downloadProvider.getAnimeDirName(it.ogTitle) == animeDirName }
        for (anime in candidates) {
            val source = sourceManager.get(anime.source) ?: continue
            if (downloadProvider.getSourceDirName(source) != sourceDirName) continue

            val episode = getEpisodesByAnimeId.await(anime.id).firstOrNull { ep ->
                val dirName = downloadProvider.getEpisodeDirName(ep.name, ep.scanlator, ep.url)
                dirName.isNotBlank() &&
                    (episodeSegment == dirName || episodeSegment.substringBeforeLast('.') == dirName)
            } ?: continue

            return anime.id to episode.id
        }
        return null
    }
    // <-- AM (EXTERNAL_PREFERS_LIBRARY)

    /**
     * Plays a video handed to us from outside the app.
     *
     * Does NOT go through [loadHosters]/[loadVideo], and that is a correction
     * to what PlaybackSource's own doc comment claims. Those two require a
     * non-null `currentEpisodeSource` - loadHosters throws without one, and
     * loadVideo needs it for `getResolvedVideo` - because their job is to turn
     * a source's hoster into a playable URL. An external URI IS already that
     * URL: there is nothing to resolve, no quality to pick between, no hoster
     * to fail over to. So this seeds the hoster state the UI reads as already
     * READY and goes straight to [setVideo], which (unlike those two) reads
     * the source as `as? AnimeHttpSource` and tolerates its absence.
     *
     * What step 3b's reasoning got right is the STATE shape - one Hoster
     * holding one Video, so the quality sheet and hoster UI render an external
     * file without knowing it is one. What it got wrong was assuming the
     * resolution pipeline came along with that shape. It does not, and forcing
     * it to would have meant inventing a stub AnimeSource for files that have
     * no source - exactly the sentinel-shaped workaround this whole refactor
     * exists to avoid.
     */
    fun changeToExternal(
        uri: String,
        title: String?,
        positionMs: Long = 0L,
        // AM (EXTERNAL_APP_LABEL)
        appLabel: String? = null,
    ) {
        episodeChangeJob?.cancel()
        // AM (EXTERNAL_APP_LABEL)
        // Held for the session rather than passed along: the save that uses it
        // happens long after the load, and after switchToAnime has cleared
        // everything else about where this came from.
        externalAppLabel = appLabel
        episodeChangeJob = viewModelScope.launch {
            loadExternal(uri, title, positionMs)
        }
    }

    // AM (EXTERNAL_APP_LABEL)
    /** The app this session was opened from - see LocalSourceTarget. */
    private var externalAppLabel: String? = null

    // AM (EXTERNAL_FOLDER_GRANT) -->
    /**
     * The load itself, separated from [changeToExternal] so folder navigation
     * can reuse it.
     *
     * [changeToExternal] cancels episodeChangeJob before launching, which is
     * right for a request arriving from outside the app but fatal if called
     * from inside that job - it would cancel the coroutine making the call.
     * Everything that loads an external file goes through this, and exactly one
     * place owns the job.
     */
    private suspend fun loadExternal(uri: String, title: String?, positionMs: Long = 0L) {
        // AM (EXTERNAL_PREFERS_LIBRARY) -->
        // A file handed to us from outside is very often one this app already
        // knows: anything under the local source directory has been scanned
        // into real Anime and Episode rows, so there is nothing external
        // about it except how it arrived. Opening it as the library episode
        // it already is gives the real playlist for next/previous, the stored
        // resume position, history, trackers and artwork - none of which an
        // external session has, and all of which would otherwise be rebuilt
        // beside machinery that already does it.
        //
        // Checked before any external state is set, so the two can never both
        // be half-true, and routed through switchToAnime rather than
        // changeAnime because changeAnime cancels episodeChangeJob - the job
        // this is running on.
        val libraryTarget = resolveLibraryEpisode(uri)
        if (libraryTarget != null) {
            val (animeId, episodeId) = libraryTarget
            logcat(LogPriority.INFO) {
                "EXTERNAL_PREFERS_LIBRARY $uri is local episode $episodeId of anime $animeId"
            }
            externalSource = null
            externalQueue = null
            publishCanGrantExternalFolder()
            switchToAnime(
                animeId = animeId,
                episodeId = episodeId,
                hostList = "",
                hostIndex = -1,
                vidIndex = -1,
            )
            return
        }
        // <-- AM (EXTERNAL_PREFERS_LIBRARY)
        val parsedUri = uri.toUri()
        val source = withIOContext {
            // Touches storage for the display name - see create()'s own note.
            ExternalPlaybackSource.create(context, parsedUri, title)
        }
        externalSource = source
    // AM (EXTERNAL_FOLDER_GRANT) -->
    // Captured now, while the launching app's grant is alive. Matching the
    // file inside a granted folder is by name, and by the time the user
    // grants one the provider may no longer answer for this URI.
        val realPath = withIOContext {
            parsedUri.realFilePath(context, heldFd = PlayerMediaHolder.heldExternalFd(uri))
        }
        externalFileName = withIOContext {
            runCatching { UniFile.fromUri(context, parsedUri)?.name }.getOrNull()
        }
        externalFolderPickerStart = realPath?.let { folderPickerStartFor(it) }

        // A folder this app can already read needs no asking, and asking is the
        // only reason this was ever an external folder queue rather than a local
        // source entry. So when a persisted tree covers the file - a folder
        // granted in an earlier session, or the app's own storage tree - the
        // folder is registered as a local source entry right here and the session
        // becomes the library episode it now is: real episode list, real
        // add-to-library, no dialog at all.
        //
        // Not done when a folder queue is already in hand and still contains this
        // file, because that is this session stepping through a folder it is
        // already playing, and re-registering mid-step would reload the player
        // underneath itself.
        val carriedQueue = (externalQueue as? ExternalFolderQueue)
            ?.takeIf { it.indexOf(source.sessionKey) != -1 }
        if (carriedQueue == null && realPath != null) {
            val coveringTree = withIOContext { persistedFolderUriFor(realPath) }
            val registered = coveringTree?.let {
                registerGrantedFolderAsLocalEntry(treeUri = it, resumeSeconds = positionMs / 1000L)
            }
            if (registered != null) {
                val (animeId, episodeId) = registered
                logcat(LogPriority.INFO) {
                    "EXTERNAL_FOLDER covered by an existing grant, playing as episode $episodeId"
                }
                externalSource = null
                externalQueue = null
                publishCanGrantExternalFolder()
                switchToAnime(
                    animeId = animeId,
                    episodeId = episodeId,
                    hostList = "",
                    hostIndex = -1,
                    vidIndex = -1,
                )
                return
            }
        }

        // AM (EXTERNAL_STREAM_SOURCE) -->
        // A stream has no folder, so the branch above can never cover it. It
        // becomes an ordinary entry of its own instead and the session carries
        // on as an episode - which is what the episode list, downloading and
        // next/previous already know how to work with, so none of them need to
        // learn about external playback. See ExternalStreamSource.
        if (carriedQueue == null && source.isOnline()) {
            val registered = registerStreamAsEntry(source, resumeSeconds = positionMs / 1000L)
            if (registered != null) {
                val (animeId, episodeId) = registered
                logcat(LogPriority.INFO) {
                    "EXTERNAL_STREAM playing as episode $episodeId of entry $animeId"
                }
                externalSource = null
                externalQueue = null
                publishCanGrantExternalFolder()
                switchToAnime(
                    animeId = animeId,
                    episodeId = episodeId,
                    hostList = "",
                    hostIndex = -1,
                    vidIndex = -1,
                )
                return
            }
        }
        // <-- AM (EXTERNAL_STREAM_SOURCE)

        // Falls back to the folder queue when that did not come off, so a folder
        // the local source cannot make an entry of still gets next/previous.
        externalQueue = carriedQueue
            ?: realPath?.let { path ->
                withIOContext {
                    persistedFolderUriFor(path)?.let { folderUri ->
                        ExternalFolderQueue.create(
                            context = context,
                            treeUri = folderUri,
                            openedUri = source.sessionKey.uri,
                            openedFileName = externalFileName,
                        )
                    }
                }
            }
            ?: SingleItemQueue(source.sessionKey)
        // Both halves of what the offer depends on are settled by here, so this
        // is the one place that has to publish it on load.
        publishCanGrantExternalFolder()
        // <-- AM (EXTERNAL_FOLDER_GRANT)
        // AM (EXTERNAL_RESUME_POSITION) -->
        // Stashed rather than applied here: setVideo owns every decision about
        // where a file starts, and routing this through the same expression as
        // the episode case keeps that true. Consumed exactly once - see
        // consumePendingExternalResume.
        pendingExternalResumeMs = positionMs.takeIf { it > 0L }
        // <-- AM (EXTERNAL_RESUME_POSITION)

        // AM (EXTERNAL_FD_OWNERSHIP) -->
        // Claimed here, on the session's first load, because this is the
        // last moment the grant from the launching app is reliably alive -
        // it dies with the task, and a reload after a swipe-away runs with
        // nothing. Idempotent per uri, so the reload itself reuses whatever
        // this already holds instead of asking the provider again. See
        // PlayerMediaHolder.externalFd.
        withIOContext { PlayerMediaHolder.claimExternalFd(context, source.sessionKey.uri) }
        // <-- AM (EXTERNAL_FD_OWNERSHIP)

        updateUiData { it.copy(animeTitle = source.title, isLoadingEpisode = true) }
        // AM (EXTERNAL_FOLDER_GRANT)
        val resolvedQueue = externalQueue ?: SingleItemQueue(source.sessionKey)
        val queueIndex = resolvedQueue.indexOf(source.sessionKey)
        updateStateData {
            // AM (EXTERNAL_FOLDER_GRANT) -->
            // Derived from whatever queue this session ended up with above, so a
            // folder queue enables next/previous and a single file does not.
            it.copy(
                isEpisodeOnline = source.isOnline(),
                currentPlaylistIndex = queueIndex,
                hasPreviousEpisode = resolvedQueue.hasPrevious(queueIndex),
                hasNextEpisode = resolvedQueue.hasNext(queueIndex),
            )
            // <-- AM (EXTERNAL_FOLDER_GRANT)
        }

        // The notification and lock-screen controls read the holder, not
        // this ViewModel, and go through the same one writer a library
        // session does - see PlayerMediaHolder.syncSessionState.
        mediaHolder?.syncSessionState(
            sessionKey = source.sessionKey,
            animeTitle = source.title,
            episodeTitle = "",
            animeThumbnailUrl = null,
            episodePreviewUrl = null,
        )

        val hosters = withIOContext { source.resolveStreams() }
        val video = hosters.firstOrNull()?.videoList?.firstOrNull()
        if (video == null) {
            // resolveStreams() is a pure wrap of the URI, so this means the
            // URI itself was unusable. Clearing the spinner matters: nothing
            // downstream runs to clear it, and the player would buffer
            // forever on a file it never attempted.
            updateUiData { it.copy(isLoadingEpisode = false, isLoadingHosters = false) }
            showMessage(AYMR.strings.no_available_videos)
            return
        }

        updateStateData {
            it.copy(
                hosterList = hosters,
                hosterState = listOf(
                    HosterState.Ready(
                        name = hosters.first().hosterName,
                        videoList = listOf(video),
                        videoState = listOf(Video.State.READY),
                    ),
                ),
                currentVideo = video,
            )
        }
        updateUiData {
            it.copy(
                hosterExpandedList = listOf(true),
                selectedHosterVideoIndex = 0 to 0,
                previousPauseState = false,
            )
        }
        qualityIndex = 0 to 0

        // AM (EXTERNAL_TRACK_SELECTION_FIX) -->
        // loadVideo() does this before every library load and this path did not,
        // which is why an external video sometimes played with no audio at all.
        // setVideo() loads every file with aid=no/sid=no and relies on the
        // track-list event to select tracks afterwards - but onTrackListChanged()
        // only runs that selection when hasLoadedTracks is false, or retries it
        // when hasLoadedAudio/hasLoadedSubs are false. Carrying those flags in
        // still true from the previous file sent it down the already-loaded
        // branch, so nothing ever chose a track and aid stayed off. "Sometimes"
        // because it depended entirely on what the previous session left behind.
        clearTracks()
        // <-- AM (EXTERNAL_TRACK_SELECTION_FIX)

        setVideo(video)
    }
    // <-- AM (EXTERNAL_FOLDER_GRANT)
    // <-- AM (EXTERNAL_PLAYBACK)

    /**
     * A [PlaybackSource] for one episode of the current session.
     *
     * [anime]/[source] are the episode's OWN anime and source (the child, for a
     * merged entry); [displayAnime] is the library entry the session is on,
     * which is what the title names - see MERGED_NOTIFICATION_ARTWORK_FIX for
     * why those two differ and which belongs where.
     */
    private fun librarySourceFor(
        episode: Episode,
        anime: Anime,
        source: AnimeSource,
        displayAnime: Anime,
        prefetched: List<Hoster>? = null,
    ): LibraryPlaybackSource = LibraryPlaybackSource(
        sessionKey = SessionKey.Library(animeId = displayAnime.id, episodeId = episode.id!!),
        title = "${displayAnime.title} - ${displayNameOf(episode)}",
        episode = episode.toDomainEpisode()!!,
        anime = anime,
        source = source,
        episodeLoader = episodeLoader,
        prefetched = prefetched,
    )
    // <-- AM (CONTENT_AGNOSTIC_SESSION)

    // AM (EPISODE_NAMES) -->
    /** The episode's name as the list shows it - see PlayerStateData.displayNameOf. */
    fun displayNameOf(episode: Episode): String = stateData.value.displayNameOf(episode)

    // AM (LIVE_EPISODE_NAMES) -->
    private var playlistNamesJob: Job? = null

    /**
     * Keeps the playlist's names current while the player lives.
     *
     * The playlist is resolved once and never refetched, so an episode renamed
     * afterwards - from its entry screen, while this player sits in the
     * background - kept showing the old name in the episode list dialog until
     * the player was restarted. Custom names are the only part of a name that
     * can change without the playlist itself changing, so watching them is
     * enough: [base] is what the resolve produced (the source's name, or the
     * entry's own for a single-episode source) and a custom name takes
     * precedence over it, exactly as GetEpisodeOrder orders the two.
     */
    private fun observePlaylistNames(base: Map<Long, String>, ownerIds: Set<Long>) {
        playlistNamesJob?.cancel()
        playlistNamesJob = viewModelScope.launchIO {
            episodeNameRepository.getByAnimeIdsAsFlow(ownerIds.toList())
                .distinctUntilChanged()
                .collect { customNames ->
                    updateStateData { it.copy(playlistEpisodeNames = base + customNames) }
                }
        }
    }
    // <-- AM (LIVE_EPISODE_NAMES)
    // <-- AM (EPISODE_NAMES)

    private fun setupEpisode(episode: Episode) {
        // AM (PERSIST_ON_CADENCE) -->
        // Reset the backstop for the new episode. Without this a switch from late
        // in one episode to the start of another leaves the counter far ahead of
        // the new position, and the difference stays negative until playback
        // passes the old timestamp - the first several minutes of the new episode
        // would never reach a backstop write.
        lastPersistedPositionSeconds = 0
        // <-- AM (PERSIST_ON_CADENCE)
        // AM (CUSTOM_EPISODE_ORDER) -->
        // Whatever made this episode current - confirming the prompt, a manual
        // next/previous, picking from the list, a playlist rehost - resolves
        // any pending season prompt; it described a step that no longer
        // applies.
        if (uiData.value.seasonAdvancePrompt != null) {
            updateUiData { it.copy(seasonAdvancePrompt = null) }
        }
        // <-- AM (CUSTOM_EPISODE_ORDER)
        val currentState = stateData.value

        // AM (CONTENT_AGNOSTIC_SESSION) -->
        // Position and the two next/previous facts come from the queue now
        // rather than being recomputed against currentPlaylist here. Same
        // comparisons - see hasNext/hasPrevious, which keep them verbatim,
        // index -1 behaviour included.
        // EmptyPlaybackQueue rather than a null check: with no entry loaded the
        // old inline comparisons ran against an empty currentPlaylist and
        // reported index -1, a previous and no next. An empty queue answers
        // identically, where `?: false` would have quietly changed what the
        // previous button does in that state.
        val queue = playbackQueue ?: EmptyPlaybackQueue
        val currentEpisodeIndex = episode.id
            ?.let { episodeId ->
                currentState.currentAnime?.id?.let { animeId ->
                    queue.indexOf(SessionKey.Library(animeId = animeId, episodeId = episodeId))
                }
            }
            ?: -1

        updateStateData {
            it.copy(
                currentEpisode = episode,
                currentPlaylistIndex = currentEpisodeIndex,
                isEpisodeOnline = isEpisodeOnline(episode) == true,
                hasPreviousEpisode = queue.hasPrevious(currentEpisodeIndex),
                hasNextEpisode = queue.hasNext(currentEpisodeIndex),
            )
        }
        // <-- AM (CONTENT_AGNOSTIC_SESSION)

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

        // AM (PLAYBACK_QUEUE) -->
        // Trimming hangs off the same choke point, for the same reason: this
        // is where every episode switch lands, so progression - the only thing
        // that ever drops a queued entry - is observed here without a second
        // mechanism watching for it.
        // Launched rather than awaited: a switch must not wait on a cleanup,
        // and a trim cut short by the next switch is simply redone from there.
        episode.id?.let { episodeId ->
            viewModelScope.launchIO {
                trimQueue.await(episodeId, playerPreferences.queueAutoTrim.get())
            }
        }
        // <-- AM (PLAYBACK_QUEUE)
    }

    fun setupPlayerOrientation() {
        if (player.isForegroundSuspended) return
        val orientation = when (playerPreferences.defaultPlayerOrientationType.get()) {
            // AM (FREE_ORIENTATION_FULL_SENSOR_FIX) -->
            // USER: portrait + both landscapes, following the sensor
            // while the phone's auto-rotate is on, and opening in /
            // holding the current orientation when the user has rotation
            // locked - i.e. it respects the PHONE's setting, which plain
            // SENSOR/FULL_SENSOR do not. Not FULL_USER: reverse portrait
            // (phone upside down) is atypical for apps to honor.
            PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_USER
            // <-- AM (FREE_ORIENTATION_FULL_SENSOR_FIX)
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
        // AM (MERGED_SOURCES) -->
        val source = stateData.value.currentEpisodeSource ?: return
        val anime = stateData.value.currentEpisodeAnime ?: return
        // <-- AM (MERGED_SOURCES)
        val episode = stateData.value.currentEpisode ?: return

        if (!player.isReleased) {
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
                    showMessage(AYMR.strings.http_server_start_failure)
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
                    TorrentServerService.start(context)
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
                    showMessage(AMMR.strings.cast_server_start_failed)
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
                episodeTitle = displayNameOf(episode),
                startPosition = startPosition,
                playbackRate = mpv.getPropertyDouble("speed") ?: 1.0,
            )

            _eventFlow.emit(Event.UpdateDiscordRPC(false))
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
                    showMessage(
                        "Skip Intro: ${context.stringResource(
                            AYMR.strings.player_aniskip_dontskip_toast,
                            chapter.name.substringBeforeLast(ChapterUtils.ANIYOMI_CHAPTER_IDENTIFIER),
                            defaultWaitingTime,
                        )}",
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

    // AM (NEXT_EPISODE_PREFETCH) -->
    /**
     * A next episode whose hosters and best video have already been resolved, so
     * the transition onto it is a loadfile instead of three sequential network
     * round trips (getHosters -> loadHosterVideos -> resolveVideo) against a
     * frozen frame.
     *
     * [hosterState] holds the chosen video already marked READY, which is what
     * makes loadVideo() skip resolution entirely and go straight to setVideo().
     */
    private data class PrefetchedEpisode(
        val episodeId: Long,
        val hosterList: List<Hoster>,
        val hosterState: List<HosterState>,
        val selectedIndex: Pair<Int, Int>,
        val fetchedAt: Long,
    )

    private var prefetchedEpisode: PrefetchedEpisode? = null
    private var prefetchStartedForEpisodeId: Long? = null
    private var prefetchJob: Job? = null

    /** The prefetch for [episodeId], or null if there is none or it has aged out. */
    private fun prefetchedFor(episodeId: Long?): PrefetchedEpisode? {
        val prefetch = prefetchedEpisode ?: return null
        if (episodeId == null || prefetch.episodeId != episodeId) return null
        if (System.currentTimeMillis() - prefetch.fetchedAt > PREFETCH_TTL_MS) {
            prefetchedEpisode = null
            return null
        }
        return prefetch
    }

    private fun clearPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchedEpisode = null
        prefetchStartedForEpisodeId = null
    }

    /**
     * Resolves the next episode shortly before this one ends. Deliberately close to
     * the transition rather than early in the episode: resolved hoster links are
     * usually time-limited, so a prefetch taken minutes ahead would be stale by the
     * time it was used (and [PREFETCH_TTL_MS] discards one that sat too long anyway).
     *
     * Hosters are walked one at a time and the walk stops at the first video that
     * resolves, rather than fanning out across all of them - this runs while the
     * current episode is still streaming, and the point is to spend as little
     * bandwidth as possible alongside playback.
     */
    private fun prefetchNextEpisode() {
        val displayAnime = stateData.value.currentAnime ?: return
        val state = stateData.value
        val next = state.currentPlaylist.getOrNull(state.currentPlaylistIndex + 1) ?: return
        val nextId = next.id ?: return
        if (prefetchStartedForEpisodeId == nextId) return
        prefetchStartedForEpisodeId = nextId

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launchIO {
            try {
                val (episodeAnime, episodeSource) = resolveEpisodeAnimeAndSource(displayAnime, next)
                    ?: return@launchIO
                val hosterList = episodeLoader.getHosters(next.toDomainEpisode()!!, episodeAnime, episodeSource)
                if (hosterList.isEmpty()) return@launchIO

                val states = MutableList<HosterState>(hosterList.size) { HosterState.Idle(hosterList[it].hosterName) }
                hosterList.forEachIndexed { hosterIdx, hoster ->
                    states[hosterIdx] = episodeLoader.loadHosterVideos(episodeSource, hoster)

                    val (bestHosterIdx, bestVideoIdx) = hosterLoader.selectBestVideo(states)
                    if (bestHosterIdx == -1) return@forEachIndexed

                    val ready = states[bestHosterIdx] as? HosterState.Ready ?: return@forEachIndexed
                    val video = ready.videoList.getOrNull(bestVideoIdx) ?: return@forEachIndexed
                    val resolved = hosterLoader.getResolvedVideo(episodeSource, video)
                    if (resolved == null || resolved.videoUrl.isEmpty()) return@forEachIndexed

                    states[bestHosterIdx] = ready.getChangedAt(bestVideoIdx, resolved, Video.State.READY)
                    prefetchedEpisode = PrefetchedEpisode(
                        episodeId = nextId,
                        hosterList = hosterList,
                        hosterState = states.toList(),
                        selectedIndex = bestHosterIdx to bestVideoIdx,
                        fetchedAt = System.currentTimeMillis(),
                    )
                    return@launchIO
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // A prefetch is an optimisation - a failure just means the normal
                // load path runs, exactly as it did before.
                logcat(LogPriority.INFO, e) { "NEXT_EPISODE_PREFETCH failed for episodeId=$nextId" }
            }
        }
    }
    // <-- AM (NEXT_EPISODE_PREFETCH)

    // AM (STOP_RESOLVING_UNUSED_HOSTERS) -->
    /**
     * Raised by the first hoster whose video actually starts playing, to cancel the
     * sibling loads still in flight.
     *
     * Not a CancellationException on purpose: loadHosters()'s own catch treats one
     * of those as "the whole load was abandoned" and resets every hoster to Idle,
     * which would throw away the Ready state of the hoster now playing.
     */
    private class PlaybackStartedEarly : Exception()

    /**
     * Returns every hoster still mid-load to Idle after an early stop. Idle is the
     * state onHosterClicked() already loads on demand (it is what a `lazy` hoster
     * gets), so the quality sheet keeps working - it just resolves a hoster when
     * the user asks for it instead of resolving all of them next to a video that
     * is trying to start.
     */
    private fun markLoadingHostersIdle() {
        updateStateData { data ->
            data.copy(
                hosterState = data.hosterState.map { state ->
                    if (state is HosterState.Loading) HosterState.Idle(state.name) else state
                },
            )
        }
    }
    // <-- AM (STOP_RESOLVING_UNUSED_HOSTERS)

    fun loadHosters(hosterList: List<Hoster>, hosterIndex: Int, videoIndex: Int) {
        val hasFoundPreferredVideo = AtomicBoolean(false)

        updateStateData { it.copy(hosterList = hosterList) }
        updateUiData { it.copy(hosterExpandedList = List(hosterList.size) { true }) }

        // AM (MERGED_SOURCES) -->
        val source = stateData.value.currentEpisodeSource
            ?: throw Exception("No source available")
        // <-- AM (MERGED_SOURCES)

        // AM (NEXT_EPISODE_PREFETCH) -->
        // Identity check, not equality: this list is the one loadEpisode() handed
        // back, which is the prefetched list itself when a prefetch was used. Any
        // other caller (a genuine fresh load, a rehost) takes the normal path.
        val prefetch = prefetchedFor(stateData.value.currentEpisode?.id)
        if (prefetch != null && hosterList === prefetch.hosterList) {
            clearPrefetch()
            updateStateData { it.copy(hosterState = prefetch.hosterState) }

            val (prefetchedHosterIdx, prefetchedVideoIdx) = prefetch.selectedIndex
            val prefetchedVideo = (prefetch.hosterState[prefetchedHosterIdx] as HosterState.Ready)
                .videoList[prefetchedVideoIdx]

            getHosterVideoLinksJob?.cancel()
            getHosterVideoLinksJob = viewModelScope.launchIO {
                // Already READY in the seeded state, so this resolves nothing and
                // goes straight to the loadfile.
                loadVideo(prefetchedVideo, prefetchedHosterIdx, prefetchedVideoIdx)
            }
            return
        }
        // <-- AM (NEXT_EPISODE_PREFETCH)

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
                            val hosterState = episodeLoader.loadHosterVideos(source, hoster)

                            updateHosterStateAt(hosterIdx, hosterState)

                            if (hosterState is HosterState.Ready) {
                                if (hosterIdx == hosterIndex) {
                                    hosterState.videoList.getOrNull(videoIndex)?.let {
                                        hasFoundPreferredVideo.set(true)
                                        val success = loadVideo(it, hosterIndex, videoIndex)
                                        if (!success) {
                                            hasFoundPreferredVideo.set(false)
                                        }
                                        // AM (STOP_RESOLVING_UNUSED_HOSTERS) -->
                                        if (success) throw PlaybackStartedEarly()
                                        // <-- AM (STOP_RESOLVING_UNUSED_HOSTERS)
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
                                            // AM (STOP_RESOLVING_UNUSED_HOSTERS) -->
                                            if (success) throw PlaybackStartedEarly()
                                            // <-- AM (STOP_RESOLVING_UNUSED_HOSTERS)
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()

                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                        if (uiData.value.selectedHosterVideoIndex == Pair(-1, -1)) {
                            val (hosterIdx, videoIdx) = hosterLoader.selectBestVideo(stateData.value.hosterState)
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
            } catch (_: PlaybackStartedEarly) {
                // AM (STOP_RESOLVING_UNUSED_HOSTERS) -->
                // The fan-out above stays parallel - that is what keeps startup
                // latency low when the first hoster is slow or dead. What changes
                // is that it no longer runs to completion: once a video is actually
                // playing, the other hosters' resolution is work whose result
                // nothing reads, competing for bandwidth with the video that just
                // started. Throwing from the winning child cancels its siblings
                // through coroutineScope's own semantics; whatever was still
                // loading goes back to Idle, which onHosterClicked() loads on
                // demand.
                markLoadingHostersIdle()
                // <-- AM (STOP_RESOLVING_UNUSED_HOSTERS)
            } catch (e: CancellationException) {
                updateStateData {
                    it.copy(
                        hosterState = it.hosterList.map { h ->
                            HosterState.Idle(h.hosterName)
                        },
                    )
                }

                throw e
            } catch (e: Throwable) {
                // AM (LOAD_FAILURE_NOT_FATAL) -->
                // Everything that can fail in here used to leave through the
                // coroutine's uncaught handler and kill the process:
                // selectBestVideo() finding nothing (an empty hoster list, or one
                // whose videos have all errored), loadVideo() running out of
                // fallbacks, a hoster resolver throwing. None of those are
                // programming errors - they are the ordinary outcome of a source
                // that no longer has the video - so they get reported, not thrown.
                // Whatever was still resolving goes back to Idle so the quality
                // sheet is usable rather than stuck spinning.
                markLoadingHostersIdle()
                reportLoadFailure(e)
                // <-- AM (LOAD_FAILURE_NOT_FATAL)
            }
        }
    }

    fun loadBestVideo(): Boolean {
        val (hosterIdx, videoIdx) = hosterLoader.selectBestVideo(stateData.value.hosterState)
        if (hosterIdx == -1) return false
        val newVideo = (stateData.value.hosterState[hosterIdx] as HosterState.Ready).videoList[videoIdx]
        viewModelScope.launchIO {
            // AM (VIDEO_SWITCH_KEEPS_POSITION) -->
            // A mid-playback failover to another hoster is the same operation as a
            // manual quality switch as far as position goes: the episode did not
            // change, so playback resumes where it stopped. On an initial load that
            // never started, the live position is 0 anyway.
            loadVideo(newVideo, hosterIdx, videoIdx, keepPosition = true)
            // <-- AM (VIDEO_SWITCH_KEEPS_POSITION)
        }
        return true
    }

    // AM (VIDEO_SWITCH_KEEPS_POSITION) -->
    // keepPosition states the CALLER'S INTENT, which is the only thing that can
    // distinguish the two operations sharing this function: starting an episode
    // (position comes from the resume policy - temp memory, then the DB, then 0)
    // versus swapping the file under an episode that is already playing (position
    // is simply where playback currently is). It is not inferable from
    // isLoadingEpisode, which loadVideo sets true for both.
    // <-- AM (VIDEO_SWITCH_KEEPS_POSITION)
    /** Loads [video]; returns true if successful. */
    private suspend fun loadVideo(
        video: Video,
        hosterIndex: Int,
        videoIndex: Int,
        keepPosition: Boolean = false,
    ): Boolean {
        // AM (MERGED_SOURCES) -->
        val source = stateData.value.currentEpisodeSource
            ?: throw Exception("No source loaded")
        // <-- AM (MERGED_SOURCES)
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
        // AM (SWITCH_PAUSES_LATE) -->
        // Only freeze up front when the EPISODE is changing: there the old file's
        // audio would otherwise keep playing over the new episode's load. A
        // same-episode swap (quality change, hoster failover) has nothing to hide -
        // the current file is still perfectly playable, and resolving the new URL
        // is a network round trip. Pausing before that turned every quality change
        // into a frozen frame for the length of the resolve; the pause for those
        // moves down to just before the loadfile that actually replaces the file.
        if (!keepPosition) pauseInternal()
        // <-- AM (SWITCH_PAUSES_LATE)

        // AM (LOAD_CLEARS_STALE_PROGRESS) -->
        // The freeze above stops the old file, but playbackData still holds its
        // position/duration - and mpv won't report the new file's until well
        // after loadfile. For that whole window the seek bar shows the OLD
        // video's progress under the new one's loading spinner. Zeroing it
        // here is the honest state (nothing is playing, nowhere is "where we
        // are"), and the new file's own duration/time-pos observers repaint
        // real values as they arrive. keepPosition loads skip this on purpose:
        // the old file keeps playing until the new one's loadfile, so its
        // progress is still live data, and the position carries across by
        // design.
        if (!keepPosition) {
            updatePlaybackData { it.copy(position = 0, duration = 0) }
        }
        // <-- AM (LOAD_CLEARS_STALE_PROGRESS)

        // AM (SHARED_LINK_RESOLUTION) -->
        // A link the downloader already resolved for this episode and quality is
        // the same link this would go and fetch, so take it rather than hitting the
        // hoster a second time.
        val sharedKey = ResolvedVideoCache.key(
            stateData.value.currentEpisode?.id,
            video.videoTitle,
        )
        ResolvedVideoCache.get(sharedKey)?.let { shared ->
            updateHosterStateAt(
                hosterIndex,
                selectedHosterState.getChangedAt(videoIndex, shared, Video.State.READY),
            )
            qualityIndex = Pair(hosterIndex, videoIndex)
            if (keepPosition) pauseInternal()
            setVideo(shared, keepPosition)
            return true
        }
        // <-- AM (SHARED_LINK_RESOLUTION)

        val resolvedVideo = if (selectedHosterState.videoState[videoIndex] != Video.State.READY) {
            hosterLoader.getResolvedVideo(source, video)
        } else {
            video
        }

        if (resolvedVideo == null || resolvedVideo.videoUrl.isEmpty()) {
            if (stateData.value.currentVideo == null) {
                updateHosterStateAt(hosterIndex, selectedHosterState.getChangedAt(videoIndex, video, Video.State.ERROR))

                val (newHosterIdx, newVideoIdx) = hosterLoader.selectBestVideo(stateData.value.hosterState)
                if (newHosterIdx == -1) {
                    if (stateData.value.hosterState.any { it is HosterState.Loading }) {
                        updateUiData { it.copy(selectedHosterVideoIndex = Pair(-1, -1)) }
                        return false
                    } else {
                        throw ExceptionWithStringResource("No available videos", AYMR.strings.no_available_videos)
                    }
                }

                val newVideo = (stateData.value.hosterState[newHosterIdx] as HosterState.Ready).videoList[newVideoIdx]
                // AM (VIDEO_SWITCH_KEEPS_POSITION) -->
                return loadVideo(newVideo, newHosterIdx, newVideoIdx, keepPosition)
                // <-- AM (VIDEO_SWITCH_KEEPS_POSITION)
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

        // AM (SHARED_LINK_RESOLUTION) -->
        ResolvedVideoCache.put(sharedKey, resolvedVideo)
        // <-- AM (SHARED_LINK_RESOLUTION)
        qualityIndex = Pair(hosterIndex, videoIndex)
        // AM (SWITCH_PAUSES_LATE) -->
        if (keepPosition) pauseInternal()
        // <-- AM (SWITCH_PAUSES_LATE)
        // AM (VIDEO_SWITCH_KEEPS_POSITION) -->
        setVideo(resolvedVideo, keepPosition)
        // <-- AM (VIDEO_SWITCH_KEEPS_POSITION)
        return true
    }

    private fun setVideo(video: Video?, keepPosition: Boolean = false) {
        if (player.isReleased) return
        if (video == null) return
        stopHttpServer()
        // DEBUG (POSITION_BLEED_TRACE) -->
        logcat(LogPriority.INFO) {
            "PositionTrace: setVideo ENTRY animeId=${stateData.value.currentAnime?.id} " +
                "episodeId=${stateData.value.currentEpisode?.id} " +
                "holderState=(animeId=${mediaHolder?.state?.value?.animeId} " +
                "episodeId=${mediaHolder?.state?.value?.episodeId})"
        }
        // <-- DEBUG (POSITION_BLEED_TRACE)

        val castState = castManager.castState.value
        val isLoadingEpisode = if (castState.hasLoadedVideo) {
            castUiData.value.isLoadingEpisode
        } else {
            uiData.value.isLoadingEpisode
        }
        // AM (VIDEO_SWITCH_KEEPS_POSITION) -->
        // isLoadingEpisode alone used to pick this branch, and loadVideo sets it
        // true for a quality switch as well as an episode load - so switching
        // quality mid-episode resumed from the DB's last tick, or from 0 outright
        // once the episode had passed the seen threshold with
        // preserveWatchingPosition off. That 0 is what made a quality change
        // re-fetch the new file from the first byte.
        // <-- AM (VIDEO_SWITCH_KEEPS_POSITION)
        val resumePosition = if (isLoadingEpisode && !keepPosition) {
            // AM (EXTERNAL_RESUME_POSITION) -->
            // An external session has no episode row, so the branch below yields
            // null for it and nothing would set `start` at all - the file would
            // reload from zero every time the session was rebuilt. This is the
            // same decision for a session whose "last here" came off the reopen
            // Intent instead of the DB, which is why it sits here rather than
            // seeking after the load: setVideo stays the one place that decides
            // where a file starts.
            consumePendingExternalResume()
                ?: stateData.value.currentEpisode?.let { episode ->
                val preservePos = playerPreferences.preserveWatchingPosition.get()
                val liveHolderState = mediaHolder?.state?.value
                // AM (RESUME_PRIORITY_SYSTEM) -->
                // Two-step resume: (1) any live/temp position wins outright, no matter
                // what the episode's seen state is - it's currently/very-recently in
                // progress, so it isn't stale. (2) only once no temp position exists do
                // we fall back to what "watch progress" means for this episode: 0 if
                // it's marked seen (unless preserving watch position on seen episodes),
                // otherwise the DB's last_second_seen (0 by default for anything never
                // played or already wiped).
                val resumeFromLast = forceResumeFromLastPosition
                forceResumeFromLastPosition = false
                // AM (TEMP_MEMORY_PRIORITY_FIX) -->
                // Checked first, unconditionally - not nested inside the resumeFromLast
                // branches below. forceResumeFromLastPosition is set true on EVERY fresh
                // PlayerActivity launch (onNewIntent()'s shared cold-start path), not just
                // a genuine process-death reinit - that includes the deliberate
                // teardown-and-relaunch a cross-series switch does (see
                // CROSS_SERIES_TEARDOWN_RELAUNCH_FIX). With the manager consult nested
                // under an `else` that only ran once resumeFromLast's own branches had
                // already been checked, resumeFromLast being (near-)always true on a
                // fresh launch meant temp memory got shadowed on essentially every
                // cross-series reopen, even with a correct, present cache entry sitting
                // right there. This matches the original design intent: any live/temp
                // position wins outright, no matter what else is going on.
                // AM (MERGED_SOURCES) -->
                // Keyed on the episode alone - see RecentEpisodePositionManager.consume.
                val tempFromManager = episode.id?.let { recentEpisodePositionManager.consume(it) }
                // <-- AM (MERGED_SOURCES)
                // AM (CONTENT_AGNOSTIC_SESSION) -->
                val targetSessionKey = stateData.value.currentAnime?.id?.let { animeId ->
                    episode.id?.let { SessionKey.Library(animeId, it) }
                }
                // <-- AM (CONTENT_AGNOSTIC_SESSION)
                val tempPositionMs = when {
                    tempFromManager != null -> tempFromManager
                    // <-- AM (TEMP_MEMORY_PRIORITY_FIX)
                    // AM (LIVE_POSITION_REINIT_ONLY_FIX) -->
                    // Gated on resumeFromLast, deliberately - NOT "any time the holder's
                    // identity happens to match, trust its position." setupEpisode() ->
                    // syncHolderSessionState() updates the holder's animeId/episodeId on
                    // EVERY episode switch, including a completely ordinary
                    // changeEpisode() - but syncSessionState()'s positionMs param is left
                    // null on that path on purpose (see its own doc comment: the
                    // foreground path has its own separate, continuous position-tracking
                    // timer and never touches this field here). That means the instant
                    // after an ordinary switch, the holder's identity already matches the
                    // NEW episode while positionMs is still whatever was last live-tracked
                    // for the OLD one - so without this guard, every ordinary next-episode
                    // click would satisfy every condition below and hand back the previous
                    // episode's position. resumeFromLast is only true for the one case
                    // this shortcut is actually valid for: a process died mid-playback and
                    // this is the reinit picking that exact same episode back up, where
                    // there's no live in-memory temp source left and the holder's position
                    // genuinely still belongs to what's being resumed. animeId/episodeId
                    // checks below guard a narrower cross-series bleed within that same
                    // reinit case: the holder's state stays live for whatever was
                    // PREVIOUSLY playing right up until a new session adopts it.
                    // AM (CONTENT_AGNOSTIC_SESSION) -->
                    // Was two separate animeId/episodeId comparisons; one key
                    // comparison now. Built via a non-null local rather than
                    // inline, so a missing anime/episode id can never collapse
                    // into a null that matches a holder with no session at all.
                    resumeFromLast && liveHolderState != null &&
                        targetSessionKey != null &&
                        liveHolderState.sessionKey == targetSessionKey &&
                        liveHolderState.positionMs > 0 ->
                        liveHolderState.positionMs.toLong()
                    // <-- AM (CONTENT_AGNOSTIC_SESSION)
                    // <-- AM (LIVE_POSITION_REINIT_ONLY_FIX)
                    // Process died mid-playback and this is the reinit picking the
                    // session back up - there's no live in-memory temp source left, but
                    // the position just persisted to the DB right before restart plays
                    // that same role here: it's a live position, not a stored one, so it
                    // also has to bypass the seen check below.
                    resumeFromLast -> episode.last_second_seen
                    else -> null
                }
                (
                    if (tempPositionMs != null && tempPositionMs > 0) {
                        tempPositionMs / 1000L
                    } else if (episode.seen && !preservePos) {
                        0L
                    } else {
                        episode.last_second_seen / 1000L
                    }
                    )
                    // DEBUG (POSITION_BLEED_TRACE) -->
                    .also { chosen ->
                        logcat(LogPriority.INFO) {
                            "PositionTrace: setVideo RESUME chosen=${chosen}s " +
                                "animeId=${stateData.value.currentAnime?.id} episodeId=${episode.id} " +
                                "resumeFromLast=$resumeFromLast " +
                                "liveHolderState=(animeId=${liveHolderState?.animeId} " +
                                "episodeId=${liveHolderState?.episodeId} " +
                                "posMs=${liveHolderState?.positionMs}) " +
                                "tempPositionMs=$tempPositionMs " +
                                "episode.seen=${episode.seen} " +
                                "episode.last_second_seen=${episode.last_second_seen}"
                        }
                    }
                    // <-- DEBUG (POSITION_BLEED_TRACE)
                // <-- AM (RESUME_PRIORITY_SYSTEM)
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
                TorrentServerService.start(context)
                torrentLinkHandler(video.videoUrl, video.videoTitle, videoOptions)
            }
        } else {
            launchIO {
                // AM (MERGED_SOURCES) -->
                val httpSource = stateData.value.currentEpisodeSource as? AnimeHttpSource
                // <-- AM (MERGED_SOURCES)
                var videoUrl: String = video.videoUrl
                if (video.usesHttpServer() && httpSource != null) {
                    val port = try {
                        httpServer = httpSource.createHttpServer()
                        httpServer?.start()
                        httpServer?.listeningPort ?: 0
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to start http server" }
                        showMessage(AYMR.strings.http_server_start_failure)
                        return@launchIO
                    }

                    val newVideo = video.copyHttpServer(port)
                    videoUrl = newVideo.videoUrl
                    updateStateData { it.copy(currentVideo = newVideo) }
                }

                // AM (MEDIA_CACHE) -->
                // Read through MediaProxyServer under the same (episode, quality)
                // key the downloader uses, so an episode being downloaded while it
                // is watched is fetched once and read twice instead of pulled twice
                // over the same connection.
                //
                // Only for a remote URL: a downloaded file, a local source and the
                // source's own localhost server all stay exactly as they were, and
                // a proxy that cannot start falls through to the direct URL.
                val playbackUrl = proxiedPlaybackUrl(videoUrl, video) ?: videoUrl
                loadFile(parseVideoUrl(playbackUrl)!!, videoOptions)
                // <-- AM (MEDIA_CACHE)
            }
        }

        viewModelScope.launch {
            _eventFlow.emit(Event.UpdateDiscordRPC(false))
        }
        // AM (STREAMING_PLAYBACK_SIGNAL) -->
        // The loaded file just changed, which is the other half of the signal -
        // a downloaded episode and a streamed one are the same "playing" state.
        publishStreamingPlaybackState()
        // <-- AM (STREAMING_PLAYBACK_SIGNAL)
    }

    // AM (MEDIA_CACHE) -->
    /**
     * Registers [url] with the loopback proxy under this episode's cache key, or
     * returns null to play it directly.
     *
     * The registration is never released: mpv reopens the URL on its own for seeks
     * and reconnects, so the mapping has to outlive any single request. The entries
     * are a handful of strings and the session drops them when the process ends.
     */
    private fun proxiedPlaybackUrl(url: String, video: Video): String? {
        if (!url.startsWith("http")) return null
        if (url.contains("127.0.0.1") || url.contains("localhost")) return null

        val episodeId = stateData.value.currentEpisode?.id ?: return null
        val headers = video.headers ?: (stateData.value.currentEpisodeSource as? AnimeHttpSource)?.headers
            ?: return null

        MediaProxyServer.attachCache(
            MediaCache.get(
                context,
                // Zero allowance is the off state - see the preference's own note.
                playerPreferences.retainRecentEpisodeMediaMaxBytes.get() > 0,
                playerPreferences.retainRecentEpisodeMediaMaxBytes.get(),
            ),
        )
        return MediaProxyServer.proxyUrlFor(
            url = url,
            headers = headers,
            httpClient = networkService.client,
            cacheKey = MediaProxyServer.cacheKeyFor(episodeId, video.videoTitle),
            // AM (RELEASE_PREVIOUS_REGISTRATION) -->
            // One registration per session, replaced on each load rather than
            // added alongside the last one.
            owner = "player-${System.identityHashCode(this)}",
            // <-- AM (RELEASE_PREVIOUS_REGISTRATION)
        )?.second
    }
    // <-- AM (MEDIA_CACHE)

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
        // AM (MERGED_SOURCES) -->
        val source = stateData.value.currentEpisodeSource as? AnimeHttpSource
            ?: return
        // <-- AM (MERGED_SOURCES)

        val headers = (video.headers ?: source.headers)
            .toMultimap()
            .mapValues { it.value.firstOrNull() ?: "" }

        val httpHeaderString = headers.map {
            it.key + ": " + it.value.replace(",", "\\,")
        }.joinToString(",")

        mpv.setOptionString("http-header-fields", httpHeaderString)
    }

    // AM (EOF_REPLAY_NO_RELOAD) -->
    /**
     * Latched copy of mpv's own `eof-reached` for the currently loaded file.
     *
     * A latch rather than a live read because of WHEN it gets consulted: [endFile]
     * runs on MPV_EVENT_END_FILE, which for a failed post-EOF re-read arrives after
     * the seek that triggered the re-read has already cleared mpv's own property.
     * Cleared in [fileLoaded] - i.e. exactly once per genuine file load - so it can
     * never describe a file other than the one currently open.
     */
    private var currentFileReachedEof = false
    // <-- AM (EOF_REPLAY_NO_RELOAD)

    // AM (COMPLETION_RUNS_ONCE) -->
    /**
     * The episode whose completion work has already SUCCEEDED this session.
     *
     * Keyed on the episode rather than cleared on file load, deliberately: a
     * quality switch reloads the file without changing the episode, and completion
     * should not run a second time for it.
     */
    private var completedEpisodeId: Long? = null

    /**
     * The episode whose completion work is running right now.
     *
     * Two flags rather than one, because they answer different questions and a
     * single flag gets one of them wrong. Marking "done" when the work STARTS makes
     * a failure permanent - a tracker call that throws during a network drop meant
     * the episode was never marked seen and never retried, which is exactly what
     * happened on device. Marking it only when the work SUCCEEDS fixes that but
     * leaves nothing to stop the next tick, a second later, launching its own copy
     * while the first is still waiting on the network - the pile-up this guard
     * exists to prevent. This one blocks that, is cleared in a finally, and so a
     * genuine failure is retried on the following tick.
     */
    private var completionInFlightEpisodeId: Long? = null

    /**
     * Runs the completion work for [episode] at most once, from whichever trigger
     * gets there first.
     */
    private fun runEpisodeCompletion(episode: Episode) {
        val id = episode.id ?: return
        if (completedEpisodeId == id || completionInFlightEpisodeId == id) return
        // Same gate the tick path applies, repeated here because the EOF trigger
        // reaches this function without passing through it.
        if (stateData.value.incognitoMode && !stateData.value.hasTrackers) return

        completionInFlightEpisodeId = id
        viewModelScope.launchNonCancellable {
            try {
                updateEpisodeProgressOnComplete(episode)
                completedEpisodeId = id
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "COMPLETION_RUNS_ONCE failed for episodeId=$id" }
            } finally {
                if (completionInFlightEpisodeId == id) completionInFlightEpisodeId = null
            }
        }
    }
    // <-- AM (COMPLETION_RUNS_ONCE)

    private fun eofReached(eofReached: Boolean) {
        // AM (EOF_REPLAY_NO_RELOAD) -->
        currentFileReachedEof = eofReached
        // <-- AM (EOF_REPLAY_NO_RELOAD)
        // AM (COMPLETION_RUNS_ONCE) -->
        // EOF is a completion trigger in its own right, and the only one that can
        // catch a skip to the very end. onSecondReached samples `time-pos`, which
        // is an Int: a seek landing in the same whole second the flow last emitted
        // produces no new value, and with keep-open=yes no further ticks ever
        // arrive - so the episode stayed unwatched permanently. The file ending is
        // unambiguous where a position sample is not.
        if (eofReached) {
            stateData.value.currentEpisode?.let { runEpisodeCompletion(it) }
        }
        // <-- AM (COMPLETION_RUNS_ONCE)
        if (eofReached && uiData.value.autoPlayEnabled) {
            // AM (CUSTOM_EPISODE_ORDER) -->
            seasonBoundaryAhead()?.let { prompt ->
                if (playerPreferences.askBeforeNextSeason.get()) {
                    updateUiData { it.copy(seasonAdvancePrompt = prompt) }
                    return
                }
            }
            // <-- AM (CUSTOM_EPISODE_ORDER)
            nextEpisode(next = true, autoplay = true)
        }
    }

    // AM (CUSTOM_EPISODE_ORDER) -->
    /**
     * The next-season prompt, if the upcoming playlist step crosses a season
     * boundary; null when it stays within the season or there is no next
     * episode.
     *
     * Only the AUTOMATIC end-of-episode advance consults this. Every other
     * route onto the next episode - the next button, gestures, media keys,
     * PiP controls - is the user choosing to move, so asking would just be
     * friction.
     */
    private fun seasonBoundaryAhead(): SeasonAdvancePrompt? {
        val state = stateData.value
        if (state.playlistSeasonById.isEmpty()) return null
        val current = state.currentPlaylist.getOrNull(state.currentPlaylistIndex) ?: return null
        val next = state.currentPlaylist.getOrNull(state.currentPlaylistIndex + 1) ?: return null
        val currentId = current.id ?: return null
        val nextId = next.id ?: return null
        val currentSeason = state.playlistSeasonById[currentId] ?: return null
        val nextSeason = state.playlistSeasonById[nextId] ?: return null
        if (currentSeason == nextSeason) return null
        return SeasonAdvancePrompt(
            nextEpisodeId = nextId,
            finishedSeason = currentSeason,
            nextSeason = nextSeason,
        )
    }

    fun confirmSeasonAdvance() {
        val prompt = uiData.value.seasonAdvancePrompt ?: return
        updateUiData { it.copy(seasonAdvancePrompt = null) }
        changeEpisode(prompt.nextEpisodeId, autoPlay = true)
    }

    fun dismissSeasonAdvance() {
        updateUiData { it.copy(seasonAdvancePrompt = null) }
    }
    // <-- AM (CUSTOM_EPISODE_ORDER)

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
            showMessage(errorMessage)
        }

        // AM (EOF_REPLAY_NO_RELOAD) -->
        // A read failure that happens AFTER the file already played through is not
        // evidence that this video is bad - the far likelier cause is a hoster link
        // that expired somewhere during playback, which only shows up once the
        // demuxer has to go back to the network (its cache is capped at 64MB, so
        // replaying from the start always does). Routing that through
        // setCurrentVideoError() marks a perfectly good video ERROR and hands
        // switchOnFailure a hoster list it has just poisoned. Re-resolve THIS video
        // once instead; if that attempt fails too the latch is already cleared, so
        // the second pass falls through to the normal error handling below.
        if (currentFileReachedEof) {
            currentFileReachedEof = false
            // AM (SHARED_LINK_RESOLUTION) -->
            // This link just failed, so the downloader must not pick it up.
            ResolvedVideoCache.invalidate(
                ResolvedVideoCache.key(
                    stateData.value.currentEpisode?.id,
                    stateData.value.currentVideo?.videoTitle.orEmpty(),
                ),
            )
            // <-- AM (SHARED_LINK_RESOLUTION)
            if (reloadCurrentVideo()) return
        }
        // <-- AM (EOF_REPLAY_NO_RELOAD)

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

    // AM (EOF_REPLAY_NO_RELOAD) -->
    /**
     * Re-resolves and reloads the video that is already selected, leaving its
     * hoster/video selection untouched. Setting the slot back to LOAD_VIDEO is what
     * makes [loadVideo] call getResolvedVideo() again rather than reuse the stale
     * (expired) URL it still holds. Returns false when there is no selected video to
     * reload, so the caller can fall back to its normal failure handling.
     */
    private fun reloadCurrentVideo(): Boolean {
        val (hosterIdx, videoIdx) = uiData.value.selectedHosterVideoIndex
        val hosterState = stateData.value.hosterState.getOrNull(hosterIdx) as? HosterState.Ready ?: return false
        val video = hosterState.videoList.getOrNull(videoIdx) ?: return false

        updateHosterStateAt(hosterIdx, hosterState.getChangedAt(videoIdx, video, Video.State.LOAD_VIDEO))
        viewModelScope.launchIO {
            loadVideo(video, hosterIdx, videoIdx)
        }
        return true
    }
    // <-- AM (EOF_REPLAY_NO_RELOAD)

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

        // AM (VIDEO_SELECT_PLAYS) -->
        // Tapping a video is an explicit request to watch THAT video, so it
        // should play once loaded - not inherit the paused state of whatever
        // it replaces. Without this, loadVideo()'s
        // `previousPauseState ?: playbackData.value.paused` capture picks up
        // the current pause and checkFileLoaded() re-applies it to the new
        // file: pause a video, pick another one, and the new one arrives
        // paused. Setting the slot (rather than passing a flag) keeps
        // recordPauseIntentDuringLoad() authoritative afterwards: a pause
        // arriving DURING this load still wins over this default.
        updateUiData { it.copy(previousPauseState = false) }
        // <-- AM (VIDEO_SELECT_PLAYS)

        // AM (LOAD_CLEARS_STALE_PROGRESS) -->
        // Same rule as loadVideo()'s own reset, for the keepPosition path
        // that function deliberately skips. An explicit video tap means the
        // user asked for a DIFFERENT video: from that moment, where the old
        // file happens to be is no longer what the bar should show while the
        // new one loads. Cleared here rather than in loadVideo() so
        // keepPosition's other callers (loadBestVideo()'s failover, a reload)
        // keep their continuous bar. If the old file is actually still
        // playing through the resolution, mpv's own time-pos/duration
        // observers repaint it immediately - this only visibly matters when
        // playback was frozen/paused, which is exactly the stale-bar case.
        updatePlaybackData { it.copy(position = 0, duration = 0) }
        // <-- AM (LOAD_CLEARS_STALE_PROGRESS)

        viewModelScope.launchIO {
            // AM (VIDEO_SWITCH_KEEPS_POSITION) -->
            val success = loadVideo(video, hosterIndex, videoIndex, keepPosition = true)
            // <-- AM (VIDEO_SWITCH_KEEPS_POSITION)
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
                // AM (MERGED_SOURCES) -->
                val source = stateData.value.currentEpisodeSource
                    ?: throw Exception("Source not loaded")
                // <-- AM (MERGED_SOURCES)

                val hosterName = stateData.value.hosterList[index].hosterName
                updateHosterStateAt(index, HosterState.Loading(hosterName))

                viewModelScope.launchIO {
                    val hosterState = episodeLoader.loadHosterVideos(
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
        if (player.isReleased) return

        // AM (EOF_REPLAY_NO_RELOAD) -->
        currentFileReachedEof = false
        // <-- AM (EOF_REPLAY_NO_RELOAD)

        // AM (PINCH_ZOOM): the zoom is session state, not per-file - one left on at
        // the end of an episode would otherwise greet the viewer on the next.
        resetVideoZoom()

        setMpvOptions()
        setMpvMediaTitle()
        setupChapters()
        setupPlayerOrientation()
        checkFileLoaded()
        generateEpisodeThumbnailIfMissing()
        // AM (EXTERNAL_STREAM_COVER)
        captureExternalStreamArtwork()

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
        // AM (MERGED_SOURCES) -->
        val anime = stateData.value.currentEpisodeAnime ?: return
        // <-- AM (MERGED_SOURCES)
        val episode = stateData.value.currentEpisode ?: return
        val episodeId = episode.id ?: return
        if (!anime.isLocal()) return
        if (!episode.preview_url.isNullOrBlank()) return

        viewModelScope.launchIO {
            // Give mpv a moment to decode a real frame, not a black/loading frame.
            delay(3000)
            if (player.isReleased) return@launchIO
            // Only capture if still on the episode this was scheduled for.
            if (stateData.value.currentEpisode?.id != episodeId) return@launchIO
            runCatching {
                val tempFile = File(context.cacheDir, "${episodeId}_auto_thumbnail_tmp.jpg")
                mpvCommand("screenshot-to-file", tempFile.absolutePath, "video")
                if (!tempFile.exists()) return@launchIO
                tempFile.inputStream().use { stream ->
                    episode.editThumbnail(anime, thumbnailManager, stream)
                }
                tempFile.delete()
            }.onSuccess {
                _thumbnailGenerated.update { episodeId }
            }
        }
    }

    // AM (EXTERNAL_STREAM_COVER) -->
    /** The capture running for the current external entry, cancelled when it changes. */
    private var externalStreamCoverJob: Job? = null

    /**
     * Gives an external stream entry artwork taken from the video itself.
     *
     * An entry on ExternalStreamSource has nothing to take a cover from the way
     * a local entry does: there is no folder to extract a frame into and the
     * stream url carries no artwork, so the entry screen the eye icon opens
     * showed a blank cover and its one episode row a blank thumbnail. The frame
     * mpv is already decoding is the one thing on hand that depicts the video,
     * and the player already turns that frame into artwork - this is the same
     * capture generateEpisodeThumbnailIfMissing makes.
     *
     * Written to the cache first and pointed at by `thumbnail_url`, BEFORE the
     * entry is anything the library knows about. The custom cover store cannot
     * hold it yet - editCover writes there only for a local or favorited entry
     * - but the cover is wanted precisely when the entry is neither, which is
     * what the eye icon opens onto. A `file://` url needs none of that: the
     * image fetcher loads one for any entry, favorited or not (see
     * AnimeImageFetcher.getResourceType), and the same file answers for the
     * episode's preview.
     *
     * Being in the cache is what makes it temporary, and the promotion below is
     * what makes it permanent once there is somewhere permanent to put it. A
     * capture evicted before then is simply taken again on the next play, which
     * is why this checks for the FILE rather than remembering that it ran.
     */
    private fun captureExternalStreamArtwork() {
        externalStreamCoverJob?.cancel()
        externalStreamCoverJob = null

        val anime = stateData.value.currentAnime ?: return
        if (anime.source != ExternalStreamSource.ID) return
        // Already promoted - the permanent copy outranks anything taken here.
        if (coverCache.getCustomCoverFile(anime.id).exists()) return
        val episode = stateData.value.currentEpisode
        val episodeId = episode?.id

        externalStreamCoverJob = viewModelScope.launchIO {
            val file = externalStreamArtworkFile(anime.id)
            if (!file.exists() || file.length() == 0L) {
                // The same lead-in the thumbnail capture takes: a frame asked
                // for too early is the black one mpv starts on, not the video.
                delay(COVER_CAPTURE_LEAD_IN_MS)
                if (player.isReleased) return@launchIO
                if (stateData.value.currentAnime?.id != anime.id) return@launchIO
                runCatching {
                    file.parentFile?.mkdirs()
                    mpvCommand("screenshot-to-file", file.absolutePath, "video")
                    if (!file.exists() || file.length() == 0L) return@launchIO
                    val fileUrl = "file://${file.absolutePath}"
                    updateAnime.await(AnimeUpdate(id = anime.id, thumbnailUrl = fileUrl))
                    // Bumped so the image loader re-reads rather than serving
                    // the blank it already cached for this entry.
                    updateAnime.awaitUpdateCoverLastModified(anime.id)
                    // The row is not the only copy. currentAnime and
                    // currentEpisodeAnime are snapshots taken at init() and
                    // never re-read, and syncSessionState feeds the media
                    // session's animeThumbnailUrl straight off them - so
                    // leaving them stale would keep pushing a null cover to
                    // the notification for the rest of the session no matter
                    // what the database says.
                    updateStateData { data ->
                        data.copy(
                            currentAnime = data.currentAnime
                                ?.takeIf { it.id == anime.id }
                                ?.copy(thumbnailUrl = fileUrl)
                                ?: data.currentAnime,
                            currentEpisodeAnime = data.currentEpisodeAnime
                                ?.takeIf { it.id == anime.id }
                                ?.copy(thumbnailUrl = fileUrl)
                                ?: data.currentEpisodeAnime,
                        )
                    }
                    if (episodeId != null && episode.preview_url.isNullOrBlank()) {
                        updateEpisode.await(EpisodeUpdate(id = episodeId, previewUrl = fileUrl))
                        _thumbnailGenerated.update { episodeId }
                    }
                }.onFailure {
                    logcat(LogPriority.INFO, it) { "EXTERNAL_STREAM artwork capture failed for ${anime.id}" }
                    return@launchIO
                }
            }
            promoteExternalStreamCoverWhenFavorited(anime.id, file)
        }
    }

    /**
     * Copies the cached capture into the custom cover store once the entry is
     * favorited, which is the first moment anything can be kept there.
     *
     * A subscription rather than a hook on the player's own add-to-library
     * action, so the heart on the entry screen counts too - the eye icon is how
     * most of these get favorited, and that path never comes back through this
     * ViewModel. Tied to viewModelScope: close the player before adding the
     * entry and the cover stays the cached one until the next play promotes it.
     */
    private suspend fun promoteExternalStreamCoverWhenFavorited(animeId: Long, file: File) {
        val favorited = getAnime.subscribe(animeId).first { it.favorite }
        if (coverCache.getCustomCoverFile(animeId).exists()) return
        if (!file.exists() || file.length() == 0L) return
        runCatching {
            file.inputStream().use { stream ->
                favorited.editCover(coverManager, stream, updateAnime, coverCache)
            }
        }.onFailure {
            logcat(LogPriority.INFO, it) { "EXTERNAL_STREAM cover promotion failed for $animeId" }
        }
    }

    /** Where an external entry's capture is kept until it can be promoted. */
    private fun externalStreamArtworkFile(animeId: Long): File =
        File(File(context.cacheDir, EXTERNAL_ARTWORK_DIR), "$animeId.png")
    // <-- AM (EXTERNAL_STREAM_COVER)

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
        pendingNoAudioFallbackJob?.cancel()
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
            // Deliberately not marking hasLoadedAudio = true here: mpv can report the
            // track list before an embedded audio track is registered (see
            // onTrackListChanged), and this flag lets that retry logic try again on
            // the next update. But if the file genuinely has no audio track at all,
            // no further track-list-changed event will ever arrive to trigger that
            // retry, so fall back after a short delay rather than blocking
            // checkFileLoaded()/isLoadingEpisode forever.
            pendingNoAudioFallbackJob?.cancel()
            pendingNoAudioFallbackJob = viewModelScope.launch {
                delay(1000)
                if (!stateData.value.hasLoadedAudio) {
                    updateStateData { it.copy(hasLoadedAudio = true) }
                    checkFileLoaded()
                }
            }
        } else {
            pendingNoAudioFallbackJob?.cancel()
            selectAudio(preferredAudio, true)
        }
    }

    // Debounced fallback for genuinely audio-less files, set in onTracksLoaded() above.
    private var pendingNoAudioFallbackJob: Job? = null

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
            audioDeselectedByUser = true
            pendingAudioTrackRestoreJob?.cancel()
            setPropertyBoolean("aid", false)
        } else {
            audioDeselectedByUser = false
            setPropertyInt("aid", id)
        }
    }

    // Tracked reactively as the freshest known-good audio track: mpv can spontaneously
    // clear it ~100-200ms after a decoder reconfig, so onAudioTrackSelectChange below
    // self-heals reactively from this value instead of timing a one-shot reapply.
    private var lastKnownAudioTrackId: Int? = null

    // Tracks the pending, debounced restore below - cancelled whenever a genuine
    // value change arrives, so a real track switch is never delayed by this.
    private var pendingAudioTrackRestoreJob: Job? = null

    // Set when the user explicitly deselects the audio track (as opposed to mpv
    // spontaneously clearing aid mid-reconfig), so the self-heal below doesn't
    // fight a deliberate "no audio" choice. Cleared as soon as any real track
    // becomes selected again.
    private var audioDeselectedByUser = false

    private fun onAudioTrackSelectChange() {
        val id = mpv.getPropertyInt("aid")
        if (id != null && id > 0) {
            lastKnownAudioTrackId = id
            audioDeselectedByUser = false
            pendingAudioTrackRestoreJob?.cancel()
        } else if (!uiData.value.isLoadingEpisode && !audioDeselectedByUser) {
            // Guarded by isLoadingEpisode so this doesn't fight genuine "no track
            // selected yet" states during an actual new episode load, and by
            // audioDeselectedByUser so it doesn't fight a deliberate deselect.
            //
            // Debounced past mpv's own documented ~100-200ms clear window (see
            // lastKnownAudioTrackId's doc comment above), and re-checks aid is
            // STILL cleared before restoring, rather than acting on the very
            // first flow emission - avoids fighting mpv's own in-flight
            // reconfigure while it's still settling.
            pendingAudioTrackRestoreJob?.cancel()
            pendingAudioTrackRestoreJob = viewModelScope.launch {
                delay(300)
                val stillCleared = (mpv.getPropertyInt("aid") ?: 0) <= 0
                if (stillCleared) {
                    lastKnownAudioTrackId?.let { savedId ->
                        if (savedId > 0) setPropertyInt("aid", savedId)
                    }
                }
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

    // AM (STALE_CHAPTER_INDICATOR_FIX) -->
    // currentChapter (what ChapterSlot in BottomPlayerControls actually checks)
    // was previously left to the separate "chapter" mpv property observer
    // (onChapterChanged()) to populate on every file transition. That observer
    // only fires on an actual value CHANGE as mpv tracks it, and mpv's "chapter"
    // property doesn't reliably re-transition through every file load - notably,
    // going from a chapterless episode to a chaptered one could land on the same
    // raw index mpv last held, firing nothing, leaving currentChapter stuck null.
    // This now queries mpv's "chapter" property directly, synchronously, the
    // moment the new file's chapter-list itself changes - not dependent on that
    // separate observer's timing/firing behavior at all - so it's correct
    // immediately on every transition, chaptered-to-chapterless or the reverse.
    fun onChapterListChanged(node: MPVNode) {
        val chapterNodes = node.toObject<List<ChapterNode>>(json)
        updateStateData {
            it.copy(
                chapters = chapterNodes.map { chapterNode -> chapterNode.toSegment() },
                currentChapter = currentChapterFor(chapterNodes),
            )
        }
    }

    private fun currentChapterFor(chapterNodes: List<ChapterNode>): Segment? {
        if (chapterNodes.isEmpty()) return null
        val chapterIndex = mpv.getPropertyInt("chapter") ?: -1
        val chapterNode = if (chapterIndex == -1) {
            ChapterNode(time = 0.0f, "")
        } else {
            chapterNodes.getOrNull(chapterIndex) ?: return null
        }
        return chapterNode.toSegment()
    }
    // <-- AM (STALE_CHAPTER_INDICATOR_FIX)

    private data class EpisodeLoadResult(
        val hosterList: List<Hoster>?,
        val episodeTitle: String,
    )

    /** Loads an episode, returning its hoster list and title. */
    private suspend fun loadEpisode(episodeId: Long?): EpisodeLoadResult? {
        val displayAnime = stateData.value.currentAnime ?: return null

        // AM (PLAYLIST_SEARCH_SCOPE) -->
        // Resolved against the unscoped list. currentPlaylist is what plays
        // NEXT; being asked to start a specific episode is not constrained by
        // it, and refusing here returned null - which left isLoadingEpisode
        // true with nothing to clear it, so the player buffered forever on any
        // episode outside the active scope.
        val chosenEpisode = stateData.value.currentPlaylist.firstOrNull { ep -> ep.id == episodeId }
            ?: stateData.value.playlistUnscoped.firstOrNull { ep -> ep.id == episodeId }
            ?: return null
        // <-- AM (PLAYLIST_SEARCH_SCOPE)

        // AM (MERGED_SOURCES) -->
        val (anime, source) = resolveEpisodeAnimeAndSource(displayAnime, chosenEpisode) ?: return null
        updateStateData { it.copy(currentEpisodeAnime = anime, currentEpisodeSource = source) }
        // <-- AM (MERGED_SOURCES)

        setupEpisode(chosenEpisode)

        return withIOContext {
            // AM (LOAD_FAILURE_NOT_FATAL) -->
            // The result is built from THIS episode's resolved hosters, held in
            // a local first. It used to assign currentHosterList inside the try
            // and then read that field back out below, so a getHosters() throw -
            // the 404 a deleted entry's page returns - left the field holding the
            // PREVIOUS episode's list and handed it back as if it were this
            // one's. The caller saw a perfectly good hoster list, loaded it, and
            // played the previous video under this episode's slot: the duplicate
            // in the play order. The catch logged and swallowed, so nothing said
            // otherwise.
            //
            // Failure is now null, and currentHosterList is cleared with it - a
            // stale list is worse than none, since init()'s defaultResult reads
            // this field too.
            // AM (CONTENT_AGNOSTIC_SESSION) -->
            // Built once, outside the try, so the title below comes from the
            // same source that resolved the streams rather than being rebuilt
            // from displayAnime a second time - two spellings of one string
            // that nothing kept in step.
            //
            // AM (NEXT_EPISODE_PREFETCH) -->
            // The prefetched list is handed to the source rather than checked
            // around it, so "where does a stream come from" has one answer per
            // session instead of one per call site.
            // <-- AM (NEXT_EPISODE_PREFETCH)
            val playbackSource = librarySourceFor(
                episode = chosenEpisode,
                anime = anime,
                source = source,
                displayAnime = displayAnime,
                prefetched = prefetchedFor(chosenEpisode.id)?.hosterList,
            )
            // <-- AM (CONTENT_AGNOSTIC_SESSION)
            val resolved = try {
                val hosters = playbackSource.resolveStreams()
                this@PlayerViewModel.episodeId = chosenEpisode.id!!
                hosters
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to resolve hosters for episodeId=${chosenEpisode.id}" }
                null
            }
            currentHosterList = resolved
            // <-- AM (LOAD_FAILURE_NOT_FATAL)

            EpisodeLoadResult(
                hosterList = resolved,
                episodeTitle = playbackSource.title,
            )
        }
    }

    // AM (MERGED_SOURCES) -->
    /**
     * Resolves the real anime/source [episode] actually belongs to, given the
     * session's library entry [displayAnime]. For a normal session this is
     * just currentAnime/currentSource re-used as-is (episode.animeId already
     * equals displayAnime.id there, so no extra lookup happens); for a merged
     * session, episode.animeId points at the real child anime instead of the
     * merge parent, so this is what makes hoster/video fetching, HTTP
     * headers, thumbnail tiles, and download/local checks land on the right
     * source per episode instead of MergedSource (which never fetches
     * anything itself - see MergedSource's doc comment).
     */
    private suspend fun resolveEpisodeAnimeAndSource(displayAnime: Anime, episode: Episode): Pair<Anime, AnimeSource>? {
        val episodeAnimeId = episode.anime_id ?: return null
        if (episodeAnimeId == displayAnime.id) {
            val source = stateData.value.currentSource ?: sourceManager.getOrStub(displayAnime.source)
            return displayAnime to source
        }
        val anime = getAnime.await(episodeAnimeId) ?: return null
        val source = sourceManager.getOrStub(anime.source)
        return anime to source
    }
    // <-- AM (MERGED_SOURCES)

    fun nextEpisode(next: Boolean, autoplay: Boolean = false) {
        // AM (EXTERNAL_FOLDER_GRANT) -->
        // An external session navigates its folder, not a playlist of episodes -
        // changeEpisode() below takes an episode id it has none of. Branching
        // here keeps that function about episodes, which every other caller of it
        // is.
        if (externalSource != null) {
            nextExternal(next)
            return
        }
        // <-- AM (EXTERNAL_FOLDER_GRANT)
        // AM (CONTENT_AGNOSTIC_SESSION) -->
        // Stepping one position and asking the queue what is there, instead of
        // indexing currentPlaylist directly. keyAt() returns null for an index
        // outside the queue, which folds the old bounds check and the
        // null-episode-id check into one answer.
        val queue = playbackQueue ?: return
        val currentIndex = stateData.value.currentPlaylistIndex
        val newIndex = if (next) currentIndex + 1 else currentIndex - 1
        val target = queue.keyAt(newIndex) as? SessionKey.Library ?: return

        changeEpisode(target.episodeId, autoplay)
        // <-- AM (CONTENT_AGNOSTIC_SESSION)
    }

    // AM (EPISODE_CHANGE_RACE_FIX) -->
    // Was PlayerMediaHolder's currentSkipJob, scoped to the now-deleted background
    // skip path only (BACKGROUND_SKIP_RACE_FIX). That protection never made it
    // into changeEpisode() itself, because changeEpisode() was previously only
    // ever called from foreground UI taps, naturally rate-limited by a human and
    // by the UI disabling itself while isLoadingEpisode is true - concurrent
    // overlapping calls essentially never happened in practice. Now that every
    // skip trigger (autoplay-on-EOF, external media-button presses, backgrounded
    // or not) routes through this exact function unconditionally, that assumption
    // no longer holds: a skip that hasn't finished loading (e.g. one arriving
    // while backgrounded, with the notification/PIP UI never actually blocking a
    // second one from firing) followed by another before the first completes
    // means two independent loadEpisode() calls both running loadfile() against
    // mpv, neither aware of the other, each eventually committing its own
    // episode as "current" whichever happens to finish last - confirmed
    // reproducible via: autoplay-on-EOF (or an external skip) left pending while
    // backgrounded, then Next/Previous pressed again after reopening - the first,
    // never-cancelled call's result still lands, on top of the second one's,
    // giving a predictable extra +1 (or a net-zero forward+back) beyond what was
    // actually requested. Cancelling any still-in-flight change before starting a
    // new one, at this single shared implementation, is the correct fix - not
    // re-splitting this back into two separately-guarded paths.
    private var episodeChangeJob: Job? = null
    // <-- AM (EPISODE_CHANGE_RACE_FIX)

    // AM (LOAD_FAILURE_NOT_FATAL) -->
    /**
     * Suspends until the in-flight episode/anime change has settled.
     *
     * [changeEpisode], [changeAnime] and the initial load are fire-and-forget
     * - they assign [episodeChangeJob] and return - so a caller that needs to
     * know WHAT the session ended up on has to wait for the job rather than
     * assume the request it made is what landed. Since a failed load now rolls
     * the session back, "the request" and "what is playing" are no longer the
     * same thing, and PlayerHostScreen's reconciliation bookkeeping reads the
     * real state through this instead of recording its own request as fact.
     *
     * Joining a job that a newer change has already replaced waits on the
     * newer one, which is correct: the newer change is the one that decides
     * where the session lands.
     */
    suspend fun awaitEpisodeChange() {
        episodeChangeJob?.join()
    }
    // <-- AM (LOAD_FAILURE_NOT_FATAL)

    /** Switches playback to [episodeId]; [autoPlay] indicates an automatic transition. */
    fun changeEpisode(episodeId: Long?, autoPlay: Boolean = false) {
        if (stateData.value.isCasting) {
            castManager.stopRemoteMediaClient()
            updateCastUiData { it.copy(isLoadingEpisode = true) }
        } else {
            pause()
            clearTracks()
        }

        // AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
        rememberRecentEpisodePosition()
        // <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)

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

        // AM (EPISODE_CHANGE_RACE_FIX) -->
        // See episodeChangeJob's own doc comment just above.
        episodeChangeJob?.cancel()
        episodeChangeJob = viewModelScope.launch {
            // <-- AM (EPISODE_CHANGE_RACE_FIX)
            // AM (PLAYLIST_SEARCH_SCOPE) -->
            // Submitting a search and picking an episode is one action, so the
            // rebuild belongs in this job, before the load - not in a collector
            // racing it. Two coroutines writing playlist state during a switch
            // is how the index ended up anchored on whichever finished last.
            stateData.value.currentAnime?.let { anime ->
                if (PlaylistSearchScope.queryFor(anime.id) != stateData.value.playlistSearchQuery) {
                    setupEpisodeList(anime)
                }
            }
            // <-- AM (PLAYLIST_SEARCH_SCOPE)
            // AM (LOAD_FAILURE_NOT_FATAL) -->
            // An entry whose source has nothing left to give is stepped over
            // rather than played. Both of the old outcomes were wrong for a play
            // order: an empty hoster list raised InitialEpisodeError, whose only
            // handler finishes the legacy Activity and which nothing subscribes
            // to here at all, and a null one fell through to a bare logcat, so
            // the order simply stopped on a dead entry with no explanation.
            //
            // The walk is bounded by the playlist: each attempt advances the
            // index (loadEpisode -> setupEpisode), and it ends at the last entry.
            // Failures are counted so the user gets one message at the end of the
            // run instead of one per dead entry.
            var targetId = episodeId
            var skipped = 0

            while (true) {
                val switchMethod = loadEpisode(targetId)

                if (switchMethod == null) {
                    updateUiData { it.copy(isLoadingEpisode = false, isLoadingHosters = false) }
                    if (skipped > 0) {
                        showMessage(AMMR.strings.skipped_missing_sources, skipped)
                    } else if (stateData.value.currentAnime != null && !autoPlay) {
                        showMessage(AYMR.strings.no_next_episode)
                    }
                    return@launch
                }

                if (!switchMethod.hosterList.isNullOrEmpty()) {
                    updateUiData { it.copy(isLoadingHosters = false) }
                    if (skipped > 0) {
                        showMessage(AMMR.strings.skipped_missing_sources, skipped)
                    }
                    loadHosters(
                        hosterList = switchMethod.hosterList,
                        hosterIndex = -1,
                        videoIndex = -1,
                    )
                    if (pipEpisodeToasts) {
                        _eventFlow.emit(Event.EpisodeTitle(switchMethod.episodeTitle))
                    }
                    return@launch
                }

                skipped++
                val nextIndex = stateData.value.currentPlaylistIndex + 1
                val nextId = stateData.value.currentPlaylist.getOrNull(nextIndex)?.id
                if (nextId == null) {
                    // Nothing loaded, so no later stage will clear the episode
                    // spinner - it has to come down here or the player buffers
                    // forever on an order that has run out.
                    updateUiData { it.copy(isLoadingEpisode = false, isLoadingHosters = false) }
                    showMessage(AMMR.strings.skipped_missing_sources_none_left, skipped)
                    return@launch
                }
                targetId = nextId
            }
            // <-- AM (LOAD_FAILURE_NOT_FATAL)
        }
    }

    // AM (CROSS_ANIME_IN_PLACE_SWITCH) -->
    /**
     * Cross-anime ("playlist") counterpart of [changeEpisode]: switches this LIVE
     * session to a different anime entirely, in place - same ViewModel, same
     * player, same surface, same dummy-pip window. mpv only ever sees a new
     * loadfile, exactly like an episode change. The old teardown-and-relaunch
     * for this case (CROSS_SERIES_TEARDOWN_RELAUNCH_FIX) predates the
     * persistent-host architecture and threw away the whole session - holder,
     * pip window state, auto-enter pip registration - on every playlist switch,
     * which is also what made recents-button pip entry flaky afterward.
     *
     * Prep mirrors changeEpisode() (pause, position save, UI reset, job
     * cancel); the load itself reuses [init] wholesale - everything init does
     * per fresh session (source, trackers, episode list, hosters, user-data
     * props) is exactly the set a cross-anime switch needs. Also serves as the
     * initial load for a fresh ViewModel (currentAnime == null): routing that
     * through here too means every load this session ever performs serializes
     * through episodeChangeJob.
     */
    fun changeAnime(
        animeId: Long,
        episodeId: Long,
        hostList: String,
        hostIndex: Int,
        vidIndex: Int,
        // AM (PLAYBACK_QUEUE)
        continuesQueue: Boolean = false,
    ) {
        if (stateData.value.currentAnime?.id == animeId) {
            // Same anime is changeEpisode()'s case - never let a caller
            // route it through the heavier cross-anime path by accident.
            changeEpisode(episodeId)
            return
        }
        // AM (MERGED_SOURCES) -->
        // Different entry, but the episode ALREADY PLAYING. A merged entry and
        // its children share episodes, so opening the one that's on screen from
        // the other entry is not a content switch at all - only the surrounding
        // playlist differs. Reloading it would restart the video from the
        // resume position and drop the live position, so re-host in place
        // instead. hostList guards an explicit hoster/quality request, which
        // does need a real reload.
        if (hostList.isBlank() && stateData.value.currentEpisode?.id == episodeId) {
            episodeChangeJob?.cancel()
            episodeChangeJob = viewModelScope.launch {
                if (!rehostPlaylist(animeId)) {
                    // AM (PLAYBACK_QUEUE)
                    switchToAnime(animeId, episodeId, hostList, hostIndex, vidIndex, continuesQueue)
                }
            }
            return
        }
        episodeChangeJob?.cancel()
        episodeChangeJob = viewModelScope.launch {
            // AM (PLAYBACK_QUEUE)
            switchToAnime(animeId, episodeId, hostList, hostIndex, vidIndex, continuesQueue)
        }
    }

    /**
     * The original body of [changeAnime] - a genuine cross-anime switch, which
     * always reloads the episode. Extracted so [changeAnime] can fall back to
     * it from inside episodeChangeJob when [rehostPlaylist] turns out not to
     * apply, without re-entering changeAnime() and cancelling the very job it
     * is running on.
     */
    private suspend fun switchToAnime(
        animeId: Long,
        episodeId: Long,
        hostList: String,
        hostIndex: Int,
        vidIndex: Int,
        // AM (PLAYBACK_QUEUE)
        continuesQueue: Boolean = false,
    ) {
        // <-- AM (MERGED_SOURCES)
        // AM (LOAD_FAILURE_NOT_FATAL) -->
        // Taken before the teardown below, while the state still describes what is
        // actually loaded in mpv.
        val snapshot = captureSession()
        val hadLiveSession = stateData.value.currentVideo != null
        // <-- AM (LOAD_FAILURE_NOT_FATAL)

        if (stateData.value.isCasting) {
            castManager.stopRemoteMediaClient()
            updateCastUiData { it.copy(isLoadingEpisode = true) }
        } else {
            pause()
            clearTracks()
        }

        rememberRecentEpisodePosition()

        // AM (NEXT_EPISODE_PREFETCH) -->
        // The playlist itself is about to be replaced, so any prefetched "next"
        // belongs to a playlist that no longer applies.
        clearPrefetch()
        // <-- AM (NEXT_EPISODE_PREFETCH)

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

        // AM (MERGED_SOURCES) -->
        // episodeChangeJob is now owned by changeAnime()/the caller - this runs
        // inside it rather than starting its own.
        val (initResult, loadResult) = init(
            animeId = animeId,
            initialEpisodeId = episodeId,
            hostList = hostList,
            hostIndex = hostIndex,
            vidIndex = vidIndex,
            // AM (PLAYBACK_QUEUE)
            continuesQueue = continuesQueue,
        )
        logcat(LogPriority.INFO) {
            "CROSS_ANIME_IN_PLACE_SWITCH init() returned initResult=$initResult loadResult=$loadResult"
        }

        // AM (LOAD_FAILURE_NOT_FATAL) -->
        // loadResult used to be logged and then ignored, so a failed init fell
        // through into loadHosters() anyway. That is wrong twice over: the empty
        // list makes loadHosters() raise "No available videos", which both hides
        // the real reason (a 404 from a deleted entry's page, say) and used to
        // crash the process on its way out - and on the other branch, init()'s
        // defaultResult carries the PREVIOUS anime's hoster list, which would have
        // been loaded under the new anime's state.
        val failure: Throwable? = loadResult.exceptionOrNull()
            ?: if (loadResult.getOrDefault(false)) null else IllegalStateException("Unknown error")
        if (failure != null) {
            if (hadLiveSession) {
                restoreSession(snapshot)
            } else {
                updateUiData { it.copy(isLoadingEpisode = false, isLoadingHosters = false) }
            }
            reportLoadFailure(failure)
            return
        }
        // <-- AM (LOAD_FAILURE_NOT_FATAL)

        updateUiData { it.copy(isLoadingHosters = false) }
        loadHosters(
            hosterList = initResult.hosterList ?: emptyList(),
            hosterIndex = initResult.videoIndex.first,
            videoIndex = initResult.videoIndex.second,
        )
        // <-- AM (MERGED_SOURCES)
    }
    // <-- AM (CROSS_ANIME_IN_PLACE_SWITCH)

    // AM (MERGED_SOURCES) -->
    /**
     * Moves the live session under a different library entry WITHOUT touching
     * playback: same player, same loaded file, same position, same
     * currentEpisodeAnime/currentEpisodeSource (the episode's real owner hasn't
     * changed - only the entry it's being browsed through has). Rebuilds the
     * playlist so next/previous, the seekbar's episode context and the entry's
     * own filters/sort come from the new host, and repoints everything scoped
     * to the library entry - trackers, skip-intro length, incognito, the mpv
     * user-data props - at it.
     *
     * Returns false without mutating anything if [animeId] can't host the
     * current episode, so the caller can fall back to a real switch. The one
     * post-mutation false is a genuine invariant break (setupEpisodeList
     * guarantees the selected episode is in the list it builds); falling back
     * there is still correct, since switchToAnime() overwrites all of it.
     */
    private suspend fun rehostPlaylist(animeId: Long): Boolean {
        val currentEpisodeId = stateData.value.currentEpisode?.id ?: return false
        val anime = getAnime.await(animeId) ?: return false
        sourceManager.isInitialized.first { it }

        val canHost = getEpisodeOrder.await(anime).any { it.id == currentEpisodeId }
        if (!canHost) return false

        val source = sourceManager.getOrStub(anime.source)
        val incognito = getIncognitoState.await(anime.source)
        updateStateData { it.copy(currentAnime = anime, currentSource = source, incognitoMode = incognito) }
        updateUiData { it.copy(animeTitle = anime.title) }
        episodeId = currentEpisodeId

        setupTrackers(anime.id)
        setupEpisodeList(anime)

        val episode = stateData.value.currentPlaylist.firstOrNull { it.id == currentEpisodeId }
        if (episode == null) {
            logcat(LogPriority.ERROR) {
                "MERGED_SOURCES rehostPlaylist lost episode=$currentEpisodeId in anime=$animeId playlist"
            }
            return false
        }
        setupEpisode(episode)

        val skipIntroLength = getAnimeSkipIntroLength()
        updateCastUiData { it.copy(skipIntroLength = skipIntroLength.toLong()) }
        val parentTitle = anime.parentId?.let { getAnime.await(it)?.title } ?: ""
        setPropertyString("user-data/current-anime/anime-title", anime.title)
        setPropertyString("user-data/current-anime/parent-title", parentTitle)
        setPropertyInt("user-data/current-anime/intro-length", skipIntroLength)
        setPropertyString(
            "user-data/current-anime/category",
            getCategories.await(anime.id).joinToString { it.name },
        )
        return true
    }
    // <-- AM (MERGED_SOURCES)

    // === Controls ===

    fun onKey(keyEvent: KeyEvent): Boolean {
        return player.onKey(keyEvent)
    }

    fun updateHasPip(value: Boolean) {
        updateStateData { it.copy(isPipAvailable = value) }
    }

    // AM (PIP_AUTOENTER_DURING_LOAD_FIX) -->
    /**
     * The pause state a PiP auto-enter registration should read.
     *
     * During a load, playbackData.paused is true for no reason other than
     * loadVideo()'s freeze - it says nothing about what playback will do once
     * the file arrives. The reactive setPictureInPictureParams() push on every
     * pause change therefore registers setAutoEnterEnabled(false) for the
     * whole load/buffer window, and the OS's auto-enter (the only path a
     * Recents press has) reads exactly that stale registration: PiP cannot be
     * entered until the load finishes. previousPauseState is the answer
     * checkFileLoaded() will apply, so it - not the transient freeze - is the
     * intent to register.
     */
    fun pauseStateForPipRegistration(): Boolean {
        val ui = uiData.value
        return if (ui.isLoadingEpisode) {
            ui.previousPauseState ?: playbackData.value.paused
        } else {
            playbackData.value.paused
        }
    }
    // <-- AM (PIP_AUTOENTER_DURING_LOAD_FIX)

    // AM (UNIFIED_CAST_PLAY_PAUSE) -->
    // Was two separate paths: this ViewModel's pause()/unpause()/pauseUnpause()
    // touched mpv only, while the CastScreen play/pause button dispatched
    // CastManagerEvent.PlayPause straight to CastManager, bypassing the
    // ViewModel entirely (see PlayerScreen's onCastManagerEvent wiring). Since
    // the unified MediaSession.Callback (buildMediaSessionCallback() above)
    // calls these same three functions regardless of whether an Activity/UI is
    // attached, that meant a backgrounded play/pause command while casting hit
    // local mpv instead of the actual cast session - the two states silently
    // desynced and the TV never responded. These now branch on isCasting and
    // are the single path either caller (MediaSession callback or CastScreen's
    // button, see PlayerScreen.kt) goes through - CastManager.handleCastManagerEvent
    // is only ever invoked from here for play/pause now.
    //
    // CastManagerEvent.PlayPause is toggle-only (it reads castState.value.playing
    // itself), so pause()/unpause() guard on the current playing state before
    // firing it, to preserve their explicit (not toggle) semantics and stay
    // idempotent - onPlay()/onPause() above call these directly, not
    // pauseUnpause().
    fun pauseUnpause() {
        if (stateData.value.isCasting) {
            castManager.handleCastManagerEvent(CastManagerEvent.PlayPause)
            return
        }
        // AM (EOF_REPLAY_NO_RELOAD) -->
        if (restartIfAtEof()) return
        // <-- AM (EOF_REPLAY_NO_RELOAD)
        mpvCommand("cycle", "pause")
    }

    // AM (EOF_REPLAY_NO_RELOAD) -->
    /**
     * Handles a play request made while playback is sitting at EOF - the last
     * episode in a playlist finishing is the common way to get there, since nothing
     * advances past it.
     *
     * The file is STILL LOADED at that point (keep-open=yes), so playing it again is
     * a seek, never a reload: no changeEpisode(), no loadEpisode(), no hoster
     * re-resolution. It also can't be left to the plain `cycle pause` below, because
     * mpv's own handle_keep_open() re-pauses immediately at EOF unless the position
     * actually moves first - so an unpause alone visibly does nothing.
     *
     * Returns true when it handled the request.
     */
    private fun restartIfAtEof(): Boolean {
        if (player.isReleased) return false
        if (mpv.getPropertyBoolean("eof-reached") != true) return false

        mpvCommand("seek", "0", "absolute")
        setPropertyBoolean("pause", false)
        updatePlaybackData { it.copy(paused = false, position = 0) }
        return true
    }
    // <-- AM (EOF_REPLAY_NO_RELOAD)
    fun pause() {
        pauseInternal()
        // AM (PAUSE_DURING_LOAD_WINS) -->
        recordPauseIntentDuringLoad(shouldPause = true)
        // <-- AM (PAUSE_DURING_LOAD_WINS)
    }

    // AM (PAUSE_DURING_LOAD_WINS) -->
    /**
     * Pauses without expressing an intent about what should happen after the
     * current load - for loadVideo()'s own freeze, which pauses precisely BECAUSE
     * a load is starting. Routing that through pause() would have it record
     * "paused" as the post-load answer and leave every episode paused on arrival.
     */
    // AM (PERSIST_ON_CADENCE) -->
    // Every pause routes through here, including the freeze before a load and the
    // one before PIP entry, so persisting on it covers the moments where the app is
    // most likely to be killed next.
    // <-- AM (PERSIST_ON_CADENCE)
    private fun pauseInternal() {
        if (stateData.value.isCasting) {
            if (castManager.castState.value.playing) {
                castManager.handleCastManagerEvent(CastManagerEvent.PlayPause)
            }
            return
        }
        setPropertyBoolean("pause", true)

        // PiP needs the state immediately
        updatePlaybackData { it.copy(paused = true) }

        // AM (PERSIST_ON_CADENCE) -->
        // AM (IDLE_SESSION_COST_FIX) -->
        // Was persistPositionNow() here. Moved to PlayerMediaHolder's PauseChanged
        // observer, which sees every pause rather than only the ones routed through
        // this function - see that observer's own note. Leaving it here as well would
        // mean two writes for every pause that does come through here, with the holder
        // covering the rest, which is exactly the kind of split-writer arrangement
        // HOLDER_PAUSE_STATE_SYNC_FIX and MEDIASESSION_SINGLE_WRITER_FIX removed
        // elsewhere in this flow.
        // <-- AM (IDLE_SESSION_COST_FIX)
        // <-- AM (PERSIST_ON_CADENCE)
    }
    // <-- AM (PAUSE_DURING_LOAD_WINS)

    // AM (PAUSE_DURING_LOAD_WINS) -->
    /**
     * Keeps [PlayerUiData.previousPauseState] in step with the most recent
     * explicitly expressed intent while a load is in flight.
     *
     * That field is not a record of the past - it is the answer to "what should the
     * pause state be once this file finishes loading", captured when the load
     * starts and applied by [checkFileLoaded]. Nothing updated it afterwards, so a
     * pause arriving DURING the load wrote through to mpv and was then undone the
     * moment the file loaded and the stale pre-load answer was applied. Dismissing
     * a video before it had loaded therefore started it playing.
     *
     * Updating the slot is the fix rather than ordering the two events: whoever
     * expressed an intent last is the one the load should honour, whenever it
     * happens to finish.
     */
    private fun recordPauseIntentDuringLoad(shouldPause: Boolean) {
        if (!uiData.value.isLoadingEpisode) return
        updateUiData { it.copy(previousPauseState = shouldPause) }
    }
    // <-- AM (PAUSE_DURING_LOAD_WINS)
    fun unpause() {
        if (stateData.value.isCasting) {
            if (!castManager.castState.value.playing) {
                castManager.handleCastManagerEvent(CastManagerEvent.PlayPause)
            }
            return
        }
        // AM (EOF_REPLAY_NO_RELOAD) -->
        // Every non-toggle play entry point lands here - the PIP play action, the
        // MediaSession callback, the dummy-pip button - so the EOF case has to be
        // handled here too, not just in pauseUnpause() above.
        if (restartIfAtEof()) return
        // <-- AM (EOF_REPLAY_NO_RELOAD)
        setPropertyBoolean("pause", false)
        updatePlaybackData { it.copy(paused = false) }

        // AM (PAUSE_DURING_LOAD_WINS) -->
        // Symmetric: hitting play while a file is still loading should survive the
        // load too, not be reverted to a pre-load paused snapshot.
        recordPauseIntentDuringLoad(shouldPause = false)
        // <-- AM (PAUSE_DURING_LOAD_WINS)
    }
    // <-- AM (UNIFIED_CAST_PLAY_PAUSE)

    // AM (MEDIA_SESSION_FALLBACK_CALLBACK) -->
    /**
     * Re-syncs [playbackData]'s paused flag from mpv's actual property. Called once from
     * PlayerActivity.onServiceConnected() right after (re)binding to the Service-owned
     * player - mpv is the only reliable source of truth at that point, since play/pause
     * may have been driven by PlayerMediaHolder's fallback MediaSession callback (see
     * PlayerMediaHolder.setPaused) while this Activity/ViewModel didn't exist to keep its
     * own playbackData in sync. Without this, a freshly constructed ViewModel's default
     * paused state could silently disagree with what mpv is actually doing.
     */
    fun reconcilePausedFromPlayer() {
        val actuallyPaused = mpv.getPropertyBoolean("pause") ?: return
        updatePlaybackData { it.copy(paused = actuallyPaused) }
    }
    // <-- AM (MEDIA_SESSION_FALLBACK_CALLBACK)

    // AM (STREAMING_PLAYBACK_SIGNAL) -->
    /**
     * Publishes whether this session is currently pulling media off the network,
     * so the downloader can yield to it (see Downloader's launchDownloaderJob).
     *
     * A local file is a path, a content:// or a file:// URI; everything else -
     * including a source's own localhost HTTP server, which proxies a remote
     * fetch - is network traffic competing with whatever the downloader is doing.
     */
    private fun publishStreamingPlaybackState() {
        val url = stateData.value.currentVideo?.videoUrl
        val isLocal = url == null ||
            url.startsWith("/") ||
            url.startsWith("content://") ||
            url.startsWith("file://")
        PlayerMediaHolder.setStreamingPlaybackActive(!isLocal && !playbackData.value.paused)
    }
    // <-- AM (STREAMING_PLAYBACK_SIGNAL)

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
            )
        }
    }

    fun hideControls() {
        updateUiData {
            it.copy(
                controlsShown = false,
            )
        }
    }

    // AM (SYSTEM_BARS_DERIVED_FROM_CONTROLS) -->
    // statusBarShown no longer exists as independent state. Every past
    // "player controls visible but system bars not" (and vice versa) bug
    // - reopen-from-notification stuck bars, dummy-pip expand leaving
    // bars up, paused-playback timers hiding bars under visible controls
    // - traced back to some caller setting the two separately and a
    // later transition only fixing one of them. Bar visibility is now
    // DERIVED at the single place that applies it (PlayerScreen's
    // SystemBarOverlay): bars show iff (controlsShown && the
    // showSystemStatusBar preference) OR the dummy pip is up (it floats
    // over ordinary app UI, which always has bars). This function keeps
    // the controls-half for the dummy-pip paths; the bars half needs no
    // write at all anymore.
    // <-- AM (SYSTEM_BARS_DERIVED_FROM_CONTROLS)

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
        updatePlaybackData { it.copy(currentBrightness = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)) }
    }

    fun displayBrightnessSlider(show: Boolean) {
        updateUiData { it.copy(isBrightnessSliderShown = show) }
    }

    // AM (SYSTEM_VOLUME_PANEL) -->
    // Volume keys are routed per-surface in MainActivity/PlayerActivity
    // onKeyDown: dummy pip uses adjustStreamVolume(FLAG_SHOW_UI) (no
    // in-player slider exists there); casting falls through to the OS
    // (the system panel drives the CAST DEVICE's volume via the active
    // media route; adjusting the phone's stream volume mid-cast changes
    // nothing audible); fullscreen keeps the custom slider, the only UI
    // that can represent mpv's volume boost past 100%.
    // <-- AM (SYSTEM_VOLUME_PANEL)

    // Keys handled via adjustStreamVolume() (dummy pip) bypass
    // changeVolumeTo(), so the app's own copy of the volume (slider
    // state, persisted restore value) would go stale without this re-read.
    fun syncVolumeFromSystem() {
        val volume = audioManager.getVolume()
        playerPreferences.playerVolumeValue.set(volume)
        updatePlaybackData { it.copy(currentVolume = volume) }
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

    // AM (ASPECT_OVERRIDE_PICKER): two states, not three - Stretch is no longer a
    // viewing mode. Still here because gestures and custom buttons bind to it.
    private fun cycleAspectRatio() {
        val newAspectRatio = if (playerPreferences.aspectState.get() == VideoAspect.Crop) {
            VideoAspect.Fit
        } else {
            VideoAspect.Crop
        }

        setAspectRatio(newAspectRatio)
    }

    // AM (PINCH_ZOOM) -->
    /**
     * Zoom and pan are applied by the player's own graphicsLayer, NOT by mpv's
     * video-zoom/video-pan.
     *
     * mpv's window here is not the screen. FIXED_SURFACE_SIZE pins the surface to
     * the VIDEO's shape and scales that box down to fit, so the black bars are empty
     * space around the surface and mpv has no margin to letterbox into. video-zoom
     * therefore magnifies and crops strictly inside the video-shaped box, leaving
     * the bars exactly where they were however far it is pushed. Scaling the surface
     * box instead covers them, because that box IS the video.
     *
     * The layer scales a buffer mpv has already rendered, so past the fill factor it
     * magnifies screen-resolution pixels rather than resolving new detail from the
     * source. Handing that magnification to mpv instead was tried and removed: mpv
     * and the layer have no shared clock, so every transition between them showed a
     * frame of disagreement, and the pan had to drag the whole mechanism along
     * because at fill the surface has no room left to translate. One layer, one
     * source of truth, nothing to synchronise.
     */
    private val _videoZoom = MutableStateFlow(1f)
    val videoZoom = _videoZoom.asStateFlow()

    /** Pan in window-relative units: 0.5f is half the window across. */
    private val _videoPan = MutableStateFlow(0f to 0f)
    val videoPan = _videoPan.asStateFlow()

    /**
     * What the player's graphicsLayer applies, as SNAPSHOT state rather than a
     * StateFlow.
     *
     * The player reads these inside its graphicsLayer lambda, where Compose defers
     * the read to the draw phase: writing them invalidates the layer and nothing
     * recomposes. Through a StateFlow collected in composition they were read during
     * composition instead, so every pointer event - and those arrive faster than a
     * 120Hz panel refreshes - recomposed the whole surface subtree.
     */
    var layerZoom by mutableFloatStateOf(1f)
        private set
    var layerPanX by mutableFloatStateOf(0f)
        private set
    var layerPanY by mutableFloatStateOf(0f)
        private set

    fun setLayerTransform(zoom: Float, panX: Float, panY: Float) {
        layerZoom = zoom
        layerPanX = panX
        layerPanY = panY
    }

    /** Where a settle animation should land the layer, and a token to start it. */
    var settleTarget: Triple<Float, Float, Float>? = null
        private set
    private val _settleToken = MutableStateFlow(0L)
    val settleToken = _settleToken.asStateFlow()

    /**
     * How much of the window the fitted video covers on each axis, pushed in by the
     * player once it has laid the surface out. One axis is always 1.0 (the fit is
     * limited by it); the other is smaller, and that shortfall is the bars.
     */
    private var coverageX = 1f
    private var coverageY = 1f

    /** Indicator state last surfaced, to avoid an emission per frame. */
    private var shownZoomStep = -1
    private var lastIndicatorAtMs = 0L

    /** The detent a release would land on right now, or null. */
    private val _pendingZoomDetent = MutableStateFlow<Float?>(null)
    val pendingZoomDetent = _pendingZoomDetent.asStateFlow()

    fun setVideoCoverage(coverX: Float, coverY: Float) {
        if (!coverX.isFinite() || !coverY.isFinite() || coverX <= 0f || coverY <= 0f) return
        if (coverX == coverageX && coverY == coverageY) return
        coverageX = coverX
        coverageY = coverY
        clampPan()
        publishTransform()
    }

    /**
     * The zoom at which the video covers the window on both axes - the point the
     * black bars disappear. Equals 1x when the video already matches the window's
     * shape, in which case it is simply not a separate detent.
     */
    private fun fillZoom(): Float =
        (1f / minOf(coverageX, coverageY)).coerceIn(MIN_VIDEO_ZOOM, MAX_VIDEO_ZOOM)

    /**
     * Largest pan that keeps the zoomed video covering the window, per axis, in the
     * same window-relative units as [_videoPan].
     */
    private fun panLimits(): Pair<Float, Float> {
        val zoom = _videoZoom.value
        val maxX = ((coverageX * zoom - 1f) / 2f).coerceAtLeast(0f)
        val maxY = ((coverageY * zoom - 1f) / 2f).coerceAtLeast(0f)
        return maxX to maxY
    }

    private fun clampPan() {
        val (maxX, maxY) = panLimits()
        val (panX, panY) = _videoPan.value
        val clamped = panX.coerceIn(-maxX, maxX) to panY.coerceIn(-maxY, maxY)
        if (clamped != _videoPan.value) _videoPan.value = clamped
    }

    /**
     * The detent [zoom] would be pulled to if the fingers came up now, or null.
     *
     * Nothing is snapped while the gesture is live - the pinch stays free the whole
     * way, and the zone only decides where a release lands. 1x wins where the two
     * zones overlap.
     */
    private fun detentFor(zoom: Float): Float? {
        if (abs(zoom - MIN_VIDEO_ZOOM) / MIN_VIDEO_ZOOM < ZOOM_DETENT_ZONE) return MIN_VIDEO_ZOOM
        val fill = fillZoom()
        if (fill > MIN_VIDEO_ZOOM && abs(zoom - fill) / fill < ZOOM_DETENT_ZONE) return fill
        return null
    }

    private fun publishTransform() {
        val (panX, panY) = _videoPan.value
        setLayerTransform(_videoZoom.value, panX, panY)
    }

    /**
     * One pointer event's worth of pinch: scale and translation together, so a frame
     * that did both settles the state once.
     *
     * Pan deltas arrive as fractions of the window rather than px - the gesture
     * layer measures in its own coordinate space and the surface is a different
     * size, so px would not survive the trip.
     */
    fun transformVideoBy(scaleFactor: Float = 1f, deltaXFraction: Float = 0f, deltaYFraction: Float = 0f) {
        val previous = _videoZoom.value

        if (scaleFactor.isFinite() && scaleFactor > 0f && scaleFactor != 1f) {
            val next = (previous * scaleFactor).coerceIn(MIN_VIDEO_ZOOM, MAX_VIDEO_ZOOM)
            if (next != previous) {
                _videoZoom.value = next
                if (next == MIN_VIDEO_ZOOM) _videoPan.value = 0f to 0f
            }
        }

        val zoom = _videoZoom.value
        if (zoom > MIN_VIDEO_ZOOM && (deltaXFraction != 0f || deltaYFraction != 0f)) {
            val (panX, panY) = _videoPan.value
            _videoPan.value = Pair(
                panX + deltaXFraction * PAN_SPEED,
                panY + deltaYFraction * PAN_SPEED,
            )
        }
        clampPan()
        publishTransform()

        _pendingZoomDetent.value = detentFor(zoom)
        showZoomIndicator(zoom)
    }

    private fun showZoomIndicator(zoom: Float, force: Boolean = false) {
        // Quantised AND time-throttled. Each emission writes uiData, which recomposes
        // everything reading it and restarts the 2s dismissal coroutine in the
        // controls; a fast pinch crosses tenths far quicker than anyone can read them.
        val step = (zoom * ZOOM_INDICATOR_STEP).roundToInt()
        if (step == shownZoomStep) return
        val now = System.currentTimeMillis()
        if (!force && now - lastIndicatorAtMs < ZOOM_INDICATOR_MIN_INTERVAL_MS) return
        lastIndicatorAtMs = now
        shownZoomStep = step
        val update = if (zoom == fillZoom() && zoom > MIN_VIDEO_ZOOM) {
            PlayerUpdates.ShowTextResource(AYMR.strings.player_zoomed_to_fill)
        } else {
            PlayerUpdates.ShowText(formatZoom(step))
        }
        updateUiData { it.copy(playerUpdate = update) }
    }

    /**
     * Called when the fingers come up: pulls the zoom onto its pending detent, if
     * there is one. Returns true when a settle animation started, which is the
     * gesture layer's cue to tick the haptics.
     */
    fun settleVideoZoom(): Boolean {
        val target = _pendingZoomDetent.value
        _pendingZoomDetent.value = null
        if (target == null || target == _videoZoom.value) return false

        _videoZoom.value = target
        if (target == MIN_VIDEO_ZOOM) _videoPan.value = 0f to 0f
        clampPan()
        shownZoomStep = -1
        showZoomIndicator(target, force = true)
        // The layer is NOT moved to the target here; the player animates it there.
        val (panX, panY) = _videoPan.value
        settleTarget = Triple(target, panX, panY)
        _settleToken.value = _settleToken.value + 1
        return true
    }

    /** The settle animation has landed. */
    fun onZoomSettled() {
        settleTarget = null
    }

    /**
     * Back to 1x, unpanned. The zoom is session state rather than per-file, so
     * without this it would outlive the episode it was applied to.
     */
    fun resetVideoZoom() {
        shownZoomStep = -1
        _pendingZoomDetent.value = null
        settleTarget = null
        _videoZoom.value = MIN_VIDEO_ZOOM
        _videoPan.value = 0f to 0f
        publishTransform()
        // Clears anything an earlier build left on mpv's own transform; the zoom
        // itself never writes these.
        clearMpvTransform()
    }

    private fun clearMpvTransform() {
        setPropertyDouble("video-zoom", 0.0)
        setPropertyDouble("video-pan-x", 0.0)
        setPropertyDouble("video-pan-y", 0.0)
    }

    /** Tenths of a zoom factor as "1.1x". */
    private fun formatZoom(step: Int) = "${step / ZOOM_INDICATOR_STEP}.${step % ZOOM_INDICATOR_STEP}x"
    // <-- AM (PINCH_ZOOM)

    fun setAspectRatio(aspect: VideoAspect, showConfirmation: Boolean = true) {
        // AM (PINCH_ZOOM): a new aspect mode redraws the fitted video, so the
        // coverage the pan limits are built on no longer describes it.
        resetVideoZoom()

        // AM (ASPECT_OVERRIDE_PICKER) -->
        // Stretch is retired. Anyone whose stored preference still says so is moved
        // to the equivalent pair - fit the picture, override the ratio to the
        // screen's - once, here, rather than through a store migration, because this
        // is the one place the value is ever applied.
        @Suppress("DEPRECATION")
        if (aspect == VideoAspect.Stretch) {
            playerPreferences.aspectOverride.set(VideoAspectOverride.Screen)
            setAspectRatio(VideoAspect.Fit, showConfirmation)
            return
        }

        val pan = if (aspect == VideoAspect.Crop) 1.0 else 0.0
        setPropertyDouble("panscan", pan)
        playerPreferences.aspectState.set(aspect)
        applyAspectOverride(playerPreferences.aspectOverride.get())
        // <-- AM (ASPECT_OVERRIDE_PICKER)
        if (showConfirmation) {
            updateUiData { it.copy(playerUpdate = PlayerUpdates.AspectRatio(aspect)) }
        }
    }

    // AM (ASPECT_OVERRIDE_PICKER) -->
    /**
     * Imposes a display aspect ratio on the video, for files tagged wrong. Separate
     * from [setAspectRatio] on purpose: how the picture fits the screen and what
     * shape the picture actually is are different questions, and conflating them is
     * what made the old Stretch mode a repair tool disguised as a viewing mode.
     */
    fun setAspectOverride(override: VideoAspectOverride, showConfirmation: Boolean = true) {
        playerPreferences.aspectOverride.set(override)
        applyAspectOverride(override)
        if (showConfirmation) {
            updateUiData { it.copy(playerUpdate = PlayerUpdates.ShowTextResource(override.titleRes)) }
        }
    }

    private fun applyAspectOverride(override: VideoAspectOverride) {
        // -1 hands the decision back to the file, which is mpv's own default.
        val ratio = when {
            override.useScreen -> screenAspectRatio
            else -> override.ratio ?: -1.0
        }
        setPropertyDouble("video-aspect-override", ratio)
    }
    // <-- AM (ASPECT_OVERRIDE_PICKER)

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

        // AM (PERSIST_ON_CADENCE) -->
        // A seek is the other moment the stored position becomes wrong in a way
        // the user would notice if the app died before the next backstop write.
        // Passing the target explicitly: playbackData still holds the pre-seek
        // position at this point, so the default would persist the place the user
        // just left rather than the one they jumped to.
        persistPositionNow(position)
        // <-- AM (PERSIST_ON_CADENCE)

        viewModelScope.launch {
            if (!playbackData.value.paused) {
                _eventFlow.emit(Event.UpdateDiscordRPC(false, position))
            }
        }
    }

    fun selectChapter(index: Int) {
        setPropertyInt("chapter", index)
        dismissSheet()
        unpause()
    }

    private var lastThumbnailFetch = 0L

    // AM (PERSIST_ON_CADENCE) -->
    /** Position, in seconds, at the last database write - see onSecondReached. */
    private var lastPersistedPositionSeconds = 0

    // AM (CONTENT_AGNOSTIC_SESSION) -->
    // historyWrittenForEpisodeId and syncRequestedForEpisodeId moved into
    // LibraryProgressStore as plain flags. They were ids held here only so a
    // once-per-episode guard had something to compare the current episode
    // against; a store that exists for exactly one episode needs no comparison.
    //
    // Recreated on demand rather than in setupEpisode() so a save that lands
    // before (or after) an episode switch still writes through a store for the
    // episode it is actually saving, instead of depending on which of the two
    // ran first.
    private var progressStore: PlaybackProgressStore? = null

    /** The store for [episode], creating one if the last was for something else. */
    private fun progressStoreFor(episode: Episode): PlaybackProgressStore? {
        val episodeId = episode.id ?: return null
        val animeId = stateData.value.currentAnime?.id ?: return null
        val key = SessionKey.Library(animeId = animeId, episodeId = episodeId)
        progressStore?.takeIf { it.sessionKey == key }?.let { return it }
        return LibraryProgressStore(
            episode = episode,
            sessionKey = key,
            context = context,
            getEpisode = getEpisode,
            updateEpisode = updateEpisode,
            upsertHistory = upsertHistory,
            playerPreferences = playerPreferences,
            syncPreferences = syncPreferences,
            isIncognito = { stateData.value.incognitoMode },
            hasTrackers = { stateData.value.hasTrackers },
        ).also { progressStore = it }
    }
    // <-- AM (CONTENT_AGNOSTIC_SESSION)

    /**
     * Writes the current position now, for the moments where durability actually
     * matters. Resets the cadence so an event-driven save and the backstop never
     * double up.
     */
    // AM (IDLE_SESSION_COST_FIX) -->
    // Public now: PlayerMediaHolder's PauseChanged observer is the single pause-time
    // flush, since it is the only place that sees every pause regardless of cause.
    // See that observer for why it moved out of pauseInternal().
    // <-- AM (IDLE_SESSION_COST_FIX)
    fun persistPositionNow(positionSeconds: Int = playbackData.value.position) {
        val episode = stateData.value.currentEpisode ?: return
        if (uiData.value.isLoadingEpisode) return

        // The caller may know a position the tick loop has not caught up to yet -
        // a seek target, most obviously - so the episode's own field is brought up
        // to date here rather than persisting whatever the last tick left behind.
        // Same seen/preserveWatchingPosition rule onSecondReached applies.
        if (!episode.seen || playerPreferences.preserveWatchingPosition.get()) {
            episode.last_second_seen = positionSeconds.toLong() * 1000L
        }
        lastPersistedPositionSeconds = positionSeconds
        saveWatchingProgress(episode)
    }
    // <-- AM (PERSIST_ON_CADENCE)

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
                    // AM (MERGED_SOURCES) -->
                    val source = stateData.value.currentEpisodeSource as? AnimeHttpSource ?: return@launchIO
                    // <-- AM (MERGED_SOURCES)

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
        // AM (CONTENT_AGNOSTIC_SESSION) -->
        // Position only, no history write - same as the saveEpisodeProgress()
        // call this replaces, which deliberately did not go through
        // saveWatchingProgress() and so never touched history.
        val store = progressStoreFor(currentEpisode) ?: return
        viewModelScope.launchNonCancellable {
            store.savePosition(writeSeenState = false)
        }
        // <-- AM (CONTENT_AGNOSTIC_SESSION)
    }

    // AM (SERVICE_OWNED_VIEWMODEL) -->
    // Public, not protected (the default for an override with no explicit
    // modifier is to inherit the parent's visibility, not widen to public) -
    // PlayerMediaHolder.release() now calls this directly once ViewModelProvider
    // no longer does. See release()'s own doc comment for why.
    public override fun onCleared() {
    // <-- AM (SERVICE_OWNED_VIEWMODEL)
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

        // AM (RECENT_EPISODE_POSITIONS_PERSISTED) -->
        // changeEpisode()'s own call to this only covers next/prev within the same
        // series - backing out of the player entirely to go watch something else never
        // goes through changeEpisode() at all, so without this, temp memory was never
        // actually written on the one path that's most of what "switching series"
        // means in practice. Same call, same guards (finished-episode clear, duration
        // buffer) as the in-series case - this is just the other real exit point.
        rememberRecentEpisodePosition()
        // <-- AM (RECENT_EPISODE_POSITIONS_PERSISTED)

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
        // AM (COMPLETION_RUNS_ONCE) -->
        // This condition stays true for every remaining tick of the episode once
        // the threshold is crossed - roughly the last 15% - and had no guard, so
        // updateEpisodeProgressOnComplete() re-ran once a second: a getTracks DB
        // read plus (until the tracker's own lastEpisodeSeen catches up) a refresh
        // and update over the network per tracker, a pending-deleter write, and a
        // seen-state save. It is a once-per-episode
        // transition; make it run once.
        if (position >= duration * progress && shouldTrack) {
            runEpisodeCompletion(currentEpisode)
        }
        // <-- AM (COMPLETION_RUNS_ONCE)

        // AM (PERSIST_ON_CADENCE) -->
        // The in-memory episode fields above are updated every tick, because that
        // is the live truth the UI and the position table read. Writing them to
        // the database every tick is a different question, and the answer was
        // never "every second": each save is a getEpisode read, an updateEpisode
        // write and a history upsert, so a 24-minute episode performed roughly
        // 1400 reads and 2800 writes to record a number that only has to survive
        // the app dying.
        //
        // Durability is event-shaped, not tick-shaped. Pause, seek, episode change
        // and teardown all persist explicitly; this is only the crash backstop
        // between those moments.
        if (position - lastPersistedPositionSeconds >= PERSIST_INTERVAL_SECONDS) {
            lastPersistedPositionSeconds = position
            saveWatchingProgress(currentEpisode)
        }
        // <-- AM (PERSIST_ON_CADENCE)

        // AM (NEXT_EPISODE_PREFETCH) -->
        if (duration > 0 && duration - position <= PREFETCH_LEAD_SECONDS) {
            prefetchNextEpisode()
        }
        // <-- AM (NEXT_EPISODE_PREFETCH)

        // AM (DOWNLOAD_AHEAD_ONCE) -->
        // Was re-evaluated on every tick past the 35% mark. downloadNextEpisodes()
        // ran two isDownload() checks synchronously before its own launchIO, and
        // this function runs on Main (propFlow("time-pos").launchIn(viewModelScope)),
        // so that was two SAF directory walks a second on the main thread for the
        // back two-thirds of every episode. Nothing it checks can change from one
        // second to the next in a way that matters.
        val inDownloadRange = position.toDouble() / duration > 0.35
        if (inDownloadRange && downloadAheadEpisodeId != currentEpisode.id) {
            downloadAheadEpisodeId = currentEpisode.id
            downloadNextEpisodes()
        }
        // <-- AM (DOWNLOAD_AHEAD_ONCE)
    }

    private suspend fun updateEpisodeProgressOnComplete(currentEp: Episode) {
        currentEp.seen = true
        // Reset resume position now that it's finished, otherwise it stays near the
        // "seen" threshold (close to the end) for anything reading it later.
        currentEp.last_second_seen = 0L
        updateTrackEpisodeSeen(currentEp)
        deleteEpisodeIfNeeded(currentEp)
        // Persist explicitly - not guaranteed to run after onSecondReached()'s own call.
        // writeSeenState = true: this call is the one intentionally making the episode
        // seen (natural completion), unlike every other saveWatchingProgress call site.
        saveWatchingProgress(currentEp, writeSeenState = true)

        // AM (MERGE_SETTINGS): the sweep that marked every same-numbered episode
        // in the playlist seen is gone with the "mark duplicate seen episode as
        // seen" preference. A merge's dedupe mode keeps one copy of a number in
        // the list to begin with, and TransferMergeEpisodeMarks carries seen onto
        // it when the visible copy changes, so there is nothing left to sweep.

        // AM (SYNC) -->
        val isSyncEnabled = syncPreferences.isSyncEnabled()
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        if (isSyncEnabled && syncTriggerOpt.syncOnEpisodeSeen) {
            SyncDataJob.startNow(context.workManager)
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
    // AM (TICK_NEVER_OWNS_SEEN) -->
    // writeSeenState is false for every routine per-second call - a live tick has no
    // business writing seen/bookmark at all, it only owns position/duration. Passing
    // null for those fields coalesces to "leave whatever's in the DB alone" (see the
    // episodes.sq update query), so a tick can never race an external mark-as-seen and
    // stomp it back to false, no matter how the timing lines up. Only a call that is
    // itself intentionally changing seen (episode naturally finishing) passes true.
    // <-- AM (TICK_NEVER_OWNS_SEEN)
    // AM (CONTENT_AGNOSTIC_SESSION) -->
    // saveEpisodeProgress() and saveEpisodeHistory() moved verbatim into
    // LibraryProgressStore (see PlaybackProgressStore.kt) - the DB writes, the
    // version-staleness check, WIPE_POSITION_ON_SEEN, SAVE_ONLY_WHAT_CHANGED and
    // both once-per-episode guards all behave as they did. What is left here is
    // the dispatch: WHERE progress goes is the store's business now, not the
    // ViewModel's, which is what gives a session with no library row somewhere
    // to write at all.
    private fun saveWatchingProgress(episode: Episode, writeSeenState: Boolean = false) {
        val store = progressStoreFor(episode) ?: return
        viewModelScope.launchNonCancellable {
            store.savePosition(writeSeenState)
            store.recordWatched()
        }
    }
    // <-- AM (CONTENT_AGNOSTIC_SESSION)

    // AM (PLAYER_ADD_TO_LIBRARY) -->
    /**
     * The entry screen's heart, for the episode list dialog, on the playlist's
     * own entry - and the same behaviour as there, including what it asks.
     *
     * The tap itself writes nothing: it opens the categories dialog, whose
     * checked set is the entry's library membership (at least one category puts
     * it in, none takes it out), so the same button adds and removes without a
     * misread tap doing either. Duplicates in the library are raised first, as
     * on the entry screen. With no categories to choose from there is nothing to
     * ask, and the entry is simply added or removed.
     *
     * See AnimeViewModel.toggleFavorite and applyFavoriteCategories, which this
     * follows.
     */
    fun toggleCurrentAnimeFavorite(checkDuplicate: Boolean = true) {
        val anime = stateData.value.currentAnime ?: return
        viewModelScope.launchIO {
            if (!anime.favorite && checkDuplicate) {
                val duplicates = getDuplicateLibraryAnime(anime)
                if (duplicates.isNotEmpty()) {
                    setDialog(Dialogs.DuplicateAnime(anime, duplicates))
                    return@launchIO
                }
            }

            val categories = getCategories.await()
            if (categories.isEmpty()) {
                // Nothing to ask: add or remove outright.
                applyFavoriteCategories(anime, emptyList(), hadCategoriesToChooseFrom = false)
                return@launchIO
            }

            // Whatever the entry is already filed under, which outlives a
            // removal, falling back to the default category for one that has
            // none - so the common case is one tap on OK.
            val checked = getCategories.await(anime.id).map { it.id }.ifEmpty {
                listOfNotNull(libraryPreferences.defaultCategory.get().toLong().takeIf { it > 0 })
            }
            setDialog(
                Dialogs.ChangeCategory(
                    anime = anime,
                    initialSelection = categories.mapAsCheckboxState { it.id in checked },
                ),
            )
        }
    }

    /**
     * The categories dialog's confirm: [categoryIds] decides membership. Empty
     * removes the entry (and offers to delete its downloads), anything else puts
     * it in the library with exactly those categories.
     *
     * [hadCategoriesToChooseFrom] separates "the user cleared every box" from
     * "there were no boxes": with no categories at all an empty list is just
     * what adding looks like, so it must not read as a removal.
     */
    fun applyFavoriteCategories(
        anime: Anime,
        categoryIds: List<Long>,
        hadCategoriesToChooseFrom: Boolean = true,
    ) {
        viewModelScope.launchIO {
            if (categoryIds.isEmpty()) {
                if (!anime.favorite) {
                    // Nothing checked on an entry that is not in the library:
                    // with boxes to check that is a cleared dialog and means
                    // nothing was asked for; with none it is what adding looks
                    // like, so add it uncategorised.
                    if (hadCategoriesToChooseFrom) return@launchIO
                    if (!updateAnime.awaitUpdateFavorite(anime.id, true)) return@launchIO
                    updateStateData { it.copy(currentAnime = anime.copy(favorite = true)) }
                    stateData.value.currentSource?.let { addTracks.bindEnhancedTrackers(anime, it) }
                    return@launchIO
                }
                if (!updateAnime.awaitUpdateFavorite(anime.id, false)) return@launchIO
                // Category rows left alone on purpose - see
                // AnimeViewModel.applyFavoriteCategories: they are what the
                // dialog offers back if the entry is added again.
                updateStateData { it.copy(currentAnime = anime.copy(favorite = false)) }
                // Only worth asking when there is something to delete - which a
                // local entry never has. AM (LOCAL_ENTRIES_HAVE_NO_DOWNLOADS)
                if (downloadManager.hasDeletableDownloads(anime)) {
                    setDialog(Dialogs.DeleteDownloadsAfterRemoval(anime))
                }
                return@launchIO
            }
            setAnimeCategories.await(anime.id, categoryIds)
            if (anime.favorite) return@launchIO
            if (!updateAnime.awaitUpdateFavorite(anime.id, true)) return@launchIO
            updateStateData { it.copy(currentAnime = anime.copy(favorite = true)) }
            stateData.value.currentSource?.let { addTracks.bindEnhancedTrackers(anime, it) }
        }
    }

    fun deleteDownloadsOf(anime: Anime) {
        val source = stateData.value.currentSource ?: return
        downloadManager.deleteAnime(anime, source)
    }
    // <-- AM (PLAYER_ADD_TO_LIBRARY)

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

        viewModelScope.launchIO {
            // AM (DOWNLOAD_AHEAD_ONCE) -->
            // Both checks moved inside launchIO (they were above it, on Main) and
            // onto the DownloadCache rather than a fresh SAF directory listing -
            // skipCache exists for the load path, which has to be certain before
            // choosing downloaded-vs-stream. This one only decides whether to queue
            // more downloads, where the cache is authoritative enough.
            val episodesAreDownloaded =
                episodeLoader.isDownload(currentEpisode.toDomainEpisode()!!, anime, skipCache = false) &&
                    episodeLoader.isDownload(nextEpisode.toDomainEpisode()!!, anime, skipCache = false)
            // <-- AM (DOWNLOAD_AHEAD_ONCE)
            if (!episodesAreDownloaded) {
                return@launchIO
            }
            val episodesToDownload = getNextEpisodes.await(anime.id, nextEpisode.id!!)
                .take(downloadAheadAmount)
            downloadManager.downloadEpisodes(anime, episodesToDownload)
        }
    }

    // AM (DOWNLOAD_AHEAD_ONCE) -->
    /** The episode whose download-ahead pass has already run - see onSecondReached. */
    private var downloadAheadEpisodeId: Long? = null
    // <-- AM (DOWNLOAD_AHEAD_ONCE)

    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /** Downloads [episodes] the ordinary way, owned by the entry they belong to. */
    fun downloadEpisodes(episodes: List<Episode>) {
        enqueueByOwner(episodes) { anime, owned ->
            // AM (LOCAL_SOURCE_DOWNLOAD) -->
            // One download action, with the destination following the entry
            // rather than the button. An entry that only exists because
            // something was opened from outside the app has no library of its
            // own to own the file, so it is saved into the local source, where
            // it becomes an entry the user can find again; everything else is
            // owned by the entry it came from, as before.
            if (anime.source == ExternalStreamSource.ID) {
                saveToLocalSource(anime, owned, entryName = externalAppLabel)
            } else {
                downloadManager.downloadEpisodes(anime, owned)
            }
            // <-- AM (LOCAL_SOURCE_DOWNLOAD)
        }
    }

    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /**
     * Saves [owned] into the local source, as plain videos under the site they
     * came from - see LocalSourceTarget.
     */
    private fun saveToLocalSource(anime: Anime, owned: List<DomainEpisode>, entryName: String? = null) {
        downloadManager.downloadEpisodesToLocalSource(anime, owned, entryName)
        // The refresh that turns the saved files into a real local entry runs
        // HERE rather than inside Downloader.
        //
        // It needs UpdateAnimeFromRemote, which needs SyncEpisodesWithSource,
        // which needs DownloadManager - so injecting it anywhere in the download
        // layer closes a dependency cycle the graph refuses to build. This
        // ViewModel already holds both interactors for the granted-folder
        // registration, so asking from here adds no dependency anywhere and the
        // download layer stays unaware of the library entirely.
        registerLocalSourceEntryWhenSaved(anime, owned.size, entryName)
    }
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)

    // AM (LOCAL_SOURCE_DOWNLOAD) -->
    /**
     * Waits for the queued saves to leave the download queue, then syncs the
     * local source entry they landed in.
     *
     * The same refresh the granted-folder path makes, for the same reason: it
     * extracts the cover and background from the video, writes them onto the
     * entry, syncs the episode list and starts thumbnail generation, none of
     * which a hand-written episode row would get.
     *
     * Tied to [viewModelScope], so closing the player before the download
     * finishes simply leaves the files to be picked up by the local source's
     * next refresh - the videos are on disk either way, and nothing is lost
     * beyond the entry appearing a little later.
     */
    private fun registerLocalSourceEntryWhenSaved(anime: Anime, queued: Int, entryName: String? = null) {
        if (queued <= 0) return
        val source = sourceManager.get(anime.source) as? AnimeHttpSource ?: return
        // Built the same way the save itself builds it - see
        // DownloadManager.downloadEpisodesToLocalSource - because this waits on
        // the queue emptying of exactly that target.
        val target = LocalSourceTarget(
            entryName = entryName?.let { LocalSourceTarget.entryNameFor(it) }
                ?: LocalSourceTarget.entryNameFor(source),
            seasonName = LocalSourceTarget.seasonNameFor(
                source.baseUrl.ifBlank { anime.url },
            ),
        )
        viewModelScope.launchNonCancellable {
            // Waits for the downloader to say the files are in place. Queue
            // state cannot answer this: it looks empty of the target both
            // before the enqueue lands and after the download is removed on
            // completion, so a refresh driven from it ran against a folder the
            // files had not reached - which is why nothing was ever extracted
            // from them. See Downloader.localSourceSaved.
            downloadManager.localSourceSaves()
                .filter { it == target }
                .take(queued)
                .collect { }
            val local = sourceManager.get(LocalSource.ID) as? LocalSource ?: return@launchNonCancellable
            val entryUrl = "${target.entryName}/${target.seasonName}"
            val entry = networkToLocalAnime(
                SAnime.create().apply {
                    url = entryUrl
                    title = target.seasonName
                }.toDomainAnime(LocalSource.ID),
            )
            updateAnimeFromRemote.awaitEpisodesUpdate(
                source = local,
                anime = entry,
                fetchDetails = true,
                fetchEpisodes = true,
                manualFetch = true,
            ).onFailure {
                logcat(LogPriority.INFO, it) { "LOCAL_SOURCE_DOWNLOAD refresh failed for $entryUrl" }
            }
        }
    }
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)

    /**
     * Runs [enqueue] once per owning entry.
     *
     * Grouped rather than passed wholesale with currentAnime, because on a
     * merged entry the playlist's episodes belong to the CHILDREN while
     * currentAnime is the parent - and the download path resolves the source
     * from the anime it is handed, so handing it the parent would resolve the
     * wrong source (or none) for every child's episodes.
     * playlistOwnerByAnimeId is the same map the episode list uses to label
     * those rows; an ordinary entry leaves it empty and everything falls back
     * to currentAnime, which is then the right answer.
     */
    private fun enqueueByOwner(episodes: List<Episode>, enqueue: (Anime, List<DomainEpisode>) -> Unit) {
        if (episodes.isEmpty()) return
        val state = stateData.value
        val fallback = state.currentAnime ?: return
        episodes
            .groupBy { episode -> state.playlistOwnerByAnimeId[episode.anime_id] ?: fallback }
            .forEach { (anime, owned) ->
                val domainEpisodes = owned.mapNotNull { it.toDomainEpisode() }
                if (domainEpisodes.isNotEmpty()) enqueue(anime, domainEpisodes)
            }
    }
    // <-- AM (LOCAL_SOURCE_DOWNLOAD)

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
                    ArtType.Cover -> anime.editCover(coverManager, imageStream(), updateAnime, coverCache)
                    ArtType.Background -> anime.editBackground(
                        backgroundManager,
                        imageStream(),
                        updateAnime,
                        backgroundCache,
                    )
                    ArtType.Thumbnail -> episode.editThumbnail(anime, thumbnailManager, imageStream())
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
                    showMessage(
                        "Skip Intro: ${context.stringResource(
                            AYMR.strings.player_aniskip_dontskip_toast,
                            chapter.chapterTitle,
                            defaultWaitingTime,
                        )}",
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
                is Anilist -> aniSkipApi.getMalIdFromAL(track.remoteId)
                else -> null
            }
            val duration = playerDuration ?: return null
            return malId?.let {
                aniSkipApi.getResult(it.toInt(), episodeNumber, duration.toLong())
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
            showMessage(AYMR.strings.toast_sleep_timer_ended)
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
        // AM (PLAYBACK_QUEUE) -->
        /**
         * The queue's episode ids as they were when this playlist was built -
         * the same role [playlistShuffleSeed] plays for the shuffle. Compared
         * against the live queue in observeQueue to tell whether the playlist
         * still matches it.
         *
         * Every episode id in the queue, in order - which is now the whole
         * playlist, so this and the playlist are built from the same read.
         */
        val playlistQueueIds: List<Long> = emptyList(),
        // <-- AM (PLAYBACK_QUEUE)
        // AM (CUSTOM_EPISODE_ORDER) -->
        /**
         * Season of each playlist episode, or empty when the playlist has no
         * season boundaries to stop at (single season, or shuffled). See
         * setupEpisodeList and the season-advance prompt in eofReached.
         */
        val playlistSeasonById: Map<Long, Long> = emptyMap(),
        // <-- AM (CUSTOM_EPISODE_ORDER)
        // AM (NAMED_SEASONS) -->
        /** The entry's seasons in display order, for labelling them. */
        val playlistSeasons: List<EntrySeason> = emptyList(),
        // <-- AM (NAMED_SEASONS)
        // AM (EPISODE_NAMES) -->
        /** Display names for the playlist - custom names and single-episode source titles. */
        val playlistEpisodeNames: Map<Long, String> = emptyMap(),
        // AM (EPISODE_TAGS)
        val playlistEpisodeTags: Map<Long, List<String>> = emptyMap(),
        // <-- AM (EPISODE_NAMES)
        // AM (SHUFFLE_FOLLOWS_SEED) -->
        /** The shuffle seed [currentPlaylist] was ordered by; 0 means unshuffled. */
        val playlistShuffleSeed: Long = 0L,
        // <-- AM (SHUFFLE_FOLLOWS_SEED)
        // AM (PLAYLIST_SEARCH_SCOPE) -->
        /** The submitted search [currentPlaylist] was built from; empty means unscoped. */
        val playlistSearchQuery: String = "",
        /** [currentPlaylist] before the search narrowed it - what the dialog searches. */
        val playlistUnscoped: List<Episode> = emptyList(),
        // <-- AM (PLAYLIST_SEARCH_SCOPE)
        // AM (EPISODE_TAG_SEARCH) -->
        /** Merged children by their anime id; empty for an ordinary entry. */
        val playlistOwnerByAnimeId: Map<Long, Anime> = emptyMap(),
        /** Source name per source id, for the children above. */
        val playlistOwnerSourceNames: Map<Long, String> = emptyMap(),
        // <-- AM (EPISODE_TAG_SEARCH)
        val hasPreviousEpisode: Boolean = false,
        val hasNextEpisode: Boolean = false,
        // AM (EXTRA_LOCAL_FOLDERS) -->
        /** Whether to offer folder access - see publishCanGrantExternalFolder. */
        val canGrantExternalFolder: Boolean = false,
        // <-- AM (EXTRA_LOCAL_FOLDERS)
        val isEpisodeOnline: Boolean = false,
        val currentEpisode: Episode? = null,
        val currentAnime: Anime? = null,
        val currentSource: AnimeSource? = null,
        // AM (MERGED_SOURCES) -->
        /**
         * The real anime/source the CURRENTLY LOADED episode actually belongs
         * to - resolved fresh in loadEpisode() from the episode's own animeId.
         * Equal to currentAnime/currentSource for a normal (non-merged)
         * session, since every episode there already belongs to that same
         * anime; only diverges for a merged entry, where currentAnime stays
         * the stable library parent (MergedSource) throughout while this
         * tracks whichever child source the playing episode actually came
         * from. Anything that needs the CONTENT source for the episode on
         * screen right now - hoster/video fetching, HTTP headers, thumbnail
         * tiles, isDownload/isLocal checks - reads these, not
         * currentAnime/currentSource. Anything scoped to the library entry
         * itself - trackers, skip-intro length, the changeAnime() same-session
         * check - keeps reading currentAnime/currentSource unchanged.
         */
        val currentEpisodeAnime: Anime? = null,
        val currentEpisodeSource: AnimeSource? = null,
        // <-- AM (MERGED_SOURCES)
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
    ) {
        // AM (LOCAL_SOURCE_DOWNLOAD) -->
        /**
         * Whether this playlist has anything downloadable behind it.
         *
         * Derived rather than stored, for the same reason as [displayNameOf]
         * below: it is a question about the entry already in this state, and a
         * stored field would be one more thing every load path has to remember
         * to set. False for an external session, which has no entry, and for a
         * local one, whose episodes are already files on disk.
         *
         * A merged entry answers true off its parent; the per-episode owners
         * are resolved when the download is actually enqueued, where the
         * episodes being acted on are known.
         */
        val canDownloadEpisodes: Boolean
            get() = currentAnime != null && currentAnime.source != LocalSource.ID
        // <-- AM (LOCAL_SOURCE_DOWNLOAD)

        // AM (EPISODE_NAMES) -->
        /**
         * The episode's name as the episode list shows it: a custom name, then
         * a single-episode source's own title, then the source's episode name.
         *
         * Lives on the state rather than the ViewModel so the player's title
         * bar and its episode list resolve a name the same way the list does,
         * from one definition. Deriving it also means it cannot go stale: a
         * name copied into ui state at episode setup would keep whatever was
         * true at that moment, and an episode renamed from the entry screen
         * while the session is alive behind the mini player would not reach
         * the title.
         */
        fun displayNameOf(episode: Episode): String {
            return episode.id?.let { playlistEpisodeNames[it] } ?: episode.name
        }
        // <-- AM (EPISODE_NAMES)

        // AM (PLAYBACK_QUEUE) -->
        /**
         * Whether to show a number or a name for this episode, by its OWN
         * entry's setting.
         *
         * The playlist used the session entry's mode for every row, which is
         * right for a merged host - its children have no mode of their own
         * that the user ever set - but wrong for a queued episode of another
         * entry: in number mode it rendered "Episode 1" and never consulted
         * [displayNameOf] at all, so the single-episode naming rule could not
         * reach the row no matter what resolved it.
         *
         * Falls back to the session's entry, which is what every row got
         * before, so an episode whose owner is not resolved yet behaves as it
         * always did.
         */
        fun displayModeOf(episode: Episode): Long? {
            val owner = episode.anime_id?.let { playlistOwnerByAnimeId[it] }
            return owner?.displayMode ?: currentAnime?.displayMode
        }
        // <-- AM (PLAYBACK_QUEUE)

        // AM (EPISODE_TAG_SEARCH) -->
        /**
         * What the search grammar sees for this episode. The owning entry is
         * the merged child where there is one, so tag: and source: mean that
         * child's rather than the parent's.
         */
        fun searchFieldsOf(episode: Episode): EpisodeSearchFields? {
            val host = currentAnime ?: return null
            val owner = episode.anime_id?.let { playlistOwnerByAnimeId[it] } ?: host
            return EpisodeSearchFields(
                displayName = displayNameOf(episode),
                episodeNumber = episode.episode_number.toDouble(),
                scanlator = episode.scanlator,
                anime = owner,
                sourceName = playlistOwnerSourceNames[owner.source]
                    ?: currentSource?.name.orEmpty(),
                // AM (EPISODE_TAGS)
                tags = episode.id?.let { playlistEpisodeTags[it] } ?: owner.genre.orEmpty(),
            )
        }
        // <-- AM (EPISODE_TAG_SEARCH)
    }

    @Stable
    data class PlayerUiData(
        val isLoadingHosters: Boolean = false,
        val isLoadingEpisode: Boolean = false,
        val previousPauseState: Boolean? = false,
        val hosterExpandedList: List<Boolean> = emptyList(),
        val selectedHosterVideoIndex: Pair<Int, Int> = Pair(-1, -1),
        // AM (EPISODE_NAMES) -->
        // mediaTitle removed: derived from stateData where it is shown, so a
        // rename reaches the player without an ordering dependency on when
        // the playlist's names were resolved.
        // <-- AM (EPISODE_NAMES)
        val animeTitle: String = "",
        val controlsShown: Boolean = true,
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
        // AM (CUSTOM_EPISODE_ORDER) -->
        /** Pending "continue to next season?" decision - see eofReached. */
        val seasonAdvancePrompt: SeasonAdvancePrompt? = null,
        // <-- AM (CUSTOM_EPISODE_ORDER)
        val showChapterIndicator: Boolean = true,
        val playerSpeedPref: Float = 1f,
        val bottomPlayerButtons: List<BottomPlayerButton?> = emptyList(),
        val enableCast: Boolean = false,
    )

    // AM (CUSTOM_EPISODE_ORDER) -->
    @Stable
    data class SeasonAdvancePrompt(
        val nextEpisodeId: Long,
        val finishedSeason: Long,
        val nextSeason: Long,
    )
    // <-- AM (CUSTOM_EPISODE_ORDER)

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
        // AM (PIP_BRING_MAIN_TO_FRONT_FIX) -->
        // Distinct from EnterPip above on purpose: EnterPip is the dedicated PIP
        // button's event and must stay plain PIP entry, nothing else. This one is
        // only for the two back-press triggers (see PlayerScreen.kt's
        // handleBackPress) - PlayerActivity's handling of it additionally brings
        // MainActivity to front. Keeping them as separate PlayerEvent/Event cases
        // rather than a shared one with a flag means the dedicated PIP button can
        // never accidentally pick up the extra behavior by sharing a code path
        // with back - confirmed by the user this was happening before the split.
        data object EnterPipFromBack : PlayerEvent
        // <-- AM (PIP_BRING_MAIN_TO_FRONT_FIX)
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

    // AM (NEXT_EPISODE_PREFETCH) -->
    companion object {
        // AM (EXTERNAL_FOLDER_GRANT) -->
        private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"
        private const val PRIMARY_VOLUME_PATH = "/storage/emulated/0"
        // <-- AM (EXTERNAL_FOLDER_GRANT)

        // AM (PERSIST_ON_CADENCE) -->
        /** Backstop write interval between the event-driven saves. */
        private const val PERSIST_INTERVAL_SECONDS = 5
        // <-- AM (PERSIST_ON_CADENCE)

        /** How close to the end of an episode the next one is resolved, in seconds. */
        private const val PREFETCH_LEAD_SECONDS = 30

        /** How long a prefetched, resolved hoster link is trusted. */
        private const val PREFETCH_TTL_MS = 5L * 60L * 1000L

        // AM (PINCH_ZOOM) -->
        private const val MIN_VIDEO_ZOOM = 1f
        private const val MAX_VIDEO_ZOOM = 8f

        /** Tenths per whole zoom factor - the indicator's granularity. */
        private const val ZOOM_INDICATOR_STEP = 10

        /**
         * How near a detent a release has to end up to be pulled onto it. Wide
         * because it only affects releases, never the live pinch.
         */
        private const val ZOOM_DETENT_ZONE = 0.20f

        /** Pan travel per unit of finger travel. 1f would be content-follows-finger. */
        private const val PAN_SPEED = 2f


        /** Floor between zoom indicator emissions, in ms. */
        private const val ZOOM_INDICATOR_MIN_INTERVAL_MS = 100L

        // AM (EXTERNAL_STREAM_COVER) -->
        // The lead-in a captured frame needs to be the video rather than the
        // black frame mpv opens on, and the cache directory a capture waits in
        // until the entry is favorited and it can be promoted.
        private const val COVER_CAPTURE_LEAD_IN_MS = 3000L
        private const val EXTERNAL_ARTWORK_DIR = "external_artwork"
        // <-- AM (EXTERNAL_STREAM_COVER)
        // <-- AM (PINCH_ZOOM)
    }
    // <-- AM (NEXT_EPISODE_PREFETCH)

    sealed interface Event {
        data object EnterPip : Event
        data object EnterPipFromBack : Event
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
        data class UpdateDiscordRPC(val paused: Boolean, val position: Int? = null) : Event
    }
}

// AM (DUMMY_PIP_REAL_VIDEO_RECT) -->
/**
 * mpv's own report of the surface it rendered into and the letterbox margins it
 * left around the video inside it (osd-dimensions).
 *
 * Top-level rather than nested in [PlayerViewModel.State] so the dummy pip can
 * take it as a plain parameter without depending on the whole state object.
 */
data class VideoOutputRect(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val marginLeft: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
) {
    /** Width of the drawn video inside the surface, in surface px. */
    val videoWidth: Int get() = (surfaceWidth - marginLeft - marginRight).coerceAtLeast(0)

    /** Height of the drawn video inside the surface, in surface px. */
    val videoHeight: Int get() = (surfaceHeight - marginTop - marginBottom).coerceAtLeast(0)

    /** True when the rect describes a real, non-degenerate drawn video. */
    val isUsable: Boolean get() = videoWidth > 0 && videoHeight > 0
}
// <-- AM (DUMMY_PIP_REAL_VIDEO_RECT)
