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
import com.kova.workstation.data.model.ClosedWindowRecord
import com.kova.workstation.data.model.CommandSnippet
import com.kova.workstation.data.model.FileEntry
import com.kova.workstation.data.model.FileReadResponse
import com.kova.workstation.data.model.FileRoot
import com.kova.workstation.data.model.FocusedInputHint
import com.kova.workstation.data.model.GitStatusResponse
import com.kova.workstation.data.model.StageFrameMeta
import com.kova.workstation.data.model.SystemInfo
import com.kova.workstation.data.model.TerminalInfo
import com.kova.workstation.data.model.UiaElement
import com.kova.workstation.data.model.UiaInspectResponse
import com.kova.workstation.data.model.WindowInfo
import com.kova.workstation.data.model.WindowStateNotice
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

enum class WorkspaceTab { STAGE, SHELL, FORGE, SETTINGS }

enum class StageInputMode(val id: String, val label: String) {
    TOUCH("TOUCH", "Touch"),
    TRACKPAD("TRACKPAD", "Trackpad"),
    LOUPE("LOUPE", "Loupe");

    companion object {
        fun fromId(id: String?): StageInputMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TOUCH
    }
}

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
    val activeTab: WorkspaceTab = WorkspaceTab.STAGE,
    val preferences: AppPreferences = AppPreferences(),
    val snippets: List<CommandSnippet> = emptyList(),

    // Workspace Switcher & Telemetry
    val systemInfo: SystemInfo? = null,
    val windows: List<WindowInfo> = emptyList(),
    val windowThumbnails: Map<Long, Bitmap> = emptyMap(),
    val recentlyClosedWindows: List<ClosedWindowRecord> = emptyList(),
    val windowsLoading: Boolean = false,
    val windowsClipboardText: String = "",
    val clipboardHistory: List<ClipboardEntry> = emptyList(),
    val showWorkspaceSwitcher: Boolean = false,

    // Immersive Visual Stage & Semantic UI Automation
    val stageMode: String = "desktop", // "desktop" or "window"
    val stageHwnd: Long? = null,
    val stageBitmap: Bitmap? = null,
    val stageMeta: StageFrameMeta = StageFrameMeta(),
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val stageWarning: String? = null,
    val windowStateNotice: WindowStateNotice? = null,
    val focusedInputHint: FocusedInputHint? = null,
    val stageInputMode: StageInputMode = StageInputMode.TOUCH,
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
    private val initialPrefs = store.loadPreferences()
    private val _uiState = MutableStateFlow(
        WorkstationUiState(
            profiles = store.loadProfiles(),
            preferences = initialPrefs,
            stageInputMode = StageInputMode.fromId(initialPrefs.defaultInputMode),
            snippets = store.loadSnippets()
        )
    )
    val uiState: StateFlow<WorkstationUiState> = _uiState.asStateFlow()

    private var client: KovaClient? = null
    private var eventsWs: WebSocket? = null
    private var stageWs: WebSocket? = null
    private val terminalSockets = mutableMapOf<String, WebSocket>()
    private val terminalBuffers = mutableMapOf<String, AnsiTerminalBuffer>()

    // Monotonic cursor ownership (prevents delayed server echoes from fighting live local finger drags)
    @Volatile
    private var lastSentPointerSeq: Long = 0L
    @Volatile
    private var lastLocalPointerMs: Long = 0L

    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var logTailJob: Job? = null
    private var bannerJob: Job? = null
    private var focusedHintJob: Job? = null

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
                val prefs = _uiState.value.preferences
                _uiState.update {
                    it.copy(
                        profiles = updatedProfiles,
                        activeProfile = saved,
                        systemInfo = info,
                        connected = true,
                        connecting = false,
                        connectionError = null,
                        activeTab = WorkspaceTab.STAGE,
                        stageInputMode = StageInputMode.fromId(prefs.defaultInputMode)
                    )
                }
                if (prefs.keepAliveService) {
                    runCatching {
                        WorkstationService.start(
                            getApplication(),
                            saved.name,
                            "${saved.host}:${saved.port}"
                        )
                    }
                }
                startEventsSocket(newClient)
                startStageStream()
                refreshWindows(withThumbnails = false)
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
                        "Could not reach ${profile.host}:${profile.port}. Ensure KovaHost.exe is running and reachable (or use Cloudflare Tunnel / Tailscale)."
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

    fun reconnectNow() {
        val profile = _uiState.value.activeProfile ?: return
        showBanner("Reconnecting to ${profile.name}...")
        connect(profile)
    }

    fun pingNow() {
        val payload = buildJsonObject {
            put("type", "ping")
            put("ts", System.currentTimeMillis())
        }.toString()
        val sent = eventsWs?.send(payload) ?: false
        if (!sent) {
            showBanner("Control channel offline — tap Reconnect")
        } else {
            showBanner("Ping sent...")
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
        focusedHintJob?.cancel()
        eventsWs?.close(1000, "disconnect")
        eventsWs = null
        stageWs?.close(1000, "disconnect")
        stageWs = null
        terminalSockets.values.forEach { it.close(1000, "disconnect") }
        terminalSockets.clear()
        terminalBuffers.clear()
        client?.shutdown()
        client = null
        lastSentPointerSeq = 0L
        lastLocalPointerMs = 0L

        _uiState.update {
            it.copy(
                activeProfile = if (keepProfile) it.activeProfile else null,
                connected = false,
                connecting = false,
                latencyMs = null,
                stageBitmap = null,
                windowStateNotice = null,
                focusedInputHint = null,
                showWorkspaceSwitcher = false,
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
                if (isOpen) {
                    eventsWs?.send(buildJsonObject {
                        put("type", "ping")
                        put("ts", System.currentTimeMillis())
                    }.toString())
                }
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
                _uiState.value.activeProfile ?: break
                delay(delayMs)
                val c = client ?: break
                val check = c.getSystemInfo()
                if (check.isSuccess) {
                    _uiState.update { it.copy(systemInfo = check.getOrNull(), connected = true, connectionError = null) }
                    startEventsSocket(c)
                    if (_uiState.value.activeTab == WorkspaceTab.STAGE) startStageStream()
                    break
                }
                delayMs = (delayMs * 2).coerceAtMost(15000L)
            }
        }
    }

    fun selectTab(tab: WorkspaceTab) {
        val prev = _uiState.value.activeTab
        _uiState.update { it.copy(activeTab = tab, showWorkspaceSwitcher = false) }
        if (tab == WorkspaceTab.STAGE) {
            if (stageWs == null) startStageStream() else sendStageConfig()
            refreshWindows(withThumbnails = false)
        } else if (prev == WorkspaceTab.STAGE) {
            stopStageStream()
        }
        if (tab == WorkspaceTab.SHELL && _uiState.value.terminals.isEmpty()) {
            createTerminal("powershell")
        } else if (tab == WorkspaceTab.FORGE && _uiState.value.fileEntries.isEmpty()) {
            loadInitialFiles()
        }
    }

    // ---------- Workspace Switcher, Windows & Clipboard ----------

    fun setWorkspaceSwitcherVisible(visible: Boolean) {
        _uiState.update { it.copy(showWorkspaceSwitcher = visible) }
        if (visible) {
            refreshWindows(withThumbnails = true)
            refreshClipboard()
        }
    }

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
        val targetWin = _uiState.value.windows.firstOrNull { it.hwnd == hwnd }
        viewModelScope.launch {
            c.windowAction(hwnd, action, width, height).onSuccess {
                if (action == "close" && targetWin != null && targetWin.process.isNotBlank()) {
                    _uiState.update { state ->
                        val rec = ClosedWindowRecord(title = targetWin.title, process = targetWin.process)
                        val dedup = listOf(rec) + state.recentlyClosedWindows.filterNot {
                            it.process.equals(targetWin.process, ignoreCase = true)
                        }
                        state.copy(recentlyClosedWindows = dedup.take(6))
                    }
                }
                val label = when (action) {
                    "mobile_fit" -> "Reflowed window to mobile layout"
                    "restore_fit" -> "Restored desktop window size"
                    "focus" -> "Focused window"
                    "maximize" -> "Maximized window"
                    "minimize" -> "Minimized window"
                    "restore" -> "Restored window"
                    "close" -> "Closed ${targetWin?.title?.take(24) ?: "window"}"
                    else -> "Updated window"
                }
                showBanner(label)
                delay(180)
                refreshWindows(withThumbnails = _uiState.value.showWorkspaceSwitcher)
                if (_uiState.value.activeTab == WorkspaceTab.STAGE) {
                    if (action == "restore" || action == "focus") {
                        _uiState.update { it.copy(windowStateNotice = null) }
                    }
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
                windowStateNotice = null,
                focusedInputHint = null,
                stageXRayEnabled = openXRay,
                showSemanticSheet = openXRay,
                showWorkspaceSwitcher = false,
                activeTab = WorkspaceTab.STAGE
            )
        }
        viewModelScope.launch {
            if (mobileFitFirst) {
                client?.windowAction(hwnd, "mobile_fit", width = 460, height = 840)
                delay(160)
            } else {
                client?.windowAction(hwnd, "focus")
            }
            if (stageWs == null) startStageStream() else sendStageConfig()
            refreshWindows(withThumbnails = false)
            if (openXRay) inspectCurrentWindowUia()
        }
    }

    fun switchAdjacentWindow(delta: Int) {
        val visible = _uiState.value.windows.filterNot { it.minimized }
        if (visible.isEmpty()) {
            setStageSource("desktop", null)
            return
        }
        // Cycle: [Desktop] -> win0 -> win1 -> ... -> [Desktop]
        val curIdx = if (_uiState.value.stageMode == "desktop") {
            -1
        } else {
            visible.indexOfFirst { it.hwnd == _uiState.value.stageHwnd }
        }
        val totalSlots = visible.size + 1
        val normalizedCur = curIdx + 1 // 0 = desktop, 1..N = windows
        val nextSlot = (normalizedCur + delta + totalSlots) % totalSlots
        if (nextSlot == 0) {
            setStageSource("desktop", null)
            showBanner("Full Desktop")
        } else {
            val target = visible[nextSlot - 1]
            openWindowInStage(target.hwnd, mobileFitFirst = false, openXRay = false)
        }
    }

    fun launchApp(target: String, args: List<String> = emptyList(), cwd: String? = null, stageAfterLaunch: Boolean = false) {
        val c = client ?: return
        viewModelScope.launch {
            c.launchTarget(target, args, cwd).onSuccess {
                showBanner("Launched $target")
                if (stageAfterLaunch) {
                    _uiState.update {
                        it.copy(
                            showWorkspaceSwitcher = false,
                            activeTab = WorkspaceTab.STAGE
                        )
                    }
                    if (stageWs == null) startStageStream()
                }
                delay(750)
                refreshWindows(withThumbnails = _uiState.value.showWorkspaceSwitcher)
            }.onFailure { showBanner(it.message ?: "Launch failed") }
        }
    }

    fun reopenClosedWindow(record: ClosedWindowRecord) {
        _uiState.update { state ->
            state.copy(recentlyClosedWindows = state.recentlyClosedWindows.filterNot { it == record })
        }
        launchApp(record.process, stageAfterLaunch = true)
    }

    fun openRemoteWindowsSettings() {
        launchApp("ms-settings:", stageAfterLaunch = true)
        setStageSource("desktop", null)
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

    // ---------- Immersive Visual Stage & Precision Pointer Pipeline ----------

    fun startStageStream() {
        val c = client ?: return
        stageWs?.close(1000, "reopen")
        stageWs = c.openStageSocket(
            onFrame = { frame ->
                val now = System.currentTimeMillis()
                val acceptHostCursor = (now - lastLocalPointerMs) > 280L
                _uiState.update { state ->
                    state.copy(
                        stageBitmap = frame.bitmap,
                        stageMeta = frame.meta,
                        cursorX = if (acceptHostCursor) frame.meta.cursorX else state.cursorX,
                        cursorY = if (acceptHostCursor) frame.meta.cursorY else state.cursorY,
                        windowStateNotice = null
                    )
                }
            },
            onWarning = { warn ->
                _uiState.update { it.copy(stageWarning = warn) }
            },
            onCursorAck = { cx, cy, ackSeq ->
                // Ignore stale ACKs from earlier packets so local cursor NEVER jumps backward!
                if (ackSeq >= lastSentPointerSeq) {
                    _uiState.update { it.copy(cursorX = cx, cursorY = cy) }
                }
            },
            onWindowState = { st, hwnd, message ->
                if (st == "ok") {
                    _uiState.update { it.copy(windowStateNotice = null) }
                } else {
                    _uiState.update {
                        it.copy(windowStateNotice = WindowStateNotice(state = st, hwnd = hwnd, message = message))
                    }
                    refreshWindows(withThumbnails = false)
                }
            },
            onFocusedInput = { editable, role, name ->
                if (editable && _uiState.value.preferences.autoPromptOnTextInput) {
                    val hint = FocusedInputHint(editable = true, role = role, name = name)
                    _uiState.update { it.copy(focusedInputHint = hint) }
                    focusedHintJob?.cancel()
                    focusedHintJob = viewModelScope.launch {
                        delay(8000)
                        _uiState.update {
                            if (it.focusedInputHint?.timestampMs == hint.timestampMs) it.copy(focusedInputHint = null) else it
                        }
                    }
                } else if (!editable) {
                    _uiState.update { it.copy(focusedInputHint = null) }
                }
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
        val ws = stageWs ?: return
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
        ws.send(payload.toString())
    }

    fun setStageSource(mode: String, hwnd: Long? = null) {
        _uiState.update {
            it.copy(
                stageMode = mode,
                stageHwnd = hwnd,
                stageRoi = null,
                windowStateNotice = null,
                focusedInputHint = null,
                showWorkspaceSwitcher = false
            )
        }
        if (mode == "window" && hwnd != null) {
            viewModelScope.launch {
                client?.windowAction(hwnd, "focus")
                sendStageConfig()
            }
        } else {
            sendStageConfig()
        }
        if (_uiState.value.stageXRayEnabled) inspectCurrentWindowUia()
    }

    fun setStageRoi(roi: StageRoi?) {
        _uiState.update { it.copy(stageRoi = roi) }
        sendStageConfig()
    }

    fun setStageInputMode(mode: StageInputMode) {
        _uiState.update { it.copy(stageInputMode = mode, focusedInputHint = null) }
    }

    fun dismissFocusedInputHint() {
        focusedHintJob?.cancel()
        _uiState.update { it.copy(focusedInputHint = null) }
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
        _uiState.update { it.copy(showPromptComposer = visible, focusedInputHint = if (visible) null else it.focusedInputHint) }
    }

    fun activateLiveDisplay() {
        val c = client ?: return
        viewModelScope.launch {
            showBanner("Attaching Windows console display...")
            c.activateDisplay().onSuccess { resp ->
                if (resp.ok) {
                    showBanner("Display attached! Refreshing stream...")
                    delay(400)
                    refreshWindows(withThumbnails = false)
                    sendStageConfig()
                } else {
                    showBanner(resp.error.ifBlank { "Could not attach console display" })
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Activate display failed")
            }
        }
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

    /** Sequence-numbered absolute move: zero OS mouse acceleration, zero snap-back. */
    fun sendPointerMoveAbs(x: Int, y: Int, seq: Long? = null) {
        val nextSeq = seq ?: (lastSentPointerSeq + 1L)
        if (nextSeq > lastSentPointerSeq) lastSentPointerSeq = nextSeq
        lastLocalPointerMs = System.currentTimeMillis()
        _uiState.update { it.copy(cursorX = x, cursorY = y) }
        stageWs?.send(buildJsonObject {
            put("type", "mouse_move")
            put("x", x)
            put("y", y)
            put("seq", lastSentPointerSeq)
        }.toString())
    }

    /** Press mouse button down at exact physical coordinates (used by Touch hold-to-drag). */
    fun sendPointerDown(x: Int, y: Int, button: String = "left") {
        lastSentPointerSeq += 1L
        lastLocalPointerMs = System.currentTimeMillis()
        _uiState.update { it.copy(cursorX = x, cursorY = y) }
        stageWs?.send(buildJsonObject {
            put("type", "pointer_down")
            put("x", x)
            put("y", y)
            put("button", button)
            put("seq", lastSentPointerSeq)
        }.toString())
    }

    /** Release mouse button at exact physical coordinates (ends Touch hold-to-drag cleanly). */
    fun sendPointerUp(x: Int, y: Int, button: String = "left") {
        lastSentPointerSeq += 1L
        lastLocalPointerMs = System.currentTimeMillis()
        _uiState.update { it.copy(cursorX = x, cursorY = y) }
        stageWs?.send(buildJsonObject {
            put("type", "pointer_up")
            put("x", x)
            put("y", y)
            put("button", button)
            put("seq", lastSentPointerSeq)
        }.toString())
    }

    fun sendMouseButton(button: String, down: Boolean) {
        stageWs?.send(buildJsonObject {
            put("type", "mouse_button")
            put("button", button)
            put("down", down)
        }.toString())
    }

    fun sendMouseClick(
        x: Int? = null,
        y: Int? = null,
        button: String = "left",
        count: Int = 1,
        checkInput: Boolean = false
    ) {
        lastSentPointerSeq += 1L
        lastLocalPointerMs = System.currentTimeMillis()
        if (x != null && y != null) {
            _uiState.update { it.copy(cursorX = x, cursorY = y) }
        }
        stageWs?.send(buildJsonObject {
            put("type", "mouse_click")
            if (x != null) put("x", x)
            if (y != null) put("y", y)
            put("button", button)
            put("count", count)
            put("seq", lastSentPointerSeq)
            if (checkInput) put("check_input", true)
        }.toString())
    }

    fun sendScroll(dx: Int = 0, dy: Int = 0, x: Int? = null, y: Int? = null) {
        stageWs?.send(buildJsonObject {
            put("type", "scroll")
            put("dx", dx)
            put("dy", dy)
            if (x != null) put("x", x)
            if (y != null) put("y", y)
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
                stopStageStream()
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

    // ---------- Real, Live-Wired Settings & Snippets ----------

    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        val prev = _uiState.value.preferences
        val next = transform(prev)
        store.savePreferences(next)
        _uiState.update { state ->
            state.copy(
                preferences = next,
                stageInputMode = if (next.defaultInputMode != prev.defaultInputMode) {
                    StageInputMode.fromId(next.defaultInputMode)
                } else state.stageInputMode
            )
        }

        // Apply Keep-Alive Foreground Service LIVE when toggled!
        if (next.keepAliveService != prev.keepAliveService) {
            val profile = _uiState.value.activeProfile
            if (next.keepAliveService && profile != null && _uiState.value.connected) {
                runCatching {
                    WorkstationService.start(
                        getApplication(),
                        profile.name,
                        "${profile.host}:${profile.port}"
                    )
                }
                showBanner("Background keep-alive service enabled")
            } else if (!next.keepAliveService) {
                runCatching { WorkstationService.stop(getApplication()) }
                showBanner("Background keep-alive service stopped")
            }
        }

        // Push stream parameter changes immediately if stage socket is open
        if (stageWs != null && (
                next.streamFps != prev.streamFps ||
                next.streamQuality != prev.streamQuality ||
                next.streamMaxWidth != prev.streamMaxWidth
            )
        ) {
            sendStageConfig()
        }
    }

    fun resetPreferencesToDefaults() {
        updatePreferences { AppPreferences() }
        showBanner("Restored default Kova settings")
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
