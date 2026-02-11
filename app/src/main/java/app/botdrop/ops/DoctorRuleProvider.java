package app.botdrop.ops;

import org.json.JSONObject;

import java.util.List;

public interface DoctorRuleProvider {
    List<DoctorIssue> collect(JSONObject config, RuntimeProbe runtimeProbe);
}
