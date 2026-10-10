package aniyomi.source.js

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Undo and redo for the editor.
 *
 * The value-based text field has no history of its own, and revisions are the
 * wrong tool for this: a revision is a version of the file, taken when a person
 * opens or runs a project, while undo is for the keystroke they just regretted.
 * Different granularity, different lifetime.
 *
 * ## Coalescing
 *
 * One entry per keystroke would make undo useless - twenty taps to get back
 * across a word - so consecutive typing inside [coalesceMillis] collapses into
 * one step, which is what every editor does and what the hand expects. Anything
 * that is not a single character is never coalesced: a paste, a symbol-bar
 * insert, an indent of a block and a revision restore each stay their own step,
 * because those are precisely the edits someone wants to take back whole.
 */
class JsEditHistory(
    private val limit: Int = DEFAULT_LIMIT,
    private val coalesceMillis: Long = DEFAULT_COALESCE_MILLIS,
) {

    /** Text with the caret that went with it; restoring one without the other is jarring. */
    class Snapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private var lastRecordedAt = 0L

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * Offers [previous] as an undo point, given that the buffer is about to
     * become [next]. Returns true when it was kept.
     *
     * Any edit, kept or coalesced, discards the redo stack: once someone types
     * after undoing, the branch they undid away is gone, which is the behaviour
     * every editor has and nobody is surprised by.
     */
    fun record(previous: Snapshot, next: Snapshot, now: Long = System.currentTimeMillis()): Boolean {
        redoStack.clear()
        _canRedo.value = false

        val typing = abs(next.text.length - previous.text.length) <= 1
        if (typing && now - lastRecordedAt < coalesceMillis && undoStack.isNotEmpty()) {
            return false
        }

        push(undoStack, previous)
        lastRecordedAt = now
        _canUndo.value = true
        return true
    }

    /** The state to restore, with [current] kept for redo. Null when there is nothing to undo. */
    fun undo(current: Snapshot): Snapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        push(redoStack, current)
        // The next keystroke starts a fresh step rather than coalescing into
        // whatever was being typed before the undo.
        lastRecordedAt = 0L
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        return previous
    }

    fun redo(current: Snapshot): Snapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        push(undoStack, current)
        lastRecordedAt = 0L
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        return next
    }

    /** Called when the buffer is replaced wholesale - opening a project, or a restore. */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
        lastRecordedAt = 0L
        _canUndo.value = false
        _canRedo.value = false
    }

    fun depth(): Pair<Int, Int> = undoStack.size to redoStack.size

    private fun push(stack: ArrayDeque<Snapshot>, snapshot: Snapshot) {
        if (stack.size >= limit) stack.removeFirst()
        stack.addLast(snapshot)
    }

    companion object {
        /** Deep enough to cover a session's mistakes, bounded so a long edit is not a leak. */
        const val DEFAULT_LIMIT = 100

        /** About the gap between words, not between letters. */
        const val DEFAULT_COALESCE_MILLIS = 600L
    }
}
