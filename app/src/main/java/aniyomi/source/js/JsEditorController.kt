package aniyomi.source.js

import aniyomi.core.common.jsengine.JsSyntaxError
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One open project, and everything the editor screen needs to know about it.
 *
 * Deliberately free of Compose types so the editor's behaviour is separable from
 * its layout: what happens when a run times out, or when a manifest is saved
 * while the script is unsaved, is decided here and merely displayed there.
 *
 * The buffer owns the text - see [JsProjectBuffer] - and this owns everything
 * around it: the manifest, the last run, the syntax check, and the rule that
 * nothing reads the project from disk without flushing first.
 */
class JsEditorController(
    private val store: JsProjectStore,
    private val repository: JsProjectRepository,
    private val network: NetworkHelper,
    val slug: String,
    private val scope: CoroutineScope,
) {

    private val runner = JsProjectRunner(network)

    private val _buffer = MutableStateFlow<JsProjectBuffer?>(null)
    val buffer: StateFlow<JsProjectBuffer?> = _buffer.asStateFlow()

    private val _manifest = MutableStateFlow<JsProjectManifest?>(null)
    val manifest: StateFlow<JsProjectManifest?> = _manifest.asStateFlow()

    /**
     * Set when the manifest on disk does not parse. The script still opens, so
     * the editor can say what is wrong with the manifest instead of refusing to
     * show the code.
     */
    private val _manifestFault = MutableStateFlow<String?>(null)
    val manifestFault: StateFlow<String?> = _manifestFault.asStateFlow()

    private val _syntax = MutableStateFlow<JsSyntaxError?>(null)
    val syntax: StateFlow<JsSyntaxError?> = _syntax.asStateFlow()

    private val _lastRun = MutableStateFlow<JsRunResult?>(null)
    val lastRun: StateFlow<JsRunResult?> = _lastRun.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _revisions = MutableStateFlow<List<JsProjectStore.JsRevision>>(emptyList())
    val revisions: StateFlow<List<JsProjectStore.JsRevision>> = _revisions.asStateFlow()

    suspend fun load() {
        val entry = withContext(Dispatchers.IO) { store.listAll().firstOrNull { it.slug == slug } }
        when (entry) {
            is JsProjectEntry.Readable -> {
                _manifest.value = entry.project.manifest
                _manifestFault.value = null
            }
            is JsProjectEntry.Unreadable -> {
                _manifest.value = null
                _manifestFault.value = entry.reason
            }
            null -> _manifestFault.value = "no project directory named '$slug'"
        }

        _buffer.value = store.bufferFor(slug, repository.writeScope)
        refreshRevisions()

        // Opening is a version: whatever is on disk now is what a person would
        // expect to be able to get back to after an afternoon of edits.
        _buffer.value?.checkpoint()
    }

    fun edit(text: String) {
        _buffer.value?.edit(text)
        // The previous check describes text that no longer exists.
        _syntax.value = null
    }

    /** Compiles without running. Cheap enough to offer on every save. */
    fun check() {
        val buffer = _buffer.value ?: return
        scope.launch {
            buffer.flush()
            _busy.value = true
            try {
                _syntax.value = runner.syntaxError(buffer.current(), baseUrl())
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Runs one entry point against the buffer.
     *
     * Flushed first, and checkpointed, so what ran is both what is on screen and
     * something the person can return to.
     */
    fun run(entry: String) {
        val buffer = _buffer.value ?: return
        scope.launch {
            buffer.checkpoint()
            refreshRevisions()
            _busy.value = true
            try {
                val result = runner.run(buffer.current(), baseUrl(), entry)
                _lastRun.value = result
                // A load failure is usually a syntax error, and the console
                // should say which line without being asked twice.
                _syntax.value = if (result.phase == JsRunPhase.LOAD && !result.ok) {
                    runner.syntaxError(buffer.current(), baseUrl())
                } else {
                    null
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Writes the manifest.
     *
     * [JsProjectRepository.refresh] follows because the manifest is what decides
     * whether this project is a registered source: flipping `active` has to
     * reach the source manager, and the repository is the only thing that tells
     * it anything changed.
     */
    fun saveManifest(updated: JsProjectManifest) {
        scope.launch {
            withContext(Dispatchers.IO) {
                store.writeManifest(slug, updated)
                repository.refresh()
            }
            _manifest.value = updated
            _manifestFault.value = null
        }
    }

    /**
     * Restores a revision and returns the text now in the buffer, so the editor
     * can mirror it. Null when the revision could not be read.
     *
     * Returning the text rather than publishing it as state keeps the buffer the
     * single source of truth: the screen's field holds a selection as well as
     * text, and two writers for one string is how a cursor ends up jumping
     * while someone types.
     */
    suspend fun restoreNow(atMillis: Long): String? {
        val buffer = _buffer.value ?: return null
        if (!buffer.restore(atMillis)) return null
        refreshRevisions()
        _syntax.value = null
        return buffer.current()
    }

    /**
     * Called when the editor is left, where waiting out the debounce would lose
     * the last edit.
     *
     * On [JsProjectRepository.writeScope], not the screen's: by the time this
     * runs the composition is being torn down, and a flush cancelled halfway is
     * the one thing it must not be.
     */
    fun release() {
        val buffer = _buffer.value ?: return
        repository.writeScope.launch {
            buffer.flush()
            store.saveRevision(slug, buffer.current())

            // The registered source was built from the script as it was when
            // the repository last read it, and autosave does not re-read -
            // deliberately, since it fires every few hundred milliseconds. The
            // consequence was that editing a live project and then browsing it
            // ran the previous code, indefinitely and silently. Leaving the
            // editor is the point where "what the app runs" should catch up.
            refreshSources()
        }
    }

    private suspend fun refreshSources() = withContext(Dispatchers.IO) {
        repository.refresh()
    }

    private suspend fun refreshRevisions() {
        _revisions.value = withContext(Dispatchers.IO) { store.revisions(slug) }
    }

    private fun baseUrl(): String = _manifest.value?.baseUrl.orEmpty()
}
