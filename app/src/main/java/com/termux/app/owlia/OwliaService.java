package com.termux.app.owlia;

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

/**
 * Background service for executing Owlia-related commands and managing gateway lifecycle.
 * Handles OpenClaw installation, configuration, and gateway control without showing terminal UI.
 */
public class OwliaService extends Service {

    private static final String LOG_TAG = "OwliaService";

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public class LocalBinder extends Binder {
        public OwliaService getService() {
            return OwliaService.this;
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

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);

            // Set Termux environment variables
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);

            pb.redirectErrorStream(false);

            Logger.logDebug(LOG_TAG, "Executing: " + command);
            Process process = pb.start();

            // Read stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    Logger.logVerbose(LOG_TAG, "stdout: " + line);
                }
            }

            // Read stderr
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                    Logger.logVerbose(LOG_TAG, "stderr: " + line);
                }
            }

            exitCode = process.waitFor();
            Logger.logDebug(LOG_TAG, "Command exited with code: " + exitCode);

            return new CommandResult(exitCode == 0, stdout.toString(), stderr.toString(), exitCode);

        } catch (IOException | InterruptedException e) {
            Logger.logError(LOG_TAG, "Command execution failed: " + e.getMessage());
            return new CommandResult(false, stdout.toString(),
                stderr.toString() + "\nException: " + e.getMessage(), exitCode);
        }
    }

    /**
     * Install OpenClaw with progress callbacks
     * Steps:
     * 0 - Fix permissions
     * 1 - Verify Node.js and npm
     * 2 - Install OpenClaw via npm
     */
    public void installOpenclaw(InstallProgressCallback callback) {
        mExecutor.execute(() -> {
            // Step 0: Fix permissions
            mHandler.post(() -> callback.onStepStart(0, "Fixing permissions..."));

            CommandResult chmodResult = executeCommandSync(
                "chmod +x $PREFIX/bin/* 2>/dev/null; " +
                "chmod +x $PREFIX/lib/node_modules/.bin/* 2>/dev/null; " +
                "exit 0"
            );

            mHandler.post(() -> callback.onStepComplete(0));

            // Step 1: Verify Node.js and npm
            mHandler.post(() -> callback.onStepStart(1, "Verifying Node.js..."));

            CommandResult verifyResult = executeCommandSync("node --version && npm --version");

            if (!verifyResult.success) {
                mHandler.post(() -> callback.onError(
                    "Node.js not found. Bootstrap installation may be incomplete.\n\n" +
                    verifyResult.stderr
                ));
                return;
            }

            Logger.logInfo(LOG_TAG, "Node.js version: " + verifyResult.stdout.trim());
            mHandler.post(() -> callback.onStepComplete(1));

            // Step 2: Install OpenClaw
            mHandler.post(() -> callback.onStepStart(2, "Installing OpenClaw..."));

            CommandResult installResult = executeCommandSync(
                "npm install -g openclaw@latest --ignore-scripts"
            );

            if (!installResult.success) {
                mHandler.post(() -> callback.onError(
                    "Failed to install OpenClaw:\n\n" +
                    installResult.stderr
                ));
                return;
            }

            Logger.logInfo(LOG_TAG, "OpenClaw installed successfully");
            mHandler.post(() -> {
                callback.onStepComplete(2);
                callback.onComplete();
            });
        });
    }

    /**
     * Check if bootstrap (Node.js) is installed
     */
    public static boolean isBootstrapInstalled() {
        return new java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/node").exists();
    }

    /**
     * Check if OpenClaw is installed
     */
    public static boolean isOpenclawInstalled() {
        return new java.io.File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules/openclaw").exists();
    }

    /**
     * Check if OpenClaw config exists
     */
    public static boolean isOpenclawConfigured() {
        return new java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.config/openclaw/openclaw.json").exists();
    }

    /**
     * Build a command with proper Termux environment
     */
    private String withTermuxEnv(String command) {
        return "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + " && " +
               "export PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + " && " +
               "export PATH=$PREFIX/bin:$PATH && " +
               "export TMPDIR=$PREFIX/tmp && " +
               command;
    }

    /**
     * Start the OpenClaw gateway
     */
    public void startGateway(CommandCallback callback) {
        executeCommand(withTermuxEnv("openclaw gateway start"), callback);
    }

    /**
     * Stop the OpenClaw gateway
     */
    public void stopGateway(CommandCallback callback) {
        executeCommand(withTermuxEnv("openclaw gateway stop"), callback);
    }

    /**
     * Restart the OpenClaw gateway
     */
    public void restartGateway(CommandCallback callback) {
        executeCommand(withTermuxEnv("openclaw gateway restart"), callback);
    }

    /**
     * Get the OpenClaw gateway status
     */
    public void getGatewayStatus(CommandCallback callback) {
        executeCommand(withTermuxEnv("openclaw gateway status"), callback);
    }

    /**
     * Check if the gateway is currently running
     */
    public void isGatewayRunning(CommandCallback callback) {
        executeCommand("pgrep -f 'node.*openclaw.*gateway' > /dev/null && echo 'running' || echo 'stopped'", callback);
    }

    /**
     * Get gateway uptime in a human-readable format
     * Returns uptime string or null if gateway is not running
     */
    public void getGatewayUptime(CommandCallback callback) {
        // Get the gateway process start time and calculate uptime
        String cmd = "if pgrep -f 'node.*openclaw.*gateway' > /dev/null; then " +
            "pid=$(pgrep -f 'node.*openclaw.*gateway' | head -1); " +
            "ps -p $pid -o etime= 2>/dev/null || echo '—'; " +
            "else echo '—'; fi";
        executeCommand(cmd, callback);
    }
}
