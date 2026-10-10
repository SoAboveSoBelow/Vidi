// AM (JS_PROJECTS) -->
package eu.kanade.presentation.more.settings.screen.advanced

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import aniyomi.source.js.JsTokenKind
import aniyomi.source.js.lexJavaScript

/**
 * VS Code's Dark+ colours, verbatim.
 *
 * These are the values from the theme itself rather than an approximation of how
 * it looks, which is the part that can be matched exactly. What cannot be
 * matched is the tokenizer: VS Code decides which colour applies using a
 * TextMate grammar run through Oniguruma plus semantic tokens from the
 * TypeScript language server, and neither belongs in an Android app. See
 * [lexJavaScript] for where the two will disagree.
 *
 * The pane is dark regardless of the app's theme. A code editor that inverted
 * with the rest of the app would need a second, hand-picked palette for light
 * mode, and "the same as VS Code" has one answer, not two.
 */
object JsSyntaxColors {
    val background = Color(0xFF1E1E1E)
    val foreground = Color(0xFFD4D4D4)
    val cursor = Color(0xFFAEAFAD)
    val lineNumber = Color(0xFF858585)

    /** editorError.foreground, for the console. */
    val error = Color(0xFFF48771)

    val comment = Color(0xFF6A9955)
    val string = Color(0xFFCE9178)
    val number = Color(0xFFB5CEA8)
    val keyword = Color(0xFF569CD6)
    val control = Color(0xFFC586C0)
    val function = Color(0xFFDCDCAA)
    val type = Color(0xFF4EC9B0)
    val identifier = Color(0xFF9CDCFE)

    fun of(kind: JsTokenKind): Color = when (kind) {
        JsTokenKind.COMMENT -> comment
        JsTokenKind.STRING -> string
        JsTokenKind.NUMBER -> number
        JsTokenKind.KEYWORD -> keyword
        JsTokenKind.CONTROL -> control
        JsTokenKind.FUNCTION -> function
        JsTokenKind.TYPE -> type
        JsTokenKind.IDENTIFIER -> identifier
        JsTokenKind.PLAIN -> foreground
    }
}

/**
 * Colours the buffer without changing it.
 *
 * [OffsetMapping.Identity] is the important part: the transformation adds
 * styling and not one character, so every offset the text field computes - the
 * caret, the selection, a tap - means the same thing before and after. A
 * transformation that inserted or hid characters would need a real mapping, and
 * getting that subtly wrong is how an editor starts putting typed characters in
 * the wrong place.
 *
 * Lexing is a single pass and the result is cached per text by the caller, so
 * this costs one scan per edit rather than one per frame.
 */
class JsSyntaxTransformation(private val highlighted: AnnotatedString) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlighted, OffsetMapping.Identity)
}

fun highlightJavaScript(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    lexJavaScript(text).forEach { token ->
        if (token.kind != JsTokenKind.PLAIN) {
            addStyle(
                style = SpanStyle(color = JsSyntaxColors.of(token.kind)),
                start = token.start,
                end = token.end,
            )
        }
    }
}
// <-- AM (JS_PROJECTS)
