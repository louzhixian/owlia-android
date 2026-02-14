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
    private volatile boolean mUpdateInProgress = false;

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
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keep the service alive even if no Activity is bound. GatewayMonitorService depends on
        // this service to execute gateway control commands while the app is backgrounded.
        return START_STICKY;
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
     * Callback for OpenClaw update progress
     */
    public interface UpdateProgressCallback {
        void onStepStart(String message);
        void onError(String error);
        void onComplete(String newVersion);
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
     * Execute a shell command synchronously with default 60-second timeout
     */
    private CommandResult executeCommandSync(String command) {
        return executeCommandSync(command, 60);
    }

    /**
     * Execute a shell command synchronously with configurable timeout
     */
    private CommandResult executeCommandSync(String command, int timeoutSeconds) {
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
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Logger.logError(LOG_TAG, "Command timeout after " + timeoutSeconds + " seconds");
                return new CommandResult(false, stdout.toString(),
                    "Command timeout after " + timeoutSeconds + " seconds", -1);
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
            "pkill -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then\n" +
            "  kill $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null\n" +
            "  rm -f " + GATEWAY_PID_FILE + "\n" +
            "  sleep 1\n" +
            "fi\n" +
            "sleep 1\n" +
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
        // PID files can be stale and the gateway may spawn children. Use best-effort cleanup to
        // prevent port 18789 conflicts and restart storms.
        String cmd =
            "PID=''\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then PID=$(cat " + GATEWAY_PID_FILE + " 2>/dev/null || true); fi\n" +
            "rm -f " + GATEWAY_PID_FILE + "\n" +
            "if [ -n \"$PID\" ]; then\n" +
            "  kill \"$PID\" 2>/dev/null || true\n" +
            "  pkill -TERM -P \"$PID\" 2>/dev/null || true\n" +
            "fi\n" +
            "pkill -TERM -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "sleep 1\n" +
            "pkill -9 -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "echo stopped\n";
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
        // Don't rely only on PID file (can be stale after crashes or upgrades).
        String cmd =
            "if [ -f " + GATEWAY_PID_FILE + " ] && kill -0 $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null; then\n" +
            "  echo running\n" +
            "  exit 0\n" +
            "fi\n" +
            "if pgrep -f \"openclaw.*gateway\" >/dev/null 2>&1; then\n" +
            "  echo running\n" +
            "else\n" +
            "  echo stopped\n" +
            "fi\n";
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

    /**
     * Whether an OpenClaw update is currently in progress.
     * Checked by GatewayMonitorService to suppress auto-restart during updates.
     */
    public boolean isUpdateInProgress() {
        return mUpdateInProgress;
    }

    /**
     * Update OpenClaw to the specified version from npm.
     * Stops the gateway, runs npm install, recreates the Android-specific wrapper,
     * and restarts the gateway. Reports progress via callback on the main thread.
     *
     * Must run on mExecutor — calls executeCommandSync directly to avoid deadlock
     * (the public stopGateway/startGateway methods also post to mExecutor).
     */
    public void updateOpenclaw(String targetVersion, UpdateProgressCallback callback) {
        final String packageVersion = normalizeOpenclawVersion(targetVersion);
        final java.util.concurrent.atomic.AtomicBoolean notified = new java.util.concurrent.atomic.AtomicBoolean(false);

        mExecutor.execute(() -> {
            mUpdateInProgress = true;
            try {
                // Step 1: Stop gateway
                Logger.logInfo(LOG_TAG, "Update: stopping gateway");
                notifyUpdateStep(callback, "Stopping gateway...");
                String stopCmd = buildStopGatewayScript();
                CommandResult stopResult = executeCommandSync(stopCmd, 60);
                if (!stopResult.success) {
                    // Non-fatal — gateway may not be running
                    Logger.logWarn(LOG_TAG, "Gateway stop returned non-zero: " + stopResult.stdout);
                }
                Thread.sleep(2000);

                // Step 2: npm install
                Logger.logInfo(LOG_TAG, "Update: running npm install");
                notifyUpdateStep(callback, "Installing update...");
                String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
                String safePackage = shellQuoteSingle(packageVersion);
                String npmCmd =
                    "export PREFIX=" + prefix + "\n" +
                    "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + "\n" +
                    "export PATH=$PREFIX/bin:$PATH\n" +
                    "export TMPDIR=$PREFIX/tmp\n" +
                    "export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem\n" +
                    "export NODE_OPTIONS=--dns-result-order=ipv4first\n" +
                    "npm install -g " + safePackage + " --ignore-scripts --force 2>&1\n";
                CommandResult npmResult = executeCommandSync(npmCmd, 300);
                if (!npmResult.success) {
                    String tail = extractTail(npmResult.stdout, 15);
                    String error = "npm install failed (exit " + npmResult.exitCode + ")\n" + tail;
                    Logger.logError(LOG_TAG, "Update npm install failed: " + error);
                    notifyUpdateError(callback, notified, error);
                    return;
                }

                // Step 3: Recreate the Android-specific wrapper
                // npm install overwrites $PREFIX/bin/openclaw with its own shim which
                // doesn't work on Android/proot. We must recreate the custom wrapper.
                Logger.logInfo(LOG_TAG, "Update: recreating openclaw wrapper");
                notifyUpdateStep(callback, "Finalizing...");
                String binPrefix = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                String wrapperCmd =
                    "export PREFIX=" + prefix + "\n" +
                    "cat > $PREFIX/bin/openclaw <<'BOTDROP_OPENCLAW_WRAPPER'\n" +
                    "#!" + binPrefix + "/bash\n" +
                    "PREFIX=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                    "ENTRY=\"\"\n" +
                    "for CANDIDATE in \\\n" +
                    "  \"$PREFIX/lib/node_modules/openclaw/dist/cli.js\" \\\n" +
                    "  \"$PREFIX/lib/node_modules/openclaw/bin/openclaw.js\" \\\n" +
                    "  \"$PREFIX/lib/node_modules/openclaw/dist/index.js\"; do\n" +
                    "  if [ -f \"$CANDIDATE\" ]; then\n" +
                    "    ENTRY=\"$CANDIDATE\"\n" +
                    "    break\n" +
                    "  fi\n" +
                    "done\n" +
                    "if [ -z \"$ENTRY\" ]; then\n" +
                    "  echo \"openclaw entrypoint not found under $PREFIX/lib/node_modules/openclaw\" >&2\n" +
                    "  exit 127\n" +
                    "fi\n" +
                    "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n" +
                    "export NODE_OPTIONS=\"--dns-result-order=ipv4first\"\n" +
                    "exec \"$PREFIX/bin/termux-chroot\" \"$PREFIX/bin/node\" \"$ENTRY\" \"$@\"\n" +
                    "BOTDROP_OPENCLAW_WRAPPER\n" +
                    "chmod 755 $PREFIX/bin/openclaw\n" +
                    "echo done\n";
                CommandResult wrapperResult = executeCommandSync(wrapperCmd, 30);
                if (!wrapperResult.success) {
                    Logger.logError(LOG_TAG, "Update: failed to recreate wrapper");
                    notifyUpdateError(callback, notified, "Failed to recreate openclaw wrapper");
                    return;
                }

                // Step 4: Start gateway
                Logger.logInfo(LOG_TAG, "Update: starting gateway");
                notifyUpdateStep(callback, "Starting gateway...");
                BotDropConfig.sanitizeLegacyConfig();
                String startCmd = buildStartGatewayScript();
                CommandResult startResult = executeCommandSync(startCmd, 60);

                String newVersion = getOpenclawVersion();
                String versionStr = newVersion != null ? newVersion : "unknown";

                if (startResult.success) {
                    Logger.logInfo(LOG_TAG, "Update complete, new version: " + versionStr);
                    notifyUpdateComplete(callback, notified, versionStr);
                } else {
                    Logger.logWarn(LOG_TAG, "Update complete but gateway restart failed");
                    String tail = extractTail(startResult.stdout, 20);
                    notifyUpdateError(callback, notified,
                        "Gateway restart failed (exit " + startResult.exitCode + "):\n" + tail);
                }

            } catch (InterruptedException e) {
                Logger.logError(LOG_TAG, "Update interrupted: " + e.getMessage());
                notifyUpdateError(callback, notified, "Update interrupted");
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Update failed: " + e.getMessage());
                notifyUpdateError(callback, notified, "Update failed: " + e.getMessage());
            } finally {
                mUpdateInProgress = false;
            }
        });
    }

    /**
     * Build the stop-gateway shell script (same logic as stopGateway but returns the string
     * instead of executing it, so it can be used from within updateOpenclaw on mExecutor).
     */
    private String buildStopGatewayScript() {
        return "PID=''\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then PID=$(cat " + GATEWAY_PID_FILE + " 2>/dev/null || true); fi\n" +
            "rm -f " + GATEWAY_PID_FILE + "\n" +
            "if [ -n \"$PID\" ]; then\n" +
            "  kill \"$PID\" 2>/dev/null || true\n" +
            "  pkill -TERM -P \"$PID\" 2>/dev/null || true\n" +
            "fi\n" +
            "pkill -TERM -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "sleep 1\n" +
            "pkill -9 -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "echo stopped\n";
    }

    private String normalizeOpenclawVersion(String targetVersion) {
        if (targetVersion == null) {
            return "openclaw@latest";
        }

        String trimmed = targetVersion.trim();
        if (trimmed.isEmpty() || "openclaw".equals(trimmed) || "latest".equals(trimmed)) {
            return "openclaw@latest";
        }

        if (trimmed.startsWith("openclaw@")) {
            return trimmed;
        }
        return "openclaw@" + trimmed;
    }

    private String shellQuoteSingle(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void notifyUpdateStep(UpdateProgressCallback callback, String message) {
        if (callback == null) {
            return;
        }
        mHandler.post(() -> {
            try {
                callback.onStepStart(message);
            } catch (Throwable e) {
                Logger.logWarn(LOG_TAG, "Update progress callback failed: " + e.getMessage());
            }
        });
    }

    private void notifyUpdateError(
        UpdateProgressCallback callback,
        java.util.concurrent.atomic.AtomicBoolean notified,
        String error
    ) {
        if (callback == null || !notified.compareAndSet(false, true)) {
            return;
        }
        mHandler.post(() -> {
            try {
                callback.onError(error);
            } catch (Throwable e) {
                Logger.logWarn(LOG_TAG, "Update error callback failed: " + e.getMessage());
            }
        });
    }

    private void notifyUpdateComplete(
        UpdateProgressCallback callback,
        java.util.concurrent.atomic.AtomicBoolean notified,
        String version
    ) {
        if (callback == null || !notified.compareAndSet(false, true)) {
            return;
        }
        mHandler.post(() -> {
            try {
                callback.onComplete(version);
            } catch (Throwable e) {
                Logger.logWarn(LOG_TAG, "Update complete callback failed: " + e.getMessage());
            }
        });
    }

    /**
     * Build the start-gateway shell script (same logic as startGateway but returns the string
     * instead of executing it, so it can be used from within updateOpenclaw on mExecutor).
     */
    private String buildStartGatewayScript() {
        String logDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw";
        String debugLog = logDir + "/gateway-debug.log";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        return "mkdir -p " + logDir + "\n" +
            "exec 2>" + debugLog + "\n" +
            "set -x\n" +
            "echo \"date: $(date)\" >&2\n" +
            "echo \"id: $(id)\" >&2\n" +
            "echo \"PATH=$PATH\" >&2\n" +
            "pgrep -x sshd || sshd || true\n" +
            "pkill -f \"openclaw.*gateway\" 2>/dev/null || true\n" +
            "if [ -f " + GATEWAY_PID_FILE + " ]; then\n" +
            "  kill $(cat " + GATEWAY_PID_FILE + ") 2>/dev/null\n" +
            "  rm -f " + GATEWAY_PID_FILE + "\n" +
            "  sleep 1\n" +
            "fi\n" +
            "sleep 1\n" +
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
            "  cat " + GATEWAY_LOG_FILE + "\n" +
            "  echo '---'\n" +
            "  cat " + debugLog + "\n" +
            "  exit 1\n" +
            "fi\n";
    }

    /**
     * Extract the last N lines from a string for error reporting.
     */
    private static String extractTail(String text, int maxLines) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

}
