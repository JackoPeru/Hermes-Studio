"""SQLite WAL store with minimal migrations. Jobs survive app close + backend restart."""
from __future__ import annotations
import sqlite3
import time
import uuid
from .config import DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs(
  id TEXT PRIMARY KEY,
  task_type TEXT NOT NULL,
  status TEXT NOT NULL,
  engine TEXT NOT NULL,
  model TEXT NOT NULL,
  prompt TEXT NOT NULL,
  params TEXT NOT NULL DEFAULT '{}',
  comfy_prompt_id TEXT,
  progress REAL,
  error TEXT,
  outputs TEXT NOT NULL DEFAULT '[]',
  idempotency_key TEXT UNIQUE,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS assets(
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  rel_path TEXT NOT NULL,
  job_id TEXT,
  width INTEGER,
  height INTEGER,
  duration REAL,
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS projects(
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS project_assets(
  project_id TEXT NOT NULL,
  asset_id TEXT NOT NULL,
  PRIMARY KEY(project_id, asset_id)
);
CREATE TABLE IF NOT EXISTS favorites(
  asset_id TEXT PRIMARY KEY
);
CREATE INDEX IF NOT EXISTS idx_jobs_status_created ON jobs(status, created_at);
CREATE INDEX IF NOT EXISTS idx_assets_created ON assets(created_at);
"""

_init_done_for: dict[str, tuple] = {}

# Colonne attese (tabella, colonna, DDL): DB vecchi vengono completati,
# mai ricreati. Chiave di cache: path + mtime (se il file cambia, re-init).
_EXPECTED_COLUMNS: list[tuple[str, str, str]] = [
    ("jobs", "idempotency_key", "TEXT UNIQUE"),
    ("jobs", "comfy_prompt_id", "TEXT"),
    ("jobs", "progress", "REAL"),
    ("jobs", "error", "TEXT"),
    ("jobs", "outputs", "TEXT NOT NULL DEFAULT '[]'"),
    ("assets", "job_id", "TEXT"),
    ("assets", "width", "INTEGER"),
    ("assets", "height", "INTEGER"),
    ("assets", "duration", "REAL"),
]

def _migrate(cx: sqlite3.Connection) -> None:
    for table, col, ddl in _EXPECTED_COLUMNS:
        try:
            cols = {r[1] for r in cx.execute(f"PRAGMA table_info({table})").fetchall()}
        except Exception:
            continue
        if table in {r[0] for r in cx.execute(
                "SELECT name FROM sqlite_master WHERE type='table'").fetchall()} and col not in cols:
            cx.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")


def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    cx = sqlite3.connect(str(DB_PATH), timeout=30.0, check_same_thread=False)
    cx.row_factory = sqlite3.Row
    key = str(DB_PATH)
    # Identità file (dev+inode, non mtime che cambia a ogni write): se il DB
    # viene cancellato/sostituito esternamente, re-init invece di errori strani.
    try:
        ident = (DB_PATH.stat().st_dev, DB_PATH.stat().st_ino)
    except OSError:
        ident = (0, 0)
    if _init_done_for.get(key) != ident:
        cx.execute("PRAGMA journal_mode=WAL;")
        cx.execute("PRAGMA busy_timeout=30000;")
        cx.executescript(SCHEMA)
        _migrate(cx)
        cx.commit()
        _init_done_for[key] = ident
    else:
        # Light per-connection pragmas only (no full schema rewrite).
        try:
            cx.execute("PRAGMA busy_timeout=30000;")
        except Exception:
            pass
    return cx

def now() -> float:
    return time.time()

def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"

def iso(ts: float) -> str:
    import datetime
    return datetime.datetime.fromtimestamp(ts).astimezone().isoformat()
