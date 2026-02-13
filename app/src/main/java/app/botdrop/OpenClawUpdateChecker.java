package app.botdrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.shared.logger.Logger;

/**
 * Checks the npm registry for newer versions of OpenClaw.
 * Throttled to once per 24 hours. Fails silently — never blocks app usage.
 *
     * Results are persisted to SharedPreferences so the Dashboard can show update prompts.
 */
public class OpenClawUpdateChecker {

    private static final String LOG_TAG = "OpenClawUpdateChecker";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final String PREFS_NAME = "openclaw_update";
    private static final String KEY_LAST_CHECK = "last_check_time";
    private static final String KEY_DISMISSED_VERSION = "dismissed_version";
    private static final String KEY_LATEST_VERSION = "latest_version";
    private static final String KEY_CURRENT_VERSION = "current_version";

    interface UpdateCallback {
        void onUpdateAvailable(String currentVersion, String latestVersion);
        void onNoUpdate();
    }

    /**
     * Run a throttled background check. Calls back on the main thread.
     * Requires a bound BotDropService to execute shell commands.
     */
    static void check(Context ctx, BotDropService service, UpdateCallback cb) {
        check(ctx, service, cb, false);
    }

    static void check(Context ctx, BotDropService service, UpdateCallback cb, boolean force) {
        if (service == null) return;
        Log.e(LOG_TAG, "check() called, force=" + force);

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Throttle: skip if checked within the last 24 hours (unless forced)
        if (!force) {
            long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
            long elapsed = System.currentTimeMillis() - lastCheck;
            if (elapsed < CHECK_INTERVAL_MS) {
                Log.e(LOG_TAG, "Throttled, last check " + (elapsed / 1000) + "s ago");
                if (cb != null) notifyFromStored(ctx, prefs, cb);
                return;
            }
        }

        // Preconditions
        if (!BotDropService.isBootstrapInstalled() || !BotDropService.isOpenclawInstalled()) {
            Log.e(LOG_TAG, "Bootstrap or OpenClaw not installed, skipping");
            if (cb != null) cb.onNoUpdate();
            return;
        }

        String currentVersion = BotDropService.getOpenclawVersion();
        if (currentVersion == null || currentVersion.isEmpty()) {
            Log.e(LOG_TAG, "Could not read current version");
            if (cb != null) cb.onNoUpdate();
            return;
        }

        Log.e(LOG_TAG, "Starting check, current=" + currentVersion);

        // Query npm registry via shell command
        // Use pipefail so exit code reflects npm failure, not tr's.
        // Use tail -1 to grab only the version line (npm may print warnings to stdout).
        service.executeCommand(
            "set -o pipefail; npm view openclaw version 2>/dev/null | tail -1 | tr -d '[:space:]'",
            result -> {
                // This callback runs on the main thread
                Log.e(LOG_TAG, "npm result: success=" + result.success +
                    " exit=" + result.exitCode + " stdout=[" + result.stdout + "]");
                if (!result.success || result.stdout == null || result.stdout.trim().isEmpty()) {
                    Log.e(LOG_TAG, "npm view failed");
                    if (cb != null) cb.onNoUpdate();
                    return;
                }

                String latestVersion = result.stdout.trim();
                // If multi-line, take only the last non-empty line
                if (latestVersion.contains("\n")) {
                    String[] lines = latestVersion.split("\n");
                    for (int i = lines.length - 1; i >= 0; i--) {
                        String l = lines[i].trim();
                        if (!l.isEmpty()) { latestVersion = l; break; }
                    }
                }
                Log.e(LOG_TAG, "Parsed latest=\"" + latestVersion +
                    "\" current=\"" + currentVersion + "\"");

                // Validate that the version string actually parses before recording check time.
                // If npm returned garbage, we don't want to record it and throttle for 24h.
                try {
                    int[] parsed = parseSemver(latestVersion);
                    Log.e(LOG_TAG, "parseSemver(latest) = [" + parsed[0] + "," + parsed[1] + "," + parsed[2] + "]");
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to parse latest: \"" + latestVersion + "\"");
                    if (cb != null) cb.onNoUpdate();
                    return;
                }

                // Record check time only after successful parse
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

                boolean newer = isNewer(latestVersion, currentVersion);
                Log.e(LOG_TAG, "isNewer(" + latestVersion + ", " + currentVersion + ") = " + newer);
                if (!newer) {
                    clearStored(prefs);
                    if (cb != null) cb.onNoUpdate();
                    return;
                }

                // Check if user dismissed this version (skip when forced)
                if (!force) {
                    String dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null);
                    if (latestVersion.equals(dismissedVersion)) {
                        Logger.logInfo(LOG_TAG, "Version " + latestVersion + " was dismissed");
                        if (cb != null) cb.onNoUpdate();
                        return;
                    }
                }

                Logger.logInfo(LOG_TAG, "Update available: " + currentVersion + " -> " + latestVersion);
                prefs.edit()
                    .putString(KEY_LATEST_VERSION, latestVersion)
                    .putString(KEY_CURRENT_VERSION, currentVersion)
                    .apply();

                if (cb != null) cb.onUpdateAvailable(currentVersion, latestVersion);
            }
        );
    }

    /**
     * Get stored update info, or null if no update is available.
     * Returns [currentVersion, latestVersion] or null.
     */
    static String[] getAvailableUpdate(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String latestVersion = prefs.getString(KEY_LATEST_VERSION, null);
        if (latestVersion == null) return null;

        // Check if dismissed
        String dismissed = prefs.getString(KEY_DISMISSED_VERSION, null);
        if (latestVersion.equals(dismissed)) return null;

        // Check if still newer than current installed version
        String currentVersion = BotDropService.getOpenclawVersion();
        if (currentVersion == null || !isNewer(latestVersion, currentVersion)) {
            clearStored(prefs);
            return null;
        }

        return new String[]{currentVersion, latestVersion};
    }

    /**
     * Mark a version as dismissed so the banner won't show again for it.
     */
    static void dismiss(Context ctx, String version) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_VERSION, version)
            .apply();
    }

    /**
     * Clear stored update result (e.g. after a successful update).
     */
    static void clearUpdate(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        clearStored(prefs);
        prefs.edit().remove(KEY_DISMISSED_VERSION).apply();
    }

    private static void notifyFromStored(Context ctx, SharedPreferences prefs, UpdateCallback cb) {
        String[] update = getAvailableUpdate(ctx);
        if (update != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                cb.onUpdateAvailable(update[0], update[1]));
        } else {
            new Handler(Looper.getMainLooper()).post(cb::onNoUpdate);
        }
    }

    private static void clearStored(SharedPreferences prefs) {
        prefs.edit()
            .remove(KEY_LATEST_VERSION)
            .remove(KEY_CURRENT_VERSION)
            .apply();
    }

    /**
     * Simple semver comparison: returns true if latest > current.
     */
    static boolean isNewer(String latest, String current) {
        try {
            int[] l = parseSemver(latest);
            int[] c = parseSemver(current);
            for (int i = 0; i < 3; i++) {
                if (l[i] > c[i]) return true;
                if (l[i] < c[i]) return false;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static int[] parseSemver(String v) {
        // Strip any leading 'v' prefix
        if (v.startsWith("v")) v = v.substring(1);
        // Strip any pre-release suffix (e.g., "-beta.1")
        int dashIndex = v.indexOf('-');
        if (dashIndex >= 0) v = v.substring(0, dashIndex);
        String[] parts = v.split("\\.");
        return new int[]{
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2])
        };
    }
}
