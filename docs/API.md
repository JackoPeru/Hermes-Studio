# API v1 (stabile, engine-independent)

Base: `http(s)://<backend-privato>/api/v1`. Auth: `Authorization: Bearer <pairing-token>` su tutto tranne `GET /health`.

- `GET /health` → `{status, backend_version, api_version}`
- `GET /capabilities` → task image/video con `enabled`, `disabled_reason`, `supports_reference`
- `GET /status` → coda, comfy/manager raw, storage
- `POST /jobs` `{taskType:image|video, prompt, width?, height?, steps?, seed?, style_preset?, asset_ids?, idempotency_key?}` → `201 Job`. Video → `409 LICENSE_GATE`.
- `GET /jobs?limit=` · `GET /jobs/{id}` · `POST /jobs/{id}/cancel`
- `GET /events` SSE `{id,status}`
- `POST /assets/upload` (multipart, JPEG/PNG/WebP ≤25 MB) · `GET /assets[?kind&favorite]` · `GET /assets/{id}/download` · `GET /assets/{id}/thumbnail` · `POST /assets/{id}/favorite?on=`
- `GET/POST /projects` · `POST /projects/{pid}/assets/{aid}`

Job: `queued→preparing→running→completed|failed|cancelling→cancelled`. Restart backend: `preparing/running→queued` (niente doppi submit se `comfy_prompt_id` già assegnato: reconcile via `/history`).
