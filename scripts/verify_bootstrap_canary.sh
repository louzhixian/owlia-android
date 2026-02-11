#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-}"
PACKAGE_VARIANT="${2:-apt-android-7}"

if [[ -z "$BASE_URL" ]]; then
  echo "Usage: $0 <bootstrap_base_url> [package_variant]" >&2
  echo "Example: $0 https://github.com/zhixianio/botdrop-packages/releases/download/bootstrap-2026.02.11-r1+botdrop" >&2
  exit 1
fi

echo "== Verify Bootstrap Canary =="
echo "base_url: $BASE_URL"
echo "package_variant: $PACKAGE_VARIANT"

# Force re-download to avoid stale local bootstrap artifact.
rm -f app/src/main/cpp/bootstrap-aarch64.zip

export BOTDROP_BOOTSTRAP_BASE_URL="$BASE_URL"
export BOTDROP_BOOTSTRAP_REQUIRE_ADB=1
export BOTDROP_BOOTSTRAP_FAIL_ON_LEGACY_PATHS=1
export TERMUX_PACKAGE_VARIANT="$PACKAGE_VARIANT"

./gradlew :app:assembleDebug
scripts/audit_bootstrap.sh app/src/main/cpp/bootstrap-aarch64.zip

echo "Canary verification passed."
