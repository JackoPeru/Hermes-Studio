# Runbook operativo V1

## Avvio
`HERMES_STUDIO_DATA=/var/lib/hermes-studio uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8650`
Token in `$DATA/bearer.token` (0600). Vedi `.env.example` per tutte le env.

## Coda / reconcile
- Worker seriale: `queued→preparing→running→completed|failed`, `cancelling→cancelled`.
- Restart: `preparing→queued`; `running` senza `comfy_prompt_id→queued`, con `prompt_id→running` (reconcile via `/history`, niente doppi submit).
- Cancel: `queued→cancelled` subito; `running/preparing→cancelling`, worker fa `POST /interrupt` a Comfy e flippa a `cancelled`.

## Comfy / Manager
- Mai `kill/reset`: solo `GET /status` manager, `POST /interrupt` best-effort su cancel.
- Se Comfy offline (LLM residente): job → `failed` con `comfy submit failed`, da riaccodare.

## Storage
- SQLite WAL `$DATA/studio.db`, `busy_timeout 30s`, indici su `jobs(status,created_at)`.
- Asset in `$DATA/assets`, thumb `$DATA/thumbs`, upload `$DATA/uploads` (scrittura atomica `.part`).
- Upload: streaming con cap 25MB, sniff JPEG/PNG/WebP. File >25MB → 413, tipo ignoto → 415.

## Troubleshooting
- 401: re-incolla token da `bearer.token` (o `HERMES_STUDIO_BEARER`). `GET /health` è no-auth: usare `GET /capabilities` per test reale.
- 409 video: atteso (LICENSE_GATE H3). Vedi `docs/DISCOVERY.md`.
- `database is locked`: aggiornare a questa V1 (timeout + connessioni brevi). Backup: copiare `studio.db` a backend fermo.
- Immagini 404 in app: verificare baseUrl + token (download/thumb richiedono auth).
