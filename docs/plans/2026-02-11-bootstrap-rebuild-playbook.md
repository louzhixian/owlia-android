# Bootstrap Rebuild Playbook (Minimal + Fast Path)

Date: 2026-02-11

## Objective

Ship a new `bootstrap-aarch64.zip` that:

- works under `app.botdrop` prefix
- keeps `pkg/apt` functional
- includes `adb` (`android-tools`)

while keeping rebuild time manageable.

## Phase 1: Minimal package set

In `botdrop-packages`, build only:

- `termux-tools`
- `apt`
- `dpkg`
- `android-tools`

plus dependency closure pulled by these packages.

Do not attempt full repository parity in this phase.

## Phase 2: Speed up build pipeline

Recommended CI knobs for `botdrop-packages`:

1. Enable `ccache` and persist cache by arch/toolchain hash.
2. Build changed packages first, not full world rebuild.
3. Split package build and bootstrap assembly jobs.
4. Reuse published `.deb` artifacts when assembling bootstrap.

## Phase 3: Publish and consume

1. Publish `.deb` + APT index (`Packages`, `Release`) to your static host.
2. Assemble `bootstrap-aarch64.zip` from that repository.
3. Publish bootstrap release in `botdrop-packages`.
4. In app build, pin bootstrap source when needed via:
   - `BOTDROP_BOOTSTRAP_BASE_URL`
   - example: `https://github.com/zhixianio/botdrop-packages/releases/download/bootstrap-2026.xx.yy-rN+botdrop`

## Verification in this repository

Use script:

```bash
scripts/audit_bootstrap.sh app/src/main/cpp/bootstrap-aarch64.zip
```

Or run Gradle audit directly (no APK build required):

```bash
./gradlew :app:auditBootstraps
```

For strict canary verification against a specific bootstrap release:

```bash
scripts/verify_bootstrap_canary.sh <bootstrap_base_url> [apt-android-7|apt-android-5]
```

For before/after comparison of two bootstrap zips:

```bash
scripts/compare_bootstrap.sh <old_bootstrap.zip> <new_bootstrap.zip>
```

It checks:

- core files (`bin/pkg`, `etc/apt/sources.list`, `etc/profile`)
- whether `bin/adb` exists
- legacy `/data/data/com.termux` hardcoded paths in known scripts

## Strict CI gates (optional)

`app/build.gradle` now supports optional hard-fail envs during bootstrap download:

- `BOTDROP_BOOTSTRAP_FAIL_ON_LEGACY_PATHS=1`
- `BOTDROP_BOOTSTRAP_REQUIRE_ADB=1`

Default behavior is warning-only to avoid blocking local development.

GitHub workflow dispatch now supports passing these values directly:

- `bootstrap_base_url`
- `bootstrap_require_adb`
- `bootstrap_fail_on_legacy_paths`

So canary release verification can run without editing repository files.

There is also a dedicated strict workflow:

- `.github/workflows/bootstrap-canary-verify.yml`
- always enforces:
  - `BOTDROP_BOOTSTRAP_REQUIRE_ADB=1`
  - `BOTDROP_BOOTSTRAP_FAIL_ON_LEGACY_PATHS=1`

## Rollout suggestion

1. Build and publish one canary bootstrap.
2. Install canary app build on test device.
3. Verify:
   - `pkg update`
   - `adb version`
   - botdrop-ui `op:"adb"` flow (`connect/devices/openApp`)
4. Promote release tag to stable once validated.
