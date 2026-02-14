package app.botdrop;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;

import android.text.TextUtils;

import java.util.Collections;

/**
 * Configuration template cache using SharedPreferences.
 * Purpose: Avoid users re-entering configuration information.
 * Not for security - just convenience caching.
 */
public class ConfigTemplateCache {

    private static final String LOG_TAG = "ConfigTemplateCache";
    private static final String PREFS_NAME = "botdrop_config_template";
    private static final int CONFIG_VERSION = 2;

    // Keys
    private static final String KEY_VERSION = "version";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_TG_BOT_TOKEN = "tg_bot_token";
    private static final String KEY_TG_USER_ID = "tg_user_id";

    /**
     * Save configuration template to cache.
     */
    public static void saveTemplate(Context ctx, ConfigTemplate template) {
        if (template == null) {
            Logger.logError(LOG_TAG, "Cannot save null template");
            return;
        }

        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            editor.putInt(KEY_VERSION, CONFIG_VERSION);
            editor.putString(KEY_PROVIDER, template.provider);
            editor.putString(KEY_MODEL, template.model);
            editor.putString(KEY_API_KEY, template.apiKey);
            editor.putString(KEY_BASE_URL, template.baseUrl);
            editor.putString(KEY_TG_BOT_TOKEN, template.tgBotToken);
            editor.putString(KEY_TG_USER_ID, template.tgUserId);

            editor.apply();
            Logger.logInfo(LOG_TAG, "Config template saved: " + template.toString());

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to save template: " + e.getMessage());
        }
    }

    /**
     * Load configuration template from cache.
     * @return ConfigTemplate or null if not found or invalid
     */
    public static ConfigTemplate loadTemplate(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Check version
            int version = prefs.getInt(KEY_VERSION, 0);
            if (version == 0) {
                Logger.logDebug(LOG_TAG, "No config template found");
                return null;
            }

            if (version < CONFIG_VERSION) {
                Logger.logInfo(LOG_TAG, "Config version outdated, migrating...");
                // Future: add migration logic here
            }

            ConfigTemplate template = new ConfigTemplate();
            template.provider = prefs.getString(KEY_PROVIDER, null);
            template.model = prefs.getString(KEY_MODEL, null);
            template.apiKey = prefs.getString(KEY_API_KEY, null);
            template.baseUrl = prefs.getString(KEY_BASE_URL, null);
            template.tgBotToken = prefs.getString(KEY_TG_BOT_TOKEN, null);
            template.tgUserId = prefs.getString(KEY_TG_USER_ID, null);

            Logger.logInfo(LOG_TAG, "Config template loaded: " + template.toString());
            return template;

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load template: " + e.getMessage());
            clearTemplate(ctx); // Clear corrupted data
            return null;
        }
    }

    /**
     * Check if a configuration template exists in cache.
     */
    public static boolean hasTemplate(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int version = prefs.getInt(KEY_VERSION, 0);
            return version > 0;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to check template: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clear configuration template from cache.
     */
    public static void clearTemplate(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            Logger.logInfo(LOG_TAG, "Config template cleared");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to clear template: " + e.getMessage());
        }
    }

    /**
     * Apply template to OpenClaw configuration files.
     * @return true if successful
     */
    public static boolean applyTemplate(Context ctx, ConfigTemplate template) {
        if (template == null || !template.isValid()) {
            Logger.logError(LOG_TAG, "Cannot apply invalid template");
            return false;
        }

        try {
            String modelName = template.model;
            if (modelName != null && modelName.startsWith(template.provider + "/")) {
                modelName = modelName.substring((template.provider + "/").length());
            }

            // Set provider and model
            boolean success = BotDropConfig.setProvider(
                template.provider,
                modelName,
                template.baseUrl,
                !TextUtils.isEmpty(modelName) ? Collections.singletonList(modelName) : null
            );
            if (!success) {
                Logger.logError(LOG_TAG, "Failed to set provider/model");
                return false;
            }

            // Set API key
            success = BotDropConfig.setApiKey(
                template.provider,
                modelName,
                template.apiKey,
                !TextUtils.isEmpty(template.baseUrl) ? template.baseUrl : null
            );
            if (!success) {
                Logger.logError(LOG_TAG, "Failed to set API key");
                return false;
            }

            // Set Telegram config if present
            if (template.tgBotToken != null && !template.tgBotToken.isEmpty()) {
                success = ChannelSetupHelper.writeChannelConfig("telegram", template.tgBotToken, template.tgUserId);
                if (!success) {
                    Logger.logError(LOG_TAG, "Failed to set Telegram config");
                    return false;
                }
            }

            Logger.logInfo(LOG_TAG, "Config template applied successfully");
            return true;

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to apply template: " + e.getMessage());
            return false;
        }
    }
}
