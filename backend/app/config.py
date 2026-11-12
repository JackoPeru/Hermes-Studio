"""Hermes Studio backend — single-owner private FastAPI service (V1)."""
from __future__ import annotations
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("HERMES_STUDIO_DATA", str(BASE_DIR / ".." / "data"))).resolve()
ASSETS_DIR = DATA_DIR / "assets"
THUMBS_DIR = DATA_DIR / "thumbs"
UPLOADS_DIR = DATA_DIR / "uploads"
DB_PATH = DATA_DIR / "studio.db"

API_VERSION = "1.0.0"
BACKEND_VERSION = "0.1.0"

# Private bind by default: loopback only. Tailscale Serve runs in front when configured.
HOST = os.environ.get("HERMES_STUDIO_HOST", "127.0.0.1")
PORT = int(os.environ.get("HERMES_STUDIO_PORT", "8650"))

# Single-owner bearer. Never hardcoded: generated on first start into DATA_DIR/bearer.token (0600).
BEARER_ENV = "HERMES_STUDIO_BEARER"

COMFY_URL = os.environ.get("HERMES_STUDIO_COMFY_URL", "http://127.0.0.1:8188")
MANAGER_URL = os.environ.get("HERMES_STUDIO_MANAGER_URL", "http://127.0.0.1:8643")
MANAGER_KEY = os.environ.get("HERMES_STUDIO_MANAGER_KEY", "")

MAX_UPLOAD_MB = int(os.environ.get("HERMES_STUDIO_MAX_UPLOAD_MB", "25"))
