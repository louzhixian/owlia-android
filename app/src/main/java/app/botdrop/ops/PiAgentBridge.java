package app.botdrop.ops;

import org.json.JSONObject;
import org.json.JSONArray;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private final OpsCredentialResolver credentialResolver;

    private static class CommandResult {
        final boolean success;
        final String stdout;
        final String stderr;
        final int exitCode;

        CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    public PiAgentBridge(OpsCredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    public PiAgentResult ask(String systemPrompt, String userPrompt) {
        OpsLlmConfig cfg = credentialResolver.resolvePrimaryConfig();
        if (cfg == null || !cfg.isValid()) {
            return new PiAgentResult(false, null, "No usable provider key/model found in auth profiles");
        }

        CommandResult result = runPiCommand(cfg, systemPrompt, userPrompt, 20);
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

    private CommandResult runPiCommand(OpsLlmConfig cfg, String systemPrompt, String userPrompt, int timeoutSeconds) {
        String stdout = "";
        int exitCode = -1;
        Process process = null;
        File tmpOutput = null;

        try {
            String provider = mapProviderName(cfg.provider);
            if (provider == null) {
                return new CommandResult(false, "", "Unsupported provider for assistant runtime.", -1);
            }

            String piNodePath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/node";
            String piBinPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/pi";
            if (!new File(piNodePath).exists() || !new File(piBinPath).exists()) {
                return new CommandResult(false, "", "pi runtime is not installed in bootstrap.", -1);
            }

            File tmpDir = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (!tmpDir.exists()) tmpDir.mkdirs();
            tmpOutput = new File(tmpDir, "pi_" + System.currentTimeMillis() + ".out");

            List<String> argv = new ArrayList<>();
            argv.add(piNodePath);
            argv.add(piBinPath);
            argv.add("-p");
            argv.add("--mode");
            argv.add("json");
            argv.add("--no-session");
            argv.add("--no-tools");
            argv.add("--thinking");
            argv.add("off");
            argv.add("--provider");
            argv.add(provider);
            argv.add("--model");
            argv.add(cfg.model);
            argv.add("--api-key");
            argv.add(cfg.apiKey);
            argv.add("--system-prompt");
            argv.add(systemPrompt == null ? "" : systemPrompt);
            argv.add(userPrompt == null ? "" : userPrompt);

            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
            pb.environment().put("NODE_OPTIONS", "--dns-result-order=ipv4first");
            pb.environment().put("PI_CODING_AGENT_DIR", TermuxConstants.TERMUX_HOME_DIR_PATH + "/.botdrop/pi-agent");
            pb.redirectErrorStream(true);
            pb.redirectOutput(tmpOutput);

            process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String partial = readOutput(tmpOutput);
                if (partial.length() > 800) {
                    partial = partial.substring(0, 800);
                }
                return new CommandResult(
                    false,
                    partial,
                    "pi command timed out after " + timeoutSeconds + " seconds",
                    -1
                );
            }

            exitCode = process.exitValue();
            stdout = readOutput(tmpOutput);
            return new CommandResult(exitCode == 0, stdout, "", exitCode);
        } catch (Exception e) {
            return new CommandResult(
                false,
                stdout,
                "Exception: " + e.getMessage(),
                exitCode
            );
        } finally {
            if (tmpOutput != null) tmpOutput.delete();
            if (process != null) process.destroy();
        }
    }

    private String readOutput(File file) {
        if (file == null || !file.exists()) return "";
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
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

}
