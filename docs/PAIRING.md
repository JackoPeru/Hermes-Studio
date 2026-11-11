# Pairing privato (Tailscale, niente porte pubbliche)

1. Sul telefono: installa Tailscale, stesso account di `hermes`, verifica che `hermes.tail85940c.ts.net` sia raggiungibile (il serve HTTP risponde solo al nome MagicDNS, non all'IP letterale).
2. Sul server: backend su `127.0.0.1:8650` + `sudo tailscale serve --bg --http=8650 http://127.0.0.1:8650` (verificato: serve `http://hermes.tail85940c.ts.net:8650` in tailnet).
3. Token: `HERMES_STUDIO_BEARER` in `/etc/hermes/hermes-studio.env` (attuale: frase di pairing impostata dal proprietario).
4. Nell'app → Impostazioni: incolla `http://hermes.tail85940c.ts.net:8650/` + token → Salva e testa (test reale su `GET /capabilities` autenticato; `GET /health` è no-auth e non prova il token).
5. Mai Tailscale Funnel, mai ComfyUI esposto. In caso di 401: re-incolla token (salvato in DataStore privato dell'app, `allowBackup=false`).
