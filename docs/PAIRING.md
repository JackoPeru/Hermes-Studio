# Pairing privato (Tailscale, niente porte pubbliche)

1. Sul telefono: installa Tailscale, stesso account di `hermes`, verifica `100.94.223.14` raggiungibile.
2. Sul server (dopo OK): avvia backend su `127.0.0.1:8650`, poi esponi SOLO in tailnet, es.:
   `tailscale serve --bg --https=443 http://127.0.0.1:8650` (verifica output, certificato MagicDNS automatico),
   oppure SSH port-forward / reverse proxy vincolato a `tailscale0`.
3. Leggi il token: `cat /var/lib/hermes-studio/bearer.token` (rotazione: cancella il file e riavvia dopo OK).
4. Nell'app → Impostazioni: incolla `https://<magicdns>/` + token → Salva e testa (test reale su `GET /capabilities` autenticato; `GET /health` è no-auth e non prova il token).
5. Mai Tailscale Funnel, mai ComfyUI esposto. In caso di 401: re-incolla token (salvato in DataStore privato dell'app, `allowBackup=false`).
