# UI Automation (Accessibility + Local Socket)

This doc describes the no-root UI automation bridge implemented by BotDrop.

## Architecture

- Android side:
  - `BotDropAccessibilityService`: reads the active window accessibility tree and performs node/global actions.
  - `AutomationControllerService` (foreground): exposes a local API over a Unix domain socket.
- Termux/OpenClaw side:
  - Call the `botdrop-ui` CLI (installed into `$PREFIX/bin`) to send JSON requests to the socket.

## Socket

- Path: `$PREFIX/var/run/botdrop-ui.sock`
- Protocol: one connection = one request JSON, one response JSON, then close.

## Operations

- Ping:
  - Request: `{"op":"ping"}`
  - Response: `{"ok":true,"message":"pong"}`

- Dump tree:
  - Request: `{"op":"tree","maxNodes":500}`
  - Response: `{"ok":true,"tree":{...},"nodeCount":123,"truncated":false}`
  - Each node contains a `nodeId` which is a child-index path like `"0/3/1"`.

- Find:
  - Request: `{"op":"find","selector":{...},"mode":"first"|"all","timeoutMs":3000,"maxNodes":1500}`
  - Response: `{"ok":true,"matches":[{nodeId,...},...]}`
  - If `timeoutMs>0` and no matches, the server waits for the element to exist.

- Action:
  - Request (selector target): `{"op":"action","target":{"selector":{...}},"action":"click","timeoutMs":3000}`
  - Request (nodeId target): `{"op":"action","target":{"nodeId":"0/3/1"},"action":"click"}`
  - Actions: `click`, `longClick`, `focus`, `scrollForward`, `scrollBackward`, `setText`
  - `setText` args: `{"args":{"text":"hello"}}`

- Wait:
  - Request: `{"op":"wait","event":"windowChanged|contentChanged","sinceMs":0,"timeoutMs":5000}`
  - Request: `{"op":"wait","event":"exists","selector":{...},"timeoutMs":5000,"maxNodes":1500}`

## CLI

Examples:

```bash
botdrop-ui '{"op":"ping"}'
botdrop-ui '{"op":"tree","maxNodes":300}'
botdrop-ui '{"op":"find","selector":{"textContains":"OK"},"mode":"first","timeoutMs":3000}'
botdrop-ui '{"op":"action","target":{"selector":{"textContains":"OK"}},"action":"click","timeoutMs":3000}'
```

## Selector Notes

- `ancestor`: constrain matches to nodes that are contained within an ancestor node.
  - Example: only match "OK" under a specific container:
  - `{"text":"OK","ancestor":{"resourceId":"com.example:id/container"}}`
- `scrollable`: match scroll containers:
  - `{"scrollable":true,"packageName":"com.example"}`
