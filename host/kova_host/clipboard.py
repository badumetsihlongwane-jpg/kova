"""Win32 Unicode clipboard manager with history ring and change detection."""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import threading
import time
from collections import deque
from typing import Any

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002

kernel32.GlobalAlloc.argtypes = [wt.UINT, ctypes.c_size_t]
kernel32.GlobalAlloc.restype = wt.HGLOBAL
kernel32.GlobalLock.argtypes = [wt.HGLOBAL]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalUnlock.argtypes = [wt.HGLOBAL]
kernel32.GlobalUnlock.restype = wt.BOOL
kernel32.GlobalFree.argtypes = [wt.HGLOBAL]
kernel32.GlobalFree.restype = wt.HGLOBAL

user32.OpenClipboard.argtypes = [wt.HWND]
user32.OpenClipboard.restype = wt.BOOL
user32.CloseClipboard.argtypes = []
user32.CloseClipboard.restype = wt.BOOL
user32.EmptyClipboard.argtypes = []
user32.EmptyClipboard.restype = wt.BOOL
user32.GetClipboardData.argtypes = [wt.UINT]
user32.GetClipboardData.restype = wt.HANDLE
user32.SetClipboardData.argtypes = [wt.UINT, wt.HANDLE]
user32.SetClipboardData.restype = wt.HANDLE
user32.IsClipboardFormatAvailable.argtypes = [wt.UINT]
user32.IsClipboardFormatAvailable.restype = wt.BOOL
user32.GetClipboardSequenceNumber.argtypes = []
user32.GetClipboardSequenceNumber.restype = wt.DWORD


class ClipboardManager:
    def __init__(self, max_history: int = 25) -> None:
        self._lock = threading.Lock()
        self._history: deque[dict[str, Any]] = deque(maxlen=max_history)
        self._last_seq: int = int(user32.GetClipboardSequenceNumber())
        self._last_text: str = ""

    def _open_with_retry(self, retries: int = 8) -> bool:
        for _ in range(retries):
            if user32.OpenClipboard(None):
                return True
            time.sleep(0.015)
        return False

    def get_text(self) -> str:
        with self._lock:
            if not user32.IsClipboardFormatAvailable(CF_UNICODETEXT):
                return ""
            if not self._open_with_retry():
                return self._last_text
            try:
                handle = user32.GetClipboardData(CF_UNICODETEXT)
                if not handle:
                    return ""
                ptr = kernel32.GlobalLock(handle)
                if not ptr:
                    return ""
                try:
                    text = ctypes.wstring_at(ptr)
                finally:
                    kernel32.GlobalUnlock(handle)
                self._record_unlocked(text, source="windows")
                self._last_seq = int(user32.GetClipboardSequenceNumber())
                return text
            finally:
                user32.CloseClipboard()

    def set_text(self, text: str, source: str = "mobile") -> bool:
        with self._lock:
            encoded = text.encode("utf-16-le") + b"\x00\x00"
            if not self._open_with_retry():
                return False
            try:
                user32.EmptyClipboard()
                hmem = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(encoded))
                if not hmem:
                    return False
                ptr = kernel32.GlobalLock(hmem)
                if not ptr:
                    kernel32.GlobalFree(hmem)
                    return False
                try:
                    ctypes.memmove(ptr, encoded, len(encoded))
                finally:
                    kernel32.GlobalUnlock(hmem)
                if not user32.SetClipboardData(CF_UNICODETEXT, hmem):
                    kernel32.GlobalFree(hmem)
                    return False
                self._record_unlocked(text, source=source)
                self._last_seq = int(user32.GetClipboardSequenceNumber())
                return True
            finally:
                user32.CloseClipboard()

    def poll_change(self) -> dict[str, Any] | None:
        """Return new clipboard entry if Windows clipboard sequence changed externally."""
        seq = int(user32.GetClipboardSequenceNumber())
        if seq == self._last_seq:
            return None
        text = self.get_text()
        if text:
            return {
                "text": text,
                "seq": seq,
                "timestamp": time.time(),
            }
        return None

    def _record_unlocked(self, text: str, source: str) -> None:
        if not text:
            return
        if text == self._last_text and self._history:
            return
        self._last_text = text
        # Deduplicate most recent identical entry
        if self._history and self._history[0]["text"] == text:
            return
        self._history.appendleft(
            {
                "text": text[:4000],
                "length": len(text),
                "source": source,
                "timestamp": time.time(),
            }
        )

    def history(self) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._history)
