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

def _read_token() -> str | None:
    """Legge il token senza crearlo (usato dal percorso auth)."""
    global _cached_token, _cached_mtime
    env = os.environ.get(BEARER_ENV)
    if env:
        return env
    p = _token_path()
    try:
        mtime = p.stat().st_mtime
    except OSError:
        return None
    if _cached_token and mtime == _cached_mtime:
        return _cached_token
    try:
        tok = p.read_text().strip() or None
    except OSError:
        return None
    _cached_token, _cached_mtime = tok, mtime
    return tok

def get_or_create_token() -> str:
    tok = _read_token()
    if tok:
        return tok
    global _cached_token, _cached_mtime
    p = _token_path()
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
    # Mai creare il token qui: una request non autenticata non deve avere
    # effetti collaterali. La creazione avviene solo all'avvio (lifespan).
    tok = _read_token()
    if not tok:
        raise HTTPException(401, "pairing non inizializzato")
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing bearer")
    if not hmac.compare_digest(authorization[len("Bearer "):].strip(), tok):
        raise HTTPException(401, "invalid bearer")
