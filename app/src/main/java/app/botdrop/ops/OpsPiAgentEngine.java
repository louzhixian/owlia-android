package app.botdrop.ops;

import org.json.JSONObject;

/**
 * Pi-agent-backed assistant planner.
 */
public class OpsPiAgentEngine {

    public static class AssistantReply {
        public final String text;
        public final String tool;

        public AssistantReply(String text, String tool) {
            this.text = text;
            this.tool = tool;
        }
    }

    private final PiAgentBridge bridge;

    public OpsPiAgentEngine(PiAgentBridge bridge) {
        this.bridge = bridge;
    }

    public AssistantReply reply(String userMessage, DoctorReport report) {
        String system = buildSystemPrompt();
        String user = buildUserPrompt(userMessage, report);
        PiAgentBridge.PiAgentResult result = bridge.ask(system, user);

        if (!result.success || result.text == null || result.text.trim().isEmpty()) {
            return new AssistantReply("Pi-agent request failed: " + result.error, "none");
        }

        AssistantReply parsed = parseJsonReply(result.text);
        if (parsed != null) return parsed;
        return new AssistantReply(result.text.trim(), "none");
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
                String sourceType = "unknown";
                if (issue.ruleSource != null && issue.ruleSource.sourceType != null) {
                    sourceType = issue.ruleSource.sourceType.name();
                }
                sb.append("- ").append(issue.code)
                    .append(" [").append(issue.severity).append("]")
                    .append(" domain=").append(issue.ruleDomain)
                    .append(" source=").append(sourceType)
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
