package app.botdrop.automation;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class AdbCommandRunner {

    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final String DEFAULT_SERIAL = "localhost:5555";

    private AdbCommandRunner() {}

    static JSONObject handle(JSONObject req) {
        String action = req.optString("action", "").trim();
        if (action.isEmpty()) return err("BAD_REQUEST", "missing adb action");

        String serial = req.optString("serial", DEFAULT_SERIAL).trim();
        int timeoutMs = req.optInt("timeoutMs", DEFAULT_TIMEOUT_MS);

        switch (action) {
            case "connect":
                return run(serial, timeoutMs, "connect", req.optString("host", DEFAULT_SERIAL).trim());
            case "devices":
                return run(serial, timeoutMs, "devices");
            case "openApp":
                return openApp(req, serial, timeoutMs);
            case "shell": {
                String command = req.optString("command", "").trim();
                if (command.isEmpty()) return err("BAD_REQUEST", "missing shell command");
                return run(serial, timeoutMs, "shell", "sh", "-c", command);
            }
            case "tap":
                return run(serial, timeoutMs, "shell", "input", "tap",
                    String.valueOf(req.optInt("x", -1)), String.valueOf(req.optInt("y", -1)));
            case "swipe":
                return run(serial, timeoutMs, "shell", "input", "swipe",
                    String.valueOf(req.optInt("x1", -1)),
                    String.valueOf(req.optInt("y1", -1)),
                    String.valueOf(req.optInt("x2", -1)),
                    String.valueOf(req.optInt("y2", -1)),
                    String.valueOf(req.optInt("durationMs", 200)));
            case "text": {
                String text = req.optString("text", "");
                if (text.isEmpty()) return err("BAD_REQUEST", "missing text");
                String escaped = text.replace(" ", "%s");
                return run(serial, timeoutMs, "shell", "input", "text", escaped);
            }
            case "keyevent": {
                String key = req.optString("key", "").trim();
                if (key.isEmpty()) return err("BAD_REQUEST", "missing key");
                return run(serial, timeoutMs, "shell", "input", "keyevent", key);
            }
            default:
                return err("BAD_ACTION", "unknown adb action: " + action);
        }
    }

    private static JSONObject openApp(JSONObject req, String serial, int timeoutMs) {
        String packageName = req.optString("packageName", "").trim();
        if (packageName.isEmpty()) return err("BAD_REQUEST", "missing packageName");

        String component = req.optString("component", "").trim();
        String activity = req.optString("activity", "").trim();

        if (!component.isEmpty()) {
            return run(serial, timeoutMs, "shell", "am", "start", "-n", component);
        }

        if (!activity.isEmpty()) {
            String cls = activity.startsWith(".") ? (packageName + activity) : activity;
            return run(serial, timeoutMs, "shell", "am", "start", "-n", packageName + "/" + cls);
        }

        return run(serial, timeoutMs, "shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1");
    }

    private static JSONObject run(String serial, int timeoutMs, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveAdbPath());
        String serialArg = (serial == null || serial.isEmpty()) ? DEFAULT_SERIAL : serial;
        // adb connect/devices do not require -s.
        if (args.length > 0 && !"connect".equals(args[0]) && !"devices".equals(args[0])) {
            cmd.add("-s");
            cmd.add(serialArg);
        }
        for (String a : args) cmd.add(a);
        return exec(cmd, Math.max(1000, timeoutMs));
    }

    private static JSONObject exec(List<String> cmd, int timeoutMs) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();

            boolean exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                JSONObject out = err("TIMEOUT", "adb command timeout");
                put(out, "command", String.join(" ", cmd));
                return out;
            }

            String output = readAll(process.getInputStream());
            int exit = process.exitValue();
            JSONObject out = new JSONObject();
            put(out, "ok", exit == 0);
            put(out, "exitCode", exit);
            put(out, "output", output);
            put(out, "command", String.join(" ", cmd));
            if (exit != 0) {
                put(out, "error", "ADB_FAILED");
            }
            return out;
        } catch (Exception e) {
            JSONObject out = err("EXCEPTION", e.getMessage() != null ? e.getMessage() : "unknown");
            put(out, "command", String.join(" ", cmd));
            return out;
        } finally {
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (Exception ignored) {}
                try {
                    process.getOutputStream().close();
                } catch (Exception ignored) {}
                try {
                    process.getErrorStream().close();
                } catch (Exception ignored) {}
            }
        }
    }

    private static String resolveAdbPath() {
        String local = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/adb";
        File f = new File(local);
        return f.exists() ? local : "adb";
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static JSONObject err(String code, String message) {
        JSONObject o = new JSONObject();
        put(o, "ok", false);
        put(o, "error", code);
        put(o, "message", message == null ? "" : message);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {}
    }
}
