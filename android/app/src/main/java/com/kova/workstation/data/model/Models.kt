package com.kova.workstation.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class WorkstationProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Windows VPS",
    val host: String = "",
    val port: Int = 8765,
    val token: String = "",
    val certFingerprint: String = "",
    val tlsEnabled: Boolean = true,
    val lastConnectedMs: Long = 0L
) {
    val baseHttpUrl: String
        get() = "${if (tlsEnabled) "https" else "http"}://$host:$port"

    val baseWsUrl: String
        get() = "${if (tlsEnabled) "wss" else "ws"}://$host:$port"
}

@Serializable
data class AppPreferences(
    val streamFps: Int = 24,
    val streamQuality: Int = 75,
    val streamMaxWidth: Int = 960,
    val streamFormat: String = "jpeg",
    val stageFillScreen: Boolean = false,
    val defaultInputMode: String = "TOUCH",
    val pointerCurve: String = "BALANCED",
    val touchpadSensitivity: Float = 1.35f,
    val naturalScroll: Boolean = true,
    val scrollSensitivity: Float = 1.0f,
    val longPressMs: Int = 380,
    val autoPromptOnTextInput: Boolean = true,
    val showCursorReticle: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val autoClipboardSync: Boolean = true,
    val keepAliveService: Boolean = true,
    val terminalFontSp: Float = 12.5f,
    val editorFontSp: Float = 13f,
    val darkTheme: Boolean = true
)

data class FocusedInputHint(
    val editable: Boolean,
    val role: String,
    val name: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class WindowStateNotice(
    val state: String, // "minimized" or "closed"
    val hwnd: Long,
    val message: String
)

data class ClosedWindowRecord(
    val title: String,
    val process: String,
    val closedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class CommandSnippet(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val command: String
)

@Serializable
data class MemoryInfo(
    val percent: Double = 0.0,
    @SerialName("used_bytes") val usedBytes: Long = 0L,
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("available_bytes") val availableBytes: Long = 0L
)

@Serializable
data class DiskInfo(
    val drive: String = "C:\\",
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("used_bytes") val usedBytes: Long = 0L,
    @SerialName("free_bytes") val freeBytes: Long = 0L,
    val percent: Double = 0.0
)

@Serializable
data class ScreenInfo(
    @SerialName("primary_width") val primaryWidth: Int = 1920,
    @SerialName("primary_height") val primaryHeight: Int = 1080,
    @SerialName("virtual_x") val virtualX: Int = 0,
    @SerialName("virtual_y") val virtualY: Int = 0,
    @SerialName("virtual_width") val virtualWidth: Int = 1920,
    @SerialName("virtual_height") val virtualHeight: Int = 1080
)

@Serializable
data class SessionInfo(
    @SerialName("session_id") val sessionId: Int = 0,
    @SerialName("console_session_id") val consoleSessionId: Int = 0,
    @SerialName("is_rdp") val isRdp: Boolean = false,
    val state: String = "unknown",
    @SerialName("interactive_gui_ready") val interactiveGuiReady: Boolean = true
)

@Serializable
data class QuickLaunchItem(
    val id: String = "",
    val label: String = "",
    val target: String = "",
    val args: List<String> = emptyList(),
    val icon: String = "apps",
    val category: String = "app"
)

@Serializable
data class SystemInfo(
    val hostname: String = "",
    val username: String = "",
    val os: String = "",
    val arch: String = "",
    val name: String = "",
    val version: String = "",
    @SerialName("cpu_count") val cpuCount: Int = 1,
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    val memory: MemoryInfo = MemoryInfo(),
    val disks: List<DiskInfo> = emptyList(),
    val screen: ScreenInfo = ScreenInfo(),
    val session: SessionInfo = SessionInfo(),
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0L,
    val ips: List<String> = emptyList(),
    @SerialName("quick_launch") val quickLaunch: List<QuickLaunchItem> = emptyList()
)

@Serializable
data class WindowInfo(
    val hwnd: Long,
    val title: String,
    val process: String = "",
    val pid: Int = 0,
    @SerialName("class_name") val className: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val minimized: Boolean = false,
    val maximized: Boolean = false,
    val focused: Boolean = false,
    @SerialName("mobile_fit") val mobileFit: Boolean = false
)

@Serializable
data class WindowsListResponse(
    val ok: Boolean = true,
    val windows: List<WindowInfo> = emptyList()
)

@Serializable
data class UiaRect(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class UiaElement(
    val id: Int = 0,
    @SerialName("runtime_id") val runtimeId: String = "",
    val role: String = "",
    val name: String = "",
    val value: String = "",
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val focusable: Boolean = false,
    val actions: List<String> = emptyList(),
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("rel_x") val relX: Float = 0f,
    @SerialName("rel_y") val relY: Float = 0f,
    @SerialName("rel_w") val relW: Float = 0f,
    @SerialName("rel_h") val relH: Float = 0f
) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    val isEditable: Boolean
        get() = role.equals("Edit", ignoreCase = true) ||
            role.equals("Document", ignoreCase = true) ||
            actions.contains("set_value")
}

@Serializable
data class UiaInspectResponse(
    val ok: Boolean = false,
    val hwnd: Long = 0L,
    @SerialName("window_title") val windowTitle: String = "",
    @SerialName("window_rect") val windowRect: UiaRect = UiaRect(),
    val elements: List<UiaElement> = emptyList(),
    @SerialName("readable_text") val readableText: List<String> = emptyList(),
    @SerialName("elapsed_ms") val elapsedMs: Long = 0L,
    val error: String = ""
)

@Serializable
data class ClipboardEntry(
    val text: String = "",
    val length: Int = 0,
    val source: String = "windows",
    val timestamp: Double = 0.0
)

@Serializable
data class ClipboardResponse(
    val ok: Boolean = true,
    val text: String = "",
    val history: List<ClipboardEntry> = emptyList()
)

@Serializable
data class TerminalInfo(
    val id: String,
    val title: String = "PowerShell",
    val shell: String = "powershell",
    val cwd: String = "",
    val cols: Int = 80,
    val rows: Int = 24,
    val pid: Int = 0,
    val alive: Boolean = true,
    @SerialName("exit_code") val exitCode: Int? = null
)

@Serializable
data class TerminalsListResponse(
    val ok: Boolean = true,
    val terminals: List<TerminalInfo> = emptyList()
)

@Serializable
data class TerminalCreateResponse(
    val ok: Boolean = true,
    val terminal: TerminalInfo? = null,
    val error: String = ""
)

@Serializable
data class FileRoot(
    val label: String,
    val path: String,
    val icon: String = "folder",
    val kind: String = "bookmark"
)

@Serializable
data class FileRootsResponse(
    val ok: Boolean = true,
    val roots: List<FileRoot> = emptyList()
)

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    @SerialName("is_dir") val isDir: Boolean = false,
    val size: Long = 0L,
    val modified: Double = 0.0,
    val ext: String = "",
    val language: String = "text"
)

@Serializable
data class FileListResponse(
    val ok: Boolean = true,
    val path: String = "",
    val parent: String? = null,
    @SerialName("git_root") val gitRoot: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val error: String = ""
)

@Serializable
data class FileReadResponse(
    val ok: Boolean = true,
    val path: String = "",
    val name: String = "",
    val size: Long = 0L,
    val modified: Double = 0.0,
    @SerialName("is_binary") val isBinary: Boolean = false,
    val truncated: Boolean = false,
    val language: String = "text",
    val content: String = "",
    @SerialName("line_count") val lineCount: Int = 0,
    val error: String = ""
)

@Serializable
data class FileSearchResponse(
    val ok: Boolean = true,
    val matches: List<FileEntry> = emptyList()
)

@Serializable
data class GitFileStatus(
    val path: String,
    @SerialName("full_path") val fullPath: String = "",
    @SerialName("index_status") val indexStatus: String = " ",
    @SerialName("worktree_status") val worktreeStatus: String = " ",
    val staged: Boolean = false,
    val untracked: Boolean = false
)

@Serializable
data class GitStatusResponse(
    @SerialName("is_repo") val isRepo: Boolean = false,
    @SerialName("git_available") val gitAvailable: Boolean = true,
    val root: String = "",
    val branch: String = "",
    val files: List<GitFileStatus> = emptyList(),
    val error: String = ""
)

@Serializable
data class GitDiffResponse(
    val ok: Boolean = false,
    val root: String = "",
    val diff: String = "",
    val error: String = ""
)

@Serializable
data class StageFrameMeta(
    val source: String = "desktop",
    val hwnd: Long = 0L,
    @SerialName("origin_x") val originX: Int = 0,
    @SerialName("origin_y") val originY: Int = 0,
    @SerialName("source_width") val sourceWidth: Int = 1,
    @SerialName("source_height") val sourceHeight: Int = 1,
    @SerialName("full_x") val fullX: Int = 0,
    @SerialName("full_y") val fullY: Int = 0,
    @SerialName("full_width") val fullWidth: Int = 1,
    @SerialName("full_height") val fullHeight: Int = 1,
    @SerialName("frame_width") val frameWidth: Int = 1,
    @SerialName("frame_height") val frameHeight: Int = 1,
    @SerialName("cursor_x") val cursorX: Int = 0,
    @SerialName("cursor_y") val cursorY: Int = 0,
    @SerialName("is_blank") val isBlank: Boolean = false,
    @SerialName("capture_ms") val captureMs: Double = 0.0,
    @SerialName("frame_id") val frameId: Long = 0L,
    val bytes: Int = 0
)

@Serializable
data class GenericOkResponse(
    val ok: Boolean = false,
    val error: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val method: String = ""
)
