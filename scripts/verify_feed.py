"""Verifica feed update: manifest canonico, firma ECDSA, size/SHA APK.

Stessa canonicalizzazione di UpdateReleaseSigner.kt (Kotlin): versionCode e
assetSize devono essere interi JSON, SHA lowercase, niente newline finale.
"""
from __future__ import annotations

import base64
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 4:
        print("uso: verify_feed.py feed.json app.apk public.pem", file=sys.stderr)
        return 2
    feed_path, apk_path, pubkey = sys.argv[1:4]
    feed = json.loads(Path(feed_path).read_text(encoding="utf-8"))
    for key in ("versionCode", "versionName", "apkUrl", "assetSize", "sha256", "signature"):
        if not feed.get(key):
            print(f"campo mancante/vuoto: {key}", file=sys.stderr)
            return 1
    if type(feed["versionCode"]) is not int:
        print("versionCode deve essere un intero JSON", file=sys.stderr)
        return 1
    if type(feed["assetSize"]) is not int:
        print("assetSize deve essere un intero JSON", file=sys.stderr)
        return 1
    manifest = "\n".join([
        "hermes-studio-update-v1",
        str(feed["versionCode"]),
        feed["versionName"],
        feed["apkUrl"],
        str(feed["assetSize"]),
        feed["sha256"].lower(),
    ])
    data = Path(apk_path).read_bytes()
    if len(data) != feed["assetSize"]:
        print(f"size {len(data)} != {feed['assetSize']}", file=sys.stderr)
        return 1
    if hashlib.sha256(data).hexdigest() != feed["sha256"].lower():
        print("SHA mismatch", file=sys.stderr)
        return 1
    try:
        signature = base64.b64decode(feed["signature"], validate=True)
    except Exception as exc:  # noqa: BLE001 - messaggio unico
        print(f"firma base64 non valida: {exc}", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        mf = Path(tmp) / "manifest.txt"
        sg = Path(tmp) / "signature.bin"
        mf.write_text(manifest, encoding="utf-8")
        sg.write_bytes(signature)
        r = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", pubkey,
             "-signature", str(sg), str(mf)],
            capture_output=True, text=True,
        )
    out = (r.stdout + r.stderr).strip()
    print(out)
    if r.returncode != 0 or "Verified OK" not in out:
        print("FIRMA NON VALIDA: feed rifiutato", file=sys.stderr)
        return 1
    print(f"OK: {feed['versionCode']} {feed['versionName']} · {apk_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
