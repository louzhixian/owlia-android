package app.botdrop.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DoctorReport {

    public final List<DoctorIssue> issues;

    public DoctorReport(List<DoctorIssue> issues) {
        if (issues == null) {
            this.issues = Collections.emptyList();
        } else {
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }
    }

    public boolean hasErrors() {
        for (DoctorIssue issue : issues) {
            if (issue.severity == DoctorIssueSeverity.ERROR || issue.severity == DoctorIssueSeverity.CRITICAL) {
                return true;
            }
        }
        return false;
    }

    public List<FixAction> collectSuggestedFixes() {
        List<FixAction> fixes = new ArrayList<>();
        for (DoctorIssue issue : issues) {
            if (issue.suggestedFix == null) continue;
            if (fixes.contains(issue.suggestedFix)) continue;
            fixes.add(issue.suggestedFix);
        }
        return fixes;
    }
}
