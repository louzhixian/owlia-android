package app.botdrop.ops;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class OpsLlmClient {

    private static final String LOG_TAG = "OpsLlmClient";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 30000;

    public static class LlmResponse {
        public final boolean success;
        public final String text;
        public final String error;

        public LlmResponse(boolean success, String text, String error) {
            this.success = success;
            this.text = text;
            this.error = error;
        }
    }

    public LlmResponse ask(OpsLlmConfig cfg, String systemPrompt, String userPrompt) {
        if (cfg == null || !cfg.isValid()) {
            return new LlmResponse(false, null, "Missing LLM configuration");
        }

        try {
            if ("anthropic".equals(cfg.provider)) {
                return askAnthropic(cfg, systemPrompt, userPrompt);
            } else if ("openai".equals(cfg.provider)) {
                return askOpenAiCompatible(
                    "https://api.openai.com/v1/chat/completions",
                    cfg,
                    systemPrompt,
                    userPrompt,
                    false
                );
            } else if ("openrouter".equals(cfg.provider)) {
                return askOpenAiCompatible(
                    "https://openrouter.ai/api/v1/chat/completions",
                    cfg,
                    systemPrompt,
                    userPrompt,
                    true
                );
            } else {
                return new LlmResponse(false, null, "Provider not supported yet: " + cfg.provider);
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "LLM request failed: " + e.getMessage());
            return new LlmResponse(false, null, e.getMessage());
        }
    }

    private LlmResponse askAnthropic(OpsLlmConfig cfg, String systemPrompt, String userPrompt) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", cfg.apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("max_tokens", 1024);
        body.put("system", systemPrompt);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        body.put("messages", messages);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes());
        }

        int code = conn.getResponseCode();
        String response = readResponse(conn, code < 400);
        if (code < 200 || code >= 300) {
            return new LlmResponse(false, null, "Anthropic HTTP " + code + ": " + response);
        }

        JSONObject json = new JSONObject(response);
        JSONArray content = json.optJSONArray("content");
        String text = "";
        if (content != null && content.length() > 0) {
            JSONObject first = content.optJSONObject(0);
            if (first != null) text = first.optString("text", "");
        }
        return new LlmResponse(true, text, null);
    }

    private LlmResponse askOpenAiCompatible(String endpoint, OpsLlmConfig cfg, String systemPrompt,
                                            String userPrompt, boolean openRouterHeaders) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
        if (openRouterHeaders) {
            conn.setRequestProperty("HTTP-Referer", "https://app.botdrop");
            conn.setRequestProperty("X-Title", "BotDrop Ops Assistant");
        }

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        body.put("messages", messages);
        body.put("temperature", 0.1);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes());
        }

        int code = conn.getResponseCode();
        String response = readResponse(conn, code < 400);
        if (code < 200 || code >= 300) {
            String label = openRouterHeaders ? "OpenRouter" : "OpenAI";
            return new LlmResponse(false, null, label + " HTTP " + code + ": " + response);
        }

        JSONObject json = new JSONObject(response);
        JSONArray choices = json.optJSONArray("choices");
        String text = "";
        if (choices != null && choices.length() > 0) {
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg != null) text = msg.optString("content", "");
        }
        return new LlmResponse(true, text, null);
    }

    private String readResponse(HttpURLConnection conn, boolean successStream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            successStream ? conn.getInputStream() : conn.getErrorStream()
        ));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
}
