"""Single-owner bearer auth. Pairing via local file, rotation supported."""
from __future__ import annotations
import hmac
import os
import secrets
import time
from fastapi import Header, HTTPException
from .config import DATA_DIR, BEARER_ENV


_cached_token: str | None = None
_cached_mtime: float = 0.0


def _token_path():
    return DATA_DIR / "bearer.token"

def get_or_create_token() -> str:
    global _cached_token, _cached_mtime
    env = os.environ.get(BEARER_ENV)
    if env:
        return env
    p = _token_path()
    if p.exists():
        try:
            mtime = p.stat().st_mtime
            if _cached_token and mtime == _cached_mtime:
                return _cached_token
            tok = p.read_text().strip()
            if tok:
                _cached_token = tok
                _cached_mtime = mtime
                return tok
        except OSError:
            pass
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tok = secrets.token_urlsafe(32)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(tok)
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, p)
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass
    try:
        _cached_mtime = p.stat().st_mtime
    except OSError:
        _cached_mtime = time.time()
    _cached_token = tok
    return tok

async def require_auth(authorization: str | None = Header(default=None)):
    tok = get_or_create_token()
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing bearer")
    if not hmac.compare_digest(authorization[len("Bearer "):].strip(), tok):
        raise HTTPException(401, "invalid bearer")
