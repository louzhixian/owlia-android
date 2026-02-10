# UI Automation Roadmap

This is a backlog for the BotDrop Accessibility + local socket UI automation bridge.

## Next (Small, High-ROI)

- Add explicit gesture actions (not just fallback):
  - `tap(x,y)`
  - `swipe(x1,y1,x2,y2,durationMs)`
- Add `preferLeaf` for click:
  - Current default prefers a clickable ancestor via `actionNodeId`.
  - Some UIs require clicking the leaf element (e.g., tiny icon button inside a clickable row).
- Improve scroll control:
  - `direction`: `up|down|left|right`
  - `scrollPage`: e.g. `0.8` (percentage of bounds)
- Expand waits:
  - `gone`: wait for selector to disappear
  - `textEquals` / `textContains`: wait for text changes on a selector target
- Selector improvements (string contains):
  - `resourceIdContains`
  - `classNameContains`
  - `packageNameContains`
- Result shaping:
  - `depth`, `childCount`, `clickableAncestorCount` in `find` matches (helps agent pick strategy)
- Focus helpers:
  - `getFocusedNode`
  - `focus(selector|nodeId)`

## Next (Medium, High-ROI)

- `scrollUntilExists(selector, maxScrolls, timeoutPerScrollMs)`
- `ensureVisible(selector)`:
  - If match exists but is off-screen, scroll to bring it into view, then return actionable node id.
- `setText` improvements:
  - optional `clickBefore=true`
  - optional clear: `selectAll+delete` or other best-effort clear path before setting text
- Diagnostics improvements:
  - show current foreground package/activity
  - one-tap copy suggested selector (resourceId/contentDesc/textContains)
- Protocol versioning:
  - `capabilities` op: agent can probe supported operations/fields at runtime

## Later / Risky / Optional

- ADB-based fallback (`adb shell input ...`): operationally brittle unless you mandate wireless debugging.
- uinput injection: device/SELinux dependent; not recommended as a default path.

