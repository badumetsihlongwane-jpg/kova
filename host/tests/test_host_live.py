"""Live Windows integration & unit test suite for Kova Host Agent."""

from __future__ import annotations

import asyncio
import json
import struct
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer

from kova_host.auth import build_pairing_uri, render_qr_terminal, verify_token
from kova_host.capture import ScreenCapturer, WindowUnavailableError
from kova_host.clipboard import ClipboardManager
from kova_host.config import create_ssl_context, ensure_tls_certificate, load_or_create_config
from kova_host.input_ctrl import InputController
from kova_host.server import KovaServer
from kova_host.telemetry import TelemetryCollector
from kova_host.uia_bridge import UiaBridge
from kova_host.windows_mgr import WindowManager


def test_config_tls_and_pairing(tmp_path: Path) -> None:
    cfg = load_or_create_config(config_dir=tmp_path, port_override=9876, public_host_override="100.64.0.10")
    assert cfg.port == 9876
    assert len(cfg.token) >= 32
    assert verify_token(cfg.token, cfg.token)
    assert not verify_token("wrong-token", cfg.token)

    fp = ensure_tls_certificate(tmp_path / "cert.pem", tmp_path / "key.pem", extra_hosts=["100.64.0.10"])
    assert len(fp) == 64
    ssl_ctx = create_ssl_context(tmp_path / "cert.pem", tmp_path / "key.pem")
    assert ssl_ctx is not None

    uri = build_pairing_uri("100.64.0.10", 9876, cfg.token, fp, cfg.name, True)
    assert uri.startswith("kova://connect?")
    assert "fp=" in uri
    qr = render_qr_terminal(uri)
    assert len(qr.splitlines()) >= 10


def test_telemetry_and_windows() -> None:
    snap = TelemetryCollector().snapshot()
    assert snap["hostname"]
    assert snap["memory"]["total_bytes"] > 0
    assert snap["screen"]["primary_width"] > 0
    assert snap["screen"]["primary_height"] > 0
    assert len(snap["disks"]) >= 1

    wins = WindowManager().list_windows()
    assert isinstance(wins, list)


def test_screen_capture_and_roi() -> None:
    cap = ScreenCapturer()
    res1 = cap.capture_frame(mode="desktop", max_width=480, quality=70, fmt="webp")
    res2 = cap.capture_frame(mode="desktop", max_width=480, quality=70, fmt="webp")
    assert len(res1.image_bytes) > 100
    assert res1.meta["frame_width"] <= 480
    assert res1.meta["mime"] == "image/webp"
    # Even integer output dimensions & exact aspect stability across consecutive captures
    assert res1.meta["frame_width"] % 2 == 0
    assert res1.meta["frame_height"] % 2 == 0
    assert res1.meta["frame_width"] == res2.meta["frame_width"]
    assert res1.meta["frame_height"] == res2.meta["frame_height"]

    # Server-side ROI crop should produce a valid cropped frame
    roi_res = cap.capture_frame(
        mode="desktop",
        roi={"x": 0.1, "y": 0.1, "w": 0.5, "h": 0.5},
        max_width=480,
        quality=75,
        fmt="jpeg",
    )
    assert len(roi_res.image_bytes) > 100
    assert roi_res.meta["mime"] == "image/jpeg"
    assert "roi" in roi_res.meta

    # Invalid window handle raises clean WindowUnavailableError instead of silently flipping to desktop
    with pytest.raises(WindowUnavailableError):
        cap.capture_frame(mode="window", hwnd=999999999, allow_fallback_to_desktop=False)


def test_precision_mouse_zero_drift() -> None:
    """Verify deterministic physical-pixel movement across slow micro-steps & fast sweeps (no OS accel drift)."""
    ctrl = InputController()
    orig_x, orig_y = ctrl.get_cursor_pos()
    try:
        # Move to known anchor
        ax, ay = ctrl.move_abs(180, 220)
        assert (ax, ay) == (180, 220)
        assert ctrl.get_cursor_pos() == (180, 220)

        # 10 tiny 1-pixel precision steps -> must travel EXACTLY +10px, +10px
        for _ in range(10):
            ctrl.move_rel(1, 1)
        assert ctrl.get_cursor_pos() == (190, 230)

        # Fast large single-step jump (+140px, +180px) -> must NOT be amplified by Windows Enhance Pointer Precision!
        ctrl.move_rel(140, 180)
        assert ctrl.get_cursor_pos() == (330, 410)

        # Reverse fast diagonal sweep back to exact origin anchor
        ctrl.move_rel(-150, -190)
        assert ctrl.get_cursor_pos() == (180, 220)
    finally:
        ctrl.move_abs(orig_x, orig_y)


def test_clipboard_roundtrip() -> None:
    cm = ClipboardManager()
    prev = cm.get_text()
    try:
        marker = "kova-clipboard-test-✓-12345"
        assert cm.set_text(marker)
        assert cm.get_text() == marker
        assert any(item["text"] == marker for item in cm.history())
    finally:
        if prev:
            cm.set_text(prev, source="restore")


@pytest.mark.asyncio
async def test_uia_bridge_compiles_and_inspects(tmp_path: Path) -> None:
    bridge = UiaBridge(bin_dir=tmp_path / "bin")
    exe = bridge.ensure_compiled()
    assert exe is not None and exe.exists(), f"UIA C# helper compile failed: {bridge._compile_error}"
    try:
        pong = await bridge.ping()
        assert pong.get("ok") is True and pong.get("pong") is True
        focused = await bridge.focused_input()
        assert "editable" in focused
        wins = WindowManager().list_windows()
        target_hwnd = wins[0]["hwnd"] if wins else 0
        res = await bridge.inspect(hwnd=target_hwnd, max_nodes=80, budget_ms=1000)
        assert "elements" in res
        # On interactive Windows desktops (outside headless GitHub Actions runners), inspect succeeds directly
        import os
        if target_hwnd != 0 and not os.environ.get("CI"):
            assert res.get("ok") is True
    finally:
        await bridge.close()


@pytest.mark.asyncio
async def test_full_server_rest_files_conpty_and_websockets(tmp_path: Path) -> None:
    cfg = load_or_create_config(config_dir=tmp_path / "cfg", tls_override=False)
    server = KovaServer(cfg, cert_fingerprint="")
    headers = {"Authorization": f"Bearer {cfg.token}"}

    async with TestClient(TestServer(server.app)) as client:
        # 1. Public health + auth enforcement
        r = await client.get("/api/health")
        assert r.status == 200
        assert (await r.json())["ok"] is True

        r_unauth = await client.get("/api/system/info")
        assert r_unauth.status == 401

        r_info = await client.get("/api/system/info", headers=headers)
        assert r_info.status == 200
        info = await r_info.json()
        assert info["hostname"]

        # 2. Native file manager CRUD + search + upload/download
        work_dir = tmp_path / "workspace"
        r_mk = await client.post("/api/files/mkdir", headers=headers, json={"path": str(work_dir)})
        assert r_mk.status == 200

        sample_file = work_dir / "hello.py"
        r_wr = await client.post(
            "/api/files/write",
            headers=headers,
            json={"path": str(sample_file), "content": "print('hello from kova')\n"},
        )
        assert r_wr.status == 200

        r_rd = await client.get("/api/files/read", headers=headers, params={"path": str(sample_file)})
        assert r_rd.status == 200
        rd_json = await r_rd.json()
        assert rd_json["language"] == "python"
        assert "hello from kova" in rd_json["content"]

        r_ls = await client.get("/api/files/list", headers=headers, params={"path": str(work_dir)})
        assert r_ls.status == 200
        assert any(e["name"] == "hello.py" for e in (await r_ls.json())["entries"])

        r_srch = await client.get("/api/files/search", headers=headers, params={"path": str(work_dir), "q": "hello"})
        assert len((await r_srch.json())["matches"]) == 1

        # 3. True Windows ConPTY terminal over WebSocket!
        r_term = await client.post(
            "/api/terminals",
            headers=headers,
            json={"shell": "cmd", "cwd": str(work_dir), "cols": 80, "rows": 24},
        )
        assert r_term.status == 200
        term = (await r_term.json())["terminal"]
        assert term["alive"] is True
        tid = term["id"]

        async with client.ws_connect(f"/ws/terminal/{tid}?token={cfg.token}") as ws:
            await ws.send_json({"type": "resize", "cols": 72, "rows": 20})
            await ws.send_json({"type": "input", "data": "echo KOVA_CONPTY_OK\r\n"})
            collected = ""
            for _ in range(40):
                try:
                    msg = await asyncio.wait_for(ws.receive_json(), timeout=0.5)
                    if msg.get("type") in ("output", "replay"):
                        collected += msg.get("data", "")
                        if "KOVA_CONPTY_OK" in collected:
                            break
                except asyncio.TimeoutError:
                    continue
            assert "KOVA_CONPTY_OK" in collected, f"ConPTY output did not contain marker; got: {collected!r}"

        r_del = await client.delete(f"/api/terminals/{tid}", headers=headers)
        assert r_del.status == 200

        # 4. Visual Stage binary frame stream + sequence-numbered cursor ack over WebSocket!
        async with client.ws_connect(f"/ws/stage?token={cfg.token}") as ws:
            await ws.send_json({"type": "config", "mode": "desktop", "fps": 5, "max_width": 480, "quality": 65})
            await ws.send_json({"type": "mouse_move", "x": 140, "y": 160, "seq": 42})
            got_frame = False
            got_ack = False
            for _ in range(15):
                msg = await asyncio.wait_for(ws.receive(), timeout=3.0)
                if msg.type.name == "BINARY":
                    raw = msg.data
                    hdr_len = struct.unpack(">I", raw[:4])[0]
                    meta = json.loads(raw[4 : 4 + hdr_len].decode("utf-8"))
                    img_bytes = raw[4 + hdr_len :]
                    assert meta["frame_width"] <= 480
                    assert len(img_bytes) > 100
                    got_frame = True
                elif msg.type.name == "TEXT":
                    ev = json.loads(msg.data)
                    if ev.get("type") == "cursor_ack" and ev.get("seq") == 42:
                        assert ev["x"] == 140 and ev["y"] == 160
                        got_ack = True
                if got_frame and got_ack:
                    break
            assert got_frame, "Did not receive binary stage frame over WebSocket"
            assert got_ack, "Did not receive exact sequence-numbered cursor_ack"

        # 5. Live events WebSocket (telemetry push + ping/pong)
        async with client.ws_connect(f"/ws/events?token={cfg.token}") as ws:
            got_telemetry = False
            for _ in range(5):
                ev = await asyncio.wait_for(ws.receive_json(), timeout=3.0)
                if ev.get("type") == "telemetry":
                    got_telemetry = True
                    break
            assert got_telemetry, "Did not receive initial telemetry event"
            await ws.send_json({"type": "ping", "ts": 123.0})
            while True:
                ev = await asyncio.wait_for(ws.receive_json(), timeout=3.0)
                if ev.get("type") == "pong":
                    assert ev["ts"] == 123.0
                    break
