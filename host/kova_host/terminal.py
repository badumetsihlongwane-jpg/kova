"""True Windows ConPTY (CreatePseudoConsole) multi-session terminal manager."""

from __future__ import annotations

import asyncio
import ctypes
import ctypes.wintypes as wt
import os
import shutil
import threading
import time
import uuid
from collections import deque
from pathlib import Path
from typing import Any

kernel32 = ctypes.windll.kernel32

HANDLE = wt.HANDLE
DWORD = wt.DWORD
BOOL = wt.BOOL
LPCWSTR = wt.LPCWSTR
LPWSTR = wt.LPWSTR
LPVOID = ctypes.c_void_p
SIZE_T = ctypes.c_size_t
HPCON = HANDLE

PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016
EXTENDED_STARTUPINFO_PRESENT = 0x00080000
STARTF_USESTDHANDLES = 0x00000100
INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value
WAIT_OBJECT_0 = 0x00000000
STILL_ACTIVE = 259


class STARTUPINFOW(ctypes.Structure):
    _fields_ = [
        ("cb", DWORD),
        ("lpReserved", LPWSTR),
        ("lpDesktop", LPWSTR),
        ("lpTitle", LPWSTR),
        ("dwX", DWORD),
        ("dwY", DWORD),
        ("dwXSize", DWORD),
        ("dwYSize", DWORD),
        ("dwXCountChars", DWORD),
        ("dwYCountChars", DWORD),
        ("dwFillAttribute", DWORD),
        ("dwFlags", DWORD),
        ("wShowWindow", wt.WORD),
        ("cbReserved2", wt.WORD),
        ("lpReserved2", ctypes.POINTER(ctypes.c_byte)),
        ("hStdInput", HANDLE),
        ("hStdOutput", HANDLE),
        ("hStdError", HANDLE),
    ]


class STARTUPINFOEXW(ctypes.Structure):
    _fields_ = [
        ("StartupInfo", STARTUPINFOW),
        ("lpAttributeList", LPVOID),
    ]


class PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("hProcess", HANDLE),
        ("hThread", HANDLE),
        ("dwProcessId", DWORD),
        ("dwThreadId", DWORD),
    ]


class SECURITY_ATTRIBUTES(ctypes.Structure):
    _fields_ = [
        ("nLength", DWORD),
        ("lpSecurityDescriptor", LPVOID),
        ("bInheritHandle", BOOL),
    ]


kernel32.CreatePipe.argtypes = [ctypes.POINTER(HANDLE), ctypes.POINTER(HANDLE), ctypes.POINTER(SECURITY_ATTRIBUTES), DWORD]
kernel32.CreatePipe.restype = BOOL

# COORD { SHORT X; SHORT Y; } is a 4-byte value packed as (rows << 16) | cols in little-endian
kernel32.CreatePseudoConsole.argtypes = [ctypes.c_uint32, HANDLE, HANDLE, DWORD, ctypes.POINTER(HPCON)]
kernel32.CreatePseudoConsole.restype = ctypes.c_long

kernel32.ResizePseudoConsole.argtypes = [HPCON, ctypes.c_uint32]
kernel32.ResizePseudoConsole.restype = ctypes.c_long

kernel32.ClosePseudoConsole.argtypes = [HPCON]
kernel32.ClosePseudoConsole.restype = None

kernel32.InitializeProcThreadAttributeList.argtypes = [LPVOID, DWORD, DWORD, ctypes.POINTER(SIZE_T)]
kernel32.InitializeProcThreadAttributeList.restype = BOOL

kernel32.UpdateProcThreadAttribute.argtypes = [LPVOID, DWORD, SIZE_T, LPVOID, SIZE_T, LPVOID, LPVOID]
kernel32.UpdateProcThreadAttribute.restype = BOOL

kernel32.DeleteProcThreadAttributeList.argtypes = [LPVOID]
kernel32.DeleteProcThreadAttributeList.restype = None

kernel32.CreateProcessW.argtypes = [
    LPCWSTR,
    LPWSTR,
    LPVOID,
    LPVOID,
    BOOL,
    DWORD,
    LPVOID,
    LPCWSTR,
    ctypes.POINTER(STARTUPINFOEXW),
    ctypes.POINTER(PROCESS_INFORMATION),
]
kernel32.CreateProcessW.restype = BOOL

kernel32.ReadFile.argtypes = [HANDLE, LPVOID, DWORD, ctypes.POINTER(DWORD), LPVOID]
kernel32.ReadFile.restype = BOOL

kernel32.WriteFile.argtypes = [HANDLE, LPVOID, DWORD, ctypes.POINTER(DWORD), LPVOID]
kernel32.WriteFile.restype = BOOL

kernel32.CloseHandle.argtypes = [HANDLE]
kernel32.CloseHandle.restype = BOOL

kernel32.TerminateProcess.argtypes = [HANDLE, wt.UINT]
kernel32.TerminateProcess.restype = BOOL

kernel32.GetExitCodeProcess.argtypes = [HANDLE, ctypes.POINTER(DWORD)]
kernel32.GetExitCodeProcess.restype = BOOL


def _pack_coord(cols: int, rows: int) -> int:
    c = max(20, min(300, int(cols)))
    r = max(5, min(150, int(rows)))
    return ((r & 0xFFFF) << 16) | (c & 0xFFFF)


def resolve_shell_command(shell: str) -> tuple[str, str]:
    s = (shell or "powershell").strip().lower()
    if s in ("pwsh", "powershell7"):
        exe = shutil.which("pwsh.exe") or shutil.which("powershell.exe") or "powershell.exe"
        return f'"{exe}" -NoLogo', "PowerShell"
    if s in ("powershell", "ps"):
        exe = shutil.which("powershell.exe") or r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
        return f'"{exe}" -NoLogo', "PowerShell"
    if s in ("cmd", "command"):
        exe = shutil.which("cmd.exe") or r"C:\Windows\System32\cmd.exe"
        return f'"{exe}"', "Command Prompt"
    if s == "wsl":
        exe = shutil.which("wsl.exe")
        if exe:
            return f'"{exe}"', "WSL"
    if s in ("bash", "gitbash"):
        for cand in [
            r"C:\Program Files\Git\bin\bash.exe",
            r"C:\Program Files\Git\usr\bin\bash.exe",
            shutil.which("bash.exe") or "",
        ]:
            if cand and os.path.exists(cand):
                return f'"{cand}" -i -l', "Bash"
    # Fallback to powershell
    exe = shutil.which("powershell.exe") or "powershell.exe"
    return f'"{exe}" -NoLogo', "PowerShell"


class ConPtySession:
    """Single persistent Windows ConPTY session with reconnect ring buffer."""

    def __init__(
        self,
        session_id: str,
        shell: str = "powershell",
        cwd: str | None = None,
        cols: int = 80,
        rows: int = 24,
        loop: asyncio.AbstractEventLoop | None = None,
    ) -> None:
        self.id = session_id
        self.shell = shell
        self.command, self.title = resolve_shell_command(shell)
        self.cwd = cwd if (cwd and os.path.isdir(cwd)) else str(Path.home())
        self.cols = cols
        self.rows = rows
        self.created_at = time.time()
        self.alive = False
        self.exit_code: int | None = None
        self.pid: int = 0

        self._loop = loop
        self._hpc: HPCON | None = None
        self._h_in_write: HANDLE | None = None
        self._h_out_read: HANDLE | None = None
        self._h_process: HANDLE | None = None
        self._h_thread: HANDLE | None = None

        self._lock = threading.Lock()
        self._ring: deque[bytes] = deque()
        self._ring_size = 0
        self._max_ring_bytes = 256 * 1024
        self._subscribers: set[asyncio.Queue[tuple[str, Any]]] = set()
        self._reader_thread: threading.Thread | None = None

    def start(self) -> None:
        h_pty_in_read = HANDLE()
        h_pty_in_write = HANDLE()
        h_pty_out_read = HANDLE()
        h_pty_out_write = HANDLE()

        if not kernel32.CreatePipe(ctypes.byref(h_pty_in_read), ctypes.byref(h_pty_in_write), None, 0):
            raise OSError("CreatePipe(in) failed")
        if not kernel32.CreatePipe(ctypes.byref(h_pty_out_read), ctypes.byref(h_pty_out_write), None, 0):
            kernel32.CloseHandle(h_pty_in_read)
            kernel32.CloseHandle(h_pty_in_write)
            raise OSError("CreatePipe(out) failed")

        hpc = HPCON()
        hr = kernel32.CreatePseudoConsole(
            _pack_coord(self.cols, self.rows),
            h_pty_in_read,
            h_pty_out_write,
            0,
            ctypes.byref(hpc),
        )
        # Child-side pipe ends are duplicated internally by CreatePseudoConsole
        kernel32.CloseHandle(h_pty_in_read)
        kernel32.CloseHandle(h_pty_out_write)

        if hr != 0:
            kernel32.CloseHandle(h_pty_in_write)
            kernel32.CloseHandle(h_pty_out_read)
            raise OSError(f"CreatePseudoConsole failed with HRESULT 0x{hr & 0xFFFFFFFF:08X}")

        self._hpc = hpc
        self._h_in_write = h_pty_in_write
        self._h_out_read = h_pty_out_read

        attr_size = SIZE_T(0)
        kernel32.InitializeProcThreadAttributeList(None, 1, 0, ctypes.byref(attr_size))
        attr_buf = ctypes.create_string_buffer(attr_size.value)
        if not kernel32.InitializeProcThreadAttributeList(attr_buf, 1, 0, ctypes.byref(attr_size)):
            self.close()
            raise OSError("InitializeProcThreadAttributeList failed")

        try:
            # UpdateProcThreadAttribute expects the HPCON handle value itself as lpValue
            if not kernel32.UpdateProcThreadAttribute(
                attr_buf,
                0,
                PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
                hpc,
                ctypes.sizeof(HPCON),
                None,
                None,
            ):
                raise OSError("UpdateProcThreadAttribute failed")

            si = STARTUPINFOEXW()
            si.StartupInfo.cb = ctypes.sizeof(STARTUPINFOEXW)
            # Clear inherited redirected handles so child attaches strictly to ConPTY's CONIN$/CONOUT$
            si.StartupInfo.dwFlags = STARTF_USESTDHANDLES
            si.StartupInfo.hStdInput = None
            si.StartupInfo.hStdOutput = None
            si.StartupInfo.hStdError = None
            si.lpAttributeList = ctypes.cast(attr_buf, LPVOID)
            pi = PROCESS_INFORMATION()

            cmd_buf = ctypes.create_unicode_buffer(self.command)
            ok = kernel32.CreateProcessW(
                None,
                cmd_buf,
                None,
                None,
                False,
                EXTENDED_STARTUPINFO_PRESENT,
                None,
                self.cwd,
                ctypes.byref(si),
                ctypes.byref(pi),
            )
            if not ok:
                err = ctypes.GetLastError()
                raise OSError(f"CreateProcessW failed (error {err}) for {self.command}")

            self._h_process = pi.hProcess
            self._h_thread = pi.hThread
            self.pid = int(pi.dwProcessId)
            self.alive = True
        except Exception:
            kernel32.DeleteProcThreadAttributeList(attr_buf)
            self.close()
            raise
        finally:
            kernel32.DeleteProcThreadAttributeList(attr_buf)

        self._reader_thread = threading.Thread(target=self._read_loop, name=f"conpty-{self.id}", daemon=True)
        self._reader_thread.start()

    def _read_loop(self) -> None:
        buf = ctypes.create_string_buffer(8192)
        bytes_read = DWORD(0)
        try:
            while self.alive and self._h_out_read:
                ok = kernel32.ReadFile(self._h_out_read, buf, 8192, ctypes.byref(bytes_read), None)
                if not ok or bytes_read.value == 0:
                    break
                chunk = buf.raw[: bytes_read.value]
                self._append_ring(chunk)
                self._broadcast("output", chunk)
        finally:
            self.alive = False
            if self._h_process:
                code = DWORD(0)
                if kernel32.GetExitCodeProcess(self._h_process, ctypes.byref(code)):
                    self.exit_code = int(code.value) if code.value != STILL_ACTIVE else 0
            self._broadcast("exit", self.exit_code or 0)

    def _append_ring(self, chunk: bytes) -> None:
        with self._lock:
            self._ring.append(chunk)
            self._ring_size += len(chunk)
            while self._ring_size > self._max_ring_bytes and self._ring:
                old = self._ring.popleft()
                self._ring_size -= len(old)

    def get_replay_text(self) -> str:
        with self._lock:
            data = b"".join(self._ring)
        return data.decode("utf-8", errors="replace")

    def subscribe(self) -> asyncio.Queue[tuple[str, Any]]:
        q: asyncio.Queue[tuple[str, Any]] = asyncio.Queue(maxsize=256)
        with self._lock:
            self._subscribers.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue[tuple[str, Any]]) -> None:
        with self._lock:
            self._subscribers.discard(q)

    def _broadcast(self, kind: str, payload: Any) -> None:
        with self._lock:
            subs = list(self._subscribers)
        if not subs or self._loop is None or self._loop.is_closed():
            return
        for q in subs:
            def _push(queue: asyncio.Queue[tuple[str, Any]] = q) -> None:
                if queue.full():
                    try:
                        queue.get_nowait()
                    except asyncio.QueueEmpty:
                        pass
                try:
                    queue.put_nowait((kind, payload))
                except asyncio.QueueFull:
                    pass

            try:
                self._loop.call_soon_threadsafe(_push)
            except RuntimeError:
                pass

    def write(self, data: str | bytes) -> bool:
        if not self.alive or not self._h_in_write:
            return False
        raw = data.encode("utf-8") if isinstance(data, str) else data
        if not raw:
            return True
        written = DWORD(0)
        ok = kernel32.WriteFile(self._h_in_write, raw, len(raw), ctypes.byref(written), None)
        return bool(ok)

    def resize(self, cols: int, rows: int) -> bool:
        self.cols = max(20, min(300, int(cols)))
        self.rows = max(5, min(150, int(rows)))
        if not self._hpc:
            return False
        hr = kernel32.ResizePseudoConsole(self._hpc, _pack_coord(self.cols, self.rows))
        return hr == 0

    def close(self) -> None:
        self.alive = False
        if self._hpc:
            try:
                kernel32.ClosePseudoConsole(self._hpc)
            except Exception:
                pass
            self._hpc = None
        if self._h_process:
            try:
                kernel32.TerminateProcess(self._h_process, 0)
            except Exception:
                pass
            kernel32.CloseHandle(self._h_process)
            self._h_process = None
        if self._h_thread:
            kernel32.CloseHandle(self._h_thread)
            self._h_thread = None
        if self._h_in_write:
            kernel32.CloseHandle(self._h_in_write)
            self._h_in_write = None
        if self._h_out_read:
            kernel32.CloseHandle(self._h_out_read)
            self._h_out_read = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "title": self.title,
            "shell": self.shell,
            "cwd": self.cwd,
            "cols": self.cols,
            "rows": self.rows,
            "pid": self.pid,
            "alive": self.alive,
            "exit_code": self.exit_code,
            "created_at": self.created_at,
        }


class TerminalManager:
    def __init__(self) -> None:
        self._sessions: dict[str, ConPtySession] = {}

    def create(
        self,
        shell: str = "powershell",
        cwd: str | None = None,
        cols: int = 80,
        rows: int = 24,
        loop: asyncio.AbstractEventLoop | None = None,
    ) -> ConPtySession:
        sid = uuid.uuid4().hex[:10]
        sess = ConPtySession(
            session_id=sid,
            shell=shell,
            cwd=cwd,
            cols=cols,
            rows=rows,
            loop=loop or asyncio.get_running_loop(),
        )
        sess.start()
        self._sessions[sid] = sess
        return sess

    def get(self, session_id: str) -> ConPtySession | None:
        return self._sessions.get(session_id)

    def list_sessions(self) -> list[dict[str, Any]]:
        return [s.to_dict() for s in self._sessions.values()]

    def close_session(self, session_id: str) -> bool:
        sess = self._sessions.pop(session_id, None)
        if not sess:
            return False
        sess.close()
        return True

    def close_all(self) -> None:
        for sid in list(self._sessions.keys()):
            self.close_session(sid)
