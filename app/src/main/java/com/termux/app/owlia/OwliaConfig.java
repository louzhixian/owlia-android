package com.termux.app.owlia;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Helper class for reading and writing OpenClaw configuration.
 * Handles openclaw.json at ~/.openclaw/openclaw.json
 * 
 * Thread-safe: All file operations are synchronized.
 */
public class OwliaConfig {
    
    private static final String LOG_TAG = "BotDropConfig";
    private static final String CONFIG_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw";
    private static final String CONFIG_FILE = CONFIG_DIR + "/openclaw.json";
    
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
        try {
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
            
            // Set model in format "provider/model"
            String modelString = provider + "/" + model;
            defaults.put("model", modelString);
            
            // Set workspace if not already set
            if (!defaults.has("workspace")) {
                defaults.put("workspace", "~/botdrop");
            }

            // Ensure gateway.mode=local is set (required for gateway run on Android)
            if (!config.has("gateway")) {
                config.put("gateway", new JSONObject());
            }
            JSONObject gateway = config.getJSONObject("gateway");
            if (!gateway.has("mode")) {
                gateway.put("mode", "local");
            }

            return writeConfig(config);
            
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to set provider: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Set the API key/token for a provider
     * Writes to the appropriate environment variable or config section
     * @param provider Provider ID
     * @param credential API key or setup token
     * @return true if successful
     */
    public static boolean setApiKey(String provider, String credential) {
        try {
            JSONObject config = readConfig();

            // Store auth credentials in providers section
            if (!config.has("providers")) {
                config.put("providers", new JSONObject());
            }

            JSONObject providers = config.getJSONObject("providers");
            if (!providers.has(provider)) {
                providers.put(provider, new JSONObject());
            }

            JSONObject providerConfig = providers.getJSONObject(provider);

            // Set the appropriate key based on provider
            String envKey = getEnvKeyForProvider(provider);
            providerConfig.put("apiKey", credential);

            // Also write to env file for openclaw to pick up
            writeEnvFile(provider, envKey, credential);

            return writeConfig(config);

        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to set API key: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the environment variable name for a provider's API key
     */
    private static String getEnvKeyForProvider(String provider) {
        switch (provider) {
            case "anthropic": return "ANTHROPIC_API_KEY";
            case "openai": return "OPENAI_API_KEY";
            case "google": return "GOOGLE_API_KEY";
            case "openrouter": return "OPENROUTER_API_KEY";
            case "kimi": return "KIMI_API_KEY";
            case "minimax": return "MINIMAX_API_KEY";
            case "venice": return "VENICE_API_KEY";
            case "chutes": return "CHUTES_API_KEY";
            default: return provider.toUpperCase() + "_API_KEY";
        }
    }

    /**
     * Write API key to .env file that openclaw reads
     */
    private static void writeEnvFile(String provider, String envKey, String credential) {
        synchronized (CONFIG_LOCK) {
            String envDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw";
            File envFile = new File(envDir + "/.env");

            try {
                // Read existing env content
                StringBuilder existing = new StringBuilder();
                if (envFile.exists()) {
                    try (FileReader reader = new FileReader(envFile)) {
                        char[] buffer = new char[1024];
                        int read;
                        while ((read = reader.read(buffer)) != -1) {
                            existing.append(buffer, 0, read);
                        }
                    }
                }

                // Remove existing entry for this key if present
                String content = existing.toString();
                String[] lines = content.split("\n");
                StringBuilder newContent = new StringBuilder();
                for (String line : lines) {
                    if (!line.startsWith(envKey + "=") && line.length() > 0) {
                        newContent.append(line).append("\n");
                    }
                }

                // Append new key
                newContent.append(envKey).append("=").append(credential).append("\n");

                // Write
                new File(envDir).mkdirs();
                try (FileWriter writer = new FileWriter(envFile)) {
                    writer.write(newContent.toString());
                }
                
                // Set file permissions to owner-only (contains API keys)
                envFile.setReadable(false, false);
                envFile.setReadable(true, true);
                envFile.setWritable(false, false);
                envFile.setWritable(true, true);

                Logger.logInfo(LOG_TAG, "Env file updated with " + envKey);

            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to write env file: " + e.getMessage());
            }
        }
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
            // Check if it has agents.defaults.model set
            if (config.has("agents")) {
                JSONObject agents = config.getJSONObject("agents");
                if (agents.has("defaults")) {
                    JSONObject defaults = agents.getJSONObject("defaults");
                    return defaults.has("model");
                }
            }
            return false;
            
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to check config: " + e.getMessage());
            return false;
        }
    }
}
