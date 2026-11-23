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
- Template API attivi usati dallo Studio:
  `h3/i2v-turbo.json` (`journey_video_preview`, i2v 1 frame),
  `h3/i2v-turbo-916-chained.json` (`journey_video_preview_chained`, primo/ultimo frame),
  `h3/i2v-turbo-ref2va.json` (`journey_video_preview_ref2va`, 1-4 reference),
  `h3/t2v-turbo.json` (`journey_video_t2v`, derivato da i2v turbo senza frame, verificato con render),
  `h3/ref2va-reframe-916.json` (`journey_video_reframe916`, video input → 9:16 con audio, verificato).
- Preset full-steps (`journey_video_quality/first_last/reference`) marcati
  `DISABLED_FULLSTEPS_OOM` upstream → mai usati dallo Studio.

## Flusso implementato (image-to-video)
1. App: start frame dalla libreria + movement prompt + formato (16:9/9:16 in 720p o 480p, nient'altro).
2. Backend: `POST /jobs {taskType: video, video_mode, asset_ids?, input_video_asset_id?, width, height}` → preset per modalità (il manager allinea ai multipli di 32 e al cap video).
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
- Modalità (`video_mode`) verificate con render reali: `i2v` (1 frame,
  journey_video_preview), `first_last` (2 frame, chained turbo), `ref2va`
  (1-4 reference, ref2va turbo), `t2v` (solo testo, preset journey_video_t2v
  derivato dal turbo i2v), `v2v` (video input, reframe916: output 9:16 con
  audio, ~11 min nel test).
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
