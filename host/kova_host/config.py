"""Configuration, persistent pairing secret, and self-signed TLS management."""

from __future__ import annotations

import datetime
import hashlib
import ipaddress
import json
import os
import secrets
import socket
import ssl
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


def default_config_dir() -> Path:
    env_dir = os.environ.get("KOVA_CONFIG_DIR")
    if env_dir:
        return Path(env_dir)
    return Path.home() / ".kova"


def detect_local_ips() -> list[str]:
    ips: list[str] = []
    # Primary outbound IP
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and ip not in ips:
                ips.append(ip)
    except OSError:
        pass

    # All resolved addresses for hostname
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass

    if "127.0.0.1" not in ips:
        ips.append("127.0.0.1")
    return ips


@dataclass
class QuickLaunchItem:
    id: str
    label: str
    target: str
    args: list[str] = field(default_factory=list)
    icon: str = "apps"
    category: str = "app"


def default_quick_launch() -> list[dict[str, Any]]:
    return [
        asdict(QuickLaunchItem("powershell", "PowerShell", "powershell.exe", [], "terminal", "shell")),
        asdict(QuickLaunchItem("cmd", "Command Prompt", "cmd.exe", [], "terminal", "shell")),
        asdict(QuickLaunchItem("explorer", "File Explorer", "explorer.exe", [], "folder", "system")),
        asdict(QuickLaunchItem("notepad", "Notepad", "notepad.exe", [], "edit", "editor")),
        asdict(QuickLaunchItem("taskmgr", "Task Manager", "taskmgr.exe", [], "monitoring", "system")),
        asdict(QuickLaunchItem("settings", "Windows Settings", "ms-settings:", [], "settings", "system")),
        asdict(QuickLaunchItem("edge", "Microsoft Edge", "msedge.exe", [], "web", "browser")),
        asdict(QuickLaunchItem("vscode", "VS Code", "code", [], "code", "dev")),
    ]


@dataclass
class HostConfig:
    host: str = "0.0.0.0"
    port: int = 8765
    token: str = field(default_factory=lambda: secrets.token_urlsafe(32))
    name: str = field(default_factory=socket.gethostname)
    tls_enabled: bool = True
    public_host: str = ""
    quick_launch: list[dict[str, Any]] = field(default_factory=default_quick_launch)

    @property
    def config_dir(self) -> Path:
        return default_config_dir()

    @property
    def cert_path(self) -> Path:
        return self.config_dir / "cert.pem"

    @property
    def key_path(self) -> Path:
        return self.config_dir / "key.pem"

    @property
    def bin_dir(self) -> Path:
        return self.config_dir / "bin"


def load_or_create_config(
    config_dir: Path | None = None,
    port_override: int | None = None,
    host_override: str | None = None,
    public_host_override: str | None = None,
    tls_override: bool | None = None,
    rotate_token: bool = False,
) -> HostConfig:
    cfg_dir = config_dir or default_config_dir()
    cfg_dir.mkdir(parents=True, exist_ok=True)
    cfg_file = cfg_dir / "config.json"

    if cfg_file.exists():
        try:
            raw = json.loads(cfg_file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            raw = {}
        cfg = HostConfig(
            host=raw.get("host", "0.0.0.0"),
            port=int(raw.get("port", 8765)),
            token=raw.get("token") or secrets.token_urlsafe(32),
            name=raw.get("name") or socket.gethostname(),
            tls_enabled=bool(raw.get("tls_enabled", True)),
            public_host=raw.get("public_host", ""),
            quick_launch=raw.get("quick_launch") or default_quick_launch(),
        )
    else:
        cfg = HostConfig()

    changed = not cfg_file.exists()
    if rotate_token:
        cfg.token = secrets.token_urlsafe(32)
        changed = True
    if port_override is not None:
        cfg.port = port_override
        changed = True
    if host_override is not None:
        cfg.host = host_override
        changed = True
    if public_host_override is not None:
        cfg.public_host = public_host_override
        changed = True
    if tls_override is not None:
        cfg.tls_enabled = tls_override
        changed = True

    if changed:
        save_config(cfg, cfg_dir)
    return cfg


def save_config(cfg: HostConfig, config_dir: Path | None = None) -> None:
    cfg_dir = config_dir or default_config_dir()
    cfg_dir.mkdir(parents=True, exist_ok=True)
    cfg_file = cfg_dir / "config.json"
    cfg_file.write_text(json.dumps(asdict(cfg), indent=2), encoding="utf-8")


def ensure_tls_certificate(
    cert_path: Path,
    key_path: Path,
    extra_hosts: list[str] | None = None,
    force_regenerate: bool = False,
) -> str:
    """Generate a self-signed TLS certificate if absent and return its SHA-256 hex fingerprint."""
    cert_path.parent.mkdir(parents=True, exist_ok=True)

    if force_regenerate or not cert_path.exists() or not key_path.exists():
        private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        hostname = socket.gethostname()
        subject = issuer = x509.Name(
            [
                x509.NameAttribute(NameOID.COMMON_NAME, f"kova-host ({hostname})"),
                x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Kova Workstation"),
            ]
        )

        san_entries: list[x509.GeneralName] = [
            x509.DNSName("localhost"),
            x509.DNSName(hostname),
        ]
        seen_ips: set[str] = set()
        seen_dns: set[str] = {"localhost", hostname}

        candidates = detect_local_ips() + (extra_hosts or [])
        for entry in candidates:
            entry = entry.strip()
            if not entry:
                continue
            try:
                ip_obj = ipaddress.ip_address(entry)
                if str(ip_obj) not in seen_ips:
                    san_entries.append(x509.IPAddress(ip_obj))
                    seen_ips.add(str(ip_obj))
            except ValueError:
                if entry not in seen_dns:
                    san_entries.append(x509.DNSName(entry))
                    seen_dns.add(entry)

        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(issuer)
            .public_key(private_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5))
            .not_valid_after(now + datetime.timedelta(days=1095))
            .add_extension(x509.SubjectAlternativeName(san_entries), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(private_key, hashes.SHA256())
        )

        key_path.write_bytes(
            private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))

    cert_obj = x509.load_pem_x509_certificate(cert_path.read_bytes())
    der_bytes = cert_obj.public_bytes(serialization.Encoding.DER)
    return hashlib.sha256(der_bytes).hexdigest().upper()


def create_ssl_context(cert_path: Path, key_path: Path) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))
    return ctx
