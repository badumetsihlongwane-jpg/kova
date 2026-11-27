Kova Workstation Host v1.1.0 — Windows Quick Start
==================================================

1. Extract ALL files from this ZIP into a normal folder (e.g. C:\KovaHost).
   (Do not run directly from inside the ZIP preview.)

2. Double-click  KovaHost.exe
   - Opens the Kova Control Center window and starts the server immediately.
   - Click "Detect Public IP" (or choose your Tailscale 100.x.y.z IP).
   - Click "Open Firewall (TCP 8765)" if connecting directly via VPS public IP.
   - Scan the QR code inside the Kova Android app (or click "Copy Pairing Link").

Optional Helpers (double-clickable .bat launchers):
- Start-Kova-CLI.bat          : Run in PowerShell/Command Prompt console mode.
- Open-Firewall-Port.bat      : Open TCP port 8765 in Windows Firewall (Admin).
- Keep-RDP-Session-Alive.bat  : Hand RDP session to console (tscon) so GUI
                                rendering stays active after closing RDP.
