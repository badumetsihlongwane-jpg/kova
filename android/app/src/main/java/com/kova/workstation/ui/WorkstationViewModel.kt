package com.kova.workstation.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kova.workstation.data.model.AppPreferences
import com.kova.workstation.data.model.ClipboardEntry
import com.kova.workstation.data.model.CommandSnippet
import com.kova.workstation.data.model.FileEntry
import com.kova.workstation.data.model.FileReadResponse
import com.kova.workstation.data.model.FileRoot
import com.kova.workstation.data.model.GitStatusResponse
import com.kova.workstation.data.model.StageFrameMeta
import com.kova.workstation.data.model.SystemInfo
import com.kova.workstation.data.model.TerminalInfo
import com.kova.workstation.data.model.UiaElement
import com.kova.workstation.data.model.UiaInspectResponse
import com.kova.workstation.data.model.WindowInfo
import com.kova.workstation.data.model.WorkstationProfile
import com.kova.workstation.data.network.KovaClient
import com.kova.workstation.data.network.PairingUriParser
import com.kova.workstation.data.storage.ProfileStore
import com.kova.workstation.service.WorkstationService
import com.kova.workstation.ui.terminal.AnsiTerminalBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.WebSocket

enum class WorkspaceTab { PULSE, LENS, SHELL, FORGE, SETTINGS }
enum class StageInputMode { TRACKPAD, DIRECT, LOUPE }

data class StageRoi(val x: Float, val y: Float, val w: Float, val h: Float)

data class TerminalTabState(
    val info: TerminalInfo,
    val lines: List<AnnotatedString> = emptyList(),
    val titleOverride: String = "",
    val connected: Boolean = false
)

data class OpenEditorState(
    val path: String,
    val name: String,
    val language: String,
    val content: String,
    val savedContent: String,
    val isBinary: Boolean = false,
    val truncated: Boolean = false,
    val saving: Boolean = false,
    val liveTail: Boolean = false
) {
    val dirty: Boolean get() = content != savedContent
}

data class WorkstationUiState(
    val profiles: List<WorkstationProfile> = emptyList(),
    val activeProfile: WorkstationProfile? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val connectionError: String? = null,
    val latencyMs: Long? = null,
    val activeTab: WorkspaceTab = WorkspaceTab.PULSE,
    val preferences: AppPreferences = AppPreferences(),
    val snippets: List<CommandSnippet> = emptyList(),

    // Pulse Hub
    val systemInfo: SystemInfo? = null,
    val windows: List<WindowInfo> = emptyList(),
    val windowThumbnails: Map<Long, Bitmap> = emptyMap(),
    val windowsLoading: Boolean = false,
    val windowsClipboardText: String = "",
    val clipboardHistory: List<ClipboardEntry> = emptyList(),

    // Lens Visual Stage & Semantic UI Automation
    val stageMode: String = "desktop", // "desktop" or "window"
    val stageHwnd: Long? = null,
    val stageBitmap: Bitmap? = null,
    val stageMeta: StageFrameMeta = StageFrameMeta(),
    val stageWarning: String? = null,
    val stageInputMode: StageInputMode = StageInputMode.TRACKPAD,
    val stageRoi: StageRoi? = null,
    val stageDragLocked: Boolean = false,
    val stageXRayEnabled: Boolean = false,
    val uiaData: UiaInspectResponse? = null,
    val uiaLoading: Boolean = false,
    val showSemanticSheet: Boolean = false,
    val showPromptComposer: Boolean = false,

    // Shell (ConPTY)
    val terminals: List<TerminalTabState> = emptyList(),
    val activeTerminalId: String? = null,

    // Forge (Files, Editor & Git)
    val fileRoots: List<FileRoot> = emptyList(),
    val currentPath: String = "",
    val parentPath: String? = null,
    val currentGitRoot: String? = null,
    val fileEntries: List<FileEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val showHiddenFiles: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<FileEntry>? = null,
    val editor: OpenEditorState? = null,
    val gitStatus: GitStatusResponse? = null,
    val gitDiffText: String = "",
    val gitDiffFile: String? = null,
    val showGitSheet: Boolean = false,

    // Transient Toast / Banner
    val bannerMessage: String? = null
)

class WorkstationViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ProfileStore(application)
    private val _uiState = MutableStateFlow(
        WorkstationUiState(
            profiles = store.loadProfiles(),
            preferences = store.loadPreferences(),
            snippets = store.loadSnippets()
        )
    )
    val uiState: StateFlow<WorkstationUiState> = _uiState.asStateFlow()

    private var client: KovaClient? = null
    private var eventsWs: WebSocket? = null
    private var stageWs: WebSocket? = null
    private val terminalSockets = mutableMapOf<String, WebSocket>()
    private val terminalBuffers = mutableMapOf<String, AnsiTerminalBuffer>()

    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var logTailJob: Job? = null
    private var bannerJob: Job? = null

    fun showBanner(message: String) {
        _uiState.update { it.copy(bannerMessage = message) }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(3200)
            _uiState.update { if (it.bannerMessage == message) it.copy(bannerMessage = null) else it }
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun handleIncomingPairingUri(rawUri: String): WorkstationProfile? {
        val parsed = PairingUriParser.parse(rawUri)
        if (parsed != null) {
            val updated = store.upsertProfile(parsed)
            _uiState.update { it.copy(profiles = updated) }
            showBanner("Paired ${parsed.name} (${parsed.host})")
            connect(parsed)
        } else {
            showBanner("Invalid Kova pairing link")
        }
        return parsed
    }

    fun saveAndConnectProfile(profile: WorkstationProfile) {
        val updated = store.upsertProfile(profile)
        _uiState.update { it.copy(profiles = updated) }
        connect(profile)
    }

    fun deleteProfile(id: String) {
        val updated = store.deleteProfile(id)
        _uiState.update { it.copy(profiles = updated) }
    }

    fun connect(profile: WorkstationProfile) {
        disconnectInternal(keepProfile = true)
        _uiState.update {
            it.copy(
                activeProfile = profile,
                connecting = true,
                connected = false,
                connectionError = null
            )
        }

        val newClient = KovaClient(profile)
        client = newClient

        viewModelScope.launch {
            val res = newClient.getSystemInfo()
            res.onSuccess { info ->
                val saved = profile.copy(
                    name = info.name.ifBlank { profile.name },
                    lastConnectedMs = System.currentTimeMillis()
                )
                val updatedProfiles = store.upsertProfile(saved)
                _uiState.update {
                    it.copy(
                        profiles = updatedProfiles,
                        activeProfile = saved,
                        systemInfo = info,
                        connected = true,
                        connecting = false,
                        connectionError = null
                    )
                }
                if (_uiState.value.preferences.keepAliveService) {
                    runCatching {
                        WorkstationService.start(
                            getApplication(),
                            saved.name,
                            "${saved.host}:${saved.port}"
                        )
                    }
                }
                startEventsSocket(newClient)
                refreshWindows(withThumbnails = true)
                refreshClipboard()
                loadInitialFiles()
                syncTerminals()
            }.onFailure { err ->
                val raw = err.message.orEmpty()
                val friendly = when {
                    raw.contains("401") -> "Authentication rejected (HTTP 401). Re-scan the QR code from KovaHost.exe to update your token."
                    raw.contains("pin mismatch", ignoreCase = true) || raw.contains("CertPath", ignoreCase = true) ->
                        "TLS certificate changed or did not match the pinned fingerprint. Re-scan the QR code in KovaHost.exe."
                    raw.contains("timeout", ignoreCase = true) || raw.contains("failed to connect", ignoreCase = true) ->
                        "Could not reach ${profile.host}:${profile.port}. Ensure KovaHost.exe is running and TCP ${profile.port} is open (or use Tailscale)."
                    raw.contains("Unable to resolve host", ignoreCase = true) ->
                        "Cannot resolve '${profile.host}'. Check the IP/hostname and your network connection."
                    else -> raw.ifBlank { "Unable to connect to ${profile.host}:${profile.port}" }
                }
                _uiState.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        connectionError = friendly
                    )
                }
            }
        }
    }

    fun disconnect() {
        disconnectInternal(keepProfile = false)
        runCatching { WorkstationService.stop(getApplication()) }
    }

    private fun disconnectInternal(keepProfile: Boolean) {
        reconnectJob?.cancel()
        pingJob?.cancel()
        logTailJob?.cancel()
        eventsWs?.close(1000, "disconnect")
        eventsWs = null
        stageWs?.close(1000, "disconnect")
        stageWs = null
        terminalSockets.values.forEach { it.close(1000, "disconnect") }
        terminalSockets.clear()
        terminalBuffers.clear()
        client?.shutdown()
        client = null

        _uiState.update {
            it.copy(
                activeProfile = if (keepProfile) it.activeProfile else null,
                connected = false,
                connecting = false,
                latencyMs = null,
                stageBitmap = null,
                terminals = emptyList(),
                activeTerminalId = null,
                editor = null
            )
        }
    }

    private fun startEventsSocket(c: KovaClient) {
        eventsWs?.close(1000, "restart")
        eventsWs = c.openEventsSocket(
            onTelemetry = { info ->
                _uiState.update { it.copy(systemInfo = info, connected = true) }
            },
            onClipboard = { clipText ->
                _uiState.update { it.copy(windowsClipboardText = clipText) }
                if (_uiState.value.preferences.autoClipboardSync) {
                    copyToAndroidClipboard(clipText, silent = true)
                }
            },
            onPong = { rtt ->
                _uiState.update { it.copy(latencyMs = rtt.coerceAtLeast(1L)) }
            },
            onState = { isOpen, err ->
                _uiState.update { it.copy(connected = isOpen, connectionError = if (isOpen) null else err) }
                if (!isOpen && _uiState.value.activeProfile != null && client === c) {
                    scheduleReconnect()
                }
            }
        )

        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val payload = buildJsonObject {
                    put("type", "ping")
                    put("ts", System.currentTimeMillis())
                }.toString()
                eventsWs?.send(payload)
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var delayMs = 2000L
            while (isActive && !_uiState.value.connected) {
                val p = _uiState.value.activeProfile ?: break
                delay(delayMs)
                val c = client ?: break
                val check = c.getSystemInfo()
                if (check.isSuccess) {
                    _uiState.update { it.copy(systemInfo = check.getOrNull(), connected = true, connectionError = null) }
                    startEventsSocket(c)
                    if (_uiState.value.activeTab == WorkspaceTab.LENS) startStageStream()
                    break
                }
                delayMs = (delayMs * 2).coerceAtMost(15000L)
            }
        }
    }

    fun selectTab(tab: WorkspaceTab) {
        val prev = _uiState.value.activeTab
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == WorkspaceTab.LENS) {
            startStageStream()
        } else if (prev == WorkspaceTab.LENS) {
            stopStageStream()
        }
        if (tab == WorkspaceTab.PULSE) {
            refreshWindows(withThumbnails = true)
        } else if (tab == WorkspaceTab.SHELL && _uiState.value.terminals.isEmpty()) {
            createTerminal("powershell")
        } else if (tab == WorkspaceTab.FORGE && _uiState.value.fileEntries.isEmpty()) {
            loadInitialFiles()
        }
    }

    // ---------- Pulse Hub ----------

    fun refreshWindows(withThumbnails: Boolean = false) {
        val c = client ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(windowsLoading = true) }
            c.listWindows().onSuccess { resp ->
                _uiState.update { it.copy(windows = resp.windows, windowsLoading = false) }
                if (withThumbnails) {
                    resp.windows.filterNot { it.minimized }.take(8).forEach { win ->
                        launch {
                            c.fetchWindowThumbnail(win.hwnd).onSuccess { bmp ->
                                _uiState.update { state ->
                                    state.copy(windowThumbnails = state.windowThumbnails + (win.hwnd to bmp))
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                _uiState.update { it.copy(windowsLoading = false) }
            }
        }
    }

    fun windowAction(hwnd: Long, action: String, width: Int? = null, height: Int? = null) {
        val c = client ?: return
        viewModelScope.launch {
            c.windowAction(hwnd, action, width, height).onSuccess {
                val label = when (action) {
                    "mobile_fit" -> "Reflowed window to mobile layout"
                    "restore_fit" -> "Restored desktop window size"
                    "focus" -> "Focused window"
                    "close" -> "Sent close signal"
                    else -> "Updated window"
                }
                showBanner(label)
                delay(150)
                refreshWindows(withThumbnails = true)
                if (_uiState.value.activeTab == WorkspaceTab.LENS) {
                    sendStageConfig()
                    if (_uiState.value.stageXRayEnabled) inspectCurrentWindowUia()
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Window action failed")
            }
        }
    }

    fun openWindowInStage(hwnd: Long, mobileFitFirst: Boolean = false, openXRay: Boolean = false) {
        _uiState.update {
            it.copy(
                stageMode = "window",
                stageHwnd = hwnd,
                stageRoi = null,
                stageXRayEnabled = openXRay,
                showSemanticSheet = openXRay,
                activeTab = WorkspaceTab.LENS
            )
        }
        viewModelScope.launch {
            if (mobileFitFirst) {
                client?.windowAction(hwnd, "mobile_fit", width = 460, height = 840)
                delay(160)
            } else {
                client?.windowAction(hwnd, "focus")
            }
            startStageStream()
            if (openXRay) inspectCurrentWindowUia()
        }
    }

    fun launchApp(target: String, args: List<String> = emptyList(), cwd: String? = null) {
        val c = client ?: return
        viewModelScope.launch {
            c.launchTarget(target, args, cwd).onSuccess {
                showBanner("Launched $target")
                delay(650)
                refreshWindows(withThumbnails = true)
            }.onFailure { showBanner(it.message ?: "Launch failed") }
        }
    }

    fun refreshClipboard() {
        val c = client ?: return
        viewModelScope.launch {
            c.getClipboard().onSuccess { resp ->
                _uiState.update {
                    it.copy(windowsClipboardText = resp.text, clipboardHistory = resp.history)
                }
            }
        }
    }

    fun pushAndroidClipboardToWindows() {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(getApplication())?.toString().orEmpty()
        if (text.isBlank()) {
            showBanner("Phone clipboard is empty")
            return
        }
        pushTextToWindowsClipboard(text)
    }

    fun pushTextToWindowsClipboard(text: String) {
        val c = client ?: return
        viewModelScope.launch {
            c.setClipboard(text).onSuccess {
                _uiState.update { it.copy(windowsClipboardText = text) }
                showBanner("Synced to Windows clipboard")
                refreshClipboard()
            }
        }
    }

    fun copyToAndroidClipboard(text: String, silent: Boolean = false) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            runCatching {
                val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Kova", text))
            }
            if (!silent) showBanner("Copied to phone clipboard")
        }
    }

    // ---------- Lens Visual Stage & Semantic UIA ----------

    fun startStageStream() {
        val c = client ?: return
        stageWs?.close(1000, "reopen")
        stageWs = c.openStageSocket(
            onFrame = { frame ->
                _uiState.update { it.copy(stageBitmap = frame.bitmap, stageMeta = frame.meta) }
            },
            onWarning = { warn ->
                _uiState.update { it.copy(stageWarning = warn) }
            },
            onCursor = { cx, cy ->
                _uiState.update { it.copy(stageMeta = it.stageMeta.copy(cursorX = cx, cursorY = cy)) }
            },
            onClosed = { _ -> }
        )
        sendStageConfig()
    }

    fun stopStageStream() {
        stageWs?.close(1000, "leave_stage")
        stageWs = null
    }

    fun sendStageConfig() {
        val s = _uiState.value
        val prefs = s.preferences
        val payload = buildJsonObject {
            put("type", "config")
            put("mode", s.stageMode)
            if (s.stageHwnd != null) put("hwnd", s.stageHwnd)
            put("fps", prefs.streamFps)
            put("quality", prefs.streamQuality)
            put("format", "webp")
            put("max_width", prefs.streamMaxWidth)
            val roi = s.stageRoi
            if (roi != null) {
                put("roi", buildJsonObject {
                    put("x", roi.x)
                    put("y", roi.y)
                    put("w", roi.w)
                    put("h", roi.h)
                })
            } else {
                put("roi", null as String?)
            }
        }
        stageWs?.send(payload.toString())
    }

    fun setStageSource(mode: String, hwnd: Long? = null) {
        _uiState.update { it.copy(stageMode = mode, stageHwnd = hwnd, stageRoi = null) }
        sendStageConfig()
        if (_uiState.value.stageXRayEnabled) inspectCurrentWindowUia()
    }

    fun setStageRoi(roi: StageRoi?) {
        _uiState.update { it.copy(stageRoi = roi) }
        sendStageConfig()
    }

    fun setStageInputMode(mode: StageInputMode) {
        _uiState.update { it.copy(stageInputMode = mode) }
    }

    fun toggleStageDragLock() {
        val next = !_uiState.value.stageDragLocked
        _uiState.update { it.copy(stageDragLocked = next) }
        sendMouseButton("left", next)
    }

    fun toggleXRayOverlay() {
        val next = !_uiState.value.stageXRayEnabled
        _uiState.update { it.copy(stageXRayEnabled = next) }
        if (next) inspectCurrentWindowUia()
    }

    fun setSemanticSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showSemanticSheet = visible) }
        if (visible) inspectCurrentWindowUia()
    }

    fun setPromptComposerVisible(visible: Boolean) {
        _uiState.update { it.copy(showPromptComposer = visible) }
    }

    fun inspectCurrentWindowUia() {
        val c = client ?: return
        val hwnd = if (_uiState.value.stageMode == "window") _uiState.value.stageHwnd ?: 0L else 0L
        viewModelScope.launch {
            _uiState.update { it.copy(uiaLoading = true) }
            c.inspectUia(hwnd).onSuccess { resp ->
                _uiState.update { it.copy(uiaData = resp, uiaLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(uiaLoading = false) }
                showBanner(err.message ?: "UI inspection failed")
            }
        }
    }

    fun invokeUiaElement(element: UiaElement, action: String = "invoke", value: String = "") {
        val c = client ?: return
        val hwnd = _uiState.value.uiaData?.hwnd ?: _uiState.value.stageHwnd ?: 0L
        viewModelScope.launch {
            c.uiaAction(
                hwnd = hwnd,
                runtimeId = element.runtimeId,
                action = action,
                value = value,
                x = element.centerX,
                y = element.centerY
            ).onSuccess {
                showBanner(
                    if (action == "set_value") "Updated ${element.name.ifBlank { element.role }}"
                    else "Triggered ${element.name.ifBlank { element.role }}"
                )
                delay(220)
                inspectCurrentWindowUia()
            }.onFailure { err ->
                showBanner(err.message ?: "Control action failed")
            }
        }
    }

    fun sendMouseMoveAbs(x: Int, y: Int) {
        stageWs?.send(buildJsonObject {
            put("type", "mouse_move")
            put("x", x)
            put("y", y)
        }.toString())
    }

    fun sendMouseMoveRel(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        // Optimistic local cursor prediction for 120Hz visual smoothness on cellular networks
        _uiState.update { state ->
            val m = state.stageMeta
            val nx = (m.cursorX + dx).coerceIn(m.fullX, m.fullX + m.fullWidth.coerceAtLeast(1))
            val ny = (m.cursorY + dy).coerceIn(m.fullY, m.fullY + m.fullHeight.coerceAtLeast(1))
            state.copy(stageMeta = m.copy(cursorX = nx, cursorY = ny))
        }
        stageWs?.send(buildJsonObject {
            put("type", "mouse_rel")
            put("dx", dx)
            put("dy", dy)
        }.toString())
    }

    fun sendMouseButton(button: String, down: Boolean) {
        stageWs?.send(buildJsonObject {
            put("type", "mouse_button")
            put("button", button)
            put("down", down)
        }.toString())
    }

    fun sendMouseClick(x: Int? = null, y: Int? = null, button: String = "left", count: Int = 1) {
        stageWs?.send(buildJsonObject {
            put("type", "mouse_click")
            if (x != null) put("x", x)
            if (y != null) put("y", y)
            put("button", button)
            put("count", count)
        }.toString())
    }

    fun sendScroll(dx: Int = 0, dy: Int = 0) {
        stageWs?.send(buildJsonObject {
            put("type", "scroll")
            put("dx", dx)
            put("dy", dy)
        }.toString())
    }

    fun sendKeyChord(keys: List<String>) {
        if (stageWs != null) {
            stageWs?.send(buildJsonObject {
                put("type", "key")
                put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
            }.toString())
        } else {
            val c = client ?: return
            viewModelScope.launch {
                c.sendKeyChord(keys, hwnd = _uiState.value.stageHwnd)
            }
        }
    }

    fun dispatchPromptText(text: String, pressEnter: Boolean, mode: String = "paste") {
        val c = client ?: return
        if (text.isEmpty()) return
        val targetHwnd = if (_uiState.value.stageMode == "window") _uiState.value.stageHwnd else null
        viewModelScope.launch {
            c.dispatchText(text, pressEnter = pressEnter, mode = mode, hwnd = targetHwnd)
                .onSuccess { showBanner(if (pressEnter) "Dispatched & submitted" else "Injected text") }
                .onFailure { showBanner(it.message ?: "Text dispatch failed") }
        }
    }

    // ---------- Shell (ConPTY) ----------

    fun syncTerminals() {
        val c = client ?: return
        viewModelScope.launch {
            c.listTerminals().onSuccess { resp ->
                resp.terminals.forEach { attachTerminalSocket(it) }
                if (resp.terminals.isNotEmpty() && _uiState.value.activeTerminalId == null) {
                    _uiState.update { it.copy(activeTerminalId = resp.terminals.first().id) }
                }
            }
        }
    }

    fun createTerminal(shell: String = "powershell", cwd: String? = null, cols: Int = 68, rows: Int = 24) {
        val c = client ?: return
        viewModelScope.launch {
            c.createTerminal(shell = shell, cwd = cwd, cols = cols, rows = rows).onSuccess { resp ->
                val term = resp.terminal ?: return@onSuccess
                attachTerminalSocket(term)
                _uiState.update { it.copy(activeTerminalId = term.id, activeTab = WorkspaceTab.SHELL) }
            }.onFailure { err ->
                showBanner(err.message ?: "Could not start terminal")
            }
        }
    }

    private fun attachTerminalSocket(info: TerminalInfo) {
        val c = client ?: return
        val buf = terminalBuffers.getOrPut(info.id) { AnsiTerminalBuffer() }
        if (_uiState.value.terminals.none { it.info.id == info.id }) {
            _uiState.update { state ->
                state.copy(terminals = state.terminals + TerminalTabState(info = info, lines = buf.snapshotLines()))
            }
        }
        if (terminalSockets.containsKey(info.id)) return

        val ws = c.openTerminalSocket(
            terminalId = info.id,
            onOutput = { chunk, isReplay ->
                if (isReplay) buf.clear()
                buf.feed(chunk)
                val snap = buf.snapshotLines()
                val title = buf.windowTitle
                _uiState.update { state ->
                    state.copy(
                        terminals = state.terminals.map { tab ->
                            if (tab.info.id == info.id) {
                                tab.copy(lines = snap, titleOverride = title, connected = true)
                            } else tab
                        }
                    )
                }
            },
            onExit = { code ->
                _uiState.update { state ->
                    state.copy(
                        terminals = state.terminals.map { tab ->
                            if (tab.info.id == info.id) {
                                tab.copy(info = tab.info.copy(alive = false, exitCode = code), connected = false)
                            } else tab
                        }
                    )
                }
            },
            onClosed = {
                terminalSockets.remove(info.id)
                _uiState.update { state ->
                    state.copy(
                        terminals = state.terminals.map { tab ->
                            if (tab.info.id == info.id) tab.copy(connected = false) else tab
                        }
                    )
                }
            }
        )
        terminalSockets[info.id] = ws
    }

    fun selectTerminal(id: String) {
        _uiState.update { it.copy(activeTerminalId = id) }
    }

    fun sendTerminalInput(data: String) {
        val id = _uiState.value.activeTerminalId ?: return
        terminalSockets[id]?.send(buildJsonObject {
            put("type", "input")
            put("data", data)
        }.toString())
    }

    fun resizeActiveTerminal(cols: Int, rows: Int) {
        val id = _uiState.value.activeTerminalId ?: return
        terminalSockets[id]?.send(buildJsonObject {
            put("type", "resize")
            put("cols", cols.coerceIn(24, 240))
            put("rows", rows.coerceIn(6, 120))
        }.toString())
    }

    fun closeTerminal(id: String) {
        terminalSockets.remove(id)?.close(1000, "closed")
        terminalBuffers.remove(id)
        viewModelScope.launch { client?.deleteTerminal(id) }
        _uiState.update { state ->
            val remaining = state.terminals.filterNot { it.info.id == id }
            state.copy(
                terminals = remaining,
                activeTerminalId = if (state.activeTerminalId == id) remaining.firstOrNull()?.info?.id else state.activeTerminalId
            )
        }
    }

    fun copyActiveTerminalText() {
        val id = _uiState.value.activeTerminalId ?: return
        val text = terminalBuffers[id]?.plainText().orEmpty()
        copyToAndroidClipboard(text)
    }

    // ---------- Forge (Files, Code Editor & Git) ----------

    fun loadInitialFiles() {
        val c = client ?: return
        viewModelScope.launch {
            c.getFileRoots().onSuccess { resp ->
                _uiState.update { it.copy(fileRoots = resp.roots) }
            }
            browsePath(null)
        }
    }

    fun browsePath(path: String?) {
        val c = client ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(filesLoading = true, searchResults = null, searchQuery = "") }
            c.listFiles(path, _uiState.value.showHiddenFiles).onSuccess { resp ->
                _uiState.update {
                    it.copy(
                        currentPath = resp.path,
                        parentPath = resp.parent,
                        currentGitRoot = resp.gitRoot,
                        fileEntries = resp.entries,
                        filesLoading = false
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(filesLoading = false) }
                showBanner(err.message ?: "Unable to list folder")
            }
        }
    }

    fun toggleHiddenFiles() {
        val next = !_uiState.value.showHiddenFiles
        _uiState.update { it.copy(showHiddenFiles = next) }
        browsePath(_uiState.value.currentPath.ifBlank { null })
    }

    fun searchCurrentFolder(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null) }
            return
        }
        val c = client ?: return
        val root = _uiState.value.currentPath
        viewModelScope.launch {
            c.searchFiles(root, query).onSuccess { resp ->
                _uiState.update { it.copy(searchResults = resp.matches) }
            }
        }
    }

    fun createNewFolder(name: String) {
        val c = client ?: return
        val base = _uiState.value.currentPath.trimEnd('\\', '/')
        val full = "$base\\${name.trim()}"
        viewModelScope.launch {
            c.makeDirectory(full).onSuccess {
                showBanner("Created folder $name")
                browsePath(_uiState.value.currentPath)
            }.onFailure { showBanner(it.message ?: "Create folder failed") }
        }
    }

    fun createNewFile(name: String) {
        val c = client ?: return
        val base = _uiState.value.currentPath.trimEnd('\\', '/')
        val full = "$base\\${name.trim()}"
        viewModelScope.launch {
            c.writeFile(full, "").onSuccess {
                browsePath(_uiState.value.currentPath)
                openFileInEditor(full)
            }.onFailure { showBanner(it.message ?: "Create file failed") }
        }
    }

    fun renameEntry(entry: FileEntry, newName: String) {
        val c = client ?: return
        val parent = entry.path.substringBeforeLast('\\', _uiState.value.currentPath)
        val dst = "$parent\\${newName.trim()}"
        viewModelScope.launch {
            c.renameFile(entry.path, dst).onSuccess {
                showBanner("Renamed to $newName")
                browsePath(_uiState.value.currentPath)
            }.onFailure { showBanner(it.message ?: "Rename failed") }
        }
    }

    fun deleteEntry(entry: FileEntry) {
        val c = client ?: return
        viewModelScope.launch {
            c.deleteFile(entry.path).onSuccess {
                showBanner("Deleted ${entry.name}")
                browsePath(_uiState.value.currentPath)
            }.onFailure { showBanner(it.message ?: "Delete failed") }
        }
    }

    fun uploadBytesToCurrentDir(fileName: String, bytes: ByteArray) {
        val c = client ?: return
        val dir = _uiState.value.currentPath.ifBlank { return }
        viewModelScope.launch {
            showBanner("Uploading $fileName...")
            c.uploadFile(dir, fileName, bytes).onSuccess {
                showBanner("Uploaded $fileName")
                browsePath(dir)
            }.onFailure { showBanner(it.message ?: "Upload failed") }
        }
    }

    fun downloadFileBytes(path: String, onDone: (ByteArray) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            c.downloadFile(path).onSuccess { onDone(it) }
                .onFailure { showBanner(it.message ?: "Download failed") }
        }
    }

    fun openFileInEditor(path: String) {
        val c = client ?: return
        logTailJob?.cancel()
        viewModelScope.launch {
            c.readFile(path).onSuccess { resp: FileReadResponse ->
                _uiState.update {
                    it.copy(
                        editor = OpenEditorState(
                            path = resp.path,
                            name = resp.name,
                            language = resp.language,
                            content = resp.content,
                            savedContent = resp.content,
                            isBinary = resp.isBinary,
                            truncated = resp.truncated
                        )
                    )
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Unable to read file")
            }
        }
    }

    fun updateEditorContent(newContent: String) {
        _uiState.update { state ->
            state.copy(editor = state.editor?.copy(content = newContent))
        }
    }

    fun saveEditorFile() {
        val c = client ?: return
        val ed = _uiState.value.editor ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(editor = ed.copy(saving = true)) }
            c.writeFile(ed.path, ed.content).onSuccess {
                _uiState.update {
                    it.copy(editor = it.editor?.copy(savedContent = ed.content, saving = false))
                }
                showBanner("Saved ${ed.name}")
            }.onFailure { err ->
                _uiState.update { it.copy(editor = it.editor?.copy(saving = false)) }
                showBanner(err.message ?: "Save failed")
            }
        }
    }

    fun toggleEditorLiveTail() {
        val ed = _uiState.value.editor ?: return
        val next = !ed.liveTail
        _uiState.update { it.copy(editor = ed.copy(liveTail = next)) }
        logTailJob?.cancel()
        if (next) {
            logTailJob = viewModelScope.launch {
                while (isActive) {
                    client?.readFile(ed.path, tailLines = 250)?.onSuccess { resp ->
                        _uiState.update { state ->
                            val cur = state.editor ?: return@update state
                            if (cur.path == ed.path && cur.liveTail) {
                                state.copy(editor = cur.copy(content = resp.content, savedContent = resp.content))
                            } else state
                        }
                    }
                    delay(1800)
                }
            }
        }
    }

    fun closeEditor() {
        logTailJob?.cancel()
        _uiState.update { it.copy(editor = null) }
    }

    // ---------- Git Bridge ----------

    fun openGitPanel() {
        _uiState.update { it.copy(showGitSheet = true) }
        refreshGitStatus()
    }

    fun closeGitPanel() {
        _uiState.update { it.copy(showGitSheet = false, gitDiffText = "", gitDiffFile = null) }
    }

    fun refreshGitStatus() {
        val c = client ?: return
        val path = _uiState.value.currentGitRoot ?: _uiState.value.currentPath
        if (path.isBlank()) return
        viewModelScope.launch {
            c.gitStatus(path).onSuccess { resp ->
                _uiState.update { it.copy(gitStatus = resp) }
            }
        }
    }

    fun loadGitDiff(fileRel: String?, staged: Boolean = false) {
        val c = client ?: return
        val path = _uiState.value.currentGitRoot ?: _uiState.value.currentPath
        viewModelScope.launch {
            c.gitDiff(path, fileRel, staged).onSuccess { resp ->
                _uiState.update { it.copy(gitDiffText = resp.diff, gitDiffFile = fileRel ?: "All changes") }
            }
        }
    }

    fun runGitAction(action: String, message: String = "", files: List<String>? = null) {
        val c = client ?: return
        val path = _uiState.value.currentGitRoot ?: _uiState.value.currentPath
        viewModelScope.launch {
            c.gitAction(path, action, message, files).onSuccess { resp ->
                val note = when {
                    resp.ok -> "Git $action succeeded"
                    resp.stderr.isNotBlank() -> resp.stderr.take(120)
                    else -> resp.error.ifBlank { "Git $action failed" }
                }
                showBanner(note)
                refreshGitStatus()
            }.onFailure { showBanner(it.message ?: "Git error") }
        }
    }

    // ---------- Settings & Snippets ----------

    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        val next = transform(_uiState.value.preferences)
        store.savePreferences(next)
        _uiState.update { it.copy(preferences = next) }
        if (_uiState.value.activeTab == WorkspaceTab.LENS) sendStageConfig()
    }

    fun addSnippet(label: String, command: String) {
        val cmd = if (command.endsWith("\r") || command.endsWith("\n")) command else "$command\r"
        val updated = _uiState.value.snippets + CommandSnippet(label = label.trim(), command = cmd)
        store.saveSnippets(updated)
        _uiState.update { it.copy(snippets = updated) }
    }

    fun deleteSnippet(id: String) {
        val updated = _uiState.value.snippets.filterNot { it.id == id }
        store.saveSnippets(updated)
        _uiState.update { it.copy(snippets = updated) }
    }

    override fun onCleared() {
        disconnectInternal(keepProfile = false)
        super.onCleared()
    }
}
