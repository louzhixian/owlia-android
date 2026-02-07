package app.botdrop;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for ChannelSetupHelper
 *
 * This class has pure logic for decoding setup codes and can be fully tested.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChannelSetupHelperTest {

    /**
     * Test: Valid Telegram setup code is correctly decoded
     */
    @Test
    public void testDecodeSetupCode_validTelegram_decodesCorrectly() throws JSONException {
        // Create a valid setup code for Telegram
        JSONObject payload = new JSONObject();
        payload.put("v", 1);
        payload.put("platform", "telegram");
        payload.put("bot_token", "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11");
        payload.put("owner_id", "987654321");
        payload.put("created_at", 1234567890);

        String base64Payload = Base64.encodeToString(
            payload.toString().getBytes(),
            Base64.NO_WRAP
        );
        String setupCode = "BOTDROP-tg-" + base64Payload;

        // Decode
        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        // Verify
        assertNotNull("Decoded data should not be null", data);
        assertEquals("telegram", data.platform);
        assertEquals("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11", data.botToken);
        assertEquals("987654321", data.ownerId);
    }

    /**
     * Test: Valid Discord setup code is correctly decoded
     */
    @Test
    public void testDecodeSetupCode_validDiscord_decodesCorrectly() throws JSONException {
        // Create a valid setup code for Discord
        JSONObject payload = new JSONObject();
        payload.put("v", 1);
        payload.put("platform", "discord");
        payload.put("bot_token", "discord-test-bot-token-placeholder");
        payload.put("owner_id", "123456789012345678");
        payload.put("created_at", 1234567890);

        String base64Payload = Base64.encodeToString(
            payload.toString().getBytes(),
            Base64.NO_WRAP
        );
        String setupCode = "BOTDROP-dc-" + base64Payload;

        // Decode
        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        // Verify
        assertNotNull("Decoded data should not be null", data);
        assertEquals("discord", data.platform);
        assertEquals("discord-test-bot-token-placeholder", data.botToken);
        assertEquals("123456789012345678", data.ownerId);
    }

    /**
     * Test: Setup code without platform in JSON infers from prefix
     */
    @Test
    public void testDecodeSetupCode_noPlatformInJSON_infersFromPrefix() throws JSONException {
        // Create payload without platform field
        JSONObject payload = new JSONObject();
        payload.put("v", 1);
        payload.put("bot_token", "test-token");
        payload.put("owner_id", "test-owner");

        String base64Payload = Base64.encodeToString(
            payload.toString().getBytes(),
            Base64.NO_WRAP
        );
        String setupCode = "BOTDROP-tg-" + base64Payload;

        // Decode
        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        // Should infer platform from "tg" prefix
        assertNotNull("Decoded data should not be null", data);
        assertEquals("telegram", data.platform);
    }

    /**
     * Test: Invalid prefix is rejected
     */
    @Test
    public void testDecodeSetupCode_invalidPrefix_returnsNull() {
        String setupCode = "INVALID-tg-dGVzdA==";

        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        assertNull("Should return null for invalid prefix", data);
    }

    /**
     * Test: Missing BOTDROP prefix is rejected
     */
    @Test
    public void testDecodeSetupCode_missingBotdropPrefix_returnsNull() {
        String setupCode = "tg-dGVzdA==";

        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        assertNull("Should return null when BOTDROP prefix is missing", data);
    }

    /**
     * Test: Malformed base64 payload is handled
     */
    @Test
    public void testDecodeSetupCode_malformedBase64_returnsNull() {
        String setupCode = "BOTDROP-tg-NOT_VALID_BASE64!!!";

        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        assertNull("Should return null for malformed base64", data);
    }

    /**
     * Test: Invalid JSON in payload is handled
     */
    @Test
    public void testDecodeSetupCode_invalidJSON_returnsNull() {
        // Create invalid JSON
        String invalidJson = "{invalid json}";
        String base64Payload = Base64.encodeToString(
            invalidJson.getBytes(),
            Base64.NO_WRAP
        );
        String setupCode = "BOTDROP-tg-" + base64Payload;

        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        assertNull("Should return null for invalid JSON", data);
    }

    /**
     * Test: Missing required fields in JSON is handled
     */
    @Test
    public void testDecodeSetupCode_missingFields_returnsNull() throws JSONException {
        // Create payload with missing bot_token
        JSONObject payload = new JSONObject();
        payload.put("v", 1);
        payload.put("platform", "telegram");
        payload.put("owner_id", "123456789");
        // bot_token is missing

        String base64Payload = Base64.encodeToString(
            payload.toString().getBytes(),
            Base64.NO_WRAP
        );
        String setupCode = "BOTDROP-tg-" + base64Payload;

        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        assertNull("Should return null when required fields are missing", data);
    }

    /**
     * Test: Empty/null input is handled
     * NOTE: The implementation doesn't explicitly handle null, so NPE is expected
     */
    @Test(expected = NullPointerException.class)
    public void testDecodeSetupCode_nullInput_throwsNPE() {
        // The method doesn't check for null before calling split()
        // This is acceptable - callers should validate input
        ChannelSetupHelper.decodeSetupCode(null);
    }

    /**
     * Test: Empty string input is handled
     */
    @Test
    public void testDecodeSetupCode_emptyInput_returnsNull() {
        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode("");

        assertNull("Should return null for empty input", data);
    }

    /**
     * Test: Setup code with wrong number of parts is rejected
     */
    @Test
    public void testDecodeSetupCode_wrongNumberOfParts_returnsNull() {
        String setupCode = "BOTDROP-tg"; // Missing payload part

        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);

        assertNull("Should return null when setup code has wrong number of parts", data);
    }

    /**
     * Test: writeChannelConfig for Telegram (integration with BotDropConfig)
     *
     * NOTE: This will fail in test environment because BotDropConfig uses hardcoded
     * Termux paths. We verify it doesn't crash and handles the error gracefully.
     */
    @Test
    public void testWriteChannelConfig_telegram_handlesGracefully() {
        boolean result = ChannelSetupHelper.writeChannelConfig(
            "telegram",
            "test-token",
            "test-owner"
        );

        // In test environment, should return false but not crash
        assertFalse("Should return false when unable to write to non-existent paths", result);
    }

    /**
     * Test: writeChannelConfig for Discord
     */
    @Test
    public void testWriteChannelConfig_discord_handlesGracefully() {
        boolean result = ChannelSetupHelper.writeChannelConfig(
            "discord",
            "test-token",
            "test-owner"
        );

        // In test environment, should return false but not crash
        assertFalse("Should return false when unable to write to non-existent paths", result);
    }

    /**
     * Test: writeChannelConfig with unsupported platform
     */
    @Test
    public void testWriteChannelConfig_unsupportedPlatform_returnsFalse() {
        boolean result = ChannelSetupHelper.writeChannelConfig(
            "unsupported",
            "test-token",
            "test-owner"
        );

        assertFalse("Should return false for unsupported platform", result);
    }

    /**
     * Test: SetupCodeData constructor
     */
    @Test
    public void testSetupCodeData_constructor() {
        ChannelSetupHelper.SetupCodeData data = new ChannelSetupHelper.SetupCodeData(
            "telegram",
            "test-token",
            "test-owner"
        );

        assertEquals("telegram", data.platform);
        assertEquals("test-token", data.botToken);
        assertEquals("test-owner", data.ownerId);
    }
}
