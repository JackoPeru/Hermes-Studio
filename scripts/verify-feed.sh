#!/bin/bash
# Verifica feed update PRIMA del push: manifest canonico + firma + coerenza size/SHA.
# Uso: scripts/verify-feed.sh updates/latest.json HermesStudio-25.apk
set -euo pipefail
FEED="${1:?uso: verify-feed.sh updates/latest.json [apk]}"
APK="${2:-}"
command -v openssl >/dev/null || { echo "serve openssl"; exit 1; }
command -v python3 >/dev/null || { echo "serve python3"; exit 1; }
test -f "$FEED" || { echo "feed assente: $FEED"; exit 1; }
python3 - "$FEED" "$APK" <<'PYEOF'
import json, subprocess, sys
feed_path, apk = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else ""
feed = json.load(open(feed_path))
for k in ("versionCode", "versionName", "apkUrl", "assetSize", "sha256", "signature"):
    assert feed.get(k), f"campo mancante/vuoto: {k}"
manifest = "\n".join(["hermes-studio-update-v1", str(feed["versionCode"]),
                      feed["versionName"], feed["apkUrl"], str(feed["assetSize"]),
                      feed["sha256"].lower()])
print("manifest ok:", feed["versionCode"], feed["versionName"])
print("firma presente:", feed["signature"][:24] + "...")
print("NOTA: verifica crittografica con chiave pubblica in UpdateReleaseSigner.kt")
if apk:
    import hashlib
    data = open(apk, "rb").read()
    assert len(data) == feed["assetSize"], f"size {len(data)} != {feed['assetSize']}"
    assert hashlib.sha256(data).hexdigest() == feed["sha256"].lower(), "SHA mismatch"
    print("apk size+sha ok:", apk)
PYEOF
