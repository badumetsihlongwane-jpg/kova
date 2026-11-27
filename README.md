# Kova — Mobile-First Semantic Windows Remote Workstation

**Kova** rethinks working on a Windows PC or VPS from an Android smartphone. Instead of shrinking a 1080p desktop bitmap onto a 6-inch display, Kova pairs a lightweight Windows Host Agent (`kova-host`) with a native Kotlin & Jetpack Compose Android workstation across **four synchronized, thumb-ergonomic surfaces** over a single TLS-pinned port.

---

## What Makes Kova Different from RDP / VNC

| Pain Point on a Phone | Traditional RDP | Kova Workstation |
|---|---|---|
| **Tiny unreadable desktop UI** | Pinch-pan a shrunk 1080p bitmap | **One-Tap Mobile-Fit Reflow** reshapes the Windows app itself to portrait ratio so browsers, VS Code, and AI IDEs reflow into a single mobile column—then restores desktop size in 1 tap. |
| **Fat-finger mis-clicks** | Hunt tiny 14px buttons with a slow cursor | **Semantic UI Automation X-Ray & Native Control Sheet** extracts real buttons, inputs, tabs, and selectable text from Windows apps + a **2.4× Precision Magnifier Loupe** & accelerated **Trackpad**. |
| **Writing AI prompts & typing** | Keyboard hides half the screen; broken paste | **Smart Prompt Composer** lets you use Android swipe/voice dictation in a native multi-line box and inject straight into Antigravity, VS Code, Cursor, or browsers (`Ctrl+V` or Unicode). |
| **Blurry unreadable terminals** | Compressed pixels of PowerShell/CMD | **True Windows ConPTY Terminal (`Shell`)** with crisp ANSI 256/TrueColor text, persistent sessions across disconnects, auto-reflow `(cols × rows)`, sticky `CTRL`, arrows, and command snippets. |
| **File & Git friction** | Double-clicking Windows Explorer over RDP | **Native Mobile File Explorer, Syntax-Highlighted Code Editor, Live Log Tail & Git Diff/Commit/Push (`Forge`)**—works even without an active RDP session! |

---

## Architecture Overview

- **`android/`** — Native Android app (Kotlin 2.1, Jetpack Compose Material 3, OkHttp, CameraX + ZXing QR scanner, Kotlinx Serialization).
  - **Pulse**: Live CPU/RAM/Disk telemetry, visual window cards with `Stage` / `Mobile-Fit` / `X-Ray`, Quick Launch dock, and bi-directional Clipboard Bridge.
  - **Lens**: Adaptive WebP single-window or full-desktop stage with server-side ROI zoom cropping, Trackpad / 2.4× Loupe / Direct Touch modes, X-Ray UI Automation overlay, and one-thumb shortcut chords.
  - **Shell**: Multi-tab native Windows ConPTY (`CreatePseudoConsole`) terminal with ring-buffer replay on reconnect.
  - **Forge**: Native file manager with Android upload/download, mobile code editor + symbol bar, live log tailing, and Git diff/stage/commit/push.
- **`host/`** — Windows Host Agent (`kova-host`) in Python 3.11+ + auto-compiled .NET Framework 4.x UI Automation daemon (`uia_helper.cs`). Zero third-party SDK install required on Windows 10/11/Server 2019/2022/2025.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full wire protocol and security design.

---

## Quick Start (2 Minutes on Windows VPS + Android)

### 1. Install Kova on Android
1. Open this repository's **Actions** tab -> latest **Build Android APK** workflow run.
2. Download the `kova-android-apks` artifact and install `Kova-release.apk` on your Android phone.

### 2. Run `KovaHost.exe` on your Windows VPS / PC
**Option A — Standalone Windows Control Center (No Python needed):**
1. Download **`kova-host-windows-x64.zip`** from [Releases](../../releases) and **extract all files** into a folder (e.g., `C:\KovaHost`).
2. Double-click **`KovaHost.exe`** — a native dark-mode Control Center opens immediately with a large scannable QR code, **Detect Public IP**, **Copy Pairing Link**, **Open Firewall (TCP 8765)**, and **Keep RDP GUI Alive** buttons.
   - Prefer terminal mode? Double-click **`Start-Kova-CLI.bat`** or run `.\KovaHost.exe --cli --public-host <YOUR_IP>`.

**Option B — From Source (Python 3.10+):**
```powershell
powershell -ExecutionPolicy Bypass -File .\host\scripts\install.ps1 -PublicHost <YOUR_TAILSCALE_OR_VPS_IP> -OpenFirewall
```

### 3. Pair in 5 Seconds
`kova-host` generates a 256-bit secret token and self-signed TLS certificate with SHA-256 fingerprint pinning, then prints both a **QR code** and a `kova://connect?...` URI in PowerShell.
- Open **Kova** on Android -> tap **Scan QR** (or **Paste URI**) -> connected!

### Networking & VPS Tips
- **Recommended (Zero Open Ports):** Install [Tailscale](https://tailscale.com) on both your Windows VPS and Android phone, then pass `--public-host <100.x.y.z>`.
- **Direct Public VPS Port:** Pass `-OpenFirewall` (opens TCP `8765`) and ensure your cloud provider security group allows TCP `8765`. Kova enforces pinned TLS + 256-bit Bearer token authentication by default.
- **Headless / Disconnected RDP Sessions:** **Shell (ConPTY)**, **Forge (Files/Editor/Git)**, **Telemetry**, and **Clipboard** always work even when no RDP client is attached. If Windows Server suspends desktop rendering after closing RDP, run `host\scripts\keep-session-alive.ps1` as Administrator inside your RDP session to cleanly hand it off to the local console (`tscon`).

---

## Local Development & Tests

### Host Tests (Windows)
```powershell
cd host
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt pytest pytest-asyncio
.\.venv\Scripts\pip install -e . --no-deps
.\.venv\Scripts\pytest -v tests\test_host_live.py
```

### Android Unit Tests & APK Build
```bash
cd android
gradle testDebugUnitTest assembleDebug assembleRelease
```
