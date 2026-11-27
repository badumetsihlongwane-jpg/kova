"""64-bit safe Win32 SendInput & SetCursorPos controller engineered for zero-drift mobile precision."""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import time
from typing import TYPE_CHECKING

from .desktop_attach import ensure_input_desktop

if TYPE_CHECKING:
    from .clipboard import ClipboardManager
    from .windows_mgr import WindowManager

user32 = ctypes.windll.user32

ULONG_PTR = ctypes.c_uint64 if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_uint32

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1
INPUT_HARDWARE = 2

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000
MOUSEEVENTF_VIRTUALDESK = 0x4000
MOUSEEVENTF_ABSOLUTE = 0x8000

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wt.LONG),
        ("dy", wt.LONG),
        ("mouseData", wt.DWORD),
        ("dwFlags", wt.DWORD),
        ("time", wt.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wt.WORD),
        ("wScan", wt.WORD),
        ("dwFlags", wt.DWORD),
        ("time", wt.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wt.DWORD),
        ("wParamL", wt.WORD),
        ("wParamH", wt.WORD),
    ]


class _INPUTUNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [
        ("type", wt.DWORD),
        ("u", _INPUTUNION),
    ]


user32.SendInput.argtypes = [wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SendInput.restype = wt.UINT
user32.GetCursorPos.argtypes = [ctypes.POINTER(wt.POINT)]
user32.GetCursorPos.restype = wt.BOOL
user32.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
user32.SetCursorPos.restype = wt.BOOL
user32.GetForegroundWindow.argtypes = []
user32.GetForegroundWindow.restype = wt.HWND

VK_MAP: dict[str, int] = {
    "backspace": 0x08,
    "bs": 0x08,
    "tab": 0x09,
    "enter": 0x0D,
    "return": 0x0D,
    "shift": 0x10,
    "ctrl": 0x11,
    "control": 0x11,
    "alt": 0x12,
    "menu": 0x12,
    "pause": 0x13,
    "capslock": 0x14,
    "esc": 0x1B,
    "escape": 0x1B,
    "space": 0x20,
    "pgup": 0x21,
    "pageup": 0x21,
    "pgdn": 0x22,
    "pagedown": 0x22,
    "end": 0x23,
    "home": 0x24,
    "left": 0x25,
    "up": 0x26,
    "right": 0x27,
    "down": 0x28,
    "printscreen": 0x2C,
    "insert": 0x2D,
    "ins": 0x2D,
    "delete": 0x2E,
    "del": 0x2E,
    "win": 0x5B,
    "lwin": 0x5B,
    "meta": 0x5B,
    "rwin": 0x5C,
    "apps": 0x5D,
    "f1": 0x70,
    "f2": 0x71,
    "f3": 0x72,
    "f4": 0x73,
    "f5": 0x74,
    "f6": 0x75,
    "f7": 0x76,
    "f8": 0x77,
    "f9": 0x78,
    "f10": 0x79,
    "f11": 0x7A,
    "f12": 0x7B,
    ";": 0xBA,
    "=": 0xBB,
    ",": 0xBC,
    "-": 0xBD,
    ".": 0xBE,
    "/": 0xBF,
    "`": 0xC0,
    "[": 0xDB,
    "\\": 0xDC,
    "]": 0xDD,
    "'": 0xDE,
}
for _c in "abcdefghijklmnopqrstuvwxyz":
    VK_MAP[_c] = ord(_c.upper())
for _d in "0123456789":
    VK_MAP[_d] = ord(_d)

EXTENDED_VKS = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C}


def _send_inputs(items: list[INPUT]) -> int:
    if not items:
        return 0
    ensure_input_desktop()
    arr = (INPUT * len(items))(*items)
    return int(user32.SendInput(len(items), arr, ctypes.sizeof(INPUT)))


def _virtual_bounds() -> tuple[int, int, int, int]:
    vx = int(user32.GetSystemMetrics(76))
    vy = int(user32.GetSystemMetrics(77))
    vw = max(1, int(user32.GetSystemMetrics(78) or user32.GetSystemMetrics(0) or 1))
    vh = max(1, int(user32.GetSystemMetrics(79) or user32.GetSystemMetrics(1) or 1))
    return vx, vy, vw, vh


def _clamp_to_virtual_screen(x: int, y: int) -> tuple[int, int]:
    vx, vy, vw, vh = _virtual_bounds()
    cx = max(vx, min(vx + vw - 1, int(x)))
    cy = max(vy, min(vy + vh - 1, int(y)))
    return cx, cy


class InputController:
    """Deterministic physical-pixel mouse/touch & keyboard input controller.

    Uses SetCursorPos (exact physical virtual-screen coordinates under Per-Monitor V2 DPI)
    plus absolute SendInput so Windows's non-linear Enhance Pointer Precision curve NEVER
    amplifies or distorts fast finger movements.
    """

    def get_cursor_pos(self) -> tuple[int, int]:
        ensure_input_desktop()
        pt = wt.POINT()
        if user32.GetCursorPos(ctypes.byref(pt)):
            return int(pt.x), int(pt.y)
        return 0, 0

    def move_abs(self, x: int, y: int) -> tuple[int, int]:
        ensure_input_desktop()
        cx, cy = _clamp_to_virtual_screen(x, y)
        # Emit pixel-centered absolute SendInput (Win32 kernel decodes floor(n * dim / 65536),
        # so ((p - origin) * 65536 + 32768) // dim hits the exact center of pixel p with zero
        # off-by-one snapback, waking hover states, tooltips, Electron & DirectInput apps).
        vx, vy, vw, vh = _virtual_bounds()
        nx = max(0, min(65535, ((cx - vx) * 65536 + 32768) // max(1, vw)))
        ny = max(0, min(65535, ((cy - vy) * 65536 + 32768) // max(1, vh)))
        inp = INPUT(
            type=INPUT_MOUSE,
            mi=MOUSEINPUT(
                dx=nx,
                dy=ny,
                mouseData=0,
                dwFlags=MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
                time=0,
                dwExtraInfo=0,
            ),
        )
        _send_inputs([inp])
        # Final SetCursorPos guarantees exact physical-pixel placement even across mixed-DPI monitors
        user32.SetCursorPos(cx, cy)
        return cx, cy

    def move_rel(self, dx: int, dy: int) -> tuple[int, int]:
        """Deterministic 1:1 relative movement bypassing Windows's ballistic mouse acceleration."""
        ensure_input_desktop()
        cur_x, cur_y = self.get_cursor_pos()
        return self.move_abs(cur_x + int(dx), cur_y + int(dy))

    def ensure_window_foreground(self, window_mgr: WindowManager | None, hwnd: int | None) -> None:
        if not hwnd or window_mgr is None:
            return
        ensure_input_desktop()
        fg = int(user32.GetForegroundWindow() or 0)
        if fg != int(hwnd):
            window_mgr.focus_window(int(hwnd))
            time.sleep(0.03)

    def mouse_button(self, button: str, down: bool) -> None:
        b = button.lower()
        if b == "left":
            flag = MOUSEEVENTF_LEFTDOWN if down else MOUSEEVENTF_LEFTUP
        elif b == "right":
            flag = MOUSEEVENTF_RIGHTDOWN if down else MOUSEEVENTF_RIGHTUP
        elif b == "middle":
            flag = MOUSEEVENTF_MIDDLEDOWN if down else MOUSEEVENTF_MIDDLEUP
        else:
            return
        inp = INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(dx=0, dy=0, mouseData=0, dwFlags=flag, time=0, dwExtraInfo=0))
        _send_inputs([inp])

    def click(
        self,
        x: int | None = None,
        y: int | None = None,
        button: str = "left",
        count: int = 1,
        window_mgr: WindowManager | None = None,
        hwnd: int | None = None,
    ) -> tuple[int, int]:
        ensure_input_desktop()
        if hwnd and window_mgr is not None:
            self.ensure_window_foreground(window_mgr, hwnd)
        if x is not None and y is not None:
            cx, cy = self.move_abs(int(x), int(y))
            time.sleep(0.008)
        else:
            cx, cy = self.get_cursor_pos()
        for i in range(max(1, min(3, count))):
            self.mouse_button(button, True)
            time.sleep(0.012)
            self.mouse_button(button, False)
            if i + 1 < count:
                time.sleep(0.045)
        return cx, cy

    def pointer_down(
        self,
        x: int | None = None,
        y: int | None = None,
        button: str = "left",
        window_mgr: WindowManager | None = None,
        hwnd: int | None = None,
    ) -> tuple[int, int]:
        ensure_input_desktop()
        if hwnd and window_mgr is not None:
            self.ensure_window_foreground(window_mgr, hwnd)
        if x is not None and y is not None:
            cx, cy = self.move_abs(int(x), int(y))
            time.sleep(0.008)
        else:
            cx, cy = self.get_cursor_pos()
        self.mouse_button(button, True)
        return cx, cy

    def pointer_up(
        self,
        x: int | None = None,
        y: int | None = None,
        button: str = "left",
    ) -> tuple[int, int]:
        ensure_input_desktop()
        if x is not None and y is not None:
            cx, cy = self.move_abs(int(x), int(y))
        else:
            cx, cy = self.get_cursor_pos()
        self.mouse_button(button, False)
        return cx, cy

    def scroll(
        self,
        dx: int = 0,
        dy: int = 0,
        x: int | None = None,
        y: int | None = None,
        window_mgr: WindowManager | None = None,
        hwnd: int | None = None,
    ) -> None:
        ensure_input_desktop()
        if hwnd and window_mgr is not None:
            self.ensure_window_foreground(window_mgr, hwnd)
        if x is not None and y is not None:
            cx, cy = _clamp_to_virtual_screen(int(x), int(y))
            user32.SetCursorPos(cx, cy)
        items: list[INPUT] = []
        if dy:
            items.append(
                INPUT(
                    type=INPUT_MOUSE,
                    mi=MOUSEINPUT(
                        dx=0,
                        dy=0,
                        mouseData=ctypes.c_uint32(int(dy)).value,
                        dwFlags=MOUSEEVENTF_WHEEL,
                        time=0,
                        dwExtraInfo=0,
                    ),
                )
            )
        if dx:
            items.append(
                INPUT(
                    type=INPUT_MOUSE,
                    mi=MOUSEINPUT(
                        dx=0,
                        dy=0,
                        mouseData=ctypes.c_uint32(int(dx)).value,
                        dwFlags=MOUSEEVENTF_HWHEEL,
                        time=0,
                        dwExtraInfo=0,
                    ),
                )
            )
        _send_inputs(items)

    def send_key_chord(self, keys: list[str]) -> bool:
        vks: list[int] = []
        for k in keys:
            vk = VK_MAP.get(k.strip().lower())
            if vk is not None:
                vks.append(vk)
        if not vks:
            return False

        downs: list[INPUT] = []
        ups: list[INPUT] = []
        for vk in vks:
            ext = KEYEVENTF_EXTENDEDKEY if vk in EXTENDED_VKS else 0
            downs.append(
                INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(wVk=vk, wScan=0, dwFlags=ext, time=0, dwExtraInfo=0))
            )
        for vk in reversed(vks):
            ext = KEYEVENTF_EXTENDEDKEY if vk in EXTENDED_VKS else 0
            ups.append(
                INPUT(
                    type=INPUT_KEYBOARD,
                    ki=KEYBDINPUT(wVk=vk, wScan=0, dwFlags=ext | KEYEVENTF_KEYUP, time=0, dwExtraInfo=0),
                )
            )
        _send_inputs(downs)
        time.sleep(0.02)
        _send_inputs(ups)
        return True

    def type_unicode(self, text: str) -> None:
        if not text:
            return
        items: list[INPUT] = []
        for ch in text:
            if ch == "\n":
                items.append(INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(wVk=0x0D, wScan=0, dwFlags=0, time=0, dwExtraInfo=0)))
                items.append(
                    INPUT(
                        type=INPUT_KEYBOARD,
                        ki=KEYBDINPUT(wVk=0x0D, wScan=0, dwFlags=KEYEVENTF_KEYUP, time=0, dwExtraInfo=0),
                    )
                )
                continue
            if ch == "\r":
                continue
            utf16 = ch.encode("utf-16-le")
            for i in range(0, len(utf16), 2):
                unit = int.from_bytes(utf16[i : i + 2], "little")
                items.append(
                    INPUT(
                        type=INPUT_KEYBOARD,
                        ki=KEYBDINPUT(wVk=0, wScan=unit, dwFlags=KEYEVENTF_UNICODE, time=0, dwExtraInfo=0),
                    )
                )
                items.append(
                    INPUT(
                        type=INPUT_KEYBOARD,
                        ki=KEYBDINPUT(
                            wVk=0,
                            wScan=unit,
                            dwFlags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP,
                            time=0,
                            dwExtraInfo=0,
                        ),
                    )
                )
        chunk = 128
        for idx in range(0, len(items), chunk):
            _send_inputs(items[idx : idx + chunk])
            if len(items) > chunk:
                time.sleep(0.008)

    def dispatch_text(
        self,
        text: str,
        press_enter: bool = False,
        mode: str = "paste",
        clipboard_mgr: ClipboardManager | None = None,
        window_mgr: WindowManager | None = None,
        hwnd: int | None = None,
    ) -> dict[str, object]:
        """Smart mobile-to-Windows text / AI prompt injection."""
        if hwnd and window_mgr is not None:
            window_mgr.focus_window(hwnd)
            time.sleep(0.06)

        if mode == "paste" and clipboard_mgr is not None:
            clipboard_mgr.set_text(text, source="mobile-dispatch")
            time.sleep(0.03)
            self.send_key_chord(["ctrl", "v"])
            time.sleep(0.04)
        else:
            self.type_unicode(text)

        if press_enter:
            time.sleep(0.04)
            self.send_key_chord(["enter"])

        return {"ok": True, "length": len(text), "mode": mode, "press_enter": press_enter}
