package app.botdrop.ops;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * BotDrop integration-specific invariants.
 */
public class BotDropInvariantRuleProvider implements DoctorRuleProvider {

    private static final RuleSource SOURCE =
        new RuleSource(RuleSourceType.BOTDROP_CODE, "botdrop-ops-v1", "botdrop-invariants");
    private final String agentVersion;

    public BotDropInvariantRuleProvider() {
        this("unknown");
    }

    public BotDropInvariantRuleProvider(String agentVersion) {
        this.agentVersion = (agentVersion == null || agentVersion.trim().isEmpty())
            ? "unknown"
            : agentVersion.trim();
    }

    @Override
    public List<DoctorIssue> collect(JSONObject config, RuntimeProbe runtimeProbe) {
        List<DoctorIssue> issues = new ArrayList<>();
        JSONObject safeConfig = config != null ? config : new JSONObject();

        checkGatewayMode(safeConfig, issues);
        checkChannelPluginEntries(safeConfig, issues);
        checkRuntime(runtimeProbe, issues);

        return issues;
    }

    private void checkGatewayMode(JSONObject config, List<DoctorIssue> issues) {
        JSONObject gateway = config.optJSONObject("gateway");
        if (gateway == null) return;

        String mode = gateway.optString("mode", "").trim();
        if (mode.isEmpty() || !"local".equals(mode)) {
            issues.add(issue(
                "CFG_INVALID_GATEWAY_MODE",
                DoctorIssueSeverity.WARNING,
                "Gateway mode not local",
                "gateway.mode should be \"local\" for on-device BotDrop setup",
                FixAction.ENSURE_GATEWAY_MODE_LOCAL
            ));
        }
    }

    private void checkChannelPluginEntries(JSONObject config, List<DoctorIssue> issues) {
        JSONObject channels = config.optJSONObject("channels");
        if (channels == null || channels.length() == 0) {
            issues.add(issue(
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
            issues.add(issue(
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
            issues.add(issue(
                "RT_GATEWAY_NOT_RUNNING",
                DoctorIssueSeverity.CRITICAL,
                "Gateway process is not running",
                "Gateway process is stopped or crashed",
                null
            ));
            return;
        }

        if (!probe.gatewayHttpReachable) {
            issues.add(issue(
                "RT_GATEWAY_UNREACHABLE",
                DoctorIssueSeverity.ERROR,
                "Gateway not reachable via HTTP",
                "Gateway process exists but localhost endpoint is unreachable",
                null
            ));
        }

        String errors = probe.recentGatewayErrors == null ? "" : probe.recentGatewayErrors.trim();
        if (!errors.isEmpty()) {
            issues.add(issue(
                "RT_GATEWAY_LOG_ERRORS",
                DoctorIssueSeverity.WARNING,
                "Recent gateway errors found",
                errors,
                null
            ));
        }
    }

    private DoctorIssue issue(String code, DoctorIssueSeverity severity, String title, String detail, FixAction fixAction) {
        return new DoctorIssue(
            code,
            severity,
            title,
            detail,
            fixAction,
            RuleDomain.BOTDROP_INVARIANTS,
            AgentType.OPENCLAW,
            agentVersion,
            SOURCE
        );
    }
}
