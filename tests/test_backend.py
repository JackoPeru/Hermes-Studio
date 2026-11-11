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

def test_upload_limits_and_sniffing(client):
    big = b"\xff\xd8\xff" + b"0" * 10
    r = client.post("/api/v1/assets/upload", files={"f": ("a.jpg", big, "image/jpeg")})
    assert r.status_code == 200
    r = client.post("/api/v1/assets/upload", files={"f": ("a.txt", b"hello world", "text/plain")})
    assert r.status_code == 415

def test_workflow_builder_shapes():
    from app.workflows import build_qwen_t2i
    p = build_qwen_t2i(prompt="cat", negative="blur", width=770, height=900, seed=7, steps=20, job_id="job_abc")
    assert p["5"]["inputs"]["width"] == 770  # builder keeps caller value; API layer snaps to 32
    assert p["8"]["inputs"]["filename_prefix"] == "journey/job_abc"
    assert p["6"]["inputs"]["seed"] == 7

def test_workflow_prompt_with_placeholders_not_corrupted():
    from app.workflows import build_qwen_t2i
    tricky = "a poster saying {{WIDTH}} x {{HEIGHT}} and {{PROMPT}}"
    p = build_qwen_t2i(prompt=tricky, negative="n", width=1024, height=1024, seed=1, steps=5, job_id="j1")
    assert p["4"]["inputs"]["prompt"] == tricky
    assert p["5"]["inputs"]["width"] == 1024

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
    jid = dbmod.new_id("job")
    cx = dbmod.connect()
    try:
        cx.execute(
            "INSERT INTO jobs(id,task_type,status,engine,model,prompt,params,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (jid, "video", "queued", "comfyui", "minimax-h3-turbo-4step", "onde",
             json.dumps({"width": 1344, "height": 768, "steps": 4, "seed": 1,
                         "asset_ids": [aid], "video_preset": "journey_video_preview"}),
             dbmod.now(), dbmod.now()))
        cx.commit()
    finally:
        cx.close()
    class FakeMgr:
        async def submit_video(self, preset, params):
            assert params["input_images"] and params["input_images"][0].endswith(".png")
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
