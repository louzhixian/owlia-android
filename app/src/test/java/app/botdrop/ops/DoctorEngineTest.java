package app.botdrop.ops;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DoctorEngineTest {

    @Test
    public void diagnose_missingCoreConfig_reportsErrors() {
        DoctorEngine engine = new DoctorEngine();
        DoctorReport report = engine.diagnose(new JSONObject(), new RuntimeProbe(false, false, ""));

        assertTrue(report.hasErrors());
        assertTrue(hasIssue(report, "CFG_MISSING_GATEWAY"));
        assertTrue(hasIssue(report, "CFG_MISSING_MODEL_PRIMARY"));
        assertTrue(hasIssue(report, "RT_GATEWAY_NOT_RUNNING"));
    }

    @Test
    public void diagnose_healthyConfig_noErrors() throws Exception {
        JSONObject cfg = new JSONObject();
        cfg.put("gateway", new JSONObject()
            .put("mode", "local")
            .put("auth", new JSONObject().put("token", "token-123")));
        cfg.put("agents", new JSONObject()
            .put("defaults", new JSONObject()
                .put("workspace", "~/botdrop")
                .put("model", new JSONObject().put("primary", "anthropic/claude-sonnet-4-5"))));
        cfg.put("channels", new JSONObject().put("telegram", new JSONObject().put("enabled", true)));
        cfg.put("plugins", new JSONObject()
            .put("entries", new JSONObject().put("telegram", new JSONObject().put("enabled", true))));

        DoctorEngine engine = new DoctorEngine();
        DoctorReport report = engine.diagnose(cfg, new RuntimeProbe(true, true, ""));

        assertFalse(report.hasErrors());
    }

    private boolean hasIssue(DoctorReport report, String code) {
        for (DoctorIssue issue : report.issues) {
            if (code.equals(issue.code)) return true;
        }
        return false;
    }
}
