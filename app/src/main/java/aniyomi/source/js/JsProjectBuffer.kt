package aniyomi.source.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.milliseconds

/**
 * One project's text, between the keyboard and the disk.
 *
 * The editor is a text editor, so the thing it must never do is lose text. That
 * rules out save buttons: people leave editors by backgrounding the app, and an
 * unsaved buffer dies with the process. Everything here follows from that -
 * every edit is written through on a short debounce, [flush] exists for the
 * moments where waiting out a debounce would be a bug (leaving the screen, or
 * running the code), and nothing is ever normalized on the way to disk.
 *
 * ## No trailing-whitespace trimming, no reformatting
 *
 * Deliberate, and the reason this class exists instead of a `writeText` call at
 * the call site. Trailing whitespace is significant in exactly the formats a
 * person is likeliest to paste in here - a backslash continuation in a shell
 * snippet, a line inside a template literal - and an autosave that tidies as it
 * writes would silently break code that was correct when it was typed. What is
 * on screen is what is on disk, byte for byte.
 *
 * Saves run on [Dispatchers.IO] rather than the caller's thread: private-storage
 * writes are fast but they are still writes, and a keystroke is not a reason to
 * touch the filesystem on the main thread.
 */
class JsProjectBuffer(
    private val store: JsProjectStore,
    val slug: String,
    initial: String,
    scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {

    private val text = MutableStateFlow(initial)

    private val _savedAtMillis = MutableStateFlow(0L)

    /** When the buffer was last written through; 0 until the first save. */
    val savedAtMillis: StateFlow<Long> = _savedAtMillis.asStateFlow()

    private val _dirty = MutableStateFlow(false)

    /** True while the buffer holds text that has not reached disk yet. */
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Set when a save failed, so the editor can say so rather than pretend. */
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        text
            // The initial value is what was just read from disk; writing it
            // straight back would be a pointless write and a false save time.
            .drop(1)
            .debounce(debounceMillis.milliseconds)
            .onEach { save(it) }
            .launchIn(scope)
    }

    fun current(): String = text.value

    /** Records an edit. Returns immediately; the write follows on the debounce. */
    fun edit(updated: String) {
        if (updated == text.value) return
        _dirty.value = true
        text.value = updated
    }

    /**
     * Writes now and waits for it.
     *
     * Called before anything that reads the project from disk, and before the
     * editor is left. Both cases would otherwise race the debounce and read
     * stale text, which is the kind of bug that looks like the engine ignoring
     * an edit.
     */
    suspend fun flush() {
        if (_dirty.value) save(text.value)
    }

    /**
     * Takes a revision of the current text, after flushing it.
     *
     * Checkpoints are taken at points a person would call a version - opening a
     * project, running it, leaving it - rather than per keystroke; see
     * [JsProjectStore.saveRevision].
     */
    suspend fun checkpoint(): Boolean {
        flush()
        return withContext(Dispatchers.IO) { store.saveRevision(slug, text.value) }
    }

    /**
     * Replaces the buffer with a past revision, after checkpointing what is
     * there now. Restoring is not a way to lose the thing you were restoring
     * from, so the current text becomes a revision first.
     */
    suspend fun restore(atMillis: Long): Boolean {
        val revision = withContext(Dispatchers.IO) { store.readRevision(slug, atMillis) } ?: return false
        checkpoint()
        edit(revision)
        flush()
        return true
    }

    private suspend fun save(value: String) {
        try {
            withContext(Dispatchers.IO) { store.writeScript(slug, value) }
            _savedAtMillis.value = System.currentTimeMillis()
            _error.value = null
            // Only clean if nothing arrived while the write was in flight.
            if (text.value == value) _dirty.value = false
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Could not save project '$slug'" }
            _error.value = "${e::class.simpleName}: ${e.message}"
        }
    }

    companion object {
        /**
         * Short enough that no realistic pause between keystrokes leaves text
         * unwritten, long enough that holding a key is not a write per frame.
         */
        const val DEFAULT_DEBOUNCE_MILLIS = 400L
    }
}

/**
 * Reads the project's script and hands back a buffer writing into the same slug.
 *
 * Reads the script file directly rather than through a parsed project, because a
 * project with a broken manifest must still open with its code intact - see
 * [JsProjectStore.readScript].
 */
suspend fun JsProjectStore.bufferFor(slug: String, scope: CoroutineScope): JsProjectBuffer {
    val initial = withContext(Dispatchers.IO) { readScript(slug).orEmpty() }
    return JsProjectBuffer(this, slug, initial, scope)
}

/** Fire-and-forget checkpoint, for call sites that are not suspending. */
fun JsProjectBuffer.checkpointIn(scope: CoroutineScope) {
    scope.launch { checkpoint() }
}
