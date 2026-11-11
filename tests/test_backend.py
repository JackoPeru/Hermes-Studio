import io, json, sys
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
    import app.db as dbmod, app.auth as authmod
    importlib.reload(dbmod); importlib.reload(authmod)
    import app.main as mainmod
    importlib.reload(mainmod)
    tok = "test-token"
    (data / "bearer.token").parent.mkdir(parents=True, exist_ok=True)
    (data / "bearer.token").write_text(tok)
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
    assert all(not t["enabled"] for t in j["video"])
    assert "LICENSE_GATE" in json.dumps(j)

def test_enqueue_image_and_idempotency(client):
    body = {"taskType": "image", "prompt": "a neon alley", "width": 768, "height": 768, "idempotency_key": "k1"}
    r1 = client.post("/api/v1/jobs", json=body)
    assert r1.status_code == 201, r1.text
    r2 = client.post("/api/v1/jobs", json=body)
    assert r2.json()["id"] == r1.json()["id"]

def test_video_rejected_409(client):
    r = client.post("/api/v1/jobs", json={"taskType": "video", "prompt": "x"})
    assert r.status_code == 409

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
