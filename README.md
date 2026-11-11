# Hermes Studio — kit V1 funzionante (vertical slice)

App Android nativa (Kotlin+Compose, Material3) + backend privato (FastAPI) per generare immagini con Qwen-Image 2.1 via ComfyUI sul server `hermes`. Video **disabilitato per license-gate MiniMax H3** (documentato, UI + API 409).

## Struttura
- `backend/` FastAPI `/api/v1/*` (jobs persistenti SQLite WAL, ComfyUIAdapter, coda serializzata, SSE, assets+thumbs, progetti/preferiti).
- `android/` app nativa: Home, Crea (immagine), Crea video (gate), Libreria, Impostazioni. Nessuna WebView.
- `docs/DISCOVERY.md` ricognizione reale, `docs/API.md` contratto, `docs/PAIRING.md` pairing Tailscale.
- `tests/test_backend.py` 7 test verdi.

## Avvio backend (sul server, DOPO ok esplicito — non eseguire senza autorizzazione)
```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r backend/requirements.txt
HERMES_STUDIO_DATA=/var/lib/hermes-studio HERMES_STUDIO_HOST=127.0.0.1 HERMES_STUDIO_PORT=8650 \
  uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8650
# token in $DATA/bearer.token (0600); incollarlo nell'app in Impostazioni.
# Tailscale Serve davanti per HTTPS privato (vedi docs/PAIRING.md).
```

## App
Apri `android/` in Android Studio, imposta backend+token in Impostazioni, build `assembleDebug`. La coda sopravvive a chiusura app (stato dal server).

## Limiti V1 onesti
- Solo `text-to-image` Qwen (edit/reference disabilitato finché non validato).
- Niente ETA inventate: stato + step; progresso reale solo quando Comfy lo riporta.
- Concorrenza 1 job (VRAM reale); coordinamento via GPU-manager, mai kill/reset.
