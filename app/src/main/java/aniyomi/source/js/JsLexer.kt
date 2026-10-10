package aniyomi.source.js

/**
 * What a run of characters is, for colouring purposes.
 *
 * The categories are the ones VS Code's themes actually distinguish, because the
 * colours are taken from one of them - see `JsSyntaxColors`. Anything the lexer
 * is unsure of is [PLAIN], which renders as the editor's foreground and is
 * always a safe answer.
 */
enum class JsTokenKind {
    PLAIN,
    COMMENT,
    STRING,
    NUMBER,

    /** Declarations and operators-as-words: `var`, `function`, `new`, `typeof`. */
    KEYWORD,

    /** Flow control, which VS Code colours differently: `if`, `return`, `for`. */
    CONTROL,

    /** An identifier called as a function. */
    FUNCTION,

    /** An identifier starting with a capital, which in practice means a type. */
    TYPE,

    /** Anything else nameable: variables, parameters, property keys. */
    IDENTIFIER,
}

class JsToken(val start: Int, val end: Int, val kind: JsTokenKind)

/**
 * A single-pass JavaScript lexer, good enough to colour an editor.
 *
 * ## What this is not
 *
 * It is not VS Code's tokenizer. That is a TextMate grammar interpreted by
 * Oniguruma, layered with semantic tokens from the TypeScript language server -
 * tens of thousands of lines of regex and a type checker, neither of which is
 * going into an Android app to colour a few hundred lines of code. The colours
 * here are VS Code's exactly; the decisions about which colour applies are this
 * lexer's, and on ordinary code they agree.
 *
 * Where they will differ: a regular expression literal is read as division, so
 * its contents colour as code; JSX is not understood; and a property named with
 * a keyword (`source.default`) colours as a keyword. All three are visual only -
 * nothing here can affect what runs.
 *
 * Template literals are followed properly, including `${...}` placeholders,
 * which are lexed as code at any nesting depth. That one is worth the trouble
 * because building URLs in templates is most of what a source does.
 */
fun lexJavaScript(text: String): List<JsToken> {
    val tokens = ArrayList<JsToken>()
    var index = 0

    // Depth of `{` seen inside a template placeholder, per nested template. A
    // stack rather than a counter because a placeholder can contain another
    // template, which can contain another placeholder.
    val templateStack = ArrayList<Int>()

    fun add(start: Int, end: Int, kind: JsTokenKind) {
        if (end > start) tokens.add(JsToken(start, end, kind))
    }

    while (index < text.length) {
        val character = text[index]

        when {
            character == '/' && text.startsWith("//", index) -> {
                val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                add(index, end, JsTokenKind.COMMENT)
                index = end
            }

            character == '/' && text.startsWith("/*", index) -> {
                val close = text.indexOf("*/", index + 2)
                val end = if (close < 0) text.length else close + 2
                add(index, end, JsTokenKind.COMMENT)
                index = end
            }

            character == '\'' || character == '"' -> {
                val end = endOfQuoted(text, index, character)
                add(index, end, JsTokenKind.STRING)
                index = end
            }

            character == '`' -> {
                val end = endOfTemplateChunk(text, index + 1)
                add(index, end, JsTokenKind.STRING)
                index = end
                // Stopped on a placeholder rather than the closing backtick, so
                // the code inside it is lexed by the main loop from here.
                if (end < text.length && text.startsWith("\${", end)) {
                    add(end, end + 2, JsTokenKind.PLAIN)
                    index = end + 2
                    templateStack.add(0)
                }
            }

            character == '{' && templateStack.isNotEmpty() -> {
                templateStack[templateStack.lastIndex]++
                add(index, index + 1, JsTokenKind.PLAIN)
                index++
            }

            character == '}' && templateStack.isNotEmpty() -> {
                val depth = templateStack.last()
                if (depth == 0) {
                    // Closes the placeholder: back to template text.
                    templateStack.removeAt(templateStack.lastIndex)
                    val end = endOfTemplateChunk(text, index + 1)
                    add(index, end, JsTokenKind.STRING)
                    index = end
                    if (end < text.length && text.startsWith("\${", end)) {
                        add(end, end + 2, JsTokenKind.PLAIN)
                        index = end + 2
                        templateStack.add(0)
                    }
                } else {
                    templateStack[templateStack.lastIndex] = depth - 1
                    add(index, index + 1, JsTokenKind.PLAIN)
                    index++
                }
            }

            character.isDigit() -> {
                var end = index
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '.')) end++
                add(index, end, JsTokenKind.NUMBER)
                index = end
            }

            character.isLetter() || character == '_' || character == '$' -> {
                var end = index
                while (end < text.length && isIdentifierPart(text[end])) end++
                val word = text.substring(index, end)
                add(index, end, classify(word, text, end))
                index = end
            }

            else -> {
                add(index, index + 1, JsTokenKind.PLAIN)
                index++
            }
        }
    }

    return tokens
}

private fun isIdentifierPart(character: Char): Boolean =
    character.isLetterOrDigit() || character == '_' || character == '$'

/** Index just past the closing quote, or at the newline that ended it unterminated. */
private fun endOfQuoted(text: String, start: Int, quote: Char): Int {
    var index = start + 1
    while (index < text.length) {
        when (text[index]) {
            '\\' -> index += 2
            quote -> return index + 1
            // An unterminated string stops at the line end rather than painting
            // the rest of the file: a missing quote is a typo, and colouring
            // everything after it hides the next hundred lines.
            '\n' -> return index
            else -> index++
        }
    }
    return text.length
}

/**
 * Index of the closing backtick (exclusive) or of the `${` that interrupts it.
 */
private fun endOfTemplateChunk(text: String, start: Int): Int {
    var index = start
    while (index < text.length) {
        when {
            text[index] == '\\' -> index += 2
            text[index] == '`' -> return index + 1
            text.startsWith("\${", index) -> return index
            else -> index++
        }
    }
    return text.length
}

private fun classify(word: String, text: String, end: Int): JsTokenKind = when {
    word in CONTROL_KEYWORDS -> JsTokenKind.CONTROL
    word in KEYWORDS -> JsTokenKind.KEYWORD
    isCallSite(text, end) -> JsTokenKind.FUNCTION
    word.first().isUpperCase() -> JsTokenKind.TYPE
    else -> JsTokenKind.IDENTIFIER
}

/** An identifier followed by `(`, ignoring spaces, is being called. */
private fun isCallSite(text: String, end: Int): Boolean {
    var index = end
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
    return index < text.length && text[index] == '('
}

private val CONTROL_KEYWORDS = setOf(
    "if", "else", "for", "while", "do", "switch", "case", "default", "break",
    "continue", "return", "throw", "try", "catch", "finally", "yield", "await",
)

private val KEYWORDS = setOf(
    "var", "let", "const", "function", "class", "new", "typeof", "instanceof",
    "in", "of", "delete", "void", "with", "extends", "super", "static", "get",
    "set", "async", "import", "export", "from", "as", "this", "true", "false",
    "null", "undefined", "NaN", "Infinity",
)
