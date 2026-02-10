package app.botdrop.automation;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Best-effort helper to sync the BotDrop UI automation skill into OpenClaw's system skills dir.
 *
 * This is needed on upgrades where the install marker prevents re-running install.sh, but we still
 * want out-of-box skill discovery under $PREFIX/lib/node_modules/openclaw/skills/.
 */
public final class UiAutomationSkillInstaller {

    private static final String LOG_TAG = "UiAutomationSkillInstaller";

    private UiAutomationSkillInstaller() {}

    public static void ensureSystemSkillInstalledAsync() {
        new Thread(() -> {
            try {
                ensureSystemSkillInstalled();
                ensureAgentContextHintsInstalled();
            } catch (Throwable t) {
                Logger.logDebug(LOG_TAG, "ensureSystemSkillInstalled failed: " + t.getMessage());
            }
        }, "BotDropSkillSync").start();
    }

    private static void ensureSystemSkillInstalled() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;

        File openclawDir = new File(prefix + "/lib/node_modules/openclaw");
        if (!openclawDir.exists()) return;

        File src = new File(home + "/.openclaw/skills/botdrop-ui/SKILL.md");
        if (!src.exists() || src.length() <= 0) return;

        File dstDir = new File(prefix + "/lib/node_modules/openclaw/skills/botdrop-ui");
        File dst = new File(dstDir, "SKILL.md");

        // Skip if already present with same size (cheap heuristic).
        if (dst.exists() && dst.length() == src.length()) return;

        //noinspection ResultOfMethodCallIgnored
        dstDir.mkdirs();

        try {
            copyFile(src, dst);
            Logger.logInfo(LOG_TAG, "Synced system skill to " + dst.getAbsolutePath());
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "Failed to copy skill: " + e.getMessage());
        }
    }

    /**
     * OpenClaw does not necessarily inject skill docs into every new chat session automatically.
     * Add a small hint to agent context files so "botdrop-ui" is discoverable without the user
     * needing to teach it each time.
     */
    private static void ensureAgentContextHintsInstalled() {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File agentDir = new File(home + "/.openclaw/agents/main/agent");
        if (!agentDir.exists()) return;

        String marker = "BOTDROP_UI_AUTOMATION_HINT_v1";
        String snippet =
            "\n\n" +
            "<!-- " + marker + " -->\n" +
            "## BotDrop UI Automation\n" +
            "\n" +
            "This device supports Android UI automation via Accessibility.\n" +
            "Use the `botdrop-ui` command to read the UI tree and perform actions.\n" +
            "\n" +
            "Examples:\n" +
            "- `botdrop-ui '{\"op\":\"tree\",\"maxNodes\":400}'`\n" +
            "- `botdrop-ui '{\"op\":\"find\",\"selector\":{\"textContains\":\"OK\"},\"mode\":\"first\",\"timeoutMs\":3000}'`\n" +
            "- `botdrop-ui '{\"op\":\"action\",\"target\":{\"selector\":{\"textContains\":\"OK\"}},\"action\":\"click\",\"timeoutMs\":3000}'`\n";

        // Try common file names the agent runner might load.
        ensureFileContainsSnippet(new File(agentDir, "TOOLS.md"), marker, snippet);
        ensureFileContainsSnippet(new File(agentDir, "tools.md"), marker, snippet);
        ensureFileContainsSnippet(new File(agentDir, "AGENTS.md"), marker, snippet);
        ensureFileContainsSnippet(new File(agentDir, "agents.md"), marker, snippet);
    }

    private static void ensureFileContainsSnippet(File f, String marker, String snippet) {
        try {
            String existing = readFileToString(f);
            if (existing != null && existing.contains(marker)) return;

            // Ensure file exists before appending.
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            }

            appendStringToFile(f, snippet);
            Logger.logInfo(LOG_TAG, "Wrote botdrop-ui hint into " + f.getAbsolutePath());
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "Failed to write hint file " + f.getName() + ": " + e.getMessage());
        }
    }

    private static String readFileToString(File f) {
        try {
            if (!f.exists()) return null;
            long len = f.length();
            if (len <= 0 || len > (1024 * 1024)) return ""; // cap reads to 1MB
            byte[] buf = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void appendStringToFile(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }
}
