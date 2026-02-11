package app.botdrop.ops;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic diagnostics that do not depend on LLM availability.
 */
public class DoctorEngine {

    public DoctorReport diagnose(JSONObject config, RuntimeProbe runtimeProbe) {
        List<DoctorIssue> issues = new ArrayList<>();
        JSONObject safeConfig = config != null ? config : new JSONObject();

        checkGatewayConfig(safeConfig, issues);
        checkModelConfig(safeConfig, issues);
        checkChannelConfig(safeConfig, issues);
        checkRuntime(runtimeProbe, issues);

        return new DoctorReport(issues);
    }

    private void checkGatewayConfig(JSONObject config, List<DoctorIssue> issues) {
        JSONObject gateway = config.optJSONObject("gateway");
        if (gateway == null) {
            issues.add(new DoctorIssue(
                "CFG_MISSING_GATEWAY",
                DoctorIssueSeverity.ERROR,
                "Missing gateway config",
                "gateway object is missing from openclaw.json",
                FixAction.ENSURE_GATEWAY_OBJECT
            ));
            return;
        }

        String mode = gateway.optString("mode", "").trim();
        if (mode.isEmpty() || !"local".equals(mode)) {
            issues.add(new DoctorIssue(
                "CFG_INVALID_GATEWAY_MODE",
                DoctorIssueSeverity.WARNING,
                "Gateway mode not local",
                "gateway.mode should be \"local\" for on-device BotDrop setup",
                FixAction.ENSURE_GATEWAY_MODE_LOCAL
            ));
        }

        JSONObject auth = gateway.optJSONObject("auth");
        String token = auth != null ? auth.optString("token", "").trim() : "";
        if (token.isEmpty()) {
            issues.add(new DoctorIssue(
                "CFG_MISSING_GATEWAY_AUTH_TOKEN",
                DoctorIssueSeverity.ERROR,
                "Missing gateway auth token",
                "gateway.auth.token is required by OpenClaw gateway API",
                FixAction.ENSURE_GATEWAY_AUTH_TOKEN
            ));
        }
    }

    private void checkModelConfig(JSONObject config, List<DoctorIssue> issues) {
        JSONObject agents = config.optJSONObject("agents");
        JSONObject defaults = agents != null ? agents.optJSONObject("defaults") : null;
        JSONObject model = defaults != null ? defaults.optJSONObject("model") : null;
        String primary = model != null ? model.optString("primary", "").trim() : "";
        if (primary.isEmpty()) {
            issues.add(new DoctorIssue(
                "CFG_MISSING_MODEL_PRIMARY",
                DoctorIssueSeverity.ERROR,
                "Missing default model",
                "agents.defaults.model.primary is missing",
                FixAction.ENSURE_DEFAULT_MODEL_PRIMARY
            ));
        }
    }

    private void checkChannelConfig(JSONObject config, List<DoctorIssue> issues) {
        JSONObject channels = config.optJSONObject("channels");
        if (channels == null || channels.length() == 0) {
            issues.add(new DoctorIssue(
                "CFG_NO_CHANNEL_CONFIGURED",
                DoctorIssueSeverity.WARNING,
                "No channel configured",
                "No telegram/discord channel is configured yet",
                null
            ));
            return;
        }

        JSONObject plugins = config.optJSONObject("plugins");
        JSONObject entries = plugins != null ? plugins.optJSONObject("entries") : null;
        if (entries == null) {
            issues.add(new DoctorIssue(
                "CFG_MISSING_PLUGIN_ENTRIES",
                DoctorIssueSeverity.WARNING,
                "Missing plugin entries",
                "plugins.entries is missing, channel plugins may not load",
                FixAction.ENSURE_CHANNEL_PLUGIN_ENTRIES
            ));
        }
    }

    private void checkRuntime(RuntimeProbe probe, List<DoctorIssue> issues) {
        if (probe == null) return;

        if (!probe.gatewayProcessRunning) {
            issues.add(new DoctorIssue(
                "RT_GATEWAY_NOT_RUNNING",
                DoctorIssueSeverity.CRITICAL,
                "Gateway process is not running",
                "Gateway process is stopped or crashed",
                null
            ));
            return;
        }

        if (!probe.gatewayHttpReachable) {
            issues.add(new DoctorIssue(
                "RT_GATEWAY_UNREACHABLE",
                DoctorIssueSeverity.ERROR,
                "Gateway not reachable via HTTP",
                "Gateway process exists but localhost endpoint is unreachable",
                null
            ));
        }

        String errors = probe.recentGatewayErrors == null ? "" : probe.recentGatewayErrors.trim();
        if (!errors.isEmpty()) {
            issues.add(new DoctorIssue(
                "RT_GATEWAY_LOG_ERRORS",
                DoctorIssueSeverity.WARNING,
                "Recent gateway errors found",
                errors,
                null
            ));
        }
    }
}
