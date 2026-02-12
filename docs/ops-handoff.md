# Ops Milestone Handoff (2026-02-12)

## Branch

- Current branch: `feat/accessibility-adb-hybrid`

## Ops milestone commits

1. `cbf57dcb` feat(ops): scaffold isolated doctor and safe fix engine
2. `7b8c2681` feat(ops): add isolated ops assistant activity and dashboard entry
3. `fec40813` feat(ops): add fix preview and confirmation flow
4. `5a838119` refactor(ops): split agent and botdrop rule domains
5. `8082193e` feat(ops): add version-aware rule source resolver
6. `ba540afc` feat(ops): sync official docs metadata for rule source
7. `590d83de` feat(ops): add llm-backed copilot chat with safe tools
8. `1d5e898e` feat(ops): add floating chat bubble and transcript persistence

## Code boundaries (do not mix)

- Ops subsystem:
  - `app/src/main/java/app/botdrop/ops/`
  - `app/src/test/java/app/botdrop/ops/`
  - `app/src/main/res/layout/activity_botdrop_ops*.xml`
  - `docs/ops-copilot-plan.md`
- Automation subsystem:
  - `app/src/main/java/app/botdrop/automation/`
  - `app/src/main/assets/botdrop/skills/botdrop-ui/`

## Integration points already used

- `app/src/main/java/app/botdrop/DashboardActivity.java` opens `OpsActivity`.
- `app/src/main/AndroidManifest.xml` registers `OpsActivity`, `OpsChatActivity`, `OpsBubbleService`.
- Ops uses existing `BotDropService`/`BotDropConfig` through adapters only.

## Current dirty working tree (non-ops)

At handoff time these local changes exist and should be treated as user-owned automation work:

- `app/src/main/assets/botdrop/skills/botdrop-ui/SKILL.md`
- `app/src/main/assets/botdrop/skills/botdrop-ui/apps-aliases.json`
- `app/src/main/java/app/botdrop/automation/OpenAppOperation.java`
- `app/src/main/java/app/botdrop/automation/UiAutomationSkillInstaller.java`
- `app/src/main/java/app/botdrop/automation/UiAutomationSocketServer.java`
- `app/src/main/java/app/botdrop/automation/DiagnoseAppOperation.java` (untracked)
- `RELEASE_NOTES_v0.2.2.md` (untracked)
- `RELEASE_NOTES_v0.2.3.md` (untracked)
- `docker-compose.yml` (untracked)

Do not reset/revert these unless explicitly requested by user.

## Quick verification command

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :app:testDebugUnitTest --no-daemon
```
