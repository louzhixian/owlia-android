package app.botdrop.ops;

import org.json.JSONObject;

/**
 * LLM-first assistant with strict tool allowlist.
 */
public class OpsAssistantEngine {

    public static class AssistantReply {
        public final String text;
        public final String tool;

        public AssistantReply(String text, String tool) {
            this.text = text;
            this.tool = tool;
        }
    }

    private final OpsLlmClient llmClient;
    private final OpsCredentialResolver credentialResolver;

    public OpsAssistantEngine(OpsLlmClient llmClient, OpsCredentialResolver credentialResolver) {
        this.llmClient = llmClient;
        this.credentialResolver = credentialResolver;
    }

    public AssistantReply reply(String userMessage, DoctorReport report) {
        OpsLlmConfig cfg = credentialResolver.resolvePrimaryConfig();
        if (cfg == null || !cfg.isValid()) {
            return new AssistantReply(
                "LLM is not available. Configure a supported provider key (Anthropic/OpenAI) first.",
                "none"
            );
        }

        String system = buildSystemPrompt();
        String user = buildUserPrompt(userMessage, report);
        OpsLlmClient.LlmResponse response = llmClient.ask(cfg, system, user);
        if (!response.success || response.text == null || response.text.trim().isEmpty()) {
            return new AssistantReply("LLM request failed: " + response.error, "none");
        }

        AssistantReply parsed = parseJsonReply(response.text);
        if (parsed != null) return parsed;
        return new AssistantReply(response.text.trim(), "none");
    }

    private String buildSystemPrompt() {
        return "You are BotDrop Ops Assistant. "
            + "You may suggest only these tools: doctor.run, fix.preview, fix.apply, gateway.restart, none. "
            + "Return strict JSON: {\"reply\":\"...\",\"tool\":\"...\"}. "
            + "Do not output markdown.";
    }

    private String buildUserPrompt(String userMessage, DoctorReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("User message: ").append(userMessage).append("\n");
        sb.append("Current issues:\n");
        if (report == null || report.issues.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (DoctorIssue issue : report.issues) {
                sb.append("- ").append(issue.code)
                    .append(" [").append(issue.severity).append("]")
                    .append(" domain=").append(issue.ruleDomain)
                    .append(" source=").append(issue.ruleSource.sourceType)
                    .append("\n");
            }
        }
        return sb.toString();
    }

    private AssistantReply parseJsonReply(String text) {
        try {
            String trimmed = text.trim();
            int first = trimmed.indexOf('{');
            int last = trimmed.lastIndexOf('}');
            if (first < 0 || last <= first) return null;

            JSONObject json = new JSONObject(trimmed.substring(first, last + 1));
            String reply = json.optString("reply", "").trim();
            String tool = json.optString("tool", "none").trim();
            if (reply.isEmpty()) return null;
            if (!isAllowedTool(tool)) tool = "none";
            return new AssistantReply(reply, tool);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isAllowedTool(String tool) {
        return "doctor.run".equals(tool)
            || "fix.preview".equals(tool)
            || "fix.apply".equals(tool)
            || "gateway.restart".equals(tool)
            || "none".equals(tool);
    }
}
