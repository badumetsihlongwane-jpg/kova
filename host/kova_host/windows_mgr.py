"""Windows desktop window enumeration, focus management, and Mobile-Fit reflow."""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import os
import threading
import time
from typing import Any

from .desktop_attach import ensure_input_desktop

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32
try:
    dwmapi = ctypes.windll.dwmapi
except OSError:
    dwmapi = None

GWL_EXSTYLE = -20
WS_EX_TOOLWINDOW = 0x00000080
WS_EX_APPWINDOW = 0x00040000
GW_OWNER = 4

SW_HIDE = 0
SW_SHOWNORMAL = 1
SW_SHOWMINIMIZED = 2
SW_SHOWMAXIMIZED = 3
SW_RESTORE = 9

SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010
SWP_SHOWWINDOW = 0x0040

WM_CLOSE = 0x0010
DWMWA_EXTENDED_FRAME_BOUNDS = 9
DWMWA_CLOAKED = 14
SPI_GETWORKAREA = 48
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


class RECT(ctypes.Structure):
    _fields_ = [
        ("left", wt.LONG),
        ("top", wt.LONG),
        ("right", wt.LONG),
        ("bottom", wt.LONG),
    ]


class WINDOWPLACEMENT(ctypes.Structure):
    _fields_ = [
        ("length", wt.UINT),
        ("flags", wt.UINT),
        ("showCmd", wt.UINT),
        ("ptMinPosition", wt.POINT),
        ("ptMaxPosition", wt.POINT),
        ("rcNormalPosition", RECT),
    ]


WNDENUMPROC = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)

user32.EnumWindows.argtypes = [WNDENUMPROC, wt.LPARAM]
user32.EnumWindows.restype = wt.BOOL
user32.IsWindowVisible.argtypes = [wt.HWND]
user32.IsWindowVisible.restype = wt.BOOL
user32.IsWindow.argtypes = [wt.HWND]
user32.IsWindow.restype = wt.BOOL
user32.IsIconic.argtypes = [wt.HWND]
user32.IsIconic.restype = wt.BOOL
user32.IsZoomed.argtypes = [wt.HWND]
user32.IsZoomed.restype = wt.BOOL
user32.GetWindowTextLengthW.argtypes = [wt.HWND]
user32.GetWindowTextLengthW.restype = ctypes.c_int
user32.GetWindowTextW.argtypes = [wt.HWND, wt.LPWSTR, ctypes.c_int]
user32.GetWindowTextW.restype = ctypes.c_int
user32.GetClassNameW.argtypes = [wt.HWND, wt.LPWSTR, ctypes.c_int]
user32.GetClassNameW.restype = ctypes.c_int
user32.GetWindowRect.argtypes = [wt.HWND, ctypes.POINTER(RECT)]
user32.GetWindowRect.restype = wt.BOOL
user32.GetForegroundWindow.argtypes = []
user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowThreadProcessId.argtypes = [wt.HWND, ctypes.POINTER(wt.DWORD)]
user32.GetWindowThreadProcessId.restype = wt.DWORD
user32.SetWindowPos.argtypes = [wt.HWND, wt.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wt.UINT]
user32.SetWindowPos.restype = wt.BOOL
user32.ShowWindow.argtypes = [wt.HWND, ctypes.c_int]
user32.ShowWindow.restype = wt.BOOL
user32.SetForegroundWindow.argtypes = [wt.HWND]
user32.SetForegroundWindow.restype = wt.BOOL
user32.BringWindowToTop.argtypes = [wt.HWND]
user32.BringWindowToTop.restype = wt.BOOL
user32.PostMessageW.argtypes = [wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM]
user32.PostMessageW.restype = wt.BOOL
user32.GetWindow.argtypes = [wt.HWND, wt.UINT]
user32.GetWindow.restype = wt.HWND
user32.GetWindowLongW.argtypes = [wt.HWND, ctypes.c_int]
user32.GetWindowLongW.restype = wt.LONG
user32.AttachThreadInput.argtypes = [wt.DWORD, wt.DWORD, wt.BOOL]
user32.AttachThreadInput.restype = wt.BOOL
user32.GetWindowPlacement.argtypes = [wt.HWND, ctypes.POINTER(WINDOWPLACEMENT)]
user32.GetWindowPlacement.restype = wt.BOOL

kernel32.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
kernel32.OpenProcess.restype = wt.HANDLE
kernel32.CloseHandle.argtypes = [wt.HANDLE]
kernel32.CloseHandle.restype = wt.BOOL
kernel32.QueryFullProcessImageNameW.argtypes = [wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD)]
kernel32.QueryFullProcessImageNameW.restype = wt.BOOL
kernel32.GetCurrentThreadId.argtypes = []
kernel32.GetCurrentThreadId.restype = wt.DWORD

IGNORED_CLASSES = {
    "Progman",
    "WorkerW",
    "Shell_TrayWnd",
    "Shell_SecondaryTrayWnd",
    "Windows.UI.Core.CoreWindow",
    "ApplicationFrameInputSinkWindow",
}


def _is_cloaked(hwnd: int) -> bool:
    if dwmapi is None:
        return False
    cloaked = wt.DWORD(0)
    hr = dwmapi.DwmGetWindowAttribute(
        wt.HWND(hwnd),
        wt.DWORD(DWMWA_CLOAKED),
        ctypes.byref(cloaked),
        ctypes.sizeof(cloaked),
    )
    return hr == 0 and cloaked.value != 0


def get_window_rect(hwnd: int) -> tuple[int, int, int, int]:
    rect = RECT()
    if dwmapi is not None:
        hr = dwmapi.DwmGetWindowAttribute(
            wt.HWND(hwnd),
            wt.DWORD(DWMWA_EXTENDED_FRAME_BOUNDS),
            ctypes.byref(rect),
            ctypes.sizeof(rect),
        )
        if hr == 0 and (rect.right - rect.left) > 0 and (rect.bottom - rect.top) > 0:
            return rect.left, rect.top, rect.right, rect.bottom
    if user32.GetWindowRect(wt.HWND(hwnd), ctypes.byref(rect)):
        return rect.left, rect.top, rect.right, rect.bottom
    return 0, 0, 0, 0


def _get_process_name(pid: int) -> str:
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(512)
        size = wt.DWORD(512)
        if kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)):
            return os.path.basename(buf.value)
        return ""
    finally:
        kernel32.CloseHandle(handle)


def _get_work_area() -> tuple[int, int, int, int]:
    rect = RECT()
    if user32.SystemParametersInfoW(SPI_GETWORKAREA, 0, ctypes.byref(rect), 0):
        return rect.left, rect.top, rect.right, rect.bottom
    w = user32.GetSystemMetrics(0)
    h = user32.GetSystemMetrics(1)
    return 0, 0, w, h


class WindowManager:
    """Manages desktop windows and remembers pre-Mobile-Fit desktop geometry."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._saved_geometry: dict[int, dict[str, Any]] = {}

    def list_windows(self) -> list[dict[str, Any]]:
        ensure_input_desktop()
        fg = int(user32.GetForegroundWindow() or 0)
        results: list[dict[str, Any]] = []

        def callback(hwnd: int, _lparam: int) -> bool:
            if not user32.IsWindowVisible(hwnd):
                return True
            length = user32.GetWindowTextLengthW(hwnd)
            if length <= 0:
                return True

            ex_style = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
            if (ex_style & WS_EX_TOOLWINDOW) and not (ex_style & WS_EX_APPWINDOW):
                return True

            if user32.GetWindow(hwnd, GW_OWNER) and not (ex_style & WS_EX_APPWINDOW):
                return True

            if _is_cloaked(hwnd):
                return True

            cls_buf = ctypes.create_unicode_buffer(128)
            user32.GetClassNameW(hwnd, cls_buf, 128)
            cls_name = cls_buf.value
            if cls_name in IGNORED_CLASSES:
                return True

            title_buf = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, title_buf, length + 1)
            title = title_buf.value.strip()
            if not title:
                return True

            minimized = bool(user32.IsIconic(hwnd))
            maximized = bool(user32.IsZoomed(hwnd))
            left, top, right, bottom = get_window_rect(hwnd)
            width = max(0, right - left)
            height = max(0, bottom - top)
            if not minimized and (width < 40 or height < 30):
                return True

            pid = wt.DWORD(0)
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            proc = _get_process_name(int(pid.value))

            with self._lock:
                is_mobile_fit = int(hwnd) in self._saved_geometry

            results.append(
                {
                    "hwnd": int(hwnd),
                    "title": title,
                    "process": proc,
                    "pid": int(pid.value),
                    "class_name": cls_name,
                    "x": left,
                    "y": top,
                    "width": width,
                    "height": height,
                    "minimized": minimized,
                    "maximized": maximized,
                    "focused": int(hwnd) == fg,
                    "mobile_fit": is_mobile_fit,
                }
            )
            return True

        cb = WNDENUMPROC(callback)
        user32.EnumWindows(cb, 0)
        # Foreground window first, then active non-minimized, then minimized
        results.sort(key=lambda w: (not w["focused"], w["minimized"]))
        return results

    def get_window(self, hwnd: int) -> dict[str, Any] | None:
        for w in self.list_windows():
            if w["hwnd"] == hwnd:
                return w
        return None

    def focus_window(self, hwnd: int) -> bool:
        ensure_input_desktop()
        if not user32.IsWindow(hwnd):
            return False
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
        fg = user32.GetForegroundWindow()
        cur_tid = kernel32.GetCurrentThreadId()
        fg_tid = user32.GetWindowThreadProcessId(fg, None) if fg else 0
        target_tid = user32.GetWindowThreadProcessId(hwnd, None)

        attached_fg = False
        attached_target = False
        try:
            if fg_tid and fg_tid != cur_tid:
                attached_fg = bool(user32.AttachThreadInput(cur_tid, fg_tid, True))
            if target_tid and target_tid != cur_tid:
                attached_target = bool(user32.AttachThreadInput(cur_tid, target_tid, True))
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
        finally:
            if attached_target:
                user32.AttachThreadInput(cur_tid, target_tid, False)
            if attached_fg:
                user32.AttachThreadInput(cur_tid, fg_tid, False)
        return True

    def perform_action(
        self,
        hwnd: int,
        action: str,
        width: int | None = None,
        height: int | None = None,
    ) -> dict[str, Any]:
        ensure_input_desktop()
        if not user32.IsWindow(hwnd):
            return {"ok": False, "error": "Window no longer exists"}

        if action == "focus":
            ok = self.focus_window(hwnd)
            return {"ok": ok, "action": action, "hwnd": hwnd}

        if action == "minimize":
            user32.ShowWindow(hwnd, SW_SHOWMINIMIZED)
            return {"ok": True, "action": action, "hwnd": hwnd}

        if action == "maximize":
            self.focus_window(hwnd)
            user32.ShowWindow(hwnd, SW_SHOWMAXIMIZED)
            with self._lock:
                self._saved_geometry.pop(hwnd, None)
            return {"ok": True, "action": action, "hwnd": hwnd}

        if action == "restore":
            user32.ShowWindow(hwnd, SW_RESTORE)
            self.focus_window(hwnd)
            return {"ok": True, "action": action, "hwnd": hwnd}

        if action == "close":
            user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
            with self._lock:
                self._saved_geometry.pop(hwnd, None)
            return {"ok": True, "action": action, "hwnd": hwnd}

        if action == "mobile_fit":
            return self.mobile_fit_window(hwnd, width, height)

        if action == "restore_fit":
            return self.restore_fit_window(hwnd)

        return {"ok": False, "error": f"Unknown action: {action}"}

    def mobile_fit_window(
        self,
        hwnd: int,
        target_width: int | None = None,
        target_height: int | None = None,
    ) -> dict[str, Any]:
        """Reflow a Windows desktop window to a mobile portrait column for responsive rendering."""
        if not user32.IsWindow(hwnd):
            return {"ok": False, "error": "Window not found"}

        with self._lock:
            if hwnd not in self._saved_geometry:
                wp = WINDOWPLACEMENT()
                wp.length = ctypes.sizeof(WINDOWPLACEMENT)
                user32.GetWindowPlacement(hwnd, ctypes.byref(wp))
                rect = RECT()
                user32.GetWindowRect(hwnd, ctypes.byref(rect))
                self._saved_geometry[hwnd] = {
                    "left": rect.left,
                    "top": rect.top,
                    "width": max(200, rect.right - rect.left),
                    "height": max(200, rect.bottom - rect.top),
                    "maximized": bool(user32.IsZoomed(hwnd)),
                    "normal_left": wp.rcNormalPosition.left,
                    "normal_top": wp.rcNormalPosition.top,
                    "normal_width": max(200, wp.rcNormalPosition.right - wp.rcNormalPosition.left),
                    "normal_height": max(200, wp.rcNormalPosition.bottom - wp.rcNormalPosition.top),
                }

        # Un-maximize / un-minimize first so SetWindowPos takes effect
        if user32.IsIconic(hwnd) or user32.IsZoomed(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
            time.sleep(0.06)

        wa_l, wa_t, wa_r, wa_b = _get_work_area()
        wa_w = max(360, wa_r - wa_l)
        wa_h = max(480, wa_b - wa_t)

        # Compute optimal portrait aspect ratio fitting inside current monitor work area
        desired_w = target_width or min(520, wa_w)
        desired_h = target_height or min(int(desired_w * 1.85), wa_h)

        # Scale down proportionally if requested dimensions exceed the monitor work area
        scale = min(1.0, wa_w / max(1, desired_w), wa_h / max(1, desired_h))
        final_w = max(320, int(desired_w * scale))
        final_h = max(420, int(desired_h * scale))

        pos_x = wa_l + max(0, (wa_w - final_w) // 2)
        pos_y = wa_t + max(0, (wa_h - final_h) // 2)

        self.focus_window(hwnd)
        ok = bool(
            user32.SetWindowPos(
                hwnd,
                0,
                pos_x,
                pos_y,
                final_w,
                final_h,
                SWP_NOZORDER | SWP_SHOWWINDOW,
            )
        )
        time.sleep(0.05)
        l, t, r, b = get_window_rect(hwnd)
        return {
            "ok": ok,
            "action": "mobile_fit",
            "hwnd": hwnd,
            "mobile_fit": True,
            "rect": {"x": l, "y": t, "width": r - l, "height": b - t},
        }

    def restore_fit_window(self, hwnd: int) -> dict[str, Any]:
        if not user32.IsWindow(hwnd):
            return {"ok": False, "error": "Window not found"}

        with self._lock:
            saved = self._saved_geometry.pop(hwnd, None)

        if not saved:
            user32.ShowWindow(hwnd, SW_SHOWMAXIMIZED)
            return {"ok": True, "action": "restore_fit", "hwnd": hwnd, "mobile_fit": False}

        user32.SetWindowPos(
            hwnd,
            0,
            saved["normal_left"],
            saved["normal_top"],
            saved["normal_width"],
            saved["normal_height"],
            SWP_NOZORDER | SWP_SHOWWINDOW,
        )
        if saved.get("maximized"):
            user32.ShowWindow(hwnd, SW_SHOWMAXIMIZED)
        self.focus_window(hwnd)
        return {"ok": True, "action": "restore_fit", "hwnd": hwnd, "mobile_fit": False}
