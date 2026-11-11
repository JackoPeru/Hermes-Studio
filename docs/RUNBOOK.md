# Runbook operativo V1

## Avvio
`HERMES_STUDIO_DATA=/var/lib/hermes-studio uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8650`
Token in `$DATA/bearer.token` (0600). Vedi `.env.example` per tutte le env.

## Coda /orchestrazione (via manager, mai Comfy diretto)
- Image e video vanno al manager (`POST /jobs/image|video` con preset versionati):
  in AUTO il manager fa unload LLM → avvio ComfyUI → run → restore LLM da solo.
  Lo Studio non tocca mai i servizi GPU: niente kill, reset o cambi modo manuali.
- Worker seriale: `queued→preparing→running→completed|failed`, `cancelling→cancelled`.
- Restart: `preparing→queued`; `running` con id manager → riconciliazione via `GET /jobs/{mid}`.
- Cancel: `queued→cancelled` subito; `running→cancelling`, il worker propaga `POST .../cancel` al manager.

## Comfy / Manager
- Mai `kill/reset`: solo `GET /status` manager, `POST /interrupt` best-effort su cancel.
- Se Comfy offline (LLM residente): job → `failed` con `comfy submit failed`, da riaccodare.

## Storage
- SQLite WAL `$DATA/studio.db`, `busy_timeout 30s`, indici su `jobs(status,created_at)`.
- Asset in `$DATA/assets`, thumb `$DATA/thumbs`, upload `$DATA/uploads` (scrittura atomica `.part`).
- Upload: streaming con cap 25MB, sniff JPEG/PNG/WebP. File >25MB → 413, tipo ignoto → 415.

## Troubleshooting
- 401: re-incolla token da `bearer.token` (o `HERMES_STUDIO_BEARER`). `GET /health` è no-auth: usare `GET /capabilities` per test reale.
- Video (image-to-video H3 turbo): il worker interroga il manager ogni 10s (timeout 90min). Se il manager è in coda dietro altri job, il job resta `running`: è normale.
- `database is locked`: aggiornare a questa V1 (timeout + connessioni brevi). Backup: copiare `studio.db` a backend fermo.
- Immagini 404 in app: verificare baseUrl + token (download/thumb richiedono auth).
