"""Pydantic contracts — stable, engine-independent. v1."""
from __future__ import annotations
from enum import Enum
from typing import Literal
from pydantic import BaseModel, Field

class TaskType(str, Enum):
    image = "image"
    video = "video"

class JobStatus(str, Enum):
    queued = "queued"
    preparing = "preparing"
    running = "running"
    completed = "completed"
    failed = "failed"
    cancelling = "cancelling"
    cancelled = "cancelled"

class HealthOut(BaseModel):
    status: Literal["ok"] = "ok"
    backend_version: str
    api_version: str

class CapabilityTask(BaseModel):
    task: str
    engine: str
    model: str
    enabled: bool
    disabled_reason: str | None = None
    max_width: int | None = None
    max_height: int | None = None
    supports_reference: bool = False
    notes: str = ""

class CapabilitiesOut(BaseModel):
    image: list[CapabilityTask]
    video: list[CapabilityTask]
    style_presets_are_prompt_templates: bool = True

class JobCreate(BaseModel):
    taskType: TaskType
    engine: str | None = None
    model: str | None = None
    prompt: str = Field(min_length=1, max_length=4000)
    negative_prompt: str | None = Field(default=None, max_length=2000)
    width: int | None = Field(default=None, ge=64, le=2048)
    height: int | None = Field(default=None, ge=64, le=2048)
    steps: int | None = Field(default=None, ge=1, le=60)
    seed: int | None = None
    style_preset: str | None = None  # Cinematico/Anime/Realistico/Fantasy -> prompt template only
    asset_ids: list[str] = Field(default_factory=list)
    idempotency_key: str | None = Field(default=None, max_length=128)

class JobOut(BaseModel):
    id: str
    taskType: str
    status: str
    engine: str
    model: str
    prompt: str
    width: int | None = None
    height: int | None = None
    steps: int | None = None
    seed: int | None = None
    comfy_prompt_id: str | None = None
    progress: float | None = None
    error: str | None = None
    outputs: list[str] = Field(default_factory=list)
    created_at: str
    updated_at: str

class AssetOut(BaseModel):
    id: str
    kind: str
    path: str
    thumb: str | None = None
    job_id: str | None = None
    width: int | None = None
    height: int | None = None
    created_at: str

class ProjectIn(BaseModel):
    name: str = Field(min_length=1, max_length=120)

class ProjectOut(BaseModel):
    id: str
    name: str
    created_at: str
    asset_ids: list[str] = Field(default_factory=list)
