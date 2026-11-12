# Runbook operativo (V3, normativo dove README rimanda qui)

## Avvio
`HERMES_STUDIO_DATA=/var/lib/hermes-studio uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8650`
Token: `HERMES_STUDIO_BEARER` in `/etc/hermes/hermes-studio.env`, altrimenti
`$DATA/bearer.token` (0600, creato al primo avvio). Vedi `.env.example`.

## Coda / orchestrazione (via manager, mai Comfy diretto)
- Lo Studio accoda con `POST /api/v1/jobs {taskType: image|video, ...}`;
  il worker sottomette al manager (`POST /jobs/image|video` con preset
  versionati: `create_image`, `edit_image`, `journey_video_preview[_chained]`).
  In AUTO il manager fa unload LLM → avvio ComfyUI → run → restore LLM da solo.
  Lo Studio non tocca mai i servizi GPU: niente kill, reset o cambi modo manuali.
- Cap area manager: video tetto stretto 1032192 px; immagini fino a 2K
  (3686400 px) per workflow con `SaveImage`. Lo Studio normalizza in `_norm_wh`
  (snap 32px + clamp per-kind) e l'app propone solo tier conformi
  (2K / Full HD / HD / 480p); i reali finiscono nel job e nell'asset.
- Worker seriale: `queued→preparing→running→completed|failed`, `cancelling→cancelled`.
  Un job guasto non uccide il worker (guard + log con traceback).
- Restart: `preparing/running→queued` (reconcile via `comfy_prompt_id` che
  riusa l'id manager, mai doppi submit), `cancelling→cancelled`.
- Cancel: `queued→cancelled` subito; `running/preparing→cancelling`, il worker
  propaga `POST /jobs/{mid}/cancel` al manager.
- Video (image-to-video H3 turbo): poll manager ogni 10s (timeout 90min);
  durate 3/5/8s via `duration`. Se il manager è in coda dietro altri job,
  il job resta `running`: è normale.

## Manager / Comfy
- Mai `kill/reset`: solo `GET /status`, submit e cancel via `ManagerClient`
  (ruolo app, `app.key` — mai full-control).
- Se il manager rifiuta (coda piena, preset ignoto, input fuori root fidate):
  job → `failed` con `manager submit failed: ...`, da riaccodare.

## Storage
- SQLite WAL `$DATA/studio.db`, `busy_timeout 30s`, indici su `jobs(status,created_at)`,
  migrazioni automatiche per colonne mancanti.
- Asset in `$DATA/assets`, thumb `$DATA/thumbs`; upload indicizzati subito in libreria.
- Upload: streaming su disco con cap 25MB (mai tutto in RAM), sniff JPEG/PNG/WebP.
  File >25MB → 413, tipo ignoto → 415, vuoto → 422.
- `POST /maintenance/prune`: job terminali vecchi (default >30gg, grazia minima 1h)
  + upload orfani (default >7gg). Asset/progetti/attivi mai toccati.

## SSE live (`GET /events`)
Heartbeat `: ping` ogni ~15s + header anti-buffering (`Cache-Control: no-cache`,
`X-Accel-Buffering: no`) così i proxy (tailscale serve) non accumulano eventi.

## Troubleshooting
- 401: re-incolla token (`HERMES_STUDIO_BEARER` o `bearer.token`). `GET /health` è
  no-auth: usare `GET /capabilities` per il test reale.
- `database is locked`: timeout 30s + connessioni brevi; se persiste, backup:
  copiare `studio.db` a backend fermo.
- Immagini 404 in app: verificare baseUrl + token (download/thumb richiedono auth).
- Job `failed` con `worker crash`: leggere il traceback nel journal
  (`journalctl -u hermes-studio`), il worker resta vivo per gli altri job.
