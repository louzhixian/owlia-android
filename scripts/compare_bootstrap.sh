#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <old_bootstrap.zip> <new_bootstrap.zip>" >&2
  echo "Example: $0 /tmp/bootstrap-old.zip /tmp/bootstrap-new.zip" >&2
  exit 1
fi

OLD_ZIP="$1"
NEW_ZIP="$2"

if [[ ! -f "$OLD_ZIP" ]]; then
  echo "ERROR: old bootstrap not found: $OLD_ZIP" >&2
  exit 1
fi
if [[ ! -f "$NEW_ZIP" ]]; then
  echo "ERROR: new bootstrap not found: $NEW_ZIP" >&2
  exit 1
fi

bytes() {
  stat -f%z "$1" 2>/dev/null || stat -c%s "$1"
}

human_bytes() {
  local n="$1"
  if command -v numfmt >/dev/null 2>&1; then
    numfmt --to=iec-i --suffix=B "$n"
  else
    echo "${n}B"
  fi
}

count_legacy_hits() {
  local zip="$1"
  local files=(
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
  local count=0
  local entries
  entries="$(unzip -Z1 "$zip")"
  for f in "${files[@]}"; do
    if ! rg -qx "$f" <<<"$entries"; then
      continue
    fi
    local content
    content="$(unzip -p "$zip" "$f" 2>/dev/null || true)"
    if echo "$content" | rg -q "/data/data/com\\.termux"; then
      count=$((count + 1))
    fi
  done
  echo "$count"
}

has_adb() {
  local zip="$1"
  if unzip -Z1 "$zip" | rg -qx "bin/adb"; then
    echo "yes"
  else
    echo "no"
  fi
}

OLD_SIZE="$(bytes "$OLD_ZIP")"
NEW_SIZE="$(bytes "$NEW_ZIP")"
DELTA=$((NEW_SIZE - OLD_SIZE))
ABS_DELTA="$DELTA"
SIGN="+"
if (( DELTA < 0 )); then
  ABS_DELTA=$(( -DELTA ))
  SIGN="-"
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unzip -Z1 "$OLD_ZIP" | sort > "$TMP_DIR/old.list"
unzip -Z1 "$NEW_ZIP" | sort > "$TMP_DIR/new.list"

comm -13 "$TMP_DIR/old.list" "$TMP_DIR/new.list" > "$TMP_DIR/added.list"
comm -23 "$TMP_DIR/old.list" "$TMP_DIR/new.list" > "$TMP_DIR/removed.list"

OLD_ADB="$(has_adb "$OLD_ZIP")"
NEW_ADB="$(has_adb "$NEW_ZIP")"
OLD_LEGACY="$(count_legacy_hits "$OLD_ZIP")"
NEW_LEGACY="$(count_legacy_hits "$NEW_ZIP")"

echo "== Bootstrap Compare =="
echo "old: $OLD_ZIP"
echo "new: $NEW_ZIP"
echo
echo "Size:"
echo "  old: $OLD_SIZE ($(human_bytes "$OLD_SIZE"))"
echo "  new: $NEW_SIZE ($(human_bytes "$NEW_SIZE"))"
echo "  delta: ${SIGN}${ABS_DELTA} ($(human_bytes "$ABS_DELTA"))"
echo
echo "Critical checks:"
echo "  adb present: old=$OLD_ADB new=$NEW_ADB"
echo "  legacy com.termux hits (known files): old=$OLD_LEGACY new=$NEW_LEGACY"
echo
echo "File list diff:"
echo "  added: $(wc -l < "$TMP_DIR/added.list" | tr -d ' ')"
echo "  removed: $(wc -l < "$TMP_DIR/removed.list" | tr -d ' ')"

if [[ -s "$TMP_DIR/added.list" ]]; then
  echo
  echo "Top added entries:"
  sed -n '1,40p' "$TMP_DIR/added.list"
fi

if [[ -s "$TMP_DIR/removed.list" ]]; then
  echo
  echo "Top removed entries:"
  sed -n '1,40p' "$TMP_DIR/removed.list"
fi

