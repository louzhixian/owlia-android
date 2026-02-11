package app.botdrop.automation;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Best-effort helper to sync the BotDrop UI automation skill into OpenClaw's system skills dir.
 *
 * This is needed on upgrades where the install marker prevents re-running install.sh, but we still
 * want out-of-box skill discovery under $PREFIX/lib/node_modules/openclaw/skills/.
 */
public final class UiAutomationSkillInstaller {

    private static final String LOG_TAG = "UiAutomationSkillInstaller";
    private static final String ASSET_SKILL_MD = "botdrop/skills/botdrop-ui/SKILL.md";
    private static final String ASSET_ALIASES_JSON = "botdrop/skills/botdrop-ui/apps-aliases.json";
    private static final String SKILL_REL_DIR = ".openclaw/skills/botdrop-ui";
    private static final String AGENT_SKILL_REL_DIR = ".openclaw/agents/main/agent/skills/botdrop-ui";

    private UiAutomationSkillInstaller() {}

    public static void ensureSystemSkillInstalledAsync(Context context) {
        final Context appContext = context != null ? context.getApplicationContext() : null;
        new Thread(() -> {
            try {
                if (appContext != null) {
                    ensureBundledSkillFilesInstalled(appContext);
                }
                ensureSystemSkillInstalled();
                ensureAgentContextHintsInstalled();
            } catch (Throwable t) {
                Logger.logDebug(LOG_TAG, "ensureSystemSkillInstalled failed: " + t.getMessage());
            }
        }, "BotDropSkillSync").start();
    }

    /** @deprecated Use {@link #ensureSystemSkillInstalledAsync(Context)}. */
    @Deprecated
    public static void ensureSystemSkillInstalledAsync() {
        ensureSystemSkillInstalledAsync(null);
    }

    private static void ensureBundledSkillFilesInstalled(Context context) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;

        File userSkillDir = new File(home, SKILL_REL_DIR);
        File agentSkillDir = new File(home, AGENT_SKILL_REL_DIR);
        //noinspection ResultOfMethodCallIgnored
        userSkillDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        agentSkillDir.mkdirs();

        copyAssetIfDifferent(context, ASSET_SKILL_MD, new File(userSkillDir, "SKILL.md"), "user SKILL.md");
        copyAssetIfDifferent(context, ASSET_ALIASES_JSON, new File(userSkillDir, "apps-aliases.json"), "user apps-aliases.json");
        copyAssetIfDifferent(context, ASSET_SKILL_MD, new File(agentSkillDir, "SKILL.md"), "agent SKILL.md");
        copyAssetIfDifferent(context, ASSET_ALIASES_JSON, new File(agentSkillDir, "apps-aliases.json"), "agent apps-aliases.json");
    }

    private static void ensureSystemSkillInstalled() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;

        File openclawDir = new File(prefix + "/lib/node_modules/openclaw");
        if (!openclawDir.exists()) return;

        File dstDir = new File(prefix + "/lib/node_modules/openclaw/skills/botdrop-ui");
        //noinspection ResultOfMethodCallIgnored
        dstDir.mkdirs();

        syncFileIfNeeded(
            new File(home, SKILL_REL_DIR + "/SKILL.md"),
            new File(dstDir, "SKILL.md"),
            "SKILL.md"
        );
        syncFileIfNeeded(
            new File(home, SKILL_REL_DIR + "/apps-aliases.json"),
            new File(dstDir, "apps-aliases.json"),
            "apps-aliases.json"
        );
    }

    private static void syncFileIfNeeded(File src, File dst, String label) {
        try {
            if (!src.exists() || src.length() <= 0) return;
            if (dst.exists() && dst.length() == src.length()) return; // cheap heuristic
            copyFile(src, dst);
            Logger.logInfo(LOG_TAG, "Synced " + label + " to " + dst.getAbsolutePath());
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "Failed to sync " + label + ": " + e.getMessage());
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
            "Do NOT use adb pair/connect flow for this skill.\n" +
            "If alias has preferredComponent, pass it to openApp.component.\n" +
            "\n" +
            "Examples:\n" +
            "- `botdrop-ui '{\"op\":\"openApp\",\"packageName\":\"com.twitter.android\",\"timeoutMs\":12000}'`\n" +
            "- `botdrop-ui '{\"op\":\"openApp\",\"packageName\":\"com.tencent.mm\",\"activity\":\".ui.LauncherUI\",\"timeoutMs\":12000}'`\n" +
            "- `botdrop-ui '{\"op\":\"openApp\",\"packageName\":\"tv.danmaku.bili\",\"component\":\"tv.danmaku.bili/.MainActivityV2\",\"timeoutMs\":12000}'`\n" +
            "- `botdrop-ui '{\"op\":\"adb\",\"action\":\"connect\",\"host\":\"localhost:5555\"}'`\n" +
            "- `botdrop-ui '{\"op\":\"adb\",\"action\":\"openApp\",\"packageName\":\"com.tencent.mm\",\"component\":\"com.tencent.mm/.ui.LauncherUI\"}'`\n" +
            "- `botdrop-ui '{\"op\":\"global\",\"action\":\"home\"}'`\n" +
            "- `botdrop-ui '{\"op\":\"tree\",\"maxNodes\":400}'`\n" +
            "- `botdrop-ui '{\"op\":\"find\",\"selector\":{\"textContains\":\"OK\"},\"mode\":\"first\",\"timeoutMs\":3000}'`\n" +
            "- `botdrop-ui '{\"op\":\"action\",\"target\":{\"selector\":{\"textContains\":\"OK\"}},\"action\":\"click\",\"timeoutMs\":3000}'`\n" +
            "- If app launch shows confirmation dialog, click `始终打开/Always` (fallback `仅此一次/Just once`).\n" +
            "- Do not infer app uninstalled from `pm list packages`; package visibility can hide results.\n";

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

    private static void copyAssetIfDifferent(Context context, String assetPath, File dst, String label) {
        try {
            byte[] assetBytes = readAssetBytes(context, assetPath);
            if (assetBytes == null || assetBytes.length == 0) return;

            byte[] existing = readFileBytes(dst);
            if (existing != null && bytesEqual(existing, assetBytes)) return;

            File parent = dst.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(dst)) {
                out.write(assetBytes);
                out.flush();
            }
            Logger.logInfo(LOG_TAG, "Synced " + label + " to " + dst.getAbsolutePath());
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "Failed to sync " + label + ": " + e.getMessage());
        }
    }

    private static byte[] readAssetBytes(Context context, String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            byte[] buf = new byte[8192];
            int n;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readFileBytes(File f) {
        try {
            if (!f.exists()) return null;
            long len = f.length();
            if (len < 0 || len > (2 * 1024 * 1024)) return null;
            byte[] b = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n <= 0) break;
                    off += n;
                }
                if (off != b.length) return null;
            }
            return b;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean bytesEqual(byte[] a, byte[] b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }
}
