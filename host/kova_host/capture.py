"""Adaptive Windows GDI + PrintWindow capture engine with ROI cropping and frame diffing."""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import hashlib
import io
import time
from dataclasses import dataclass
from typing import Any

from PIL import Image

from .windows_mgr import get_window_rect

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32

SRCCOPY = 0x00CC0020
CAPTUREBLT = 0x40000000
DIB_RGB_COLORS = 0
BI_RGB = 0
PW_CLIENTONLY = 0x00000001
PW_RENDERFULLCONTENT = 0x00000002
CURSOR_SHOWING = 0x00000001
DI_NORMAL = 0x0003


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wt.DWORD),
        ("biWidth", wt.LONG),
        ("biHeight", wt.LONG),
        ("biPlanes", wt.WORD),
        ("biBitCount", wt.WORD),
        ("biCompression", wt.DWORD),
        ("biSizeImage", wt.DWORD),
        ("biXPelsPerMeter", wt.LONG),
        ("biYPelsPerMeter", wt.LONG),
        ("biClrUsed", wt.DWORD),
        ("biClrImportant", wt.DWORD),
    ]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wt.DWORD * 3)]


class CURSORINFO(ctypes.Structure):
    _fields_ = [
        ("cbSize", wt.DWORD),
        ("flags", wt.DWORD),
        ("hCursor", wt.HANDLE),
        ("ptScreenPos", wt.POINT),
    ]


class ICONINFO(ctypes.Structure):
    _fields_ = [
        ("fIcon", wt.BOOL),
        ("xHotspot", wt.DWORD),
        ("yHotspot", wt.DWORD),
        ("hbmMask", wt.HBITMAP),
        ("hbmColor", wt.HBITMAP),
    ]


user32.GetDC.argtypes = [wt.HWND]
user32.GetDC.restype = wt.HDC
user32.ReleaseDC.argtypes = [wt.HWND, wt.HDC]
user32.ReleaseDC.restype = ctypes.c_int
user32.PrintWindow.argtypes = [wt.HWND, wt.HDC, wt.UINT]
user32.PrintWindow.restype = wt.BOOL
user32.GetCursorInfo.argtypes = [ctypes.POINTER(CURSORINFO)]
user32.GetCursorInfo.restype = wt.BOOL
user32.GetIconInfo.argtypes = [wt.HANDLE, ctypes.POINTER(ICONINFO)]
user32.GetIconInfo.restype = wt.BOOL
user32.DrawIconEx.argtypes = [
    wt.HDC,
    ctypes.c_int,
    ctypes.c_int,
    wt.HANDLE,
    ctypes.c_int,
    ctypes.c_int,
    wt.UINT,
    wt.HBRUSH,
    wt.UINT,
]
user32.DrawIconEx.restype = wt.BOOL

gdi32.CreateCompatibleDC.argtypes = [wt.HDC]
gdi32.CreateCompatibleDC.restype = wt.HDC
gdi32.DeleteDC.argtypes = [wt.HDC]
gdi32.DeleteDC.restype = wt.BOOL
gdi32.CreateCompatibleBitmap.argtypes = [wt.HDC, ctypes.c_int, ctypes.c_int]
gdi32.CreateCompatibleBitmap.restype = wt.HBITMAP
gdi32.SelectObject.argtypes = [wt.HDC, wt.HGDIOBJ]
gdi32.SelectObject.restype = wt.HGDIOBJ
gdi32.DeleteObject.argtypes = [wt.HGDIOBJ]
gdi32.DeleteObject.restype = wt.BOOL
gdi32.BitBlt.argtypes = [
    wt.HDC,
    ctypes.c_int,
    ctypes.c_int,
    ctypes.c_int,
    ctypes.c_int,
    wt.HDC,
    ctypes.c_int,
    ctypes.c_int,
    wt.DWORD,
]
gdi32.BitBlt.restype = wt.BOOL
gdi32.GetDIBits.argtypes = [
    wt.HDC,
    wt.HBITMAP,
    wt.UINT,
    wt.UINT,
    ctypes.c_void_p,
    ctypes.POINTER(BITMAPINFO),
    wt.UINT,
]
gdi32.GetDIBits.restype = ctypes.c_int


@dataclass
class CaptureResult:
    image_bytes: bytes
    meta: dict[str, Any]
    frame_hash: str
    unchanged: bool = False
    is_blank: bool = False


def _draw_cursor_on_dc(mem_dc: int, origin_x: int, origin_y: int) -> tuple[int, int] | None:
    ci = CURSORINFO()
    ci.cbSize = ctypes.sizeof(CURSORINFO)
    if not user32.GetCursorInfo(ctypes.byref(ci)):
        return None
    cx, cy = int(ci.ptScreenPos.x), int(ci.ptScreenPos.y)
    if (ci.flags & CURSOR_SHOWING) and ci.hCursor:
        ii = ICONINFO()
        hx, hy = 0, 0
        if user32.GetIconInfo(ci.hCursor, ctypes.byref(ii)):
            hx, hy = int(ii.xHotspot), int(ii.yHotspot)
            if ii.hbmMask:
                gdi32.DeleteObject(ii.hbmMask)
            if ii.hbmColor:
                gdi32.DeleteObject(ii.hbmColor)
        user32.DrawIconEx(mem_dc, cx - origin_x - hx, cy - origin_y - hy, ci.hCursor, 0, 0, 0, None, DI_NORMAL)
    return cx, cy


def _extract_pil_from_bitmap(mem_dc: int, hbmp: int, width: int, height: int) -> tuple[Image.Image, bytes]:
    bmi = BITMAPINFO()
    bmi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bmi.bmiHeader.biWidth = width
    bmi.bmiHeader.biHeight = -height  # top-down DIB
    bmi.bmiHeader.biPlanes = 1
    bmi.bmiHeader.biBitCount = 32
    bmi.bmiHeader.biCompression = BI_RGB

    buf_len = width * height * 4
    buf = ctypes.create_string_buffer(buf_len)
    lines = gdi32.GetDIBits(mem_dc, hbmp, 0, height, buf, ctypes.byref(bmi), DIB_RGB_COLORS)
    if lines == 0:
        raise RuntimeError("GetDIBits failed")
    raw = buf.raw
    img = Image.frombuffer("RGB", (width, height), raw, "raw", "BGRX", 0, 1)
    return img, raw


def _is_mostly_black(raw: bytes) -> bool:
    """Fast strided check to detect blank/disconnected RDP black frames."""
    if not raw:
        return True
    step = max(4, len(raw) // 1024)
    sample = raw[::step]
    # Check if every sampled channel is nearly 0 (RGB channels; ignore X byte)
    non_zero = sum(1 for b in sample if b > 4)
    return non_zero < 3


def _fast_hash(raw: bytes) -> str:
    step = max(4, len(raw) // 4096)
    return hashlib.blake2b(raw[::step], digest_size=12).hexdigest()


class ScreenCapturer:
    """Captures full desktop or single window with optional normalized ROI crop."""

    def capture_desktop_raw(
        self,
        crop_rect: tuple[int, int, int, int] | None = None,
        draw_cursor: bool = True,
    ) -> tuple[Image.Image, bytes, dict[str, Any]]:
        vx = user32.GetSystemMetrics(76)
        vy = user32.GetSystemMetrics(77)
        vw = user32.GetSystemMetrics(78) or user32.GetSystemMetrics(0)
        vh = user32.GetSystemMetrics(79) or user32.GetSystemMetrics(1)
        if vw <= 0 or vh <= 0:
            raise RuntimeError("Desktop dimensions unavailable")

        if crop_rect is not None:
            sx, sy, sw, sh = crop_rect
            sx = max(vx, min(vx + vw - 1, sx))
            sy = max(vy, min(vy + vh - 1, sy))
            sw = max(16, min(vx + vw - sx, sw))
            sh = max(16, min(vy + vh - sy, sh))
        else:
            sx, sy, sw, sh = vx, vy, vw, vh

        hdc_screen = user32.GetDC(0)
        if not hdc_screen:
            raise RuntimeError("GetDC(0) failed")
        mem_dc = gdi32.CreateCompatibleDC(hdc_screen)
        hbmp = gdi32.CreateCompatibleBitmap(hdc_screen, sw, sh)
        old = gdi32.SelectObject(mem_dc, hbmp)
        cursor_pos = None
        try:
            ok = gdi32.BitBlt(mem_dc, 0, 0, sw, sh, hdc_screen, sx, sy, SRCCOPY | CAPTUREBLT)
            if not ok:
                gdi32.BitBlt(mem_dc, 0, 0, sw, sh, hdc_screen, sx, sy, SRCCOPY)
            if draw_cursor:
                cursor_pos = _draw_cursor_on_dc(mem_dc, sx, sy)
            img, raw = _extract_pil_from_bitmap(mem_dc, hbmp, sw, sh)
        finally:
            gdi32.SelectObject(mem_dc, old)
            gdi32.DeleteObject(hbmp)
            gdi32.DeleteDC(mem_dc)
            user32.ReleaseDC(0, hdc_screen)

        meta = {
            "source": "desktop",
            "origin_x": sx,
            "origin_y": sy,
            "source_width": sw,
            "source_height": sh,
            "full_x": vx,
            "full_y": vy,
            "full_width": vw,
            "full_height": vh,
            "cursor_x": cursor_pos[0] if cursor_pos else 0,
            "cursor_y": cursor_pos[1] if cursor_pos else 0,
        }
        return img, raw, meta

    def capture_window_raw(self, hwnd: int, draw_cursor: bool = True) -> tuple[Image.Image, bytes, dict[str, Any]]:
        if not user32.IsWindow(hwnd):
            raise RuntimeError("Target window closed")

        left, top, right, bottom = get_window_rect(hwnd)
        w = max(0, right - left)
        h = max(0, bottom - top)
        if w < 16 or h < 16:
            raise RuntimeError("Window is minimized or zero-sized")

        hdc_screen = user32.GetDC(0)
        mem_dc = gdi32.CreateCompatibleDC(hdc_screen)
        hbmp = gdi32.CreateCompatibleBitmap(hdc_screen, w, h)
        old = gdi32.SelectObject(mem_dc, hbmp)
        cursor_pos = None
        source_mode = "window_print"
        try:
            printed = bool(user32.PrintWindow(hwnd, mem_dc, PW_RENDERFULLCONTENT))
            img, raw = _extract_pil_from_bitmap(mem_dc, hbmp, w, h)

            # Fallback to BitBlt desktop crop if PrintWindow returned blank on GPU/MSBDA windows
            if not printed or _is_mostly_black(raw):
                source_mode = "window_bitblt"
                gdi32.BitBlt(mem_dc, 0, 0, w, h, hdc_screen, left, top, SRCCOPY | CAPTUREBLT)
                if draw_cursor:
                    cursor_pos = _draw_cursor_on_dc(mem_dc, left, top)
                img, raw = _extract_pil_from_bitmap(mem_dc, hbmp, w, h)
            elif draw_cursor:
                # Re-overlay live cursor if inside window bounds
                ci = CURSORINFO()
                ci.cbSize = ctypes.sizeof(CURSORINFO)
                if user32.GetCursorInfo(ctypes.byref(ci)):
                    cursor_pos = (int(ci.ptScreenPos.x), int(ci.ptScreenPos.y))
        finally:
            gdi32.SelectObject(mem_dc, old)
            gdi32.DeleteObject(hbmp)
            gdi32.DeleteDC(mem_dc)
            user32.ReleaseDC(0, hdc_screen)

        meta = {
            "source": source_mode,
            "hwnd": int(hwnd),
            "origin_x": left,
            "origin_y": top,
            "source_width": w,
            "source_height": h,
            "full_x": left,
            "full_y": top,
            "full_width": w,
            "full_height": h,
            "cursor_x": cursor_pos[0] if cursor_pos else 0,
            "cursor_y": cursor_pos[1] if cursor_pos else 0,
        }
        return img, raw, meta

    def capture_frame(
        self,
        mode: str = "desktop",
        hwnd: int | None = None,
        roi: dict[str, float] | None = None,
        max_width: int = 1080,
        quality: int = 78,
        fmt: str = "webp",
        last_hash: str | None = None,
        draw_cursor: bool = True,
    ) -> CaptureResult:
        t0 = time.perf_counter()
        if mode == "window" and hwnd:
            try:
                img, raw, meta = self.capture_window_raw(hwnd, draw_cursor=draw_cursor)
            except Exception:
                img, raw, meta = self.capture_desktop_raw(draw_cursor=draw_cursor)
        else:
            img, raw, meta = self.capture_desktop_raw(draw_cursor=draw_cursor)

        # Server-side ROI crop BEFORE scaling so zoomed-in phone viewports get native crisp text
        if roi:
            rx = max(0.0, min(0.95, float(roi.get("x", 0.0))))
            ry = max(0.0, min(0.95, float(roi.get("y", 0.0))))
            rw = max(0.05, min(1.0 - rx, float(roi.get("w", 1.0))))
            rh = max(0.05, min(1.0 - ry, float(roi.get("h", 1.0))))
            if rw < 0.999 or rh < 0.999 or rx > 0.001 or ry > 0.001:
                px = int(rx * img.width)
                py = int(ry * img.height)
                pw = max(32, int(rw * img.width))
                ph = max(32, int(rh * img.height))
                img = img.crop((px, py, min(img.width, px + pw), min(img.height, py + ph)))
                meta["origin_x"] += px
                meta["origin_y"] += py
                meta["source_width"] = img.width
                meta["source_height"] = img.height
                meta["roi"] = {"x": rx, "y": ry, "w": rw, "h": rh}

        # Hash final cropped image pixels to skip redundant transfers
        sample_bytes = img.tobytes() if roi else raw
        fhash = _fast_hash(sample_bytes)
        blank = _is_mostly_black(raw)

        if last_hash and fhash == last_hash:
            return CaptureResult(image_bytes=b"", meta=meta, frame_hash=fhash, unchanged=True, is_blank=blank)

        # Resize cleanly with Lanczos if wider than requested target
        out_img = img
        if max_width and out_img.width > max_width:
            ratio = max_width / float(out_img.width)
            new_h = max(16, int(out_img.height * ratio))
            out_img = out_img.resize((max_width, new_h), Image.Resampling.LANCZOS)

        buf = io.BytesIO()
        fmt_lower = fmt.lower()
        q = max(30, min(95, int(quality)))
        if fmt_lower == "webp":
            out_img.save(buf, format="WEBP", quality=q, method=0)
            mime = "image/webp"
        else:
            out_img.save(buf, format="JPEG", quality=q, optimize=False)
            mime = "image/jpeg"

        encoded = buf.getvalue()
        meta.update(
            {
                "frame_width": out_img.width,
                "frame_height": out_img.height,
                "mime": mime,
                "bytes": len(encoded),
                "is_blank": blank,
                "capture_ms": round((time.perf_counter() - t0) * 1000, 1),
                "ts": time.time(),
            }
        )
        return CaptureResult(image_bytes=encoded, meta=meta, frame_hash=fhash, unchanged=False, is_blank=blank)

    def capture_thumbnail(self, hwnd: int, max_width: int = 380, quality: int = 68) -> bytes:
        res = self.capture_frame(
            mode="window",
            hwnd=hwnd,
            max_width=max_width,
            quality=quality,
            fmt="jpeg",
            draw_cursor=False,
        )
        return res.image_bytes
