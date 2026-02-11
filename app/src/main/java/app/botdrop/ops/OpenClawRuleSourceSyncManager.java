package app.botdrop.ops;

import android.content.Context;

import com.termux.shared.logger.Logger;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Best-effort sync for rule source versions.
 * Currently syncs official docs metadata and stores it for source selection.
 */
public class OpenClawRuleSourceSyncManager {

    private static final String LOG_TAG = "OpenClawRuleSync";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 6000;

    private static final String FAQ_URL = "https://docs.openclaw.ai/help/faq";
    private static final String TROUBLESHOOTING_URL = "https://docs.openclaw.ai/help/troubleshooting";

    public interface Callback {
        void onComplete(boolean updated, String docsVersion);
    }

    private final CachedRuleSourceResolver resolver;

    public OpenClawRuleSourceSyncManager(Context context) {
        this.resolver = new CachedRuleSourceResolver(context);
    }

    public void syncOfficialDocsVersionAsync(Callback callback) {
        new Thread(() -> {
            String faqVersion = fetchDocVersion(FAQ_URL);
            String troubleshootingVersion = fetchDocVersion(TROUBLESHOOTING_URL);
            String mergedVersion = mergeVersions(faqVersion, troubleshootingVersion);

            boolean updated = false;
            if (mergedVersion != null && !mergedVersion.trim().isEmpty()) {
                resolver.saveOpenClawDocsVersion(mergedVersion);
                updated = true;
            }

            if (callback != null) callback.onComplete(updated, mergedVersion);
        }).start();
    }

    public void saveRuntimeSchemaVersion(String schemaVersion) {
        if (schemaVersion == null || schemaVersion.trim().isEmpty()) return;
        resolver.saveOpenClawSchemaVersion(schemaVersion.trim());
    }

    public RuleSource resolveSource(String openclawVersion) {
        return resolver.resolveOpenClawSource(openclawVersion);
    }

    private String fetchDocVersion(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;

            String etag = conn.getHeaderField("ETag");
            if (etag != null && !etag.trim().isEmpty()) return "etag:" + etag.trim();

            String modified = conn.getHeaderField("Last-Modified");
            if (modified != null && !modified.trim().isEmpty()) return "last-modified:" + modified.trim();

            return "http-" + code;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to fetch docs version for " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String mergeVersions(String v1, String v2) {
        if ((v1 == null || v1.isEmpty()) && (v2 == null || v2.isEmpty())) return null;
        if (v1 == null || v1.isEmpty()) return "troubleshooting=" + v2;
        if (v2 == null || v2.isEmpty()) return "faq=" + v1;
        return "faq=" + v1 + ";troubleshooting=" + v2;
    }
}
