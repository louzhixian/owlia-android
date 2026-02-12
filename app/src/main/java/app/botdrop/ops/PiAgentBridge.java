package app.botdrop.ops;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.botdrop.BotDropService;

/**
 * Shell bridge to pi CLI (JSON mode).
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
            return new PiAgentResult(false, null, "No response from pi");
        }
        if (!result.success) {
            String stderr = result.stderr == null ? "" : result.stderr.trim();
            String stdout = result.stdout == null ? "" : result.stdout.trim();
            String err = !stderr.isEmpty()
                ? stderr
                : (!stdout.isEmpty() ? stdout : ("pi command failed with exit code " + result.exitCode));
            return new PiAgentResult(false, null, err);
        }

        String parsed = extractAssistantMessage(result.stdout);
        if (parsed == null || parsed.trim().isEmpty()) {
            return new PiAgentResult(false, null, "pi returned no assistant message");
        }
        return new PiAgentResult(true, parsed, null);
    }

    private String buildCommand(OpsLlmConfig cfg, String systemPrompt, String userPrompt) {
        String provider = mapProviderName(cfg.provider);
        if (provider == null) {
            return "echo '{\"type\":\"assistant_message\",\"text\":\"Unsupported provider for assistant runtime.\"}'\n";
        }
        String escapedSystem = shellEscape(systemPrompt);
        String escapedUser = shellEscape(userPrompt);
        String escapedProvider = shellEscape(provider);
        String escapedModel = shellEscape(cfg.model);
        String escapedKey = shellEscape(cfg.apiKey);

        return ""
            + "PI_BIN=\"$(command -v pi 2>/dev/null || true)\"\n"
            + "PI_NODE=\"$(command -v node 2>/dev/null || true)\"\n"
            + "if [ -z \"$PI_BIN\" ] || [ -z \"$PI_NODE\" ]; then\n"
            + "  echo '{\"type\":\"assistant_message\",\"text\":\"pi runtime is not installed in bootstrap. Please install @mariozechner/pi-coding-agent first.\"}'\n"
            + "  exit 0\n"
            + "fi\n"
            + "PI_CODING_AGENT_DIR=\"$HOME/.botdrop/pi-agent\" "
            + "\"$PI_NODE\" \"$PI_BIN\" -p --mode json --no-session --no-tools "
            + "--provider '" + escapedProvider + "' --model '" + escapedModel + "' --api-key '" + escapedKey + "' "
            + "--system-prompt '" + escapedSystem + "' '" + escapedUser + "'\n";
    }

    private String mapProviderName(String openClawProvider) {
        if (openClawProvider == null) return null;
        String provider = openClawProvider.trim();
        switch (provider) {
            case "anthropic":
            case "openai":
            case "google":
            case "openrouter":
            case "minimax":
            case "kimi-coding":
                return provider;
            case "kimi":
                return "kimi-coding";
            default:
                // Allow custom providers if pi runtime recognizes them.
                return provider.isEmpty() ? null : provider;
        }
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
                    continue;
                }

                if ("message_end".equals(type)) {
                    JSONObject message = json.optJSONObject("message");
                    if (message == null) continue;
                    if (!"assistant".equals(message.optString("role", ""))) continue;

                    JSONArray content = message.optJSONArray("content");
                    if (content == null) continue;
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject item = content.optJSONObject(i);
                        if (item == null) continue;
                        if (!"text".equals(item.optString("type", ""))) continue;
                        String t = item.optString("text", "");
                        if (!t.trim().isEmpty()) {
                            if (text.length() > 0) text.append("\n");
                            text.append(t.trim());
                        }
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
