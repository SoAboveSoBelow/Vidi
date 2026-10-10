// AM (JS_PROJECTS) -->
package eu.kanade.presentation.more.settings.screen.advanced

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import aniyomi.core.common.jsengine.JsSyntaxError
import aniyomi.source.js.JsEditHistory
import aniyomi.source.js.JsEditorController
import aniyomi.source.js.JsProjectManifest
import aniyomi.source.js.JsProjectRunner
import aniyomi.source.js.JsProjectStore
import aniyomi.source.js.JsRunResult
import aniyomi.source.js.autoIndentOnNewline
import aniyomi.source.js.dedentLines
import aniyomi.source.js.lineCount
import aniyomi.source.js.offsetOfLine
import aniyomi.source.js.tabAt
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding

/**
 * A text editor for one project, and a console under it.
 *
 * ## Why this is a plain text field
 *
 * Nothing here highlights, completes, formats or corrects. The buffer may hold
 * JavaScript, but it may equally hold a shell snippet in a template literal, a
 * regex, or a heredoc, and every convenience an editor normally offers is a way
 * of damaging one of those: a straightened quote breaks a
 * `sed` expression, an auto-inserted brace breaks a template placeholder, an
 * autocorrected identifier breaks everything. The
 * keyboard is told not to capitalize or correct, the font is monospaced, and the
 * only inference made anywhere is carrying a line's indentation onto the next -
 * see [aniyomi.source.js.autoIndentOnNewline].
 *
 * Line numbers sit in the same vertical scroll as the field rather than in their
 * own scrollable column, which is what keeps them aligned with the text without
 * two scroll positions to synchronize.
 */
class JsProjectEditorScreen(private val slug: String) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { context.appGraph }
        val density = LocalDensity.current

        // Read here, above the column that applies imePadding, rather than
        // inside the editor. Down there the inset has been padded away by an
        // ancestor, and whether a raw read still reports it is a question about
        // Compose's consumption internals that this should not depend on.
        val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
        val repository = remember { graph.jsProjectRepository }
        val scope = rememberCoroutineScope()

        val controller = remember(slug) {
            JsEditorController(
                store = repository.store,
                repository = repository,
                network = graph.networkHelper,
                slug = slug,
                scope = scope,
            )
        }

        val buffer by controller.buffer.collectAsState()
        val manifest by controller.manifest.collectAsState()
        val manifestFault by controller.manifestFault.collectAsState()
        val syntax by controller.syntax.collectAsState()
        val lastRun by controller.lastRun.collectAsState()
        val busy by controller.busy.collectAsState()
        val revisions by controller.revisions.collectAsState()

        var value by remember { mutableStateOf(TextFieldValue()) }
        var wrap by remember { mutableStateOf(false) }
        var consoleOpen by remember { mutableStateOf(false) }

        val history = remember(slug) { JsEditHistory() }
        val canUndo by history.canUndo.collectAsState()
        val canRedo by history.canRedo.collectAsState()
        var showManifest by remember { mutableStateOf(false) }
        var showRevisions by remember { mutableStateOf(false) }

        LaunchedEffect(slug) {
            controller.load()
            // Guarded so a keystroke landing during the read is not overwritten
            // by the text that was on disk a moment before it.
            if (value.text.isEmpty()) {
                controller.buffer.value?.let { value = TextFieldValue(it.current()) }
                history.reset()
            }
        }

        DisposableEffect(controller) {
            onDispose { controller.release() }
        }

        // Results are worth interrupting for; nothing else opens the pane on its
        // own, so a person who closed it keeps the space while they type.
        LaunchedEffect(lastRun, syntax) {
            if (lastRun != null || syntax != null) consoleOpen = true
        }

        // A flow that exists whether or not the buffer does, so the collection
        // is unconditional and the slot table stays stable.
        val noBuffer = remember { MutableStateFlow(false) }
        val dirty by (buffer?.dirty ?: noBuffer).collectAsState()

        // Not named `apply`: shadowing the scope function inside a composable
        // is a trap for whoever edits this next.
        fun replaceText(text: String, selectionStart: Int, selectionEnd: Int = selectionStart) {
            history.record(
                previous = JsEditHistory.Snapshot(value.text, value.selection.start, value.selection.end),
                next = JsEditHistory.Snapshot(text, selectionStart, selectionEnd),
            )
            value = TextFieldValue(text = text, selection = TextRange(selectionStart, selectionEnd))
            controller.edit(text)
        }

        fun applySnapshot(snapshot: JsEditHistory.Snapshot) {
            value = TextFieldValue(
                text = snapshot.text,
                selection = TextRange(snapshot.selectionStart, snapshot.selectionEnd),
            )
            controller.edit(snapshot.text)
        }

        fun snapshot() = JsEditHistory.Snapshot(value.text, value.selection.start, value.selection.end)

        fun jumpTo(line: Int) {
            val offset = offsetOfLine(value.text, line)
            value = value.copy(selection = TextRange(offset))
        }

        /*
         * Replacing the open project's code from a file, for the same reason the
         * list screen imports from one: a couple of hundred lines is more than
         * the clipboard reliably carries on a phone.
         */
        val replaceLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val script = withIOContext {
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                    }.getOrNull()
                }
                if (script.isNullOrBlank()) {
                    context.toast("Could not read that file")
                } else {
                    // A revision first: this discards whatever is in the buffer,
                    // and one undo or one revision should get it back.
                    controller.buffer.value?.checkpoint()
                    replaceText(script, 0)
                    history.reset()
                    controller.check()
                    context.toast("Replaced from file, previous version kept as a revision")
                }
            }
        }

        var showEntries by remember { mutableStateOf(false) }

        if (showManifest) {
            ManifestDialog(
                manifest = manifest ?: JsProjectManifest(name = slug),
                fault = manifestFault,
                onDismissRequest = { showManifest = false },
                onSave = {
                    showManifest = false
                    controller.saveManifest(it)
                },
            )
        }

        if (showRevisions) {
            RevisionsDialog(
                revisions = revisions,
                onDismissRequest = { showRevisions = false },
                onRestore = { at ->
                    showRevisions = false
                    scope.launch {
                        // The buffer is the source of truth; the field mirrors
                        // whatever it ends up holding.
                        controller.restoreNow(at)?.let { restored ->
                            replaceText(restored, 0)
                            // The restored text is the new baseline; undoing
                            // back across a restore would be a surprise.
                            history.reset()
                        }
                    }
                },
            )
        }

        if (showEntries) {
            AlertDialog(
                onDismissRequest = { showEntries = false },
                title = { Text(text = "Run which entry point?") },
                text = {
                    Column {
                        JsProjectRunner.ENTRIES.forEach { entry ->
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showEntries = false
                                        controller.run(entry)
                                    }
                                    .padding(vertical = MaterialTheme.padding.small),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEntries = false }) { Text(text = "Cancel") }
                },
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = manifest?.name ?: slug,
                    // The line count is here because its absence cost an
                    // evening: a truncated paste looks exactly like a project
                    // that half works, and nothing on screen said the file was
                    // ninety lines instead of two hundred.
                    subtitle = buildString {
                        append(if (dirty) "saving…" else "saved")
                        append(" · ")
                        append(lineCount(value.text))
                        append(" lines")
                        // An imported project has no base url until someone
                        // sets one, and every request it makes will fail until
                        // they do. Worth a word here rather than a surprise on
                        // the first run.
                        if (manifest?.baseUrl.isNullOrBlank()) append(" · no base url")
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            actions = listOf(
                                AppBar.Action(
                                    title = "Run",
                                    icon = Icons.Outlined.PlayArrow,
                                    onClick = { showEntries = true },
                                    enabled = !busy,
                                ),
                                AppBar.Action(
                                    title = "Check syntax",
                                    icon = Icons.Outlined.Spellcheck,
                                    onClick = controller::check,
                                    enabled = !busy,
                                ),
                                AppBar.OverflowAction(
                                    title = "Project settings",
                                    onClick = { showManifest = true },
                                ),
                                AppBar.OverflowAction(
                                    title = if (wrap) "Disable soft wrap" else "Enable soft wrap",
                                    onClick = { wrap = !wrap },
                                ),
                                AppBar.OverflowAction(
                                    title = "Replace from file",
                                    onClick = { replaceLauncher.launch(arrayOf("*/*")) },
                                ),
                                AppBar.OverflowAction(
                                    title = "Revisions",
                                    onClick = { showRevisions = true },
                                ),
                                AppBar.OverflowAction(
                                    title = "Export",
                                    onClick = { shareScript(context, manifest?.name ?: slug, value.text) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            // imePadding here rather than on the editor: the whole column
            // shrinks when the keyboard opens, which is what keeps the toolbar
            // and the console header above it instead of behind it.
            //
            // consumeWindowInsets is not optional. The scaffold's padding
            // already reserves the navigation bar, and the IME inset is
            // measured from the bottom of the window, so it covers that same
            // strip; without declaring the first one consumed, both are applied
            // and the toolbar floats a navigation bar's height above the
            // keyboard instead of resting on it.
            // One surface for the editor, its toolbar and the console. A dark
            // code pane abutting an app-themed console in light mode reads as a
            // rendering fault rather than a choice.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(JsSyntaxColors.background)
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding)
                    .imePadding(),
            ) {
                EditorArea(
                    value = value,
                    wrap = wrap,
                    onValueChange = { updated ->
                        val indented = autoIndentOnNewline(
                            previous = value.text,
                            updated = updated.text,
                            caret = updated.selection.start,
                        )
                        if (indented != null) {
                            replaceText(indented.text, indented.selectionStart, indented.selectionEnd)
                        } else {
                            // Only when the text changed: moving the caret or
                            // selecting is not an edit and must not consume an
                            // undo step.
                            if (updated.text != value.text) {
                                history.record(
                                    previous = snapshot(),
                                    next = JsEditHistory.Snapshot(
                                        updated.text,
                                        updated.selection.start,
                                        updated.selection.end,
                                    ),
                                )
                            }
                            value = updated
                            controller.edit(updated.text)
                        }
                    },
                    keyboardOpen = keyboardOpen,
                    onEditingEnded = { controller.check() },
                    modifier = Modifier.weight(EDITOR_WEIGHT),
                )

                HorizontalDivider()

                EditorToolbar(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = { history.undo(snapshot())?.let(::applySnapshot) },
                    onRedo = { history.redo(snapshot())?.let(::applySnapshot) },
                    onTab = {
                        val result = tabAt(value.text, value.selection.start, value.selection.end)
                        replaceText(result.text, result.selectionStart, result.selectionEnd)
                    },
                    onUnindent = {
                        val result = dedentLines(
                            value.text,
                            value.selection.start,
                            value.selection.end,
                        )
                        replaceText(result.text, result.selectionStart, result.selectionEnd)
                    },
                )

                HorizontalDivider()

                ConsoleHeader(
                    open = consoleOpen,
                    busy = busy,
                    summary = syntax?.let { "${it.name}: ${it.message}" }
                        ?: lastRun?.summary()
                        ?: "No runs yet",
                    isError = syntax != null || lastRun?.ok == false,
                    canCopy = syntax != null || lastRun != null,
                    onToggle = { consoleOpen = !consoleOpen },
                    onCopy = {
                        context.copyToClipboard("console", consoleText(syntax, lastRun))
                    },
                )

                if (consoleOpen) {
                    Console(
                        busy = busy,
                        syntax = syntax?.let { "${it.name}: ${it.message}" to it.line },
                        run = lastRun,
                        onJumpTo = ::jumpTo,
                        onCopy = {
                            context.copyToClipboard("console", consoleText(syntax, lastRun))
                        },
                        modifier = Modifier.weight(CONSOLE_WEIGHT),
                    )
                }
            }
        }
    }

    @Composable
    private fun EditorArea(
        value: TextFieldValue,
        wrap: Boolean,
        onValueChange: (TextFieldValue) -> Unit,
        keyboardOpen: Boolean,
        onEditingEnded: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val density = LocalDensity.current
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current

        val style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = CODE_SIZE.sp,
            lineHeight = CODE_LINE_HEIGHT.sp,
            color = JsSyntaxColors.foreground,
        )

        // Lexed once per edit, not once per frame.
        val highlighted = remember(value.text) { highlightJavaScript(value.text) }
        val transformation = remember(highlighted) { JsSyntaxTransformation(highlighted) }

        // The field's own measurement of itself. Every line position below comes
        // from this rather than from a guess at the font's metrics, which is
        // what makes the gutter line up with the code and keeps it lined up.
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

        val scrollState = rememberScrollState()
        var viewportHeight by remember { mutableIntStateOf(0) }
        var focused by remember { mutableStateOf(false) }
        var sawKeyboard by remember { mutableStateOf(false) }

        // Back closes the keyboard before the press ever reaches the activity,
        // so watching for the keyboard leaving is what makes one press end the
        // edit rather than two. Without this the caret keeps blinking over a
        // closed keyboard, which says the field is still taking input when it
        // is not.
        //
        // sawKeyboard guards the first frames of a focus: focus arrives before
        // the keyboard animates in, and acting on that would clear focus the
        // instant the field was tapped.
        LaunchedEffect(keyboardOpen, focused) {
            if (!focused) {
                sawKeyboard = false
            } else if (keyboardOpen) {
                sawKeyboard = true
            } else if (sawKeyboard) {
                focusManager.clearFocus()
                onEditingEnded()
            }
        }

        // The other direction, and the case with a hardware keyboard where the
        // IME never appears at all.
        BackHandler(enabled = focused) {
            keyboardController?.hide()
            focusManager.clearFocus()
            onEditingEnded()
        }

        // Keeps the caret in view: when the keyboard opens over it, and when
        // something moves it - which includes tapping an error in the console,
        // where the line being jumped to is usually off screen.
        LaunchedEffect(value.selection.start, keyboardOpen, layout, viewportHeight) {
            val measured = layout ?: return@LaunchedEffect
            if (viewportHeight == 0) return@LaunchedEffect

            val line = measured.getLineForOffset(value.selection.start.coerceIn(0, value.text.length))
            val top = measured.getLineTop(line).toInt()
            val bottom = measured.getLineBottom(line).toInt()

            val target = when {
                top < scrollState.value -> top
                bottom > scrollState.value + viewportHeight -> bottom - viewportHeight
                else -> null
            }
            target?.let { scrollState.animateScrollTo(it.coerceIn(0, scrollState.maxValue)) }
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .onSizeChanged { viewportHeight = it.height }
                .verticalScroll(scrollState),
        ) {
            // Only while wrapping is off. With it on, one logical line occupies
            // several rows and every number below the first wrapped line would
            // point at the wrong row - a gutter that lies is worse than no
            // gutter, and the console's jump-to-line is what actually needs the
            // numbering to be right.
            if (!wrap) {
                LineNumbers(layout = layout, style = style, density = density)
            }

            // No `softWrap` parameter here - BasicTextField has none. Wrapping
            // is decided by the width it is measured with: unbounded inside a
            // horizontal scroll, so lines run off to the right, or the parent's
            // width, so they wrap.
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = style,
                visualTransformation = transformation,
                onTextLayout = { layout = it },
                cursorBrush = SolidColor(JsSyntaxColors.cursor),
                // Autocorrect and capitalization off, because this is code and
                // an IME that "fixes" an identifier has broken the project.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            )
        }
    }

    /**
     * One number per line the field actually laid out, at that line's height.
     *
     * Counting newlines and stacking fixed-height rows is what the first version
     * did, and it drifted: the field's real line height is decided by font
     * metrics, not by the `lineHeight` asked for, so by line sixty the gutter had
     * run out well before the code had. Reading the positions back from the
     * layout removes the guess entirely.
     */
    @Composable
    private fun LineNumbers(layout: TextLayoutResult?, style: TextStyle, density: Density) {
        val measured = layout ?: return

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .widthIn(min = GUTTER_WIDTH.dp)
                .padding(end = MaterialTheme.padding.small),
        ) {
            repeat(measured.lineCount) { line ->
                val height = with(density) {
                    (measured.getLineBottom(line) - measured.getLineTop(line)).toDp()
                }
                Text(
                    text = (line + 1).toString(),
                    style = style,
                    textAlign = TextAlign.End,
                    color = JsSyntaxColors.lineNumber,
                    modifier = Modifier.height(height),
                )
            }
        }
    }

    /**
     * The keys a phone keyboard does not give you.
     *
     * Brackets, quotes and operators are all one tap away on any keyboard's
     * symbols page; these four are not available at all. Tab does double duty -
     * a tab at the caret, or a block indent when there is a selection, since a
     * plain text field would otherwise replace the selection with a tab and
     * lose it - which is why indenting is no longer a menu item. Unindent has
     * no equivalent at all: there is no key that removes a level.
     */
    @Composable
    private fun EditorToolbar(
        canUndo: Boolean,
        canRedo: Boolean,
        onUndo: () -> Unit,
        onRedo: () -> Unit,
        onTab: () -> Unit,
        onUnindent: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.extraSmall),
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) JsSyntaxColors.foreground else JsSyntaxColors.lineNumber,
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) JsSyntaxColors.foreground else JsSyntaxColors.lineNumber,
                )
            }
            TextButton(onClick = onTab) {
                Text(text = "tab", fontFamily = FontFamily.Monospace, color = JsSyntaxColors.foreground)
            }
            IconButton(onClick = onUnindent) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.FormatIndentDecrease,
                    contentDescription = "Unindent",
                    tint = JsSyntaxColors.foreground,
                )
            }
        }
    }

    /**
     * Always visible, so the pane can be closed without losing the verdict:
     * collapsed, this one line still says whether the last run worked.
     */
    @Composable
    private fun ConsoleHeader(
        open: Boolean,
        busy: Boolean,
        summary: String,
        isError: Boolean,
        canCopy: Boolean,
        onToggle: () -> Unit,
        onCopy: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(
                    horizontal = MaterialTheme.padding.small,
                    vertical = MaterialTheme.padding.extraSmall,
                ),
        ) {
            Icon(
                imageVector = if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = if (open) "Collapse console" else "Expand console",
                tint = JsSyntaxColors.lineNumber,
            )
            Text(
                text = if (busy) "running…" else summary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isError && !busy) JsSyntaxColors.error else JsSyntaxColors.lineNumber,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.small),
            )

            if (canCopy) {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy console",
                        tint = JsSyntaxColors.lineNumber,
                    )
                }
            }
        }
    }

    @Composable
    private fun Console(
        busy: Boolean,
        syntax: Pair<String, Int?>?,
        run: JsRunResult?,
        onJumpTo: (Int) -> Unit,
        onCopy: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = CONSOLE_SIZE.sp,
            color = JsSyntaxColors.foreground,
        )

        Column(
            modifier = modifier
                .fillMaxWidth()
                // Long-press anywhere in the pane, since that is the gesture a
                // person tries on text they cannot select.
                .combinedClickable(onClick = {}, onLongClick = onCopy)
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.padding.small),
        ) {
            if (busy) {
                CircularProgressIndicator()
                return@Column
            }

            syntax?.let { (message, line) ->
                Text(
                    text = if (line != null) "$message  (line $line)" else message,
                    style = style,
                    color = JsSyntaxColors.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (line != null) Modifier.clickable { onJumpTo(line) } else Modifier),
                )
            }

            if (run == null) {
                if (syntax == null) {
                    Text(text = "No runs yet.", style = style)
                }
                return@Column
            }

            Text(text = run.summary(), style = style)

            run.error?.let { error ->
                Text(
                    text = error,
                    style = style,
                    color = JsSyntaxColors.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            run.errorSite?.line
                                ?.let { line -> Modifier.clickable { onJumpTo(line) } }
                                ?: Modifier,
                        ),
                )
            }

            run.logs.forEach { line -> Text(text = "log  $line", style = style) }

            run.exchanges.forEach { exchange -> Text(text = exchange.toString(), style = style) }

            run.payload?.let { payload ->
                Text(
                    text = payload.take(PAYLOAD_PREVIEW),
                    style = style,
                    color = JsSyntaxColors.lineNumber,
                )
            }

            run.diagnostics?.takeIf { run.errorSite == null && it.isNotBlank() }?.let { raw ->
                Text(
                    text = raw,
                    style = style,
                    color = JsSyntaxColors.lineNumber,
                )
            }
        }
    }

    @Composable
    private fun ManifestDialog(
        manifest: JsProjectManifest,
        fault: String?,
        onDismissRequest: () -> Unit,
        onSave: (JsProjectManifest) -> Unit,
    ) {
        var name by remember { mutableStateOf(manifest.name) }
        var lang by remember { mutableStateOf(manifest.lang) }
        var baseUrl by remember { mutableStateOf(manifest.baseUrl) }
        var supportsLatest by remember { mutableStateOf(manifest.supportsLatest) }
        var nsfw by remember { mutableStateOf(manifest.nsfw) }
        var active by remember { mutableStateOf(manifest.active) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = "Project settings") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    fault?.let {
                        Text(
                            text = "The manifest on disk could not be read ($it). Saving here " +
                                "replaces it.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(text = "Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = lang,
                        onValueChange = { lang = it },
                        label = { Text(text = "Language code") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(text = "Base URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                    )

                    SwitchRow("Supports latest", supportsLatest) { supportsLatest = it }
                    SwitchRow("Adult content", nsfw) { nsfw = it }
                    SwitchRow("Active", active) { active = it }

                    Text(
                        text = "A draft is never registered as a source, so nothing in the app " +
                            "can reach it. Activating is what grants it access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(
                            JsProjectManifest(
                                name = name.trim(),
                                lang = lang.trim().ifEmpty { "en" },
                                baseUrl = baseUrl.trim(),
                                supportsLatest = supportsLatest,
                                nsfw = nsfw,
                                active = active,
                            ),
                        )
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text(text = "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(text = "Cancel") }
            },
        )
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = label)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun RevisionsDialog(
        revisions: List<JsProjectStore.JsRevision>,
        onDismissRequest: () -> Unit,
        onRestore: (Long) -> Unit,
    ) {
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = "Revisions") },
            text = {
                if (revisions.isEmpty()) {
                    Text(text = "No revisions yet. One is kept each time you open or run a project.")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = REVISION_LIST_HEIGHT.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        revisions.forEach { revision ->
                            Text(
                                text = "${format.format(Date(revision.atMillis))}  " +
                                    "(${revision.sizeBytes} B)",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRestore(revision.atMillis) }
                                    .padding(vertical = MaterialTheme.padding.small),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text(text = "Close") }
            },
        )
    }

    /**
     * The console as text, which is what gets copied.
     *
     * Built from the same pieces the pane renders rather than scraped from it,
     * so what lands on the clipboard is what is on screen - including the raw
     * frames, which are the part worth pasting somewhere and the part nobody can
     * retype.
     */
    private fun consoleText(syntax: JsSyntaxError?, run: JsRunResult?): String = buildString {
        syntax?.let { appendLine("${it.name}: ${it.message}${it.line?.let { line -> "  (line $line)" } ?: ""}") }
        run?.let { result ->
            appendLine(result.summary())
            result.error?.let { appendLine(it) }
            result.logs.forEach { appendLine("log  $it") }
            result.exchanges.forEach { appendLine(it.toString()) }
            result.payload?.let { appendLine(it) }
            result.diagnostics?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        }
    }.trimEnd()

    /**
     * The seam an add-on hooks into.
     *
     * A share intent rather than an API: anything that can receive text can take
     * a project - a notes app, a diff tool, or an add-on that rewrites code and
     * shares it back - and none of it needs this app to know it exists.
     */
    private fun shareScript(context: Context, name: String, script: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, script)
        }
        context.startActivity(Intent.createChooser(intent, name))
    }

    private companion object {
        const val EDITOR_WEIGHT = 2f
        const val CONSOLE_WEIGHT = 1f
        const val CODE_SIZE = 13
        const val CODE_LINE_HEIGHT = 18
        const val CONSOLE_SIZE = 11
        const val GUTTER_WIDTH = 32
        const val PAYLOAD_PREVIEW = 2000
        const val REVISION_LIST_HEIGHT = 320
    }
}
// <-- AM (JS_PROJECTS)
