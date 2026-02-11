package app.botdrop.ops;

public class OpsLlmConfig {

    public final String provider;
    public final String model;
    public final String apiKey;

    public OpsLlmConfig(String provider, String model, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
    }

    public boolean isValid() {
        return provider != null && !provider.trim().isEmpty()
            && model != null && !model.trim().isEmpty()
            && apiKey != null && !apiKey.trim().isEmpty();
    }
}
