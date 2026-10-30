package aniyomi.source.js

import aniyomi.domain.source.model.BrowseDisplayMode

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The live view of what is on disk, and the only thing the source manager talks
 * to.
 *
 * Starts empty on purpose. Reading the project directory in the constructor
 * would put disk I/O on whichever thread first injects this, which for a
 * singleton is unpredictable; [refresh] is called from a background scope
 * instead, and until then there are simply no projects - which is exactly the
 * state of an install that has never made one.
 *
 * [activeProjects] is what registration consumes: readable, active, nothing
 * else. Drafts and unreadable directories stay visible through [entries] for the
 * editor, and invisible to the app.
 */
@Inject
@SingleIn(AppScope::class)
class JsProjectRepository(
    context: Context,
    private val network: NetworkHelper,
) {

    val store = JsProjectStore(context)

    /**
     * For writes that must not be cancelled by a screen going away.
     *
     * The editor autosaves, and the last edit before someone leaves is the one
     * most likely to be in flight as the composition is torn down. Scoping the
     * write to the screen would make that edit the one that gets lost, so it is
     * scoped to this singleton instead, which lives as long as the app.
     */
    val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sources = ConcurrentHashMap<String, JsProjectSource>()

    private val _entries = MutableStateFlow<List<JsProjectEntry>>(emptyList())

    /** Everything on disk, including drafts and the ones that failed to load. */
    val entries: StateFlow<List<JsProjectEntry>> = _entries.asStateFlow()

    val activeProjects: Flow<List<JsProject>> = entries.map { list ->
        list.filterIsInstance<JsProjectEntry.Readable>()
            .map { it.project }
            .filter { it.manifest.active }
    }

    /** Re-reads the directory. Blocking; call it off the main thread. */
    fun refresh() {
        _entries.value = store.listAll()
    }

    fun createSource(project: JsProject): JsProjectSource = store.sourceFor(project, network)

    /**
     * One source object per project, for the life of the app.
     *
     * Identity matters here: whatever holds a source - a browse screen, a
     * library update, a paging source mid-scroll - holds the object, not a
     * lookup. Handing out a fresh instance on every refresh left those holders
     * pointing at a closed runtime running the previous script, which is
     * indistinguishable from "my edit did nothing".
     */
    suspend fun sourcesFor(projects: List<JsProject>): List<JsProjectSource> {
        val wanted = projects.associateBy { it.slug }

        // A project that is gone, or no longer active, releases its runtime.
        sources.keys.filterNot { it in wanted.keys }.forEach { slug ->
            sources.remove(slug)?.close()
        }

        return projects.map { project ->
            val existing = sources[project.slug]
            val source = if (existing == null) {
                store.sourceFor(project, network).also { sources[project.slug] = it }
            } else {
                existing.also {
                    it.update(
                        name = project.manifest.name,
                        lang = project.manifest.lang,
                        supportsLatest = project.manifest.supportsLatest,
                        defaultBrowseDisplayMode = project.manifest.defaultBrowseDisplayMode
                            ?.takeIf { it == "scroller" }
                            ?.let { BrowseDisplayMode.Scroller },
                        script = project.script,
                        baseUrl = project.manifest.baseUrl,
                    )
                }
            }
            source.also { warmUp(it) }
        }
    }

    /**
     * Reads a project's filters before the UI asks for them - see
     * [JsProjectSource.warmUp].
     *
     * Bounded and swallowed, deliberately. Registration builds every source in
     * turn, so a project that hangs or throws while loading must cost itself and
     * nothing else: one project with an endless top-level loop would otherwise
     * stop every other project, and every extension, from being registered at
     * all.
     */
    private suspend fun warmUp(source: JsProjectSource) {
        try {
            withTimeoutOrNull(WARM_UP_TIMEOUT_MILLIS) { source.warmUp() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Project '${source.name}' failed to load at registration" }
        }
    }

    private companion object {
        /** Generous for a load and one call; short enough not to delay startup. */
        const val WARM_UP_TIMEOUT_MILLIS = 5_000L
    }
}
