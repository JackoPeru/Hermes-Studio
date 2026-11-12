import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

import pytest
from fastapi.testclient import TestClient

import app.config as cfg

@pytest.fixture()
def client(tmp_path, monkeypatch):
    data = tmp_path / "data"
    monkeypatch.setenv("HERMES_STUDIO_DATA", str(data))
    # rebind config paths
    cfg.DATA_DIR = data
    cfg.ASSETS_DIR = data / "assets"
    cfg.THUMBS_DIR = data / "thumbs"
    cfg.UPLOADS_DIR = data / "uploads"
    cfg.DB_PATH = data / "studio.db"
    import importlib
    import app.db as dbmod
    import app.auth as authmod
    import app.storage as storagemod
    # reset init cache + token cache per test isolation
    dbmod._init_done_for.clear()
    authmod._cached_token = None
    importlib.reload(dbmod)
    importlib.reload(authmod)
    # storage lega ASSETS_DIR/THUMBS_DIR all'import: ricaricare o i file
    # finiscono nella dir del primo test (thumb fantasma).
    importlib.reload(storagemod)
    import app.main as mainmod
    importlib.reload(mainmod)
    tok = "test-token"
    (data / "bearer.token").parent.mkdir(parents=True, exist_ok=True)
    (data / "bearer.token").write_text(tok)
    authmod._cached_token = None
    c = TestClient(mainmod.app)
    c.headers.update({"Authorization": f"Bearer {tok}"})
    return c

def test_health_no_auth(tmp_path, monkeypatch):
    import app.main as m
    c = TestClient(m.app)
    r = c.get("/api/v1/health")
    assert r.status_code == 200

def test_unauthorized(client):
    from fastapi.testclient import TestClient
    import app.main as m
    c2 = TestClient(m.app)
    assert c2.get("/api/v1/capabilities").status_code in (401, 422)

def test_capabilities_gates_video(client):
    r = client.get("/api/v1/capabilities")
    assert r.status_code == 200
    j = r.json()
    assert any(t["enabled"] for t in j["image"])
    i2v = [t for t in j["video"] if t["task"] == "image-to-video"]
    assert i2v and i2v[0]["enabled"] is True
    t2v = [t for t in j["video"] if t["task"] == "text-to-video"]
    assert t2v and t2v[0]["enabled"] is False  # nessun preset T2V nel manager

def test_enqueue_image_and_idempotency(client):
    body = {"taskType": "image", "prompt": "a neon alley", "width": 768, "height": 768, "idempotency_key": "k1"}
    r1 = client.post("/api/v1/jobs", json=body)
    assert r1.status_code == 201, r1.text
    r2 = client.post("/api/v1/jobs", json=body)
    assert r2.json()["id"] == r1.json()["id"]
    import app.db as dbmod
    import json as _json
    cx = dbmod.connect()
    try:
        row = cx.execute("SELECT params FROM jobs WHERE id=?", (r1.json()["id"],)).fetchone()
    finally:
        cx.close()
    assert _json.loads(row["params"])["image_preset"] == "create_image"

def test_video_rejected_409(client):
    # Video senza immagine di partenza: 422 onesto, non 409 (gate rimosso).
    r = client.post("/api/v1/jobs", json={"taskType": "video", "prompt": "x"})
    assert r.status_code == 422

def test_video_enqueue_uses_manager_preset(client, monkeypatch):
    import app.db as dbmod
    aid = _seed_asset(client)
    r = client.post("/api/v1/jobs", json={"taskType": "video", "prompt": "onde lente",
                                          "asset_ids": [aid], "width": 1344, "height": 768})
    assert r.status_code == 201, r.text
    j = r.json()
    assert j["taskType"] == "video" and j["status"] == "queued"
    # L'enqueue mappa già preset e start frame (il submit al manager lo fa il worker).
    cx = dbmod.connect()
    try:
        row = cx.execute("SELECT params FROM jobs WHERE id=?", (j["id"],)).fetchone()
    finally:
        cx.close()
    p = json.loads(row["params"])
    assert p["video_preset"] == "journey_video_preview"
    assert p["asset_ids"] == [aid]

def test_image_edit_mode_maps_preset(client):
    import app.db as dbmod
    aid = _seed_asset(client)
    r = client.post("/api/v1/jobs", json={"taskType": "image", "prompt": "aggiungi un gatto",
                                          "asset_ids": [aid]})
    assert r.status_code == 201, r.text
    cx = dbmod.connect()
    try:
        row = cx.execute("SELECT params FROM jobs WHERE id=?", (r.json()["id"],)).fetchone()
    finally:
        cx.close()
    assert json.loads(row["params"])["image_preset"] == "edit_image"

def test_upload_limits_and_sniffing(client):
    big = b"\xff\xd8\xff" + b"0" * 10
    r = client.post("/api/v1/assets/upload", files={"f": ("a.jpg", big, "image/jpeg")})
    assert r.status_code == 200
    j = r.json()
    assert j["asset_id"]  # upload indicizzati in libreria
    assert client.get(f"/api/v1/assets/{j['asset_id']}").status_code == 200
    r = client.post("/api/v1/assets/upload", files={"f": ("a.txt", b"hello world", "text/plain")})
    assert r.status_code == 415

def test_norm_wh_area_cap():
    import app.main as m
    assert m._norm_wh(1344, 768) == (1344, 768)  # al cap video, passa netto
    assert m._norm_wh(768, 1344) == (768, 1344)
    w, h = m._norm_wh(1024, 1024)  # sotto il cap immagini: solo snap
    assert (w, h) == (1024, 1024)
    w, h = m._norm_wh(2560, 1440)  # 2K: al cap immagini esatto
    assert (w, h) == (2560, 1440)
    w, h = m._norm_wh(3000, 3000)  # oltre: clamp onesto
    assert w * h <= 3686400 and w % 32 == 0 and h % 32 == 0
    w, h = m._norm_wh(2048, 2048, m._VIDEO_AREA_CAP_PX)  # video: tetto stretto invariato
    assert w * h <= 1032192

def test_apply_style_templates():
    from app.workflows import apply_style
    assert "cinematic lighting" in apply_style("un vicolo", "cinematico")
    assert apply_style("x", "inesistente") == "x"
    assert apply_style("x", None) == "x"

def test_cancel_queued_goes_cancelled(client):
    r = client.post("/api/v1/jobs", json={"taskType": "image", "prompt": "cancel me"})
    jid = r.json()["id"]
    rc = client.post(f"/api/v1/jobs/{jid}/cancel")
    assert rc.status_code == 200
    assert rc.json()["status"] == "cancelled"

def test_favorite_missing_404_and_project_validation(client):
    assert client.post("/api/v1/assets/nope/favorite?on=true").status_code == 404
    r = client.post("/api/v1/projects", json={"name": "p1"})
    assert r.status_code == 201
    pid = r.json()["id"]
    assert client.post(f"/api/v1/projects/{pid}/assets/nope").status_code == 404
    assert client.post("/api/v1/projects/nope/assets/nope").status_code == 404
    # kind+favorite combo does not 500
    assert client.get("/api/v1/assets", params={"kind": "image", "favorite": True}).status_code == 200

def test_upload_rejects_oversize_fast(client, monkeypatch):
    import app.main as m
    monkeypatch.setattr(m, "MAX_UPLOAD_MB", 0)
    big = b"\xff\xd8\xff" + b"0" * 10
    r = client.post("/api/v1/assets/upload", files={"f": ("a.jpg", big, "image/jpeg")})
    assert r.status_code == 413

def test_status_shape(client):
    r = client.get("/api/v1/status")
    assert r.status_code == 200
    j = r.json()
    assert "queue_length" in j and "storage" in j

def _seed_asset(client):
    import app.db as dbmod
    import app.config as cfg
    from PIL import Image
    aid = dbmod.new_id("ast")
    rel = aid + "_seed.png"
    p = cfg.ASSETS_DIR / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (64, 32), "red").save(p)
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO assets(id,kind,rel_path,job_id,width,height,created_at) VALUES(?,?,?,?,?,?,?)",
            (aid, "image", rel, None, 64, 32, dbmod.now()),
        )
        cx.commit()
    finally:
        cx.close()
    return aid

def test_get_and_delete_asset(client):
    aid = _seed_asset(client)
    r = client.get(f"/api/v1/assets/{aid}")
    assert r.status_code == 200
    assert r.json()["width"] == 64
    assert client.get("/api/v1/assets/nope").status_code == 404
    assert client.post(f"/api/v1/assets/{aid}/favorite?on=true").status_code == 200
    d = client.delete(f"/api/v1/assets/{aid}")
    assert d.status_code == 200
    assert client.get(f"/api/v1/assets/{aid}").status_code == 404
    assert client.get(f"/api/v1/assets/{aid}/download").status_code == 404
    assert client.delete(f"/api/v1/assets/{aid}").status_code == 404

def test_gpu_endpoint_shape(client, monkeypatch):
    import subprocess as _sp
    def fake_run(*a, **k):
        class R:
            returncode = 0
            stdout = "0, Test GPU, 42, 7, 1000, 8000\n1, Test GPU 2, 40, 0, 500, 8000\n"
            stderr = ""
        return R()
    monkeypatch.setattr(_sp, "run", fake_run)
    monkeypatch.setattr("shutil.which", lambda x: "/usr/bin/nvidia-smi")
    r = client.get("/api/v1/gpu")
    assert r.status_code == 200, r.text
    g = r.json()
    assert len(g) == 2 and g[0]["temp_c"] == 42 and g[1]["mem_total_mb"] == 8000

@pytest.mark.asyncio
async def test_video_worker_done_path(client, tmp_path, monkeypatch):
    # Worker completo con manager finto: nessun render reale, nessun ffmpeg.
    # ffprobe/ffmpeg assenti -> probe {} e thumb None, senza eccezioni (fail-soft).
    import asyncio as _aio
    import app.db as dbmod
    import app.main as m
    mp4 = tmp_path / "clip.mp4"
    mp4.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"0" * 2048)
    aid = _seed_asset(client)
    aid2 = _seed_asset(client)
    jid = dbmod.new_id("job")
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (jid, "video", "queued", "comfyui", "minimax-h3-turbo-4step", "onde",
             json.dumps({"width": 768, "height": 1344, "steps": 4, "seed": 1,
                         "asset_ids": [aid, aid2], "video_preset": "journey_video_preview_chained"}),
             dbmod.now(), dbmod.now()))
        cx.commit()
    finally:
        cx.close()
    seen = {}
    class FakeMgr:
        async def submit_video(self, preset, params):
            seen.update(preset=preset, params=params)
            assert len(params["input_images"]) == 2
            assert all(p.endswith(".png") for p in params["input_images"])
            return {"job_id": "mid9", "status": "queued", "preset": preset}
        async def get_job(self, mid):
            assert mid == "mid9"
            return {"job_id": mid, "status": "done", "progress": 1.0,
                    "result_paths": [str(mp4)], "error": ""}
        async def cancel_job(self, mid):
            return {"job_id": mid, "status": "cancelled"}
    monkeypatch.setattr(m, "_manager", lambda: FakeMgr())
    async def no_sleep(*a, **k):
        return None
    monkeypatch.setattr(_aio, "sleep", no_sleep)
    await m._run_one_video(jid)
    assert seen["preset"] == "journey_video_preview_chained"
    cx = dbmod.connect()
    try:
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
        assert row["status"] == "completed", row["error"]
        outs = json.loads(row["outputs"])
        assert len(outs) == 1
        a = cx.execute("SELECT * FROM assets WHERE id=?", (outs[0],)).fetchone()
        assert a["kind"] == "video"
        rel = a["rel_path"]
    finally:
        cx.close()
    import app.config as cfg
    assert (cfg.ASSETS_DIR / rel).is_file()

@pytest.mark.asyncio
async def test_image_worker_done_path(client, tmp_path, monkeypatch):
    # Image via manager (preset create_image): nessun Comfy diretto, nessun render reale.
    import asyncio as _aio
    import app.db as dbmod
    import app.main as m
    from PIL import Image
    png = tmp_path / "out.png"
    Image.new("RGB", (320, 240), "blue").save(png)
    jid = dbmod.new_id("job")
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (jid, "image", "queued", "comfyui", "qwen-image-2.1-UC-Q4_K_M", "gatto",
             json.dumps({"width": 768, "height": 768, "steps": 12, "seed": 3,
                         "asset_ids": [], "image_preset": "create_image"}),
             dbmod.now(), dbmod.now()))
        cx.commit()
    finally:
        cx.close()
    seen = {}
    class FakeMgr:
        async def submit_image(self, preset, params):
            seen.update(preset=preset, params=params)
            assert preset == "create_image"
            return {"job_id": "mid3", "status": "queued", "preset": preset}
        async def get_job(self, mid):
            assert mid == "mid3"
            return {"job_id": mid, "status": "done", "progress": 1.0,
                    "result_paths": [str(png)], "error": ""}
        async def cancel_job(self, mid):
            return {"job_id": mid, "status": "cancelled"}
    monkeypatch.setattr(m, "_manager", lambda: FakeMgr())
    async def no_sleep(*a, **k):
        return None
    monkeypatch.setattr(_aio, "sleep", no_sleep)
    await m._run_one_image(jid)
    assert seen["params"]["width"] == 768
    cx = dbmod.connect()
    try:
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
        assert row["status"] == "completed", row["error"]
        outs = json.loads(row["outputs"])
        assert len(outs) == 1
        a = cx.execute("SELECT * FROM assets WHERE id=?", (outs[0],)).fetchone()
        assert a["kind"] == "image"
        assert a["width"] == 320 and a["height"] == 240
        rel = a["rel_path"]
    finally:
        cx.close()
    import app.config as cfg
    assert (cfg.ASSETS_DIR / rel).is_file()
    assert (cfg.THUMBS_DIR / (outs[0] + ".jpg")).is_file()

@pytest.mark.asyncio
async def test_image_edit_two_refs(client, tmp_path, monkeypatch):
    # Ritocco con 2 riferimenti: preset edit_image + entrambi staged.
    import asyncio as _aio
    import app.db as dbmod
    import app.main as m
    from PIL import Image
    png = tmp_path / "out.png"
    Image.new("RGB", (320, 240), "green").save(png)
    aid1 = _seed_asset(client)
    aid2 = _seed_asset(client)
    jid = dbmod.new_id("job")
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (jid, "image", "queued", "comfyui", "qwen-image-2.1-UC-Q4_K_M", "piu cupo",
             json.dumps({"width": 768, "height": 768, "steps": 12, "seed": 3,
                         "asset_ids": [aid1, aid2], "image_preset": "edit_image"}),
             dbmod.now(), dbmod.now()))
        cx.commit()
    finally:
        cx.close()
    seen = {}
    class FakeMgr:
        async def submit_image(self, preset, params):
            seen.update(preset=preset, params=params)
            assert preset == "edit_image"
            assert len(params["input_images"]) == 2
            return {"job_id": "mid7", "status": "queued", "preset": preset}
        async def get_job(self, mid):
            assert mid == "mid7"
            return {"job_id": mid, "status": "done", "progress": 1.0,
                    "result_paths": [str(png)], "error": ""}
        async def cancel_job(self, mid):
            return {"job_id": mid, "status": "cancelled"}
    monkeypatch.setattr(m, "_manager", lambda: FakeMgr())
    async def no_sleep(*a, **k):
        return None
    monkeypatch.setattr(_aio, "sleep", no_sleep)
    await m._run_one_image(jid)
    assert seen["preset"] == "edit_image"
    cx = dbmod.connect()
    try:
        row = cx.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
        assert row["status"] == "completed", row["error"]
    finally:
        cx.close()

def _old_job(client, status, days_ago, with_asset=False):
    import app.db as dbmod
    jid = dbmod.new_id("job")
    old = dbmod.now() - days_ago * 86400
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (jid, "image", status, "comfyui", "m", "p", "{}", old, old))
        cx.commit()
    finally:
        cx.close()
    return jid

def test_prune_jobs_and_uploads(client, tmp_path):
    import app.db as dbmod
    import app.config as cfg
    import os
    old_failed = _old_job(client, "failed", 40)
    old_done = _old_job(client, "completed", 40)
    new_failed = _old_job(client, "failed", 1)
    cfg.UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
    old_up = cfg.UPLOADS_DIR / "old.bin"
    new_up = cfg.UPLOADS_DIR / "new.bin"
    old_up.write_bytes(b"0" * 100)
    new_up.write_bytes(b"0" * 50)
    old_t = dbmod.now() - 10 * 86400
    os.utime(old_up, (old_t, old_t))
    r = client.post("/api/v1/maintenance/prune", json={})
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["deleted_jobs"] == 1
    assert j["deleted_uploads"] == 1 and j["freed_bytes"] == 100
    cx = dbmod.connect()
    try:
        ids = {x[0] for x in cx.execute("SELECT id FROM jobs").fetchall()}
    finally:
        cx.close()
    assert old_failed not in ids  # potato via default
    assert old_done in ids and new_failed in ids  # completed esclusi di default, recenti salvi
    assert not old_up.exists() and new_up.exists()
    r = client.post("/api/v1/maintenance/prune", json={"include_completed": True})
    assert r.json()["deleted_jobs"] == 1


@pytest.mark.asyncio
async def test_recovery_requeues_and_cancels(client):
    import app.db as dbmod
    import app.main as m
    cx = dbmod.connect()
    try:
        for jid, st in [("rec-prep", "preparing"), ("rec-run", "running"),
                        ("rec-run-mid", "running"), ("rec-canc", "cancelling")]:
            cx.execute(
                "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,comfy_prompt_id,created_at,updated_at)"
                " VALUES(?,?,?,?,?,?,?,?,?,?)",
                (jid, "image", st, "e", "m", "p", "{}",
                 "mid-x" if jid == "rec-run-mid" else None, dbmod.now(), dbmod.now()))
        cx.commit()
    finally:
        cx.close()
    out = await m._recover_once()
    assert out == {"requeued": 3, "cancelled": 1}
    cx = dbmod.connect()
    try:
        got = {r[0]: r[1] for r in cx.execute("SELECT id,status FROM jobs WHERE id LIKE 'rec-%'").fetchall()}
    finally:
        cx.close()
    assert got == {"rec-prep": "queued", "rec-run": "queued",
                   "rec-run-mid": "queued", "rec-canc": "cancelled"}

@pytest.mark.asyncio
async def test_cancel_running_propagates_to_manager(client, monkeypatch):
    import asyncio as _aio
    import app.db as dbmod
    import app.main as m
    jid = dbmod.new_id("job")
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,comfy_prompt_id,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?,?)",
            (jid, "video", "running", "comfyui", "x", "p", "{}", "mid-z",
             dbmod.now(), dbmod.now()))
        cx.commit()
    finally:
        cx.close()
    cancelled = []
    calls = {"n": 0}
    class FakeMgr:
        async def submit_video(self, preset, params):
            raise AssertionError("no submit atteso")
        async def get_job(self, mid):
            # Primo poll: l'utente annulla proprio ora.
            calls["n"] += 1
            if calls["n"] == 1:
                c2 = dbmod.connect()
                try:
                    c2.execute("UPDATE jobs SET status='cancelling' WHERE id=?", (jid,))
                    c2.commit()
                finally:
                    c2.close()
            return {"job_id": mid, "status": "running", "progress": 0.5, "error": ""}
        async def cancel_job(self, mid):
            cancelled.append(mid)
            return {"job_id": mid, "status": "cancelled"}
    monkeypatch.setattr(m, "_manager", lambda: FakeMgr())
    async def no_sleep(*a, **k):
        return None
    monkeypatch.setattr(_aio, "sleep", no_sleep)
    await m._run_one_video(jid)
    assert cancelled == ["mid-z"]
    cx = dbmod.connect()
    try:
        st = cx.execute("SELECT status FROM jobs WHERE id=?", (jid,)).fetchone()[0]
    finally:
        cx.close()
    assert st == "cancelled"

@pytest.mark.asyncio
async def test_submit_race_keeps_cancel(client, monkeypatch):
    import asyncio as _aio
    import app.db as dbmod
    import app.main as m
    jid = dbmod.new_id("job")
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (jid, "image", "queued", "comfyui", "m", "p",
             json.dumps({"width": 768, "height": 768, "steps": 12, "seed": 1}),
             dbmod.now(), dbmod.now()))
        cx.commit()
    finally:
        cx.close()
    cancelled = []
    class FakeMgr:
        async def submit_image(self, preset, params):
            # L'utente annulla DURANTE il submit: il flip non deve sovrascrivere.
            c2 = dbmod.connect()
            try:
                c2.execute("UPDATE jobs SET status='cancelling' WHERE id=?", (jid,))
                c2.commit()
            finally:
                c2.close()
            return {"job_id": "mid-r", "status": "queued", "preset": preset}
        async def get_job(self, mid):
            raise AssertionError("no poll atteso")
        async def cancel_job(self, mid):
            cancelled.append(mid)
            return {"job_id": mid, "status": "cancelled"}
    monkeypatch.setattr(m, "_manager", lambda: FakeMgr())
    async def no_sleep(*a, **k):
        return None
    monkeypatch.setattr(_aio, "sleep", no_sleep)
    await m._run_one_image(jid)
    assert cancelled == ["mid-r"]
    cx = dbmod.connect()
    try:
        st = cx.execute("SELECT status FROM jobs WHERE id=?", (jid,)).fetchone()[0]
    finally:
        cx.close()
    assert st == "cancelled"

def test_auth_all_protected_routes_401(client):
    from fastapi.testclient import TestClient
    import app.main as m
    c2 = TestClient(m.app)
    for method, path in [
        ("get", "/api/v1/capabilities"), ("get", "/api/v1/status"),
        ("get", "/api/v1/jobs"), ("get", "/api/v1/jobs/x"),
        ("get", "/api/v1/assets"), ("get", "/api/v1/assets/x"),
        ("get", "/api/v1/projects"), ("get", "/api/v1/gpu"),
        ("get", "/api/v1/events"),
        ("post", "/api/v1/jobs"), ("post", "/api/v1/projects"),
        ("post", "/api/v1/maintenance/prune"),
        ("delete", "/api/v1/assets/x"),
    ]:
        if method == "post":
            r = getattr(c2, method)(path, json={})
        else:
            r = getattr(c2, method)(path)
        assert r.status_code == 401, (method, path, r.status_code)

@pytest.mark.asyncio
async def test_stage_missing_returns_none(client):
    import app.main as m
    assert await m._stage_manager_input("job-x", "nope", "1") is None

def test_prune_keeps_assets_and_links(client):
    import app.db as dbmod
    aid = _seed_asset(client)
    _old_job(client, "failed", 40)
    cx = dbmod.connect()
    try:
        cx.execute("INSERT INTO favorites(asset_id) VALUES(?)", (aid,))
        cx.execute("INSERT INTO projects(id,name,created_at) VALUES(?,?,?)",
                   ("prj-keep", "P", dbmod.now()))
        cx.execute("INSERT INTO project_assets(project_id,asset_id) VALUES(?,?)",
                   ("prj-keep", aid))
        cx.commit()
    finally:
        cx.close()
    r = client.post("/api/v1/maintenance/prune", json={"include_completed": True})
    assert r.status_code == 200
    assert client.get(f"/api/v1/assets/{aid}").status_code == 200
    cx = dbmod.connect()
    try:
        assert cx.execute("SELECT 1 FROM favorites WHERE asset_id=?", (aid,)).fetchone()
        assert cx.execute("SELECT 1 FROM project_assets WHERE asset_id=?", (aid,)).fetchone()
    finally:
        cx.close()

def test_blank_prompt_422(client):
    r = client.post("/api/v1/jobs", json={"taskType": "image", "prompt": "   "})
    assert r.status_code == 422

def test_upload_empty_422(client):
    r = client.post("/api/v1/assets/upload", files={"f": ("e.jpg", b"", "image/jpeg")})
    assert r.status_code == 422

def test_video_480p_dims_honored(client):
    aid = _seed_asset(client)
    r = client.post("/api/v1/jobs", json={"taskType": "video", "prompt": "x",
                                          "asset_ids": [aid], "width": 854, "height": 480})
    assert r.status_code == 201, r.text
    j = r.json()
    assert (j["width"], j["height"]) == (832, 480)

