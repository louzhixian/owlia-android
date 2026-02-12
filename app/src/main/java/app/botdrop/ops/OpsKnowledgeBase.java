package app.botdrop.ops;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Local cached docs snippets for OpenClaw troubleshooting/configuration answers.
 */
public class OpsKnowledgeBase {

    private static final String LOG_TAG = "OpsKnowledgeBase";
    private static final String DOC_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.botdrop/ops-docs";
    private static final long STALE_MS = 6L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;

    private static final String[] FAQ_URLS = new String[] {
        "https://raw.githubusercontent.com/openclaw/openclaw/main/docs/help/faq.md",
        "https://raw.githubusercontent.com/anthropics/openclaw/main/docs/help/faq.md"
    };

    private static final String[] TROUBLESHOOTING_URLS = new String[] {
        "https://raw.githubusercontent.com/openclaw/openclaw/main/docs/help/troubleshooting.md",
        "https://raw.githubusercontent.com/anthropics/openclaw/main/docs/help/troubleshooting.md"
    };

    private volatile boolean refreshing = false;

    public void refreshAsyncIfStale() {
        if (refreshing) return;
        File dir = new File(DOC_DIR);
        File faq = new File(dir, "faq.md");
        File troubleshooting = new File(dir, "troubleshooting.md");
        long now = System.currentTimeMillis();
        boolean stale = !faq.exists() || !troubleshooting.exists()
            || now - faq.lastModified() > STALE_MS
            || now - troubleshooting.lastModified() > STALE_MS;
        if (!stale) return;

        refreshing = true;
        new Thread(() -> {
            try {
                if (!dir.exists()) dir.mkdirs();
                downloadFirstSuccess(FAQ_URLS, faq);
                downloadFirstSuccess(TROUBLESHOOTING_URLS, troubleshooting);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "refresh failed: " + e.getMessage());
            } finally {
                refreshing = false;
            }
        }).start();
    }

    public String buildContext(String query, int maxChars) {
        File dir = new File(DOC_DIR);
        File faq = new File(dir, "faq.md");
        File troubleshooting = new File(dir, "troubleshooting.md");

        StringBuilder docs = new StringBuilder();
        docs.append(readSafe(faq)).append("\n").append(readSafe(troubleshooting));
        String all = docs.toString();
        if (all.trim().isEmpty()) return "";

        List<String> lines = new ArrayList<>();
        for (String line : all.split("\n")) lines.add(line);

        Set<String> keywords = extractKeywords(query);
        StringBuilder out = new StringBuilder();

        if (!keywords.isEmpty()) {
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                String lower = l.toLowerCase(Locale.US);
                boolean hit = false;
                for (String kw : keywords) {
                    if (lower.contains(kw)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) continue;
                for (int j = Math.max(0, i - 2); j <= Math.min(lines.size() - 1, i + 3); j++) {
                    String s = lines.get(j).trim();
                    if (s.isEmpty()) continue;
                    appendLine(out, s, maxChars);
                    if (out.length() >= maxChars) return out.toString();
                }
                appendLine(out, "---", maxChars);
                if (out.length() >= maxChars) return out.toString();
            }
        }

        if (out.length() == 0) {
            for (String line : lines) {
                String s = line.trim();
                if (s.startsWith("#")) {
                    appendLine(out, s, maxChars);
                    if (out.length() >= maxChars) return out.toString();
                }
            }
        }

        return out.toString();
    }

    private void downloadFirstSuccess(String[] urls, File target) throws Exception {
        for (String u : urls) {
            if (download(u, target)) return;
        }
    }

    private boolean download(String url, File target) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return false;

            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                 FileOutputStream fos = new FileOutputStream(target, false)) {
                String line;
                while ((line = br.readLine()) != null) {
                    fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "download failed for " + url + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readSafe(File f) {
        try {
            if (f == null || !f.exists()) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private Set<String> extractKeywords(String query) {
        Set<String> out = new LinkedHashSet<>();
        if (query == null) return out;
        String[] parts = query.toLowerCase(Locale.US).split("[^a-z0-9]+");
        for (String p : parts) {
            if (p.length() >= 3) out.add(p);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private void appendLine(StringBuilder sb, String line, int maxChars) {
        if (sb.length() >= maxChars) return;
        String candidate = line.length() > 220 ? line.substring(0, 220) : line;
        if (sb.length() + candidate.length() + 1 > maxChars) return;
        sb.append(candidate).append("\n");
    }
}
