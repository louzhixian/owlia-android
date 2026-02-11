# BotDrop Minimal Self-Hosted Package Plan

Date: 2026-02-11

## Goal

Make `pkg/apt` and `adb` work reliably under `app.botdrop` without maintaining a full Termux mirror.

## Scope (Minimal)

Build and publish only:

- `termux-tools`
- `apt`
- `dpkg`
- `android-tools` (provides `adb`)
- runtime dependencies pulled by the above

## Why this is enough

- `pkg` is a script in `termux-tools`; if it is built with the correct prefix, package management works.
- `apt` + `dpkg` are the package manager core.
- `android-tools` is required for wireless ADB fallback in automation.
- Everything else can be added later on-demand.

## Current risk observed

Existing bootstrap may contain mixed prefixes:

- shebang/path uses `app.botdrop`
- scripts still hardcode `/data/data/com.termux/...`

This breaks `pkg` behavior on some devices.

## Immediate app-side mitigation (done)

`TermuxInstaller` now patches known bootstrap scripts from:

- `/data/data/com.termux/...`

to:

- `/data/data/app.botdrop/...`

both:

- during bootstrap install (staging directory)
- on `BotDropService` startup (for already-installed users)

## Build/Release flow

1. Build selected packages in `botdrop-packages` for `aarch64` with `TERMUX_PACKAGE_NAME=app.botdrop`.
2. Publish `.deb` files and APT index (`Packages`, `Release`) to a static endpoint.
3. Generate new `bootstrap-aarch64.zip` pointing `sources.list` to that endpoint.
4. Publish bootstrap release.
5. Build BotDrop app (downloads latest bootstrap).

## Acceptance checks

On device:

1. `pkg update` succeeds.
2. `pkg install android-tools` succeeds (or `adb` already present in bootstrap).
3. `$PREFIX/bin/adb version` succeeds.
4. BotDrop skill `op:"adb"` can `connect`, `devices`, `openApp`.

## Cost estimate

- Initial minimal setup: 1-3 days.
- Ongoing maintenance: add packages on demand.
- Full mirror replacement: out of scope for this phase.
