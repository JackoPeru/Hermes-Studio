# Runbook operativo (V3, normativo dove README rimanda qui)

## Avvio
`set -a; . /etc/hermes/hermes-studio.env; set +a` (include `HERMES_STUDIO_MANAGER_KEY`) poi
`backend/.venv/bin/uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8650`.
Token: `HERMES_STUDIO_BEARER` in `/etc/hermes/hermes-studio.env`, altrimenti
`$DATA/bearer.token` (0600, creato al primo avvio). Vedi `.env.example`.

## Coda / orchestrazione (via manager, mai Comfy diretto)
- Lo Studio accoda con `POST /api/v1/jobs {taskType: image|video, ...}`;
  il worker sottomette al manager (`POST /jobs/image|video` con preset
  versionati: `create_image`, `edit_image`, `journey_video_preview`,
  `journey_video_preview_chained`, `journey_video_preview_ref2va`,
  `journey_video_t2v`, `journey_video_reframe916`).
  In AUTO il manager fa unload LLM → avvio ComfyUI → run → restore LLM da solo.
  Lo Studio non tocca mai i servizi GPU: niente kill, reset o cambi modo manuali.
- Cap area manager: video tetto stretto 1032192 px; immagini fino a 2K
  (3686400 px) per workflow con `SaveImage`. Lo Studio normalizza in `_norm_wh`
  (snap 32px + clamp per-kind) e l'app propone solo tier conformi
  (2K / Full HD / HD / 480p); i reali finiscono nel job e nell'asset.
- Worker seriale: `queued→preparing→running→completed|failed`, `cancelling→cancelled`.
  Un job guasto non uccide il worker (guard + log con traceback).
- Restart: `running→queued` (reconcile via `comfy_prompt_id`, mai doppi submit),
  `preparing→failed` (submit interrotto: senza mid non è riconciliabile),
  `cancelling→cancelled` con cancel al manager.
- Cancel: `queued→cancelled` subito; `running/preparing→cancelling`, il worker
  propaga `POST /jobs/{mid}/cancel` al manager.
- Video (H3 turbo): poll manager ogni 10s (timeout 90min); 5 modalità
  (`video_mode`), durate 3/5/8s via `duration`. Se il manager è in coda dietro
  altri job, il job resta `running`: è normale.

## Manager / Comfy
- Mai `kill/reset`: solo `GET /status`, submit e cancel via `ManagerClient`
  (ruolo app, `app.key` — mai full-control).
- Preset richiesti dalle modalità video Studio: `journey_video_preview`,
  `journey_video_preview_chained`, `journey_video_preview_ref2va`,
  `journey_video_t2v`, `journey_video_reframe916`. Verifica:
  `sudo grep -o '"journey_video_[a-z0-9_]*"' /opt/hermes/gpu-manager/manager.py | sort -u`
  (`journey_video_t2v` usa `h3/t2v-turbo.json`). Se il repo HermesHub è più
  vecchio del deploy, riallineare prima di un redeploy del manager.
- Se il manager rifiuta (coda piena, preset ignoto, input fuori root fidate):
  job → `failed` con `manager submit failed: ...`, da riaccodare.

## Storage
- SQLite WAL `$DATA/studio.db`, `busy_timeout 30s`, indici su `jobs(status,created_at)`,
  migrazioni automatiche per colonne mancanti.
- Asset in `$DATA/assets`, thumb `$DATA/thumbs`; upload indicizzati subito in libreria.
- Upload: streaming su disco con cap 25MB (mai tutto in RAM), sniff JPEG/PNG/WebP
  + `verify()` PIL. File >25MB → 413, tipo ignoto → 415, vuoto/illeggibile → 422.
- `POST /maintenance/prune`: job terminali vecchi (default >30gg, grazia minima 1h)
  + file orfani in assets/staging non referenziati (default >7gg). Asset noti,
  progetti e job attivi mai toccati.

## SSE live (`GET /events`)
Solo eventi nuovi (seed a `updated_at` corrente) + heartbeat `: ping` ogni ~15s
e header anti-buffering (`Cache-Control: no-cache`, `X-Accel-Buffering: no`)
così i proxy (tailscale serve) non accumulano eventi.

## Troubleshooting
- 401: re-incolla token (`HERMES_STUDIO_BEARER` o `bearer.token`). `GET /health` è
  no-auth: usare `GET /capabilities` per il test reale.
- `database is locked`: timeout 30s + connessioni brevi; se persiste, backup:
  copiare `studio.db` a backend fermo.
- Immagini 404 in app: verificare baseUrl + token (download/thumb richiedono auth).
- Job `failed` con `worker crash`: leggere il traceback nel journal
  (`journalctl -u hermes-studio`), il worker resta vivo per gli altri job.
