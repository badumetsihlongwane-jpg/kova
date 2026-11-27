"""Win32 active input desktop attachment helper (handles hidden desktops, services, UAC, and RDP)."""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import threading

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

UOI_NAME = 2
DESKTOP_ALL = 0x01FF  # READOBJECTS | CREATEWINDOW | HOOKCONTROL | WRITEOBJECTS | SWITCHDESKTOP | GENERIC

user32.OpenInputDesktop.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
user32.OpenInputDesktop.restype = wt.HANDLE
user32.SetThreadDesktop.argtypes = [wt.HANDLE]
user32.SetThreadDesktop.restype = wt.BOOL
user32.CloseDesktop.argtypes = [wt.HANDLE]
user32.CloseDesktop.restype = wt.BOOL
user32.GetThreadDesktop.argtypes = [wt.DWORD]
user32.GetThreadDesktop.restype = wt.HANDLE
user32.GetUserObjectInformationW.argtypes = [wt.HANDLE, ctypes.c_int, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD)]
user32.GetUserObjectInformationW.restype = wt.BOOL
kernel32.GetCurrentThreadId.argtypes = []
kernel32.GetCurrentThreadId.restype = wt.DWORD
kernel32.CloseHandle.argtypes = [wt.HANDLE]
kernel32.CloseHandle.restype = wt.BOOL
kernel32.CreateProcessW.argtypes = [
    wt.LPCWSTR,
    wt.LPWSTR,
    ctypes.c_void_p,
    ctypes.c_void_p,
    wt.BOOL,
    wt.DWORD,
    ctypes.c_void_p,
    wt.LPCWSTR,
    ctypes.c_void_p,
    ctypes.c_void_p,
]
kernel32.CreateProcessW.restype = wt.BOOL

_tls = threading.local()
_dpi_initialized = False


def enable_per_monitor_dpi() -> None:
    """Opt process into Per-Monitor V2 DPI awareness so all Win32/DWM/Input/UIA coords are 1:1 physical pixels."""
    global _dpi_initialized
    if _dpi_initialized:
        return
    _dpi_initialized = True
    try:
        user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
        user32.SetProcessDpiAwarenessContext.restype = wt.BOOL
        if user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4)):
            return
    except Exception:
        pass
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
        return
    except Exception:
        pass
    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass


enable_per_monitor_dpi()


def _desktop_name(hdesk: int) -> str:
    if not hdesk:
        return ""
    buf = ctypes.create_unicode_buffer(128)
    needed = wt.DWORD(0)
    if user32.GetUserObjectInformationW(hdesk, UOI_NAME, buf, 256, ctypes.byref(needed)):
        return buf.value
    return ""


def ensure_input_desktop() -> bool:
    """Attach the current worker thread to the live Windows input desktop (e.g. WinSta0\\Default)."""
    try:
        cur_desk = user32.GetThreadDesktop(kernel32.GetCurrentThreadId())
        cur_name = _desktop_name(cur_desk)

        inp_desk = user32.OpenInputDesktop(0, False, DESKTOP_ALL)
        if not inp_desk:
            return cur_name.lower() == "default"

        inp_name = _desktop_name(inp_desk)
        if cur_name and inp_name and cur_name.lower() == inp_name.lower():
            user32.CloseDesktop(inp_desk)
            return True

        ok = bool(user32.SetThreadDesktop(inp_desk))
        if ok:
            prev = getattr(_tls, "owned_desktop", None)
            if prev:
                user32.CloseDesktop(prev)
            _tls.owned_desktop = inp_desk
            return True

        user32.CloseDesktop(inp_desk)
        return False
    except Exception:
        return False


class _STARTUPINFOW(ctypes.Structure):
    _fields_ = [
        ("cb", wt.DWORD),
        ("lpReserved", wt.LPWSTR),
        ("lpDesktop", wt.LPWSTR),
        ("lpTitle", wt.LPWSTR),
        ("dwX", wt.DWORD),
        ("dwY", wt.DWORD),
        ("dwXSize", wt.DWORD),
        ("dwYSize", wt.DWORD),
        ("dwXCountChars", wt.DWORD),
        ("dwYCountChars", wt.DWORD),
        ("dwFillAttribute", wt.DWORD),
        ("dwFlags", wt.DWORD),
        ("wShowWindow", wt.WORD),
        ("cbReserved2", wt.WORD),
        ("lpReserved2", ctypes.c_void_p),
        ("hStdInput", wt.HANDLE),
        ("hStdOutput", wt.HANDLE),
        ("hStdError", wt.HANDLE),
    ]


class _PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("hProcess", wt.HANDLE),
        ("hThread", wt.HANDLE),
        ("dwProcessId", wt.DWORD),
        ("dwThreadId", wt.DWORD),
    ]


def launch_on_interactive_desktop(target: str, args: list[str] | None = None, cwd: str | None = None) -> int:
    """Spawn a Windows GUI app or URI explicitly on WinSta0\\Default so it always appears on screen."""
    import os
    import subprocess

    args = args or []
    safe_cwd = cwd if (cwd and os.path.isdir(cwd)) else None

    if target.startswith(("http://", "https://", "ms-settings:")):
        cmdline = subprocess.list2cmdline(["cmd.exe", "/c", "start", "", target])
    elif target.lower() in ("code", "cursor", "antigravity"):
        cmdline = subprocess.list2cmdline(["cmd.exe", "/c", target, *[str(a) for a in args]])
    else:
        cmdline = subprocess.list2cmdline([target, *[str(a) for a in args]])

    si = _STARTUPINFOW()
    si.cb = ctypes.sizeof(_STARTUPINFOW)
    si.lpDesktop = "WinSta0\\Default"
    pi = _PROCESS_INFORMATION()

    CREATE_NEW_PROCESS_GROUP = 0x00000200
    ok = kernel32.CreateProcessW(
        None,
        ctypes.create_unicode_buffer(cmdline),
        None,
        None,
        False,
        CREATE_NEW_PROCESS_GROUP,
        None,
        safe_cwd,
        ctypes.byref(si),
        ctypes.byref(pi),
    )
    if not ok:
        err = ctypes.get_last_error() or kernel32.GetLastError()
        raise OSError(f"CreateProcessW failed (winerror={err}) for: {target}")

    pid = int(pi.dwProcessId)
    if pi.hThread:
        kernel32.CloseHandle(pi.hThread)
    if pi.hProcess:
        kernel32.CloseHandle(pi.hProcess)
    return pid
