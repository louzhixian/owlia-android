#!/usr/bin/env bash
set -euo pipefail

ZIP_PATH="${1:-app/src/main/cpp/bootstrap-aarch64.zip}"
if [[ ! -f "$ZIP_PATH" ]]; then
  echo "ERROR: bootstrap zip not found: $ZIP_PATH" >&2
  exit 1
fi

echo "== Bootstrap Audit =="
echo "file: $ZIP_PATH"
echo "size: $(stat -f%z "$ZIP_PATH" 2>/dev/null || stat -c%s "$ZIP_PATH") bytes"
zip_entries="$(unzip -Z1 "$ZIP_PATH")"

echo
echo "[1/4] Check core entries"
for entry in bin/pkg etc/apt/sources.list etc/profile; do
  if rg -qx "$entry" <<<"$zip_entries"; then
    echo "OK  $entry"
  else
    echo "MISS $entry"
  fi
done

echo
echo "[2/4] Check adb presence"
if rg -qx "bin/adb" <<<"$zip_entries"; then
  echo "OK  bin/adb present"
else
  echo "MISS bin/adb"
fi

echo
echo "[3/4] Check shebang/package prefix in bin/pkg"
PKG_HEAD="$(unzip -p "$ZIP_PATH" bin/pkg 2>/dev/null | head -n 1 || true)"
echo "bin/pkg shebang: ${PKG_HEAD:-<missing>}"
if [[ "$PKG_HEAD" == *"/data/data/app.botdrop/files/usr/bin/bash"* ]]; then
  echo "OK  shebang uses app.botdrop prefix"
else
  echo "WARN shebang does not use app.botdrop prefix"
fi

echo
echo "[4/4] Scan known files for legacy com.termux hardcode"
FILES=(
  bin/pkg
  bin/termux-change-repo
  bin/termux-info
  bin/termux-reset
  bin/login
  etc/profile
  etc/termux-login.sh
  etc/profile.d/init-termux-properties.sh
  etc/motd.sh
)

legacy_hits=0
for f in "${FILES[@]}"; do
  if ! rg -qx "$f" <<<"$zip_entries"; then
    continue
  fi
  content="$(unzip -p "$ZIP_PATH" "$f" 2>/dev/null || true)"
  if echo "$content" | rg -q "/data/data/com\\.termux"; then
    echo "LEGACY $f"
    legacy_hits=$((legacy_hits + 1))
  fi
done

if [[ "$legacy_hits" -eq 0 ]]; then
  echo "OK  no legacy com.termux hardcoded paths found in known files"
else
  echo "WARN found $legacy_hits known files with legacy com.termux paths"
fi
