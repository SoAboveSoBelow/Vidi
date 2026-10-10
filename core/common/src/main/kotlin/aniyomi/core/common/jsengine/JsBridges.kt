package aniyomi.core.common.jsengine

/**
 * Host functions handed to project code.
 *
 * Every signature here is restricted to what `app.cash.quickjs.QuickJs` can
 * marshal - primitives, [String], and arrays of those. That is the reason DOM
 * nodes cross the boundary as integer handles rather than as objects: there is
 * no way to hand JavaScript a reference to a `org.jsoup.nodes.Element`, so the
 * Kotlin side keeps a table and JavaScript holds keys into it.
 *
 * Handles are [Int] rather than [Long] deliberately. JavaScript numbers are
 * doubles, so a 64-bit handle cannot round-trip exactly past 2^53, and the
 * binding's support for `long` is not worth depending on. A per-call arena
 * never comes close to exhausting 2^31 - see [DomArena].
 */
internal interface DomBridge {
    fun parse(html: String, baseUrl: String): Int

    fun select(handle: Int, query: String): IntArray

    fun selectFirst(handle: Int, query: String): Int

    fun attr(handle: Int, name: String): String

    fun absAttr(handle: Int, name: String): String

    fun text(handle: Int): String

    fun ownText(handle: Int): String

    fun html(handle: Int): String
}

internal interface LogBridge {
    fun log(message: String)
}

/**
 * Spike scaffolding: lets the sample project pull its page from Kotlin instead
 * of embedding 40KB of HTML in a JavaScript string literal.
 *
 * [documentHandle] returns a pinned handle to a document parsed once at load,
 * which is what isolates boundary cost from jsoup parse cost.
 */
internal interface FixtureBridge {
    fun page(): String

    fun documentHandle(): Int
}

/**
 * The shape the prelude installs for the runtime to call back into.
 *
 * One generic dispatch taking a JSON object rather than a method per entry point: the binding would
 * otherwise need its interface changed every time the contract grows, and a
 * project that does not define every method risks failing at bind time instead
 * of at call time. The real adapter will dispatch by name too.
 *
 * Entry points return JSON strings because that is the only structured type the
 * binding can carry; the runtime deserializes on the Kotlin side.
 */
internal interface EntryBridge {
    fun call(name: String, argsJson: String): String
}

/**
 * Hands the project's own source back to JavaScript.
 *
 * The check is defined as "compile the project this runtime was built from", so
 * the source of truth is the runtime rather than whoever asked. There is no
 * second copy to drift, and an editor cannot accidentally check text other than
 * the text that will run.
 */
internal interface SourceBridge {
    fun script(): String

    /** The manifest's base URL, so a project can build links without repeating it. */
    fun baseUrl(): String
}

/**
 * Compiles the project without running it, from inside the engine.
 *
 * The native `evaluate` reports a compile failure as a Java exception with a
 * Java-only stack - no JavaScript frame, so no line - which is useless to an
 * editor, where a syntax error is the most common error there is. Thrown inside
 * JavaScript, the same failure is an ordinary `SyntaxError` object with whatever
 * location the engine chose to put on it.
 *
 * Returns JSON: empty string when the project compiles, otherwise everything the
 * error carries, for the host to pick a line out of.
 */
internal interface CheckBridge {
    fun compile(): String

    /** Compiles arbitrary source by the same route, for calibration. */
    fun probe(source: String): String
}
