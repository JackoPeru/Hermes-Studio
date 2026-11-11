"""Asset store: dedicated HermesStudio dirs, thumbnails in background, safe names."""
from __future__ import annotations
import re
from pathlib import Path
from PIL import Image
from .config import ASSETS_DIR, THUMBS_DIR

_SAFE = re.compile(r"[^A-Za-z0-9._-]+")

def safe_name(name: str) -> str:
    n = _SAFE.sub("_", name).strip("._") or "file"
    return n[:120]

def save_bytes(rel: str, data: bytes) -> Path:
    p = (ASSETS_DIR / safe_name(rel)).resolve()
    assert str(p).startswith(str(ASSETS_DIR.resolve())), "path traversal blocked"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    return p

def make_thumb(src: Path, thumb_name: str, size: int = 512) -> Path | None:
    try:
        THUMBS_DIR.mkdir(parents=True, exist_ok=True)
        dst = THUMBS_DIR / (safe_name(thumb_name) + ".jpg")
        with Image.open(src) as im:
            im.thumbnail((size, size))
            rgb = im.convert("RGB")
            rgb.save(dst, "JPEG", quality=80)
        return dst
    except Exception:
        return None
