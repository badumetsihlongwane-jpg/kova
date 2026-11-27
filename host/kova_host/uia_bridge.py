"""Windows UI Automation bridge powered by a persistent auto-compiled .NET helper daemon."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

from .config import default_config_dir

DOTNET_4_DIR = Path(r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319")
CSC_EXE = DOTNET_4_DIR / "csc.exe"
WPF_DIR = DOTNET_4_DIR / "WPF"


def _source_path() -> Path:
    # Support both source tree and PyInstaller frozen bundle
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        candidate = Path(sys._MEIPASS) / "kova_host" / "uia_helper.cs"
        if candidate.exists():
            return candidate
        alt = Path(sys.executable).resolve().parent / "_internal" / "kova_host" / "uia_helper.cs"
        if alt.exists():
            return alt
    return Path(__file__).resolve().parent / "uia_helper.cs"


class UiaBridge:
    """Compiles uia_helper.cs once via built-in .NET csc.exe and runs it as a low-latency JSON daemon."""

    def __init__(self, bin_dir: Path | None = None) -> None:
        self._bin_dir = bin_dir or (default_config_dir() / "bin")
        self._exe_path: Path | None = None
        self._proc: asyncio.subprocess.Process | None = None
        self._lock = asyncio.Lock()
        self._compile_error: str | None = None

    def ensure_compiled(self) -> Path | None:
        if self._exe_path and self._exe_path.exists():
            return self._exe_path

        # Check prebuilt helper next to frozen executable first
        if getattr(sys, "frozen", False):
            sibling = Path(sys.executable).resolve().parent / "uia_helper.exe"
            if sibling.exists():
                self._exe_path = sibling
                return sibling

        src = _source_path()
        if not src.exists():
            self._compile_error = f"uia_helper.cs missing at {src}"
            return None
        if not CSC_EXE.exists():
            self._compile_error = ".NET Framework 4.x csc.exe not found"
            return None

        self._bin_dir.mkdir(parents=True, exist_ok=True)
        src_hash = hashlib.sha256(src.read_bytes()).hexdigest()[:12]
        out_exe = self._bin_dir / f"kova_uia_{src_hash}.exe"
        if out_exe.exists():
            self._exe_path = out_exe
            return out_exe

        refs = [
            WPF_DIR / "UIAutomationClient.dll",
            WPF_DIR / "UIAutomationTypes.dll",
            WPF_DIR / "WindowsBase.dll",
            DOTNET_4_DIR / "System.Web.Extensions.dll",
        ]
        cmd = [
            str(CSC_EXE),
            "/nologo",
            "/optimize+",
            "/target:exe",
            f"/out:{out_exe}",
        ]
        for ref in refs:
            if ref.exists():
                cmd.append(f"/r:{ref}")
        cmd.append(str(src))

        res = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if res.returncode != 0 or not out_exe.exists():
            self._compile_error = (res.stdout + "\n" + res.stderr).strip()
            return None

        self._exe_path = out_exe
        return out_exe

    async def _ensure_daemon(self) -> asyncio.subprocess.Process | None:
        if self._proc is not None and self._proc.returncode is None:
            return self._proc

        exe = await asyncio.to_thread(self.ensure_compiled)
        if not exe:
            return None

        creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self._proc = await asyncio.create_subprocess_exec(
            str(exe),
            "--daemon",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            creationflags=creation_flags,
        )
        return self._proc

    async def _request(self, payload: dict[str, Any], timeout: float = 3.0) -> dict[str, Any]:
        async with self._lock:
            proc = await self._ensure_daemon()
            if proc is None or proc.stdin is None or proc.stdout is None:
                return {
                    "ok": False,
                    "error": self._compile_error or "UIA helper unavailable",
                    "elements": [],
                }

            try:
                line = (json.dumps(payload) + "\n").encode("utf-8")
                proc.stdin.write(line)
                await proc.stdin.drain()
                raw = await asyncio.wait_for(proc.stdout.readline(), timeout=timeout)
                if not raw:
                    await self.close()
                    return {"ok": False, "error": "UIA daemon exited unexpectedly", "elements": []}
                return json.loads(raw.decode("utf-8", errors="replace"))
            except asyncio.TimeoutError:
                await self.close()
                return {"ok": False, "error": "UIA inspection timed out", "elements": []}
            except Exception as exc:
                await self.close()
                return {"ok": False, "error": str(exc), "elements": []}

    async def ping(self) -> dict[str, Any]:
        return await self._request({"cmd": "ping"}, timeout=10.0)

    async def inspect(self, hwnd: int = 0, max_nodes: int = 160, budget_ms: int = 650) -> dict[str, Any]:
        return await self._request(
            {"cmd": "inspect", "hwnd": int(hwnd), "max_nodes": max_nodes, "budget_ms": budget_ms},
            timeout=10.0,
        )

    async def action(self, hwnd: int, runtime_id: str, action: str, value: str = "") -> dict[str, Any]:
        return await self._request(
            {
                "cmd": "action",
                "hwnd": int(hwnd),
                "runtime_id": runtime_id,
                "action": action,
                "value": value,
            },
            timeout=6.0,
        )

    async def close(self) -> None:
        if self._proc is not None:
            try:
                if self._proc.returncode is None:
                    self._proc.terminate()
                    try:
                        await asyncio.wait_for(self._proc.wait(), timeout=1.0)
                    except asyncio.TimeoutError:
                        self._proc.kill()
            except OSError:
                pass
            self._proc = None
