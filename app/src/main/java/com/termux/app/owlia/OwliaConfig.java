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
 * Handles openclaw.json at ~/.config/openclaw/openclaw.json
 */
public class OwliaConfig {
    
    private static final String LOG_TAG = "OwliaConfig";
    private static final String CONFIG_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.config/openclaw";
    private static final String CONFIG_FILE = CONFIG_DIR + "/openclaw.json";
    
    /**
     * Read the current configuration
     * @return JSONObject of config, or empty config if not found
     */
    public static JSONObject readConfig() {
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
    
    /**
     * Write configuration to file
     * @param config JSONObject to write
     * @return true if successful
     */
    public static boolean writeConfig(JSONObject config) {
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
            Logger.logInfo(LOG_TAG, "Config written successfully");
            return true;
            
        } catch (IOException | JSONException e) {
            Logger.logError(LOG_TAG, "Failed to write config: " + e.getMessage());
            return false;
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
                defaults.put("workspace", "~/owlia");
            }
            
            return writeConfig(config);
            
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to set provider: " + e.getMessage());
            return false;
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
