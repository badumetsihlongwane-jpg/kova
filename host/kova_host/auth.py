"""Authentication helpers, pairing URI builder, and terminal QR rendering."""

from __future__ import annotations

import hmac
from urllib.parse import urlencode

from aiohttp import web


def verify_token(provided: str | None, expected: str) -> bool:
    if not provided or not expected:
        return False
    return hmac.compare_digest(provided.strip(), expected.strip())


def extract_request_token(request: web.Request) -> str | None:
    auth_header = request.headers.get("Authorization", "")
    if auth_header.lower().startswith("bearer "):
        return auth_header[7:].strip()
    token_q = request.query.get("token")
    if token_q:
        return token_q.strip()
    return None


def build_pairing_uri(
    host: str,
    port: int,
    token: str,
    fingerprint: str,
    name: str,
    tls_enabled: bool = True,
) -> str:
    params = {
        "host": host,
        "port": str(port),
        "token": token,
        "name": name,
        "tls": "1" if tls_enabled else "0",
    }
    if fingerprint and tls_enabled:
        params["fp"] = fingerprint
    return f"kova://connect?{urlencode(params)}"


def render_qr_terminal(payload: str) -> str:
    """Render a compact Unicode half-block QR code for Windows PowerShell/Terminal."""
    try:
        import qrcode

        qr = qrcode.QRCode(
            version=None,
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=1,
            border=2,
        )
        qr.add_data(payload)
        qr.make(fit=True)
        matrix = qr.get_matrix()

        lines: list[str] = []
        height = len(matrix)
        width = len(matrix[0]) if height else 0
        for y in range(0, height, 2):
            row_chars: list[str] = []
            for x in range(width):
                top = matrix[y][x]
                bottom = matrix[y + 1][x] if y + 1 < height else False
                # Invert for dark terminal background (white quiet zone, dark modules)
                if not top and not bottom:
                    row_chars.append("\u2588")  # full block
                elif not top and bottom:
                    row_chars.append("\u2580")  # upper half
                elif top and not bottom:
                    row_chars.append("\u2584")  # lower half
                else:
                    row_chars.append(" ")
            lines.append("".join(row_chars))
        return "\n".join(lines)
    except Exception:
        return "[QR rendering unavailable - copy the pairing URI below]"
