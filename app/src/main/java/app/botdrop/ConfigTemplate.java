package app.botdrop;

/**
 * Configuration template data class.
 * Holds user configuration for caching and quick recovery.
 */
public class ConfigTemplate {

    public String provider;      // e.g., "google"
    public String model;         // e.g., "gemini-3-flash-preview"
    public String apiKey;        // e.g., "AIzaSy..."
    public String baseUrl;       // optional custom provider base URL
    public java.util.List<String> customModels;
    public String tgBotToken;    // e.g., "7123456:AAF..." (optional)
    public String tgUserId;      // e.g., "987654321" (optional)

    public ConfigTemplate() {
    }

    public ConfigTemplate(String provider, String model, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
    }

    /**
     * Validate that required fields are present.
     * @return true if provider, model, and apiKey are non-empty
     */
    public boolean isValid() {
        return provider != null && !provider.isEmpty()
            && model != null && !model.isEmpty()
            && apiKey != null && !apiKey.isEmpty();
        // tgBotToken and tgUserId are optional
    }

    @Override
    public String toString() {
        return "ConfigTemplate{" +
            "provider='" + provider + '\'' +
            ", model='" + model + '\'' +
            ", apiKey='***'" + // Don't log full key
            ", baseUrl='" + (baseUrl != null ? "set" : "null") + '\'' +
            ", customModels=" + (customModels == null ? 0 : customModels.size()) +
            ", tgBotToken=" + (tgBotToken != null ? "***" : "null") +
            ", tgUserId='" + tgUserId + '\'' +
            '}';
    }
}
