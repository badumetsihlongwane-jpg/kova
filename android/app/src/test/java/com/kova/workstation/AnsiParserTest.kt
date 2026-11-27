package com.kova.workstation

import com.kova.workstation.ui.terminal.AnsiTerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserTest {

    @Test
    fun stripsAnsiCodesAndExtractsOscTitle() {
        val buf = AnsiTerminalBuffer()
        buf.feed("\u001B]0;PowerShell 7\u0007\u001B[32mHello\u001B[0m World\r\nLine 2")
        assertEquals("PowerShell 7", buf.windowTitle)
        assertEquals("Hello World\nLine 2", buf.plainText())
        assertEquals(2, buf.snapshotLines().size)
    }

    @Test
    fun handlesCarriageReturnAndBackspaceOverwrite() {
        val buf = AnsiTerminalBuffer()
        buf.feed("Progress 10%\rProgress 99%")
        assertEquals("Progress 99%", buf.plainText())

        val buf2 = AnsiTerminalBuffer()
        buf2.feed("abx\bc")
        assertEquals("abc", buf2.plainText())
    }

    @Test
    fun handlesClearScreenSequence() {
        val buf = AnsiTerminalBuffer()
        buf.feed("old line\nmore\u001B[2Jfresh")
        assertTrue(buf.plainText().contains("fresh"))
        assertEquals(1, buf.snapshotLines().size)
    }
}
