# Telegram Fetch Failed - IPv6 autoSelectFamily Issue

**Date:** 2026-02-09
**Status:** ✅ Resolved
**Severity:** Critical - Telegram bot completely non-functional

## Problem Summary

Telegram bot fails to send/receive messages with error:
```
TypeError: fetch failed
    at node:internal/deps/undici/undici:16416:13
```

All Telegram API calls fail including:
- `sendChatAction`
- `sendMessage`
- `getMe`

## Symptoms

- Gateway starts successfully and shows no errors during startup
- Telegram provider initializes: `[telegram] [default] starting provider (@Lay2CMOBot)`
- Every Telegram API call fails with "fetch failed" error
- No detailed error information in logs
- Same code/APK that previously worked suddenly fails

## Root Cause

OpenClaw defaults `autoSelectFamily=false` for Node.js 22+ as a workaround for older Node.js issues. This setting disables the Happy Eyeballs algorithm (RFC 8305), which normally tries both IPv4 and IPv6 addresses when connecting.

**The Problem Chain:**
1. DNS resolves `api.telegram.org` and returns IPv6 address first: `2001:67c:4e8:f004::9`
2. With `autoSelectFamily=false`, undici/Node.js only tries the first IP address
3. IPv6 connectivity doesn't work in proot/Android environment
4. Connection to IPv6 address fails with `ECONNREFUSED`
5. No fallback to IPv4, so all Telegram API calls fail

## Investigation Process

### Step 1: Verify Basic Connectivity

Tested Node.js built-in fetch in termux-chroot:
```bash
node -e 'fetch("https://api.telegram.org").then(r => console.log("OK:", r.status))'
# Result: OK: 200 ✅
```

**Finding:** Node.js fetch works fine standalone.

### Step 2: Test Undici in OpenClaw Environment

Created test script in `/data/data/app.botdrop/files/usr/lib/node_modules/openclaw/`:
```javascript
import undici from "undici";
const token = "<bot-token>";
undici.fetch(`https://api.telegram.org/bot${token}/getMe`)
  .then(async r => console.log("Success:", await r.json()))
  .catch(e => console.error("Error:", e.message, e.cause));
```

**Result:** Undici fetch works! ✅

**Finding:** Both Node.js fetch and undici work when called directly.

### Step 3: Test with autoSelectFamily=false

Modified test to match OpenClaw's configuration:
```javascript
import undici from "undici";
const token = "<bot-token>";
undici.fetch(`https://api.telegram.org/bot${token}/getMe`, {
  dispatcher: new undici.Agent({ autoSelectFamily: false })
}).catch(e => {
  console.error("Error:", e.message);
  console.error("Cause:", e.cause);
});
```

**Result:**
```
Error: fetch failed
Cause: Error: connect ECONNREFUSED 2001:67c:4e8:f004::9:443
```

**Finding:** IPv6 address fails! The problem is `autoSelectFamily=false`.

### Step 4: Verify Gateway Configuration

Checked gateway logs:
```
2026-02-09T07:02:47.852Z [telegram] autoSelectFamily=false (default-node22)
```

**Confirmed:** OpenClaw is using `autoSelectFamily=false` by default for Node.js 22+.

## Solution

Override the default by adding network configuration to OpenClaw config file.

### Fix Configuration

Edit `~/.openclaw/openclaw.json` and add the `network` section under `channels.telegram`:

```json
{
  "channels": {
    "telegram": {
      "enabled": true,
      "network": {
        "autoSelectFamily": true
      },
      "botToken": "...",
      "dmPolicy": "allowlist",
      "groupPolicy": "allowlist",
      "streamMode": "partial",
      "allowFrom": ["..."]
    }
  }
}
```

**Note:** The `network` key must be in `channels.telegram`, NOT in `plugins.entries.telegram`.

### Restart Gateway

```bash
# Kill existing gateway
pkill -f "openclaw gateway"

# Start gateway
termux-chroot openclaw gateway run &

# Verify in logs
tail -f ~/.openclaw/gateway.log | grep autoSelectFamily
# Should show: [telegram] autoSelectFamily=true (config)
```

## Verification

After applying the fix:

1. **Check gateway logs:**
   ```
   2026-02-09T07:10:58.933Z [telegram] autoSelectFamily=true (config)
   ```
   ✅ Config applied successfully

2. **No more fetch errors:**
   - Old logs: Continuous "fetch failed" errors every ~30 seconds
   - New logs: No fetch errors after 10+ minutes
   ✅ Issue resolved

3. **Test bot functionality:**
   - Send message to bot via Telegram
   - Bot responds normally
   ✅ Full functionality restored

## Technical Details

### Why This Issue Occurs

**Node.js 18+** made `autoSelectFamily=true` the default (Happy Eyeballs, RFC 8305).

**OpenClaw workaround:** Set `autoSelectFamily=false` for Node.js 22+ due to historical issues in some environments. However, this breaks IPv6-first DNS resolution in proot/Android environments where IPv6 doesn't work.

### Why It Worked Before, Then Stopped

Possible explanations:
1. DNS resolution order changed (started returning IPv6 first)
2. Network configuration changed
3. Telegram API infrastructure changed (added IPv6 addresses)

Since IPv6 support in Android/proot is unreliable, and `autoSelectFamily=false` prevents fallback to IPv4, any change that makes DNS return IPv6 first will break connectivity.

### The Right Default for BotDrop

**Recommendation:** For BotDrop Android app, we should default `autoSelectFamily=true` or remove the override entirely, allowing Node.js to use its default Happy Eyeballs behavior. This is more resilient to DNS changes and network configurations.

## Related Files

- `~/.openclaw/openclaw.json` - OpenClaw configuration
- `~/.openclaw/gateway.log` - Gateway logs
- `/data/data/app.botdrop/files/usr/lib/node_modules/openclaw/dist/extensionAPI.js` - OpenClaw code with autoSelectFamily default

## Prevention

For future BotDrop releases, consider:

1. **Document this configuration** in default setup/README
2. **Pre-configure** `autoSelectFamily=true` during installation
3. **Add diagnostic command** to check network connectivity and IPv4/IPv6 support
4. **Upstream fix:** Submit PR to OpenClaw to remove `autoSelectFamily=false` default or make it configurable

## References

- [RFC 8305 - Happy Eyeballs Version 2](https://www.rfc-editor.org/rfc/rfc8305.html)
- [Node.js net.Socket autoSelectFamily](https://nodejs.org/api/net.html#socketconnectoptions-connectlistener)
- [undici Agent options](https://undici.nodejs.org/#/docs/api/Agent)

---

**Lesson Learned:** When investigating network issues in containerized/proot environments, always test with both IPv4 and IPv6 explicitly, and check DNS resolution order. IPv6 support is inconsistent in Android environments.
