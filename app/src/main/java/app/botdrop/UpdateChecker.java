package app.botdrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight version checker that queries the BotDrop API for the latest release.
 * Throttled to once per 24 hours. Fails silently — never blocks app usage.
 */
public class UpdateChecker {

    private static final String LOG_TAG = "UpdateChecker";
    private static final String CHECK_URL = "https://api.botdrop.app/version";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final String PREFS_NAME = "botdrop_update";
    private static final String KEY_LAST_CHECK = "last_check_time";
    private static final String KEY_DISMISSED_VERSION = "dismissed_version";

    interface UpdateCallback {
        void onUpdateAvailable(String latestVersion, String downloadUrl, String notes);
    }

    static void check(Context ctx, UpdateCallback cb) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Throttle: skip if checked within the last 24 hours
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }

        String currentVersion;
        int currentVersionCode;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            currentVersion = pi.versionName;
            currentVersionCode = pi.versionCode;
        } catch (Exception e) {
            return;
        }

        String dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null);

        new Thread(() -> {
            try {
                String urlStr = CHECK_URL + "?v=" + currentVersion + "&vc=" + currentVersionCode;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                // Record successful check
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

                JSONObject json = new JSONObject(sb.toString());
                String latestVersion = json.optString("latest_version", "");
                String downloadUrl = json.optString("download_url", "");
                String notes = json.optString("release_notes", "");

                if (latestVersion.isEmpty() || latestVersion.equals(currentVersion)) {
                    return;
                }

                // Skip if user already dismissed this version
                if (latestVersion.equals(dismissedVersion)) {
                    return;
                }

                if (isNewer(latestVersion, currentVersion)) {
                    new Handler(Looper.getMainLooper()).post(() -> cb.onUpdateAvailable(latestVersion, downloadUrl, notes));
                }
            } catch (Exception e) {
                Logger.logDebug(LOG_TAG, "Update check failed (non-fatal): " + e.getMessage());
            }
        }).start();
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
     * Simple semver comparison: returns true if latest > current.
     */
    private static boolean isNewer(String latest, String current) {
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

    private static int[] parseSemver(String v) {
        String[] parts = v.split("\\.");
        return new int[]{
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2])
        };
    }
}
