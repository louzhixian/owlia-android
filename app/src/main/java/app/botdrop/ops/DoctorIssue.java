package app.botdrop.ops;

public class DoctorIssue {

    public final String code;
    public final DoctorIssueSeverity severity;
    public final String title;
    public final String detail;
    public final FixAction suggestedFix;
    public final RuleDomain ruleDomain;
    public final AgentType agentType;
    public final String agentVersion;
    public final RuleSource ruleSource;

    public DoctorIssue(String code, DoctorIssueSeverity severity, String title, String detail, FixAction suggestedFix) {
        this(code, severity, title, detail, suggestedFix,
            RuleDomain.BOTDROP_INVARIANTS, AgentType.UNKNOWN, "unknown",
            new RuleSource(RuleSourceType.LOCAL_FALLBACK, "unspecified", "legacy"));
    }

    public DoctorIssue(String code, DoctorIssueSeverity severity, String title, String detail,
                       FixAction suggestedFix, RuleDomain ruleDomain, AgentType agentType,
                       String agentVersion, RuleSource ruleSource) {
        this.code = code;
        this.severity = severity;
        this.title = title;
        this.detail = detail;
        this.suggestedFix = suggestedFix;
        this.ruleDomain = ruleDomain;
        this.agentType = agentType;
        this.agentVersion = agentVersion;
        this.ruleSource = ruleSource;
    }
}
