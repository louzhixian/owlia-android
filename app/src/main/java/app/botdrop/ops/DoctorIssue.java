package app.botdrop.ops;

public class DoctorIssue {

    public final String code;
    public final DoctorIssueSeverity severity;
    public final String title;
    public final String detail;
    public final FixAction suggestedFix;

    public DoctorIssue(String code, DoctorIssueSeverity severity, String title, String detail, FixAction suggestedFix) {
        this.code = code;
        this.severity = severity;
        this.title = title;
        this.detail = detail;
        this.suggestedFix = suggestedFix;
    }
}
