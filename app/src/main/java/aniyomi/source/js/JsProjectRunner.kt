package aniyomi.source.js

import aniyomi.core.common.jsengine.JsErrorSite
import aniyomi.core.common.jsengine.JsExchange
import aniyomi.core.common.jsengine.JsSourceRuntime
import aniyomi.core.common.jsengine.JsSyntaxError
import aniyomi.core.common.jsengine.jsDiagnostics
import aniyomi.core.common.jsengine.jsErrorSite
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference

/**
 * Which phase a run reached. The distinction matters to the console: a failure
 * at [LOAD] is a syntax or top-level error and every entry is dead, while a
 * failure at [CALL] is one entry misbehaving in an otherwise valid project.
 */
enum class JsRunPhase { LOAD, CALL }

class JsRunResult(
    val entry: String,
    val phase: JsRunPhase,
    val payload: String?,
    val error: String?,
    val errorSite: JsErrorSite?,
    /** Message and frames verbatim, for when [errorSite] found nothing. */
    val diagnostics: String?,
    val logs: List<String>,
    val exchanges: List<JsExchange>,
    val durationMillis: Long,
    val requests: Int,
    val bytes: Long,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = error == null

    fun summary(): String = buildString {
        append(entry).append(": ")
        append(if (ok) "ok" else "failed at ${phase.name.lowercase()}")
        append(" in ").append(durationMillis).append("ms")
        if (requests > 0) append(", ").append(requests).append(" requests, ").append(bytes).append("B")
        if (logs.isNotEmpty()) append(", ").append(logs.size).append(" log lines")
        errorSite?.let { append(" (project.js:").append(it.line).append(')') }
    }
}

/**
 * Runs the editor's buffer, not the registered source.
 *
 * Those are two different things and conflating them would make the editor
 * useless: the registered source is built from what is on disk and only exists
 * at all once a project is active, whereas the whole point of a run button is to
 * try code that is unsaved, a draft, or currently broken.
 *
 * A fresh runtime per run, closed afterwards, is the other half of that. Reusing
 * one would carry a previous run's globals into the next, so a project that only
 * works the second time - or only the first - would look like it works. Every run
 * starts from the same empty context the app would give it.
 */
class JsProjectRunner(private val network: NetworkHelper) {

    /**
     * Dispatches on its own scope for the same reason [JsProjectSource] does:
     * [withTimeout] can release the editor while JavaScript that cannot be
     * interrupted keeps running on the runtime's thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun run(
        script: String,
        baseUrl: String,
        entry: String,
        args: String = defaultArgsFor(entry),
        softTimeoutMillis: Long = DEFAULT_SOFT_TIMEOUT,
        hardTimeoutMillis: Long = DEFAULT_HARD_TIMEOUT,
    ): JsRunResult {
        val runtime = JsSourceRuntime(script, baseUrl, network)
        val startedAt = System.nanoTime()

        // Written on the runtime's thread and read on the editor's, so it has to
        // be published rather than merely assigned.
        val phase = AtomicReference(JsRunPhase.LOAD)

        val work = scope.async {
            runtime.load()
            phase.set(JsRunPhase.CALL)
            runtime.call(entry, args, softTimeoutMillis)
        }

        return try {
            val payload = withTimeout(hardTimeoutMillis) { work.await() }
            result(runtime, entry, phase.get(), startedAt, payload = payload)
        } catch (e: TimeoutCancellationException) {
            runtime.requestAbort()
            result(
                runtime,
                entry,
                phase.get(),
                startedAt,
                error = "No response within ${hardTimeoutMillis}ms - the project is not yielding",
                timedOut = true,
            )
        } catch (e: CancellationException) {
            runtime.requestAbort()
            throw e
        } catch (e: Exception) {
            val reached = phase.get()
            val site = jsErrorSite(e)
            // A load failure arrives with no JavaScript frame, so the only place
            // a line can come from is a compile inside the engine. Asked for
            // only when the throwable itself had nothing, so a project that
            // compiles and then throws at its top level pays nothing for this.
            val syntax = if (site == null && reached == JsRunPhase.LOAD) {
                runCatchingSyntax(runtime)
            } else {
                null
            }

            result(
                runtime,
                entry,
                reached,
                startedAt,
                error = e.message?.takeIf { it.isNotBlank() } ?: e.toString(),
                errorSite = site ?: syntax?.line?.let { line ->
                    JsErrorSite(line, syntax.column, "${syntax.name}: ${syntax.message}")
                },
                diagnostics = listOfNotNull(jsDiagnostics(e), syntax?.raw?.takeIf { it.isNotBlank() })
                    .joinToString("\n"),
            )
        } finally {
            // Non-blocking: queues the close on the runtime's own thread, which
            // a wedged script will never reach. That thread is then abandoned,
            // which is the cost of a runaway and is bounded at one per run.
            runtime.close()
        }
    }

    /**
     * Compiles the project without running it; null when it compiles.
     *
     * A separate path from [run] because it answers a different question and
     * must work when running is impossible. Nothing in the project executes, so
     * this is also what an editor can afford to do while someone is still
     * typing.
     */
    suspend fun syntaxError(script: String, baseUrl: String): JsSyntaxError? {
        val runtime = JsSourceRuntime(script, baseUrl, network)
        return try {
            // A project whose top level throws still compiled, so a failure here
            // is not necessarily a syntax error - the check is what decides.
            try {
                runtime.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Expected for exactly the projects this method is for.
            }
            runtime.syntaxError()
        } finally {
            runtime.close()
        }
    }

    private suspend fun runCatchingSyntax(runtime: JsSourceRuntime): JsSyntaxError? = try {
        runtime.syntaxError()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The check is a nicety; never let it replace the real failure.
        null
    }

    private fun result(
        runtime: JsSourceRuntime,
        entry: String,
        phase: JsRunPhase,
        startedAt: Long,
        payload: String? = null,
        error: String? = null,
        errorSite: JsErrorSite? = null,
        diagnostics: String? = null,
        timedOut: Boolean = false,
    ) = JsRunResult(
        entry = entry,
        phase = phase,
        payload = payload,
        error = error,
        errorSite = errorSite,
        diagnostics = diagnostics,
        logs = runtime.drainLogs(),
        exchanges = runtime.httpExchanges(),
        durationMillis = (System.nanoTime() - startedAt) / 1_000_000L,
        requests = runtime.httpRequests(),
        bytes = runtime.httpBytes(),
        timedOut = timedOut,
    )

    companion object {
        const val DEFAULT_SOFT_TIMEOUT = 20_000L
        const val DEFAULT_HARD_TIMEOUT = 30_000L

        /** The entries [JsProjectSource] calls, in the order a person tries them. */
        val ENTRIES = listOf("popular", "latest", "search", "details", "episodes", "videos")

        /**
         * Enough to get a run off the ground without a form to fill in. `url` is
         * empty rather than invented, so a project that needs one fails with its
         * own message instead of a 404 from a made-up path.
         */
        fun defaultArgsFor(entry: String): String = when (entry) {
            "search" -> args {
                put("page", 1)
                put("query", "")
            }
            "details", "episodes", "videos" -> args { put("url", "") }
            else -> args { put("page", 1) }
        }

        private inline fun args(build: JsonObjectBuilder.() -> Unit): String =
            buildJsonObject(build).toString()
    }
}
