"""CLI entrypoint for Kova Windows Host Agent."""

from __future__ import annotations

import argparse
import asyncio
import sys

from aiohttp import web

from . import __version__
from .auth import build_pairing_uri, render_qr_terminal
from .config import (
    create_ssl_context,
    detect_local_ips,
    ensure_tls_certificate,
    load_or_create_config,
)
from .server import KovaServer
from .telemetry import enable_dpi_awareness


def _print_banner(
    host_display: str,
    port: int,
    token: str,
    fingerprint: str,
    name: str,
    tls_enabled: bool,
    all_ips: list[str],
    show_qr: bool = True,
) -> None:
    uri = build_pairing_uri(
        host=host_display,
        port=port,
        token=token,
        fingerprint=fingerprint,
        name=name,
        tls_enabled=tls_enabled,
    )
    proto = "https" if tls_enabled else "http"
    print("=" * 64)
    print(f"  KOVA WORKSTATION HOST AGENT  v{__version__}")
    print("=" * 64)
    print(f"  Workstation : {name}")
    print(f"  Endpoint    : {proto}://{host_display}:{port}")
    print(f"  Local IPs   : {', '.join(all_ips)}")
    if tls_enabled:
        short_fp = f"{fingerprint[:16]}...{fingerprint[-12:]}" if len(fingerprint) > 28 else fingerprint
        print(f"  TLS Pin     : {short_fp}")
    else:
        print("  TLS Pin     : DISABLED (use behind Tailscale/SSH/Cloudflare Tunnel)")
    print(f"  Token       : {token}")
    print("-" * 64)
    if show_qr:
        print("  Scan this QR code in the Kova Android app:")
        print()
        print(render_qr_terminal(uri))
        print()
    print("  Or paste this Pairing URI into Kova on Android:")
    print(f"  {uri}")
    print("=" * 64)
    sys.stdout.flush()


async def _run_server(args: argparse.Namespace) -> None:
    enable_dpi_awareness()
    cfg = load_or_create_config(
        port_override=args.port,
        host_override=args.host,
        public_host_override=args.public_host,
        tls_override=(False if args.no_tls else None),
        rotate_token=args.rotate_token,
    )

    ips = detect_local_ips()
    extra_hosts = [cfg.public_host] if cfg.public_host else []
    fingerprint = ""
    ssl_ctx = None
    if cfg.tls_enabled:
        fingerprint = ensure_tls_certificate(
            cfg.cert_path,
            cfg.key_path,
            extra_hosts=extra_hosts,
            force_regenerate=args.regen_cert,
        )
        ssl_ctx = create_ssl_context(cfg.cert_path, cfg.key_path)

    primary_host = cfg.public_host or (ips[0] if ips else "127.0.0.1")

    if args.print_pairing:
        _print_banner(
            host_display=primary_host,
            port=cfg.port,
            token=cfg.token,
            fingerprint=fingerprint,
            name=cfg.name,
            tls_enabled=cfg.tls_enabled,
            all_ips=ips,
            show_qr=not args.no_qr,
        )
        return

    server = KovaServer(cfg, cert_fingerprint=fingerprint)
    runner = web.AppRunner(server.app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, host=cfg.host, port=cfg.port, ssl_context=ssl_ctx)
    await site.start()

    _print_banner(
        host_display=primary_host,
        port=cfg.port,
        token=cfg.token,
        fingerprint=fingerprint,
        name=cfg.name,
        tls_enabled=cfg.tls_enabled,
        all_ips=ips,
        show_qr=not args.no_qr,
    )

    stop_event = asyncio.Event()
    try:
        await stop_event.wait()
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        await runner.cleanup()


def cli(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="kova-host",
        description="Kova Mobile-First Semantic Windows Remote Workstation Host Agent",
    )
    parser.add_argument("--host", default=None, help="Bind interface (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=None, help="Listen port (default: 8765)")
    parser.add_argument(
        "--public-host",
        default=None,
        help="Public IP, Tailscale IP, or domain embedded in the pairing QR/URI",
    )
    parser.add_argument("--no-tls", action="store_true", help="Disable TLS (use behind tunnel/VPN)")
    parser.add_argument("--rotate-token", action="store_true", help="Generate a fresh 256-bit auth token")
    parser.add_argument("--regen-cert", action="store_true", help="Regenerate self-signed TLS certificate")
    parser.add_argument("--no-qr", action="store_true", help="Skip printing terminal QR code")
    parser.add_argument("--print-pairing", action="store_true", help="Print pairing URI/QR and exit")
    args = parser.parse_args(argv)

    try:
        asyncio.run(_run_server(args))
    except KeyboardInterrupt:
        print("\nKova Host Agent stopped.")


if __name__ == "__main__":
    cli()
