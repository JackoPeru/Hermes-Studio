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
    ProjectIn, ProjectOut, PruneIn, PruneOut,
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
        duration=params.get("duration"), asset_ids=params.get("asset_ids") or [],
        comfy_prompt_id=r["comfy_prompt_id"], progress=r["progress"],
        error=r["error"], outputs=json.loads(r["outputs"] or "[]"),
        created_at=db.iso(r["created_at"]), updated_at=db.iso(r["updated_at"]),
    )


async def _recover_once() -> dict:
    """Rimette in carreggiata i job interrotti (restart/crash).

    preparing/running -> queued (reconcile via existing_mid, mai doppi submit);
    cancelling -> cancelled. Ritorna i conteggi per log/test.
    """
    cx = db.connect()
    try:
        n1 = cx.execute(
            "UPDATE jobs SET status='queued', updated_at=? WHERE status IN ('preparing','running')",
            (db.now(),),
        ).rowcount
        n2 = cx.execute(
            "UPDATE jobs SET status='cancelled', updated_at=? WHERE status='cancelling'",
            (db.now(),),
        ).rowcount
        cx.commit()
        return {"requeued": n1, "cancelled": n2}
    finally:
        cx.close()


async def _dispatch_loop() -> None:
    """Background worker: queued -> preparing -> running -> completed/failed.

    Survives app close (jobs in SQLite). Al restart, TUTTI i job attivi
    (preparing/running/cancelling) tornano in coda o terminali: il worker
    non sottomette mai due volte (se esiste già un manager id lo riusa e
    riconcilia via GET /jobs/{mid}), quindi il requeue è sempre sicuro.
    """
    from .config import DB_PATH  # noqa
    await asyncio.sleep(1.0)
    counts = await _recover_once()
    if counts["requeued"] or counts["cancelled"]:
        _log.info("recovery: %d requeued, %d cancelled",
                  counts["requeued"], counts["cancelled"])
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


async def _stage_manager_input(job_id: str, asset_id: str, tag: str = "") -> str | None:
    """Copia un asset libreria nella staging fidata dal manager.

    Ritorna il path assoluto o None (asset mancante/file illeggibile/staging
    impossibile). Il chiamante cancella il file dopo il submit accettato.
    Il manager mappa INPUT_IMAGE_{n} in ordine di lista (max 4, i template
    reali ne usano 1-2: Qwen edit 2, H3 16:9 uno, H3 9:16 due).
    """
    from pathlib import Path as _P
    import shutil as _sh
    cx = db.connect()
    try:
        rr = cx.execute("SELECT rel_path FROM assets WHERE id=?", (asset_id,)).fetchone()
    finally:
        cx.close()
    if not rr:
        return None
    cand = ASSETS_DIR / safe_name(rr["rel_path"])
    if not cand.is_file():
        return None
    stage_dir = _P.home() / ".hermes" / "hermes-studio-in"
    try:
        stage_dir.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    stage = stage_dir / f"hs_{job_id}_in{tag}{cand.suffix.lower() or '.png'}"
    try:
        await asyncio.to_thread(_sh.copy2, str(cand.resolve()), stage)
    except OSError:
        return None
    return str(stage)


def _unstage(path: str | None) -> None:
    if not path:
        return
    try:
        from pathlib import Path as _P
        p = _P(path)
        if p.is_file():
            p.unlink()
    except OSError:
        pass


async def _run_one_image(job_id: str) -> None:
    """Text-to-image (preset create_image) o image-to-image (preset edit_image,
    con start frame) via GPU-manager. Niente Comfy diretto: in AUTO il manager
    fa unload LLM -> avvio Comfy -> run -> restore LLM."""
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
            # Atomico: se nel frattempo è arrivato un cancel, rowcount==0 e si esce.
            cur = cx.execute(
                "UPDATE jobs SET status='preparing', updated_at=? WHERE id=? AND status='queued'",
                (db.now(), job_id),
            )
            cx.commit()
            if cur.rowcount == 0:
                return
    finally:
        cx.close()

    cx = db.connect()
    try:
        mgr = _manager()
        mid = existing_mid
        if not mid:
            preset = params.get("image_preset") or "create_image"
            submit_params = {
                "prompt": prompt_text,
                "negative_prompt": params.get("negative_prompt") or DEFAULT_NEGATIVE,
                "width": int(params.get("width") or 1024),
                "height": int(params.get("height") or 1024),
                "seed": int(params.get("seed") if params.get("seed") is not None else random.randint(0, 2**31 - 1)),
                "steps": int(params.get("steps") or 20),
            }
            staged: list[str] = []
            if preset == "edit_image":
                aids = (params.get("asset_ids") or [])[:4]
                if not aids:
                    cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                               ("edit richiede asset_ids con almeno 1 immagine di partenza", db.now(), job_id))
                    cx.commit()
                    return
                for i, aid_in in enumerate(aids):
                    p = await _stage_manager_input(job_id, aid_in, str(i + 1))
                    if p:
                        staged.append(p)
                if not staged:
                    cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                               ("immagini di partenza non trovate in libreria", db.now(), job_id))
                    cx.commit()
                    return
                submit_params = {**submit_params, "input_images": staged}
            try:
                res = await mgr.submit_image(preset, submit_params)
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
            finally:
                for _s in staged:
                    _unstage(_s)
            # Flip condizionale: se nel frattempo è arrivato un cancel, non
            # sovrascrivere 'cancelling' — cancella il job manager e chiudi.
            cur = cx.execute(
                "UPDATE jobs SET status='running', comfy_prompt_id=?, progress=0.05, updated_at=? "
                "WHERE id=? AND status='preparing'",
                (mid, db.now(), job_id),
            )
            cx.commit()
            if cur.rowcount == 0:
                try:
                    await mgr.cancel_job(mid)
                except Exception:
                    pass
                cx.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?",
                           (db.now(), job_id))
                cx.commit()
                return
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
            # Solo immagini vere: niente file arbitrari in libreria.
            if pp.suffix.lower() not in (".png", ".jpg", ".jpeg", ".webp"):
                continue
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
            # Atomico: se nel frattempo è arrivato un cancel, rowcount==0 e si esce.
            cur = cx.execute(
                "UPDATE jobs SET status='preparing', updated_at=? WHERE id=? AND status='queued'",
                (db.now(), job_id),
            )
            cx.commit()
            if cur.rowcount == 0:
                return
    finally:
        cx.close()

    cx = db.connect()
    try:
        mgr = _manager()
        mid = existing_mid
        if not mid:
            aids = (params.get("asset_ids") or [])[:4]
            staged: list[str] = []
            for i, aid_in in enumerate(aids):
                p = await _stage_manager_input(job_id, aid_in, str(i + 1))
                if p:
                    staged.append(p)
            if not staged:
                cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                           ("immagine di partenza non trovata in libreria", db.now(), job_id))
                cx.commit()
                return
            preset = params.get("video_preset") or "journey_video_preview"
            mparams = {
                "prompt": prompt_text,
                "input_images": staged,
                "width": int(params.get("width") or 1344),
                "height": int(params.get("height") or 768),
                "seed": int(params.get("seed") if params.get("seed") is not None else random.randint(0, 2**31 - 1)),
            }
            # Durata solo se richiesta (default manager: ~5s @24fps); il manager
            # calcola i frame dalla durata, clamp 22..3600.
            try:
                _dur = float(params.get("duration") or 0)
            except (TypeError, ValueError):
                _dur = 0
            if _dur > 0:
                mparams["duration"] = _dur
            try:
                res = await mgr.submit_video(preset, mparams)
                mid = str(res.get("job_id") or res.get("id") or "")
                if not mid:
                    raise ValueError(f"manager senza job_id: {res}")
            except Exception as e:
                cx.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                           (f"manager submit failed: {e}", db.now(), job_id))
                cx.commit()
                return
            finally:
                for _s in staged:
                    _unstage(_s)
            # Flip condizionale: se nel frattempo è arrivato un cancel, non
            # sovrascrivere 'cancelling' — cancella il job manager e chiudi.
            cur = cx.execute(
                "UPDATE jobs SET status='running', comfy_prompt_id=?, progress=0.05, updated_at=? "
                "WHERE id=? AND status='preparing'",
                (mid, db.now(), job_id),
            )
            cx.commit()
            if cur.rowcount == 0:
                try:
                    await mgr.cancel_job(mid)
                except Exception:
                    pass
                cx.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?",
                           (db.now(), job_id))
                cx.commit()
                return
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
            # Allowlist estensioni + niente symlink: anche se il manager è
            # locale e fidato, non si copia mai roba arbitraria in libreria.
            if pp.suffix.lower() not in (".mp4", ".webm", ".mov", ".mkv", ".m4v",
                                         ".png", ".jpg", ".jpeg", ".webp"):
                continue
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
    # Se nel frattempo è arrivato un cancel, non convertire per niente.
    cc = db.connect()
    try:
        _cur = cc.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
    finally:
        cc.close()
    if _cur and _cur["status"] in ("cancelling", "cancelled"):
        cx.execute("UPDATE jobs SET status='cancelled', updated_at=? WHERE id=?", (db.now(), job_id))
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
        dest = (ASSETS_DIR / safe_name(rel)).resolve()
        assert str(dest).startswith(str(ASSETS_DIR.resolve())), "path traversal blocked"
        dest.parent.mkdir(parents=True, exist_ok=True)
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
        try:
            await _worker_task
        except asyncio.CancelledError:
            pass
        except Exception:
            _log.exception("worker: errore in shutdown")

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
                           enabled=True, max_width=2560, max_height=2560,
                           notes="Preset manager edit_image (Qwen edit, input staged). Richiede immagine di partenza.",
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
    import csv as _csv
    for parts in _csv.reader((r.stdout or "").splitlines()):
        parts = [p.strip() for p in parts]
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


# Cap area: il manager rifiuta oltre con 400 onesto (validate_workflow).
# Video: tetto stretto anti-OOM (i preset turbo stanno a 1344x768 max).
# Immagini: tetto 2K per still con SaveImage (proprietario 2026-11-12),
# video e workflow senza output restano al tetto stretto.
_VIDEO_AREA_CAP_PX = 1032192
_IMAGE_AREA_CAP_PX = 2560 * 1440


def _norm_wh(w: int | None, h: int | None, max_area: int = _IMAGE_AREA_CAP_PX) -> tuple[int, int]:
    w = w or 1024
    h = h or 1024
    # snap to 32px (regola latente H3, default conservativo anche per Qwen:
    # le dimensioni reali finiscono nel job e nell'asset, niente sorprese)
    w, h = max(64, w // 32 * 32), max(64, h // 32 * 32)
    if w * h > max_area:
        import math as _math
        s = _math.sqrt(max_area / (w * h))
        w, h = max(64, int(w * s) // 32 * 32), max(64, int(h * s) // 32 * 32)
    return w, h


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
    prompt = body.prompt.strip()
    if not prompt:
        raise HTTPException(422, "prompt vuoto")
    is_video = body.taskType.value == "video"
    engine = body.engine or "comfyui"
    cx = db.connect()
    try:
        # Fail-fast: gli asset di partenza devono esistere (e per i video
        # devono essere immagini), altrimenti 404/422 subito invece di un
        # ciclo seriale sprecato nel worker.
        if body.asset_ids:
            for aid in body.asset_ids[:8]:
                r = cx.execute("SELECT kind FROM assets WHERE id=?", (aid,)).fetchone()
                if not r:
                    raise HTTPException(404, f"asset di partenza non trovato: {aid}")
                if is_video and r["kind"] != "image":
                    raise HTTPException(422, f"lo start frame deve essere un'immagine: {aid}")
    finally:
        cx.close()
    if is_video:
        if not body.asset_ids:
            raise HTTPException(422, "video richiede asset_ids con almeno 1 immagine di partenza")
        model = body.model or "minimax-h3-turbo-4step"
        prompt = body.prompt.strip()
        preset, pw, ph = _video_preset_for(body.width or 1344, body.height or 768)
        w, h = _norm_wh(pw, ph, _VIDEO_AREA_CAP_PX)
        steps = body.steps or 4
        seed = body.seed if body.seed is not None else random.randint(0, 2**31 - 1)
        params = {"width": w, "height": h, "steps": steps, "seed": seed,
                  "negative_prompt": body.negative_prompt, "style_preset": None,
                  "asset_ids": body.asset_ids, "video_preset": preset,
                  "duration": body.duration}
        task_type = "video"
    else:
        model = body.model or "qwen-image-2.1-UC-Q4_K_M"
        prompt = apply_style(body.prompt.strip(), body.style_preset)
        w, h = _norm_wh(body.width, body.height)
        steps = body.steps or 20
        seed = body.seed if body.seed is not None else random.randint(0, 2**31 - 1)
        edit_mode = bool(body.asset_ids)
        params = {"width": w, "height": h, "steps": steps, "seed": seed,
                  "negative_prompt": body.negative_prompt, "style_preset": body.style_preset,
                  "asset_ids": body.asset_ids,
                  "image_preset": "edit_image" if edit_mode else "create_image"}
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
        last = 0.0
        beats = 0
        try:
            while True:
                # Connessione breve per ciclo: niente handle aperti per ore.
                cx = db.connect()
                try:
                    rows = cx.execute(
                        "SELECT id,status,updated_at FROM jobs WHERE updated_at>? ORDER BY updated_at LIMIT 50",
                        (last,)).fetchall()
                finally:
                    cx.close()
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
    # no-cache/no-buffer: i proxy intermedi (es. tailscale serve) non devono
    # accumulare gli eventi, altrimenti l'app li vede in ritardo o mai.
    return StreamingResponse(
        gen(), media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "Connection": "keep-alive"},
    )


@app.post("/api/v1/assets/upload")
async def upload_asset(f: UploadFile = File(...), _: None = Depends(require_auth)):
    # Streaming su disco con cap: mai tutto in RAM (DoS concorrente).
    limit = MAX_UPLOAD_MB * 1024 * 1024
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    staging = ASSETS_DIR / (safe_name(db.new_id("upl")) + ".part")
    total = 0
    head = b""
    try:
        with open(staging, "wb") as fh:
            while True:
                chunk = await f.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > limit:
                    raise HTTPException(413, f"file oltre {MAX_UPLOAD_MB} MB")
                if len(head) < 16:
                    head = (head + chunk)[:16]
                fh.write(chunk)
    except HTTPException:
        try:
            staging.unlink()
        except OSError:
            pass
        raise
    if total == 0:
        try:
            staging.unlink()
        except OSError:
            pass
        raise HTTPException(422, "file vuoto")
    if head.startswith(b"\xff\xd8\xff"):
        ext = ".jpg"
    elif head.startswith(b"\x89PNG"):
        ext = ".png"
    elif head.startswith(b"RIFF") and total >= 12 and head[8:12] == b"WEBP":
        ext = ".webp"
    else:
        try:
            staging.unlink()
        except OSError:
            pass
        raise HTTPException(415, "solo JPEG/PNG/WebP")
    # Upload indicizzati: diventano subito elementi di libreria (job_id NULL),
    # usabili come start frame / riferimento.
    aid = db.new_id("ast")
    rel = safe_name(aid) + ext
    dest = (ASSETS_DIR / rel).resolve()
    assert str(dest).startswith(str(ASSETS_DIR.resolve())), "path traversal blocked"
    staging.replace(dest)
    w = h = None
    try:
        from PIL import Image as _I
        with _I.open(dest) as _im:
            w, h = _im.size
    except Exception:
        pass
    cx = db.connect()
    try:
        cx.execute(
            "INSERT INTO assets(id,kind,rel_path,job_id,width,height,created_at) VALUES(?,?,?,?,?,?,?)",
            (aid, "image", rel, None, w, h, db.now()),
        )
        cx.commit()
    finally:
        cx.close()
    await asyncio.to_thread(make_thumb, dest, aid)
    return {"asset_upload_id": aid, "asset_id": aid, "path": rel, "bytes": total}


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
    if not p.is_file() or p.is_symlink():
        raise HTTPException(404, "file missing")
    return FileResponse(str(p))


@app.get("/api/v1/assets/{aid}/thumbnail")
async def thumb_asset(aid: str, _: None = Depends(require_auth)):
    t = THUMBS_DIR / (safe_name(aid) + ".jpg")
    if not t.is_file() or t.is_symlink():
        src = _asset_path(aid)
        if not src.is_file() or src.is_symlink():
            raise HTTPException(404, "file missing")
        t2 = await asyncio.to_thread(make_thumb, src, aid)
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


@app.post("/api/v1/maintenance/prune", response_model=PruneOut)
async def prune(body: PruneIn, _: None = Depends(require_auth)):
    """Pulizia conservativa: job terminali vecchi + upload orfani vecchi.

    Mai toccati: job attivi (queued/preparing/running/cancelling), asset,
    thumbnail, progetti. Gli asset restano anche se il job che li ha creati
    viene potato (provenienza via job_id conservata nella riga asset).
    """
    import time as _time
    statuses = ["failed", "cancelled"]
    if body.include_completed:
        statuses.append("completed")
    # Grazia minima 1h comunque: un job appena fallito resta leggibile in app.
    cutoff = db.now() - max(body.older_than_days * 86400, 3600)
    deleted_jobs = 0
    cx = db.connect()
    try:
        ph = ",".join("?" for _ in statuses)
        rows = cx.execute(
            f"SELECT id FROM jobs WHERE status IN ({ph}) AND updated_at < ? LIMIT ?",
            (*statuses, cutoff, body.limit)).fetchall()
        for (jid,) in rows:
            cx.execute("DELETE FROM jobs WHERE id=?", (jid,))
            deleted_jobs += 1
        cx.commit()
    finally:
        cx.close()
    deleted_uploads = 0
    freed = 0
    ucut = _time.time() - body.prune_uploads_older_than_days * 86400
    try:
        UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
        for p in UPLOADS_DIR.iterdir():
            try:
                if not p.is_file() or p.stat().st_mtime >= ucut:
                    continue
                freed += p.stat().st_size
                p.unlink()
                deleted_uploads += 1
            except OSError:
                continue
    except OSError:
        pass
    return PruneOut(deleted_jobs=deleted_jobs, deleted_uploads=deleted_uploads, freed_bytes=freed)


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
