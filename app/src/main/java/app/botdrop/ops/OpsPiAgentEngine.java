package app.botdrop.ops;

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
        String user = userMessage == null ? "" : userMessage.trim();
        PiAgentBridge.PiAgentResult result = bridge.ask(system, user);

        if (!result.success || result.text == null || result.text.trim().isEmpty()) {
            return new AssistantReply("Pi-agent request failed: " + result.error, "none");
        }

        return new AssistantReply(result.text.trim(), "none");
    }

    private String buildSystemPrompt() {
        return "You are a helpful assistant in BotDrop. "
            + "Reply directly and concisely in plain text.";
    }
}
