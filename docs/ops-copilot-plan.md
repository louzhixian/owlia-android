# Ops Copilot Plan (Isolated From Automation)

## Goal

Provide an in-app assistant that helps users configure and repair BotDrop/OpenClaw safely, without starting a second OpenClaw process.

## Non-Goals

- Do not couple with `app.botdrop.automation.*`.
- Do not allow arbitrary shell execution by LLM decisions.
- Do not depend on the main OpenClaw agent to be healthy.

## Isolation Boundaries

1. Package boundary:
- New subsystem lives under `app.botdrop.ops.*`.
- Automation remains under `app.botdrop.automation.*`.

2. Runtime boundary:
- Ops uses existing local app services (`BotDropService`, `BotDropConfig`) through small adapters.
- No shared mutable singletons with automation.

3. Git merge boundary:
- Mostly additive files.
- Minimal future hook points in UI classes (`DashboardActivity`/new activity), no deep edits to automation classes.

## Core Components (Phase 1 scaffold)

- `DoctorEngine`: deterministic diagnosis rules.
- `RuntimeProbe`: process/http/log probe snapshot.
- `SafeExecutor`: backup + apply + validate + rollback flow for config fixes.
- `ConfigRepository`: config read/write boundary.
- `GatewayController`: restart boundary.
- `OpsOrchestrator`: single entrypoint for UI and later LLM tool-calling.

## Safety Model

1. LLM (later phase) proposes actions only.
2. App executes only whitelisted `FixAction`.
3. Every write creates backup under `~/.openclaw/backups`.
4. Config validation runs before persisting.
5. Rollback path is always available.

## Phase Plan

1. Phase 1 (current scaffold)
- Deterministic doctor checks and fix executor.
- Unit tests for rule engine and fix pipeline.

2. Phase 2
- Add simple Ops UI (`OpsActivity`) with:
  - Diagnose
  - Proposed fixes
  - Diff/preview
  - Apply + restart + recheck

3. Phase 3
- Add LLM copilot layer that calls only internal tools:
  - `doctor.run`
  - `fix.preview`
  - `fix.apply`
  - `gateway.restart`

4. Phase 4
- Add docs ingestion/cache (FAQ, troubleshooting) for explanation quality.

## Notes

- Source of truth for OpenClaw docs should be `docs.openclaw.ai` with GitHub docs as fallback.
- Keep model provider handling aligned with existing `auth-profiles.json` support in app.
