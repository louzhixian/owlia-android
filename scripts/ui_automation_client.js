#!/usr/bin/env node
/**
 * Minimal local socket client for BotDrop UI automation.
 *
 * Usage:
 *   node scripts/ui_automation_client.js '{"op":"ping"}'
 *   node scripts/ui_automation_client.js '{"op":"tree","maxNodes":300}'
 *   node scripts/ui_automation_client.js '{"op":"global","action":"back"}'
 */
const net = require("net");

const prefix = process.env.PREFIX;
if (!prefix) {
  console.error("PREFIX is not set (expected in Termux env).");
  process.exit(1);
}

const sock = `${prefix}/var/run/botdrop-ui.sock`;
const raw = process.argv[2];
if (!raw) {
  console.error("Missing request JSON argument.");
  process.exit(1);
}

let req;
try {
  req = JSON.parse(raw);
} catch (e) {
  console.error("Invalid JSON:", e.message);
  process.exit(1);
}

const s = net.createConnection({ path: sock }, () => {
  s.end(JSON.stringify(req));
});

let buf = "";
s.on("data", (d) => (buf += d.toString("utf8")));
s.on("end", () => {
  try {
    const obj = JSON.parse(buf);
    process.stdout.write(JSON.stringify(obj, null, 2) + "\n");
  } catch {
    process.stdout.write(buf + "\n");
  }
});
s.on("error", (e) => {
  console.error("Socket error:", e.message);
  process.exit(1);
});

