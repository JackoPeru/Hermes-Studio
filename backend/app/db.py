"""SQLite WAL store with minimal migrations. Jobs survive app close + backend restart."""
from __future__ import annotations
import json
import sqlite3
import time
import uuid
from pathlib import Path
from .config import DB_PATH

SCHEMA = """
PRAGMA journal_mode=WAL;
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
"""

def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    cx = sqlite3.connect(str(DB_PATH))
    cx.row_factory = sqlite3.Row
    cx.executescript(SCHEMA)
    return cx

def now() -> float:
    return time.time()

def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"

def iso(ts: float) -> str:
    import datetime
    return datetime.datetime.fromtimestamp(ts).astimezone().isoformat()
