# Hermes Studio — V2 (vertical slice completa)

App Android nativa (Kotlin+Compose, Material3) + backend privato (FastAPI) per generare immagini (Qwen-Image 2.1) e video (MiniMax H3 turbo, image-to-video) via ComfyUI sul server `hermes`.

## Struttura
- `backend/` FastAPI `/api/v1/*` (jobs persistenti SQLite WAL, ComfyUIAdapter, coda seriale, SSE, assets+thumbs, progetti/preferiti).
- `android/` app nativa: Home, Crea (immagine), Crea video, Libreria, Impostazioni (pairing + GPU live + **auto-update in-app**). Nessuna WebView.
- `docs/DISCOVERY.md` ricognizione reale, `docs/API.md` contratto, `docs/PAIRING.md` pairing Tailscale, `docs/RUNBOOK.md` operativo, `docs/UPDATE.md` auto-update, `docs/VIDEO_H3.md` pipeline video verificata.
- `tests/test_backend.py` 18 test verdi. `.env.example` per tutte le env.

## V2 (corrente)
- Dettaglio job (tap in coda: stato/progresso reali, output, annulla, riusa) e dettaglio asset (player video in-app, scarica in galleria, condividi, preferito, assegna a progetto, riusa, elimina, fullscreen con zoom per le immagini).
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
# token in $DATA/bearer.token (0600) oppure HERMES_STUDIO_BEARER; incollarlo nell'app in Impostazioni.
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

## Limiti V2 onesti
- Video = solo image-to-video H3 turbo (~5s, 1344×768 / 768×1344), start frame obbligatorio dalla libreria. Text-to-video: nessun preset nel manager (solo template grezzo mai validato) → non offerto.
- Immagini: formati conformi all'area cap manager (1:1→992×992, 16:9→1344×768, 9:16→768×1344), passi Bozza 12 / Alta 20 / Ultra 28.
- Edit/reference Qwen: pipeline non validata end-to-end → UI disabilitata.
- Niente ETA inventate: stato + step; progresso reale solo quando Comfy/manager lo riportano.
- Concorrenza 1 job (VRAM reale); coordinamento via GPU-manager, mai kill/reset.
- Auto-update in-app firmato dal feed `updates/latest.json` (`docs/UPDATE.md`).
