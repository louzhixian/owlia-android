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
        service.executeCommand("command -v pi >/dev/null 2>&1 && echo installed || echo missing", check -> {
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
            "command -v pi >/dev/null 2>&1\n";

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
