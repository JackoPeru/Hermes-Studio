"""Single-owner bearer auth. Pairing via local file, rotation supported."""
from __future__ import annotations
import secrets
from fastapi import Header, HTTPException
from .config import DATA_DIR, BEARER_ENV
import os

def _token_path():
    return DATA_DIR / "bearer.token"

def get_or_create_token() -> str:
    env = os.environ.get(BEARER_ENV)
    if env:
        return env
    p = _token_path()
    if p.exists():
        return p.read_text().strip()
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tok = secrets.token_urlsafe(32)
    p.write_text(tok)
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass
    return tok

async def require_auth(authorization: str | None = Header(default=None)):
    tok = get_or_create_token()
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing bearer")
    if authorization[len("Bearer "):].strip() != tok:
        raise HTTPException(401, "invalid bearer")
