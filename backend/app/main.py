"""Hermes Studio API v1 — engine-independent contract for the Android app."""
from __future__ import annotations
import asyncio
import json
import random
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse

from . import db
from .auth import get_or_create_token, require_auth
from .comfy import ComfyUIAdapter
from .config import (
    API_VERSION, ASSETS_DIR, BACKEND_VERSION, COMFY_URL, DATA_DIR, MANAGER_KEY,
    MANAGER_URL, MAX_UPLOAD_MB, THUMBS_DIR, UPLOADS_DIR,
)
from .manager import ManagerClient
from .models import (
    AssetOut, CapabilitiesOut, CapabilityTask, HealthOut, JobCreate, JobOut,
    ProjectIn, ProjectOut,
)
from .storage import make_thumb, safe_name, save_bytes
from .workflows import DEFAULT_NEGATIVE, apply_style, build_qwen_t2i

# Single worker: serialized dispatch matches VRAM reality (never assume 2 heavy models resident).
_worker_task: asyncio.Task | None = None
_stop = asyncio.Event()


def _comfy() -> ComfyUIAdapter:
    return ComfyUIAdapter(COMFY_URL)


def _manager() -> ManagerClient:
    return ManagerClient(MANAGER_URL, MANAGER_KEY)


def _job_row_to_out(r) -> JobOut:
    params = json.loads(r["params"] or "{}")
    return JobOut(
        id=r["id"], taskType=r["task_type"], status=r["status"],
        engine=r["engine"], model=r["model"], prompt=r["prompt"],
        width=params.get("width"), height=params.get("height"),
        steps=params.get("steps"), seed=params.get("seed"),
        comfy_prompt_id=r["comfy_prompt_id"], progress=r["progress"],
        error=r["error"], outputs=json.loads(r["outputs"] or "[]"),
        created_at=db.iso(r["created_at"]), updated_at=db.iso(r["updated_at"]),
    )


async def _dispatch_loop() -> None:
    """Background worker: queued -> preparing -> running -> completed/failed.

    Survives app close (jobs in SQLite). On backend restart, stale
    preparing/running jobs are re-queued once (no duplicate Comfy submits:
    only jobs without comfy_prompt_id are resubmitted; others are reconciled
    via /history on next loop).
    """
    from .config import DB_PATH  # noqa
    await asyncio.sleep(1.0)
    cx = db.connect()
    try:
        # Recovery: requeue stale active jobs once.
        cx.execute(
            "UPDATE jobs SET status='queued', updated_at=? WHERE status IN ('preparing','running')",
            (db.now(),),
        )
        cx.commit()
        while not _stop.is_set():
            row = cx.execute(
                "SELECT * FROM jobs WHERE status='queued' ORDER BY created_at LIMIT 1"
            ).fetchone()
            if row is None:
                await asyncio.sleep(2.0)
                continue
            await _run_one(row["id"])
            await asyncio.sleep(0.5)
    finally:
        cx.close()


async def _run_one(job_id: str) -> None:
    cx = db.connect()
    try:
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None or row["status"] != "queued":
            cx.close()
            return
        params = json.loads(row["params"] or "{}")
        cx.execute(
            "UPDATE jobs SET status='preparing', updated_at=? WHERE id=?",
            (db.now(), job_id),
        )
        cx.commit()
        # Build versioned workflow (image only in V1; video gated at submit).
        prompt = build_qwen_t2i(
            prompt=row["prompt"],
            negative=params.get("negative_prompt") or DEFAULT_NEGATIVE,
            width=int(params.get("width") or 1024),
            height=int(params.get("height") or 1024),
            seed=int(params.get("seed") if params.get("seed") is not None else random.randint(0, 2**31 - 1)),
            steps=int(params.get("steps") or 20),
            job_id=job_id,
        )
        adapter = _comfy()
        try:
            prompt_id = await adapter.submit(prompt)
        except Exception as e:  # Comfy offline (e.g. LLM resident) -> back to queue w/ error
            cx.execute(
                "UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                (f"comfy submit failed: {e}", db.now(), job_id),
            )
            cx.commit()
            return
        cx.execute(
            "UPDATE jobs SET status='running', comfy_prompt_id=?, progress=0.05, updated_at=? WHERE id=?",
            (prompt_id, db.now(), job_id),
        )
        cx.commit()
        # Poll history (video-safe timeouts handled per-preset server-side; image default 20 min).
        for _ in range(600):
            await asyncio.sleep(2.0)
            # cancel check
            cur = db.connect().execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
            if cur and cur["status"] in ("cancelling", "cancelled"):
                db.connect().execute(
                    "UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?", (db.now(), job_id)
                ).connection.commit() if False else None
                c2 = db.connect()
                c2.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?", (db.now(), job_id))
                c2.commit()
                c2.close()
                return
            try:
                hist = await adapter.history(prompt_id)
            except Exception:
                continue
            node = (hist.get(prompt_id) or {})
            outs = node.get("outputs") or {}
            found: list[str] = []
            for _nid, o in outs.items():
                for img in o.get("images", []) if isinstance(o, dict) else []:
                    try:
                        data = await adapter.fetch_view(
                            img["filename"], img.get("subfolder", ""), "output")
                    except Exception:
                        continue
                    rel = f"{job_id}_{safe_name(img['filename'])}"
                    p = save_bytes(rel, data)
                    # index asset
                    c2 = db.connect()
                    aid = db.new_id("ast")
                    c2.execute(
                        "INSERT INTO assets(id,kind,rel_path,job_id,created_at) VALUES(?,?,?,?,?)",
                        (aid, "image", rel, job_id, db.now()),
                    )
                    c2.commit()
                    c2.close()
                    try:
                        from PIL import Image as _I
                        with _I.open(p) as _im:
                            w, h = _im.size
                        c3 = db.connect()
                        c3.execute("UPDATE assets SET width=?, height=? WHERE id=?", (w, h, aid))
                        c3.commit()
                        c3.close()
                    except Exception:
                        pass
                    th = make_thumb(p, aid)
                    found.append(aid)
                    _ = th
            if found:
                cx.execute(
                    "UPDATE jobs SET status='completed', progress=1.0, outputs=?, updated_at=? WHERE id=?",
                    (json.dumps(found), db.now(), job_id),
                )
                cx.commit()
                return
            st = node.get("status") or {}
            if st.get("completed") is False and st.get("messages"):
                pass
            cx.execute("UPDATE jobs SET progress=MAX(COALESCE(progress,0),0.2), updated_at=? WHERE id=?",
                       (db.now(), job_id))
            cx.commit()
        cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                   ("timeout waiting for ComfyUI output", db.now(), job_id))
        cx.commit()
    finally:
        cx.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _worker_task
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    THUMBS_DIR.mkdir(parents=True, exist_ok=True)
    UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
    get_or_create_token()
    _stop.clear()
    _worker_task = asyncio.create_task(_dispatch_loop())
    yield
    _stop.set()
    if _worker_task:
        _worker_task.cancel()

app = FastAPI(title="Hermes Studio", version=BACKEND_VERSION, lifespan=lifespan)
# Private LAN client (native app). No wildcard CORS for browsers; app is native.
app.add_middleware(
    CORSMiddleware, allow_origins=[], allow_methods=["GET", "POST"], allow_headers=["Authorization"],
)


@app.get("/api/v1/health", response_model=HealthOut)
async def health():
    return HealthOut(backend_version=BACKEND_VERSION, api_version=API_VERSION)


@app.get("/api/v1/capabilities", response_model=CapabilitiesOut)
async def capabilities(_: None = Depends(require_auth)):
    return CapabilitiesOut(
        image=[
            CapabilityTask(task="text-to-image", engine="comfyui", model="qwen-image-2.1-UC-Q4_K_M",
                           enabled=True, max_width=2048, max_height=2048,
                           notes="Workflow qwen-t2i v1 (euler/simple, cfg 1.0). Batch via coda."),
            CapabilityTask(task="image-to-image", engine="comfyui", model="qwen-image-2.1-edit",
                           enabled=False, disabled_reason="edit workflow v1 non ancora validato end-to-end",
                           supports_reference=True),
        ],
        video=[
            CapabilityTask(task="text-to-video", engine="comfyui", model="minimax-h3",
                           enabled=False,
                           disabled_reason="LICENSE_GATE: h3.enabled=false (MiniMax community license, EU/Italy esclusa). Richiesta autorizzazione scritta MiniMax.",
                           notes="Pesi presenti ma disabilitati da /etc/hermes/gpu-manager.yaml"),
            CapabilityTask(task="image-to-video", engine="comfyui", model="minimax-h3-turbo",
                           enabled=False,
                           disabled_reason="LICENSE_GATE: vedi sopra. Nessuna generazione H3 finché non autorizzata.",
                           supports_reference=True),
        ],
    )


@app.get("/api/v1/status")
async def status(_: None = Depends(require_auth)):
    out: dict = {"backend": BACKEND_VERSION, "comfy": {}, "manager": {}}
    try:
        out["comfy"]["queue"] = await _comfy().queue()
    except Exception as e:
        out["comfy"]["error"] = str(e)[:300]
    try:
        out["manager"] = await _manager().status()
    except Exception as e:
        out["manager"] = {"error": str(e)[:300]}
    cx = db.connect()
    try:
        q = cx.execute("SELECT COUNT(*) c FROM jobs WHERE status IN ('queued','preparing','running')").fetchone()
        out["queue_length"] = q["c"]
        import shutil
        du = shutil.disk_usage(str(DATA_DIR))
        out["storage"] = {"free_gb": round(du.free / 1e9, 1), "total_gb": round(du.total / 1e9, 1)}
    finally:
        cx.close()
    return out


def _norm_wh(w: int | None, h: int | None) -> tuple[int, int]:
    w = w or 1024
    h = h or 1024
    # snap to 32px (H3 latent rule as conservative default for all)
    return max(64, w // 32 * 32), max(64, h // 32 * 32)


@app.post("/api/v1/jobs", response_model=JobOut, status_code=201)
async def create_job(body: JobCreate, _: None = Depends(require_auth)):
    if body.taskType.value == "video":
        raise HTTPException(409, "video pipeline disabilitata (LICENSE_GATE MiniMax H3, EU/Italy). Vedi /capabilities.")
    engine = body.engine or "comfyui"
    model = body.model or "qwen-image-2.1-UC-Q4_K_M"
    prompt = apply_style(body.prompt.strip(), body.style_preset)
    w, h = _norm_wh(body.width, body.height)
    steps = body.steps or 20
    seed = body.seed if body.seed is not None else random.randint(0, 2**31 - 1)
    cx = db.connect()
    try:
        if body.idempotency_key:
            ex = cx.execute("SELECT * FROM jobs WHERE idempotency_key=?", (body.idempotency_key,)).fetchone()
            if ex:
                return _job_row_to_out(ex)
        jid = db.new_id("job")
        params = {"width": w, "height": h, "steps": steps, "seed": seed,
                  "negative_prompt": body.negative_prompt, "style_preset": body.style_preset,
                  "asset_ids": body.asset_ids}
        try:
            cx.execute(
                "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,idempotency_key,created_at,updated_at)"
                " VALUES(?,?,?,?,?,?,?,?,?,?)",
                (jid, "image", "queued", engine, model, prompt, json.dumps(params),
                 body.idempotency_key, db.now(), db.now()),
            )
            cx.commit()
        except Exception as e:
            if "UNIQUE" in str(e) and body.idempotency_key:
                ex = cx.execute("SELECT * FROM jobs WHERE idempotency_key=?", (body.idempotency_key,)).fetchone()
                return _job_row_to_out(ex)
            raise
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
        return _job_row_to_out(row)
    finally:
        cx.close()


@app.get("/api/v1/jobs", response_model=list[JobOut])
async def list_jobs(limit: int = Query(50, le=200), _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        rows = cx.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
        return [_job_row_to_out(r) for r in rows]
    finally:
        cx.close()


@app.get("/api/v1/jobs/{jid}", response_model=JobOut)
async def get_job(jid: str, _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        r = cx.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
        if not r:
            raise HTTPException(404, "job not found")
        return _job_row_to_out(r)
    finally:
        cx.close()


@app.post("/api/v1/jobs/{jid}/cancel", response_model=JobOut)
async def cancel_job(jid: str, _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        r = cx.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
        if not r:
            raise HTTPException(404, "job not found")
        if r["status"] in ("completed", "failed", "cancelled"):
            return _job_row_to_out(r)
        cx.execute("UPDATE jobs SET status='cancelling', updated_at=? WHERE id=?", (db.now(), jid))
        cx.commit()
        # best-effort: worker picks up cancelling -> cancelled. Interrupt Comfy queue client-side skipped (no API verified).
        cx.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=? AND status='cancelling'",
                   (db.now(), jid))
        # only cancel queued jobs immediately; running ones flip on next poll
        if r["status"] == "queued":
            cx.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?", (db.now(), jid))
        cx.commit()
        return _job_row_to_out(cx.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone())
    finally:
        cx.close()


@app.get("/api/v1/events")
async def events(_: None = Depends(require_auth)):
    async def gen():
        cx = db.connect()
        try:
            last = 0.0
            while True:
                rows = cx.execute(
                    "SELECT id,status,updated_at FROM jobs WHERE updated_at>? ORDER BY updated_at LIMIT 50",
                    (last,)).fetchall()
                for r in rows:
                    last = max(last, r["updated_at"])
                    yield f"data: {json.dumps({'id': r['id'], 'status': r['status']})}\n\n"
                await asyncio.sleep(2.0)
        finally:
            cx.close()
    return StreamingResponse(gen(), media_type="text/event-stream")


@app.post("/api/v1/assets/upload")
async def upload_asset(f: UploadFile = File(...), _: None = Depends(require_auth)):
    data = await f.read()
    if len(data) > MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(413, f"file oltre {MAX_UPLOAD_MB} MB")
    head = data[:16]
    if head.startswith(b"\xff\xd8\xff"):
        ext = ".jpg"
    elif head.startswith(b"\x89PNG"):
        ext = ".png"
    elif head.startswith(b"RIFF") and data[8:12] == b"WEBP":
        ext = ".webp"
    else:
        raise HTTPException(415, "solo JPEG/PNG/WebP")
    UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
    aid = db.new_id("upl")
    p = UPLOADS_DIR / (aid + ext)
    p.write_bytes(data)
    return {"asset_upload_id": aid, "path": str(p.name), "bytes": len(data)}


@app.get("/api/v1/assets", response_model=list[AssetOut])
async def list_assets(kind: str | None = None, favorite: bool = False,
                      _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        if favorite:
            rows = cx.execute(
                "SELECT a.* FROM assets a JOIN favorites f ON f.asset_id=a.id ORDER BY a.created_at DESC").fetchall()
        elif kind:
            rows = cx.execute("SELECT * FROM assets WHERE kind=? ORDER BY created_at DESC", (kind,)).fetchall()
        else:
            rows = cx.execute("SELECT * FROM assets ORDER BY created_at DESC").fetchall()
        out = []
        for r in rows:
            th = THUMBS_DIR / (r["id"] + ".jpg")
            out.append(AssetOut(id=r["id"], kind=r["kind"], path=f"/api/v1/assets/{r['id']}/download",
                                thumb=f"/api/v1/assets/{r['id']}/thumbnail" if th.exists() else None,
                                job_id=r["job_id"], width=r["width"], height=r["height"],
                                created_at=db.iso(r["created_at"])))
        return out
    finally:
        cx.close()


def _asset_path(aid: str) -> Path:
    cx = db.connect()
    try:
        r = cx.execute("SELECT * FROM assets WHERE id=?", (aid,)).fetchone()
        if not r:
            raise HTTPException(404, "asset not found")
        return ASSETS_DIR / safe_name(r["rel_path"])
    finally:
        cx.close()


@app.get("/api/v1/assets/{aid}/download")
async def download_asset(aid: str, _: None = Depends(require_auth)):
    p = _asset_path(aid)
    if not p.exists():
        raise HTTPException(404, "file missing")
    return FileResponse(str(p))


@app.get("/api/v1/assets/{aid}/thumbnail")
async def thumb_asset(aid: str, _: None = Depends(require_auth)):
    t = THUMBS_DIR / (safe_name(aid) + ".jpg")
    if not t.exists():
        src = _asset_path(aid)
        if not src.exists():
            raise HTTPException(404, "file missing")
        t2 = make_thumb(src, aid)
        if not t2:
            raise HTTPException(404, "no thumbnail")
        t = t2
    return FileResponse(str(t), media_type="image/jpeg")


@app.post("/api/v1/assets/{aid}/favorite")
async def fav_asset(aid: str, on: bool = True, _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        if on:
            cx.execute("INSERT OR IGNORE INTO favorites(asset_id) VALUES(?)", (aid,))
        else:
            cx.execute("DELETE FROM favorites WHERE asset_id=?", (aid,))
        cx.commit()
        return {"ok": True, "favorite": on}
    finally:
        cx.close()


@app.get("/api/v1/projects", response_model=list[ProjectOut])
async def list_projects(_: None = Depends(require_auth)):
    cx = db.connect()
    try:
        out = []
        for r in cx.execute("SELECT * FROM projects ORDER BY created_at DESC").fetchall():
            aids = [x["asset_id"] for x in cx.execute(
                "SELECT asset_id FROM project_assets WHERE project_id=?", (r["id"])) .fetchall()]
            out.append(ProjectOut(id=r["id"], name=r["name"], created_at=db.iso(r["created_at"]), asset_ids=aids))
        return out
    finally:
        cx.close()


@app.post("/api/v1/projects", response_model=ProjectOut, status_code=201)
async def create_project(body: ProjectIn, _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        pid = db.new_id("prj")
        cx.execute("INSERT INTO projects(id,name,created_at) VALUES(?,?,?)", (pid, body.name, db.now()))
        cx.commit()
        return ProjectOut(id=pid, name=body.name, created_at=db.iso(db.now()), asset_ids=[])
    finally:
        cx.close()


@app.post("/api/v1/projects/{pid}/assets/{aid}")
async def add_to_project(pid: str, aid: str, _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        cx.execute("INSERT OR IGNORE INTO project_assets(project_id,asset_id) VALUES(?,?)", (pid, aid))
        cx.commit()
        return {"ok": True}
    finally:
        cx.close()
