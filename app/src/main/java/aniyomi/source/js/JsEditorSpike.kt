package aniyomi.source.js

import aniyomi.core.common.jsengine.JsSourceRuntime
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spike 11 - does the editor's machinery behave before any of it has a screen?
 *
 * The editor is mostly Compose, and Compose is the part that cannot be
 * questioned from a log line. Everything underneath it can, so it is settled
 * first:
 *
 *  A. autosave writes through on the debounce, and not before
 *  B. flush beats the debounce, which is what makes "run" read current text
 *  C. revisions are a ring: deduped, newest first, pruned to the cap
 *  D. restore brings back an old script without losing the current one
 *  E. a clean run reports payload, logs and timing
 *  F. a syntax error is caught at load and mapped to a project.js line
 *  G. a thrown error is caught at call and mapped to the line that threw
 *  H. a project that never yields is released by the hard timeout
 *  I. text transforms: auto-indent, selection wrap, indent/dedent, line jump
 *
 * Offline by design: no entry here touches the network, so the spike says the
 * same thing on a plane as on wifi. The network path is spike 3's job, and the
 * exchange log it feeds is visible in a real run.
 *
 * Cleans up after itself, on the failure path too.
 */
class JsEditorSpike(
    private val repository: JsProjectRepository,
    private val network: NetworkHelper,
) {

    suspend fun run(): String = measure().also { logcat(LogPriority.INFO) { it } }

    private suspend fun measure(): String {
        val store = repository.store
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        return try {
            buildString {
                appendLine("JsEditorSpike")
                appendLine()

                store.delete(SLUG)
                store.save(SLUG, JsProjectManifest(name = "Editor spike", baseUrl = BASE_URL), "")

                val buffer = JsProjectBuffer(store, SLUG, "", scope, debounceMillis = DEBOUNCE)

                buffer.edit("// one")
                val beforeDebounce = store.read(SLUG)?.script
                delay(DEBOUNCE * 4)
                val afterDebounce = store.read(SLUG)?.script
                appendLine(
                    "A. before debounce on disk='${beforeDebounce.orEmpty()}', " +
                        "after='${afterDebounce.orEmpty()}', dirty=${buffer.dirty.value}",
                )

                buffer.edit("// two")
                buffer.flush()
                appendLine(
                    "B. flushed immediately: on disk='${store.read(SLUG)?.script}', " +
                        "dirty=${buffer.dirty.value}",
                )

                val firstRevision = buffer.checkpoint()
                val duplicate = buffer.checkpoint()
                repeat(JsProjectStore.MAX_REVISIONS + 3) { index ->
                    buffer.edit("// revision $index")
                    buffer.checkpoint()
                }
                val revisions = store.revisions(SLUG)
                appendLine(
                    "C. first=$firstRevision duplicate=$duplicate kept=${revisions.size} " +
                        "(cap ${JsProjectStore.MAX_REVISIONS}), newest first=" +
                        (revisions.map { it.atMillis } == revisions.map { it.atMillis }.sortedDescending()),
                )

                val oldest = revisions.last().atMillis
                val oldestText = store.readRevision(SLUG, oldest)
                val currentBefore = buffer.current()
                buffer.restore(oldest)
                appendLine(
                    "D. restored '${buffer.current()}' (was '$currentBefore'), " +
                        "matches revision=${buffer.current() == oldestText}, " +
                        "previous kept=${store.revisions(SLUG).any { store.readRevision(SLUG, it.atMillis) == currentBefore }}",
                )

                val runner = JsProjectRunner(network)

                val clean = runner.run(CLEAN_SCRIPT, BASE_URL, "popular")
                appendLine("E. ${clean.summary()}")
                // Decoded, not just returned. A payload that parses into an
                // empty page used to report success here, which is how a
                // misspelled field went unnoticed for a whole build.
                val decoded = clean.payload
                    ?.let { runCatching { jsJson.decodeFromString<JsAnimesPage>(it) } }
                appendLine(
                    "   decoded entries=${decoded?.getOrNull()?.entries?.size ?: "failed"} " +
                        "(1 expected), error=${decoded?.exceptionOrNull()?.message?.take(80)}",
                )
                appendLine("   logs=${clean.logs}")

                // The same mistake, now that keys are checked: this must fail
                // rather than quietly produce nothing.
                val misspelled = runner.run(MISSPELLED_SCRIPT, BASE_URL, "popular")
                val misdecoded = misspelled.payload
                    ?.let { runCatching { jsJson.decodeFromString<JsAnimesPage>(it) } }
                appendLine(
                    "E2. wrong key rejected=${misdecoded?.isFailure}, " +
                        "says=${misdecoded?.exceptionOrNull()?.message?.lineSequence()?.first()?.take(90)}",
                )

                val videos = runner.run(VIDEO_SCRIPT, BASE_URL, "videos")
                val videoList = videos.payload
                    ?.let { runCatching { jsJson.decodeFromString<List<JsVideo>>(it) } }
                appendLine(
                    "E3. videos=${videoList?.getOrNull()?.size ?: "failed"} " +
                        "first quality=${videoList?.getOrNull()?.firstOrNull()?.quality}, " +
                        "headers=${videoList?.getOrNull()?.firstOrNull()?.headers}",
                )

                val broken = runner.run(SYNTAX_ERROR_SCRIPT, BASE_URL, "popular")
                appendLine("F. ${broken.summary()}")
                appendLine("   phase=${broken.phase} line=${broken.errorSite?.line ?: "unmapped"}")
                appendRaw(broken)

                // The engine's own line is offset by the wrapper the check
                // compiles through, and the offset is measured per context
                // rather than hardcoded. Three known positions confirm the
                // measurement holds wherever the error is.
                SYNTAX_CASES.forEach { (expected, source) ->
                    val syntax = runner.syntaxError(source, BASE_URL)
                    appendLine(
                        "F2. error on line $expected -> reported ${syntax?.line ?: "unmapped"} " +
                            "(${syntax?.name}: ${syntax?.message}) match=${syntax?.line == expected}",
                    )
                }

                appendLine("F3. valid project -> ${runner.syntaxError(CLEAN_SCRIPT, BASE_URL) ?: "no error"}")

                // Out of range by construction: a one-line project cannot have
                // an error on line 3, so the guard must refuse to map it rather
                // than point somewhere impossible.
                val short = runner.syntaxError("var source = {{;", BASE_URL)
                appendLine("F4. one-line project -> line=${short?.line ?: "unmapped"} (1 or unmapped is correct)")

                val throwing = runner.run(THROWING_SCRIPT, BASE_URL, "popular")
                appendLine("G. ${throwing.summary()}")
                appendLine(
                    "   phase=${throwing.phase} line=${throwing.errorSite?.line ?: "unmapped"} " +
                        "(throw is on line $THROW_LINE)",
                )
                appendRaw(throwing)

                val wedged = runner.run(
                    SPIN_SCRIPT,
                    BASE_URL,
                    "popular",
                    softTimeoutMillis = 0,
                    hardTimeoutMillis = HARD_TIMEOUT,
                )
                appendLine(
                    "H. ${wedged.summary()}, timedOut=${wedged.timedOut}, " +
                        "its thread frees itself ${SPIN_BUDGET_MILLIS}ms in",
                )

                appendLine("I. " + textReport())
                appendLine("J. " + historyReport())
                appendLine("K. " + lexerReport())
                appendLine("M. " + abortRecoveryReport())
                appendLine("N. " + filterReport())
            }
        } catch (e: CancellationException) {
            // Cancellation is the caller's business, not a result to report.
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "JsEditorSpike failed" }
            "JsEditorSpike failed: ${e::class.simpleName}: ${e.message}"
        } finally {
            scope.cancel()
            store.delete(SLUG)
            repository.refresh()
        }
    }

    /**
     * The engine's own words, unedited.
     *
     * Where a JavaScript line number ends up in a Java throwable is a property
     * of the binding, not something to be deduced, so the spike prints the whole
     * thing: if the mapping comes back unmapped, this is what says why.
     */
    private fun StringBuilder.appendRaw(result: JsRunResult) {
        result.diagnostics?.lineSequence()?.forEach { line -> appendLine("   | $line") }
    }

    /**
     * Filters, from declaration to selected state.
     *
     * Both directions matter and only one is obvious: building the sheet from a
     * spec is easy to eyeball, while reporting the state back in the project's
     * own keys is where a wrong index silently searches for the wrong thing.
     */
    private fun filterReport(): String {
        val specs = jsJson.decodeFromString<List<JsFilterSpec>>(FILTER_SPEC_JSON)
        val list = specs.toAnimeFilterList()

        val select = list.filterIsInstance<JsSelectFilter>().first()
        val text = list.filterIsInstance<JsTextFilter>().first()
        val tri = list.filterIsInstance<JsTriStateFilter>().first()
        val group = list.filterIsInstance<JsGroupFilter>().first()

        // What the sheet does when someone uses it.
        select.state = 2
        text.state = "tentacles"
        tri.state = AnimeFilter.TriState.STATE_EXCLUDE
        (group.state.first() as JsCheckBoxFilter).state = true

        return "built ${list.size} filters " +
            "(${list.joinToString { it::class.simpleName?.removePrefix("Js").orEmpty() }}), " +
            "state=${list.toJsonObject()}"
    }

    /**
     * Does a runtime still work after a call is aborted?
     *
     * It did not, and nothing here noticed: every abort test made one call and
     * asserted it was stopped, which is only half the contract. The other half
     * is that the next call runs - and cancellation is the common case, not the
     * exotic one, so a flag that outlived its call meant a project died in
     * normal use within seconds.
     */
    private suspend fun abortRecoveryReport(): String {
        val runtime = JsSourceRuntime(RECOVERY_SCRIPT, BASE_URL, network)
        return try {
            runtime.load()

            val before = runtime.call("ping", "{}")

            // An abort nobody consumed, exactly as a cancelled caller leaves it.
            runtime.requestAbort()
            val afterStrayAbort = runCatching { runtime.call("ping", "{}") }

            // And one that really did stop a call: a deadline the loop cannot
            // meet, which throws at a crossing.
            val stopped = runCatching { runtime.call("loop", "{}", timeoutMillis = 50) }
            val afterDeadline = runCatching { runtime.call("ping", "{}") }

            "first call=$before, " +
                "after stray abort=${afterStrayAbort.getOrNull() ?: "FAILED: ${afterStrayAbort.exceptionOrNull()?.message}"}, " +
                "deadline stopped the loop=${stopped.isFailure} (${stopped.exceptionOrNull()?.message}), " +
                "after deadline=${afterDeadline.getOrNull() ?: "FAILED: ${afterDeadline.exceptionOrNull()?.message}"}"
        } catch (e: Exception) {
            "abort recovery check failed: ${e::class.simpleName}: ${e.message}"
        } finally {
            runtime.close()
        }
    }

    /**
     * The lexer, checked at the places it is most likely to be wrong.
     *
     * The interesting probe is the last: an identifier inside a `${...}`
     * placeholder has to come back as code, not as part of the surrounding
     * string, because building URLs in template literals is most of what a
     * source does and getting it wrong colours half a file as a string.
     */
    private fun lexerReport(): String {
        val tokens = lexJavaScript(LEX_SNIPPET)

        fun kindAt(probe: String): String {
            val offset = LEX_SNIPPET.indexOf(probe)
            if (offset < 0) return "probe '$probe' missing"
            val token = tokens.firstOrNull { offset >= it.start && offset < it.end }
            return token?.kind?.name ?: "none"
        }

        val checks = listOf(
            "var " to JsTokenKind.KEYWORD,
            "if (" to JsTokenKind.CONTROL,
            "total(" to JsTokenKind.FUNCTION,
            "1)" to JsTokenKind.NUMBER,
            "// comment" to JsTokenKind.COMMENT,
            "'it" to JsTokenKind.STRING,
            "`a/" to JsTokenKind.STRING,
            "Node" to JsTokenKind.TYPE,
            "count}" to JsTokenKind.IDENTIFIER,
        )

        val wrong = checks.filter { (probe, expected) -> kindAt(probe) != expected.name }
        return if (wrong.isEmpty()) {
            "all ${checks.size} token probes correct, ${tokens.size} tokens"
        } else {
            "wrong: " + wrong.joinToString { (probe, expected) ->
                "'$probe' expected $expected got ${kindAt(probe)}"
            }
        }
    }

    /**
     * Undo is judged by granularity, not by whether it compiles: a step per
     * keystroke and a single step for a whole session are both useless.
     */
    private fun historyReport(): String {
        val history = JsEditHistory(limit = 4, coalesceMillis = 100)
        fun snap(text: String) = JsEditHistory.Snapshot(text, text.length, text.length)

        // Three keystrokes inside the coalesce window: one step, not three.
        val typed = listOf(
            history.record(snap("a"), snap("ab"), now = 1_000),
            history.record(snap("ab"), snap("abc"), now = 1_040),
            history.record(snap("abc"), snap("abcd"), now = 1_080),
        )

        // A paste is never coalesced, however fast it lands after typing.
        val pasted = history.record(snap("abcd"), snap("abcd-pasted"), now = 1_090)

        val undone = history.undo(snap("abcd-pasted"))
        val undoneAgain = history.undo(undone ?: snap(""))
        val redone = history.redo(undoneAgain ?: snap(""))

        // One more than the limit, to prove the oldest is dropped rather than
        // the newest refused.
        val bounded = JsEditHistory(limit = 2, coalesceMillis = 0)
        repeat(5) { index -> bounded.record(snap("v$index"), snap("v${index + 1}"), now = index.toLong()) }

        return "typed kept=$typed (one step expected), paste kept=$pasted, " +
            "undo='${undone?.text}' then '${undoneAgain?.text}', redo='${redone?.text}', " +
            "depth after=${history.depth()}, capped depth=${bounded.depth()}"
    }

    /** Tabs are invisible in logcat, which makes an indent bug look like no bug. */
    private fun String.escapeTabs(): String = replace("\t", "\\t")

    private fun textReport(): String {
        val indented = newlineWithIndent("    if (x) {", 11, 11)
        val wrapped = insertSymbol("name", 0, 4, "`")
        val plain = insertSymbol("ab", 1, 1, "`")
        val pushed = indentLines("a\nb", 0, 3)
        val pulled = dedentLines(pushed.text, 0, pushed.text.length)
        val jump = offsetOfLine("a\nbb\nccc", 3)

        // A keyboard inserting a newline after an indented, block-opening line
        // should be rewritten; a paste of several characters never is.
        val keyboard = autoIndentOnNewline("  if (x) {", "  if (x) {\n", 11)
        val paste = autoIndentOnNewline("  if (x) {", "  if (x) {\nfoo", 14)
        val plainLine = autoIndentOnNewline("foo", "foo\n", 4)

        // One unit for both paths, which is the whole point of tabAt.
        val tabbed = tabAt("ab", 1, 1)
        val tabbedBlock = tabAt("a\nb", 0, 3)
        val unindented = dedentLines(tabbedBlock.text, 0, tabbedBlock.text.length)
        val fromSpaces = dedentLines("    a", 0, 5)

        return "tab-at-caret='${tabbed.text.escapeTabs()}' " +
            "tab-on-selection='${tabbedBlock.text.escapeTabs()}' " +
            "unindent back to '${unindented.text.escapeTabs()}' " +
            "(roundtrip=${unindented.text == "a\nb"}), " +
            "unindent of four spaces='${fromSpaces.text}', " +
            "auto-indent='${keyboard?.text?.substringAfterLast('\n')?.escapeTabs()}' " +
            "paste-untouched=${paste == null} plain-untouched=${plainLine == null}, " +
            "indent='${indented.text.substringAfterLast('\n')}' (${indented.selectionStart}), " +
            "wrap='${wrapped.text}' sel=${wrapped.selectionStart}..${wrapped.selectionEnd}, " +
            "insert='${plain.text}', " +
            "indent/dedent roundtrip=${pulled.text == "a\nb"}, " +
            "line 3 at offset $jump (expected 5), " +
            "lineOfOffset(5)=${lineOfOffset("a\nbb\nccc", 5)}"
    }

    private companion object {
        const val SLUG = "editor-spike"
        const val BASE_URL = "https://example.invalid"
        const val DEBOUNCE = 120L
        const val HARD_TIMEOUT = 1_500L

        val CLEAN_SCRIPT = """
            var source = {
                popular: function (args) {
                    console.log('popular page ' + args.page);
                    return { entries: [{ url: '/a', title: 'A' }], hasNextPage: false };
                }
            };
        """.trimIndent()

        /** The stray brace is on line 3, which is what F should report. */
        val SYNTAX_ERROR_SCRIPT = """
            var source = {
                popular: function (args) {
                    return {{;
                }
            };
        """.trimIndent()

        const val THROW_LINE = 3

        /** Built by concatenation so the `${}` placeholder survives into the string. */
        val LEX_SNIPPET = "var count = total(1);\n" +
            "// comment\n" +
            "var s = 'it\\'s';\n" +
            "var t = `a/" + "\${count}" + "/b`;\n" +
            "if (Node) return;\n"

        /**
         * Expected line to source. The same mistake - a stray brace where a
         * property name belongs - placed on line 1, 3 and 5, so two data points
         * settle whether the reported line needs an offset and a third confirms
         * it is constant.
         */
        val SYNTAX_CASES = listOf(
            1 to "var source = {{;\n// two\n// three\n// four\n// five\n",
            3 to "var source = {\n    popular: function (args) {\n        return {{;\n    }\n};\n",
            5 to "// one\n// two\n// three\n// four\nvar source = {{;\n",
        )

        val THROWING_SCRIPT = """
            var source = {
                popular: function (args) {
                    throw new Error('deliberate');
                }
            };
        """.trimIndent()

        /** Every filter type at once, including the nesting that gets dropped. */
        val FILTER_SPEC_JSON = """
            [
              { "type": "header", "name": "Pick something" },
              { "type": "select", "name": "Sort by",
                "values": ["Latest", "Viewed", "Rated"],
                "keys": ["post_date", "video_viewed", "rating"], "default": 1 },
              { "type": "text", "name": "Tag" },
              { "type": "tristate", "name": "Subtitled" },
              { "type": "separator" },
              { "type": "group", "name": "Categories", "filters": [
                  { "type": "checkbox", "name": "Anime" },
                  { "type": "checkbox", "name": "Music", "checked": true }
              ] },
              { "type": "sort", "name": "Order", "values": ["A", "B"], "keys": ["a", "b"] },
              { "type": "nonsense", "name": "Dropped" }
            ]
        """.trimIndent()

        /** `loop` crosses the host every iteration, so a deadline can stop it. */
        val RECOVERY_SCRIPT = """
            var source = {
                ping: function (args) { return 'ok'; },
                loop: function (args) {
                    var doc = parse('<div class="item"></div>', '');
                    while (true) {
                        doc.select('div.item');
                    }
                }
            };
        """.trimIndent()

        /** `animes` is not a field the host knows; strict decoding must say so. */
        val MISSPELLED_SCRIPT = """
            var source = {
                popular: function (args) {
                    return { animes: [{ url: '/a', title: 'A' }], hasNextPage: false };
                }
            };
        """.trimIndent()

        val VIDEO_SCRIPT = """
            var source = {
                videos: function (args) {
                    return [{ url: 'https://cdn.invalid/a.mp4', quality: '1080p',
                              headers: { Referer: baseUrl } }];
                }
            };
        """.trimIndent()

        /** Comfortably past [HARD_TIMEOUT], so the timeout is still what fires first. */
        const val SPIN_BUDGET_MILLIS = 4_000

        /**
         * Spins past the hard timeout and then stops.
         *
         * `while (true) {}` tested the same thing and cost a core for the life
         * of the process: nothing can interrupt a QuickJS context from outside,
         * so the runtime's thread was simply abandoned. The bound here is read
         * from `Date.now`, which QuickJS implements itself - no bridge, no
         * arena, no host crossing - so the property under test is intact: the
         * script still never yields, and the hard timeout is still the only
         * thing that can release the caller. It just ends afterwards instead of
         * never.
         */
        val SPIN_SCRIPT = """
            var source = {
                popular: function (args) {
                    var started = Date.now();
                    var burned = 0;
                    while (Date.now() - started < $SPIN_BUDGET_MILLIS) {
                        burned++;
                    }
                    return { animes: [], hasNextPage: false, burned: burned };
                }
            };
        """.trimIndent()
    }
}
