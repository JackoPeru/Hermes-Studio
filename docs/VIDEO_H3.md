# Video H3 — fatti verificati sul server (2026-11-11)

Il manager NON ha gate su H3: i preset turbo sono abilitati e gli submit passano
(verificato con submit reale). `h3.enabled` in gpu-manager.yaml non è letto da
alcun codice (solo documentazione): impostato a `true` dal proprietario in data
odierna. `h3_ready=false` è un check obsoleto (cerca un nome file del text encoder
diverso da quello usato dai template, che invece esiste e funziona).
Attivazione reale = render andato a buon fine, non flag.

Tutto sotto è rilevato via SSH read-only su `hermes`, non ipotizzato.

## Pesi e template
- `/opt/hermes/models/minimax-h3/`: FL2VA + Ref2VA int8, turbo 4-step, VAE fp16/int8, audio VAE, qwen3vl-32b per H3. Presenti.
- Template API: `h3/i2v-turbo.json` (preset `journey_video_preview`, 16:9),
  `h3/i2v-turbo-916-chained.json` (preset chained, 9:16). Entrambi abilitati nel manager.
- `h3/t2v.json` esiste ma **nessun preset lo usa** → text-to-video non offerto.
- Preset full-steps (`journey_video_quality/first_last/reference`) marcati
  `DISABLED_FULLSTEPS_OOM` upstream → mai usati dallo Studio.

## Flusso implementato (image-to-video)
1. App: start frame dalla libreria + movement prompt + formato (16:9/9:16 in 720p o 480p, nient'altro).
2. Backend: `POST /jobs {taskType: video, asset_ids: [start], width, height}` → preset per orientamento (il manager allinea ai multipli di 32 e al cap video).
3. Worker: risolve lo start frame su disco → `POST manager /jobs/video
   {preset, parameters: {prompt, input_images: [abs], width, height, seed}}`
   (ruolo app, `app.key` — niente bypass del manager).
4. Poll manager `GET /jobs/{mid}` ogni 10s (max 90min); cancel → `POST .../cancel`.
5. Done: copia il derivato `.h264.mp4` (generato dal manager) o converte con ffmpeg,
   `ffprobe` per durata/risoluzione, thumbnail a 0.5s, asset `kind=video` + `duration`.

## Note operative
- Auth verso il manager con ruolo app (`app.key`, mai full-control): submit verificato
  in 3ms su preset inesistente (400 atteso, nessun job creato), `/status` studio in 0.78s.
- Durate 3/5/8s via `duration` (2..10s accettati dal backend, l'app offre i tre
  tier verificati): il preset turbo scala `length` dai frame; render reale 8s
  verificato (7.86s misurati). Default ~5s se non specificata.
- Camera motion (Dolly/Pan/Loop): solo suggerimenti testuali appesi al prompt —
  il preset non ha parametri camera strutturali.
- Lo start frame deve stare in una root fidata del manager (`~/.hermes`,
  `/opt/hermes`, `/tmp`): lo Studio mette in staging in
  `~/.hermes/hermes-studio-in` (root fidata E scrivibile: la unit ha
  `ReadWritePaths` dedicato; `/tmp` e `/opt` non sono scrivibili nel sandbox
  `ProtectSystem=strict` — verificato con crash reale e traceback), submit,
  pulizia subito dopo. Percorsi fuori root = 400 onesto. Audit 2026-11-12:
  worker blindato con try/except + log, un job guasto non ferma più la coda.
- La coda Studio resta seriale: un video lungo blocca le immagini (VRAM reale).
- ComfyUI parte via manager quando serve (stato `media_online`); job in coda
  durante LLM residente è comportamento normale, non errore.
