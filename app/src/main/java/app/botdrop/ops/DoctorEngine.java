package app.botdrop.ops;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Aggregates issue reports from multiple rule domains.
 */
public class DoctorEngine {

    private final List<DoctorRuleProvider> ruleProviders;

    public DoctorEngine() {
        this(Arrays.asList(
            new OpenClawAgentRuleProvider(),
            new BotDropInvariantRuleProvider()
        ));
    }

    public DoctorEngine(List<DoctorRuleProvider> ruleProviders) {
        this.ruleProviders = ruleProviders;
    }

    public DoctorReport diagnose(JSONObject config, RuntimeProbe runtimeProbe) {
        List<DoctorIssue> issues = new ArrayList<>();
        JSONObject safeConfig = config != null ? config : new JSONObject();

        for (DoctorRuleProvider provider : ruleProviders) {
            if (provider == null) continue;
            List<DoctorIssue> provided = provider.collect(safeConfig, runtimeProbe);
            if (provided == null || provided.isEmpty()) continue;
            issues.addAll(provided);
        }

        return new DoctorReport(issues);
    }
}
