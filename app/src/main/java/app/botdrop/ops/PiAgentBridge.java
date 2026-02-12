package app.botdrop.ops;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.botdrop.BotDropService;

/**
 * Shell bridge to pi-agent CLI (JSON mode).
 */
public class PiAgentBridge {

    public static class PiAgentResult {
        public final boolean success;
        public final String text;
        public final String error;

        public PiAgentResult(boolean success, String text, String error) {
            this.success = success;
            this.text = text;
            this.error = error;
        }
    }

    private final BotDropService botDropService;
    private final OpsCredentialResolver credentialResolver;

    public PiAgentBridge(BotDropService botDropService, OpsCredentialResolver credentialResolver) {
        this.botDropService = botDropService;
        this.credentialResolver = credentialResolver;
    }

    public PiAgentResult ask(String systemPrompt, String userPrompt) {
        OpsLlmConfig cfg = credentialResolver.resolvePrimaryConfig();
        if (cfg == null || !cfg.isValid()) {
            return new PiAgentResult(false, null, "No usable provider key/model found in auth profiles");
        }

        String cmd = buildCommand(cfg, systemPrompt, userPrompt);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BotDropService.CommandResult> ref = new AtomicReference<>();

        botDropService.executeCommand(cmd, result -> {
            ref.set(result);
            latch.countDown();
        });

        try {
            latch.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        BotDropService.CommandResult result = ref.get();
        if (result == null) {
            return new PiAgentResult(false, null, "No response from pi-agent");
        }
        if (!result.success) {
            return new PiAgentResult(false, null, result.stderr == null ? result.stdout : result.stderr);
        }

        String parsed = extractAssistantMessage(result.stdout);
        if (parsed == null || parsed.trim().isEmpty()) {
            return new PiAgentResult(false, null, "pi-agent returned no assistant message");
        }
        return new PiAgentResult(true, parsed, null);
    }

    private String buildCommand(OpsLlmConfig cfg, String systemPrompt, String userPrompt) {
        String model = cfg.provider + "/" + cfg.model;
        String escapedSystem = shellEscape(systemPrompt);
        String escapedUser = shellEscape(userPrompt);
        String escapedModel = shellEscape(model);
        String escapedKey = shellEscape(cfg.apiKey);

        return ""
            + "if ! command -v pi-agent >/dev/null 2>&1; then\n"
            + "  echo '{\"type\":\"assistant_message\",\"text\":\"pi-agent is not installed in bootstrap. Please install @mariozechner/pi-agent first.\"}'\n"
            + "  exit 0\n"
            + "fi\n"
            + "pi-agent --json --api-key '" + escapedKey + "' --model '" + escapedModel + "' "
            + "--system-prompt '" + escapedSystem + "' '" + escapedUser + "'\n";
    }

    private String extractAssistantMessage(String stdout) {
        if (stdout == null || stdout.trim().isEmpty()) return null;
        String[] lines = stdout.split("\n");
        StringBuilder text = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                JSONObject json = new JSONObject(trimmed);
                String type = json.optString("type", "");
                if ("assistant_message".equals(type)) {
                    String t = json.optString("text", "");
                    if (!t.trim().isEmpty()) {
                        if (text.length() > 0) text.append("\n");
                        text.append(t.trim());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return text.toString();
    }

    private String shellEscape(String input) {
        if (input == null) return "";
        return input.replace("'", "'\"'\"'");
    }
}
