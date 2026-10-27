#!/usr/bin/env bash
# Run ONCE on your own computer. Creates the key that signs every release of the app.
# Needs JDK (keytool). Usage: bash tools/make-keystore.sh
set -euo pipefail
OUT="dairy-release.jks"
[ -e "$OUT" ] && { echo "$OUT already exists - not overwriting."; exit 1; }
keytool -genkeypair -v -keystore "$OUT" -alias dairy -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=GKhata, O=Personal, C=NP"
echo
echo "=== GitHub: Repo > Settings > Secrets and variables > Actions > New repository secret ==="
echo "KEYSTORE_B64  = (the long text below)"
base64 -w0 "$OUT" 2>/dev/null || base64 "$OUT" | tr -d '\n'
echo
echo "KS_PASS       = the keystore password you just typed"
echo "KEY_PASS      = the key password you just typed (same as KS_PASS if you pressed Enter)"
echo
echo "!! Now copy $OUT to 2 safe places (USB + password manager). If it is lost you can NEVER update the installed app"
echo "!! (you would have to uninstall it and lose its data). Never upload $OUT to GitHub."
