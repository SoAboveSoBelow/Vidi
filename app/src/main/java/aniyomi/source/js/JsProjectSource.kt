package aniyomi.source.js

import aniyomi.core.common.jsengine.JsSourceRuntime
import eu.kanade.tachiyomi.animesource.FaultRecoverableSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import rx.Observable
import tachiyomi.core.common.util.system.logcat

/**
 * A JavaScript project presented to the app as an ordinary source.
 *
 * Extends [AnimeHttpSource]. This started as a direct `AnimeCatalogueSource`
 * implementation, on the reasoning that `AnimeHttpSource` is built around a
 * request/parse pair per call while a project does its own fetching - true, but
 * beside the point. Thirty-three places in the app ask `source is
 * AnimeHttpSource` before offering playback, downloads, cover fetching, WebView,
 * sharing or the external player, and a source that answers no to all of them is
 * not a source in any useful sense: `EpisodeLoader.getHosters` ends in
 * `error("source not supported")`.
 *
 * The cost turned out to be nearly nothing. `baseUrl` is the class's only
 * abstract member; every request/parse hook already defaults to throwing, so
 * none has to be stubbed, and `resolveVideo` defaults to returning the video it
 * was given. Two defaults are actively useful: `animeDetailsRequest` is
 * `baseUrl + anime.url`, which makes WebView and sharing work for any project
 * that stores entry urls as paths, and `headers` gives the player a user agent
 * without the project supplying one.
 *
 * [id] is passed in rather than derived. A project must not be able to assert
 * its own identity: `AnimeHttpSource` hashes name/lang/version into an id, and if
 * a project could do the same it could claim an installed extension's id and
 * inherit its library entries. The host mints it once and keeps the mapping, so
 * renaming a project is cosmetic.
 *
 * The runtime loads on first use behind a mutex. That is still true of browsing,
 * but no longer means a source costs nothing until it is browsed: filters have
 * to be read before anything asks for them - see [warmUp].
 */
class JsProjectSource(
    override val id: Long,
    name: String,
    lang: String,
    supportsLatest: Boolean,
    script: String,
    baseUrl: String,
    private val networkHelper: NetworkHelper,
    private val softTimeoutMillis: Long = DEFAULT_SOFT_TIMEOUT,
    private val hardTimeoutMillis: Long = DEFAULT_HARD_TIMEOUT,
) : AnimeHttpSource(), Closeable, FaultRecoverableSource {

    /**
     * Mutable because the instance outlives any one version of the project.
     *
     * The source manager used to close and replace these on every refresh, which
     * looked tidy and was wrong: a browse screen holds the instance it opened
     * with, so editing a project left that screen driving a closed runtime built
     * from the previous script. One object per project, updated in place, is
     * what makes "edit, then look at it" behave the way anyone would expect.
     */
    override var name: String = name
        private set

    override var lang: String = lang
        private set

    override var supportsLatest: Boolean = supportsLatest
        private set

    private var script: String = script

    override var baseUrl: String = baseUrl
        private set

    /**
     * The runtime is replaceable, not final: a project that wedges its thread
     * cannot be recovered by clearing a flag, because nothing can kill that
     * thread. [clearFault] builds a fresh runtime on a fresh thread and leaves
     * the old one to burn out.
     */
    private var runtime: JsSourceRuntime = JsSourceRuntime(script, baseUrl, networkHelper)
    private val loadMutex = Mutex()

    /**
     * Calls are dispatched through this scope rather than the caller's, so a
     * wall-clock timeout can release the caller while the orphaned work keeps
     * running on the runtime's own thread. `await()` is cancellable even when the
     * JavaScript it is waiting on is not.
     */
    private val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _fault = MutableStateFlow<JsProjectFault?>(null)

    /** Non-null once this project has misbehaved; the editor watches this. */
    val fault: StateFlow<JsProjectFault?> = _fault.asStateFlow()

    @Volatile
    private var loaded = false

    /**
     * Cached because [getFilterList] is synchronous and reading the spec is a
     * JavaScript call.
     *
     * Filled by [warmUp] at registration, because the UI reads filters exactly
     * once: `BrowseSourceViewModel.init` calls `getFilterList()` and keeps the
     * result in screen state, and nothing re-reads it. Loading them on first
     * browse - the obvious lazy choice, and what this did first - meant the list
     * was empty at the moment it was asked for, so the filter button never
     * appeared and the only thing that could have refreshed it lives behind that
     * button.
     */
    @Volatile
    private var filterList: AnimeFilterList = AnimeFilterList()

    override fun getFilterList(): AnimeFilterList = filterList

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        pageOf(POPULAR, args { put("page", page) })

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        pageOf(LATEST, args { put("page", page) })

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        pageOf(
            SEARCH,
            args {
                put("page", page)
                put("query", query)
                // Keyed by filter name and resolved to the project's own keys -
                // see [toJsonObject] - so a project reads a value rather than
                // interpreting the sheet's labels.
                put("filters", filters.toJsonObject())
            },
        )

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val payload = call(DETAILS, args { put("url", anime.url) })
        return jsJson.decodeFromString<JsAnime>(payload).toSAnime().also {
            // The project describes the entry; the host owns its identity.
            it.url = anime.url
            it.initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val payload = call(EPISODES, args { put("url", anime.url) })
        return jsJson.decodeFromString<List<JsEpisode>>(payload).map { it.toSEpisode() }
    }

    /**
     * One hoster holding every stream the project found.
     *
     * A project returns a flat list of videos; [toHosterList] wraps it as the
     * single unnamed hoster the player treats as "no hoster list". Hosters are a
     * distinction for sites that embed third-party players, and a project that
     * needs that can express it later without changing what already works.
     */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = videosFor(episode).toHosterList()

    /** The older path, kept in step so whichever one the player takes agrees. */
    override suspend fun getVideoList(episode: SEpisode): List<Video> = videosFor(episode)

    private suspend fun videosFor(episode: SEpisode): List<Video> {
        val payload = call(VIDEOS, args { put("url", episode.url) })
        // `headers` is AnimeHttpSource's, so the user agent travels with every
        // stream even when a project adds a Referer of its own.
        return jsJson.decodeFromString<List<JsVideo>>(payload).map { it.toVideo(headers) }
    }

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularAnime"))
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = unsupported()

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        unsupported()

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = unsupported()

    /**
     * Resolves a stored episode url for WebView and sharing.
     *
     * The inherited version returns `episode.url` verbatim, which is right for a
     * source that stores absolute links and useless for one that stores paths -
     * and paths are what a project should store, since they are what survives a
     * site changing domain. `animeDetailsRequest` already resolves entry urls
     * this way; this is the same rule one level down.
     */
    override fun getEpisodeUrl(episode: SEpisode): String =
        if (episode.url.startsWith("http")) episode.url else baseUrl + episode.url

    override fun toString(): String = "$name (${lang.uppercase()})"

    override fun close() {
        callScope.cancel()
        runtime.close()
    }

    /** Diagnostics for the editor: requests made and bytes read by this project. */
    fun requestCount(): Int = runtime.httpRequests()

    fun bytesRead(): Long = runtime.httpBytes()

    private suspend fun pageOf(entry: String, args: String): AnimesPage =
        jsJson.decodeFromString<JsAnimesPage>(call(entry, args)).toAnimesPage()

    /**
     * Two layers, because one is not enough.
     *
     * The soft timeout is the arena deadline: precise, cheap, and checked at
     * every host crossing - which stops any project doing real work. The hard
     * timeout is wall clock on this side of the boundary, and exists only for the
     * case the soft one cannot see: a pure-compute loop that never crosses. When
     * it fires the project is faulted and every later call fails immediately,
     * so a wedged thread costs one source rather than a hung browse screen.
     */
    private suspend fun call(entry: String, args: String): String {
        _fault.value?.let { throw JsProjectFaultedException(name, it) }

        val work = callScope.async { ready().call(entry, args, softTimeoutMillis) }
        return try {
            withTimeout(hardTimeoutMillis) { work.await() }
        } catch (e: TimeoutCancellationException) {
            // The script is still running and cannot be stopped; ask it to stop
            // at its next crossing in case it has one, then refuse further work.
            runtime.requestAbort()
            val fault = JsProjectFault(
                entry = entry,
                reason = "no response within ${hardTimeoutMillis}ms - the project is not yielding",
            )
            _fault.value = fault
            logcat(LogPriority.WARN, e) { "Project '$name' faulted: ${fault.reason}" }
            // AM (JS_FAULT_AUTO_RECOVERY) -->
            // A permanent latch is only right for the case this watchdog
            // exists for at all: a script spinning in pure compute, which no
            // OkHttp timeout can reach (extensions never need this path -
            // their slow calls die in the HTTP layer and the source just
            // tries again). A slow network ANSWER trips the same wall clock
            // here, but its thread does finish once the response arrives,
            // leaving an idle, trustworthy runtime. Latching that case is
            // what made one 30s stall kill the source until a restart. So
            // watch the orphaned work: when it settles - abort honored or
            // late answer in - the fault clears itself and the next call
            // runs normally. Only a genuinely wedged thread, the one case
            // the latch was ever for, keeps the source faulted.
            //
            // compareAndSet, not plain set: a clearFault()/update() in the
            // meantime has already swapped in a fresh runtime, and the old
            // thread finishing must not undo that.
            callScope.launch {
                runCatching { work.await() }
                _fault.compareAndSet(fault, null)
            }
            // <-- AM (JS_FAULT_AUTO_RECOVERY)
            throw JsProjectFaultedException(name, fault)
        } catch (e: CancellationException) {
            // The caller went away, so let the script stop at its next crossing
            // rather than finishing work nobody is waiting for.
            runtime.requestAbort()
            throw e
        } catch (e: Exception) {
            // The message travels to whatever asked - a browse screen shows it -
            // but nowhere else, and a screen is not somewhere a stack trace can
            // be read from. This is the only record of which entry failed and
            // what it was called with.
            logcat(LogPriority.WARN, e) { "Project '$name' failed in $entry($args)" }
            throw e
        } finally {
            drainProjectLogs(entry)
        }
    }

    /**
     * Mirrors whatever the project printed to logcat, naming the entry it came
     * from.
     *
     * The editor reads a project's output straight off its own runtime, which is
     * enough while someone is looking at the editor and nothing at all once the
     * project is registered: a browse screen holds a different runtime, so a
     * `console.log` left in a project was unobservable in exactly the situation
     * where a project misbehaves in front of a user. This drains the registered
     * runtime only, so the editor's view is untouched.
     */
    private fun drainProjectLogs(entry: String) {
        runtime.drainLogs().forEach { line ->
            logcat(LogPriority.DEBUG) { "Project '$name' $entry: $line" }
        }
    }

    /**
     * Loads the runtime and reads the project's filters, before anything asks.
     *
     * Called at registration rather than on demand, which costs one QuickJS
     * context per *active* project at startup - a few milliseconds each, by the
     * engine spike's own numbers. Drafts cost nothing because they are never
     * registered.
     */
    suspend fun warmUp() {
        ready()
    }

    /**
     * Takes a newer version of the same project.
     *
     * Only swaps the runtime when the code or the base url actually changed, so
     * renaming a project does not throw away a warm context. A swap also clears
     * any fault, which is the right call: the code that misbehaved is gone.
     */
    suspend fun update(
        name: String,
        lang: String,
        supportsLatest: Boolean,
        script: String,
        baseUrl: String,
    ) {
        this.name = name
        this.lang = lang
        this.supportsLatest = supportsLatest

        if (script == this.script && baseUrl == this.baseUrl) return

        this.script = script
        this.baseUrl = baseUrl
        loadMutex.withLock {
            runtime.close()
            runtime = JsSourceRuntime(script, baseUrl, networkHelper)
            loaded = false
            // The new code may declare different filters, so the cache cannot
            // survive the swap.
            filterList = AnimeFilterList()
            _fault.value = null
        }
    }

    /**
     * Clears the fault and starts over on a new runtime and a new thread. The
     * previous one is closed, but a thread spinning in JavaScript will not stop
     * until it finishes - this makes the project usable again, it does not
     * reclaim the core.
     */
    override val isFaulted: Boolean get() = _fault.value != null

    override suspend fun clearFault() {
        loadMutex.withLock {
            runtime.close()
            runtime = JsSourceRuntime(script, baseUrl, networkHelper)
            loaded = false
            _fault.value = null
        }
    }

    private suspend fun ready(): JsSourceRuntime {
        if (!loaded) {
            loadMutex.withLock {
                if (!loaded) {
                    runtime.load()
                    loaded = true
                    filterList = readFilters(runtime)
                }
            }
        }
        return runtime
    }

    /**
     * Reads the project's filter declaration, or nothing if it has none.
     *
     * A project without a `filters` entry is the normal case, and the prelude
     * reports a missing entry as an ordinary error - so the absence of filters
     * must not be treated as a broken project. Anything that goes wrong here
     * costs the filter sheet and nothing else; browsing still works.
     */
    private suspend fun readFilters(runtime: JsSourceRuntime): AnimeFilterList = try {
        val payload = runtime.call(FILTERS, "{}", softTimeoutMillis)
        jsJson.decodeFromString<List<JsFilterSpec>>(payload).toAnimeFilterList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.DEBUG) { "Project '$name' declares no usable filters: ${e.message}" }
        AnimeFilterList()
    }

    private inline fun args(build: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject(build).toString()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("$name is a JavaScript project; use the suspend API")

    private companion object {
        /** Generous: a cold fetch through DDoS-Guard measured ~700ms. */
        const val DEFAULT_SOFT_TIMEOUT = 20_000L

        /** Only reachable by a project that never crosses the host boundary. */
        const val DEFAULT_HARD_TIMEOUT = 30_000L

        const val POPULAR = "popular"
        const val LATEST = "latest"
        const val SEARCH = "search"
        const val DETAILS = "details"
        const val EPISODES = "episodes"
        const val VIDEOS = "videos"
        const val FILTERS = "filters"
    }
}
