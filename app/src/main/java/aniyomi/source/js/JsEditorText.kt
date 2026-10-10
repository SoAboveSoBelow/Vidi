package aniyomi.source.js

/**
 * The text operations a code editor needs, as pure functions.
 *
 * Kept out of the Compose layer so they can be reasoned about and exercised
 * without a device: every one of them is a `(text, selection) -> (text,
 * selection)` transform, which is also the shape `TextFieldValue` wants.
 *
 * ## What is deliberately absent
 *
 * No autocorrect, no capitalization, no smart quotes, no auto-closing brackets,
 * and no reformatting of any kind. The buffer may hold a shell snippet, a
 * template literal, a heredoc or a regex, and in all of those the characters a
 * "helpful" editor substitutes are the characters that carry the meaning - a
 * straightened quote breaks `sed`, an auto-inserted brace breaks `${}`, and a
 * trimmed trailing space breaks a backslash continuation. Keys insert exactly
 * the glyph pressed; the only inference made anywhere here is carrying the
 * previous line's indentation onto a new one, and that is reversible with
 * backspace.
 */
class JsEditResult(val text: String, val selectionStart: Int, val selectionEnd: Int) {
    constructor(text: String, caret: Int) : this(text, caret, caret)
}

/**
 * One indent level, everywhere in the editor.
 *
 * A tab, because that is the key a soft keyboard cannot produce and therefore
 * the one the toolbar exists to provide - and having the toolbar insert a tab
 * while a block indent inserted spaces meant the same editor produced two kinds
 * of indentation depending on which affordance you reached for. One unit, one
 * rule.
 */
const val JS_INDENT = "\t"

/**
 * Newline plus whatever indentation the current line opened with, plus one more
 * level when that line ends on an opening bracket.
 *
 * Trailing whitespace on the line being left is untouched. An editor that
 * trimmed it here would be deciding that a line like `cmd \` was a mistake.
 */
fun newlineWithIndent(text: String, selectionStart: Int, selectionEnd: Int): JsEditResult {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)

    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0))
        .let { if (it < 0 || start == 0) 0 else it + 1 }
    val indent = text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
    val opensBlock = text.substring(lineStart, start).trimEnd().lastOrNull()
        ?.let { it in BLOCK_OPENERS } == true

    val inserted = "\n" + indent + if (opensBlock) JS_INDENT else ""
    return JsEditResult(
        text = text.substring(0, start) + inserted + text.substring(end),
        caret = start + inserted.length,
    )
}

/**
 * Catches a newline the keyboard just inserted and re-does it with indentation.
 *
 * Detected by comparing before and after rather than by intercepting a key
 * event, because a soft keyboard is not obliged to deliver one: IMEs commit
 * text through the input connection, and prediction, swipe and voice input never
 * produce a key event at all. Comparing the two strings is true of every input
 * method there is.
 *
 * Returns null unless the change is exactly one newline inserted at [caret],
 * which is the only case this is allowed to rewrite - anything else is someone's
 * paste or multi-character commit and is left alone.
 */
fun autoIndentOnNewline(previous: String, updated: String, caret: Int): JsEditResult? {
    if (updated.length != previous.length + 1) return null
    if (caret < 1 || caret > updated.length) return null
    if (updated[caret - 1] != '\n') return null
    if (updated.removeRange(caret - 1, caret) != previous) return null

    val result = newlineWithIndent(previous, caret - 1, caret - 1)
    // Nothing to carry: the keyboard's own newline is already correct.
    return result.takeIf { it.text != updated }
}

/**
 * Inserts [symbol], or wraps the selection in it when it is one of a pair and
 * something is selected.
 *
 * Wrapping only on a non-empty selection is the whole rule: with a cursor it is
 * a plain insert, so nothing ever appears that was not typed, and with a
 * selection the intent is unambiguous.
 */
fun insertSymbol(text: String, selectionStart: Int, selectionEnd: Int, symbol: String): JsEditResult {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    val closer = PAIRS[symbol]

    if (closer != null && end > start) {
        val selected = text.substring(start, end)
        val replacement = symbol + selected + closer
        return JsEditResult(
            text = text.substring(0, start) + replacement + text.substring(end),
            selectionStart = start + symbol.length,
            selectionEnd = start + symbol.length + selected.length,
        )
    }

    return JsEditResult(
        text = text.substring(0, start) + symbol + text.substring(end),
        caret = start + symbol.length,
    )
}

/** Adds one [JS_INDENT] to every line the selection touches. */
fun indentLines(text: String, selectionStart: Int, selectionEnd: Int): JsEditResult =
    reindent(text, selectionStart, selectionEnd) { JS_INDENT + it }

/**
 * Removes one level of indentation from every line the selection touches.
 *
 * Accepts indentation this editor did not write: a tab, four spaces, two
 * spaces, or one. Pasted code and the starter template are space-indented, and
 * an unindent that only recognised its own output would do nothing to them.
 */
fun dedentLines(text: String, selectionStart: Int, selectionEnd: Int): JsEditResult =
    reindent(text, selectionStart, selectionEnd) { line ->
        DEDENT_PREFIXES.firstOrNull { line.startsWith(it) }
            ?.let { line.removePrefix(it) }
            ?: line
    }

/** Longest first, so four spaces are taken as one level rather than two. */
private val DEDENT_PREFIXES = listOf(JS_INDENT, "    ", "  ", " ")

/**
 * Offset of the first character of a 1-based line, clamped into the text.
 *
 * This is what makes a console error clickable: the engine reports
 * `project.js:12` and the editor needs a caret position for it.
 */
fun offsetOfLine(text: String, line: Int): Int {
    if (line <= 1) return 0
    var offset = 0
    var remaining = line - 1
    while (remaining > 0) {
        val next = text.indexOf('\n', offset)
        if (next < 0) return text.length
        offset = next + 1
        remaining--
    }
    return offset.coerceAtMost(text.length)
}

/** 1-based line containing [offset]. */
fun lineOfOffset(text: String, offset: Int): Int {
    val limit = offset.coerceIn(0, text.length)
    var line = 1
    var index = 0
    while (index < limit) {
        if (text[index] == '\n') line++
        index++
    }
    return line
}

fun lineCount(text: String): Int = text.count { it == '\n' } + 1

private fun reindent(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    transform: (String) -> String,
): JsEditResult {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)

    val blockStart = offsetOfLine(text, lineOfOffset(text, start))
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }

    val block = text.substring(blockStart, lineEnd)
    val rewritten = block.split('\n').joinToString("\n", transform = transform)

    return JsEditResult(
        text = text.substring(0, blockStart) + rewritten + text.substring(lineEnd),
        selectionStart = blockStart,
        selectionEnd = blockStart + rewritten.length,
    )
}

private val BLOCK_OPENERS = setOf('{', '[', '(')

/** Openers that wrap a selection. Quotes included; they pair with themselves. */
private val PAIRS = mapOf(
    "{" to "}",
    "[" to "]",
    "(" to ")",
    "'" to "'",
    "\"" to "\"",
    "`" to "`",
)

/**
 * What the toolbar's tab button does, which depends on whether anything is
 * selected.
 *
 * With a selection it indents the block, because a plain text field would
 * otherwise replace the selection with a tab character and lose it. With a caret
 * it inserts one indent. Both produce [JS_INDENT], so the two cases cannot
 * disagree, and [dedentLines] reverses either.
 */
fun tabAt(text: String, selectionStart: Int, selectionEnd: Int): JsEditResult =
    if (selectionEnd > selectionStart) {
        indentLines(text, selectionStart, selectionEnd)
    } else {
        insertSymbol(text, selectionStart, selectionEnd, JS_INDENT)
    }
