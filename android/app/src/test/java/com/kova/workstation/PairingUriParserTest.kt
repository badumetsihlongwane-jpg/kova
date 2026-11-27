package com.kova.workstation

import com.kova.workstation.data.network.PairingUriParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingUriParserTest {

    @Test
    fun parsesValidTlsPairingUri() {
        val uri = "kova://connect?host=100.88.12.5&port=8765&token=abc123SECRET&fp=deadbeef01&name=My%20VPS&tls=1"
        val profile = PairingUriParser.parse(uri)
        assertNotNull(profile)
        assertEquals("100.88.12.5", profile!!.host)
        assertEquals(8765, profile.port)
        assertEquals("abc123SECRET", profile.token)
        assertEquals("DEADBEEF01", profile.certFingerprint)
        assertEquals("My VPS", profile.name)
        assertTrue(profile.tlsEnabled)
    }

    @Test
    fun parsesNoTlsUriAndDefaultsPort() {
        val uri = "kova://connect?host=10.0.0.5&token=tok999&tls=0"
        val profile = PairingUriParser.parse(uri)
        assertNotNull(profile)
        assertEquals(8765, profile!!.port)
        assertFalse(profile.tlsEnabled)
        assertEquals("10.0.0.5", profile.name)
    }

    @Test
    fun rejectsMalformedOrMissingTokenUri() {
        assertNull(PairingUriParser.parse("https://example.com"))
        assertNull(PairingUriParser.parse("kova://connect?host=1.2.3.4"))
    }
}
