"""Asset store: dedicated HermesStudio dirs, thumbnails in background, safe names."""
from __future__ import annotations
import json
import re
import subprocess
from pathlib import Path
from PIL import Image
from .config import THUMBS_DIR

_SAFE = re.compile(r"[^A-Za-z0-9._-]+")

def safe_name(name: str) -> str:
    n = _SAFE.sub("_", name).strip("._") or "file"
    return n[:120]

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


def probe_video(src: Path) -> dict:
    """ffprobe: durata secondi + dimensioni. Vuoto se illeggibile (mai eccezioni)."""
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "format=duration:stream=width,height", "-of", "json", str(src)],
            capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            return {}
        data = json.loads(r.stdout or "{}")
        out: dict = {}
        try:
            out["duration"] = float((data.get("format") or {}).get("duration") or 0) or 0
        except (TypeError, ValueError):
            pass
        for st in data.get("streams") or []:
            if st.get("width") and st.get("height"):
                out["width"] = int(st["width"])
                out["height"] = int(st["height"])
                break
        return out
    except Exception:
        return {}


def video_thumb(src: Path, thumb_name: str, size: int = 512) -> Path | None:
    """Fotogramma a ~0.5s come thumbnail JPG. None se ffmpeg fallisce."""
    try:
        THUMBS_DIR.mkdir(parents=True, exist_ok=True)
        dst = THUMBS_DIR / (safe_name(thumb_name) + ".jpg")
        tmp = dst.with_suffix(".part.jpg")
        r = subprocess.run(
            ["ffmpeg", "-y", "-v", "error", "-ss", "0.5", "-i", str(src),
             "-vframes", "1", "-vf", f"scale={size}:-1", str(tmp)],
            capture_output=True, timeout=60)
        if r.returncode != 0 or not tmp.is_file():
            try:
                tmp.unlink()
            except OSError:
                pass
            return None
        tmp.replace(dst)
        return dst
    except Exception:
        return None
