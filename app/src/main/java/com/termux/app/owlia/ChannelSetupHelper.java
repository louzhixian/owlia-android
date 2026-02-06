package com.termux.app.owlia;

import android.util.Base64;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper for channel setup:
 * - Verify setup codes via @BotDropSetupBot API
 * - Decode legacy setup codes from @OwliaSetupBot
 * - Write channel configuration to openclaw.json
 */
public class ChannelSetupHelper {

    private static final String LOG_TAG = "ChannelSetupHelper";
    
    // Setup Bot API URL - can be overridden via system property
    private static final String SETUP_BOT_API_URL = System.getProperty(
        "botdrop.setup.api.url",
        "https://setup.botdrop.app"  // TODO: Update with actual URL when deployed
    );
    
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Callback interface for async setup code verification
     */
    public interface SetupCodeCallback {
        void onSuccess(SetupCodeData data);
        void onError(String error);
    }

    /**
     * Data extracted from setup code
     */
    public static class SetupCodeData {
        public final String platform;
        public final String botToken;
        public final String ownerId;

        public SetupCodeData(String platform, String botToken, String ownerId) {
            this.platform = platform;
            this.botToken = botToken;
            this.ownerId = ownerId;
        }
    }

    /**
     * Verify setup code via BotDrop Setup Bot API
     * 
     * @param code Setup code (BD-XXXX-YYYY)
     * @param platform "telegram" or "discord"
     * @param callback Callback for result
     */
    public static void verifySetupCode(String code, String platform, SetupCodeCallback callback) {
        executor.execute(() -> {
            try {
                URL url = new URL(SETUP_BOT_API_URL + "/api/verify-code");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                // Send request body
                JSONObject requestBody = new JSONObject();
                requestBody.put("code", code);
                requestBody.put("platform", platform);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.toString().getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();
                
                // Read response
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                        responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream()
                    )
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                JSONObject json = new JSONObject(response.toString());

                if (responseCode == 200 && json.optBoolean("ok", false)) {
                    // Success - extract data
                    JSONObject telegramData = json.optJSONObject("telegram");
                    if (telegramData != null) {
                        String chatId = String.valueOf(telegramData.optLong("chatId", 0));
                        String botToken = telegramData.optString("token", "");
                        
                        // Note: The setup bot returns chatId, but we need the bot token
                        // which the user should have configured in the setup bot flow
                        // For now, return chatId as ownerId
                        SetupCodeData data = new SetupCodeData(platform, botToken, chatId);
                        callback.onSuccess(data);
                    } else {
                        callback.onError("Invalid response from setup server");
                    }
                } else {
                    String error = json.optString("error", "Unknown error");
                    callback.onError(error);
                }

            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to verify setup code: " + e.getMessage());
                callback.onError("Connection failed: " + e.getMessage());
            }
        });
    }

    /**
     * Decode setup code from @OwliaSetupBot
     * Format: OWLIA-{platform}-{base64_json}
     * 
     * Platform codes:
     * - tg = Telegram
     * - dc = Discord
     * 
     * Base64 JSON structure:
     * {
     *   "v": 1,
     *   "platform": "telegram" | "discord",
     *   "bot_token": "...",
     *   "owner_id": "...",
     *   "created_at": 1234567890
     * }
     * 
     * @param setupCode The setup code from @OwliaSetupBot
     * @return SetupCodeData or null if invalid
     */
    public static SetupCodeData decodeSetupCode(String setupCode) {
        try {
            // Split: OWLIA-tg-xxxxx or OWLIA-dc-xxxxx
            String[] parts = setupCode.split("-", 3);
            if (parts.length != 3 || !parts[0].equals("OWLIA")) {
                Logger.logError(LOG_TAG, "Invalid setup code format");
                return null;
            }

            String platformCode = parts[1];
            String base64Payload = parts[2];

            // Decode base64
            byte[] decodedBytes = Base64.decode(base64Payload, Base64.DEFAULT);
            String jsonString = new String(decodedBytes);

            Logger.logDebug(LOG_TAG, "Decoded setup code JSON: " + jsonString);

            // Parse JSON
            JSONObject json = new JSONObject(jsonString);

            String platform = json.optString("platform", null);
            String botToken = json.optString("bot_token", null);
            String ownerId = json.optString("owner_id", null);

            // Fallback: infer platform from code if not in JSON
            if (platform == null) {
                platform = platformCode.equals("tg") ? "telegram" :
                          platformCode.equals("dc") ? "discord" : null;
            }

            if (platform == null || botToken == null || ownerId == null) {
                Logger.logError(LOG_TAG, "Missing required fields in setup code");
                return null;
            }

            Logger.logInfo(LOG_TAG, "Setup code decoded: platform=" + platform + ", ownerId=" + ownerId);
            return new SetupCodeData(platform, botToken, ownerId);

        } catch (IllegalArgumentException | JSONException e) {
            Logger.logError(LOG_TAG, "Failed to decode setup code: " + e.getMessage());
            return null;
        }
    }

    /**
     * Write channel configuration to openclaw.json
     * 
     * For Telegram:
     * {
     *   "channels": {
     *     "telegram": {
     *       "accounts": {
     *         "default": { "token": "BOT_TOKEN" }
     *       },
     *       "bindings": [{ "account": "default" }],
     *       "ownerIds": ["OWNER_ID"]
     *     }
     *   }
     * }
     * 
     * For Discord:
     * {
     *   "channels": {
     *     "discord": {
     *       "token": "BOT_TOKEN",
     *       "ownerIds": ["OWNER_ID"]
     *     }
     *   }
     * }
     * 
     * @param platform "telegram" or "discord"
     * @param botToken Bot token
     * @param ownerId User ID who owns/controls the bot
     * @return true if successful
     */
    public static boolean writeChannelConfig(String platform, String botToken, String ownerId) {
        try {
            JSONObject config = OwliaConfig.readConfig();

            // Create channels object if not exists
            if (!config.has("channels")) {
                config.put("channels", new JSONObject());
            }

            JSONObject channels = config.getJSONObject("channels");

            if (platform.equals("telegram")) {
                // Telegram structure
                JSONObject telegram = new JSONObject();

                // accounts.default.token
                JSONObject accounts = new JSONObject();
                JSONObject defaultAccount = new JSONObject();
                defaultAccount.put("token", botToken);
                accounts.put("default", defaultAccount);
                telegram.put("accounts", accounts);

                // bindings: [{ account: "default" }]
                JSONArray bindings = new JSONArray();
                JSONObject binding = new JSONObject();
                binding.put("account", "default");
                bindings.put(binding);
                telegram.put("bindings", bindings);

                // ownerIds: ["OWNER_ID"]
                JSONArray ownerIds = new JSONArray();
                ownerIds.put(ownerId);
                telegram.put("ownerIds", ownerIds);

                channels.put("telegram", telegram);

            } else if (platform.equals("discord")) {
                // Discord structure
                JSONObject discord = new JSONObject();

                // token
                discord.put("token", botToken);

                // ownerIds: ["OWNER_ID"]
                JSONArray ownerIds = new JSONArray();
                ownerIds.put(ownerId);
                discord.put("ownerIds", ownerIds);

                channels.put("discord", discord);

            } else {
                Logger.logError(LOG_TAG, "Unsupported platform: " + platform);
                return false;
            }

            Logger.logInfo(LOG_TAG, "Writing channel config for platform: " + platform);
            return OwliaConfig.writeConfig(config);

        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to write channel config: " + e.getMessage());
            return false;
        }
    }
}
