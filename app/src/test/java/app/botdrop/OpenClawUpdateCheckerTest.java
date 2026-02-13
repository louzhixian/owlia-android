package app.botdrop;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for OpenClawUpdateChecker version comparison logic.
 */
public class OpenClawUpdateCheckerTest {

    @Test
    public void testIsNewer_basicComparison() {
        assertTrue(OpenClawUpdateChecker.isNewer("1.0.1", "1.0.0"));
        assertTrue(OpenClawUpdateChecker.isNewer("1.1.0", "1.0.0"));
        assertTrue(OpenClawUpdateChecker.isNewer("2.0.0", "1.9.9"));
    }

    @Test
    public void testIsNewer_sameVersion() {
        assertFalse(OpenClawUpdateChecker.isNewer("1.0.0", "1.0.0"));
        assertFalse(OpenClawUpdateChecker.isNewer("0.2.3", "0.2.3"));
    }

    @Test
    public void testIsNewer_olderVersion() {
        assertFalse(OpenClawUpdateChecker.isNewer("1.0.0", "1.0.1"));
        assertFalse(OpenClawUpdateChecker.isNewer("0.9.0", "1.0.0"));
    }

    @Test
    public void testIsNewer_withVPrefix() {
        assertTrue(OpenClawUpdateChecker.isNewer("v1.1.0", "1.0.0"));
        assertTrue(OpenClawUpdateChecker.isNewer("1.1.0", "v1.0.0"));
        assertTrue(OpenClawUpdateChecker.isNewer("v2.0.0", "v1.0.0"));
        assertFalse(OpenClawUpdateChecker.isNewer("v1.0.0", "v1.0.0"));
    }

    @Test
    public void testIsNewer_withPreReleaseSuffix() {
        // Pre-release suffix is stripped; "1.1.0-beta.1" treated as "1.1.0"
        assertTrue(OpenClawUpdateChecker.isNewer("1.1.0-beta.1", "1.0.0"));
        assertFalse(OpenClawUpdateChecker.isNewer("1.0.0-beta.1", "1.0.0"));
    }

    @Test
    public void testIsNewer_invalidInput() {
        // Should return false on invalid input, not throw
        assertFalse(OpenClawUpdateChecker.isNewer("invalid", "1.0.0"));
        assertFalse(OpenClawUpdateChecker.isNewer("1.0.0", "invalid"));
        assertFalse(OpenClawUpdateChecker.isNewer("", "1.0.0"));
    }

    @Test
    public void testParseSemver_basic() {
        int[] result = OpenClawUpdateChecker.parseSemver("1.2.3");
        assertArrayEquals(new int[]{1, 2, 3}, result);
    }

    @Test
    public void testParseSemver_stripsVPrefix() {
        int[] result = OpenClawUpdateChecker.parseSemver("v1.2.3");
        assertArrayEquals(new int[]{1, 2, 3}, result);
    }

    @Test
    public void testParseSemver_stripsPreRelease() {
        int[] result = OpenClawUpdateChecker.parseSemver("1.2.3-beta.1");
        assertArrayEquals(new int[]{1, 2, 3}, result);
    }

    @Test
    public void testParseSemver_zeroes() {
        int[] result = OpenClawUpdateChecker.parseSemver("0.0.0");
        assertArrayEquals(new int[]{0, 0, 0}, result);
    }
}
