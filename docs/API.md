# API v1 (stabile, engine-independent)

Base: `http(s)://<backend-privato>/api/v1`. Auth: `Authorization: Bearer <pairing-token>` su tutto tranne `GET /health`.

- `GET /health` → `{status, backend_version, api_version}` (no-auth, NON usare per test connessione)
- `GET /capabilities` → task image/video con `enabled`, `disabled_reason`, `supports_reference`
- `GET /status` → `{backend, comfy:{queue|error}, manager, manager_state, manager_desired, queue_length, storage}`
- `GET /gpu` → `[{index, name, temp_c, util_pct, mem_used_mb, mem_total_mb}]` live da `nvidia-smi` (503 se assente). Usato dal quadro GPU in Impostazioni.
- `POST /jobs` `{taskType:image|video, prompt, engine?, model?, negative_prompt?, width?, height?, steps?, seed?, duration?, style_preset?, asset_ids?, idempotency_key?}` → `201 Job`. Image con `asset_ids[0]` = ritocco (preset edit); video = image-to-video (richiede `asset_ids[0]`, `duration` 2..10s opzionale).
- `GET /jobs?limit=` · `GET /jobs/{id}` · `POST /jobs/{id}/cancel` (queued→cancelled subito, running→cancelling+cancel manager). `JobOut` include `duration` e `asset_ids` accodati.
- `GET /events` SSE `{id,status}` + heartbeat `: ping` ogni ~15s, header anti-buffering (`Cache-Control: no-cache`, `X-Accel-Buffering: no`) per i proxy. L'app ha watchdog 45s + backstop poll: nessun freeze silenzioso.
- `POST /assets/upload` (multipart streaming su disco, JPEG/PNG/WebP ≤25 MB, indicizzato subito in libreria) → `{asset_upload_id, asset_id, path, bytes}`. Errori: `413` oltre limite, `415` tipo non supportato, `422` file vuoto.
- `GET /assets[?kind&favorite]` (combo supportata) · `GET /assets/{id}` · `GET /assets/{id}/download` · `GET /assets/{id}/thumbnail` (auth richieste) · `POST /assets/{id}/favorite?on=` (404 se asset assente) · `DELETE /assets/{id}` (file+thumb+db+preferiti/progetti, 404 se assente)
- `GET/POST /projects` · `POST /projects/{pid}/assets/{aid}` (404 se project/asset assenti)
- `POST /maintenance/prune` `{older_than_days=30, include_completed=false, prune_uploads_older_than_days=7, limit=500}` → `{deleted_jobs, deleted_uploads, freed_bytes}`. Solo job terminali vecchi + upload orfani vecchi; asset/progetti mai toccati.

Job: `queued→preparing→running→completed|failed|cancelling→cancelled`. Restart backend: `preparing/running→queued`, `cancelling→cancelled`; il reconcile riusa l'id manager esistente (`comfy_prompt_id`), mai doppi submit.

Errori: `401` missing/invalid bearer (o pairing non inizializzato), `404` job/asset/project/file, `413` upload oltre limite, `415` sniff fallito, `422` validazione Pydantic / prompt vuoto / start frame non immagine.
