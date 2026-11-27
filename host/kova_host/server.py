"""Unified async HTTPS + WSS server powering Kova's 4 synchronized mobile workspaces."""

from __future__ import annotations

import asyncio
import json
import os
import struct
import subprocess
import time
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web

from . import __version__
from .auth import extract_request_token, verify_token
from .capture import ScreenCapturer, WindowUnavailableError
from .clipboard import ClipboardManager
from .config import HostConfig
from .desktop_attach import launch_on_interactive_desktop
from .files import (
    delete_path,
    get_roots,
    git_action,
    git_diff,
    git_status,
    list_directory,
    make_directory,
    read_text_file,
    rename_path,
    search_files,
    write_text_file,
)
from .input_ctrl import InputController
from .telemetry import TelemetryCollector
from .terminal import TerminalManager
from .uia_bridge import UiaBridge
from .windows_mgr import WindowManager


class KovaServer:
    def __init__(self, config: HostConfig, cert_fingerprint: str = "") -> None:
        self.config = config
        self.cert_fingerprint = cert_fingerprint
        self.telemetry = TelemetryCollector()
        self.windows = WindowManager()
        self.capturer = ScreenCapturer()
        self.input_ctrl = InputController()
        self.clipboard = ClipboardManager()
        self.terminals = TerminalManager()
        self.uia = UiaBridge(bin_dir=config.bin_dir)

        self._event_clients: set[web.WebSocketResponse] = set()
        self._bg_tasks: list[asyncio.Task[Any]] = []
        self.app = self._build_app()

    def _build_app(self) -> web.Application:
        app = web.Application(
            middlewares=[self._auth_middleware],
            client_max_size=100 * 1024 * 1024,  # 100 MB file uploads
        )
        app.on_startup.append(self._on_startup)
        app.on_cleanup.append(self._on_cleanup)

        app.router.add_get("/", self.handle_root)
        app.router.add_get("/api/health", self.handle_health)

        # System & Quick Launch
        app.router.add_get("/api/system/info", self.handle_system_info)
        app.router.add_post("/api/system/activate-display", self.handle_activate_display)
        app.router.add_post("/api/launch", self.handle_launch)

        # Windows & Mobile-Fit Reflow
        app.router.add_get("/api/windows", self.handle_windows_list)
        app.router.add_get("/api/windows/{hwnd}/thumbnail", self.handle_window_thumbnail)
        app.router.add_post("/api/windows/{hwnd}/action", self.handle_window_action)

        # Semantic UI Automation (X-Ray & Native Control Sheet)
        app.router.add_get("/api/windows/{hwnd}/uia", self.handle_uia_inspect)
        app.router.add_post("/api/uia/action", self.handle_uia_action)

        # Input & Smart Prompt Dispatch
        app.router.add_post("/api/input/dispatch", self.handle_input_dispatch)
        app.router.add_post("/api/input/key", self.handle_input_key)

        # Bi-directional Clipboard
        app.router.add_get("/api/clipboard", self.handle_clipboard_get)
        app.router.add_post("/api/clipboard", self.handle_clipboard_set)

        # ConPTY Terminals
        app.router.add_get("/api/terminals", self.handle_terminals_list)
        app.router.add_post("/api/terminals", self.handle_terminal_create)
        app.router.add_delete("/api/terminals/{id}", self.handle_terminal_delete)

        # Native Files & Code Editor
        app.router.add_get("/api/files/roots", self.handle_files_roots)
        app.router.add_get("/api/files/list", self.handle_files_list)
        app.router.add_get("/api/files/read", self.handle_files_read)
        app.router.add_post("/api/files/write", self.handle_files_write)
        app.router.add_post("/api/files/mkdir", self.handle_files_mkdir)
        app.router.add_post("/api/files/rename", self.handle_files_rename)
        app.router.add_delete("/api/files/delete", self.handle_files_delete)
        app.router.add_get("/api/files/search", self.handle_files_search)
        app.router.add_post("/api/files/upload", self.handle_files_upload)
        app.router.add_get("/api/files/download", self.handle_files_download)

        # Git Bridge
        app.router.add_get("/api/git/status", self.handle_git_status)
        app.router.add_get("/api/git/diff", self.handle_git_diff)
        app.router.add_post("/api/git/action", self.handle_git_action)

        # WebSocket Streams
        app.router.add_get("/ws/stage", self.handle_ws_stage)
        app.router.add_get("/ws/terminal/{id}", self.handle_ws_terminal)
        app.router.add_get("/ws/events", self.handle_ws_events)
        return app

    @web.middleware
    async def _auth_middleware(self, request: web.Request, handler: Any) -> web.StreamResponse:
        if request.path in ("/", "/api/health"):
            return await handler(request)
        token = extract_request_token(request)
        if not verify_token(token, self.config.token):
            return web.json_response({"ok": False, "error": "Unauthorized"}, status=401)
        return await handler(request)

    async def _on_startup(self, _app: web.Application) -> None:
        # Warm up UIA compiler in background so first X-Ray request is instant
        self._bg_tasks.append(asyncio.create_task(asyncio.to_thread(self.uia.ensure_compiled)))
        self._bg_tasks.append(asyncio.create_task(self._events_broadcaster()))

    async def _on_cleanup(self, _app: web.Application) -> None:
        for task in self._bg_tasks:
            task.cancel()
        for ws in list(self._event_clients):
            await ws.close()
        self.terminals.close_all()
        await self.uia.close()

    async def _events_broadcaster(self) -> None:
        last_telemetry = 0.0
        while True:
            try:
                await asyncio.sleep(0.6)
                if not self._event_clients:
                    continue

                clip_event = await asyncio.to_thread(self.clipboard.poll_change)
                if clip_event:
                    await self._broadcast_event({"type": "clipboard", "data": clip_event})

                now = time.monotonic()
                if now - last_telemetry >= 2.5:
                    last_telemetry = now
                    snap = await asyncio.to_thread(self.telemetry.snapshot)
                    await self._broadcast_event({"type": "telemetry", "data": snap})
            except asyncio.CancelledError:
                break
            except Exception:
                await asyncio.sleep(1.0)

    async def _broadcast_event(self, payload: dict[str, Any]) -> None:
        text = json.dumps(payload)
        dead: list[web.WebSocketResponse] = []
        for ws in list(self._event_clients):
            try:
                await ws.send_str(text)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self._event_clients.discard(ws)

    # ---------- Public Routes ----------

    async def handle_root(self, _request: web.Request) -> web.Response:
        html = (
            "<!doctype html><html><head><meta charset='utf-8'>"
            "<title>Kova Host Agent</title>"
            "<style>body{font-family:system-ui,sans-serif;background:#090D16;color:#E6EEF8;"
            "display:grid;place-items:center;min-height:100vh;margin:0}"
            ".card{background:#121927;border:1px solid #22304A;border-radius:16px;padding:28px 34px;max-width:440px}"
            "h1{margin:0 0 8px;font-size:22px;color:#38BDF8}p{margin:6px 0;color:#94A3B8;font-size:14px}</style>"
            "</head><body><div class='card'>"
            f"<h1>Kova Host Agent v{__version__}</h1>"
            f"<p>Workstation: <strong>{self.config.name}</strong></p>"
            "<p>Status: Online &amp; ready for paired Android clients.</p>"
            "</div></body></html>"
        )
        return web.Response(text=html, content_type="text/html")

    async def handle_health(self, _request: web.Request) -> web.Response:
        return web.json_response(
            {
                "ok": True,
                "service": "kova-host",
                "version": __version__,
                "name": self.config.name,
                "tls": self.config.tls_enabled,
            }
        )

    # ---------- System & Launch ----------

    async def handle_system_info(self, _request: web.Request) -> web.Response:
        info = await asyncio.to_thread(self.telemetry.snapshot)
        info["name"] = self.config.name
        info["version"] = __version__
        info["quick_launch"] = self.config.quick_launch
        info["cert_sha256"] = self.cert_fingerprint
        return web.json_response(info)

    async def handle_activate_display(self, _request: web.Request) -> web.Response:
        """Hand active RDP session cleanly to local VPS console (tscon) so GUI rendering stays live."""
        try:
            snap = await asyncio.to_thread(self.telemetry.snapshot)
            sid = int(snap.get("session", {}).get("session_id", 0))
            if sid <= 0:
                return web.json_response({"ok": False, "error": "No interactive user session found"}, status=400)
            res = await asyncio.to_thread(
                subprocess.run,
                ["tscon", str(sid), "/dest:console"],
                capture_output=True,
                text=True,
                timeout=8,
            )
            if res.returncode == 0:
                return web.json_response({"ok": True, "session_id": sid, "message": "Attached session to console display"})
            return web.json_response(
                {"ok": False, "error": (res.stderr or res.stdout or f"tscon exited {res.returncode}").strip()},
                status=500,
            )
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def handle_launch(self, request: web.Request) -> web.Response:
        body = await request.json()
        target = str(body.get("target", "")).strip()
        args = body.get("args") or []
        cwd = body.get("cwd") or str(Path.home())
        if not target:
            return web.json_response({"ok": False, "error": "Missing target"}, status=400)

        try:
            pid = await asyncio.to_thread(
                launch_on_interactive_desktop,
                target,
                [str(a) for a in args],
                cwd if os.path.isdir(cwd) else str(Path.home()),
            )
            return web.json_response({"ok": True, "target": target, "pid": pid})
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    # ---------- Windows & Mobile-Fit ----------

    async def handle_windows_list(self, _request: web.Request) -> web.Response:
        wins = await asyncio.to_thread(self.windows.list_windows)
        return web.json_response({"ok": True, "windows": wins})

    async def handle_window_thumbnail(self, request: web.Request) -> web.Response:
        hwnd = int(request.match_info["hwnd"])
        max_w = int(request.query.get("w", "380"))
        try:
            data = await asyncio.to_thread(self.capturer.capture_thumbnail, hwnd, max_w)
            return web.Response(body=data, content_type="image/jpeg")
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=404)

    async def handle_window_action(self, request: web.Request) -> web.Response:
        hwnd = int(request.match_info["hwnd"])
        body = await request.json()
        action = str(body.get("action", "")).strip()
        width = body.get("width")
        height = body.get("height")
        res = await asyncio.to_thread(
            self.windows.perform_action,
            hwnd,
            action,
            int(width) if width else None,
            int(height) if height else None,
        )
        return web.json_response(res)

    # ---------- Semantic UI Automation ----------

    async def handle_uia_inspect(self, request: web.Request) -> web.Response:
        hwnd = int(request.match_info["hwnd"])
        max_nodes = int(request.query.get("max_nodes", "160"))
        res = await self.uia.inspect(hwnd=hwnd, max_nodes=max_nodes)
        return web.json_response(res)

    async def handle_uia_action(self, request: web.Request) -> web.Response:
        body = await request.json()
        hwnd = int(body.get("hwnd", 0))
        runtime_id = str(body.get("runtime_id", ""))
        action = str(body.get("action", "invoke"))
        value = str(body.get("value", ""))
        x = body.get("x")
        y = body.get("y")

        if hwnd:
            await asyncio.to_thread(self.windows.focus_window, hwnd)
            await asyncio.sleep(0.05)

        if runtime_id and action in ("invoke", "toggle", "select", "set_value", "focus"):
            res = await self.uia.action(hwnd=hwnd, runtime_id=runtime_id, action=action, value=value)
            if res.get("ok"):
                return web.json_response(res)
            # Clean fallbacks when modern web/Electron controls don't implement direct patterns
            if res.get("fallback_paste") and value:
                await asyncio.to_thread(
                    self.input_ctrl.dispatch_text,
                    value,
                    False,
                    "paste",
                    self.clipboard,
                    self.windows,
                    hwnd,
                )
                return web.json_response({"ok": True, "method": "fallback_paste"})
            if res.get("fallback_click") and x is not None and y is not None:
                await asyncio.to_thread(self.input_ctrl.click, int(x), int(y), "left", 1)
                return web.json_response({"ok": True, "method": "fallback_click"})

        if x is not None and y is not None:
            await asyncio.to_thread(self.input_ctrl.click, int(x), int(y), "left", 1)
            if action == "set_value" and value:
                await asyncio.sleep(0.05)
                await asyncio.to_thread(
                    self.input_ctrl.dispatch_text,
                    value,
                    False,
                    "paste",
                    self.clipboard,
                    None,
                    None,
                )
            return web.json_response({"ok": True, "method": "coordinate_click"})

        return web.json_response({"ok": False, "error": "No valid UIA runtime_id or coordinates provided"}, status=400)

    # ---------- Input & Prompt Dispatch ----------

    async def handle_input_dispatch(self, request: web.Request) -> web.Response:
        body = await request.json()
        text = str(body.get("text", ""))
        press_enter = bool(body.get("press_enter", False))
        mode = str(body.get("mode", "paste"))
        hwnd = body.get("hwnd")
        res = await asyncio.to_thread(
            self.input_ctrl.dispatch_text,
            text,
            press_enter,
            mode,
            self.clipboard,
            self.windows,
            int(hwnd) if hwnd else None,
        )
        return web.json_response(res)

    async def handle_input_key(self, request: web.Request) -> web.Response:
        body = await request.json()
        keys = body.get("keys") or []
        hwnd = body.get("hwnd")
        if hwnd:
            await asyncio.to_thread(self.windows.focus_window, int(hwnd))
            await asyncio.sleep(0.04)
        ok = await asyncio.to_thread(self.input_ctrl.send_key_chord, [str(k) for k in keys])
        return web.json_response({"ok": ok, "keys": keys})

    # ---------- Clipboard ----------

    async def handle_clipboard_get(self, _request: web.Request) -> web.Response:
        text = await asyncio.to_thread(self.clipboard.get_text)
        return web.json_response({"ok": True, "text": text, "history": self.clipboard.history()})

    async def handle_clipboard_set(self, request: web.Request) -> web.Response:
        body = await request.json()
        text = str(body.get("text", ""))
        ok = await asyncio.to_thread(self.clipboard.set_text, text, "mobile")
        return web.json_response({"ok": ok, "length": len(text), "history": self.clipboard.history()})

    # ---------- Terminals ----------

    async def handle_terminals_list(self, _request: web.Request) -> web.Response:
        return web.json_response({"ok": True, "terminals": self.terminals.list_sessions()})

    async def handle_terminal_create(self, request: web.Request) -> web.Response:
        body = await request.json() if request.can_read_body else {}
        shell = str(body.get("shell", "powershell"))
        cwd = body.get("cwd")
        cols = int(body.get("cols", 80))
        rows = int(body.get("rows", 24))
        try:
            sess = self.terminals.create(
                shell=shell,
                cwd=str(cwd) if cwd else None,
                cols=cols,
                rows=rows,
                loop=asyncio.get_running_loop(),
            )
            return web.json_response({"ok": True, "terminal": sess.to_dict()})
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def handle_terminal_delete(self, request: web.Request) -> web.Response:
        sid = request.match_info["id"]
        ok = self.terminals.close_session(sid)
        return web.json_response({"ok": ok, "id": sid})

    # ---------- Files & Editor ----------

    async def handle_files_roots(self, _request: web.Request) -> web.Response:
        roots = await asyncio.to_thread(get_roots)
        return web.json_response({"ok": True, "roots": roots})

    async def handle_files_list(self, request: web.Request) -> web.Response:
        path = request.query.get("path")
        show_hidden = request.query.get("hidden", "0") == "1"
        try:
            data = await asyncio.to_thread(list_directory, path, show_hidden)
            return web.json_response({"ok": True, **data})
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=400)

    async def handle_files_read(self, request: web.Request) -> web.Response:
        path = request.query.get("path", "")
        tail = request.query.get("tail")
        try:
            data = await asyncio.to_thread(read_text_file, path, 1_500_000, int(tail) if tail else None)
            return web.json_response({"ok": True, **data})
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=400)

    async def handle_files_write(self, request: web.Request) -> web.Response:
        body = await request.json()
        path = str(body.get("path", ""))
        content = str(body.get("content", ""))
        try:
            res = await asyncio.to_thread(write_text_file, path, content)
            return web.json_response(res)
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=400)

    async def handle_files_mkdir(self, request: web.Request) -> web.Response:
        body = await request.json()
        try:
            res = await asyncio.to_thread(make_directory, str(body.get("path", "")))
            return web.json_response(res)
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=400)

    async def handle_files_rename(self, request: web.Request) -> web.Response:
        body = await request.json()
        try:
            res = await asyncio.to_thread(rename_path, str(body.get("src", "")), str(body.get("dst", "")))
            return web.json_response(res)
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=400)

    async def handle_files_delete(self, request: web.Request) -> web.Response:
        path = request.query.get("path", "")
        try:
            res = await asyncio.to_thread(delete_path, path)
            return web.json_response(res)
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=400)

    async def handle_files_search(self, request: web.Request) -> web.Response:
        root = request.query.get("path", "")
        q = request.query.get("q", "")
        matches = await asyncio.to_thread(search_files, root, q)
        return web.json_response({"ok": True, "matches": matches})

    async def handle_files_upload(self, request: web.Request) -> web.Response:
        dest_dir = Path(request.query.get("dir", str(Path.home()))).expanduser().resolve()
        filename = request.query.get("name", "")
        if not dest_dir.exists():
            dest_dir.mkdir(parents=True, exist_ok=True)

        if request.content_type.startswith("multipart/"):
            reader = await request.multipart()
            field = await reader.next()
            if field is None:
                return web.json_response({"ok": False, "error": "Empty multipart upload"}, status=400)
            fname = os.path.basename(filename or field.filename or "upload.bin")
            out_path = dest_dir / fname
            total = 0
            with out_path.open("wb") as f:
                while True:
                    chunk = await field.read_chunk(65536)
                    if not chunk:
                        break
                    f.write(chunk)
                    total += len(chunk)
            return web.json_response({"ok": True, "path": str(out_path), "bytes": total})
        else:
            fname = os.path.basename(filename or "upload.bin")
            out_path = dest_dir / fname
            raw = await request.read()
            out_path.write_bytes(raw)
            return web.json_response({"ok": True, "path": str(out_path), "bytes": len(raw)})

    async def handle_files_download(self, request: web.Request) -> web.StreamResponse:
        path = Path(request.query.get("path", "")).expanduser().resolve()
        if not path.is_file():
            return web.json_response({"ok": False, "error": "File not found"}, status=404)
        return web.FileResponse(
            path=path,
            headers={"Content-Disposition": f'attachment; filename="{path.name}"'},
        )

    # ---------- Git Bridge ----------

    async def handle_git_status(self, request: web.Request) -> web.Response:
        path = request.query.get("path", "")
        res = await asyncio.to_thread(git_status, path)
        return web.json_response(res)

    async def handle_git_diff(self, request: web.Request) -> web.Response:
        path = request.query.get("path", "")
        file_rel = request.query.get("file")
        staged = request.query.get("staged", "0") == "1"
        res = await asyncio.to_thread(git_diff, path, file_rel, staged)
        return web.json_response(res)

    async def handle_git_action(self, request: web.Request) -> web.Response:
        body = await request.json()
        res = await asyncio.to_thread(
            git_action,
            str(body.get("path", "")),
            str(body.get("action", "")),
            str(body.get("message", "")),
            body.get("files"),
        )
        return web.json_response(res)

    # ---------- WebSocket: Visual Stage (/ws/stage) ----------

    async def handle_ws_stage(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=4 * 1024 * 1024)
        await ws.prepare(request)

        state: dict[str, Any] = {
            "mode": "desktop",
            "hwnd": None,
            "fps": 24,
            "quality": 84,
            "format": "jpeg",
            "max_width": 1440,
            "roi": None,
            "paused": False,
            "force_keyframe": True,
        }
        last_hash: str | None = None
        last_blank_warned = False
        last_window_warned: str | None = None
        config_ready = asyncio.Event()
        wake_event = asyncio.Event()
        frame_seq = 0
        acked_frame_seq = 0
        last_sent_ts = 0.0
        last_motion_ts = 0.0
        crisp_pending = False
        client_supports_ack = False

        async def stream_loop() -> None:
            nonlocal last_hash, last_blank_warned, last_window_warned
            nonlocal frame_seq, last_sent_ts, last_motion_ts, crisp_pending
            try:
                await asyncio.wait_for(config_ready.wait(), timeout=0.25)
            except asyncio.TimeoutError:
                pass
            while not ws.closed:
                t_start = time.monotonic()
                wake_event.clear()
                if state["paused"]:
                    await asyncio.sleep(0.15)
                    continue

                # Zero-bufferbloat backpressure: cap in-flight frames at 2 when client sends frame_ack
                if client_supports_ack and (frame_seq - acked_frame_seq) >= 2 and (t_start - last_sent_ts) < 0.35:
                    try:
                        await asyncio.wait_for(wake_event.wait(), timeout=0.04)
                    except asyncio.TimeoutError:
                        pass
                    continue

                try:
                    force = state["force_keyframe"]
                    state["force_keyframe"] = False

                    # Auto-send a pixel-perfect lossless PNG still frame 220ms after motion settles!
                    send_crisp_still = (
                        not force
                        and crisp_pending
                        and (t_start - last_motion_ts) >= 0.22
                        and str(state["format"]).lower() != "webp"
                        and int(state["quality"]) >= 72
                    )
                    effective_fmt = "png" if send_crisp_still else str(state["format"])
                    effective_last_hash = None if (force or send_crisp_still) else last_hash

                    res = await asyncio.to_thread(
                        self.capturer.capture_frame,
                        state["mode"],
                        state["hwnd"],
                        state["roi"],
                        int(state["max_width"]),
                        int(state["quality"]),
                        effective_fmt,
                        effective_last_hash,
                        True,
                        False,
                    )
                    changed_from_prev = (last_hash is None) or (res.frame_hash != last_hash)
                    last_hash = res.frame_hash

                    if last_window_warned is not None:
                        last_window_warned = None
                        await ws.send_json({"type": "window_state", "state": "ok", "hwnd": state["hwnd"]})

                    if res.is_blank and not last_blank_warned:
                        last_blank_warned = True
                        await ws.send_json(
                            {
                                "type": "session_warning",
                                "code": "blank_frame",
                                "message": "Windows returned a blank frame. On a VPS without an active RDP/console session, tap Activate Live Console Display below, or use Shell & Forge which work 24/7.",
                            }
                        )
                    elif not res.is_blank and last_blank_warned:
                        last_blank_warned = False
                        await ws.send_json({"type": "session_warning", "code": "ok", "message": ""})

                    if not res.unchanged and res.image_bytes:
                        frame_seq += 1
                        res.meta["frame_id"] = frame_seq
                        res.meta["crisp"] = send_crisp_still
                        header = json.dumps(res.meta).encode("utf-8")
                        packet = struct.pack(">I", len(header)) + header + res.image_bytes
                        await ws.send_bytes(packet)
                        now_sent = time.monotonic()
                        last_sent_ts = now_sent
                        if send_crisp_still and not changed_from_prev:
                            crisp_pending = False
                        else:
                            last_motion_ts = now_sent
                            crisp_pending = not res.is_blank
                except WindowUnavailableError as werr:
                    # Do NOT flip silently to desktop aspect ratio; notify client cleanly!
                    if last_window_warned != werr.reason and not ws.closed:
                        last_window_warned = werr.reason
                        await ws.send_json(
                            {
                                "type": "window_state",
                                "state": werr.reason,
                                "hwnd": state["hwnd"],
                                "message": str(werr),
                            }
                        )
                    await asyncio.sleep(0.35)
                except Exception as exc:
                    if not ws.closed:
                        await ws.send_json({"type": "error", "message": str(exc)})
                    await asyncio.sleep(0.4)

                fps = max(1, min(30, int(state["fps"])))
                elapsed = time.monotonic() - t_start
                sleep_for = max(0.004, (1.0 / fps) - elapsed)
                try:
                    await asyncio.wait_for(wake_event.wait(), timeout=sleep_for)
                except asyncio.TimeoutError:
                    pass

        async def notify_focused_input() -> None:
            try:
                await asyncio.sleep(0.06)
                info = await self.uia.focused_input()
                if not ws.closed and info.get("ok"):
                    await ws.send_json(
                        {
                            "type": "focused_input",
                            "editable": bool(info.get("editable", False)),
                            "role": info.get("role", ""),
                            "name": info.get("name", ""),
                        }
                    )
            except Exception:
                pass

        producer = asyncio.create_task(stream_loop())
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try:
                    ev = json.loads(msg.data)
                except json.JSONDecodeError:
                    continue

                etype = ev.get("type")
                active_hwnd = state["hwnd"] if state["mode"] == "window" else None
                if etype == "frame_ack":
                    client_supports_ack = True
                    fid = int(ev.get("id", 0))
                    if fid > acked_frame_seq:
                        acked_frame_seq = fid
                    wake_event.set()
                elif etype == "config":
                    for k in ("mode", "hwnd", "fps", "quality", "format", "max_width", "roi", "paused"):
                        if k in ev:
                            state[k] = ev[k]
                    state["force_keyframe"] = True
                    last_hash = None
                    config_ready.set()
                    wake_event.set()
                elif etype == "mouse_move":
                    cx, cy = await asyncio.to_thread(self.input_ctrl.move_abs, int(ev["x"]), int(ev["y"]))
                    if "seq" in ev:
                        await ws.send_json({"type": "cursor_ack", "x": cx, "y": cy, "seq": int(ev["seq"])})
                    wake_event.set()
                elif etype == "mouse_rel":
                    cx, cy = await asyncio.to_thread(self.input_ctrl.move_rel, int(ev["dx"]), int(ev["dy"]))
                    await ws.send_json(
                        {"type": "cursor_ack", "x": cx, "y": cy, "seq": int(ev.get("seq", 0))}
                    )
                    wake_event.set()
                elif etype == "mouse_button":
                    await asyncio.to_thread(
                        self.input_ctrl.mouse_button,
                        str(ev.get("button", "left")),
                        bool(ev.get("down", True)),
                    )
                    wake_event.set()
                elif etype == "pointer_down":
                    cx, cy = await asyncio.to_thread(
                        self.input_ctrl.pointer_down,
                        ev.get("x"),
                        ev.get("y"),
                        str(ev.get("button", "left")),
                        self.windows,
                        active_hwnd,
                    )
                    if "seq" in ev:
                        await ws.send_json({"type": "cursor_ack", "x": cx, "y": cy, "seq": int(ev["seq"])})
                    wake_event.set()
                elif etype == "pointer_up":
                    cx, cy = await asyncio.to_thread(
                        self.input_ctrl.pointer_up,
                        ev.get("x"),
                        ev.get("y"),
                        str(ev.get("button", "left")),
                    )
                    state["force_keyframe"] = True
                    if "seq" in ev:
                        await ws.send_json({"type": "cursor_ack", "x": cx, "y": cy, "seq": int(ev["seq"])})
                    wake_event.set()
                elif etype == "mouse_click":
                    cx, cy = await asyncio.to_thread(
                        self.input_ctrl.click,
                        ev.get("x"),
                        ev.get("y"),
                        str(ev.get("button", "left")),
                        int(ev.get("count", 1)),
                        self.windows,
                        active_hwnd,
                    )
                    state["force_keyframe"] = True
                    if "seq" in ev:
                        await ws.send_json({"type": "cursor_ack", "x": cx, "y": cy, "seq": int(ev["seq"])})
                    wake_event.set()
                    if bool(ev.get("check_input", False)) and str(ev.get("button", "left")) == "left":
                        asyncio.create_task(notify_focused_input())
                elif etype == "scroll":
                    await asyncio.to_thread(
                        self.input_ctrl.scroll,
                        int(ev.get("dx", 0)),
                        int(ev.get("dy", 0)),
                        ev.get("x"),
                        ev.get("y"),
                        self.windows,
                        active_hwnd,
                    )
                    state["force_keyframe"] = True
                    wake_event.set()
                elif etype == "key":
                    keys = ev.get("keys") or []
                    await asyncio.to_thread(self.input_ctrl.send_key_chord, [str(k) for k in keys])
                    state["force_keyframe"] = True
                    wake_event.set()
                elif etype == "text":
                    await asyncio.to_thread(self.input_ctrl.type_unicode, str(ev.get("text", "")))
                    state["force_keyframe"] = True
                    wake_event.set()
                elif etype == "ime_patch":
                    backspaces = max(0, min(64, int(ev.get("backspaces", 0))))
                    insert_text = str(ev.get("text", ""))
                    if active_hwnd:
                        await asyncio.to_thread(self.input_ctrl.ensure_window_foreground, self.windows, active_hwnd)
                    for _ in range(backspaces):
                        await asyncio.to_thread(self.input_ctrl.send_key_chord, ["backspace"])
                    if insert_text:
                        await asyncio.to_thread(self.input_ctrl.type_unicode, insert_text)
                    state["force_keyframe"] = True
                    wake_event.set()
                elif etype == "ping":
                    await ws.send_json({"type": "pong", "ts": ev.get("ts", time.time())})
        finally:
            producer.cancel()
            try:
                await producer
            except asyncio.CancelledError:
                pass
        return ws

    # ---------- WebSocket: ConPTY Terminal (/ws/terminal/{id}) ----------

    async def handle_ws_terminal(self, request: web.Request) -> web.WebSocketResponse:
        sid = request.match_info["id"]
        sess = self.terminals.get(sid)
        if not sess:
            return web.json_response({"ok": False, "error": "Terminal session not found"}, status=404)

        ws = web.WebSocketResponse(heartbeat=20.0)
        await ws.prepare(request)

        # Send buffered scrollback immediately so reconnecting phones restore context seamlessly
        replay = sess.get_replay_text()
        if replay:
            await ws.send_json({"type": "replay", "data": replay})

        queue = sess.subscribe()

        async def pump_output() -> None:
            while not ws.closed:
                kind, payload = await queue.get()
                if kind == "output":
                    text = payload.decode("utf-8", errors="replace") if isinstance(payload, bytes) else str(payload)
                    await ws.send_json({"type": "output", "data": text})
                elif kind == "exit":
                    await ws.send_json({"type": "exit", "code": payload})

        out_task = asyncio.create_task(pump_output())
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try:
                    ev = json.loads(msg.data)
                except json.JSONDecodeError:
                    continue
                etype = ev.get("type")
                if etype == "input":
                    sess.write(str(ev.get("data", "")))
                elif etype == "resize":
                    sess.resize(int(ev.get("cols", 80)), int(ev.get("rows", 24)))
                elif etype == "ping":
                    await ws.send_json({"type": "pong", "ts": ev.get("ts", time.time())})
        finally:
            sess.unsubscribe(queue)
            out_task.cancel()
            try:
                await out_task
            except asyncio.CancelledError:
                pass
        return ws

    # ---------- WebSocket: Live Events & Telemetry (/ws/events) ----------

    async def handle_ws_events(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=20.0)
        await ws.prepare(request)
        self._event_clients.add(ws)
        try:
            # Send immediate telemetry snapshot upon connect
            snap = await asyncio.to_thread(self.telemetry.snapshot)
            await ws.send_json({"type": "telemetry", "data": snap})
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    try:
                        ev = json.loads(msg.data)
                        if ev.get("type") == "ping":
                            await ws.send_json({"type": "pong", "ts": ev.get("ts", time.time())})
                    except json.JSONDecodeError:
                        pass
        finally:
            self._event_clients.discard(ws)
        return ws
