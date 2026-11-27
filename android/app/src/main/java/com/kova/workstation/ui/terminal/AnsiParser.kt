package com.kova.workstation.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Stateful VT100 / ANSI SGR terminal stream parser designed for mobile-crisp ConPTY output.
 * Supports 16/256/24-bit colors, bold/italic/underline, OSC title extraction, CR overwrite,
 * backspace, and clear screen/line sequences while bounding scrollback memory.
 */
class AnsiTerminalBuffer(private val maxLines: Int = 1200) {

    data class StyledSpan(val text: String, val style: SpanStyle)

    private data class LineBuf(
        val chars: MutableList<Char> = mutableListOf(),
        val styles: MutableList<SpanStyle> = mutableListOf()
    ) {
        fun clear() {
            chars.clear()
            styles.clear()
        }

        fun truncateFrom(col: Int) {
            while (chars.size > col) {
                chars.removeAt(chars.lastIndex)
                styles.removeAt(styles.lastIndex)
            }
        }

        fun putChar(col: Int, ch: Char, style: SpanStyle) {
            while (chars.size < col) {
                chars.add(' ')
                styles.add(DEFAULT_STYLE)
            }
            if (col == chars.size) {
                chars.add(ch)
                styles.add(style)
            } else {
                chars[col] = ch
                styles[col] = style
            }
        }

        fun toAnnotatedString(): AnnotatedString = buildAnnotatedString {
            if (chars.isEmpty()) return@buildAnnotatedString
            var runStart = 0
            var runStyle = styles[0]
            val sb = StringBuilder()
            for (i in chars.indices) {
                val st = styles[i]
                if (st != runStyle) {
                    pushStyle(runStyle)
                    append(sb.toString())
                    pop()
                    sb.clear()
                    runStyle = st
                    runStart = i
                }
                sb.append(chars[i])
            }
            if (sb.isNotEmpty()) {
                pushStyle(runStyle)
                append(sb.toString())
                pop()
            }
        }

        fun plainText(): String = chars.joinToString("")
    }

    private enum class ParseState { NORMAL, ESC, CSI, OSC, OSC_ESC }

    private val lines = ArrayDeque<LineBuf>().apply { addLast(LineBuf()) }
    private var cursorCol = 0
    private var state = ParseState.NORMAL
    private val seqBuf = StringBuilder()
    private var currentStyle = DEFAULT_STYLE
    var windowTitle: String = ""
        private set

    fun clear() {
        lines.clear()
        lines.addLast(LineBuf())
        cursorCol = 0
        currentStyle = DEFAULT_STYLE
        state = ParseState.NORMAL
        seqBuf.clear()
    }

    fun feed(chunk: String) {
        for (ch in chunk) {
            when (state) {
                ParseState.NORMAL -> handleNormal(ch)
                ParseState.ESC -> handleEsc(ch)
                ParseState.CSI -> handleCsi(ch)
                ParseState.OSC -> handleOsc(ch)
                ParseState.OSC_ESC -> {
                    if (ch == '\\') finishOsc()
                    state = ParseState.NORMAL
                }
            }
        }
    }

    private fun handleNormal(ch: Char) {
        when (ch) {
            '\u001B' -> {
                seqBuf.clear()
                state = ParseState.ESC
            }
            '\r' -> cursorCol = 0
            '\n' -> {
                lines.addLast(LineBuf())
                while (lines.size > maxLines) lines.removeFirst()
                cursorCol = 0
            }
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> {
                val nextStop = ((cursorCol / 4) + 1) * 4
                val line = lines.last()
                while (cursorCol < nextStop) {
                    line.putChar(cursorCol, ' ', currentStyle)
                    cursorCol++
                }
            }
            '\u0007' -> Unit // Bell
            else -> {
                if (ch.code >= 0x20) {
                    lines.last().putChar(cursorCol, ch, currentStyle)
                    cursorCol++
                }
            }
        }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> {
                seqBuf.clear()
                state = ParseState.CSI
            }
            ']' -> {
                seqBuf.clear()
                state = ParseState.OSC
            }
            '(', ')' -> state = ParseState.NORMAL // Charset designation simplified
            else -> state = ParseState.NORMAL
        }
    }

    private fun handleCsi(ch: Char) {
        if (ch.code in 0x40..0x7E) {
            executeCsi(seqBuf.toString(), ch)
            state = ParseState.NORMAL
        } else {
            seqBuf.append(ch)
        }
    }

    private fun handleOsc(ch: Char) {
        when (ch) {
            '\u0007' -> {
                finishOsc()
                state = ParseState.NORMAL
            }
            '\u001B' -> state = ParseState.OSC_ESC
            else -> seqBuf.append(ch)
        }
    }

    private fun finishOsc() {
        val raw = seqBuf.toString()
        val semi = raw.indexOf(';')
        if (semi > 0) {
            val code = raw.substring(0, semi)
            if (code == "0" || code == "2") {
                windowTitle = raw.substring(semi + 1).trim()
            }
        }
    }

    private fun executeCsi(params: String, finalChar: Char) {
        val clean = params.removePrefix("?")
        when (finalChar) {
            'm' -> applySgr(clean)
            'J' -> {
                val mode = clean.toIntOrNull() ?: 0
                if (mode == 2 || mode == 3) {
                    lines.clear()
                    lines.addLast(LineBuf())
                    cursorCol = 0
                }
            }
            'K' -> {
                val mode = clean.toIntOrNull() ?: 0
                when (mode) {
                    0 -> lines.last().truncateFrom(cursorCol)
                    2 -> {
                        lines.last().clear()
                        cursorCol = 0
                    }
                }
            }
            'G' -> {
                val col = (clean.toIntOrNull() ?: 1) - 1
                cursorCol = col.coerceAtLeast(0)
            }
            'C' -> cursorCol += (clean.toIntOrNull() ?: 1).coerceAtLeast(1)
            'D' -> cursorCol = (cursorCol - (clean.toIntOrNull() ?: 1).coerceAtLeast(1)).coerceAtLeast(0)
            'H', 'f' -> {
                // ConPTY cursor home: start a clean line when returning to (1,1) on non-empty line
                val parts = clean.split(';')
                val row = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val col = parts.getOrNull(1)?.toIntOrNull() ?: 1
                if (row == 1 && col == 1 && lines.last().chars.isNotEmpty()) {
                    lines.addLast(LineBuf())
                    while (lines.size > maxLines) lines.removeFirst()
                }
                cursorCol = (col - 1).coerceAtLeast(0)
            }
        }
    }

    private fun applySgr(params: String) {
        if (params.isEmpty()) {
            currentStyle = DEFAULT_STYLE
            return
        }
        val nums = params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        var fg = currentStyle.color
        var bg = currentStyle.background
        var weight = currentStyle.fontWeight
        var italic = currentStyle.fontStyle
        var deco = currentStyle.textDecoration

        while (i < nums.size) {
            when (val code = nums[i]) {
                0 -> {
                    fg = DEFAULT_FG
                    bg = Color.Unspecified
                    weight = FontWeight.Normal
                    italic = FontStyle.Normal
                    deco = TextDecoration.None
                }
                1 -> weight = FontWeight.Bold
                22 -> weight = FontWeight.Normal
                3 -> italic = FontStyle.Italic
                23 -> italic = FontStyle.Normal
                4 -> deco = TextDecoration.Underline
                24 -> deco = TextDecoration.None
                in 30..37 -> fg = ANSI_BASE[code - 30]
                39 -> fg = DEFAULT_FG
                in 90..97 -> fg = ANSI_BRIGHT[code - 90]
                in 40..47 -> bg = ANSI_BASE[code - 40].copy(alpha = 0.35f)
                49 -> bg = Color.Unspecified
                in 100..107 -> bg = ANSI_BRIGHT[code - 100].copy(alpha = 0.35f)
                38 -> {
                    if (i + 2 < nums.size && nums[i + 1] == 5) {
                        fg = ansi256Color(nums[i + 2])
                        i += 2
                    } else if (i + 4 < nums.size && nums[i + 1] == 2) {
                        fg = Color(
                            nums[i + 2].coerceIn(0, 255),
                            nums[i + 3].coerceIn(0, 255),
                            nums[i + 4].coerceIn(0, 255)
                        )
                        i += 4
                    }
                }
                48 -> {
                    if (i + 2 < nums.size && nums[i + 1] == 5) {
                        bg = ansi256Color(nums[i + 2]).copy(alpha = 0.35f)
                        i += 2
                    } else if (i + 4 < nums.size && nums[i + 1] == 2) {
                        bg = Color(
                            nums[i + 2].coerceIn(0, 255),
                            nums[i + 3].coerceIn(0, 255),
                            nums[i + 4].coerceIn(0, 255)
                        ).copy(alpha = 0.35f)
                        i += 4
                    }
                }
            }
            i++
        }
        currentStyle = SpanStyle(
            color = fg,
            background = bg,
            fontWeight = weight,
            fontStyle = italic,
            textDecoration = deco
        )
    }

    fun snapshotLines(): List<AnnotatedString> = lines.map { it.toAnnotatedString() }

    fun plainText(): String = lines.joinToString("\n") { it.plainText() }

    companion object {
        val DEFAULT_FG = Color(0xFFE2E8F0)
        val DEFAULT_STYLE = SpanStyle(color = DEFAULT_FG)

        private val ANSI_BASE = arrayOf(
            Color(0xFF334155), // Black (readable on obsidian)
            Color(0xFFF87171), // Red
            Color(0xFF4ADE80), // Green
            Color(0xFFFACC15), // Yellow
            Color(0xFF60A5FA), // Blue
            Color(0xFFC084FC), // Magenta
            Color(0xFF22D3EE), // Cyan
            Color(0xFFE2E8F0)  // White
        )

        private val ANSI_BRIGHT = arrayOf(
            Color(0xFF64748B),
            Color(0xFFFCA5A5),
            Color(0xFF86EFAC),
            Color(0xFFFDE047),
            Color(0xFF93C5FD),
            Color(0xFFD8B4FE),
            Color(0xFF67E8F9),
            Color(0xFFFFFFFF)
        )

        fun ansi256Color(idx: Int): Color {
            val c = idx.coerceIn(0, 255)
            return when {
                c < 8 -> ANSI_BASE[c]
                c < 16 -> ANSI_BRIGHT[c - 8]
                c < 232 -> {
                    val v = c - 16
                    val r = (v / 36) * 51
                    val g = ((v % 36) / 6) * 51
                    val b = (v % 6) * 51
                    Color(r, g, b)
                }
                else -> {
                    val gray = 8 + (c - 232) * 10
                    Color(gray, gray, gray)
                }
            }
        }
    }
}
