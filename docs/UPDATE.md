# Auto-update in-app (classico HermesHub / UniNote)

Stesso pattern di UniNote + HermesHub, adattato a `com.hermes.studio`.
Il feed vive **nella stessa repo** (pubblica): `updates/latest.json`
(raw: `https://raw.githubusercontent.com/JackoPeru/Hermes-Studio/main/updates/latest.json`),
come HermesHub che legge le release dalla propria repo. Niente seconda repo
(UniNote la usa solo perché la sua primaria è privata).

- Feed: `updates/latest.json`
  (`versionCode, versionName, tagName, apkUrl, assetSize, sha256, signature, notes`).
- Firma fail-closed ECDSA P-256 su manifest canonico
  `hermes-studio-update-v1\nCODE\nNAME\nURL\nSIZE\nSHA` (`update/UpdateReleaseSigner.kt`).
  Senza firma valida → update rifiutato, mai installato.
- Download `.part` → rename atomico, verifica size + SHA-256, riverifica a tap Install.
- Signing identity: l'APK deve condividere un firmatario con l'app installata.
  Tutti i build (CI debug + release) usano lo **stesso keystore stabile**
  (`HERMES_KEYSTORE_*` nei secrets + `signingConfigs/stable` in `app/build.gradle.kts`),
  quindi la catena degli update non si rompe mai. Build locali senza env usano
  la debug-key di default (invariati).
- Rollback: conserva max 2 APK (nuovo + installato), UI "Torna a X".
- Background: `WorkManager` ogni 6h + notifica (opt-out in Impostazioni).
- Permessi: `REQUEST_INSTALL_PACKAGES` + `FileProvider` (`res/xml/file_paths.xml`).

## Chiavi (generate 2026-11-11, MAI nel repo)

- Feed priv: `Documents/HermesStudio-update-keys/hermes-studio-update-sign.key`
- Feed pubb (SPKI b64 in `UpdateReleaseSigner`): fingerprint
  `91e247fd0c04e5b74cf7ea733625f2f6348ed3d1dc8fd527e0327a07a9a97fe7`
- APK keystore: `Documents/HermesStudio-update-keys/hermes-studio-apk.keystore`
  + `HERMES_KEYSTORE_*` nei GitHub secrets. Backup della cartella = backup del canale update.
- Smarrire la chiave feed = rigenerare coppia + reinstallazione pulita.

## Pubblicare un update

1. Lancia `Build And Publish Release APK` (workflow_dispatch): compila il debug
   firmato stabile con `versionCode = 10 + run_number`, crea la GitHub release
   **nella stessa repo** e aggiorna `updates/latest.json` con `signature: ""`.
2. Firma in locale:
   `printf 'hermes-studio-update-v1\n%s\n%s\n%s\n%s\n%s' CODE NAME URL SIZE SHA | openssl dgst -sha256 -sign hermes-studio-update-sign.key | base64 -w0`
   usando i valori stampati dal workflow (`MANIFEST-DA-FIRMARE`).
3. Compila `signature` in `updates/latest.json`, commit + push.
4. Test: Impostazioni → Controlla ora → Scarica → Installa.

## Nota primo passaggio (2026-11-11)

Il build `v0.1.1-test.1` era firmato con la debug-key effimera della CI,
quello da `test.2` in poi con il keystore stabile: **firme diverse = Android
blocca l'update diretto**. Disinstallare test.1, installare test.2 manualmente
una volta (si perdono URL/token in DataStore: rifare il pairing), poi gli
update tornano automatici via app.
