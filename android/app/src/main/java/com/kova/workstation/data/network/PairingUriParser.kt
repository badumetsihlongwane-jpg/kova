package com.kova.workstation.data.network

import com.kova.workstation.data.model.WorkstationProfile
import java.net.URI
import java.net.URLDecoder

object PairingUriParser {
    /**
     * Parses `kova://connect?host=...&port=8765&token=...&fp=...&name=...&tls=1`
     * into a WorkstationProfile. Uses pure java.net.URI so unit tests run cleanly on JVM.
     */
    fun parse(raw: String): WorkstationProfile? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("kova://", ignoreCase = true)) return null
        return try {
            val uri = URI(trimmed)
            val query = uri.rawQuery ?: return null
            val params = mutableMapOf<String, String>()
            for (pair in query.split("&")) {
                if (pair.isBlank()) continue
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8").lowercase()
                val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                params[key] = value
            }
            val host = params["host"]?.trim().orEmpty()
            val token = params["token"]?.trim().orEmpty()
            if (host.isEmpty() || token.isEmpty()) return null

            val port = params["port"]?.toIntOrNull()?.coerceIn(1, 65535) ?: 8765
            val fp = params["fp"]?.trim()?.uppercase().orEmpty()
            val name = params["name"]?.trim().takeIf { !it.isNullOrEmpty() } ?: host
            val tls = when (params["tls"]?.lowercase()) {
                "0", "false", "no" -> false
                else -> true
            }
            WorkstationProfile(
                name = name,
                host = host,
                port = port,
                token = token,
                certFingerprint = fp,
                tlsEnabled = tls
            )
        } catch (_: Exception) {
            null
        }
    }
}
