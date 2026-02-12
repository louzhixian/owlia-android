package app.botdrop.ops;

import app.botdrop.BotDropService;

/**
 * Ensures pi CLI is available in Termux PATH for Ops chat.
 */
public class PiAgentInstaller {

    public interface Callback {
        void onComplete(boolean success, String message);
    }

    private final BotDropService service;

    public PiAgentInstaller(BotDropService service) {
        this.service = service;
    }

    public void ensureInstalled(Callback callback) {
        service.executeCommand(
            "if command -v pi >/dev/null 2>&1 && pi --version >/dev/null 2>&1; then echo installed; else echo missing; fi",
            check -> {
            String state = check.stdout == null ? "" : check.stdout.trim();
            if (check.success && "installed".equals(state)) {
                if (callback != null) callback.onComplete(true, "pi ready");
                return;
            }
            install(callback);
        });
    }

    private void install(Callback callback) {
        String cmd =
            "if ! command -v npm >/dev/null 2>&1; then echo 'npm missing'; exit 1; fi\n" +
            "npm config set fund false >/dev/null 2>&1 || true\n" +
            "npm config set update-notifier false >/dev/null 2>&1 || true\n" +
            "npm i -g @mariozechner/pi-coding-agent\n" +
            "PI_BIN=\"$(command -v pi 2>/dev/null || true)\"\n" +
            "PI_NODE=\"$(command -v node 2>/dev/null || true)\"\n" +
            "if [ -n \"$PI_BIN\" ] && [ -n \"$PI_NODE\" ]; then\n" +
            "  FIRST_LINE=\"$(head -n 1 \"$PI_BIN\" 2>/dev/null || true)\"\n" +
            "  if [ \"$FIRST_LINE\" = '#!/usr/bin/env node' ]; then\n" +
            "    TMP_PI=\"$PREFIX/tmp/pi-fixed-$$.js\"\n" +
            "    {\n" +
            "      echo '#!'\"$PI_NODE\"\n" +
            "      tail -n +2 \"$PI_BIN\"\n" +
            "    } > \"$TMP_PI\" && cat \"$TMP_PI\" > \"$PI_BIN\" && chmod 700 \"$PI_BIN\" && rm -f \"$TMP_PI\"\n" +
            "  fi\n" +
            "fi\n" +
            "$PI_NODE \"$PI_BIN\" --version >/dev/null 2>&1\n";

        service.executeCommand(cmd, result -> {
            if (callback == null) return;
            if (result.success) {
                callback.onComplete(true, "pi installed");
            } else {
                String err = result.stderr == null || result.stderr.trim().isEmpty()
                    ? result.stdout
                    : result.stderr;
                callback.onComplete(false, err == null ? "install failed" : err.trim());
            }
        });
    }
}
