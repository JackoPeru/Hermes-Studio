# API v1 (stabile, engine-independent)

Base: `http(s)://<backend-privato>/api/v1`. Auth: `Authorization: Bearer <pairing-token>` su tutto tranne `GET /health`.

- `GET /health` → `{status, backend_version, api_version}` (no-auth, NON usare per test connessione)
- `GET /capabilities` → task image/video con `enabled`, `disabled_reason`, `supports_reference`
- `GET /status` → `{backend, comfy:{queue|error}, manager, manager_state, manager_desired, queue_length, storage}`
- `GET /gpu` → `[{index, name, temp_c, util_pct, mem_used_mb, mem_total_mb}]` live da `nvidia-smi` (503 se assente). Usato dal quadro GPU in Impostazioni.
- `POST /jobs` `{taskType:image|video, prompt, engine?, model?, negative_prompt?, width?, height?, steps?, seed?, duration?, style_preset?, asset_ids?, idempotency_key?, video_mode?, input_video_asset_id?}` → `201 Job`. Image con `asset_ids` = ritocco (preset edit, fino a 10 riferimenti); video: `video_mode` ∈ `i2v|first_last|ref2va|t2v|v2v` (default i2v), con requisiti per modalità (i2v 1 img, first_last 2 img, ref2va 1-4 img, t2v 0, v2v `input_video_asset_id`).
- `GET /jobs?limit=` · `GET /jobs/{id}` · `POST /jobs/{id}/cancel` (queued→cancelled subito, running→cancelling+cancel manager). `JobOut` include `duration`, `asset_ids`, `progress` e `phase` (submitted/executing/saving). `progress` resta null finché il manager non riporta i passi: la UI usa una barra indeterminata invece di una % finta.
- `GET /library?path=` · `GET /library/file?path=` · `POST /library/import {path}` — libreria immagini condivisa con Hermes Hub (`~/.hermes/hub_uploads`, config `HERMES_STUDIO_LIBRARY_ROOT`): elenco cartelle+immagini, byte, import come asset Studio (dedupe per sha256). Jail rigido: niente path assoluti, `..` o symlink.
- `GET /events` SSE `{id,status}` solo per eventi nuovi + heartbeat `: ping` ogni ~15s, header anti-buffering (`Cache-Control: no-cache`, `X-Accel-Buffering: no`) per i proxy. L'app riconnette da sola e ha un poll di sicurezza ogni 30s.
- `POST /assets/upload` (multipart streaming su disco, JPEG/PNG/WebP ≤25 MB, header+PIL verify, indicizzato subito in libreria) → `{asset_upload_id, asset_id, path, bytes}`. Errori: `413` oltre limite, `415` tipo non supportato, `422` file vuoto o immagine non decodificabile.
- `GET /assets[?kind&favorite]` (combo supportata) · `GET /assets/{id}` · `GET /assets/{id}/download` · `GET /assets/{id}/thumbnail` (auth richieste) · `POST /assets/{id}/favorite?on=` (404 se asset assente) · `DELETE /assets/{id}` (file+thumb+db+preferiti/progetti, 404 se assente)
- `GET/POST /projects` · `POST /projects/{pid}/assets/{aid}` (404 se project/asset assenti)
- `POST /maintenance/prune` `{older_than_days=30, include_completed=false, prune_uploads_older_than_days=7, limit=500}` → `{deleted_jobs, deleted_uploads, freed_bytes}`. Solo job terminali vecchi + file orfani vecchi in assets/staging (non referenziati dal DB); asset noti, progetti e job attivi mai toccati.

Job: `queued→preparing→running→completed|failed|cancelling→cancelled`. Restart backend: `running→queued` (reconcile via `comfy_prompt_id`, mai doppi submit), `preparing→failed` (submit interrotto: senza mid non riconciliabile), `cancelling→cancelled` con cancel al manager.

Errori: `401` missing/invalid bearer (o pairing non inizializzato), `404` job/asset/project/file, `413` upload oltre limite, `415` sniff fallito, `422` validazione Pydantic / prompt vuoto / asset del tipo sbagliato / immagine non decodificabile / requisiti `video_mode` non rispettati.
