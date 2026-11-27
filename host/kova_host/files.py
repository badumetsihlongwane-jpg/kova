"""Native Windows file manager, atomic text editor backend, log tail, and Git bridge."""

from __future__ import annotations

import ctypes
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

SKIP_SEARCH_DIRS = {".git", "node_modules", ".venv", "venv", "__pycache__", "build", "dist", ".gradle"}

EXT_LANG_MAP = {
    ".py": "python",
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".java": "java",
    ".js": "javascript",
    ".jsx": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".json": "json",
    ".yaml": "yaml",
    ".yml": "yaml",
    ".toml": "toml",
    ".xml": "xml",
    ".html": "html",
    ".css": "css",
    ".ps1": "powershell",
    ".bat": "batch",
    ".sh": "shell",
    ".cs": "csharp",
    ".cpp": "cpp",
    ".c": "c",
    ".h": "c",
    ".rs": "rust",
    ".go": "go",
    ".md": "markdown",
    ".sql": "sql",
    ".log": "log",
    ".txt": "text",
    ".env": "env",
}


def _resolve_path(path_str: str | None) -> Path:
    if not path_str or not path_str.strip():
        return Path.home()
    return Path(path_str).expanduser().resolve()


def _detect_git_root(path: Path) -> str | None:
    cur = path if path.is_dir() else path.parent
    for _ in range(12):
        if (cur / ".git").exists():
            return str(cur)
        if cur.parent == cur:
            break
        cur = cur.parent
    return None


def get_roots() -> list[dict[str, Any]]:
    home = Path.home()
    roots: list[dict[str, Any]] = []

    bookmarks = [
        ("Home", home, "home"),
        ("Desktop", home / "Desktop", "desktop"),
        ("Documents", home / "Documents", "folder"),
        ("Downloads", home / "Downloads", "download"),
    ]
    scratch = home / ".gemini" / "antigravity" / "scratch"
    if scratch.exists():
        bookmarks.append(("Projects", scratch, "code"))

    for label, p, icon in bookmarks:
        if p.exists():
            roots.append({"label": label, "path": str(p), "icon": icon, "kind": "bookmark"})

    bitmask = ctypes.windll.kernel32.GetLogicalDrives()
    for i in range(26):
        if bitmask & (1 << i):
            drive = f"{chr(65 + i)}:\\"
            dtype = ctypes.windll.kernel32.GetDriveTypeW(drive)
            if dtype in (2, 3, 4):
                roots.append({"label": f"Drive ({chr(65 + i)}:)", "path": drive, "icon": "storage", "kind": "drive"})

    return roots


def list_directory(path_str: str | None, show_hidden: bool = False) -> dict[str, Any]:
    target = _resolve_path(path_str)
    if not target.exists():
        raise FileNotFoundError(f"Path not found: {target}")
    if not target.is_dir():
        target = target.parent

    entries: list[dict[str, Any]] = []
    with os.scandir(target) as it:
        for entry in it:
            name = entry.name
            if not show_hidden and name.startswith("."):
                continue
            try:
                st = entry.stat(follow_symlinks=False)
                is_dir = entry.is_dir(follow_symlinks=False)
                ext = "" if is_dir else Path(name).suffix.lower()
                entries.append(
                    {
                        "name": name,
                        "path": entry.path,
                        "is_dir": is_dir,
                        "size": 0 if is_dir else st.st_size,
                        "modified": st.st_mtime,
                        "ext": ext,
                        "language": EXT_LANG_MAP.get(ext, "text"),
                    }
                )
            except OSError:
                continue

    entries.sort(key=lambda e: (not e["is_dir"], e["name"].lower()))
    parent = str(target.parent) if target.parent != target else None
    return {
        "path": str(target),
        "parent": parent,
        "git_root": _detect_git_root(target),
        "entries": entries,
    }


def read_text_file(path_str: str, max_bytes: int = 1_500_000, tail_lines: int | None = None) -> dict[str, Any]:
    target = _resolve_path(path_str)
    if not target.is_file():
        raise FileNotFoundError(f"File not found: {target}")

    st = target.stat()
    truncated = st.st_size > max_bytes

    with target.open("rb") as f:
        if tail_lines and st.st_size > max_bytes:
            f.seek(max(0, st.st_size - max_bytes))
            raw = f.read(max_bytes)
        else:
            raw = f.read(max_bytes)

    # Detect binary files cleanly
    if b"\x00" in raw[:8192]:
        return {
            "path": str(target),
            "name": target.name,
            "size": st.st_size,
            "modified": st.st_mtime,
            "is_binary": True,
            "truncated": truncated,
            "language": "binary",
            "content": "",
            "line_count": 0,
        }

    text = None
    for enc in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        text = raw.decode("utf-8", errors="replace")

    if tail_lines and tail_lines > 0:
        lines = text.splitlines()
        text = "\n".join(lines[-tail_lines:])

    ext = target.suffix.lower()
    return {
        "path": str(target),
        "name": target.name,
        "size": st.st_size,
        "modified": st.st_mtime,
        "is_binary": False,
        "truncated": truncated,
        "language": EXT_LANG_MAP.get(ext, "text"),
        "content": text,
        "line_count": text.count("\n") + 1 if text else 0,
    }


def write_text_file(path_str: str, content: str) -> dict[str, Any]:
    target = _resolve_path(path_str)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f".{target.name}.kova.tmp")
    tmp.write_text(content, encoding="utf-8", newline="")
    os.replace(tmp, target)
    st = target.stat()
    return {"ok": True, "path": str(target), "size": st.st_size, "modified": st.st_mtime}


def make_directory(path_str: str) -> dict[str, Any]:
    target = _resolve_path(path_str)
    target.mkdir(parents=True, exist_ok=True)
    return {"ok": True, "path": str(target)}


def rename_path(src_str: str, dst_str: str) -> dict[str, Any]:
    src = _resolve_path(src_str)
    dst = _resolve_path(dst_str)
    if not src.exists():
        raise FileNotFoundError(f"Source not found: {src}")
    os.replace(src, dst)
    return {"ok": True, "src": str(src), "dst": str(dst)}


def delete_path(path_str: str) -> dict[str, Any]:
    target = _resolve_path(path_str)
    if not target.exists():
        raise FileNotFoundError(f"Path not found: {target}")
    if target == target.anchor or len(str(target)) <= 3:
        raise ValueError("Refusing to delete drive root")
    if target.is_dir():
        shutil.rmtree(target)
    else:
        target.unlink()
    return {"ok": True, "path": str(target)}


def search_files(root_str: str, query: str, max_results: int = 80) -> list[dict[str, Any]]:
    root = _resolve_path(root_str)
    q = query.strip().lower()
    if not q or not root.exists():
        return []

    matches: list[dict[str, Any]] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_SEARCH_DIRS]
        for fname in filenames:
            if q in fname.lower():
                full = os.path.join(dirpath, fname)
                try:
                    st = os.stat(full)
                    ext = Path(fname).suffix.lower()
                    matches.append(
                        {
                            "name": fname,
                            "path": full,
                            "is_dir": False,
                            "size": st.st_size,
                            "modified": st.st_mtime,
                            "ext": ext,
                            "language": EXT_LANG_MAP.get(ext, "text"),
                        }
                    )
                except OSError:
                    pass
                if len(matches) >= max_results:
                    return matches
    return matches


def _run_git(cwd: str, args: list[str], timeout: float = 15.0) -> tuple[bool, str, str]:
    git_exe = shutil.which("git")
    if not git_exe:
        return False, "", "git is not installed on this Windows host"
    res = subprocess.run(
        [git_exe, *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    return res.returncode == 0, res.stdout, res.stderr


def git_status(path_str: str) -> dict[str, Any]:
    target = _resolve_path(path_str)
    root = _detect_git_root(target)
    if not root:
        return {"is_repo": False, "error": "Not inside a Git repository"}

    ok_b, out_b, err_b = _run_git(root, ["rev-parse", "--abbrev-ref", "HEAD"])
    if not ok_b and "not installed" in err_b:
        return {"is_repo": True, "root": root, "git_available": False, "error": err_b}

    branch = out_b.strip() if ok_b else "HEAD"
    ok_s, out_s, _ = _run_git(root, ["status", "--porcelain=v1"])
    files: list[dict[str, Any]] = []
    if ok_s:
        for line in out_s.splitlines():
            if len(line) < 4:
                continue
            x, y = line[0], line[1]
            rel = line[3:].strip()
            staged = x not in (" ", "?")
            files.append(
                {
                    "path": rel,
                    "full_path": str(Path(root) / rel),
                    "index_status": x,
                    "worktree_status": y,
                    "staged": staged,
                    "untracked": x == "?" and y == "?",
                }
            )
    return {
        "is_repo": True,
        "git_available": True,
        "root": root,
        "branch": branch,
        "files": files,
    }


def git_diff(path_str: str, file_rel: str | None = None, staged: bool = False) -> dict[str, Any]:
    root = _detect_git_root(_resolve_path(path_str))
    if not root:
        return {"ok": False, "error": "Not inside a Git repository", "diff": ""}
    args = ["diff"]
    if staged:
        args.append("--cached")
    if file_rel:
        args.extend(["--", file_rel])
    ok, out, err = _run_git(root, args)
    return {"ok": ok, "root": root, "diff": out[:400_000], "error": err.strip() if not ok else ""}


def git_action(path_str: str, action: str, message: str = "", files: list[str] | None = None) -> dict[str, Any]:
    root = _detect_git_root(_resolve_path(path_str))
    if not root:
        return {"ok": False, "error": "Not inside a Git repository"}

    if action == "stage":
        targets = files if files else ["-A"]
        ok, out, err = _run_git(root, ["add", *targets])
    elif action == "unstage":
        targets = files if files else []
        ok, out, err = _run_git(root, ["reset", "HEAD", "--", *targets])
    elif action == "commit":
        if not message.strip():
            return {"ok": False, "error": "Commit message cannot be empty"}
        ok, out, err = _run_git(root, ["commit", "-m", message.strip()])
    elif action == "pull":
        ok, out, err = _run_git(root, ["pull"], timeout=30.0)
    elif action == "push":
        ok, out, err = _run_git(root, ["push"], timeout=30.0)
    else:
        return {"ok": False, "error": f"Unknown git action: {action}"}

    return {"ok": ok, "stdout": out.strip(), "stderr": err.strip()}
