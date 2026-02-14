package app.botdrop;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Helper class for reading and writing OpenClaw configuration.
 * Handles openclaw.json at ~/.openclaw/openclaw.json
 *
 * Thread-safe: All file operations are synchronized.
 */
public class BotDropConfig {

    private static final String LOG_TAG = "BotDropConfig";
    private static final String CONFIG_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw";
    private static final String CONFIG_FILE = CONFIG_DIR + "/openclaw.json";
    public static final String CUSTOM_PROVIDER_ID = "custom";
    private static final String MODELS_BLOCK_KEY = "models";
    private static final String MODELS_MODE_KEY = "mode";
    private static final String MODELS_MODE_MERGE = "merge";
    private static final String MODELS_PROVIDERS_KEY = "providers";
    private static final String MODELS_PROVIDER_API_KEY = "api";
    private static final String MODELS_PROVIDER_API_KEY_CAMEL = "apiKey";
    private static final String MODELS_PROVIDER_BASE_URL_KEY = "baseUrl";
    private static final String MODELS_PROVIDER_MODELS_KEY = "models";
    private static final String MODELS_PROVIDER_MODEL_NAME_KEY = "name";
    private static final String MODELS_PROVIDER_MODEL_ID_KEY = "id";
    private static final String MODELS_PROVIDER_DEFAULT_API = "openai-completions";
    
    // Lock for thread-safe file operations
    private static final Object CONFIG_LOCK = new Object();
    
    /**
     * Read the current configuration
     * @return JSONObject of config, or empty config if not found
     */
    public static JSONObject readConfig() {
        synchronized (CONFIG_LOCK) {
            File configFile = new File(CONFIG_FILE);
            
            if (!configFile.exists()) {
                Logger.logDebug(LOG_TAG, "Config file does not exist: " + CONFIG_FILE);
                return new JSONObject();
            }
            
            try (FileReader reader = new FileReader(configFile)) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                
                JSONObject config = new JSONObject(sb.toString());
                Logger.logDebug(LOG_TAG, "Config loaded successfully");
                return config;
                
            } catch (IOException | JSONException e) {
                Logger.logError(LOG_TAG, "Failed to read config: " + e.getMessage());
                return new JSONObject();
            }
        }
    }
    
    /**
     * Write configuration to file
     * @param config JSONObject to write
     * @return true if successful
     */
    public static boolean writeConfig(JSONObject config) {
        synchronized (CONFIG_LOCK) {
            // Create parent directories if needed
            File configDir = new File(CONFIG_DIR);
            if (!configDir.exists()) {
                if (!configDir.mkdirs()) {
                    Logger.logError(LOG_TAG, "Failed to create config directory: " + CONFIG_DIR);
                    return false;
                }
            }
            
            File configFile = new File(CONFIG_FILE);
            
            try (FileWriter writer = new FileWriter(configFile)) {
                // Pretty print JSON with 2-space indent
                String jsonString = config.toString(2);
                writer.write(jsonString);
                
                // Set file permissions to owner-only (prevent other apps from reading API keys)
                configFile.setReadable(false, false);
                configFile.setReadable(true, true);
                configFile.setWritable(false, false);
                configFile.setWritable(true, true);
                
                Logger.logInfo(LOG_TAG, "Config written successfully");
                return true;
                
            } catch (IOException | JSONException e) {
                Logger.logError(LOG_TAG, "Failed to write config: " + e.getMessage());
                return false;
            }
        }
    }
    
    /**
     * Set the default AI provider and model
     * @param provider Provider ID (e.g., "anthropic")
     * @param model Model name (e.g., "claude-sonnet-4-5")
     * @return true if successful
     */
    public static boolean setProvider(String provider, String model) {
        return setProvider(provider, model, null, null);
    }

    /**
     * Apply provider configuration in one step.
     *
     * - Default providers: set provider/model in openclaw.json + write API key.
     * - Custom provider: also create/update models.providers.<provider> metadata.
     *
     * @param provider Provider ID (for custom provider, use "custom")
     * @param model Model name (provider-aware model id)
     * @param apiKey API key
     * @param baseUrl Custom provider base URL (required only when provider is custom)
     * @param availableModels Custom provider supported model ids
     * @return true if config written successfully
     */
    public static boolean setActiveProvider(
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        List<String> availableModels
    ) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedModel = normalizeModel(normalizedProvider, model);
        String normalizedApiKey = apiKey == null ? "" : apiKey.trim();
        String effectiveCustomApiKey = normalizedApiKey;
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        boolean providerUsesCustomEndpoint = !TextUtils.isEmpty(normalizedBaseUrl);

        if (TextUtils.isEmpty(normalizedProvider) || TextUtils.isEmpty(normalizedModel)) {
            return false;
        }

        if (providerUsesCustomEndpoint && TextUtils.isEmpty(effectiveCustomApiKey)) {
            effectiveCustomApiKey = getApiKey(normalizedProvider);
        }

        if (!setProvider(
            normalizedProvider,
            normalizedModel,
            baseUrl,
            availableModels,
            providerUsesCustomEndpoint ? effectiveCustomApiKey : null
        )) {
            return false;
        }

        if (providerUsesCustomEndpoint) {
            return setApiKey(provider, normalizedModel, apiKey, normalizedBaseUrl);
        }

        return setApiKey(provider, normalizedModel, apiKey);
    }

    /**
     * Set the default AI provider and model with custom provider metadata.
     *
     * For custom provider, `availableModels` are written under models.providers in
     * openclaw.json so OpenClaw can resolve selected model ids.
     *
     * @param provider Provider ID (e.g., "anthropic" / "custom")
     * @param model Model name (e.g., "claude-sonnet-4-5")
     * @param baseUrl Base URL for custom provider (used by models.providers)
     * @param availableModels Optional list of model IDs supported by the custom provider
     * @return true if successful
     */
    public static boolean setProvider(String provider, String model, String baseUrl, List<String> availableModels) {
        return setProvider(provider, model, baseUrl, availableModels, null);
    }

    private static boolean setProvider(
        String provider,
        String model,
        String baseUrl,
        List<String> availableModels,
        String apiKey
    ) {
        try {
            String normalizedProvider = normalizeProvider(provider);
            JSONObject config = readConfig();
            
            // Create agents.defaults structure if not exists
            if (!config.has("agents")) {
                config.put("agents", new JSONObject());
            }
            
            JSONObject agents = config.getJSONObject("agents");
            if (!agents.has("defaults")) {
                agents.put("defaults", new JSONObject());
            }
            
            JSONObject defaults = agents.getJSONObject("defaults");

            String normalizedModel = normalizeModel(normalizedProvider, model);

            // Set model as object: { primary: "provider/model" }
            JSONObject modelObj = new JSONObject();
            modelObj.put("primary", normalizedProvider + "/" + normalizedModel);
            defaults.put("model", modelObj);
            
            // Set workspace if not already set
            if (!defaults.has("workspace")) {
                defaults.put("workspace", "~/botdrop");
            }

            // Ensure gateway config for Android
            if (!config.has("gateway")) {
                config.put("gateway", new JSONObject());
            }
            JSONObject gateway = config.getJSONObject("gateway");
            if (!gateway.has("mode")) {
                gateway.put("mode", "local");
            }
            // Gateway requires auth token
            if (!gateway.has("auth")) {
                JSONObject auth = new JSONObject();
                auth.put("token", java.util.UUID.randomUUID().toString());
                gateway.put("auth", auth);
            }

            if (!TextUtils.isEmpty(normalizeBaseUrl(baseUrl))) {
                boolean customConfigUpdated = syncCustomProviderConfig(
                    config,
                    normalizedProvider,
                    baseUrl,
                    normalizedModel,
                    availableModels,
                    apiKey
                );
                if (!customConfigUpdated) {
                    Logger.logWarn(LOG_TAG, "Failed to update custom provider metadata for: " + normalizedProvider);
                    return false;
                }
            }

            return writeConfig(config);
            
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to set provider: " + e.getMessage());
            return false;
        }
    }
    
    private static final String AUTH_PROFILES_DIR = CONFIG_DIR + "/agents/main/agent";
    private static final String AUTH_PROFILES_FILE = AUTH_PROFILES_DIR + "/auth-profiles.json";

    private static boolean syncCustomProviderConfig(
        JSONObject config,
        String provider,
        String baseUrl,
        String selectedModel,
        List<String> availableModels,
        String apiKey
    ) {
        if (TextUtils.isEmpty(provider) || config == null) {
            return false;
        }

        String normalizedProvider = provider.trim();
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);

        if (TextUtils.isEmpty(normalizedBaseUrl)) {
            return false;
        }

        try {
            JSONObject modelsSection = config.optJSONObject(MODELS_BLOCK_KEY);
            if (modelsSection == null) {
                modelsSection = new JSONObject();
                config.put(MODELS_BLOCK_KEY, modelsSection);
            }

            if (!modelsSection.has(MODELS_MODE_KEY)) {
                modelsSection.put(MODELS_MODE_KEY, MODELS_MODE_MERGE);
            }

            JSONObject providers = modelsSection.optJSONObject(MODELS_PROVIDERS_KEY);
            if (providers == null) {
                providers = new JSONObject();
                modelsSection.put(MODELS_PROVIDERS_KEY, providers);
            }

            JSONObject providerConfig = providers.optJSONObject(normalizedProvider);
            if (providerConfig == null) {
                providerConfig = new JSONObject();
            } else {
                providerConfig = new JSONObject(providerConfig.toString());
            }
            providers.put(normalizedProvider, providerConfig);

            providerConfig.put(MODELS_PROVIDER_BASE_URL_KEY, normalizedBaseUrl);
            if (!providerConfig.has(MODELS_PROVIDER_API_KEY)) {
                providerConfig.put(MODELS_PROVIDER_API_KEY, MODELS_PROVIDER_DEFAULT_API);
            }
            String normalizedProvidedApiKey = apiKey == null ? "" : apiKey.trim();
            if (TextUtils.isEmpty(normalizedProvidedApiKey)) {
                String fallbackApiKey = getApiKey(normalizedProvider);
                if (!TextUtils.isEmpty(fallbackApiKey)) {
                    providerConfig.put(MODELS_PROVIDER_API_KEY_CAMEL, fallbackApiKey);
                } else {
                    providerConfig.remove(MODELS_PROVIDER_API_KEY_CAMEL);
                }
            } else {
                providerConfig.put(MODELS_PROVIDER_API_KEY_CAMEL, normalizedProvidedApiKey);
            }

            LinkedHashSet<String> mergedModelIds = new LinkedHashSet<>();
            mergedModelIds.addAll(readProviderModels(providerConfig, selectedModel));
            if (availableModels != null) {
                for (String modelId : availableModels) {
                    String normalized = normalizeModel(normalizedProvider, modelId);
                    if (!TextUtils.isEmpty(normalized)) {
                        mergedModelIds.add(normalized);
                    }
                }
            }
            if (mergedModelIds.isEmpty()) {
                Logger.logWarn(LOG_TAG, "No custom models to write for provider: " + normalizedProvider);
                return false;
            }

            JSONArray modelList = new JSONArray();
            for (String modelId : mergedModelIds) {
                JSONObject modelEntry = new JSONObject();
                modelEntry.put(MODELS_PROVIDER_MODEL_ID_KEY, modelId);
                modelEntry.put(MODELS_PROVIDER_MODEL_NAME_KEY, modelId);
                modelList.put(modelEntry);
            }
            providerConfig.put(MODELS_PROVIDER_MODELS_KEY, modelList);
            return true;
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to sync custom provider metadata: " + e.getMessage());
            return false;
        }
    }

    private static List<String> readProviderModels(JSONObject providerConfig, String selectedModel) {
        List<String> modelIds = new ArrayList<>();
        if (providerConfig == null) {
            if (!TextUtils.isEmpty(selectedModel)) {
                modelIds.add(selectedModel);
            }
            return modelIds;
        }

        if (!TextUtils.isEmpty(selectedModel)) {
            modelIds.add(selectedModel);
        }

        JSONArray modelsArray = providerConfig.optJSONArray(MODELS_PROVIDER_MODELS_KEY);
        if (modelsArray == null || modelsArray.length() == 0) {
            return modelIds;
        }

        for (int i = 0; i < modelsArray.length(); i++) {
            Object rawModel = modelsArray.opt(i);
            if (rawModel == null) {
                continue;
            }
            if (rawModel instanceof String) {
                String value = ((String) rawModel).trim();
                if (!TextUtils.isEmpty(value)) {
                    modelIds.add(value);
                }
                continue;
            }
            if (!(rawModel instanceof JSONObject)) {
                continue;
            }
            JSONObject modelEntry = (JSONObject) rawModel;
            String modelId = modelEntry.optString(MODELS_PROVIDER_MODEL_NAME_KEY, "").trim();
            if (TextUtils.isEmpty(modelId)) {
                modelId = modelEntry.optString(MODELS_PROVIDER_MODEL_ID_KEY, "").trim();
            }
            if (!TextUtils.isEmpty(modelId)) {
                modelIds.add(modelId);
            }
        }

        return modelIds;
    }

    /**
     * Set the API key for a provider.
     * Writes to ~/.openclaw/agents/main/agent/auth-profiles.json
     */
    public static boolean setApiKey(String provider, String credential) {
        return setApiKey(provider, null, credential, null);
    }

    /**
     * Set the API key for a provider/model pair.
     * Writes:
     * - provider:model (model-specific entry)
     * - provider:default (compatibility fallback)
     */
    public static boolean setApiKey(String provider, String model, String credential) {
        return setApiKey(provider, model, credential, null);
    }

    /**
     * Set the API key and optional base URL for a provider/model pair.
     */
    public static boolean setApiKey(String provider, String model, String credential, String baseUrl) {
        String normalizedProvider = normalizeProvider(provider);
        if (TextUtils.isEmpty(normalizedProvider)) {
            return false;
        }

        synchronized (CONFIG_LOCK) {
            try {
                File dir = new File(AUTH_PROFILES_DIR);
                if (!dir.exists()) dir.mkdirs();

                // Read existing auth profiles or create new
                JSONObject authProfiles;
                File authFile = new File(AUTH_PROFILES_FILE);
                if (authFile.exists()) {
                    try (FileReader reader = new FileReader(authFile)) {
                        StringBuilder sb = new StringBuilder();
                        char[] buffer = new char[1024];
                        int read;
                        while ((read = reader.read(buffer)) != -1) {
                            sb.append(buffer, 0, read);
                        }
                        authProfiles = new JSONObject(sb.toString());
                    }
                } else {
                    authProfiles = new JSONObject();
                    authProfiles.put("version", 1);
                    authProfiles.put("profiles", new JSONObject());
                }

                String normalizedModel = normalizeModel(normalizedProvider, model);
                String modelProfileId = normalizedProvider + ":" + normalizedModel;
                String defaultProfileId = normalizedProvider + ":default";
                String normalizedCredential = credential == null ? "" : credential.trim();
                String normalizedBaseUrl = normalizeBaseUrl(baseUrl);

                JSONObject profiles = authProfiles.getJSONObject("profiles");
                JSONObject modelProfile = profiles.optJSONObject(modelProfileId);
                JSONObject defaultProfile = profiles.optJSONObject(defaultProfileId);
                JSONObject sourceProfile = modelProfile != null ? modelProfile : defaultProfile;
                boolean hasExistingKey = sourceProfile != null
                    && !TextUtils.isEmpty(sourceProfile.optString("key", "").trim());

                if (TextUtils.isEmpty(normalizedCredential) && !hasExistingKey) {
                    return false;
                }

                // Add/update profile: model-specific + default fallback
                JSONObject profile = sourceProfile != null
                    ? new JSONObject(sourceProfile.toString())
                    : new JSONObject();
                profile.put("type", "api_key");
                profile.put("provider", normalizedProvider);
                profile.put("model", normalizedModel);
                if (!TextUtils.isEmpty(normalizedCredential)) {
                    profile.put("key", normalizedCredential);
                }
                if (!TextUtils.isEmpty(normalizedBaseUrl)) {
                    profile.put("base_url", normalizedBaseUrl);
                }
                profiles.put(modelProfileId, profile);
                profiles.put(defaultProfileId, profile);

                // Write
                try (FileWriter writer = new FileWriter(authFile)) {
                    writer.write(authProfiles.toString(2));
                }
                authFile.setReadable(false, false);
                authFile.setReadable(true, true);
                authFile.setWritable(false, false);
                authFile.setWritable(true, true);

                Logger.logInfo(LOG_TAG, "Auth profile written for " + modelProfileId +
                    " (and fallback " + defaultProfileId + ")");
                return true;

            } catch (IOException | JSONException e) {
                Logger.logError(LOG_TAG, "Failed to write auth profile: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Check whether auth-profiles contains a non-empty API key for provider.
     */
    public static boolean hasApiKey(String provider) {
        synchronized (CONFIG_LOCK) {
            String normalizedProvider = normalizeProvider(provider);
            try {
                File authFile = new File(AUTH_PROFILES_FILE);
                if (!authFile.exists()) return false;

                JSONObject authProfiles;
                try (FileReader reader = new FileReader(authFile)) {
                    StringBuilder sb = new StringBuilder();
                    char[] buffer = new char[1024];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        sb.append(buffer, 0, read);
                    }
                    authProfiles = new JSONObject(sb.toString());
                }

                JSONObject profiles = authProfiles.optJSONObject("profiles");
                if (profiles == null) return false;
                JSONObject defaultProfile = profiles.optJSONObject(normalizedProvider + ":default");
                if (defaultProfile != null) {
                    String key = defaultProfile.optString("key", "").trim();
                    if (!key.isEmpty()) return true;
                }

                // Backstop: look for any provider:* entry with provider match.
                java.util.Iterator<String> keys = profiles.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject p = profiles.optJSONObject(id);
                    if (p == null) continue;
                    if (!isProviderMatch(normalizedProvider, p.optString("provider", ""))) continue;
                    String key = p.optString("key", "").trim();
                    if (!key.isEmpty()) return true;
                }
                return false;
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to check auth profile: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Read the latest API key for a provider.
     * Priority:
     * 1) provider:default
     * 2) first matching provider entry
     */
    public static String getApiKey(String provider) {
        synchronized (CONFIG_LOCK) {
            String normalizedProvider = normalizeProvider(provider);
            try {
                File authFile = new File(AUTH_PROFILES_FILE);
                if (!authFile.exists()) return "";

                JSONObject authProfiles;
                try (FileReader reader = new FileReader(authFile)) {
                    StringBuilder sb = new StringBuilder();
                    char[] buffer = new char[1024];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        sb.append(buffer, 0, read);
                    }
                    authProfiles = new JSONObject(sb.toString());
                }

                JSONObject profiles = authProfiles.optJSONObject("profiles");
                if (profiles == null) return "";

                JSONObject defaultProfile = profiles.optJSONObject(normalizedProvider + ":default");
                if (defaultProfile != null) {
                    String key = defaultProfile.optString("key", "").trim();
                    if (!key.isEmpty()) return key;
                }

                java.util.Iterator<String> keys = profiles.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject p = profiles.optJSONObject(id);
                    if (p == null) continue;
                    if (!isProviderMatch(normalizedProvider, p.optString("provider", ""))) continue;
                    String key = p.optString("key", "").trim();
                    if (!key.isEmpty()) return key;
                }

                return "";
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to read api key from auth profile: " + e.getMessage());
                return "";
            }
        }
    }

    /**
     * Read the latest custom base URL for a provider.
     * Priority:
     * 1) provider:default
     * 2) first matching provider entry
     */
    public static String getBaseUrl(String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            return "";
        }

        synchronized (CONFIG_LOCK) {
            String normalizedProvider = normalizeProvider(provider);
            try {
                File authFile = new File(AUTH_PROFILES_FILE);
                if (!authFile.exists()) return "";

                JSONObject authProfiles;
                try (FileReader reader = new FileReader(authFile)) {
                    StringBuilder sb = new StringBuilder();
                    char[] buffer = new char[1024];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        sb.append(buffer, 0, read);
                    }
                    authProfiles = new JSONObject(sb.toString());
                }

                JSONObject profiles = authProfiles.optJSONObject("profiles");
                if (profiles == null) return "";

                JSONObject defaultProfile = profiles.optJSONObject(normalizedProvider + ":default");
                if (defaultProfile != null) {
                    String baseUrl = defaultProfile.optString("base_url", "").trim();
                    if (!baseUrl.isEmpty()) return baseUrl;
                }

                java.util.Iterator<String> keys = profiles.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject p = profiles.optJSONObject(id);
                    if (p == null) continue;
                    if (!isProviderMatch(normalizedProvider, p.optString("provider", ""))) continue;
                    String baseUrl = p.optString("base_url", "").trim();
                    if (!baseUrl.isEmpty()) return baseUrl;
                }

                return "";
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to read base URL from auth profile: " + e.getMessage());
                return "";
            }
        }
    }

    /**
     * Return provider IDs configured in models.providers with a non-empty baseUrl.
     */
    public static List<String> getConfiguredCustomProviders() {
        List<String> providers = new ArrayList<>();

        synchronized (CONFIG_LOCK) {
            try {
                JSONObject config = readConfig();
                JSONObject modelsSection = config.optJSONObject(MODELS_BLOCK_KEY);
                if (modelsSection == null) {
                    return providers;
                }

                JSONObject providersSection = modelsSection.optJSONObject(MODELS_PROVIDERS_KEY);
                if (providersSection == null) {
                    return providers;
                }

                java.util.Iterator<String> providerKeys = providersSection.keys();
                while (providerKeys.hasNext()) {
                    String providerId = providerKeys.next();
                    if (TextUtils.isEmpty(providerId)) {
                        continue;
                    }
                    JSONObject providerConfig = providersSection.optJSONObject(providerId);
                    if (providerConfig == null) {
                        continue;
                    }
                    String baseUrl = providerConfig.optString(MODELS_PROVIDER_BASE_URL_KEY, "").trim();
                    if (!TextUtils.isEmpty(baseUrl)) {
                        providers.add(providerId);
                    }
                }

                Collections.sort(providers, String::compareToIgnoreCase);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to read configured custom providers: " + e.getMessage());
            }
        }

        return providers;
    }

    private static String normalizeModel(String provider, String model) {
        if (model == null) return "default";
        String normalized = model.trim();
        if (normalized.isEmpty()) return "default";
        String providerPrefix = provider + "/";
        if (normalized.startsWith(providerPrefix)) {
            normalized = normalized.substring(providerPrefix.length());
        }
        return normalized.isEmpty() ? "default" : normalized;
    }

    private static String normalizeProvider(String provider) {
        if (provider == null) {
            return "";
        }
        return provider.trim();
    }

    public static boolean isCustomProvider(String provider) {
        return TextUtils.equals(normalizeProvider(provider), CUSTOM_PROVIDER_ID);
    }

    private static boolean isProviderMatch(String targetProvider, String profileProvider) {
        if (TextUtils.isEmpty(targetProvider) || TextUtils.isEmpty(profileProvider)) {
            return false;
        }
        return TextUtils.equals(targetProvider, profileProvider);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        return baseUrl.trim();
    }

    /**
     * Check if config file exists and has basic structure
     * @return true if configured
     */
    public static boolean isConfigured() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            return false;
        }
        
        try {
            JSONObject config = readConfig();
            // Check if it has agents.defaults.model.primary set
            if (config.has("agents")) {
                JSONObject agents = config.getJSONObject("agents");
                if (agents.has("defaults")) {
                    JSONObject defaults = agents.getJSONObject("defaults");
                    if (defaults.has("model")) {
                        Object model = defaults.get("model");
                        if (model instanceof JSONObject) {
                            return ((JSONObject) model).has("primary");
                        }
                    }
                }
            }
            return false;
            
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to check config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove known deprecated/unsupported keys from existing config.
     * Safe to call repeatedly; writes only when a change is made.
     */
    public static void sanitizeLegacyConfig() {
        synchronized (CONFIG_LOCK) {
            try {
                File configFile = new File(CONFIG_FILE);
                if (!configFile.exists()) return;

                JSONObject config = readConfig();
                boolean changed = false;

                JSONObject channels = config.optJSONObject("channels");
                if (channels != null) {
                    JSONObject telegram = channels.optJSONObject("telegram");
                    if (telegram != null) {
                        JSONObject network = telegram.optJSONObject("network");
                        if (network != null) {
                            if (network.has("autoSelectFamilyAttemptTimeout")) {
                                network.remove("autoSelectFamilyAttemptTimeout");
                                changed = true;
                                Logger.logInfo(LOG_TAG, "Removed deprecated key: channels.telegram.network.autoSelectFamilyAttemptTimeout");
                            }

                            // BotDrop prefers forcing IPv4 via NODE_OPTIONS; leaving OpenClaw's
                            // autoSelectFamily behavior at its default avoids long first-connect
                            // delays seen on some Android/proot environments.
                            if (network.has("autoSelectFamily")) {
                                network.remove("autoSelectFamily");
                                changed = true;
                                Logger.logInfo(LOG_TAG, "Removed key: channels.telegram.network.autoSelectFamily");
                            }

                            // If network becomes empty, remove it to keep config clean.
                            if (network.length() == 0) {
                                telegram.remove("network");
                                changed = true;
                            }
                        }
                    }
                }

                if (normalizeProviderModels(config)) {
                    changed = true;
                }

                if (changed) {
                    boolean ok = writeConfig(config);
                    if (!ok) {
                        Logger.logError(LOG_TAG, "Failed to write sanitized config");
                    }
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "sanitizeLegacyConfig failed: " + e.getMessage());
            }
        }
    }

    /**
     * Normalize provider model entries to match OpenClaw schema used by OpenClaw:
     * [{"id": "model-id", "name": "model-id"}]
     */
    private static boolean normalizeProviderModels(JSONObject config) {
        if (config == null) {
            return false;
        }

        JSONObject modelsSection = config.optJSONObject(MODELS_BLOCK_KEY);
        if (modelsSection == null) {
            return false;
        }

        JSONObject providers = modelsSection.optJSONObject(MODELS_PROVIDERS_KEY);
        if (providers == null) {
            return false;
        }

        boolean anyChange = false;
        java.util.Iterator<String> providerKeys = providers.keys();
        while (providerKeys.hasNext()) {
            String providerId = providerKeys.next();
            if (TextUtils.isEmpty(providerId)) {
                continue;
            }

            JSONObject providerConfig = providers.optJSONObject(providerId);
            if (providerConfig == null) {
                continue;
            }

            JSONArray models = providerConfig.optJSONArray(MODELS_PROVIDER_MODELS_KEY);
            if (models == null) {
                continue;
            }

            JSONArray normalized = new JSONArray();
            boolean modified = false;
            boolean hadValidModel = false;

            for (int i = 0; i < models.length(); i++) {
                Object rawModel = models.opt(i);
                String modelId = "";

                if (rawModel instanceof String) {
                    modelId = ((String) rawModel).trim();
                    if (!TextUtils.isEmpty(modelId)) {
                        modified = true;
                    }
                } else if (rawModel instanceof JSONObject) {
                    JSONObject modelEntry = (JSONObject) rawModel;
                    modelId = modelEntry.optString(MODELS_PROVIDER_MODEL_NAME_KEY, "").trim();
                    if (TextUtils.isEmpty(modelId)) {
                        modelId = modelEntry.optString(MODELS_PROVIDER_MODEL_ID_KEY, "").trim();
                    }
                    if (TextUtils.isEmpty(modelId)) {
                        modelId = modelEntry.optString("model", "").trim();
                    }
                    String name = modelEntry.optString(MODELS_PROVIDER_MODEL_NAME_KEY, "").trim();
                    String id = modelEntry.optString(MODELS_PROVIDER_MODEL_ID_KEY, "").trim();
                    if (!TextUtils.equals(modelId, name) || !TextUtils.equals(modelId, id)) {
                        modified = true;
                    }
                }
                if (TextUtils.isEmpty(modelId)) {
                    modified = true;
                    continue;
                }

                try {
                    JSONObject normalizedEntry = new JSONObject();
                    normalizedEntry.put(MODELS_PROVIDER_MODEL_ID_KEY, modelId);
                    normalizedEntry.put(MODELS_PROVIDER_MODEL_NAME_KEY, modelId);
                    normalized.put(normalizedEntry);
                    hadValidModel = true;
                } catch (JSONException ex) {
                    modified = true;
                }
            }

            if (!modified && hadValidModel && models.length() == normalized.length()) {
                continue;
            }

            try {
                providerConfig.put(MODELS_PROVIDER_MODELS_KEY, normalized);
                anyChange = true;
            } catch (JSONException e) {
                return false;
            }
        }

        return anyChange;
    }
}
