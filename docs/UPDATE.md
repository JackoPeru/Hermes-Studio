# Auto-update in-app (classico HermesHub / UniNote)

Stesso pattern di UniNote + HermesHub, adattato a `com.hermes.studio`:

- Feed pubblico: `https://raw.githubusercontent.com/JackoPeru/HermesStudio-updates/main/latest.json`
  (`versionCode, versionName, tagName, apkUrl, assetSize, sha256, signature, notes`).
- Firma fail-closed ECDSA P-256 su manifest canonico
  `hermes-studio-update-v1\nCODE\nNAME\nURL\nSIZE\nSHA` (`update/UpdateReleaseSigner.kt`).
  Senza chiave pubblica configurata o firma invalida → update rifiutato, mai installato.
- Download `.part` → rename atomico, verifica size + SHA-256, riverifica a tap Install.
- Signing identity: l'APK deve condividere un firmatario con l'app installata.
- Rollback: conserva max 2 APK (nuovo + installato), UI "Torna a X".
- Background: `WorkManager` ogni 6h + notifica (opt-out in Impostazioni).
- Permessi: `REQUEST_INSTALL_PACKAGES` + `FileProvider` (`res/xml/file_paths.xml`).

## Setup maintainer (una tantum)

1. `openssl ecparam -genkey -name prime256v1 -noout -out hermes-studio-update-sign.key` (SOLO locale, mai nel repo).
2. `openssl ec -in hermes-studio-update-sign.key -pubout -outform DER | base64 -w0` → incolla in
   `UpdateReleaseSigner.RELEASE_SIGNING_PUBLIC_KEY_SPKI_B64`.
3. Crea repo pubblico `JackoPeru/HermesStudio-updates` (vuoto, con `README.md`).
4. Lancia `Build And Publish Release APK` (workflow_dispatch) → pubblica `latest.json` con `signature: ""`.
5. Firma in locale:
   `printf 'hermes-studio-update-v1\n%s\n%s\n%s\n%s\n%s' CODE NAME URL SIZE SHA | openssl dgst -sha256 -sign hermes-studio-update-sign.key | base64 -w0`
   compila `signature` in `latest.json` nel repo updates, push.
6. Test: Impostazioni → Controlla ora → Scarica → Installa. Smarrire la chiave = rigenerare + reinstallazione pulita.

## Note V1

Finché `RELEASE_SIGNING_PUBLIC_KEY_SPKI_B64` è vuota, l'app mostra
"Canale update senza chiave di firma configurata" e rifiuta ogni update remoto.
È il comportamento voluto (fail-closed), identico a UniNote dopo la rotazione chiave 2026-10-03.
