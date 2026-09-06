package mihon.app.di.injekt

import aniyomi.core.common.torrent.TorrentServerApi
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.BackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton

@Inject
class MetroInteropModule(
    private val json: Json,
    private val protoBuf: ProtoBuf,
    private val xml: XML,

    private val networkHelper: NetworkHelper,
    private val javaScriptEngine: JavaScriptEngine,

    private val preferenceStore: PreferenceStore,
    private val trackPreferences: TrackPreferences,

    private val extensionManager: ExtensionManager,

    private val coverCache: CoverCache,
    // AM (CUSTOM_BACKGROUND) -->
    // Vidi's own custom-background feature parallels upstream's built-in
    // custom-cover feature closely enough to reuse the exact same bridging
    // rationale as coverCache above - hasCustomBackground()/hasCustomCover()
    // in Anime.kt are both plain top-level extension functions called from a
    // mix of Composable and non-Composable contexts (e.g. AnimeKeyer, a Coil
    // Keyer with no Context receiver), so neither can rely on
    // context.appGraph.x the way most other Metro-migrated call sites do.
    private val backgroundCache: BackgroundCache,
    // <-- AM (CUSTOM_BACKGROUND)
    // AM (TORRENT_STREAMING) -->
    // TorrentUtils lives in source-api, a lower module that can't reach
    // mihon.app.di.appGraph (app depends on source-api, not the reverse) -
    // same reachability problem networkHelper above already solves this way
    // for that exact file. Explicitly scoped (@SingleIn(AppScope::class) on
    // the class itself) since it holds real local-proxy-server state (port,
    // hostUrl derived from it) that genuinely needs to be one instance app-
    // wide, not just reused for convenience.
    private val torrentServerApi: TorrentServerApi,
    // <-- AM (TORRENT_STREAMING)
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(json)
        addSingleton(protoBuf)
        addSingleton(xml)

        addSingleton(networkHelper)
        addSingleton(javaScriptEngine)

        addSingleton(preferenceStore)
        addSingleton(trackPreferences)

        addSingleton(extensionManager)

        addSingleton(coverCache)
        // AM (CUSTOM_BACKGROUND) -->
        addSingleton(backgroundCache)
        // <-- AM (CUSTOM_BACKGROUND)
        // AM (TORRENT_STREAMING) -->
        addSingleton(torrentServerApi)
        // <-- AM (TORRENT_STREAMING)
    }
}
