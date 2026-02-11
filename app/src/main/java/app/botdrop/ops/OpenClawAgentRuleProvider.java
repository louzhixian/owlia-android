package app.botdrop.ops;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent-level rules for OpenClaw config structure.
 * Current source is local fallback until runtime schema/docs sync is implemented.
 */
public class OpenClawAgentRuleProvider implements DoctorRuleProvider {

    private final RuleSource ruleSource;
    private final String agentVersion;

    public OpenClawAgentRuleProvider() {
        this(
            new RuleSource(RuleSourceType.LOCAL_FALLBACK, "openclaw-local-v1", "openclaw-agent-rules"),
            "unknown"
        );
    }

    public OpenClawAgentRuleProvider(RuleSource ruleSource, String agentVersion) {
        this.ruleSource = ruleSource;
        this.agentVersion = (agentVersion == null || agentVersion.trim().isEmpty())
            ? "unknown"
            : agentVersion.trim();
    }

    @Override
    public List<DoctorIssue> collect(JSONObject config, RuntimeProbe runtimeProbe) {
        List<DoctorIssue> issues = new ArrayList<>();
        JSONObject safeConfig = config != null ? config : new JSONObject();

        checkGatewayAuth(safeConfig, issues);
        checkDefaultModel(safeConfig, issues);

        return issues;
    }

    private void checkGatewayAuth(JSONObject config, List<DoctorIssue> issues) {
        JSONObject gateway = config.optJSONObject("gateway");
        if (gateway == null) {
            issues.add(issue(
                "CFG_MISSING_GATEWAY",
                DoctorIssueSeverity.ERROR,
                "Missing gateway config",
                "gateway object is missing from openclaw.json",
                FixAction.ENSURE_GATEWAY_OBJECT
            ));
            return;
        }

        JSONObject auth = gateway.optJSONObject("auth");
        String token = auth != null ? auth.optString("token", "").trim() : "";
        if (token.isEmpty()) {
            issues.add(issue(
                "CFG_MISSING_GATEWAY_AUTH_TOKEN",
                DoctorIssueSeverity.ERROR,
                "Missing gateway auth token",
                "gateway.auth.token is required by OpenClaw gateway API",
                FixAction.ENSURE_GATEWAY_AUTH_TOKEN
            ));
        }
    }

    private void checkDefaultModel(JSONObject config, List<DoctorIssue> issues) {
        JSONObject agents = config.optJSONObject("agents");
        JSONObject defaults = agents != null ? agents.optJSONObject("defaults") : null;
        JSONObject model = defaults != null ? defaults.optJSONObject("model") : null;
        String primary = model != null ? model.optString("primary", "").trim() : "";
        if (primary.isEmpty()) {
            issues.add(issue(
                "CFG_MISSING_MODEL_PRIMARY",
                DoctorIssueSeverity.ERROR,
                "Missing default model",
                "agents.defaults.model.primary is missing",
                FixAction.ENSURE_DEFAULT_MODEL_PRIMARY
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
            RuleDomain.AGENT_RULES,
            AgentType.OPENCLAW,
            agentVersion,
            ruleSource
        );
    }
}
