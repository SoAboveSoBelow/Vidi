package aniyomi.core.common.jsengine

/**
 * Where in the project's own file something went wrong.
 *
 * An editor that reports "TypeError: cannot read property 'text' of null" and
 * nothing else is barely better than silence, so the console needs a line to
 * point at. The two scripts are evaluated under separate names - `prelude.js`
 * and `project.js` - which is what makes this possible: a site in the prelude is
 * the host's problem and is deliberately *not* reported as a project line, and
 * because the project is evaluated as one whole file its line numbers are the
 * editor's line numbers with no offset to apply.
 */
class JsErrorSite(val line: Int, val column: Int?, val message: String)

/** The name the project is evaluated under, and so the name errors carry. */
const val PROJECT_FILE = "project.js"

/**
 * Best-effort, and honest about it: returns null rather than guessing.
 *
 * The location is looked for everywhere the binding might have put it rather
 * than in the one place it ought to be, because where that is turns out not to
 * be a given. A Java-shaped `StackTraceElement` with a real `lineNumber` is
 * preferred when there is one; failing that, every rendered form of the
 * throwable is searched for `project.js:<line>` - the message, its own
 * `toString`, each stack frame's text, and the same for any cause. JavaScript
 * engines bridged into Java commonly pack `file:line` into the element's
 * *fileName* and leave the line number as a sentinel, which no amount of
 * reading the field directly will find.
 *
 * If nothing yields a line the console still shows the message and
 * [jsDiagnostics] - it simply has nothing to jump to.
 */
fun jsErrorSite(throwable: Throwable, fileName: String = PROJECT_FILE): JsErrorSite? {
    val message = throwable.message.orEmpty().ifEmpty { throwable.toString() }
    val regex = siteRegex(fileName)

    var current: Throwable? = throwable
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        current.stackTrace.firstOrNull { element ->
            element.fileName == fileName && element.lineNumber > 0
        }?.let { element ->
            return JsErrorSite(element.lineNumber, null, message)
        }

        val rendered = sequenceOf(current.message.orEmpty(), current.toString()) +
            current.stackTrace.asSequence().map { it.toString() }

        rendered.forEach { text ->
            regex.find(text)?.let { match ->
                val line = match.groupValues[1].toIntOrNull()
                if (line != null && line > 0) {
                    return JsErrorSite(line, match.groupValues[2].toIntOrNull(), message)
                }
            }
        }

        current = current.cause
    }

    return null
}

/**
 * Everything the engine said, rendered for a console pane.
 *
 * Separate from [jsErrorSite] because the two answer different questions: that
 * one asks where to put the caret, this one is what a person reads when the
 * answer is "nowhere". Keeping the raw frames visible is the difference between
 * a project that is hard to debug and one that is impossible to.
 */
fun jsDiagnostics(throwable: Throwable): String = buildString {
    var current: Throwable? = throwable
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (isNotEmpty()) appendLine("caused by:")
        appendLine(current.toString())
        current.stackTrace.take(MAX_FRAMES).forEach { element ->
            appendLine("  at $element")
        }
        current = current.cause
    }
}.trimEnd()

private const val MAX_FRAMES = 12

private fun siteRegex(fileName: String) =
    Regex("""${Regex.escape(fileName)}:(\d+)(?::(\d+))?""")
