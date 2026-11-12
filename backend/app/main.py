"""Hermes Studio API v1 — engine-independent contract for the Android app."""
from __future__ import annotations
import asyncio
import json
import logging
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
    AssetOut, CapabilitiesOut, CapabilityTask, GpuOut, HealthOut, JobCreate, JobOut,
    ProjectIn, ProjectOut,
)
from .storage import make_thumb, probe_video, safe_name, video_thumb
from .workflows import DEFAULT_NEGATIVE, apply_style

# Single worker: serialized dispatch matches VRAM reality (never assume 2 heavy models resident).
_worker_task: asyncio.Task | None = None
_stop = asyncio.Event()
_log = logging.getLogger("hermes-studio")


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
    preparing jobs (mai sottomessi) tornano queued; running con
    comfy_prompt_id restano running e vengono riconciliati via /history,
    running senza prompt_id tornano queued.
    """
    from .config import DB_PATH  # noqa
    await asyncio.sleep(1.0)
    cx = db.connect()
    try:
        # Recovery: preparing mai sottomessi -> queued; running orfani -> queued
        # solo se senza prompt_id, altrimenti restano running per reconcile.
        cx.execute(
            "UPDATE jobs SET status='queued', updated_at=? WHERE status='preparing'",
            (db.now(),),
        )
        cx.execute(
            "UPDATE jobs SET status='queued', updated_at=? WHERE status='running' "
            "AND (comfy_prompt_id IS NULL OR comfy_prompt_id='')",
            (db.now(),),
        )
        cx.commit()
    finally:
        cx.close()
    while not _stop.is_set():
        cx = db.connect()
        try:
            row = cx.execute(
                "SELECT * FROM jobs WHERE status='queued' ORDER BY created_at LIMIT 1"
            ).fetchone()
            jid = row["id"] if row else None
        finally:
            cx.close()
        if jid is None:
            await asyncio.sleep(2.0)
            continue
        try:
            await _run_one(jid)
        except asyncio.CancelledError:
            raise
        except Exception:
            # Un singolo job guasto non deve mai uccidere il worker:
            # logga e marca failed, gli altri job continuano.
            _log.exception("worker: job %s crashato fuori dal flusso gestito", jid)
            try:
                c2 = db.connect()
                try:
                    c2.execute(
                        "UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=? "
                        "AND status IN ('queued','preparing','running')",
                        ("worker crash: vedi log servizio", db.now(), jid))
                    c2.commit()
                finally:
                    c2.close()
            except Exception:
                pass
        await asyncio.sleep(0.5)


async def _run_one(job_id: str) -> None:
    cx = db.connect()
    try:
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None:
            return
        task_type = row["task_type"]
    finally:
        cx.close()
    if task_type == "video":
        await _run_one_video(job_id)
    else:
        await _run_one_image(job_id)


async def _run_one_image(job_id: str) -> None:
    """Text-to-image via GPU-manager (preset create_image, Qwen 2.1).
    Niente Comfy diretto: in AUTO il manager fa unload LLM -> avvio Comfy ->
    run -> restore LLM. Il submit accoda, il resto lo orchestra lui."""
    cx = db.connect()
    try:
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None or row["status"] not in ("queued", "running"):
            return
        # Reconcile: se ha già un manager id (restart), non risottomettere.
        existing_mid = row["comfy_prompt_id"]
        prompt_text = row["prompt"]
        params = json.loads(row["params"] or "{}")
        if row["status"] == "queued":
            cx.execute(
                "UPDATE jobs SET status='preparing', updated_at=? WHERE id=?",
                (db.now(), job_id),
            )
            cx.commit()
    finally:
        cx.close()

    cx = db.connect()
    try:
        mgr = _manager()
        mid = existing_mid
        if not mid:
            try:
                res = await mgr.submit_image("create_image", {
                    "prompt": prompt_text,
                    "negative_prompt": params.get("negative_prompt") or DEFAULT_NEGATIVE,
                    "width": int(params.get("width") or 1024),
                    "height": int(params.get("height") or 1024),
                    "seed": int(params.get("seed") if params.get("seed") is not None else random.randint(0, 2**31 - 1)),
                    "steps": int(params.get("steps") or 20),
                })
                mid = str(res.get("job_id") or res.get("id") or "")
                if not mid:
                    raise ValueError(f"manager senza job_id: {res}")
            except Exception as e:  # Manager giù o coda piena -> failed onesto
                cx.execute(
                    "UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                    (f"manager submit failed: {e}", db.now(), job_id),
                )
                cx.commit()
                return
            cx.execute(
                "UPDATE jobs SET status='running', comfy_prompt_id=?, progress=0.05, updated_at=? WHERE id=?",
                (mid, db.now(), job_id),
            )
            cx.commit()
        # Poll manager (primo run include unload LLM + boot Comfy: 5s x 360 = 30min max).
        for _ in range(360):
            await asyncio.sleep(5.0)
            # cancel check (connessione breve, sempre chiusa)
            cc = db.connect()
            try:
                cur = cc.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
                cur_status = cur["status"] if cur else None
            finally:
                cc.close()
            if cur_status in ("cancelling", "cancelled"):
                try:
                    await mgr.cancel_job(mid)
                except Exception:
                    pass
                cc2 = db.connect()
                try:
                    cc2.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?", (db.now(), job_id))
                    cc2.commit()
                finally:
                    cc2.close()
                return
            if cur_status not in ("running", "preparing"):
                return
            try:
                st = await mgr.get_job(mid)
            except Exception:
                continue
            mstatus = str(st.get("status") or "")
            try:
                prog = float(st.get("progress") or 0)
            except (TypeError, ValueError):
                prog = 0.0
            if prog > 0:
                cx.execute("UPDATE jobs SET progress=?, updated_at=? WHERE id=?",
                           (max(0.05, min(0.99, prog)), db.now(), job_id))
                cx.commit()
            if mstatus == "done":
                await _finish_image_job(cx, job_id, st)
                return
            if mstatus in ("failed", "cancelled"):
                cx.execute("UPDATE jobs SET status=?, error=?, updated_at=? WHERE id=?",
                           ("cancelled" if mstatus == "cancelled" else "failed",
                            str(st.get("error") or "")[:500], db.now(), job_id))
                cx.commit()
                return
            cx.execute("UPDATE jobs SET progress=MAX(COALESCE(progress,0),0.1), updated_at=? WHERE id=?",
                       (db.now(), job_id))
            cx.commit()
        cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                   ("timeout attesa manager image", db.now(), job_id))
        cx.commit()
    finally:
        try:
            cx.close()
        except Exception:
            pass


async def _finish_image_job(cx, job_id: str, st: dict) -> None:
    from pathlib import Path as _P
    paths = st.get("result_paths") or []
    if isinstance(paths, str):
        paths = [paths]

    def rank(p: str) -> int:
        n = p.lower()
        if n.endswith((".png", ".jpg", ".jpeg", ".webp")):
            return 0
        return 1

    src = None
    for p in sorted([x for x in paths if isinstance(x, str)], key=rank):
        pp = _P(p)
        try:
            if pp.is_file() and not pp.is_symlink():
                src = pp
                break
        except OSError:
            continue
    if src is None:
        cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                   ("manager done ma nessun file immagine", db.now(), job_id))
        cx.commit()
        return
    import shutil as _sh
    rel = f"{job_id}_{safe_name(src.name)}"
    dest = (ASSETS_DIR / safe_name(rel)).resolve()
    assert str(dest).startswith(str(ASSETS_DIR.resolve())), "path traversal blocked"
    dest.parent.mkdir(parents=True, exist_ok=True)
    await asyncio.to_thread(_sh.copy2, src, dest)
    aid = db.new_id("ast")
    cx.execute(
        "INSERT INTO assets(id,kind,rel_path,job_id,created_at) VALUES(?,?,?,?,?)",
        (aid, "image", rel, job_id, db.now()),
    )
    cx.commit()
    try:
        from PIL import Image as _I
        with _I.open(dest) as _im:
            w, h = _im.size
        cx.execute("UPDATE assets SET width=?, height=? WHERE id=?", (w, h, aid))
        cx.commit()
    except Exception:
        pass
    await asyncio.to_thread(make_thumb, dest, aid)
    cx.execute(
        "UPDATE jobs SET status='completed', progress=1.0, outputs=?, updated_at=? WHERE id=?",
        (json.dumps([aid]), db.now(), job_id),
    )
    cx.commit()


async def _run_one_video(job_id: str) -> None:
    """Image-to-video via GPU-manager (preset turbo H3). Niente Comfy diretto:
    la coda/VRAM resta arbitrata dal manager."""
    cx = db.connect()
    try:
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None or row["status"] not in ("queued", "running"):
            return
        prompt_text = row["prompt"]
        existing_mid = row["comfy_prompt_id"]
        params = json.loads(row["params"] or "{}")
        if row["status"] == "queued":
            cx.execute(
                "UPDATE jobs SET status='preparing', updated_at=? WHERE id=?",
                (db.now(), job_id),
            )
            cx.commit()
    finally:
        cx.close()

    cx = db.connect()
    try:
        mgr = _manager()
        mid = existing_mid
        if not mid:
            aids = params.get("asset_ids") or []
            src_path = None
            if aids:
                rr = cx.execute("SELECT rel_path FROM assets WHERE id=?", (aids[0],)).fetchone()
                if rr:
                    cand = ASSETS_DIR / safe_name(rr["rel_path"])
                    if cand.is_file():
                        src_path = str(cand.resolve())
            if not src_path:
                cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                           ("immagine di partenza non trovata in libreria", db.now(), job_id))
                cx.commit()
                return
            # Il manager accetta input solo da root fidate (hub uploads, runtimes,
            # outputs, /tmp): staging in ~/.hermes/hermes-studio-in (root fidata
            # E scrivibile: la unit ha ReadWritePaths dedicato; /tmp e /opt non
            # sono scrivibili nel sandbox ProtectSystem=strict). Submit, poi pulizia.
            from pathlib import Path as _P
            import shutil as _sh2
            stage_dir = _P.home() / ".hermes" / "hermes-studio-in"
            stage_dir.mkdir(parents=True, exist_ok=True)
            stage = stage_dir / f"hs_{job_id}_in{_P(src_path).suffix.lower() or '.png'}"
            try:
                await asyncio.to_thread(_sh2.copy2, src_path, stage)
            except Exception as e:
                cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                           (f"staging input fallito: {e}", db.now(), job_id))
                cx.commit()
                return
            preset = params.get("video_preset") or "journey_video_preview"
            try:
                res = await mgr.submit_video(preset, {
                    "prompt": prompt_text,
                    "input_images": [str(stage)],
                    "width": int(params.get("width") or 1344),
                    "height": int(params.get("height") or 768),
                    "seed": int(params.get("seed") if params.get("seed") is not None else random.randint(0, 2**31 - 1)),
                })
                mid = str(res.get("job_id") or res.get("id") or "")
                if not mid:
                    raise ValueError(f"manager senza job_id: {res}")
            except Exception as e:
                cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                           (f"manager submit failed: {e}", db.now(), job_id))
                cx.commit()
                return
            finally:
                try:
                    stage.unlink()
                except OSError:
                    pass
            cx.execute(
                "UPDATE jobs SET status='running', comfy_prompt_id=?, progress=0.05, updated_at=? WHERE id=?",
                (mid, db.now(), job_id),
            )
            cx.commit()
        # Poll manager (render lunghi: 10s x 540 = 90min max, seriale come le immagini).
        for _ in range(540):
            await asyncio.sleep(10.0)
            cc = db.connect()
            try:
                cur = cc.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
                cur_status = cur["status"] if cur else None
            finally:
                cc.close()
            if cur_status in ("cancelling", "cancelled"):
                try:
                    await mgr.cancel_job(mid)
                except Exception:
                    pass
                cc2 = db.connect()
                try:
                    cc2.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?", (db.now(), job_id))
                    cc2.commit()
                finally:
                    cc2.close()
                return
            if cur_status not in ("running", "preparing"):
                return
            try:
                st = await mgr.get_job(mid)
            except Exception:
                continue
            mstatus = str(st.get("status") or "")
            try:
                prog = float(st.get("progress") or 0)
            except (TypeError, ValueError):
                prog = 0.0
            if prog > 0:
                cx.execute("UPDATE jobs SET progress=?, updated_at=? WHERE id=?",
                           (max(0.05, min(0.99, prog)), db.now(), job_id))
                cx.commit()
            if mstatus == "done":
                await _finish_video_job(cx, job_id, st)
                return
            if mstatus in ("failed", "cancelled"):
                cx.execute("UPDATE jobs SET status=?, error=?, updated_at=? WHERE id=?",
                           ("cancelled" if mstatus == "cancelled" else "failed",
                            str(st.get("error") or "")[:500], db.now(), job_id))
                cx.commit()
                return
            cx.execute("UPDATE jobs SET progress=MAX(COALESCE(progress,0),0.1), updated_at=? WHERE id=?",
                       (db.now(), job_id))
            cx.commit()
        cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                   ("timeout attesa manager video", db.now(), job_id))
        cx.commit()
    finally:
        try:
            cx.close()
        except Exception:
            pass


async def _finish_video_job(cx, job_id: str, st: dict) -> None:
    from pathlib import Path as _P
    paths = st.get("result_paths") or []
    if isinstance(paths, str):
        paths = [paths]

    def rank(p: str) -> int:
        n = p.lower()
        if n.endswith(".h264.mp4"):
            return 0
        if n.endswith(".mp4"):
            return 1
        if n.endswith((".webm", ".mov", ".mkv", ".m4v")):
            return 2
        return 3

    src = None
    for p in sorted([x for x in paths if isinstance(x, str)], key=rank):
        pp = _P(p)
        try:
            if pp.is_file() and not pp.is_symlink():
                src = pp
                break
        except OSError:
            continue
    if src is None:
        cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                   ("manager done ma nessun file video", db.now(), job_id))
        cx.commit()
        return
    # Copia in asset store (mp4 diretto; APNG/altro convertito per Android).
    import shutil as _sh
    if src.suffix.lower() in (".mp4", ".webm", ".mov", ".mkv", ".m4v"):
        rel = f"{job_id}_{safe_name(src.name)}"
        dest = (ASSETS_DIR / safe_name(rel)).resolve()
        assert str(dest).startswith(str(ASSETS_DIR.resolve())), "path traversal blocked"
        dest.parent.mkdir(parents=True, exist_ok=True)
        await asyncio.to_thread(_sh.copy2, src, dest)
    else:
        rel = f"{job_id}_video.mp4"
        dest = ASSETS_DIR / safe_name(rel)
        import subprocess as _sp
        try:
            r = await asyncio.to_thread(
                _sp.run,
                ["ffmpeg", "-y", "-v", "error", "-i", str(src),
                 "-c:v", "libx264", "-preset", "medium", "-crf", "20",
                 "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-r", "24", str(dest)],
                capture_output=True, timeout=900)
            if r.returncode != 0 or not dest.is_file():
                raise RuntimeError("conversione mp4 fallita")
        except Exception as e:
            cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                       (f"ffmpeg convert failed: {e}", db.now(), job_id))
            cx.commit()
            return
    meta = await asyncio.to_thread(probe_video, dest)
    aid = db.new_id("ast")
    cx.execute(
        "INSERT INTO assets(id,kind,rel_path,job_id,width,height,duration,created_at) VALUES(?,?,?,?,?,?,?,?)",
        (aid, "video", rel, job_id, meta.get("width"), meta.get("height"),
         meta.get("duration"), db.now()),
    )
    cx.commit()
    th = await asyncio.to_thread(video_thumb, dest, aid)
    _ = th
    cx.execute(
        "UPDATE jobs SET status='completed', progress=1.0, outputs=?, updated_at=? WHERE id=?",
        (json.dumps([aid]), db.now(), job_id),
    )
    cx.commit()


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
# OPTIONS incluso per preflight di eventuali WebView/debug locali.
app.add_middleware(
    CORSMiddleware, allow_origins=[], allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Authorization"],
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
                           disabled_reason="Nessun preset text-to-video nel manager (solo template grezzo h3/t2v.json, mai validato).",
                           notes="Pesi H3 presenti ma senza preset T2V attivo lato manager."),
            CapabilityTask(task="image-to-video", engine="comfyui", model="minimax-h3-turbo-4step",
                           enabled=True, max_width=1344, max_height=1344,
                           notes="Preset manager journey_video_preview (16:9) / chained (9:16), turbo 4-step, ~5s @24fps. Richiede immagine di partenza.",
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
    # Campi piatti per client tipizzati (Moshi): stato orchestrazione GPU.
    try:
        out["manager_state"] = str(out["manager"].get("current_state") or "")
        out["manager_desired"] = str(out["manager"].get("desired_mode") or "")
    except Exception:
        out["manager_state"] = ""
        out["manager_desired"] = ""
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


@app.get("/api/v1/gpu", response_model=list[GpuOut])
async def gpu(_: None = Depends(require_auth)):
    """Telemetria GPU live via nvidia-smi (server hermes). 503 se assente."""
    import shutil as _sh
    import subprocess as _sp
    if not _sh.which("nvidia-smi"):
        raise HTTPException(503, "nvidia-smi non disponibile")
    try:
        r = await asyncio.to_thread(
            _sp.run,
            ["nvidia-smi", "--query-gpu=index,name,temperature.gpu,utilization.gpu,memory.used,memory.total",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=10)
    except Exception as e:
        raise HTTPException(503, f"nvidia-smi fallito: {e}")
    if r.returncode != 0:
        raise HTTPException(503, f"nvidia-smi errore: {(r.stderr or '')[:200]}")
    out: list[GpuOut] = []
    for line in (r.stdout or "").splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 5:
            continue
        try:
            idx = int(float(parts[0]))
        except (TypeError, ValueError):
            continue

        def num(x: str) -> float | None:
            try:
                return float(x.split()[0].replace("%", ""))
            except (TypeError, ValueError, IndexError):
                return None

        out.append(GpuOut(index=idx, name=parts[1], temp_c=num(parts[2]),
                          util_pct=num(parts[3]), mem_used_mb=num(parts[4]),
                          mem_total_mb=num(parts[5]) if len(parts) > 5 else None))
    return out


def _norm_wh(w: int | None, h: int | None) -> tuple[int, int]:
    w = w or 1024
    h = h or 1024
    # snap to 32px (H3 latent rule as conservative default for all)
    w, h = max(64, w // 32 * 32), max(64, h // 32 * 32)
    # Area cap del manager (validate_workflow: oltre -> 400 onesto).
    # Stesso tetto dei preset video turbo: 1344x768 / 768x1344 passano netti.
    if w * h > _AREA_CAP_PX:
        import math as _math
        s = _math.sqrt(_AREA_CAP_PX / (w * h))
        w, h = max(64, int(w * s) // 32 * 32), max(64, int(h * s) // 32 * 32)
    return w, h


# Preset video verificati sul server (turbo 4-step, ~5s @24fps).
# 16:9 e 9:16 mappano ai preset manager journey_video_preview / chained.
# Full-steps (quality/first_last/reference) restano DISABLED upstream (OOM).
# Area cap del manager (validate_workflow rifiuta oltre con 400 onesto):
# 1344x768 e 768x1344 passano netti, 1024x1024 no -> clamp in _norm_wh.
_AREA_CAP_PX = 1032192
VIDEO_PRESETS = {
    "wide": ("journey_video_preview", 1344, 768),
    "tall": ("journey_video_preview_chained", 768, 1344),
}


def _video_preset_for(w: int, h: int) -> tuple[str, int, int]:
    if h > w:
        return VIDEO_PRESETS["tall"]
    return VIDEO_PRESETS["wide"]


@app.post("/api/v1/jobs", response_model=JobOut, status_code=201)
async def create_job(body: JobCreate, _: None = Depends(require_auth)):
    is_video = body.taskType.value == "video"
    engine = body.engine or "comfyui"
    if is_video:
        if not body.asset_ids:
            raise HTTPException(422, "video richiede asset_ids con almeno 1 immagine di partenza")
        model = body.model or "minimax-h3-turbo-4step"
        prompt = body.prompt.strip()
        preset, pw, ph = _video_preset_for(body.width or 1344, body.height or 768)
        w, h = _norm_wh(pw, ph)
        steps = body.steps or 4
        seed = body.seed if body.seed is not None else random.randint(0, 2**31 - 1)
        params = {"width": w, "height": h, "steps": steps, "seed": seed,
                  "negative_prompt": body.negative_prompt, "style_preset": None,
                  "asset_ids": body.asset_ids, "video_preset": preset}
        task_type = "video"
    else:
        model = body.model or "qwen-image-2.1-UC-Q4_K_M"
        prompt = apply_style(body.prompt.strip(), body.style_preset)
        w, h = _norm_wh(body.width, body.height)
        steps = body.steps or 20
        seed = body.seed if body.seed is not None else random.randint(0, 2**31 - 1)
        params = {"width": w, "height": h, "steps": steps, "seed": seed,
                  "negative_prompt": body.negative_prompt, "style_preset": body.style_preset,
                  "asset_ids": body.asset_ids, "image_preset": "create_image"}
        task_type = "image"
    cx = db.connect()
    try:
        if body.idempotency_key:
            ex = cx.execute("SELECT * FROM jobs WHERE idempotency_key=?", (body.idempotency_key,)).fetchone()
            if ex:
                return _job_row_to_out(ex)
        jid = db.new_id("job")
        try:
            cx.execute(
                "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,idempotency_key,created_at,updated_at)"
                " VALUES(?,?,?,?,?,?,?,?,?,?)",
                (jid, task_type, "queued", engine, model, prompt, json.dumps(params),
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
        if r["status"] == "queued":
            # Mai partito: cancellazione immediata, niente interrupt.
            cx.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?", (db.now(), jid))
            cx.commit()
        else:
            # running/preparing: il worker vede 'cancelling', fa /interrupt e flippa a cancelled.
            cx.execute("UPDATE jobs SET status='cancelling', updated_at=? WHERE id=?", (db.now(), jid))
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
            beats = 0
            while True:
                rows = cx.execute(
                    "SELECT id,status,updated_at FROM jobs WHERE updated_at>? ORDER BY updated_at LIMIT 50",
                    (last,)).fetchall()
                for r in rows:
                    last = max(last, r["updated_at"])
                    yield f"data: {json.dumps({'id': r['id'], 'status': r['status']})}\n\n"
                # Heartbeat ogni ~15s: tiene viva la SSE dietro proxy/timeout NAT.
                beats += 1
                if beats >= 7:
                    yield ": ping\n\n"
                    beats = 0
                await asyncio.sleep(2.0)
        except asyncio.CancelledError:
            pass
        finally:
            try:
                cx.close()
            except Exception:
                pass
    # no-cache/no-buffer: i proxy intermedi (es. tailscale serve) non devono
    # accumulare gli eventi, altrimenti l'app li vede in ritardo o mai.
    return StreamingResponse(
        gen(), media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "Connection": "keep-alive"},
    )


@app.post("/api/v1/assets/upload")
async def upload_asset(f: UploadFile = File(...), _: None = Depends(require_auth)):
    # Streaming con cap: niente buffering illimitato prima del check 25MB.
    limit = MAX_UPLOAD_MB * 1024 * 1024
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await f.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > limit:
            raise HTTPException(413, f"file oltre {MAX_UPLOAD_MB} MB")
        chunks.append(chunk)
    data = b"".join(chunks)
    if len(data) == 0:
        raise HTTPException(415, "file vuoto")
    head = data[:16]
    if head.startswith(b"\xff\xd8\xff"):
        ext = ".jpg"
    elif head.startswith(b"\x89PNG"):
        ext = ".png"
    elif head.startswith(b"RIFF") and len(data) >= 12 and data[8:12] == b"WEBP":
        ext = ".webp"
    else:
        raise HTTPException(415, "solo JPEG/PNG/WebP")
    UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
    aid = db.new_id("upl")
    p = (UPLOADS_DIR / (safe_name(aid) + ext))
    # Scrittura atomica via .part: niente file troncati visibili.
    tmp = p.with_suffix(p.suffix + ".part")
    tmp.write_bytes(data)
    tmp.replace(p)
    return {"asset_upload_id": aid, "path": str(p.name), "bytes": len(data)}


@app.get("/api/v1/assets", response_model=list[AssetOut])
async def list_assets(kind: str | None = None, favorite: bool = False,
                      _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        if favorite and kind:
            rows = cx.execute(
                "SELECT a.* FROM assets a JOIN favorites f ON f.asset_id=a.id "
                "WHERE a.kind=? ORDER BY a.created_at DESC", (kind,)).fetchall()
        elif favorite:
            rows = cx.execute(
                "SELECT a.* FROM assets a JOIN favorites f ON f.asset_id=a.id ORDER BY a.created_at DESC").fetchall()
        elif kind:
            rows = cx.execute("SELECT * FROM assets WHERE kind=? ORDER BY created_at DESC", (kind,)).fetchall()
        else:
            rows = cx.execute("SELECT * FROM assets ORDER BY created_at DESC").fetchall()
        out = []
        for r in rows:
            out.append(_asset_row_to_out(r))
        return out
    finally:
        cx.close()


def _asset_row_to_out(r) -> AssetOut:
    th = THUMBS_DIR / (r["id"] + ".jpg")
    try:
        duration = r["duration"]
    except (IndexError, KeyError):
        duration = None
    return AssetOut(id=r["id"], kind=r["kind"], path=f"/api/v1/assets/{r['id']}/download",
                    thumb=f"/api/v1/assets/{r['id']}/thumbnail" if th.exists() else None,
                    job_id=r["job_id"], width=r["width"], height=r["height"],
                    duration=duration, created_at=db.iso(r["created_at"]))


@app.get("/api/v1/assets/{aid}", response_model=AssetOut)
async def get_asset(aid: str, _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        r = cx.execute("SELECT * FROM assets WHERE id=?", (aid,)).fetchone()
        if not r:
            raise HTTPException(404, "asset not found")
        return _asset_row_to_out(r)
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
        ex = cx.execute("SELECT 1 FROM assets WHERE id=?", (aid,)).fetchone()
        if not ex:
            raise HTTPException(404, "asset not found")
        if on:
            cx.execute("INSERT OR IGNORE INTO favorites(asset_id) VALUES(?)", (aid,))
        else:
            cx.execute("DELETE FROM favorites WHERE asset_id=?", (aid,))
        cx.commit()
        return {"ok": True, "favorite": on}
    finally:
        cx.close()


@app.delete("/api/v1/assets/{aid}")
async def delete_asset(aid: str, _: None = Depends(require_auth)):
    """Elimina asset + file + thumbnail + riferimenti (preferiti, progetti).

    Conservativa altrove: i job restano (outputs punta a id rimossi),
    nessun output utente viene toccato senza questa chiamata esplicita.
    """
    cx = db.connect()
    try:
        r = cx.execute("SELECT * FROM assets WHERE id=?", (aid,)).fetchone()
        if not r:
            raise HTTPException(404, "asset not found")
        rel = r["rel_path"]
        cx.execute("DELETE FROM favorites WHERE asset_id=?", (aid,))
        cx.execute("DELETE FROM project_assets WHERE asset_id=?", (aid,))
        cx.execute("DELETE FROM assets WHERE id=?", (aid,))
        cx.commit()
    finally:
        cx.close()
    # File system dopo il commit DB: mai orfani in DB se il disco fallisce.
    try:
        p = (ASSETS_DIR / safe_name(rel))
        if p.is_file():
            p.unlink()
    except OSError:
        pass
    try:
        t = THUMBS_DIR / (safe_name(aid) + ".jpg")
        if t.is_file():
            t.unlink()
    except OSError:
        pass
    return {"ok": True, "deleted": aid}


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
        ts = db.now()
        cx.execute("INSERT INTO projects(id,name,created_at) VALUES(?,?,?)", (pid, body.name, ts))
        cx.commit()
        return ProjectOut(id=pid, name=body.name, created_at=db.iso(ts), asset_ids=[])
    finally:
        cx.close()


@app.post("/api/v1/projects/{pid}/assets/{aid}")
async def add_to_project(pid: str, aid: str, _: None = Depends(require_auth)):
    cx = db.connect()
    try:
        if not cx.execute("SELECT 1 FROM projects WHERE id=?", (pid,)).fetchone():
            raise HTTPException(404, "project not found")
        if not cx.execute("SELECT 1 FROM assets WHERE id=?", (aid,)).fetchone():
            raise HTTPException(404, "asset not found")
        cx.execute("INSERT OR IGNORE INTO project_assets(project_id,asset_id) VALUES(?,?)", (pid, aid))
        cx.commit()
        return {"ok": True}
    finally:
        cx.close()
