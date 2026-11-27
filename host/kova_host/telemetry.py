"""Zero-dependency Win32 system telemetry, session detection, and display metrics."""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import getpass
import os
import platform
import shutil
import socket
import time
from typing import Any

from .config import detect_local_ips

kernel32 = ctypes.windll.kernel32
user32 = ctypes.windll.user32

try:
    wtsapi32 = ctypes.windll.wtsapi32
except OSError:
    wtsapi32 = None


class FILETIME(ctypes.Structure):
    _fields_ = [
        ("dwLowDateTime", wt.DWORD),
        ("dwHighDateTime", wt.DWORD),
    ]


class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [
        ("dwLength", wt.DWORD),
        ("dwMemoryLoad", wt.DWORD),
        ("ullTotalPhys", ctypes.c_uint64),
        ("ullAvailPhys", ctypes.c_uint64),
        ("ullTotalPageFile", ctypes.c_uint64),
        ("ullAvailPageFile", ctypes.c_uint64),
        ("ullTotalVirtual", ctypes.c_uint64),
        ("ullAvailVirtual", ctypes.c_uint64),
        ("ullAvailExtendedVirtual", ctypes.c_uint64),
    ]


def enable_dpi_awareness() -> None:
    """Opt into Per-Monitor V2 DPI awareness so all coordinates & captures are 1:1 physical pixels."""
    try:
        # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except Exception:
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            try:
                user32.SetProcessDPIAware()
            except Exception:
                pass


def _filetime_to_int(ft: FILETIME) -> int:
    return (ft.dwHighDateTime << 32) | ft.dwLowDateTime


class TelemetryCollector:
    """Tracks delta-based CPU load alongside RAM, disks, screen, and RDP/Console session state."""

    def __init__(self) -> None:
        self._last_idle: int = 0
        self._last_kernel: int = 0
        self._last_user: int = 0
        self._last_cpu_pct: float = 0.0
        self._sample_cpu_raw()

    def _sample_cpu_raw(self) -> tuple[int, int, int]:
        idle, kernel, user = FILETIME(), FILETIME(), FILETIME()
        if kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
            i = _filetime_to_int(idle)
            k = _filetime_to_int(kernel)
            u = _filetime_to_int(user)
            if self._last_kernel or self._last_user:
                di = i - self._last_idle
                dk = k - self._last_kernel
                du = u - self._last_user
                total = dk + du
                if total > 0:
                    busy = total - di
                    self._last_cpu_pct = round(max(0.0, min(100.0, (busy / total) * 100.0)), 1)
            self._last_idle, self._last_kernel, self._last_user = i, k, u
            return i, k, u
        return 0, 0, 0

    def cpu_percent(self) -> float:
        self._sample_cpu_raw()
        return self._last_cpu_pct

    def memory_info(self) -> dict[str, Any]:
        mem = MEMORYSTATUSEX()
        mem.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
        if kernel32.GlobalMemoryStatusEx(ctypes.byref(mem)):
            total = mem.ullTotalPhys
            avail = mem.ullAvailPhys
            used = max(0, total - avail)
            return {
                "percent": float(mem.dwMemoryLoad),
                "used_bytes": used,
                "total_bytes": total,
                "available_bytes": avail,
            }
        return {"percent": 0.0, "used_bytes": 0, "total_bytes": 0, "available_bytes": 0}

    def disk_info(self) -> list[dict[str, Any]]:
        disks: list[dict[str, Any]] = []
        bitmask = kernel32.GetLogicalDrives()
        for i in range(26):
            if bitmask & (1 << i):
                letter = f"{chr(65 + i)}:\\"
                dtype = kernel32.GetDriveTypeW(letter)
                # 3 = DRIVE_FIXED, 2 = DRIVE_REMOVABLE
                if dtype in (2, 3):
                    try:
                        usage = shutil.disk_usage(letter)
                        pct = round((usage.used / usage.total) * 100.0, 1) if usage.total else 0.0
                        disks.append(
                            {
                                "drive": letter,
                                "total_bytes": usage.total,
                                "used_bytes": usage.used,
                                "free_bytes": usage.free,
                                "percent": pct,
                            }
                        )
                    except OSError:
                        continue
        return disks

    def screen_info(self) -> dict[str, int]:
        # Virtual screen handles multi-monitor & RDP display adapters cleanly
        vx = user32.GetSystemMetrics(76)  # SM_XVIRTUALSCREEN
        vy = user32.GetSystemMetrics(77)  # SM_YVIRTUALSCREEN
        vw = user32.GetSystemMetrics(78)  # SM_CXVIRTUALSCREEN
        vh = user32.GetSystemMetrics(79)  # SM_CYVIRTUALSCREEN
        pw = user32.GetSystemMetrics(0)   # SM_CXSCREEN
        ph = user32.GetSystemMetrics(1)   # SM_CYSCREEN
        return {
            "primary_width": pw,
            "primary_height": ph,
            "virtual_x": vx,
            "virtual_y": vy,
            "virtual_width": vw or pw,
            "virtual_height": vh or ph,
        }

    def session_info(self) -> dict[str, Any]:
        pid = os.getpid()
        session_id = wt.DWORD(0)
        kernel32.ProcessIdToSessionId(pid, ctypes.byref(session_id))
        sid = int(session_id.value)
        console_sid = int(kernel32.WTSGetActiveConsoleSessionId())
        is_remote = bool(user32.GetSystemMetrics(0x1000))  # SM_REMOTESESSION

        state = "unknown"
        if wtsapi32 is not None:
            buf = ctypes.c_void_p()
            bytes_ret = wt.DWORD(0)
            # WTSConnectState = 8
            if wtsapi32.WTSQuerySessionInformationW(0, sid, 8, ctypes.byref(buf), ctypes.byref(bytes_ret)):
                try:
                    code = ctypes.cast(buf, ctypes.POINTER(ctypes.c_int)).contents.value
                    state_map = {
                        0: "active",
                        1: "connected",
                        2: "connect_query",
                        3: "shadow",
                        4: "disconnected",
                        5: "idle",
                        6: "listen",
                        7: "reset",
                        8: "down",
                        9: "init",
                    }
                    state = state_map.get(code, f"code_{code}")
                finally:
                    wtsapi32.WTSFreeMemory(buf)

        return {
            "session_id": sid,
            "console_session_id": console_sid,
            "is_rdp": is_remote,
            "state": state,
            "interactive_gui_ready": sid != 0 and state in ("active", "connected", "unknown"),
        }

    def snapshot(self) -> dict[str, Any]:
        uptime_ms = kernel32.GetTickCount64()
        return {
            "hostname": socket.gethostname(),
            "username": getpass.getuser(),
            "os": f"Windows {platform.release()} ({platform.version()})",
            "arch": platform.machine(),
            "cpu_count": os.cpu_count() or 1,
            "cpu_percent": self.cpu_percent(),
            "memory": self.memory_info(),
            "disks": self.disk_info(),
            "screen": self.screen_info(),
            "session": self.session_info(),
            "uptime_seconds": int(uptime_ms // 1000),
            "ips": detect_local_ips(),
            "timestamp": time.time(),
        }
