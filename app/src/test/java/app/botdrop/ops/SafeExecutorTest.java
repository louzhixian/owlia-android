package app.botdrop.ops;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SafeExecutorTest {

    @Test
    public void applyFixes_populatesRequiredFields() throws Exception {
        InMemoryConfigRepository repo = new InMemoryConfigRepository(new JSONObject());
        FakeBackupStore backupStore = new FakeBackupStore();
        SafeExecutor executor = new SafeExecutor(repo, backupStore);

        SafeExecutor.ExecutionResult result = executor.applyFixes(Arrays.asList(
            FixAction.ENSURE_GATEWAY_OBJECT,
            FixAction.ENSURE_GATEWAY_MODE_LOCAL,
            FixAction.ENSURE_GATEWAY_AUTH_TOKEN,
            FixAction.ENSURE_DEFAULT_MODEL_PRIMARY
        ));

        assertTrue(result.success);
        assertNotNull(result.backupPath);
        JSONObject cfg = repo.read();
        assertEquals("local", cfg.getJSONObject("gateway").getString("mode"));
        assertFalse(cfg.getJSONObject("gateway").getJSONObject("auth").optString("token", "").isEmpty());
        assertFalse(cfg.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .optString("primary", "")
            .isEmpty());
    }

    @Test
    public void rollback_restoresBackup() throws Exception {
        JSONObject start = new JSONObject().put("gateway", new JSONObject().put("mode", "local"));
        InMemoryConfigRepository repo = new InMemoryConfigRepository(start);
        FakeBackupStore backupStore = new FakeBackupStore();
        SafeExecutor executor = new SafeExecutor(repo, backupStore);

        SafeExecutor.ExecutionResult result = executor.applyFixes(Arrays.asList(
            FixAction.ENSURE_GATEWAY_OBJECT,
            FixAction.ENSURE_GATEWAY_AUTH_TOKEN,
            FixAction.ENSURE_DEFAULT_MODEL_PRIMARY
        ));
        assertTrue(result.success);

        repo.write(new JSONObject().put("corrupted", true));
        assertTrue(executor.rollback(result.backupPath));
        assertEquals("local", repo.read().getJSONObject("gateway").getString("mode"));
    }

    @Test
    public void previewFixes_reportsPlannedActions() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository(new JSONObject());
        FakeBackupStore backupStore = new FakeBackupStore();
        SafeExecutor executor = new SafeExecutor(repo, backupStore);

        SafeExecutor.PreviewResult preview = executor.previewFixes(Arrays.asList(
            FixAction.ENSURE_GATEWAY_OBJECT,
            FixAction.ENSURE_GATEWAY_MODE_LOCAL,
            FixAction.ENSURE_GATEWAY_AUTH_TOKEN
        ));

        assertTrue(preview.success);
        assertTrue(preview.plannedActions.contains(FixAction.ENSURE_GATEWAY_MODE_LOCAL));
        assertTrue(preview.message.contains("Planned actions"));
    }

    private static class InMemoryConfigRepository implements ConfigRepository {
        private JSONObject config;

        InMemoryConfigRepository(JSONObject initial) {
            this.config = cloneJson(initial);
        }

        @Override
        public JSONObject read() {
            return cloneJson(config);
        }

        @Override
        public boolean write(JSONObject config) {
            this.config = cloneJson(config);
            return true;
        }
    }

    private static class FakeBackupStore extends ConfigBackupStore {
        private final Map<String, JSONObject> map = new HashMap<>();
        private int seq = 0;

        @Override
        public String createBackup(JSONObject config) {
            String key = "fake://" + (++seq);
            map.put(key, cloneJson(config));
            return key;
        }

        @Override
        public JSONObject readBackup(String backupPath) {
            JSONObject value = map.get(backupPath);
            return value == null ? null : cloneJson(value);
        }
    }

    private static JSONObject cloneJson(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
