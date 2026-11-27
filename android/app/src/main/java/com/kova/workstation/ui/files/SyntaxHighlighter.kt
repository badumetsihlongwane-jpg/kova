package com.kova.workstation.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

object SyntaxHighlighter {

    private val keywordColor = Color(0xFF7DD3FC)
    private val stringColor = Color(0xFF86EFAC)
    private val commentColor = Color(0xFF64748B)
    private val numberColor = Color(0xFFFDBA74)
    private val typeColor = Color(0xFFC4B5FD)
    private val diffAddColor = Color(0xFF4ADE80)
    private val diffDelColor = Color(0xFFFB7185)
    private val diffHunkColor = Color(0xFF38BDF8)

    private val commonKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "override",
        "def", "async", "await", "return", "yield", "import", "from", "as", "if", "elif", "else",
        "for", "while", "in", "not", "and", "or", "try", "except", "catch", "finally", "raise",
        "throw", "new", "const", "let", "function", "export", "default", "switch", "case", "break",
        "continue", "true", "false", "null", "None", "True", "False", "self", "this", "public",
        "private", "protected", "internal", "static", "void", "using", "namespace", "struct", "fn",
        "impl", "pub", "mut", "match", "package", "param", "foreach", "when", "suspend", "companion"
    )

    private val tokenRegex = Regex(
        """(#[^\n]*|//[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)"""
    )

    fun highlightLine(line: String, language: String): AnnotatedString {
        if (language == "diff") {
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> Color(0xFFCBD5E1)
                line.startsWith("+") -> diffAddColor
                line.startsWith("-") -> diffDelColor
                line.startsWith("@@") -> diffHunkColor
                else -> Color(0xFFE2E8F0)
            }
            val bg = when {
                line.startsWith("+") && !line.startsWith("+++") -> diffAddColor.copy(alpha = 0.12f)
                line.startsWith("-") && !line.startsWith("---") -> diffDelColor.copy(alpha = 0.12f)
                else -> Color.Unspecified
            }
            return buildAnnotatedString {
                pushStyle(SpanStyle(color = color, background = bg))
                append(line)
                pop()
            }
        }

        if (language == "text" || language == "log" || line.length > 600) {
            return AnnotatedString(line)
        }

        return buildAnnotatedString {
            append(line)
            for (match in tokenRegex.findAll(line)) {
                val tok = match.value
                val start = match.range.first
                val end = match.range.last + 1
                val style = when {
                    tok.startsWith("#") && language in setOf("python", "yaml", "toml", "shell", "powershell", "env") ->
                        SpanStyle(color = commentColor)
                    tok.startsWith("//") ->
                        SpanStyle(color = commentColor)
                    tok.startsWith("\"") || tok.startsWith("'") ->
                        SpanStyle(color = stringColor)
                    tok.first().isDigit() ->
                        SpanStyle(color = numberColor)
                    tok in commonKeywords ->
                        SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium)
                    tok.first().isUpperCase() && tok.length > 1 && tok.any { it.isLowerCase() } ->
                        SpanStyle(color = typeColor)
                    else -> null
                }
                if (style != null) {
                    addStyle(style, start, end)
                }
            }
        }
    }

    fun highlightDocument(text: String, language: String): AnnotatedString {
        if (text.length > 60_000) return AnnotatedString(text)
        return buildAnnotatedString {
            val lines = text.split('\n')
            lines.forEachIndexed { index, line ->
                append(highlightLine(line, language))
                if (index < lines.lastIndex) append('\n')
            }
        }
    }
}
