# Kova Architecture & Protocol Specification

Kova is a **Mobile-First Semantic Windows Remote Workstation**. Rather than squeezing a 1080p desktop bitmap onto a phone screen, Kova pairs a lightweight Windows Host Agent (`kova-host`) with a native Jetpack Compose Android client (`Kova`) across four synchronized surfaces over a single multiplexed HTTPS/WSS port.

---

## 1. Core Architectural Pillars

```
+-------------------------------------------------------------------+
|                     Android Client (Kova)                         |
|  +------------+  +--------------+  +-----------+  +------------+  |
|  | Pulse Hub  |  |  Lens Stage  |  |   Shell   |  |   Forge    |  |
|  | Cards,Clip |  | Window+UIA   |  |  ConPTY   |  | Files+Git  |  |
|  +-----+------+  +------+-------+  +-----+-----+  +-----+------+  |
|        +----------------+----------------+--------------+         |
|                         | KovaClient (OkHttp)                     |
|         Pinned TLS 1.3 + Bearer Auth + Auto-Reconnect             |
+-------------------------+-----------------------------------------+
                          | Single Port (default 8765)
                          | HTTPS REST + WSS Multiplexed Streams
+-------------------------+-----------------------------------------+
|                 Windows Host Agent (kova-host)                    |
|  +-------------------------------------------------------------+  |
|  | aiohttp Async Server (REST + /ws/stage + /ws/terminal +     |  |
|  |                       /ws/events)                           |  |
|  +----+------------+-------------+--------------+-----------+--+  |
|       |            |             |              |           |     |
|  +----+----+  +----+-----+  +----+-----+  +-----+----+  +---+---+ |
|  | Capture |  |   UIA    |  |  ConPTY  |  |  Windows |  | Files | |
|  | PrintWin|  | C# .NET  |  | Create   |  |  Manager |  | + Git | |
|  | BitBlt  |  | Daemon   |  | Pseudo   |  | MobileFit|  | +Tail | |
|  | +ROI    |  | Bridge   |  | Console  |  | SendInput|  | +Clip | |
|  +---------+  +----------+  +----------+  +----------+  +-------+ |
+-------------------------------------------------------------------+
```

### Why This Beats Traditional RDP on a Smartphone

1. **Mobile-Fit Window Reflow (`POST /api/windows/{hwnd}/mobile-fit`)**
   - Stores the window's original `RECT` and `SHOWCMD`, then uses Win32 `SetWindowPos` to reshape the target desktop app (Chrome, VS Code, Antigravity, Cursor, Notion, etc.) to match the phone's portrait aspect ratio (e.g. `480×860` logical px).
   - Responsive web apps and Electron/IDE panes natively reflow into a single readable mobile column. One tap (`restore`) snaps the window back to its exact desktop geometry.

2. **Live Semantic UI Automation Bridge (`uia_helper.cs` -> persistent daemon)**
   - Compiled automatically on Windows using the built-in .NET Framework 4.x `csc.exe` + `UIAutomationClient.dll` (zero third-party SDK install required).
   - Runs as a persistent line-delimited JSON-RPC daemon so inspecting any active Windows app takes ~20-40ms.
   - Extracts interactive controls (`Button`, `Edit`, `TabItem`, `MenuItem`, `Hyperlink`, `CheckBox`, `ComboBox`, `ListItem`, `Text`) with screen-space and window-relative bounding boxes.
   - Enables **X-Ray Overlay** (giant tappable hit chips over the visual stream) and the **Semantic Control Sheet** (searchable native Android buttons, inputs, and selectable text extracted directly from Windows).

3. **Adaptive Visual Stage with Server-Side ROI Cropping (`/ws/stage`)**
   - Supports **Single-Window Capture** (`PrintWindow` with `PW_RENDERFULLCONTENT` + DWM crop fallback) and **Full Desktop Capture** (`BitBlt` + live Win32 cursor rendering via `GetCursorInfo`/`DrawIconEx`).
   - When zoomed in on Android, the client sends a normalized Region-of-Interest (`roi: {x, y, w, h}`) so the Windows host crops BEFORE encoding—delivering 1:1 native-sharp text instead of upscaled blur.
   - Frame-change hashing skips identical frames to save cellular data and battery.
   - Automatically detects blank/black DWM frames on disconnected Windows Server VPS RDP sessions and notifies the Android client with recovery actions.

4. **True Windows ConPTY Persistent Terminals (`/ws/terminal/{id}`)**
   - Uses Win32 `CreatePseudoConsole` + `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE` directly via `ctypes`.
   - Terminals live independently of phone connections with a 256 KB scrollback ring buffer replayed on reconnect.
   - Dynamic `ResizePseudoConsole(cols, rows)` adapts cleanly to phone width and Android IME open/close state.

5. **Native Mobile File Explorer, Code Editor & Git Bridge (`/api/files/*`, `/api/git/*`)**
   - Fast directory listing, binary upload/download, text read/write, search, live log tailing, and Git status/diff/stage/commit/push directly over HTTPS—working even when no RDP GUI session is attached.

---

## 2. Wire Protocol Summary

### Authentication & Transport Security
- **TLS**: Auto-generated self-signed X.509 certificate stored in `%USERPROFILE%\.kova\cert.pem` and `key.pem` with SAN entries for detected IPs and hostname.
- **Certificate Pinning**: SHA-256 SPKI/DER fingerprint (`cert_sha256`) is embedded in the pairing QR code / `kova://connect?...` URI. The Android OkHttp client pins and verifies that exact certificate fingerprint—preventing MITM attacks without requiring public CA setup.
- **Token Auth**: 256-bit random URL-safe secret token passed via `Authorization: Bearer <token>` on REST calls and `?token=<token>` (or header) on WebSockets, verified with `hmac.compare_digest`.

### Pairing URI Format
```
kova://connect?host=<ip_or_domain>&port=8765&token=<secret>&fp=<sha256_hex>&name=<hostname>&tls=1
```

### REST Endpoints
- `GET  /api/health` — Public lightweight reachability check (no sensitive data).
- `GET  /api/system/info` — Hostname, OS, screen metrics, CPU/RAM/Disk, RDP session state, uptime.
- `GET  /api/windows` — Visible top-level windows with bounds, process, state, mobile-fit status.
- `GET  /api/windows/{hwnd}/thumbnail` — Crisp JPEG/WebP preview thumbnail.
- `POST /api/windows/{hwnd}/action` — `{action: focus|minimize|maximize|restore|close|mobile_fit|restore_fit, width?, height?}`
- `GET  /api/windows/{hwnd}/uia` — Live Semantic UI Automation control tree & extracted text.
- `POST /api/uia/action` — `{runtime_id?, hwnd?, x?, y?, action: invoke|focus|set_value|click, value?}`
- `POST /api/input/dispatch` — Smart Prompt / Unicode text injection (`text`, `press_enter`, `mode: paste|type`, `hwnd?`).
- `POST /api/input/key` — Key chords (`keys: ["ctrl","shift","p"]`).
- `POST /api/launch` — Launch app/URL/macro (`target`, `args?`, `cwd?`).
- `GET  /api/clipboard` & `POST /api/clipboard` — Bi-directional Windows clipboard sync + history.
- `GET  /api/terminals` / `POST /api/terminals` / `DELETE /api/terminals/{id}` — ConPTY session lifecycle.
- `GET  /api/files/roots` / `GET /api/files/list` / `GET /api/files/read` / `POST /api/files/write` / `POST /api/files/mkdir` / `POST /api/files/rename` / `DELETE /api/files/delete` / `POST /api/files/upload` / `GET /api/files/download` / `GET /api/files/search`
- `GET  /api/git/status` / `GET /api/git/diff` / `POST /api/git/action` (`stage`, `unstage`, `commit`, `pull`, `push`).

### WebSocket Channels
- `WSS /ws/stage` — Bidirectional visual stage stream:
  - Client -> Host: `config` (mode `desktop|window`, `hwnd`, `fps`, `quality`, `format`, `max_width`, `roi`), `mouse_move`, `mouse_rel`, `mouse_button`, `mouse_click`, `scroll`, `key`, `text`, `ping`
  - Host -> Client: Binary frame packets (`[4-byte header len][JSON metadata][WebP/JPEG bytes]`) + JSON status/cursor/warning/pong messages.
- `WSS /ws/terminal/{id}` — Bidirectional ConPTY stream:
  - Client -> Host: `input` (UTF-8 / escape sequences), `resize` (`cols`, `rows`), `ping`
  - Host -> Client: `replay` (buffered history), `output` (live VT100 stream), `exit`, `pong`.
- `WSS /ws/events` — Real-time workstation telemetry, clipboard change pushes, window list updates, and heartbeat pings.
