package app.botdrop.ops;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Applies config fixes in a transactional pattern:
 * backup -> mutate -> validate -> persist.
 */
public class SafeExecutor {

    public static class ExecutionResult {
        public final boolean success;
        public final String transactionId;
        public final String backupPath;
        public final String message;
        public final List<FixAction> appliedActions;

        public ExecutionResult(boolean success, String transactionId, String backupPath, String message, List<FixAction> appliedActions) {
            this.success = success;
            this.transactionId = transactionId;
            this.backupPath = backupPath;
            this.message = message;
            this.appliedActions = appliedActions;
        }
    }

    private final ConfigRepository configRepository;
    private final ConfigBackupStore backupStore;

    public SafeExecutor(ConfigRepository configRepository, ConfigBackupStore backupStore) {
        this.configRepository = configRepository;
        this.backupStore = backupStore;
    }

    public ExecutionResult applyFixes(List<FixAction> actions) {
        String txId = UUID.randomUUID().toString();
        if (actions == null || actions.isEmpty()) {
            return new ExecutionResult(false, txId, null, "No actions selected", new ArrayList<>());
        }

        JSONObject original = configRepository.read();
        if (original == null) original = new JSONObject();

        String backupPath = backupStore.createBackup(original);
        JSONObject updated = cloneJson(original);
        List<FixAction> applied = new ArrayList<>();

        for (FixAction action : actions) {
            if (applyFix(updated, action)) {
                applied.add(action);
            }
        }

        if (!hasRequiredFields(updated)) {
            return new ExecutionResult(false, txId, backupPath, "Validation failed after applying fixes", applied);
        }

        boolean writeOk = configRepository.write(updated);
        if (!writeOk) {
            return new ExecutionResult(false, txId, backupPath, "Failed to write openclaw.json", applied);
        }

        return new ExecutionResult(true, txId, backupPath, "Fixes applied", applied);
    }

    public boolean rollback(String backupPath) {
        JSONObject backup = backupStore.readBackup(backupPath);
        if (backup == null) return false;
        return configRepository.write(backup);
    }

    private boolean applyFix(JSONObject config, FixAction action) {
        try {
            switch (action) {
                case ENSURE_GATEWAY_OBJECT:
                    ensureGatewayObject(config);
                    return true;
                case ENSURE_GATEWAY_MODE_LOCAL:
                    ensureGatewayObject(config).put("mode", "local");
                    return true;
                case ENSURE_GATEWAY_AUTH_TOKEN:
                    JSONObject gateway = ensureGatewayObject(config);
                    JSONObject auth = gateway.optJSONObject("auth");
                    if (auth == null) auth = new JSONObject();
                    if (auth.optString("token", "").trim().isEmpty()) {
                        auth.put("token", UUID.randomUUID().toString());
                    }
                    gateway.put("auth", auth);
                    return true;
                case ENSURE_DEFAULT_MODEL_PRIMARY:
                    ensureModelPrimary(config);
                    return true;
                case ENSURE_CHANNEL_PLUGIN_ENTRIES:
                    ensurePluginEntriesForChannels(config);
                    return true;
                default:
                    return false;
            }
        } catch (JSONException e) {
            return false;
        }
    }

    private JSONObject ensureGatewayObject(JSONObject config) throws JSONException {
        JSONObject gateway = config.optJSONObject("gateway");
        if (gateway == null) {
            gateway = new JSONObject();
            config.put("gateway", gateway);
        }
        return gateway;
    }

    private void ensureModelPrimary(JSONObject config) throws JSONException {
        JSONObject agents = config.optJSONObject("agents");
        if (agents == null) {
            agents = new JSONObject();
            config.put("agents", agents);
        }
        JSONObject defaults = agents.optJSONObject("defaults");
        if (defaults == null) {
            defaults = new JSONObject();
            agents.put("defaults", defaults);
        }
        JSONObject model = defaults.optJSONObject("model");
        if (model == null) {
            model = new JSONObject();
            defaults.put("model", model);
        }
        if (model.optString("primary", "").trim().isEmpty()) {
            model.put("primary", "anthropic/claude-sonnet-4-5");
        }
        if (defaults.optString("workspace", "").trim().isEmpty()) {
            defaults.put("workspace", "~/botdrop");
        }
    }

    private void ensurePluginEntriesForChannels(JSONObject config) throws JSONException {
        JSONObject channels = config.optJSONObject("channels");
        if (channels == null || channels.length() == 0) return;

        JSONObject plugins = config.optJSONObject("plugins");
        if (plugins == null) {
            plugins = new JSONObject();
            config.put("plugins", plugins);
        }
        JSONObject entries = plugins.optJSONObject("entries");
        if (entries == null) {
            entries = new JSONObject();
            plugins.put("entries", entries);
        }

        if (channels.has("telegram")) {
            JSONObject telegram = entries.optJSONObject("telegram");
            if (telegram == null) telegram = new JSONObject();
            telegram.put("enabled", true);
            entries.put("telegram", telegram);
        }
        if (channels.has("discord")) {
            JSONObject discord = entries.optJSONObject("discord");
            if (discord == null) discord = new JSONObject();
            discord.put("enabled", true);
            entries.put("discord", discord);
        }
    }

    private boolean hasRequiredFields(JSONObject config) {
        JSONObject gateway = config.optJSONObject("gateway");
        if (gateway == null) return false;

        String mode = gateway.optString("mode", "");
        if (mode.trim().isEmpty()) return false;

        JSONObject auth = gateway.optJSONObject("auth");
        String token = auth != null ? auth.optString("token", "") : "";
        if (token.trim().isEmpty()) return false;

        JSONObject agents = config.optJSONObject("agents");
        JSONObject defaults = agents != null ? agents.optJSONObject("defaults") : null;
        JSONObject model = defaults != null ? defaults.optJSONObject("model") : null;
        String primary = model != null ? model.optString("primary", "") : "";
        return !primary.trim().isEmpty();
    }

    private JSONObject cloneJson(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
