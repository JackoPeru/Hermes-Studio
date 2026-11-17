#!/bin/bash
# Verifica feed update PRIMA del push: manifest canonico + firma + size/SHA APK.
# La chiave pubblica e quella pinnata in UpdateReleaseSigner.kt (fingerprint
# SHA-256 SPKI sotto): un feed firmato con un altra chiave viene rifiutato.
# Uso: scripts/verify-feed.sh updates/latest.json HermesStudio-XX.apk
set -euo pipefail
FEED="${1:?uso: verify-feed.sh updates/latest.json HermesStudio-XX.apk}"
APK="${2:?serve APK per verificare size e SHA}"
PUBKEY="${HERMES_FEED_PUBKEY:-}"
PINNED_FP="91e247fd0c04e5b74cf7ea733625f2f6348ed3d1dc8fd527e0327a07a9a97fe7"
command -v openssl >/dev/null || { echo "serve openssl"; exit 1; }
command -v python3 >/dev/null || { echo "serve python3"; exit 1; }
test -f "$FEED" || { echo "feed assente: $FEED"; exit 1; }
test -f "$APK" || { echo "apk assente: $APK"; exit 1; }
if [ -z "$PUBKEY" ]; then
  PUBKEY="$(dirname "$0")/feed-pub.pem"
fi
test -f "$PUBKEY" || { echo "chiave pubblica assente: $PUBKEY"; exit 1; }

FP="$(openssl pkey -pubin -in "$PUBKEY" -outform DER | sha256sum | cut -d' ' -f1)"
test "$FP" = "$PINNED_FP" || { echo "chiave pubblica diversa da quella pinnata ($FP)"; exit 1; }

python3 "$(dirname "$0")/verify_feed.py" "$FEED" "$APK" "$PUBKEY"
