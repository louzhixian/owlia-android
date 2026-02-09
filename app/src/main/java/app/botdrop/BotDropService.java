package app.botdrop;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background service for executing BotDrop-related commands and managing gateway lifecycle.
 * Handles OpenClaw installation, configuration, and gateway control without showing terminal UI.
 */
public class BotDropService extends Service {

    private static final String LOG_TAG = "BotDropService";

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public class LocalBinder extends Binder {
        public BotDropService getService() {
            return BotDropService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logDebug(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logDebug(LOG_TAG, "onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
        Logger.logDebug(LOG_TAG, "onDestroy");
    }

    /**
     * Result of a command execution
     */
    public static class CommandResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        public CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    /**
     * Callback for async command execution
     */
    public interface CommandCallback {
        void onResult(CommandResult result);
    }

    /**
     * Callback for installation progress
     */
    public interface InstallProgressCallback {
        void onStepStart(int step, String message);
        void onStepComplete(int step);
        void onError(String error);
        void onComplete();
    }

    /**
     * Execute a shell command in the Termux environment
     */
    public void executeCommand(String command, CommandCallback callback) {
        mExecutor.execute(() -> {
            CommandResult result = executeCommandSync(command);
            mHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Execute a shell command synchronously
     */
    private CommandResult executeCommandSync(String command) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;
        Process process = null;
        java.io.File tmpScript = null;

        try {
            // Write command to temp script file (same approach as installOpenclaw —
            // ProcessBuilder with script files works reliably, bash -c does not)
            java.io.File tmpDir = new java.io.File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (!tmpDir.exists()) tmpDir.mkdirs();
            tmpScript = new java.io.File(tmpDir,
                "cmd_" + System.currentTimeMillis() + ".sh");
            try (java.io.FileWriter fw = new java.io.FileWriter(tmpScript)) {
                fw.write("#!" + com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n");
                fw.write(command);
                fw.write("\n");
            }
            tmpScript.setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", tmpScript.getAbsolutePath());

            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            // Set SSL_CERT_FILE for Node.js fetch to find CA certificates
            pb.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
            // Prefer IPv4 first; avoids long IPv6 connect stalls in Android/proot environments.
            pb.environment().put("NODE_OPTIONS", "--dns-result-order=ipv4first");

            pb.redirectErrorStream(true);

            Logger.logDebug(LOG_TAG, "Executing: " + command);
            process = pb.start();

            boolean isModelListCommand = command.contains("openclaw models list");
            int loggedLines = 0;
            final int MAX_VERBOSE_LINES = 20;

            // Read stdout (stderr is merged via redirectErrorStream)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    if (!isModelListCommand || loggedLines < MAX_VERBOSE_LINES) {
                        Logger.logVerbose(LOG_TAG, "stdout: " + line);
                        loggedLines++;
                    }
                }
            }

            // Wait with timeout to prevent hanging indefinitely
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Logger.logError(LOG_TAG, "Command timeout after 60 seconds");
                return new CommandResult(false, stdout.toString(),
                    "Command timeout after 60 seconds", -1);
            }

            exitCode = process.exitValue();
            Logger.logDebug(LOG_TAG, "Command exited with code: " + exitCode);

            return new CommandResult(exitCode == 0, stdout.toString(), stderr.toString(), exitCode);

        } catch (IOException | InterruptedException e) {
            Logger.logError(LOG_TAG, "Command execution failed: " + e.getMessage());
            if (process != null) {
                process.destroy();
            }
            return new CommandResult(false, stdout.toString(),
                stderr.toString() + "\nException: " + e.getMessage(), exitCode);
        } finally {
            if (tmpScript != null) tmpScript.delete();
        }
    }

    /**
     * Install OpenClaw by calling the standalone install.sh script.
     * Parses structured output lines for progress reporting:
     *   BOTDROP_STEP:N:START:message  → callback.onStepStart(N, message)
     *   BOTDROP_STEP:N:DONE           → callback.onStepComplete(N)
     *   BOTDROP_COMPLETE              → callback.onComplete()
     *   BOTDROP_ERROR:message         → callback.onError(message)
     *   BOTDROP_ALREADY_INSTALLED     → callback.onComplete()
     */
    public void installOpenclaw(InstallProgressCallback callback) {
        final String INSTALL_SCRIPT = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/botdrop/install.sh";

        mExecutor.execute(() -> {
            // Verify install script exists
            if (!new java.io.File(INSTALL_SCRIPT).exists()) {
                mHandler.post(() -> callback.onError(
                    "Install script not found at " + INSTALL_SCRIPT +
                    "\nBootstrap may be incomplete."
                ));
                return;
            }

            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                    INSTALL_SCRIPT
                );

                pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
                pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
                pb.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
                pb.redirectErrorStream(true);

                Logger.logInfo(LOG_TAG, "Starting install via " + INSTALL_SCRIPT);
                process = pb.start();

                // Collect last lines of output for error reporting
                final java.util.LinkedList<String> recentLines = new java.util.LinkedList<>();
                final int MAX_RECENT = 20;

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Logger.logVerbose(LOG_TAG, "install.sh: " + line);
                        parseInstallOutput(line, callback);
                        recentLines.add(line);
                        if (recentLines.size() > MAX_RECENT) recentLines.remove(0);
                    }
                }

                boolean finished = process.waitFor(300, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    mHandler.post(() -> callback.onError("Installation timed out after 5 minutes"));
                    return;
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    StringBuilder tail = new StringBuilder();
                    for (String l : recentLines) tail.append(l).append("\n");
                    String output = tail.toString();
                    mHandler.post(() -> callback.onError(
                        "Installation failed (exit code " + exitCode + ")\n\n" + output));
                }

            } catch (IOException | InterruptedException e) {
                Logger.logError(LOG_TAG, "Installation failed: " + e.getMessage());
                if (process != null) {
                    process.destroy();
                }
                String msg = e.getMessage();
                mHandler.post(() -> callback.onError("Installation error: " + msg));
            }
        });
    }

    /**
     * Parse a single line of structured output from install.sh
     */
    private void parseInstallOutput(String line, InstallProgressCallback callback) {
        if (line.startsWith("BOTDROP_STEP:")) {
            // Format: BOTDROP_STEP:N:START:message or BOTDROP_STEP:N:DONE
            String[] parts = line.split(":", 4);
            if (parts.length >= 3) {
                try {
                    int step = Integer.parseInt(parts[1]);
                    String action = parts[2];
                    if ("START".equals(action)) {
                        String message = parts.length >= 4 ? parts[3] : "";
                        mHandler.post(() -> callback.onStepStart(step, message));
                    } else if ("DONE".equals(action)) {
                        mHandler.post(() -> callback.onStepComplete(step));
                    }
                } catch (NumberFormatException e) {
                    Logger.logWarn(LOG_TAG, "Invalid step number in: " + line);
                }
            }
        } else if ("BOTDROP_COMPLETE".equals(line.trim())) {
            Logger.logInfo(LOG_TAG, "Installation complete");
            mHandler.post(callback::onComplete);
        } else if ("BOTDROP_ALREADY_INSTALLED".equals(line.trim())) {
            Logger.logInfo(LOG_TAG, "Already installed, skipping");
            mHandler.post(callback::onComplete);
        } else if (line.startsWith("BOTDROP_ERROR:")) {
            String error = line.substring("BOTDROP_ERROR:".length());
            mHandler.post(() -> callback.onError(error));
        }
        // Other lines (npm output, etc.) are logged but not parsed
    }

    /**
     * Check if bootstrap (Node.js) is installed
     */
    public static boolean isBootstrapInstalled() {
        return new java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/node").exists();
    }

    /**
     * Check if OpenClaw is installed (check binary, not just module directory)
     */
    public static boolean isOpenclawInstalled() {
        // Check if the openclaw binary exists and is executable
        java.io.File binary = new java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/openclaw");
        return binary.exists() && binary.canExecute();
    }

    /**
     * Get OpenClaw version (synchronously)
     */
    public static String getOpenclawVersion() {
        try {
            java.io.File packageJson = new java.io.File(
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules/openclaw/package.json"
            );
            if (packageJson.exists()) {
                // Use try-with-resources to avoid resource leak
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(packageJson)
                )) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                    
                    // Use JSONObject for reliable parsing
                    org.json.JSONObject json = new org.json.JSONObject(content.toString());
                    return json.optString("version", null);
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to get OpenClaw version: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if OpenClaw config exists
     */
    public static boolean isOpenclawConfigured() {
        return new java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/openclaw.json").exists();
    }

    /**
     * Build a command with proper Termux environment.
     * Does NOT use termux-chroot — for non-openclaw commands only.
     */
    private String withTermuxEnv(String command) {
        return "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + " && " +
               "export PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + " && " +
               "export PATH=$PREFIX/bin:$PATH && " +
               "export TMPDIR=$PREFIX/tmp && " +
               "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem && " +
               "export NODE_OPTIONS=--dns-result-order=ipv4first && " +
               command;
    }

    /**
     * Build an openclaw command wrapped in termux-chroot.
     * Required: Android kernel blocks os.networkInterfaces() which OpenClaw needs.
     * termux-chroot (proot) provides a virtual chroot that bypasses this limitation.
     */
    private String withTermuxChroot(String openclawArgs) {
        return "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + " && " +
               "export PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + " && " +
               "export PATH=$PREFIX/bin:$PATH && " +
               "export TMPDIR=$PREFIX/tmp && " +
               "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem && " +
               "export NODE_OPTIONS=--dns-result-order=ipv4first && " +
               // `openclaw` is installed as a wrapper that already runs under `termux-chroot`.
               // Avoid nesting proot/termux-chroot, which can stall gateway startup for minutes.
               "openclaw " + openclawArgs;
    }

    private static final String GATEWAY_PID_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway.pid";
    private static final String GATEWAY_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway.log";

    public void startGateway(CommandCallback callback) {
        // Ensure legacy config keys are repaired right before starting the gateway.
        // This matters for in-place upgrades where users won't re-run channel setup.
        BotDropConfig.sanitizeLegacyConfig();

        String logDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw";
        String debugLog = logDir + "/gateway-debug.log";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        // Shell trace (set -x) goes to debug log via fd 2 redirect;
        // stdout still goes back to Java for success/error reporting.
        String cmd =
            "mkdir -p " + logDir + "\n" +
            "exec 2>" + debugLog + "\n" +
            "set -x\n" +
            "echo \"date: $(date)\" >&2\n" +
            "echo \"id: $(id)\" >&2\n" +
            "echo \"PATH=$PATH\" >&2\n" +
            "# sshd\n" +
            "pgrep -x sshd || sshd || true\n" +
            "# kill old gateway\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then\n" +
            "  kill $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null\n" +
            "  rm -f " + GATEWAY_PID_FILE + "\n" +
            "  sleep 1\n" +
            "fi\n" +
            "# start gateway\n" +
            "echo '' > " + GATEWAY_LOG_FILE + "\n" +
            "export HOME=" + home + "\n" +
            "export PREFIX=" + prefix + "\n" +
            "export PATH=$PREFIX/bin:$PATH\n" +
            "export TMPDIR=$PREFIX/tmp\n" +
            "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem\n" +
            "export NODE_OPTIONS=--dns-result-order=ipv4first\n" +
            "echo \"=== Environment before chroot ===\" >&2\n" +
            "echo \"SSL_CERT_FILE=$SSL_CERT_FILE\" >&2\n" +
            "echo \"NODE_OPTIONS=$NODE_OPTIONS\" >&2\n" +
            "echo \"Testing cert file access:\" >&2\n" +
            "ls -lh $PREFIX/etc/tls/cert.pem >&2 || echo \"cert.pem not found!\" >&2\n" +
            "# Start gateway (openclaw wrapper handles termux-chroot)\n" +
            "openclaw gateway run --force >> " + GATEWAY_LOG_FILE + " 2>&1 &\n" +
            "GW_PID=$!\n" +
            "echo $GW_PID > " + GATEWAY_PID_FILE + "\n" +
            "echo \"gateway pid: $GW_PID\" >&2\n" +
            "sleep 3\n" +
            "if kill -0 $GW_PID 2>/dev/null; then\n" +
            "  echo started\n" +
            "else\n" +
            "  echo \"gateway died, log:\" >&2\n" +
            "  cat " + GATEWAY_LOG_FILE + " >&2\n" +
            "  rm -f " + GATEWAY_PID_FILE + "\n" +
            "  # return error info to Java via stdout\n" +
            "  cat " + GATEWAY_LOG_FILE + "\n" +
            "  echo '---'\n" +
            "  cat " + debugLog + "\n" +
            "  exit 1\n" +
            "fi\n";
        executeCommand(cmd, callback);
    }

    public void stopGateway(CommandCallback callback) {
        String cmd = "if [ -f " + GATEWAY_PID_FILE + " ]; then " +
            "kill $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null && echo 'stopped' || echo 'not running'; " +
            "rm -f " + GATEWAY_PID_FILE + "; " +
            "else echo 'not running'; fi";
        executeCommand(cmd, callback);
    }

    public void restartGateway(CommandCallback callback) {
        stopGateway(result -> {
            // Brief delay to let process fully terminate
            mHandler.postDelayed(() -> startGateway(callback), 1000);
        });
    }

    public void getGatewayStatus(CommandCallback callback) {
        isGatewayRunning(callback);
    }

    /**
     * Check if the gateway is currently running using PID file
     */
    public void isGatewayRunning(CommandCallback callback) {
        String cmd = "if [ -f " + GATEWAY_PID_FILE + " ] && kill -0 $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null; then " +
            "echo 'running'; else echo 'stopped'; fi";
        executeCommand(cmd, callback);
    }

    /**
     * Get gateway uptime in a human-readable format
     */
    public void getGatewayUptime(CommandCallback callback) {
        String cmd = "if [ -f " + GATEWAY_PID_FILE + " ]; then " +
            "pid=$(cat " + GATEWAY_PID_FILE + "); " +
            "if kill -0 $pid 2>/dev/null; then " +
            "ps -p $pid -o etime= 2>/dev/null || echo '—'; " +
            "else echo '—'; fi; " +
            "else echo '—'; fi";
        executeCommand(cmd, callback);
    }

}
