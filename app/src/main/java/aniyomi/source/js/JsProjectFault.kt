package aniyomi.source.js

/**
 * Why a project stopped being trusted to answer.
 *
 * Deliberately in-memory and not persisted: a fault describes a runtime that is
 * wedged right now, and a fresh process has a fresh runtime. Persisting it would
 * mean a project stayed broken across a restart that actually fixed it.
 */
class JsProjectFault(
    val entry: String,
    val reason: String,
    val atMillis: Long = System.currentTimeMillis(),
)

/**
 * Thrown instead of queueing behind a thread that will not yield.
 *
 * This is the difference between one dead source and a hung browse screen: the
 * first call pays the hard timeout, every later one fails immediately.
 */
class JsProjectFaultedException(
    projectName: String,
    val fault: JsProjectFault,
) : Exception("$projectName stopped responding during '${fault.entry}': ${fault.reason}")
