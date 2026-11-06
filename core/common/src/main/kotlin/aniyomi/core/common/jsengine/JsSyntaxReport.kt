package aniyomi.core.common.jsengine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A project that does not compile, with a line in the project's own numbering.
 *
 * [line] is null when the engine gave no usable position, or gave one that could
 * not be trusted - see [mapSyntaxReport]. The console shows [raw] either way, so
 * a missing line costs a jump target and no information.
 */
class JsSyntaxError(
    val line: Int?,
    val column: Int?,
    val name: String,
    val message: String,
    val raw: String,
) {
    override fun toString(): String = buildString {
        append(name).append(": ").append(message)
        line?.let { append(" (").append(PROJECT_FILE).append(':').append(it).append(')') }
    }
}

/**
 * What `__check` returns. Every field defaulted because this is parsed from
 * whatever the engine happened to attach to the error object.
 */
@Serializable
internal class RawSyntaxReport(
    val ok: Boolean = false,
    val name: String = "",
    val message: String = "",
    val line: Int = -1,
    val column: Int = -1,
    val stack: String = "",
    val text: String = "",
)

/**
 * A probe whose syntax error is on a known line, used to measure the engine's
 * line offset rather than assume it.
 *
 * `new Function(body)` compiles the body inside a function wrapper it builds
 * itself, so reported lines are shifted by however many lines that wrapper
 * occupies. That is an implementation detail of the engine: it is two today, and
 * nothing in any specification keeps it there. Compiling this probe through the
 * same path and subtracting gives the offset for the engine actually present, so
 * a QuickJS update cannot quietly start pointing at the wrong line.
 *
 * The error has to be one only an object literal produces - `{{;` at statement
 * level is two valid blocks and a semicolon.
 */
internal const val CALIBRATION_SOURCE = "\n\nvar __probe = {{;\n"

internal const val CALIBRATION_LINE = 3

internal val syntaxJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseSyntaxReport(json: String): RawSyntaxReport? =
    runCatching { syntaxJson.decodeFromString<RawSyntaxReport>(json) }.getOrNull()

/**
 * Turns a raw report into a project-relative error.
 *
 * [offset] is what calibration measured, or null if calibration itself failed.
 * Two things disqualify a line: no offset to apply, and a result outside the
 * project's own extent. The second is the cheap guard that catches an engine
 * whose offset is not constant after all - a line number that cannot exist is
 * worse than none, because the editor would scroll somewhere confidently wrong.
 */
internal fun mapSyntaxReport(report: RawSyntaxReport, offset: Int?, lineCount: Int): JsSyntaxError {
    val mapped = if (offset == null || report.line <= 0) {
        null
    } else {
        (report.line - offset).takeIf { it in 1..lineCount }
    }

    return JsSyntaxError(
        line = mapped,
        column = report.column.takeIf { it > 0 },
        name = report.name.ifEmpty { "SyntaxError" },
        message = report.message.ifEmpty { report.text },
        raw = report.stack.ifEmpty { report.text },
    )
}
