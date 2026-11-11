# Hermes Studio — DISCOVERY (Fase A, solo lettura)

Data: 2026-11-11. Server `hermes` via SSH LAN `matteo@192.168.1.6` (Tailscale SSH richiede check interattivo, non usato). Nessun restart/deploy, solo GET/lettura.

## 1. Macchina
- Ubuntu 24.04.5 LTS, RAM 30 GB (16 usati), disk 936 GB (564 usati, 333 liberi).
- 2× NVIDIA RTX 5060 Ti 16 GB. Al rilievo: GPU0 15447/16311 MiB 97%, GPU1 12322/16311 MiB 60%. Carico da LLM residente, non avviare job GPU pesanti senza coordinamento.
- Uptime 10 giorni. Tailscale: hermes `100.94.223.14`, pc-matteo-1 attivo direct `192.168.1.7`, telefono `oneplus-7-di-matteo`, `redmi-pad-2` presenti.

## 2. Servizi
- `hermes-hub.service` (user unit) **active**, VERSION live `0.6.334`.
- `hermes-gpu-manager.service` **active**. `gamestream-mkw.service` inactive.
- Porte: 8642 gateway (hermes), 8643 manager, 9119 dashboard, 8020 Kokoro TTS, 8000 Tabby LLM (127.0.0.1), 8010 UniNote STT su tailnet, 8645 laya bridge. **8188 ComfyUI non in ascolto** al rilievo (media offline, atteso: manager in AUTO con LLM residente).
- Manager `/status` (con chiave manager, solo lettura): `desired_mode AUTO`, `current_state LLM_READY`, `llm_online true`, modello `Qwen3.8-27B-Uncensored-EXL3-3.07bpw` 24 GB VRAM, `media_online false`, `queue_length 0`, `voice_services true`.
- Config live `/etc/hermes/gpu-manager.yaml`: `default_mode auto`, `allow_manual_modes true`, sezioni `llm.tabbyapi 127.0.0.1:8000`, `media.comfyui 127.0.0.1:8188 output /opt/hermes/runtimes/comfyui/app/output`, `h3.enabled false`.

## 3. ComfyUI e modelli (verificato su disco)
- App: `/opt/hermes/runtimes/comfyui/app` (main.py presente), venv `/opt/hermes/runtimes/comfyui/venv`, service `hermes-comfyui.service` (`--listen 127.0.0.1 --port 8188 --lowvram`, Restart on-failure). Direct: `hermes-comfyui-direct.service` su tailnet `100.94.223.14:8188` (non usare in V1, resta su adapter via manager).
- Custom nodes: `ComfyUI-GGUF`, `ComfyUI-H3-MultiStream`, `ComfyUI-MiniMaxH3-PrefixStream`, `ComfyUI-Ref2VA-VSA`, `websocket_image_save.py`.
- Pesi verificati:
  - `diffusion_models/qwen-image-2.1-UC-Q4_K_M.gguf` 4.3 GB — OK.
  - `text_encoders/qwen3vl_8b_w4a8.safetensors` 5.9 GB — OK ma in `text_encoders/`, `clip/` contiene solo placeholder. Da verificare risoluzione `CLIPLoader` (extra paths o symlink) prima di dichiarare T2I pronto.
  - `vae/qwen_image_2.1_vae_bf16.safetensors` 645 MB — OK.
  - `diffusion_models/minimax_h3_*_pruned_int8_convrot.safetensors` (symlink a `/opt/hermes/models/minimax-h3/`) + `seedvr2_3b_int8_convrot.safetensors` 3.3 GB, VAE H3 presenti.
  - `/opt/hermes/models/minimax-h3/` completo (FL2VA, Ref2VA, turbo LoRA 4step, VSA gate, camera LoRA, audio VAE).
- Manager riporta `qwen_image_installed false`, `h3_installed false` (check interno, probabilmente cerca in `clip/` o flag installazione) — non contraddice i pesi su disco, ma va trattato come “non pronto finché non validato via API”.

## 4. Workflow reali (API format, con placeholder {{...}})
- `/opt/hermes/media-workflows/qwen/qwen-image-2.1-8gb-t2i.json` (T2I): `UnetLoaderGGUF + CLIPLoader(qwen_image) + VAELoader + TextEncodeQwenImage21(PROMPT, NEGATIVE_PROMPT, resolution 1024) + EmptyLatent(W,H) + KSampler(euler/simple, cfg 1.0, STEPS, SEED) + VAEDecode + SaveImage(journey/JOB_ID)`.
- `.../qwen-image-2.1-8gb-edit.json` (I2I, `needs_input`): `LoadImage ×2 + TextEncodeQwenImage21(images.image_1/2, vae) + QwenImage21Cache + ComfySwitchNode`.
- `/opt/hermes/media-workflows/h3/i2v-turbo.json` (I2V turbo 4 step): `UNETLoader(H3 FL2VA int8) + CLIPLoader(minimax) + VAELoader(H3 VAE) + LoadImage(INPUT_IMAGE) + MiniMaxH3ImageToVideo(W,H,LENGTH,PROMPT) + LoraLoader(turbo4+astro) + H3MultiStream + KSampler(4 step) + VAEDecode + SaveAnimatedPNG(fps 24)`.
- Altri: `H3_Ref2VA_*_READY*.json`, `ref2va-vsa.json`, `first_last.json`, `seedvr_image_upscale.json`. I preset manager `journey_video_quality/first_last/reference` marcati `DISABLED_FULLSTEPS_OOM` nel codice.

## 5. Vincolo critico video
- `gpu-manager.yaml`: `h3.enabled false`, `license_state DISABLED_LICENSE_GATE` — “EU/Italy esclusa da MiniMax community license. Non scaricare/eseguire pesi H3 senza autorizzazione scritta MiniMax”.
- Manager `/status`: `h3_license_state DISABLED_LICENSE_GATE`.
- Decisione V1: **pipeline video disabilitata in UI con spiegazione**, endpoint `POST /jobs` con `taskType video` risponde `409 + license_gate`. Niente generazione H3 finché il proprietario non fornisce autorizzazione scritta. Questo soddisfa il criterio “blocco documentato con motivo tecnico specifico”.

## 6. Evidenze pipeline immagine
- `output/journey/` contiene PNG odierni (11 nov, fino a 233 MB l’uno, 14 GB totali) — generazioni Qwen recenti riuscite. Nomi `JOBID_00001_.png` coerenti con `filename_prefix journey/JOB_ID`.
- `gpu-manager/outputs/` contiene 4 dir job (`464861130fad` …). Da ispezionare dopo (solo lettura) per mapping job→output.
- Prossimo passo non invasivo: `GET /object_info` e `GET /queue` quando ComfyUI sarà online via manager (mai forzare switch con GPU occupate e contatore agenti attivo).

## 7. Client Android / toolchain locale
- JDK Temurin 21.0.11, Android SDK platforms 35/36 + build-tools 34/36, adb 1.0.41, Python 3.13.15 (percorsi assoluti, non in PATH).
- Repo HermesHub presente in `C:\Users\matte\Desktop\Saas\HermesHub` (gateway 0.6.334). Lavoro HermesStudio in `Media hermes\HermesStudio` isolato per non toccare HermesHub senza OK.

## 8. Rischi e regole
- GPU occupate + `active_agents` storicamente incastrato: nessun `restart/deploy/commit/push/tag/release`, nessun `--force`, nessuna interruzione di worker esistenti. Solo lettura finché OK esplicito.
- Mai esporre ComfyUI su internet, mai Funnel; solo Tailscale Serve/privato + bearer. Nessun token hardcoded, nessun cloud/telemetria esterna.
- Non promettere in UI: percentuali/ETA inventate, camera control (Dolly/Pan/Loop come strutturali), formati/durate non validati, preset stile come modelli reali (solo template di prompt tracciati).
