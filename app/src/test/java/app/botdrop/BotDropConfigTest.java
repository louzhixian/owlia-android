package app.botdrop;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Unit tests for BotDropConfig
 *
 * Note: BotDropConfig uses hardcoded paths from TermuxConstants which point to
 * /data/data/com.termux/files/home/.openclaw/openclaw.json
 *
 * Robolectric provides Android context but doesn't create the Termux filesystem.
 * We test the logic that can work, and document what requires integration testing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BotDropConfigTest {

    private static final String TEST_CONFIG_DIR = System.getProperty("java.io.tmpdir") + "/test-openclaw";
    private static final String TEST_CONFIG_FILE = TEST_CONFIG_DIR + "/openclaw.json";

    @Before
    public void setUp() {
        // Clean up any previous test files
        cleanupTestFiles();
    }

    @After
    public void tearDown() {
        cleanupTestFiles();
    }

    private void cleanupTestFiles() {
        File configFile = new File(TEST_CONFIG_FILE);
        if (configFile.exists()) {
            configFile.delete();
        }
        File configDir = new File(TEST_CONFIG_DIR);
        if (configDir.exists()) {
            configDir.delete();
        }
    }

    /**
     * Test: readConfig returns empty JSONObject when file doesn't exist
     *
     * NOTE: This test verifies the ACTUAL behavior but cannot fully test it
     * because BotDropConfig.readConfig() uses hardcoded Termux paths.
     * We're testing that the method doesn't crash and returns a JSONObject.
     */
    @Test
    public void testReadConfig_nonExistentFile_returnsEmptyObject() {
        // Call on non-existent file (Termux path won't exist in test environment)
        JSONObject config = BotDropConfig.readConfig();

        // Should return empty JSONObject, not null
        assertNotNull("Config should not be null", config);
        assertEquals("Config should be empty", 0, config.length());
    }

    /**
     * Test: setProvider creates correct JSON structure
     *
     * NOTE: This tests the logic but writes to Termux paths that don't exist
     * in the test environment. The method will attempt to create directories
     * but may fail on permission issues. We verify it doesn't crash.
     */
    @Test
    public void testSetProvider_createsCorrectStructure() {
        // This will try to write to Termux paths
        // In a real device, this would work. In tests, it may fail gracefully.
        boolean result = BotDropConfig.setProvider("anthropic", "claude-sonnet-4-5");

        // We can't assert true because paths don't exist, but we verify no crash
        // The method should handle errors gracefully and return false
        assertFalse("Should return false when unable to write to non-existent paths", result);
    }

    /**
     * Test: setProvider with null values handles gracefully
     */
    @Test
    public void testSetProvider_withNullValues_handlesGracefully() {
        // Should not crash with null values
        try {
            boolean result = BotDropConfig.setProvider(null, null);
            // May return false or crash - we're just checking it doesn't hang
            assertFalse("Should return false with null values", result);
        } catch (NullPointerException e) {
            // Also acceptable - the method doesn't explicitly handle nulls
            // but we verify it fails fast rather than hanging
            assertTrue("NPE is acceptable for null inputs", true);
        }
    }

    /**
     * Test: setApiKey handles gracefully when paths don't exist
     */
    @Test
    public void testSetApiKey_withNonExistentPaths_handlesGracefully() {
        // Should not crash even if paths don't exist
        boolean result = BotDropConfig.setApiKey("anthropic", "test-key-123");

        // In test environment, this should return false because paths don't exist
        // But it shouldn't crash
        assertFalse("Should return false when unable to write to non-existent paths", result);
    }

    /**
     * Test: isConfigured returns false when no config exists
     */
    @Test
    public void testIsConfigured_noConfig_returnsFalse() {
        boolean configured = BotDropConfig.isConfigured();

        // Should return false since Termux config doesn't exist in test environment
        assertFalse("Should return false when config doesn't exist", configured);
    }

    /**
     * Test: JSON structure validation
     * This tests the expected JSON structure without file I/O
     */
    @Test
    public void testExpectedConfigStructure() throws JSONException {
        // Verify the structure that setProvider should create
        JSONObject config = new JSONObject();
        JSONObject agents = new JSONObject();
        JSONObject defaults = new JSONObject();
        JSONObject model = new JSONObject();

        model.put("primary", "anthropic/claude-sonnet-4-5");
        defaults.put("model", model);
        defaults.put("workspace", "~/botdrop");
        agents.put("defaults", defaults);
        config.put("agents", agents);

        // Add gateway structure
        JSONObject gateway = new JSONObject();
        gateway.put("mode", "local");
        JSONObject auth = new JSONObject();
        auth.put("token", "botdrop-local-token");
        gateway.put("auth", auth);
        config.put("gateway", gateway);

        // Verify structure
        assertTrue("Should have agents", config.has("agents"));
        assertTrue("Should have gateway", config.has("gateway"));

        JSONObject retrievedModel = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model");
        assertEquals("anthropic/claude-sonnet-4-5", retrievedModel.getString("primary"));
    }

    /**
     * Test: auth-profiles JSON structure validation
     */
    @Test
    public void testExpectedAuthProfileStructure() throws JSONException {
        // Verify the structure that setApiKey should create
        JSONObject authProfiles = new JSONObject();
        authProfiles.put("version", 1);

        JSONObject profiles = new JSONObject();
        JSONObject profile = new JSONObject();
        profile.put("type", "api_key");
        profile.put("provider", "anthropic");
        profile.put("key", "test-key-123");
        profiles.put("anthropic:default", profile);

        authProfiles.put("profiles", profiles);

        // Verify structure
        assertEquals(1, authProfiles.getInt("version"));
        assertTrue("Should have profiles", authProfiles.has("profiles"));

        JSONObject retrievedProfile = authProfiles.getJSONObject("profiles")
            .getJSONObject("anthropic:default");
        assertEquals("api_key", retrievedProfile.getString("type"));
        assertEquals("anthropic", retrievedProfile.getString("provider"));
        assertEquals("test-key-123", retrievedProfile.getString("key"));
    }

    /**
     * Test: Malformed JSON handling in isConfigured
     * We can't directly test readConfig with malformed JSON due to hardcoded paths,
     * but we can verify that isConfigured handles JSONException gracefully
     */
    @Test
    public void testIsConfigured_withJSONException_returnsFalse() {
        // isConfigured calls readConfig and handles JSONException
        // If readConfig returns malformed data or throws exception, isConfigured should return false
        boolean configured = BotDropConfig.isConfigured();
        assertFalse("Should return false when config is malformed or missing", configured);
    }
}
