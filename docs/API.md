# API v1 (stabile, engine-independent)

Base: `http(s)://<backend-privato>/api/v1`. Auth: `Authorization: Bearer <pairing-token>` su tutto tranne `GET /health`.

- `GET /health` → `{status, backend_version, api_version}` (no-auth, NON usare per test connessione)
- `GET /capabilities` → task image/video con `enabled`, `disabled_reason`, `supports_reference`
- `GET /status` → `{backend, comfy:{queue|error}, manager, queue_length, storage}`
- `POST /jobs` `{taskType:image|video, prompt, engine?, model?, negative_prompt?, width?, height?, steps?, seed?, style_preset?, asset_ids?, idempotency_key?}` → `201 Job`. Video → `409 LICENSE_GATE`.
- `GET /jobs?limit=` · `GET /jobs/{id}` · `POST /jobs/{id}/cancel` (queued→cancelled subito, running→cancelling+interrupt)
- `GET /events` SSE `{id,status}` + heartbeat `: ping` ogni ~30s
- `POST /assets/upload` (multipart streaming, JPEG/PNG/WebP ≤25 MB, scrittura atomica) → `{asset_upload_id, path, bytes}`. Errori: `413` oltre limite, `415` tipo non supportato.
- `GET /assets[?kind&favorite]` (combo supportata) · `GET /assets/{id}/download` · `GET /assets/{id}/thumbnail` (auth richieste) · `POST /assets/{id}/favorite?on=` (404 se asset assente)
- `GET/POST /projects` · `POST /projects/{pid}/assets/{aid}` (404 se project/asset assenti)

Job: `queued→preparing→running→completed|failed|cancelling→cancelled`. Restart backend: `preparing→queued`; `running` senza `comfy_prompt_id→queued`, con `prompt_id` resta `running` e viene riconciliato via `/history` (niente doppi submit).

Errori: `401` missing/invalid bearer, `404` job/asset/project/file, `409` video gate, `413` upload oltre limite, `415` sniff fallito, `422` validazione Pydantic.
