package com.kova.workstation.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kova.workstation.data.model.ClipboardResponse
import com.kova.workstation.data.model.FileListResponse
import com.kova.workstation.data.model.FileReadResponse
import com.kova.workstation.data.model.FileRootsResponse
import com.kova.workstation.data.model.FileSearchResponse
import com.kova.workstation.data.model.GenericOkResponse
import com.kova.workstation.data.model.GitDiffResponse
import com.kova.workstation.data.model.GitStatusResponse
import com.kova.workstation.data.model.StageFrameMeta
import com.kova.workstation.data.model.SystemInfo
import com.kova.workstation.data.model.TerminalCreateResponse
import com.kova.workstation.data.model.TerminalsListResponse
import com.kova.workstation.data.model.UiaInspectResponse
import com.kova.workstation.data.model.WindowsListResponse
import com.kova.workstation.data.model.WorkstationProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class StageFrame(
    val bitmap: Bitmap,
    val meta: StageFrameMeta
)

class KovaClient(val profile: WorkstationProfile) {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val httpClient: OkHttpClient = buildOkHttpClient(profile)

    private fun buildOkHttpClient(p: WorkstationProfile): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)

        if (p.tlsEnabled && p.certFingerprint.isNotBlank()) {
            val expectedFp = p.certFingerprint.replace(":", "").trim().uppercase()
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
                    val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    val actual = digest.joinToString("") { "%02X".format(it) }
                    if (!MessageDigest.isEqual(actual.toByteArray(), expectedFp.toByteArray())) {
                        throw CertificateException(
                            "TLS certificate pin mismatch! Expected ${expectedFp.take(12)}..., got ${actual.take(12)}..."
                        )
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun authRequest(path: String): Request.Builder =
        Request.Builder()
            .url("${profile.baseHttpUrl}$path")
            .header("Authorization", "Bearer ${profile.token}")

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private suspend inline fun <reified T> getJson(path: String): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(authRequest(path).get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${body.take(240)}")
                }
                json.decodeFromString<T>(body)
            }
        }
    }

    private suspend inline fun <reified T> postJson(path: String, payload: JsonObject): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val reqBody = payload.toString().toRequestBody(jsonMediaType)
                httpClient.newCall(authRequest(path).post(reqBody).build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}: ${body.take(240)}")
                    }
                    json.decodeFromString<T>(body)
                }
            }
        }

    private suspend inline fun <reified T> deleteJson(path: String): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(authRequest(path).delete().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${body.take(240)}")
                }
                json.decodeFromString<T>(body)
            }
        }
    }

    // ---------- REST API ----------

    suspend fun getSystemInfo(): Result<SystemInfo> = getJson("/api/system/info")

    suspend fun activateDisplay(): Result<GenericOkResponse> = postJson("/api/system/activate-display", buildJsonObject {})

    suspend fun listWindows(): Result<WindowsListResponse> = getJson("/api/windows")

    suspend fun fetchWindowThumbnail(hwnd: Long, width: Int = 360): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(authRequest("/api/windows/$hwnd/thumbnail?w=$width").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Thumbnail HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: error("Empty thumbnail")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Decode thumbnail failed")
            }
        }
    }

    suspend fun windowAction(hwnd: Long, action: String, width: Int? = null, height: Int? = null): Result<GenericOkResponse> =
        postJson(
            "/api/windows/$hwnd/action",
            buildJsonObject {
                put("action", action)
                if (width != null) put("width", width)
                if (height != null) put("height", height)
            }
        )

    suspend fun inspectUia(hwnd: Long, maxNodes: Int = 160): Result<UiaInspectResponse> =
        getJson("/api/windows/$hwnd/uia?max_nodes=$maxNodes")

    suspend fun uiaAction(
        hwnd: Long,
        runtimeId: String,
        action: String,
        value: String = "",
        x: Int? = null,
        y: Int? = null
    ): Result<GenericOkResponse> = postJson(
        "/api/uia/action",
        buildJsonObject {
            put("hwnd", hwnd)
            put("runtime_id", runtimeId)
            put("action", action)
            put("value", value)
            if (x != null) put("x", x)
            if (y != null) put("y", y)
        }
    )

    suspend fun dispatchText(
        text: String,
        pressEnter: Boolean,
        mode: String = "paste",
        hwnd: Long? = null
    ): Result<GenericOkResponse> = postJson(
        "/api/input/dispatch",
        buildJsonObject {
            put("text", text)
            put("press_enter", pressEnter)
            put("mode", mode)
            if (hwnd != null && hwnd > 0) put("hwnd", hwnd)
        }
    )

    suspend fun sendKeyChord(keys: List<String>, hwnd: Long? = null): Result<GenericOkResponse> = postJson(
        "/api/input/key",
        buildJsonObject {
            put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
            if (hwnd != null && hwnd > 0) put("hwnd", hwnd)
        }
    )

    suspend fun launchTarget(target: String, args: List<String> = emptyList(), cwd: String? = null): Result<GenericOkResponse> =
        postJson(
            "/api/launch",
            buildJsonObject {
                put("target", target)
                put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
                if (!cwd.isNullOrBlank()) put("cwd", cwd)
            }
        )

    suspend fun getClipboard(): Result<ClipboardResponse> = getJson("/api/clipboard")

    suspend fun setClipboard(text: String): Result<GenericOkResponse> =
        postJson("/api/clipboard", buildJsonObject { put("text", text) })

    suspend fun listTerminals(): Result<TerminalsListResponse> = getJson("/api/terminals")

    suspend fun createTerminal(shell: String = "powershell", cwd: String? = null, cols: Int = 72, rows: Int = 24): Result<TerminalCreateResponse> =
        postJson(
            "/api/terminals",
            buildJsonObject {
                put("shell", shell)
                if (!cwd.isNullOrBlank()) put("cwd", cwd)
                put("cols", cols)
                put("rows", rows)
            }
        )

    suspend fun deleteTerminal(id: String): Result<GenericOkResponse> = deleteJson("/api/terminals/$id")

    suspend fun getFileRoots(): Result<FileRootsResponse> = getJson("/api/files/roots")

    suspend fun listFiles(path: String? = null, showHidden: Boolean = false): Result<FileListResponse> {
        val qs = buildString {
            append("?hidden=").append(if (showHidden) "1" else "0")
            if (!path.isNullOrBlank()) append("&path=").append(enc(path))
        }
        return getJson("/api/files/list$qs")
    }

    suspend fun readFile(path: String, tailLines: Int? = null): Result<FileReadResponse> {
        val tailPart = if (tailLines != null) "&tail=$tailLines" else ""
        return getJson("/api/files/read?path=${enc(path)}$tailPart")
    }

    suspend fun writeFile(path: String, content: String): Result<GenericOkResponse> =
        postJson("/api/files/write", buildJsonObject { put("path", path); put("content", content) })

    suspend fun makeDirectory(path: String): Result<GenericOkResponse> =
        postJson("/api/files/mkdir", buildJsonObject { put("path", path) })

    suspend fun renameFile(src: String, dst: String): Result<GenericOkResponse> =
        postJson("/api/files/rename", buildJsonObject { put("src", src); put("dst", dst) })

    suspend fun deleteFile(path: String): Result<GenericOkResponse> =
        deleteJson("/api/files/delete?path=${enc(path)}")

    suspend fun searchFiles(root: String, query: String): Result<FileSearchResponse> =
        getJson("/api/files/search?path=${enc(root)}&q=${enc(query)}")

    suspend fun uploadFile(destDir: String, fileName: String, bytes: ByteArray): Result<GenericOkResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        fileName,
                        bytes.toRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                val req = authRequest("/api/files/upload?dir=${enc(destDir)}&name=${enc(fileName)}")
                    .post(body)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("Upload HTTP ${resp.code}: $text")
                    json.decodeFromString<GenericOkResponse>(text)
                }
            }
        }

    suspend fun downloadFile(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(authRequest("/api/files/download?path=${enc(path)}").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    suspend fun gitStatus(path: String): Result<GitStatusResponse> = getJson("/api/git/status?path=${enc(path)}")

    suspend fun gitDiff(path: String, fileRel: String? = null, staged: Boolean = false): Result<GitDiffResponse> {
        val filePart = if (!fileRel.isNullOrBlank()) "&file=${enc(fileRel)}" else ""
        return getJson("/api/git/diff?path=${enc(path)}&staged=${if (staged) "1" else "0"}$filePart")
    }

    suspend fun gitAction(
        path: String,
        action: String,
        message: String = "",
        files: List<String>? = null
    ): Result<GenericOkResponse> = postJson(
        "/api/git/action",
        buildJsonObject {
            put("path", path)
            put("action", action)
            put("message", message)
            if (files != null) put("files", buildJsonArray { files.forEach { add(JsonPrimitive(it)) } })
        }
    )

    // ---------- WebSockets ----------

    fun openEventsSocket(
        onTelemetry: (SystemInfo) -> Unit,
        onClipboard: (String) -> Unit,
        onPong: (Long) -> Unit,
        onState: (Boolean, String?) -> Unit
    ): WebSocket {
        val req = Request.Builder()
            .url("${profile.baseWsUrl}/ws/events?token=${enc(profile.token)}")
            .build()
        return httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "telemetry" -> {
                            val data = obj["data"] ?: return
                            val info = json.decodeFromJsonElement(SystemInfo.serializer(), data)
                            onTelemetry(info)
                        }
                        "clipboard" -> {
                            val clipText = obj["data"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
                            if (clipText.isNotEmpty()) onClipboard(clipText)
                        }
                        "pong" -> {
                            val sent = obj["ts"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L
                            if (sent > 0L) onPong(System.currentTimeMillis() - sent)
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState(false, reason.ifBlank { null })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState(false, t.message ?: "Connection lost")
            }
        })
    }

    fun openStageSocket(
        onFrame: (StageFrame) -> Unit,
        onWarning: (String?) -> Unit,
        onCursorAck: (x: Int, y: Int, seq: Long) -> Unit,
        onWindowState: (state: String, hwnd: Long, message: String) -> Unit,
        onFocusedInput: (editable: Boolean, role: String, name: String) -> Unit,
        onClosed: (String?) -> Unit
    ): WebSocket {
        val req = Request.Builder()
            .url("${profile.baseWsUrl}/ws/stage?token=${enc(profile.token)}")
            .build()
        return httpClient.newWebSocket(req, object : WebSocketListener() {
            private val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = false
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching {
                    val arr = bytes.toByteArray()
                    if (arr.size < 8) return
                    val hdrLen = ByteBuffer.wrap(arr, 0, 4).int
                    if (hdrLen <= 0 || 4 + hdrLen >= arr.size) return
                    val metaStr = String(arr, 4, hdrLen, Charsets.UTF_8)
                    val meta = json.decodeFromString<StageFrameMeta>(metaStr)
                    val imgOffset = 4 + hdrLen
                    val bmp = BitmapFactory.decodeByteArray(arr, imgOffset, arr.size - imgOffset, decodeOpts)
                    if (bmp != null) {
                        if (meta.frameId > 0L) {
                            webSocket.send("""{"type":"frame_ack","id":${meta.frameId}}""")
                        }
                        onFrame(StageFrame(bmp, meta))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "session_warning" -> {
                            val code = obj["code"]?.jsonPrimitive?.content
                            val msg = obj["message"]?.jsonPrimitive?.content
                            onWarning(if (code == "ok") null else msg)
                        }
                        "cursor_ack", "cursor" -> {
                            val cx = obj["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                            val cy = obj["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                            val seq = obj["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                            onCursorAck(cx, cy, seq)
                        }
                        "window_state" -> {
                            val st = obj["state"]?.jsonPrimitive?.content.orEmpty()
                            val hwnd = obj["hwnd"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                            val msg = obj["message"]?.jsonPrimitive?.content.orEmpty()
                            onWindowState(st, hwnd, msg)
                        }
                        "focused_input" -> {
                            val editable = obj["editable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                            val role = obj["role"]?.jsonPrimitive?.content.orEmpty()
                            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                            onFocusedInput(editable, role, name)
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(reason.ifBlank { null })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(t.message)
            }
        })
    }

    fun openTerminalSocket(
        terminalId: String,
        onOutput: (String, Boolean) -> Unit,
        onExit: (Int) -> Unit,
        onClosed: (String?) -> Unit
    ): WebSocket {
        val req = Request.Builder()
            .url("${profile.baseWsUrl}/ws/terminal/$terminalId?token=${enc(profile.token)}")
            .build()
        return httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "replay" -> onOutput(obj["data"]?.jsonPrimitive?.content.orEmpty(), true)
                        "output" -> onOutput(obj["data"]?.jsonPrimitive?.content.orEmpty(), false)
                        "exit" -> onExit(obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(reason.ifBlank { null })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(t.message)
            }
        })
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
