package app.botdrop.ops;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Ensures pi CLI is available in Termux PATH for Ops chat.
 */
public class PiAgentInstaller {

    public interface Callback {
        void onComplete(boolean success, String message);
    }

    private static class CommandResult {
        final boolean success;
        final String stdout;
        final String stderr;
        final int exitCode;

        CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    public void ensureInstalled(Callback callback) {
        new Thread(() -> {
            CommandResult check = runCommand(buildCheckCommand(), 30);
            String state = check.stdout == null ? "" : check.stdout.trim();
            if (check.success && state.contains("installed")) {
                if (callback != null) callback.onComplete(true, "pi ready");
                return;
            }

            CommandResult install = runCommand(buildInstallCommand(), 180);
            if (callback == null) return;
            if (install.success) {
                callback.onComplete(true, "pi installed");
                return;
            }
            String err = install.stderr == null || install.stderr.trim().isEmpty()
                ? install.stdout
                : install.stderr;
            callback.onComplete(false, err == null ? "install failed" : err.trim());
        }).start();
    }

    private String buildCheckCommand() {
        return ""
            + "PI_BIN=\"$(command -v pi 2>/dev/null || true)\"\n"
            + "PI_NODE=\"$(command -v node 2>/dev/null || true)\"\n"
            + "if [ -n \"$PI_BIN\" ] && [ -n \"$PI_NODE\" ]; then\n"
            + "  if \"$PI_NODE\" \"$PI_BIN\" --version >/dev/null 2>&1; then\n"
            + "    echo installed\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  FIRST_LINE=\"$(head -n 1 \"$PI_BIN\" 2>/dev/null || true)\"\n"
            + "  if [ \"$FIRST_LINE\" = '#!/usr/bin/env node' ]; then\n"
            + "    TMP_PI=\"$PREFIX/tmp/pi-fixed-$$.js\"\n"
            + "    {\n"
            + "      echo '#!'\"$PI_NODE\"\n"
            + "      tail -n +2 \"$PI_BIN\"\n"
            + "    } > \"$TMP_PI\" && cat \"$TMP_PI\" > \"$PI_BIN\" && chmod 700 \"$PI_BIN\" && rm -f \"$TMP_PI\"\n"
            + "  fi\n"
            + "  if \"$PI_NODE\" \"$PI_BIN\" --version >/dev/null 2>&1; then\n"
            + "    echo installed\n"
            + "    exit 0\n"
            + "  fi\n"
            + "fi\n"
            + "echo missing\n";
    }

    private String buildInstallCommand() {
        return ""
            + "if ! command -v npm >/dev/null 2>&1; then echo 'npm missing'; exit 1; fi\n"
            + "npm config set fund false >/dev/null 2>&1 || true\n"
            + "npm config set update-notifier false >/dev/null 2>&1 || true\n"
            + "npm i -g @mariozechner/pi-coding-agent\n"
            + buildCheckCommand();
    }

    private CommandResult runCommand(String command, int timeoutSeconds) {
        StringBuilder stdout = new StringBuilder();
        int exitCode = -1;
        Process process = null;
        File tmpScript = null;

        try {
            File tmpDir = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (!tmpDir.exists()) tmpDir.mkdirs();
            tmpScript = new File(tmpDir, "pi_install_" + System.currentTimeMillis() + ".sh");

            try (FileWriter fw = new FileWriter(tmpScript)) {
                fw.write("#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n");
                fw.write(command);
                fw.write("\n");
            }
            tmpScript.setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                tmpScript.getAbsolutePath()
            );
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
            pb.environment().put("NODE_OPTIONS", "--dns-result-order=ipv4first");
            pb.redirectErrorStream(true);

            process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, stdout.toString(),
                    "command timed out after " + timeoutSeconds + " seconds", -1);
            }

            exitCode = process.exitValue();
            return new CommandResult(exitCode == 0, stdout.toString(), "", exitCode);
        } catch (Exception e) {
            return new CommandResult(false, stdout.toString(), "Exception: " + e.getMessage(), exitCode);
        } finally {
            if (tmpScript != null) tmpScript.delete();
            if (process != null) process.destroy();
        }
    }
}
