package app.botdrop.ops;

import org.json.JSONObject;

import app.botdrop.BotDropConfig;

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
    private final OpsKnowledgeBase knowledgeBase;

    public OpsPiAgentEngine(PiAgentBridge bridge, OpsKnowledgeBase knowledgeBase) {
        this.bridge = bridge;
        this.knowledgeBase = knowledgeBase;
    }

    public AssistantReply reply(String userMessage, DoctorReport report) {
        if (knowledgeBase != null) {
            knowledgeBase.refreshAsyncIfStale();
        }

        String system = buildSystemPrompt();
        String user = buildUserPrompt(userMessage);
        PiAgentBridge.PiAgentResult result = bridge.ask(system, user);

        if (!result.success || result.text == null || result.text.trim().isEmpty()) {
            return new AssistantReply("Pi-agent request failed: " + result.error, "none");
        }

        return new AssistantReply(result.text.trim(), "none");
    }

    private String buildSystemPrompt() {
        return "You are BotDrop OpenClaw configuration assistant for Android/Termux users. "
            + "Focus on configuring and fixing OpenClaw in this mobile app environment. "
            + "Prioritize practical steps that edit ~/.openclaw/openclaw.json and related local files. "
            + "Do not give generic desktop-only Discord instructions unless explicitly requested. "
            + "If required fields are unclear, ask concise follow-up questions. "
            + "When giving config examples, use JSON snippets and exact paths. "
            + "Reply in Chinese when the user asks in Chinese.";
    }

    private String buildUserPrompt(String userMessage) {
        String msg = userMessage == null ? "" : userMessage.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("User request:\n").append(msg).append("\n\n");
        sb.append("Environment facts:\n");
        sb.append("- Platform: Android + Termux bootstrap\n");
        sb.append("- OpenClaw config path: ~/.openclaw/openclaw.json\n");
        sb.append("- Auth profiles path: ~/.openclaw/agents/main/agent/auth-profiles.json\n");
        sb.append("- Goal: help user configure/fix OpenClaw quickly and safely\n");

        String configSummary = buildConfigSummary();
        if (!configSummary.isEmpty()) {
            sb.append("\nCurrent config summary:\n").append(configSummary).append("\n");
        }

        String docs = knowledgeBase == null ? "" : knowledgeBase.buildContext(msg, 2600);
        if (!docs.trim().isEmpty()) {
            sb.append("\nRelevant OpenClaw docs snippets:\n");
            sb.append(docs).append("\n");
        }

        sb.append("\nResponse format:\n");
        sb.append("- Give concrete next steps for this user request.\n");
        sb.append("- If config change is needed, provide exact JSON patch-style snippet.\n");
        sb.append("- Keep it concise and actionable.\n");
        return sb.toString();
    }

    private String buildConfigSummary() {
        try {
            JSONObject cfg = BotDropConfig.readConfig();
            JSONObject agents = cfg.optJSONObject("agents");
            JSONObject defaults = agents != null ? agents.optJSONObject("defaults") : null;
            JSONObject model = defaults != null ? defaults.optJSONObject("model") : null;
            JSONObject channels = cfg.optJSONObject("channels");

            String primary = model != null ? model.optString("primary", "") : "";
            boolean discordEnabled = false;
            if (channels != null) {
                JSONObject discord = channels.optJSONObject("discord");
                discordEnabled = discord != null && discord.optBoolean("enabled", false);
            }

            StringBuilder sb = new StringBuilder();
            if (!primary.isEmpty()) sb.append("- agents.defaults.model.primary: ").append(primary).append("\n");
            sb.append("- channels.discord.enabled: ").append(discordEnabled).append("\n");
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
