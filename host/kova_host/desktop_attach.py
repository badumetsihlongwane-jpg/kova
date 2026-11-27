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

_tls = threading.local()


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
