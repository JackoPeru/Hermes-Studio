# Hermes Studio — V2 (vertical slice completa)

App Android nativa (Kotlin+Compose, Material3) + backend privato (FastAPI) per generare immagini con Qwen-Image 2.1 via ComfyUI sul server `hermes`. Video **disabilitato per license-gate MiniMax H3** (documentato, UI + API 409).

## Struttura
- `backend/` FastAPI `/api/v1/*` (jobs persistenti SQLite WAL, ComfyUIAdapter, coda seriale, SSE, assets+thumbs, progetti/preferiti).
- `android/` app nativa: Home, Crea (immagine), Crea video (gate), Libreria, Impostazioni (pairing + **auto-update in-app**). Nessuna WebView.
- `docs/DISCOVERY.md` ricognizione reale, `docs/API.md` contratto, `docs/PAIRING.md` pairing Tailscale, `docs/RUNBOOK.md` operativo, `docs/UPDATE.md` auto-update.
- `tests/test_backend.py` 14 test verdi. `.env.example` per tutte le env.

## V2 (corrente)
- Dettaglio job (tap in coda: stato/progresso reali, output, annulla, riusa) e dettaglio asset (scarica in galleria, condividi, preferito, assegna a progetto, riusa, elimina).
- SSE live (`GET /events` + fallback poll 15s) invece di polling cieco; niente recompose a vuoto, request Coil stabili, ricerca Libreria client-side.
- `DELETE /api/v1/assets/{id}` + `GET /api/v1/assets/{id}` con test.

## Prerequisiti
- Server: Python 3.12, ComfyUI `:8188`, gpu-manager `:8643`, systemd.
- App: JDK Temurin 17, Android SDK 35, Tailscale sullo stesso tailnet.

## Env backend (vedi `.env.example`)
`HERMES_STUDIO_DATA/HOST/PORT/COMFY_URL/MANAGER_URL/MANAGER_KEY/MAX_UPLOAD_MB/BEARER`.

## Avvio backend (sul server, DOPO ok esplicito — non eseguire senza autorizzazione)
```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r backend/requirements.txt
HERMES_STUDIO_DATA=/var/lib/hermes-studio HERMES_STUDIO_HOST=127.0.0.1 HERMES_STUDIO_PORT=8650 \
  uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8650
# token in $DATA/bearer.token (0600); incollarlo nell'app in Impostazioni.
# Tailscale Serve davanti per HTTPS privato (vedi docs/PAIRING.md).
```

## Test / lint
```bash
pip install -r backend/requirements-dev.txt
pytest -q
ruff check backend tests
```

## App
Apri `android/` in Android Studio (se manca il wrapper: `gradle wrapper`), imposta backend+token in Impostazioni, build `assembleDebug`. La coda sopravvive a chiusura app (stato dal server). Build con firma stabile + auto-update in-app: vedi `docs/UPDATE.md` + workflow `Build And Publish Release APK` (feed `updates/latest.json` nella stessa repo).

## Limiti V1 onesti
- Solo `text-to-image` Qwen (edit/reference disabilitato finché non validato).
- Niente ETA inventate: stato + step; progresso reale solo quando Comfy lo riporta.
- Concorrenza 1 job (VRAM reale); coordinamento via GPU-manager, mai kill/reset.
- Auto-update fail-closed finché la chiave pubblica non è configurata (`docs/UPDATE.md`).
