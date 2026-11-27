package com.kova.workstation

import com.kova.workstation.ui.files.SyntaxHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    @Test
    fun highlightsPythonKeywordsAndStrings() {
        val styled = SyntaxHighlighter.highlightLine("def greet(): return 'hello'", "python")
        assertEquals("def greet(): return 'hello'", styled.text)
        assertTrue(styled.spanStyles.size >= 3)
    }

    @Test
    fun highlightsUnifiedDiffLines() {
        val added = SyntaxHighlighter.highlightLine("+val kova = true", "diff")
        val removed = SyntaxHighlighter.highlightLine("-val old = false", "diff")
        assertTrue(added.spanStyles.isNotEmpty())
        assertTrue(removed.spanStyles.isNotEmpty())
    }
}
