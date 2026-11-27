"""Native Windows GUI Control Center for Kova Host Agent (double-click KovaHost.exe)."""

from __future__ import annotations

import asyncio
import ctypes
import os
import sys
import threading
import tkinter as tk
import urllib.request
from pathlib import Path
from tkinter import messagebox, ttk
from typing import Any

import qrcode
from aiohttp import web
from PIL import Image, ImageTk

from . import __version__
from .auth import build_pairing_uri
from .config import (
    HostConfig,
    create_ssl_context,
    detect_local_ips,
    ensure_tls_certificate,
    load_or_create_config,
    save_config,
)
from .server import KovaServer
from .telemetry import enable_dpi_awareness

BG = "#090D16"
CARD = "#121B2D"
ELEVATED = "#1B2842"
BORDER = "#273859"
CYAN = "#38BDF8"
EMERALD = "#2DD4BF"
AMBER = "#FBBF24"
ROSE = "#FB7185"
INK = "#F1F5F9"
MUTED = "#94A3B8"


def _run_elevated_powershell(command: str) -> bool:
    """Prompt UAC cleanly via Win32 ShellExecuteW('runas') for admin operations."""
    try:
        params = f'-NoProfile -ExecutionPolicy Bypass -Command "{command}"'
        rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", "powershell.exe", params, None, 1)
        return int(rc) > 32
    except Exception:
        return False


def _detect_public_ip(timeout: float = 4.0) -> str | None:
    for url in ("https://api.ipify.org", "https://icanhazip.com"):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "KovaHost/1.1"})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                ip = resp.read().decode("utf-8").strip()
                if ip and len(ip) <= 45:
                    return ip
        except Exception:
            continue
    return None


def _startup_bat_path() -> Path:
    appdata = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
    return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup" / "KovaHost.bat"


class KovaHostGui:
    def __init__(self, port: int | None = None, public_host: str | None = None, no_tls: bool = False) -> None:
        enable_dpi_awareness()
        self.cfg: HostConfig = load_or_create_config(
            port_override=port,
            public_host_override=public_host,
            tls_override=(False if no_tls else None),
        )
        self.ips = detect_local_ips()
        self.fingerprint = ""
        self.current_uri = ""
        self._qr_photo: ImageTk.PhotoImage | None = None

        self._loop: asyncio.AbstractEventLoop | None = None
        self._stop_event: asyncio.Event | None = None
        self._server_thread: threading.Thread | None = None
        self._server_error: str | None = None

        self.root = tk.Tk()
        self.root.title(f"Kova Workstation Host v{__version__}")
        self.root.configure(bg=BG)
        self.root.geometry("540x730")
        self.root.minsize(500, 680)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        self._host_var = tk.StringVar(value=self.cfg.public_host or (self.ips[0] if self.ips else "127.0.0.1"))
        self._status_var = tk.StringVar(value="Starting server...")
        self._uri_var = tk.StringVar(value="")
        self._pin_var = tk.StringVar(value="")
        self._autostart_var = tk.BooleanVar(value=_startup_bat_path().exists())

        self._build_ui()
        self._start_server_thread()

    def _build_ui(self) -> None:
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("TCombobox", fieldbackground=ELEVATED, background=ELEVATED, foreground=INK)

        outer = tk.Frame(self.root, bg=BG, padx=20, pady=16)
        outer.pack(fill=tk.BOTH, expand=True)

        # Header
        hdr = tk.Frame(outer, bg=BG)
        hdr.pack(fill=tk.X)
        tk.Label(
            hdr,
            text="KOVA HOST",
            font=("Segoe UI", 18, "bold"),
            fg=INK,
            bg=BG,
        ).pack(side=tk.LEFT)
        tk.Label(
            hdr,
            text=f"  v{__version__}",
            font=("Segoe UI", 10),
            fg=CYAN,
            bg=BG,
        ).pack(side=tk.LEFT, pady=(6, 0))

        self.status_badge = tk.Label(
            hdr,
            textvariable=self._status_var,
            font=("Segoe UI", 9, "bold"),
            fg=EMERALD,
            bg="#102C2D",
            padx=10,
            pady=4,
        )
        self.status_badge.pack(side=tk.RIGHT)

        tk.Label(
            outer,
            text="Scan the QR code in the Kova Android app, or click Copy Pairing Link.",
            font=("Segoe UI", 9),
            fg=MUTED,
            bg=BG,
        ).pack(anchor="w", pady=(4, 12))

        # QR Card
        qr_card = tk.Frame(outer, bg=CARD, highlightbackground=BORDER, highlightthickness=1, padx=16, pady=14)
        qr_card.pack(fill=tk.X)

        self.qr_label = tk.Label(qr_card, bg="#FFFFFF", padx=8, pady=8)
        self.qr_label.pack(pady=(0, 10))

        tk.Label(qr_card, textvariable=self._pin_var, font=("Consolas", 8), fg=EMERALD, bg=CARD).pack()

        uri_entry = tk.Entry(
            qr_card,
            textvariable=self._uri_var,
            font=("Consolas", 8),
            fg=CYAN,
            bg=BG,
            readonlybackground=BG,
            relief=tk.FLAT,
            state="readonly",
        )
        uri_entry.pack(fill=tk.X, pady=(6, 10), ipady=5)

        btn_row = tk.Frame(qr_card, bg=CARD)
        btn_row.pack(fill=tk.X)
        self.copy_btn = tk.Button(
            btn_row,
            text="Copy Pairing Link",
            command=self._copy_uri,
            font=("Segoe UI", 10, "bold"),
            fg=BG,
            bg=CYAN,
            activebackground="#7DD3FC",
            relief=tk.FLAT,
            padx=14,
            pady=7,
            cursor="hand2",
        )
        self.copy_btn.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))

        tk.Button(
            btn_row,
            text="Rotate Token",
            command=self._rotate_token,
            font=("Segoe UI", 9),
            fg=INK,
            bg=ELEVATED,
            activebackground=BORDER,
            relief=tk.FLAT,
            padx=12,
            pady=7,
            cursor="hand2",
        ).pack(side=tk.RIGHT)

        # Network Endpoint Selector
        net_card = tk.Frame(outer, bg=CARD, highlightbackground=BORDER, highlightthickness=1, padx=14, pady=12)
        net_card.pack(fill=tk.X, pady=(12, 0))

        tk.Label(
            net_card,
            text="Pairing Address (Tailscale IP, Public VPS IP, or Domain)",
            font=("Segoe UI", 9, "bold"),
            fg=INK,
            bg=CARD,
        ).pack(anchor="w")

        addr_row = tk.Frame(net_card, bg=CARD)
        addr_row.pack(fill=tk.X, pady=(6, 0))

        candidates = list(dict.fromkeys(([self.cfg.public_host] if self.cfg.public_host else []) + self.ips))
        self.host_combo = ttk.Combobox(addr_row, textvariable=self._host_var, values=candidates, font=("Consolas", 10))
        self.host_combo.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))
        self.host_combo.bind("<<ComboboxSelected>>", lambda _e: self._apply_host_change())
        self.host_combo.bind("<Return>", lambda _e: self._apply_host_change())

        tk.Button(
            addr_row,
            text="Update QR",
            command=self._apply_host_change,
            font=("Segoe UI", 9),
            fg=INK,
            bg=ELEVATED,
            relief=tk.FLAT,
            padx=10,
            pady=5,
            cursor="hand2",
        ).pack(side=tk.LEFT, padx=(0, 6))

        tk.Button(
            addr_row,
            text="Detect Public IP",
            command=self._detect_public_ip_async,
            font=("Segoe UI", 9),
            fg=CYAN,
            bg=ELEVATED,
            relief=tk.FLAT,
            padx=10,
            pady=5,
            cursor="hand2",
        ).pack(side=tk.LEFT)

        # Windows VPS Quick Tools
        tools_card = tk.Frame(outer, bg=CARD, highlightbackground=BORDER, highlightthickness=1, padx=14, pady=12)
        tools_card.pack(fill=tk.X, pady=(12, 0))

        tk.Label(tools_card, text="Windows VPS Controls", font=("Segoe UI", 9, "bold"), fg=INK, bg=CARD).pack(anchor="w")

        trow = tk.Frame(tools_card, bg=CARD)
        trow.pack(fill=tk.X, pady=(8, 4))

        tk.Button(
            trow,
            text=f"Open Firewall (TCP {self.cfg.port})",
            command=self._open_firewall,
            font=("Segoe UI", 9),
            fg=INK,
            bg=ELEVATED,
            relief=tk.FLAT,
            padx=10,
            pady=7,
            cursor="hand2",
        ).pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))

        tk.Button(
            trow,
            text="Keep RDP GUI Alive (tscon)",
            command=self._keep_rdp_alive,
            font=("Segoe UI", 9),
            fg=AMBER,
            bg=ELEVATED,
            relief=tk.FLAT,
            padx=10,
            pady=7,
            cursor="hand2",
        ).pack(side=tk.LEFT, fill=tk.X, expand=True)

        tk.Checkbutton(
            tools_card,
            text="Launch KovaHost automatically when Windows signs in",
            variable=self._autostart_var,
            command=self._toggle_autostart,
            fg=MUTED,
            bg=CARD,
            selectcolor=BG,
            activebackground=CARD,
            activeforeground=INK,
            font=("Segoe UI", 9),
        ).pack(anchor="w", pady=(6, 0))

        # Compact Activity Log
        self.log_box = tk.Text(
            outer,
            height=5,
            bg=BG,
            fg=MUTED,
            font=("Consolas", 8),
            relief=tk.FLAT,
            highlightbackground=BORDER,
            highlightthickness=1,
            state=tk.DISABLED,
        )
        self.log_box.pack(fill=tk.BOTH, expand=True, pady=(12, 0))

    def _log(self, msg: str) -> None:
        def _append() -> None:
            self.log_box.configure(state=tk.NORMAL)
            self.log_box.insert(tk.END, msg + "\n")
            self.log_box.see(tk.END)
            self.log_box.configure(state=tk.DISABLED)

        self.root.after(0, _append)

    def _refresh_qr(self) -> None:
        host = self._host_var.get().strip() or (self.ips[0] if self.ips else "127.0.0.1")
        uri = build_pairing_uri(
            host=host,
            port=self.cfg.port,
            token=self.cfg.token,
            fingerprint=self.fingerprint,
            name=self.cfg.name,
            tls_enabled=self.cfg.tls_enabled,
        )
        self.current_uri = uri
        self._uri_var.set(uri)
        if self.cfg.tls_enabled and self.fingerprint:
            self._pin_var.set(f"TLS SHA-256 Pin: {self.fingerprint[:16]}...{self.fingerprint[-12:]}")
        else:
            self._pin_var.set("TLS Disabled (recommended only inside Tailscale / WireGuard)")

        qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=6, border=2)
        qr.add_data(uri)
        qr.make(fit=True)
        pil_img = qr.make_image(fill_color="#090D16", back_color="#FFFFFF").convert("RGB")
        pil_img = pil_img.resize((220, 220), Image.Resampling.NEAREST)
        self._qr_photo = ImageTk.PhotoImage(pil_img)
        self.qr_label.configure(image=self._qr_photo)

    def _start_server_thread(self) -> None:
        def _worker() -> None:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            self._loop = loop
            try:
                loop.run_until_complete(self._serve_forever())
            except Exception as exc:
                self._server_error = str(exc)
                self.root.after(0, lambda: self._set_error_state(str(exc)))
            finally:
                loop.close()

        self._server_thread = threading.Thread(target=_worker, name="kova-server", daemon=True)
        self._server_thread.start()

    async def _serve_forever(self) -> None:
        extra_hosts = [self.cfg.public_host] if self.cfg.public_host else []
        ssl_ctx = None
        if self.cfg.tls_enabled:
            self.fingerprint = ensure_tls_certificate(self.cfg.cert_path, self.cfg.key_path, extra_hosts=extra_hosts)
            ssl_ctx = create_ssl_context(self.cfg.cert_path, self.cfg.key_path)

        server = KovaServer(self.cfg, cert_fingerprint=self.fingerprint)
        runner = web.AppRunner(server.app, access_log=None)
        await runner.setup()
        site = web.TCPSite(runner, host=self.cfg.host, port=self.cfg.port, ssl_context=ssl_ctx)
        await site.start()

        proto = "HTTPS/WSS" if self.cfg.tls_enabled else "HTTP/WS"
        self.root.after(0, lambda: self._on_server_ready(proto))

        self._stop_event = asyncio.Event()
        await self._stop_event.wait()
        await runner.cleanup()

    def _on_server_ready(self, proto: str) -> None:
        self._status_var.set(f"● ONLINE · {proto} :{self.cfg.port}")
        self.status_badge.configure(fg=EMERALD, bg="#102C2D")
        self._refresh_qr()
        self._log(f"[OK] Kova Host v{__version__} listening on {self.cfg.host}:{self.cfg.port} ({proto})")
        self._log(f"[INFO] Detected local IPs: {', '.join(self.ips)}")
        self._log("[TIP] Scan the QR code from Kova on Android, or click Detect Public IP for direct VPS access.")

    def _set_error_state(self, err: str) -> None:
        self._status_var.set("● ERROR")
        self.status_badge.configure(fg=ROSE, bg="#36101B")
        self._log(f"[ERROR] Failed to start server: {err}")
        messagebox.showerror("Kova Host Error", f"Could not start Kova Host on port {self.cfg.port}:\n\n{err}")

    def _copy_uri(self) -> None:
        if not self.current_uri:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(self.current_uri)
        self.root.update()
        self.copy_btn.configure(text="Copied to Clipboard!", bg=EMERALD)
        self._log("[OK] Pairing URI copied to Windows clipboard.")
        self.root.after(2000, lambda: self.copy_btn.configure(text="Copy Pairing Link", bg=CYAN))

    def _apply_host_change(self) -> None:
        val = self._host_var.get().strip()
        if not val:
            return
        self.cfg.public_host = val
        save_config(self.cfg)
        self._refresh_qr()
        self._log(f"[OK] Updated pairing endpoint to {val}:{self.cfg.port}")

    def _detect_public_ip_async(self) -> None:
        self._log("[INFO] Detecting public IPv4 address...")

        def _fetch() -> None:
            ip = _detect_public_ip()
            if ip:
                def _apply() -> None:
                    self._host_var.set(ip)
                    vals = list(self.host_combo["values"])
                    if ip not in vals:
                        self.host_combo["values"] = [ip] + vals
                    self._apply_host_change()
                self.root.after(0, _apply)
            else:
                self._log("[WARN] Could not detect public IP automatically. Enter your Tailscale or VPS IP manually.")

        threading.Thread(target=_fetch, daemon=True).start()

    def _rotate_token(self) -> None:
        if not messagebox.askyesno("Rotate Token", "Generate a new pairing secret? Paired phones will need to scan the new QR code."):
            return
        import secrets
        self.cfg.token = secrets.token_urlsafe(32)
        save_config(self.cfg)
        self._refresh_qr()
        self._log("[OK] Rotated 256-bit host secret token. Scan the updated QR code.")

    def _open_firewall(self) -> None:
        cmd = (
            f"New-NetFirewallRule -DisplayName 'Kova Workstation Host ({self.cfg.port})' "
            f"-Direction Inbound -Protocol TCP -LocalPort {self.cfg.port} -Action Allow -ErrorAction SilentlyContinue; "
            f"Write-Host 'Firewall rule ensured for TCP/{self.cfg.port}.' -ForegroundColor Green; Start-Sleep -Seconds 2"
        )
        ok = _run_elevated_powershell(cmd)
        self._log("[OK] Requested Administrator firewall rule for TCP/" + str(self.cfg.port) if ok else "[WARN] UAC cancelled.")

    def _keep_rdp_alive(self) -> None:
        if not messagebox.askyesno(
            "Keep RDP GUI Alive",
            "This transfers your active RDP session cleanly to the local VPS console (tscon) so Windows keeps GUI rendering live after RDP closes.\n\nYour RDP window will close now — continue?",
        ):
            return
        cmd = (
            "$line = (query user 2>$null | Select-String -Pattern '^\\s*>' | Select-Object -First 1).ToString(); "
            "$id = ($line -split '\\s+' | Where-Object { $_ -match '^\\d+$' } | Select-Object -First 1); "
            "if ($id) { tscon $id /dest:console }"
        )
        _run_elevated_powershell(cmd)

    def _toggle_autostart(self) -> None:
        bat = _startup_bat_path()
        if self._autostart_var.get():
            if getattr(sys, "frozen", False):
                cmd_line = f'start "" "{sys.executable}"'
            else:
                cmd_line = f'start "" "{sys.executable}" -m kova_host.main --gui'
            bat.parent.mkdir(parents=True, exist_ok=True)
            bat.write_text(f"@echo off\r\n{cmd_line}\r\n", encoding="utf-8")
            self._log(f"[OK] Enabled logon auto-start at {bat}")
        else:
            if bat.exists():
                bat.unlink()
            self._log("[OK] Disabled Windows logon auto-start.")

    def _on_close(self) -> None:
        if self._loop and self._stop_event and not self._loop.is_closed():
            self._loop.call_soon_threadsafe(self._stop_event.set)
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


def run_gui(port: int | None = None, public_host: str | None = None, no_tls: bool = False) -> None:
    app = KovaHostGui(port=port, public_host=public_host, no_tls=no_tls)
    app.run()
