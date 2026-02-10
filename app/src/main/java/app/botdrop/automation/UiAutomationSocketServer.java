package app.botdrop.automation;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;

import org.json.JSONObject;

/**
 * Simple request/response JSON protocol over Termux local socket.
 *
 * Protocol (MVP):
 * - Client connects, sends one JSON object, then closes output.
 * - Server replies with one JSON object, then closes output and socket.
 *
 * Request examples:
 * - {"op":"ping"}
 * - {"op":"tree","maxNodes":500}
 * - {"op":"global","action":"back"}
 */
public class UiAutomationSocketServer extends LocalSocketManagerClientBase {

    private static final String LOG_TAG = "UiAutomationSocketServer";
    private static final Object REQUEST_LOCK = new Object();
    private static final int MAX_HISTORY = 30;
    private static final Object HISTORY_LOCK = new Object();
    private static final String[] HISTORY = new String[MAX_HISTORY];
    private static int sHistoryPos = 0;

    @Override
    protected String getLogTag() {
        return LOG_TAG;
    }

    public static String[] getRecentHistory() {
        synchronized (HISTORY_LOCK) {
            String[] out = new String[MAX_HISTORY];
            // Return newest-first.
            for (int i = 0; i < MAX_HISTORY; i++) {
                int idx = (sHistoryPos - 1 - i);
                while (idx < 0) idx += MAX_HISTORY;
                out[i] = HISTORY[idx];
            }
            return out;
        }
    }

    @Override
    public void onClientAccepted(@NonNull LocalSocketManager localSocketManager,
                                 @NonNull LocalClientSocket clientSocket) {
        try {
            StringBuilder data = new StringBuilder();
            Error error = clientSocket.readDataOnInputStream(data, true);
            if (error != null) {
                sendJson(clientSocket, jsonErr("READ_FAILED", error.toString()));
                return;
            }

            String raw = data.toString().trim();
            if (raw.isEmpty()) {
                sendJson(clientSocket, jsonErr("EMPTY_REQUEST", "request body is empty"));
                return;
            }

            JSONObject req;
            try {
                req = new JSONObject(raw);
            } catch (Exception e) {
                sendJson(clientSocket, jsonErr("BAD_JSON", e.getMessage() != null ? e.getMessage() : "invalid json"));
                return;
            }

            JSONObject res;
            synchronized (REQUEST_LOCK) {
                res = handle(req);
            }
            sendJson(clientSocket, res);
            recordHistory(raw, res != null ? res.toString() : "null");
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unhandled exception in client handler", t);
            sendJson(clientSocket, jsonErr("EXCEPTION", t.getMessage() != null ? t.getMessage() : "unknown"));
            recordHistory("(exception)", t.getMessage() != null ? t.getMessage() : "unknown");
        } finally {
            clientSocket.closeClientSocket(true);
        }
    }

    private JSONObject handle(JSONObject req) {
        String op = req.optString("op", "");
        switch (op) {
            case "ping":
                return putSafe(jsonOk(), "message", "pong");
            case "tree": {
                BotDropAccessibilityService svc = BotDropAccessibilityService.getInstance();
                if (svc == null) {
                    return jsonErr("SERVICE_DISABLED", "accessibility service not connected");
                }
                int maxNodes = req.optInt("maxNodes", 500);
                return svc.dumpActiveWindowTree(maxNodes);
            }
            case "global": {
                BotDropAccessibilityService svc = BotDropAccessibilityService.getInstance();
                if (svc == null) {
                    return jsonErr("SERVICE_DISABLED", "accessibility service not connected");
                }
                String action = req.optString("action", "");
                boolean ok = svc.performBotDropGlobalAction(action);
                if (!ok) return jsonErr("BAD_ACTION", "unsupported global action: " + action);
                return jsonOk();
            }
            case "find": {
                BotDropAccessibilityService svc = BotDropAccessibilityService.getInstance();
                if (svc == null) return jsonErr("SERVICE_DISABLED", "accessibility service not connected");
                JSONObject selector = req.optJSONObject("selector");
                String mode = req.optString("mode", "first");
                int timeoutMs = req.optInt("timeoutMs", 0);
                int maxNodes = req.optInt("maxNodes", 1500);

                JSONObject res = svc.find(selector, mode, maxNodes);
                if (timeoutMs > 0 && res.optBoolean("ok", false)) {
                    // If no matches and timeout requested, wait for existence.
                    if (res.optJSONArray("matches") != null && res.optJSONArray("matches").length() == 0) {
                        boolean exists = svc.waitForExists(selector, timeoutMs, maxNodes);
                        if (exists) res = svc.find(selector, mode, maxNodes);
                        else return jsonErr("TIMEOUT", "element not found before timeout");
                    }
                }
                return res;
            }
            case "action": {
                BotDropAccessibilityService svc = BotDropAccessibilityService.getInstance();
                if (svc == null) return jsonErr("SERVICE_DISABLED", "accessibility service not connected");
                JSONObject target = req.optJSONObject("target");
                if (target == null) return jsonErr("BAD_REQUEST", "missing target");

                String action = req.optString("action", "");
                JSONObject args = req.optJSONObject("args");
                int timeoutMs = req.optInt("timeoutMs", 0);
                int maxNodes = req.optInt("maxNodes", 1500);

                // 1) nodeId direct
                String nodeId = target.optString("nodeId", null);
                if (nodeId != null && !nodeId.isEmpty()) {
                    return svc.actionByNodeId(nodeId, action, args, timeoutMs);
                }

                // 2) selector: find first match (optionally wait), then action on nodeId
                JSONObject selector = target.optJSONObject("selector");
                if (selector == null) return jsonErr("BAD_REQUEST", "missing target.selector or target.nodeId");

                if (timeoutMs > 0) {
                    boolean exists = svc.waitForExists(selector, timeoutMs, maxNodes);
                    if (!exists) return jsonErr("TIMEOUT", "element not found before timeout");
                }

                JSONObject findRes = svc.find(selector, "first", maxNodes);
                if (!findRes.optBoolean("ok", false)) return findRes;
                String foundId = null;
                if (findRes.optJSONArray("matches") != null && findRes.optJSONArray("matches").length() > 0) {
                    JSONObject m0 = findRes.optJSONArray("matches").optJSONObject(0);
                    if (m0 != null) {
                        foundId = m0.optString("actionNodeId", null);
                        if (foundId == null || foundId.isEmpty()) {
                            foundId = m0.optString("nodeId", null);
                        }
                    }
                }
                if (foundId == null || foundId.isEmpty()) return jsonErr("NOT_FOUND", "no matching node");
                return svc.actionByNodeId(foundId, action, args, timeoutMs);
            }
            case "wait": {
                BotDropAccessibilityService svc = BotDropAccessibilityService.getInstance();
                if (svc == null) return jsonErr("SERVICE_DISABLED", "accessibility service not connected");
                String event = req.optString("event", "");
                int timeoutMs = req.optInt("timeoutMs", 10000);
                int maxNodes = req.optInt("maxNodes", 1500);
                long sinceMs = req.has("sinceMs") ? req.optLong("sinceMs", 0) : System.currentTimeMillis();

                if ("windowChanged".equals(event)) {
                    boolean ok = svc.waitForWindowChanged(sinceMs, timeoutMs);
                    return ok ? jsonOk() : jsonErr("TIMEOUT", "windowChanged timeout");
                }
                if ("contentChanged".equals(event)) {
                    boolean ok = svc.waitForContentChanged(sinceMs, timeoutMs);
                    return ok ? jsonOk() : jsonErr("TIMEOUT", "contentChanged timeout");
                }
                if ("exists".equals(event)) {
                    JSONObject selector = req.optJSONObject("selector");
                    if (selector == null) return jsonErr("BAD_REQUEST", "missing selector for exists");
                    boolean ok = svc.waitForExists(selector, timeoutMs, maxNodes);
                    return ok ? jsonOk() : jsonErr("TIMEOUT", "exists timeout");
                }
                return jsonErr("BAD_EVENT", "unknown wait event: " + event);
            }
            default:
                return jsonErr("BAD_OP", "unknown op: " + op);
        }
    }

    private static JSONObject jsonOk() {
        JSONObject o = new JSONObject();
        return putSafe(o, "ok", true);
    }

    private static JSONObject jsonErr(String code, String message) {
        JSONObject o = new JSONObject();
        putSafe(o, "ok", false);
        putSafe(o, "error", code);
        putSafe(o, "message", message != null ? message : "");
        return o;
    }

    private static void sendJson(LocalClientSocket clientSocket, JSONObject obj) {
        try {
            clientSocket.sendDataToOutputStream(obj.toString(), true);
        } catch (Exception ignored) {
            // sendDataToOutputStream reports errors via Error return, but this is a best-effort path.
        }
    }

    private static JSONObject putSafe(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {}
        return o;
    }

    private static void recordHistory(String req, String res) {
        String r1 = (req != null) ? req : "";
        String r2 = (res != null) ? res : "";
        r1 = truncate(r1, 4096);
        r2 = truncate(r2, 4096);
        String entry = System.currentTimeMillis() + "\nREQ: " + r1 + "\nRES: " + r2;
        synchronized (HISTORY_LOCK) {
            HISTORY[sHistoryPos] = entry;
            sHistoryPos = (sHistoryPos + 1) % MAX_HISTORY;
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated)";
    }
}
