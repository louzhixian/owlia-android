package app.botdrop.ops;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import app.botdrop.BotDropConfig;

public class OpsCredentialResolver {

    private static final String LOG_TAG = "OpsCredentialResolver";
    private static final String AUTH_PROFILES_FILE =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/agents/main/agent/auth-profiles.json";
    private static final List<String> SUPPORTED_PROVIDERS =
        Arrays.asList("anthropic", "openai", "openrouter");

    public OpsLlmConfig resolvePrimaryConfig() {
        try {
            JSONObject config = BotDropConfig.readConfig();
            String primaryModel = readPrimaryModel(config);
            String provider = parseProvider(primaryModel);
            String model = parseModel(primaryModel);
            if (provider != null && model != null && isProviderSupported(provider)) {
                String key = readApiKey(provider, model);
                if (key != null && !key.trim().isEmpty()) {
                    return new OpsLlmConfig(provider, model, key);
                }
            }

            // Fallback: if primary provider is unsupported/missing key, pick any supported provider key.
            return resolveFallbackConfig();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to resolve primary config: " + e.getMessage());
            return null;
        }
    }

    private String readPrimaryModel(JSONObject cfg) {
        JSONObject agents = cfg.optJSONObject("agents");
        JSONObject defaults = agents != null ? agents.optJSONObject("defaults") : null;
        JSONObject model = defaults != null ? defaults.optJSONObject("model") : null;
        return model != null ? model.optString("primary", "").trim() : "";
    }

    private String parseProvider(String primaryModel) {
        if (primaryModel == null || primaryModel.trim().isEmpty()) return null;
        int idx = primaryModel.indexOf('/');
        if (idx <= 0) return null;
        return primaryModel.substring(0, idx);
    }

    private String parseModel(String primaryModel) {
        if (primaryModel == null || primaryModel.trim().isEmpty()) return null;
        int idx = primaryModel.indexOf('/');
        if (idx <= 0 || idx >= primaryModel.length() - 1) return null;
        return primaryModel.substring(idx + 1);
    }

    private String readApiKey(String provider, String model) {
        File f = new File(AUTH_PROFILES_FILE);
        if (!f.exists()) return null;

        try (FileReader reader = new FileReader(f)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) sb.append(buffer, 0, read);

            JSONObject root = new JSONObject(sb.toString());
            JSONObject profiles = root.optJSONObject("profiles");
            if (profiles == null) return null;

            JSONObject exact = profiles.optJSONObject(provider + ":" + model);
            if (exact != null) {
                String key = exact.optString("key", "").trim();
                if (!key.isEmpty()) return key;
            }

            JSONObject fallback = profiles.optJSONObject(provider + ":default");
            if (fallback != null) {
                String key = fallback.optString("key", "").trim();
                if (!key.isEmpty()) return key;
            }

            Iterator<String> ids = profiles.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject p = profiles.optJSONObject(id);
                if (p == null) continue;
                if (!provider.equals(p.optString("provider", ""))) continue;
                String key = p.optString("key", "").trim();
                if (!key.isEmpty()) return key;
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed reading auth profiles: " + e.getMessage());
        }
        return null;
    }

    private OpsLlmConfig resolveFallbackConfig() {
        for (String provider : SUPPORTED_PROVIDERS) {
            String model = defaultModelForProvider(provider);
            String key = readApiKey(provider, model);
            if (key == null || key.trim().isEmpty()) continue;
            return new OpsLlmConfig(provider, model, key);
        }
        return null;
    }

    private String defaultModelForProvider(String provider) {
        switch (provider) {
            case "anthropic":
                return "claude-sonnet-4-5";
            case "openai":
                return "gpt-4o-mini";
            case "openrouter":
                return "openai/gpt-4o-mini";
            default:
                return "default";
        }
    }

    private boolean isProviderSupported(String provider) {
        return SUPPORTED_PROVIDERS.contains(provider);
    }
}
