# BotDrop UI Automation (Android)

This skill lets the agent control Android UI using BotDrop AccessibilityService and a local Unix socket API.

Prerequisites
- BotDrop app opened at least once (Automation Controller notification visible).
- Accessibility: enable "BotDrop Accessibility" in Android Settings.

How To Call
- Use the `botdrop-ui` CLI from the Termux environment.
- It takes exactly one argument: a JSON string.
- Do NOT use `adb pair` or `adb connect` for this skill.

Open App Strategy
- Read alias map first: `~/.openclaw/skills/botdrop-ui/apps-aliases.json`.
- Resolve app name aliases (English/Chinese) to package.
- Prefer atomic launch: `openApp`.
- If alias entry has `preferredComponent`, call `openApp` with `component` first.
- `preferredComponent` is best-effort; if it fails, retry `openApp` without `component`.
- For apps with strict launch behavior, pass explicit `activity` or `component`.
- If app blocks Accessibility tree (for example some WeChat pages), fallback to `adb` actions.
- `openApp` auto-handles confirmation dialogs like `Always` / `Just once` and common OEM buttons (`Allow` / `Confirm`).
- Verify target package after launch.

Examples
```bash
botdrop-ui '{"op":"ping"}'
botdrop-ui '{"op":"openApp","packageName":"com.twitter.android","timeoutMs":12000}'
botdrop-ui '{"op":"openApp","packageName":"com.tencent.mm","activity":".ui.LauncherUI","timeoutMs":12000}'
botdrop-ui '{"op":"openApp","packageName":"tv.danmaku.bili","component":"tv.danmaku.bili/.MainActivityV2","timeoutMs":12000}'
botdrop-ui '{"op":"global","action":"home"}'
botdrop-ui '{"op":"tree","maxNodes":400}'
botdrop-ui '{"op":"find","selector":{"textContains":"OK"},"mode":"first","timeoutMs":3000}'
botdrop-ui '{"op":"action","target":{"selector":{"textContains":"OK"}},"action":"click","timeoutMs":3000}'
botdrop-ui '{"op":"adb","action":"connect","host":"localhost:5555"}'
botdrop-ui '{"op":"adb","action":"openApp","packageName":"com.tencent.mm","component":"com.tencent.mm/.ui.LauncherUI"}'
botdrop-ui '{"op":"adb","action":"tap","x":540,"y":460}'
botdrop-ui '{"op":"adb","action":"swipe","x1":900,"y1":1800,"x2":900,"y2":500,"durationMs":300}'
botdrop-ui '{"op":"adb","action":"text","text":"hello world"}'
botdrop-ui '{"op":"adb","action":"keyevent","key":"KEYCODE_BACK"}'
```

Notes
- Do not infer app uninstalled from `pm list packages`; package visibility can hide results.
- Prefer `resourceId` selectors over text when possible.
- If `openApp` fails for Telegram/Discord, retry once and inspect `resolvedPackage` in response.
