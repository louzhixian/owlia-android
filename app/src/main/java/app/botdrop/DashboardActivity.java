package app.botdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Environment;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Dashboard activity - main screen after setup is complete.
 * Shows gateway status, connected channels, and control buttons.
 * Auto-refreshes status every 5 seconds.
 */
public class DashboardActivity extends Activity {

    private static final String LOG_TAG = "DashboardActivity";
    public static final String NOTIFICATION_CHANNEL_ID = "botdrop_gateway";
    private static final int STATUS_REFRESH_INTERVAL_MS = 5000; // 5 seconds
    private static final int ERROR_CHECK_INTERVAL_MS = 15000; // 15 seconds
    private static final String MODEL_LIST_COMMAND = "openclaw models list --all --plain";
    private static final String MODEL_PREFS_NAME = "openclaw_model_cache_v1";
    private static final String MODEL_CACHE_KEY_PREFIX = "models_by_version_";
    private static final int GATEWAY_LOG_TAIL_LINES = 300;
    private static final int GATEWAY_DEBUG_LOG_TAIL_LINES = 120;
    private static final int OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT = 8;
    private static final int OPENCLAW_WEB_UI_REACHABILITY_RETRY_DELAY_MS = 700;
    private static final String OPENCLAW_DASHBOARD_COMMAND = "openclaw dashboard --no-open 2>&1";
    private static final int OPENCLAW_DEFAULT_WEB_UI_PORT = 18789;
    private static final String OPENCLAW_DEFAULT_WEB_UI_PATH = "/";
    private static final String OPENCLAW_DEFAULT_WEB_UI_URL = "http://127.0.0.1:" + OPENCLAW_DEFAULT_WEB_UI_PORT + OPENCLAW_DEFAULT_WEB_UI_PATH;
    private static final String OPENCLAW_WEB_UI_TOKEN_KEY = "token";
    private static final String OPENCLAW_WEB_UI_BUTTON_TEXT_DEFAULT = "Open Web UI";
    private static final String OPENCLAW_WEB_UI_BUTTON_TEXT_PENDING = "Opening Web UI";
    private static final String OPENCLAW_HOME_FOLDER = ".openclaw";
    private static final String BOTDROP_HOME_FOLDER = "botdrop";
    private static final String GATEWAY_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway.log";
    private static final String GATEWAY_DEBUG_LOG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/gateway-debug.log";
    private static final String OPENCLAW_BACKUP_DIRECTORY = "BotDrop/openclaw";
    private static final String OPENCLAW_BACKUP_FILE_PREFIX = "openclaw-config-backup-";
    private static final String OPENCLAW_BACKUP_FILE_EXTENSION = ".zip";
    private static final String OPENCLAW_BACKUP_FILE_EXTENSION_JSON = ".json";
    private static final String OPENCLAW_CONFIG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/openclaw.json";
    private static final String OPENCLAW_AUTH_PROFILES_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/agents/main/agent/auth-profiles.json";
    private static final String OPENCLAW_BACKUP_DATE_PATTERN = "yyyyMMdd_HHmmss";
    private static final String OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY = "openclawConfig";
    private static final String OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY = "authProfiles";
    private static final String OPENCLAW_BACKUP_META_CREATED_AT_KEY = "createdAt";
    private static final int OPENCLAW_BACKUP_IO_BUFFER_SIZE = 8192;
    private static final int OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE = 3001;
    private static final String OPENCLAW_RESTORE_STAGING_DIR_PREFIX = ".openclaw_restore_staging_";
    private static final String OPENCLAW_RESTORE_BACKUP_DIR_PREFIX = ".openclaw_restore_backup_";
    private static final String BOTDROP_RESTORE_BACKUP_DIR_PREFIX = ".botdrop_restore_backup_";
    private static final Pattern WEB_UI_URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s\"'`<>\\)\\]}]+");
    private static final Pattern HOST_PORT_PATTERN =
            Pattern.compile("(?i)\\b(127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[[0-9a-f:]+\\]|[a-z0-9._-]+):(\\d{2,5})\\b");
    private static final Pattern GATEWAY_TOKEN_QUERY_PATTERN =
            Pattern.compile("(?i)token=([^\\s\"'`<>\\)\\]}&]+)");
    private static final String VIEW_OPENCLAW_LOG_COMMAND =
            "if [ -f " + GATEWAY_LOG_FILE + " ]; then\n" +
            "  echo '=== OpenClaw gateway.log (tail " + GATEWAY_LOG_TAIL_LINES + " lines) ===';\n" +
            "  tail -n " + GATEWAY_LOG_TAIL_LINES + " " + GATEWAY_LOG_FILE + "\n" +
            "else\n" +
            "  echo 'No gateway.log at " + GATEWAY_LOG_FILE + "'\n" +
            "fi\n" +
            "if [ -f " + GATEWAY_DEBUG_LOG_FILE + " ]; then\n" +
            "  echo '\\n=== OpenClaw gateway-debug.log (tail " + GATEWAY_DEBUG_LOG_TAIL_LINES + " lines) ===';\n" +
            "  tail -n " + GATEWAY_DEBUG_LOG_TAIL_LINES + " " + GATEWAY_DEBUG_LOG_FILE + "\n" +
            "else\n" +
            "  echo '\\nNo gateway-debug.log at " + GATEWAY_DEBUG_LOG_FILE + "'\n" +
            "fi\n";

    private TextView mStatusText;
    private TextView mUptimeText;
    private View mStatusIndicator;
    private TextView mTelegramStatus;
    private TextView mDiscordStatus;
    private View mTelegramChannelRow;
    private Button mStartButton;
    private Button mStopButton;
    private Button mRestartButton;
    private View mSshCard;
    private TextView mSshInfoText;
    private View mUpdateBanner;
    private TextView mUpdateBannerText;
    private TextView mCurrentModelText;
    private View mGatewayErrorBanner;
    private TextView mGatewayErrorText;
    private TextView mOpenclawVersionText;
    private TextView mOpenclawCheckUpdateButton;
    private TextView mOpenclawLogButton;
    private TextView mOpenclawWebUiButton;
    private TextView mOpenclawBackupButton;
    private TextView mOpenclawRestoreButton;
    private ImageButton mBackToAgentSelectionButton;
    private String mOpenclawLatestUpdateVersion;
    private AlertDialog mOpenclawUpdateDialog;
    private boolean mOpenclawManualCheckRequested;
    private boolean mUiVisible = true;
    private boolean mOpenclawWebUiOpening;

    private BotDropService mBotDropService;
    private boolean mBound = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mStatusRefreshRunnable;
    private long mLastErrorCheckAtMs = 0L;
    private String mLastErrorMessage;
    private Runnable mPendingOpenclawStorageAction;
    private Runnable mPendingOpenclawStorageDeniedAction;

    private interface ModelListPrefetchCallback {
        void onFinished(boolean success);
    }

    private interface OpenclawWebUiUrlCallback {
        void onUrlResolved(String url);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mBotDropService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");

            // Start status refresh
            startStatusRefresh();

            // Start gateway monitor service
            startGatewayMonitorService();

            // Load current model
            loadCurrentModel();

            // Check for OpenClaw updates
            checkOpenclawUpdate();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mBotDropService = null;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_dashboard);

        // Create notification channel
        createNotificationChannel();

        // Initialize views
        mStatusText = findViewById(R.id.status_text);
        mUptimeText = findViewById(R.id.uptime_text);
        mStatusIndicator = findViewById(R.id.status_indicator);
        mTelegramStatus = findViewById(R.id.telegram_status);
        mDiscordStatus = findViewById(R.id.discord_status);
        mTelegramChannelRow = findViewById(R.id.telegram_channel_row);
        mStartButton = findViewById(R.id.btn_start);
        mStopButton = findViewById(R.id.btn_stop);
        mRestartButton = findViewById(R.id.btn_restart);
        Button openTerminalButton = findViewById(R.id.btn_open_terminal);
        mCurrentModelText = findViewById(R.id.current_model_text);
        Button changeModelButton = findViewById(R.id.btn_change_model);
        mGatewayErrorBanner = findViewById(R.id.gateway_error_banner);
        mGatewayErrorText = findViewById(R.id.gateway_error_text);

        // Setup button listeners
        mStartButton.setOnClickListener(v -> startGateway());
        mStopButton.setOnClickListener(v -> stopGateway());
        mRestartButton.setOnClickListener(v -> restartGatewayForControl());
        openTerminalButton.setOnClickListener(v -> openTerminal());
        changeModelButton.setOnClickListener(v -> showModelSelector());

        mSshCard = findViewById(R.id.ssh_card);
        mSshInfoText = findViewById(R.id.ssh_info_text);

        // Update banner
        mUpdateBanner = findViewById(R.id.update_banner);
        mUpdateBannerText = findViewById(R.id.update_banner_text);

        // OpenClaw version + check button
        mOpenclawVersionText = findViewById(R.id.openclaw_version_text);
        mOpenclawCheckUpdateButton = findViewById(R.id.btn_check_openclaw_update);
        if (mOpenclawCheckUpdateButton != null) {
            mOpenclawCheckUpdateButton.setOnClickListener(v -> forceCheckOpenclawUpdate());
        }
        mOpenclawLogButton = findViewById(R.id.btn_view_openclaw_log);
        if (mOpenclawLogButton != null) {
            mOpenclawLogButton.setOnClickListener(v -> showOpenclawLog());
        }
        mBackToAgentSelectionButton = findViewById(R.id.btn_back_to_agent_selection);
        if (mBackToAgentSelectionButton != null) {
            mBackToAgentSelectionButton.setOnClickListener(v -> openAgentSelection());
        }
        mOpenclawWebUiButton = findViewById(R.id.btn_open_openclaw_web_ui);
        if (mOpenclawWebUiButton != null) {
            mOpenclawWebUiButton.setOnClickListener(v -> openOpenclawWebUi());
        }
        mOpenclawBackupButton = findViewById(R.id.btn_backup_openclaw_config);
        if (mOpenclawBackupButton != null) {
            mOpenclawBackupButton.setOnClickListener(v -> backupOpenclawConfigToSdcard());
        }
        mOpenclawRestoreButton = findViewById(R.id.btn_restore_openclaw_config);
        if (mOpenclawRestoreButton != null) {
            mOpenclawRestoreButton.setOnClickListener(v -> restoreOpenclawConfigFromSdcard());
        }
        if (mTelegramChannelRow != null) {
            mTelegramChannelRow.setOnClickListener(v -> openTelegramChannelConfig());
        }

        // Load channel info
        loadChannelInfo();

        // Load SSH info
        loadSshInfo();

        // Bind to service
        Intent intent = new Intent(this, BotDropService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        // Check for app updates (also picks up results from launcher check)
        UpdateChecker.check(this, new UpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String latestVersion, String downloadUrl, String notes) {
                showUpdateBanner(latestVersion, downloadUrl);
            }

            @Override
            public void onNoUpdate() {
                hideUpdateBanner();
            }
        });

        // Also check stored result in case launcher already fetched it
        String[] stored = UpdateChecker.getAvailableUpdate(this);
        if (stored != null) {
            showUpdateBanner(stored[0], stored[1]);
        }
    }

    private void openAgentSelection() {
        Intent intent = new Intent(this, SetupActivity.class);
        intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_AGENT_SELECT);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Cancel all pending callbacks to prevent memory leak
        mHandler.removeCallbacksAndMessages(null);
        mStatusRefreshRunnable = null;

        dismissOpenclawUpdateDialog();
        
        // Unbind from service
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mUiVisible = false;
        mOpenclawWebUiOpening = false;
        stopStatusRefresh();
        mHandler.removeCallbacksAndMessages(null);
        setOpenclawWebUiButtonState(false, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mUiVisible = true;
        if (mBound) {
            startStatusRefresh();
            refreshStatus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    private void stopStatusRefresh() {
        if (mStatusRefreshRunnable != null) {
            mHandler.removeCallbacks(mStatusRefreshRunnable);
            mStatusRefreshRunnable = null;
        }
    }

    private void setOpenclawWebUiButtonState(boolean opening, String statusText) {
        if (mOpenclawWebUiButton == null) {
            return;
        }

        mOpenclawWebUiButton.setEnabled(!opening);
        mOpenclawWebUiButton.setAlpha(opening ? 0.6f : 1f);

        if (TextUtils.isEmpty(statusText)) {
            mOpenclawWebUiButton.setText(OPENCLAW_WEB_UI_BUTTON_TEXT_DEFAULT);
        } else {
            mOpenclawWebUiButton.setText(statusText);
        }
    }

    private void backupOpenclawConfigToSdcard() {
        runWithOpenclawStoragePermission(() -> {
            setButtonEnabled(mOpenclawBackupButton, false);
            new Thread(() -> {
                String backupPath = createOpenclawBackupFile();
                runOnUiThread(() -> {
                    setButtonEnabled(mOpenclawBackupButton, true);
                    if (TextUtils.isEmpty(backupPath)) {
                        Toast.makeText(this, "No OpenClaw data folder available to backup", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(
                        this,
                        "OpenClaw backup created:\n"
                            + backupPath
                            + "\nIncludes full .openclaw and botdrop folders.",
                        Toast.LENGTH_LONG
                    ).show();
                });
            }).start();
        }, () -> Toast.makeText(
            this,
            "Storage permission is required to backup OpenClaw data",
            Toast.LENGTH_SHORT
        ).show());
    }

    private void restoreOpenclawConfigFromSdcard() {
        runWithOpenclawStoragePermission(() -> {
            File backupFile = getLatestOpenclawBackupFile();
            if (backupFile == null) {
                Toast.makeText(
                    this,
                    "No backup file found in: " + getOpenclawBackupDirectory().getAbsolutePath(),
                    Toast.LENGTH_SHORT
                ).show();
                return;
            }

            confirmOpenclawRestore(backupFile);
        }, () -> Toast.makeText(
            this,
            "Storage permission is required to restore OpenClaw data",
            Toast.LENGTH_SHORT
        ).show());
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action) {
        runWithOpenclawStoragePermission(action, null);
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action, @Nullable Runnable deniedAction) {
        File backupDir = getOpenclawBackupDirectory();
        if (isOpenclawStoragePermissionGranted()) {
            action.run();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (PermissionUtils.requestManageStorageExternalPermission(this, OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) == null) {
                mPendingOpenclawStorageAction = action;
                mPendingOpenclawStorageDeniedAction = deniedAction;
            } else if (deniedAction != null) {
                deniedAction.run();
            }
            return;
        }

        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermissionIfPathOnPrimaryExternalStorage(
            this,
            backupDir.getAbsolutePath(),
            OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE,
            true
        )) {
            action.run();
            return;
        }
        mPendingOpenclawStorageAction = action;
        mPendingOpenclawStorageDeniedAction = deniedAction;
    }

    private void retryPendingOpenclawStorageActionIfPermitted() {
        Runnable action = mPendingOpenclawStorageAction;
        Runnable deniedAction = mPendingOpenclawStorageDeniedAction;
        if (action == null) {
            return;
        }
        mPendingOpenclawStorageAction = null;
        mPendingOpenclawStorageDeniedAction = null;
        if (!isOpenclawStoragePermissionGranted()) {
            if (deniedAction != null) {
                deniedAction.run();
            } else {
                Toast.makeText(
                    this,
                    "Storage permission is required to backup or restore OpenClaw data",
                    Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }
        action.run();
    }

    private boolean isOpenclawStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Environment.isExternalStorageManager();
        }
        return PermissionUtils.checkStoragePermission(this, PermissionUtils.isLegacyExternalStoragePossible(this));
    }

    private void confirmOpenclawRestore(File backupFile) {
        String createdAtText = formatBackupTimestamp(readBackupCreatedAt(backupFile));
        String message = "Restore OpenClaw data from:\n"
            + backupFile.getName()
            + "\nCreated at: " + createdAtText
            + "\n\nCurrent data files will be replaced.";

        new AlertDialog.Builder(this)
            .setTitle("Restore OpenClaw Data")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore", (dialog, which) -> performOpenclawRestore(backupFile))
            .show();
    }

    private void performOpenclawRestore(File backupFile) {
        setButtonEnabled(mOpenclawRestoreButton, false);
        new Thread(() -> {
            boolean restored = applyOpenclawBackup(backupFile);
            runOnUiThread(() -> {
                setButtonEnabled(mOpenclawRestoreButton, true);
                if (!restored) {
                    Toast.makeText(this, "Failed to restore OpenClaw data", Toast.LENGTH_SHORT).show();
                    return;
                }
                loadCurrentModel();
                loadChannelInfo();
                Toast.makeText(
                    this,
                    "OpenClaw folder restored successfully.",
                    Toast.LENGTH_LONG
                ).show();
            });
        }).start();
    }

    private boolean applyOpenclawBackup(File backupFile) {
        if (isLegacyOpenclawBackupFile(backupFile)) {
            return applyLegacyOpenclawBackup(backupFile);
        }
        File openclawDir = getOpenclawHomeDirectory();
        File botdropDir = getBotdropHomeDirectory();
        if (openclawDir == null || botdropDir == null) {
            return false;
        }

        File homeDir = openclawDir.getParentFile();
        if (homeDir != null && !homeDir.exists() && !homeDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "Failed to recreate openclaw home parent: " + homeDir.getAbsolutePath());
            return false;
        }
        if (homeDir == null) {
            Logger.logWarn(LOG_TAG, "OpenClaw home parent directory is null");
            return false;
        }

        File stagingDir = createOpenclawRestoreStagingDirectory(homeDir);
        if (stagingDir == null) {
            return false;
        }

        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "Failed to create restore staging directory: " + stagingDir.getAbsolutePath());
            return false;
        }

        File[] restoreTargets = {openclawDir, botdropDir};
        File[] rollbackDirs = new File[restoreTargets.length];
        boolean[] restoredTargetExists = new boolean[restoreTargets.length];
        boolean restoreSucceeded = false;
        try {
            if (!extractOpenclawBackupToDirectory(backupFile, stagingDir)) {
                return false;
            }

            boolean hasAnyRestoredDirectory = false;
            for (int i = 0; i < restoreTargets.length; i++) {
                File targetDir = restoreTargets[i];
                File restoredSourceDir = new File(stagingDir, targetDir.getName());
                if (!restoredSourceDir.exists()) {
                    continue;
                }

                hasAnyRestoredDirectory = true;
                restoredTargetExists[i] = true;

                if (targetDir.exists()) {
                    rollbackDirs[i] = createOpenclawRollbackDirectory(homeDir, targetDir.getName());
                    if (rollbackDirs[i] == null) {
                        Logger.logWarn(LOG_TAG, "Failed to create backup directory for restore of " + targetDir.getName());
                        return false;
                    }

                    if (!targetDir.renameTo(rollbackDirs[i])) {
                        Logger.logWarn(LOG_TAG, "Failed to backup current " + targetDir.getName() + " directory before restore");
                        return false;
                    }
                }

                if (!restoredSourceDir.renameTo(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to move restored " + targetDir.getName() + " directory into place");
                    return false;
                }
            }

            if (!hasAnyRestoredDirectory) {
                Logger.logWarn(LOG_TAG, "No restorable data directory found in backup");
                return false;
            }

            for (int i = 0; i < rollbackDirs.length; i++) {
                if (rollbackDirs[i] != null && rollbackDirs[i].exists()) {
                    if (!deleteRecursively(rollbackDirs[i])) {
                        Logger.logWarn(LOG_TAG, "Failed to delete previous backup backup directory for " + restoreTargets[i].getName());
                    }
                }
            }

            restoreSucceeded = true;
            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to restore OpenClaw backup from " + backupFile.getAbsolutePath() + ": " + e.getMessage());
            for (int i = 0; i < rollbackDirs.length; i++) {
                File targetDir = restoreTargets[i];
                File rollbackDir = rollbackDirs[i];
                if (rollbackDir == null) {
                    if (restoredTargetExists[i] && targetDir.exists()) {
                        deleteRecursively(targetDir);
                    }
                    continue;
                }

                if (targetDir.exists() && !deleteRecursively(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to clean partially restored " + targetDir.getName() + " directory");
                }

                if (!rollbackDir.renameTo(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to rollback " + targetDir.getName() + " directory after restore failure");
                }
            }
            return false;
        } finally {
            if (!deleteRecursively(stagingDir)) {
                Logger.logWarn(LOG_TAG, "Failed to delete restore staging directory: " + stagingDir.getAbsolutePath());
            }
        }
    }

    private boolean applyLegacyOpenclawBackup(@NonNull File backupFile) {
        JSONObject backupPayload = readJsonFromFile(backupFile);
        if (backupPayload == null) {
            return false;
        }

        File openclawDir = getOpenclawHomeDirectory();
        if (openclawDir == null) {
            return false;
        }

        File homeDir = openclawDir.getParentFile();
        if (homeDir != null && !homeDir.exists() && !homeDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "Failed to recreate openclaw home parent: " + homeDir.getAbsolutePath());
            return false;
        }
        if (homeDir == null) {
            Logger.logWarn(LOG_TAG, "OpenClaw home parent directory is null");
            return false;
        }

        JSONObject openclawConfig = backupPayload.optJSONObject(OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY);
        JSONObject authProfiles = backupPayload.optJSONObject(OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY);
        if (openclawConfig == null && authProfiles == null) {
            Logger.logWarn(LOG_TAG, "Legacy backup has no recoverable OpenClaw payload");
            return false;
        }

        File openclawConfigFile = new File(OPENCLAW_CONFIG_FILE);
        File authProfilesFile = new File(OPENCLAW_AUTH_PROFILES_FILE);
        File rollbackDir = null;
        File targetDir = openclawDir;

        try {
            if (targetDir.exists()) {
                rollbackDir = createOpenclawRollbackDirectory(homeDir, OPENCLAW_HOME_FOLDER);
                if (rollbackDir == null) {
                    Logger.logWarn(LOG_TAG, "Failed to create backup directory for legacy restore");
                    return false;
                }
                if (!targetDir.renameTo(rollbackDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to backup current .openclaw directory before legacy restore");
                    return false;
                }
            }

            if (openclawConfig != null && !writeJsonToFile(openclawConfigFile, openclawConfig)) {
                Logger.logWarn(LOG_TAG, "Failed to restore legacy openclaw.json");
                return false;
            }

            if (authProfiles != null && !writeJsonToFile(authProfilesFile, authProfiles)) {
                Logger.logWarn(LOG_TAG, "Failed to restore legacy auth-profiles.json");
                return false;
            }

            if (rollbackDir != null && rollbackDir.exists() && !deleteRecursively(rollbackDir)) {
                Logger.logWarn(LOG_TAG, "Failed to delete legacy restore backup directory: " + rollbackDir.getAbsolutePath());
            }

            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to restore legacy OpenClaw backup from " + backupFile.getAbsolutePath() + ": " + e.getMessage());
            if (rollbackDir != null && rollbackDir.exists()) {
                if (targetDir.exists() && !deleteRecursively(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to clean partially restored .openclaw directory after legacy restore failure");
                }
                if (!rollbackDir.renameTo(targetDir)) {
                    Logger.logWarn(LOG_TAG, "Failed to rollback .openclaw directory after legacy restore failure");
                }
            }
            return false;
        }
    }

    private boolean isLegacyOpenclawBackupFile(@NonNull File backupFile) {
        return backupFile.getName().endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON);
    }

    private File createOpenclawRestoreStagingDirectory(@NonNull File homeDir) {
        for (int suffix = 0; suffix < 10; suffix++) {
            File stagingDir = new File(homeDir, OPENCLAW_RESTORE_STAGING_DIR_PREFIX + System.currentTimeMillis() + "_" + suffix);
            if (!stagingDir.exists()) {
                return stagingDir;
            }
        }
        return null;
    }

    private File createOpenclawRollbackDirectory(@NonNull File homeDir, @NonNull String targetName) {
        String prefix = OPENCLAW_HOME_FOLDER.equals(targetName)
            ? OPENCLAW_RESTORE_BACKUP_DIR_PREFIX
            : BOTDROP_RESTORE_BACKUP_DIR_PREFIX;
        for (int suffix = 0; suffix < 10; suffix++) {
            File rollbackDir = new File(homeDir, prefix + System.currentTimeMillis() + "_" + suffix);
            if (!rollbackDir.exists()) {
                return rollbackDir;
            }
        }
        return null;
    }

    private String createOpenclawBackupFile() {
        File homeDir = getOpenclawHomeParentDirectory();
        if (homeDir == null) {
            return null;
        }
        File openclawDir = getOpenclawHomeDirectory();
        File botdropDir = getBotdropHomeDirectory();
        if (!openclawDir.exists() && !botdropDir.exists()) {
            return null;
        }

        File backupDir = getOpenclawBackupDirectory();
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            return null;
        }

        File backupFile = new File(
            backupDir,
            OPENCLAW_BACKUP_FILE_PREFIX + formatBackupTimestamp(System.currentTimeMillis()) + OPENCLAW_BACKUP_FILE_EXTENSION
        );

        try {
            boolean archived = createOpenclawBackupZip(homeDir, backupFile, openclawDir, botdropDir);
            if (!archived) {
                return null;
            }

            return backupFile.getAbsolutePath();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create OpenClaw backup: " + e.getMessage());
            return null;
        }
    }

    private File getLatestOpenclawBackupFile() {
        File backupDir = getOpenclawBackupDirectory();
        if (!backupDir.exists() || !backupDir.isDirectory()) {
            return null;
        }

        File[] candidates = backupDir.listFiles((dir, name) ->
            name != null
                && name.startsWith(OPENCLAW_BACKUP_FILE_PREFIX)
                && (name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION) || name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON))
        );
        if (candidates == null || candidates.length == 0) {
            return null;
        }

        Arrays.sort(candidates, Comparator.comparingLong(File::lastModified));
        return candidates[candidates.length - 1];
    }

    private File getOpenclawBackupDirectory() {
        File documentsDir = Environment.getExternalStorageDirectory();
        return new File(documentsDir, OPENCLAW_BACKUP_DIRECTORY);
    }

    private long readBackupCreatedAt(File backupFile) {
        if (backupFile == null || !backupFile.exists()) {
            return 0L;
        }

        String name = backupFile.getName();
        if (name.startsWith(OPENCLAW_BACKUP_FILE_PREFIX)
            && (name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION) || name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON))) {
            String extension = name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION_JSON)
                ? OPENCLAW_BACKUP_FILE_EXTENSION_JSON
                : OPENCLAW_BACKUP_FILE_EXTENSION;
            String timestampPart = name.substring(
                OPENCLAW_BACKUP_FILE_PREFIX.length(),
                name.length() - extension.length()
            );
            try {
                Date parsed = new SimpleDateFormat(OPENCLAW_BACKUP_DATE_PATTERN, Locale.US).parse(timestampPart);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (Exception ignored) {
            }
        }

        return backupFile.lastModified();
    }

    private String formatBackupTimestamp(long timeMs) {
        if (timeMs <= 0L) {
            timeMs = System.currentTimeMillis();
        }
        return new SimpleDateFormat(OPENCLAW_BACKUP_DATE_PATTERN, Locale.US).format(new Date(timeMs));
    }

    private boolean createOpenclawBackupZip(
        @NonNull File sourceDir,
        @NonNull File outputFile,
        @NonNull File... sourceDataDirectories
    ) {
        byte[] buffer = new byte[OPENCLAW_BACKUP_IO_BUFFER_SIZE];
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))) {
            boolean hasEntries = false;
            for (File sourceDataDirectory : sourceDataDirectories) {
                if (sourceDataDirectory == null || !sourceDataDirectory.exists() || !sourceDataDirectory.isDirectory()) {
                    continue;
                }
                if (!addOpenclawDirectoryEntriesToZip(sourceDir, sourceDataDirectory, zos, buffer)) {
                    return false;
                }
                hasEntries = true;
            }
            return hasEntries;
        } catch (java.io.IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create backup zip", e);
            return false;
        }
    }

    private boolean addOpenclawDirectoryEntriesToZip(
        @NonNull File sourceDir,
        @NonNull File current,
        @NonNull ZipOutputStream zos,
        @NonNull byte[] buffer
    ) throws java.io.IOException {
        if (current.equals(sourceDir)) {
            return true;
        }

        String sourcePath = sourceDir.getAbsolutePath();
        String childPath = current.getAbsolutePath();
        String relativePath = childPath.equals(sourcePath)
            ? ""
            : childPath.substring(sourcePath.length() + 1).replace('\\', '/');

        if (current.isDirectory()) {
            if (!relativePath.isEmpty()) {
                ZipEntry dirEntry = new ZipEntry(relativePath + (relativePath.endsWith("/") ? "" : "/"));
                zos.putNextEntry(dirEntry);
                zos.closeEntry();
            }
            File[] children = current.listFiles();
            if (children == null) {
                return true;
            }
            for (File child : children) {
                if (!addOpenclawDirectoryEntriesToZip(sourceDir, child, zos, buffer)) {
                    return false;
                }
            }
            return true;
        }

        String entryName = relativePath;
        ZipEntry fileEntry = new ZipEntry(entryName);
        zos.putNextEntry(fileEntry);
        try (FileInputStream input = new FileInputStream(current)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                zos.write(buffer, 0, read);
            }
        }
        zos.closeEntry();
        return true;
    }

    private boolean extractOpenclawBackupToDirectory(@NonNull File backupFile, @NonNull File homeDir) {
        byte[] buffer = new byte[OPENCLAW_BACKUP_IO_BUFFER_SIZE];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(backupFile)))) {
            ZipEntry entry;
            boolean restoredAny = false;
            String homePath = homeDir.getCanonicalPath();
            String expectedPrefix = homePath + File.separator;

            while ((entry = zis.getNextEntry()) != null) {
                String relativePath = normalizeOpenclawBackupEntryPath(entry.getName());
                if (relativePath == null) {
                    zis.closeEntry();
                    continue;
                }
                restoredAny = true;

                File targetFile = new File(homeDir, relativePath);
                String targetPath = targetFile.getCanonicalPath();
                if (!targetPath.equals(homePath) && !targetPath.startsWith(expectedPrefix)) {
                    Logger.logWarn(LOG_TAG, "Skipping unsafe backup entry: " + entry.getName());
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory() || relativePath.endsWith("/")) {
                    if (!targetFile.exists() && !targetFile.mkdirs()) {
                        Logger.logWarn(LOG_TAG, "Failed to create directory from backup: " + targetFile.getAbsolutePath());
                        return false;
                    }
                    zis.closeEntry();
                    continue;
                }

                File parentDir = targetFile.getParentFile();
                if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                    Logger.logWarn(LOG_TAG, "Failed to create parent directory: " + parentDir.getAbsolutePath());
                    return false;
                }

                try (FileOutputStream output = new FileOutputStream(targetFile)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                zis.closeEntry();
            }

            return restoredAny;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to restore OpenClaw backup from " + backupFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private JSONObject readJsonFromFile(@NonNull File file) {
        if (!file.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            return new JSONObject(sb.toString());
        } catch (IOException | org.json.JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to read JSON backup from " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    private boolean writeJsonToFile(@NonNull File file, @NonNull JSONObject payload) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Logger.logWarn(LOG_TAG, "Failed to create parent directory: " + parent.getAbsolutePath());
                return false;
            }

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(payload.toString(2));
            }

            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            return true;
        } catch (IOException | org.json.JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to write restored OpenClaw file to " + file.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    @Nullable
    private File getOpenclawHomeDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, OPENCLAW_HOME_FOLDER);
    }

    private File getBotdropHomeDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, BOTDROP_HOME_FOLDER);
    }

    private File getOpenclawHomeParentDirectory() {
        File openclawDir = getOpenclawHomeDirectory();
        if (openclawDir == null || openclawDir.getParentFile() == null) {
            return null;
        }
        return openclawDir.getParentFile();
    }

    private boolean deleteRecursively(@NonNull File file) {
        if (!file.exists()) {
            return true;
        }

        Deque<File> stack = new ArrayDeque<>();
        Deque<File> orderedDelete = new ArrayDeque<>();
        stack.push(file);

        while (!stack.isEmpty()) {
            File current = stack.pop();
            if (!current.exists()) {
                continue;
            }
            orderedDelete.push(current);
            if (current.isDirectory()) {
                File[] children = current.listFiles();
                if (children == null) {
                    Logger.logWarn(LOG_TAG, "Failed to list children for " + current.getAbsolutePath());
                    return false;
                }
                for (File child : children) {
                    stack.push(child);
                }
            }
        }

        while (!orderedDelete.isEmpty()) {
            File target = orderedDelete.pop();
            if (!target.delete()) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    private String normalizeOpenclawBackupEntryPath(@Nullable String entryName) {
        if (entryName == null) {
            return null;
        }

        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.equals(".") || normalized.equals("..") || normalized.startsWith("../") || normalized.endsWith("/..") || normalized.contains("/../")) {
            return null;
        }

        String[] allowedRoots = new String[]{OPENCLAW_HOME_FOLDER, BOTDROP_HOME_FOLDER};
        int slashIndex = normalized.indexOf('/');
        String rootName = slashIndex >= 0 ? normalized.substring(0, slashIndex) : normalized;
        boolean isAllowedRoot = false;
        for (String root : allowedRoots) {
            if (root.equals(rootName)) {
                isAllowedRoot = true;
                break;
            }
        }

        if (!isAllowedRoot) {
            return null;
        }

        return normalized;
    }

    private void setButtonEnabled(TextView button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.5f);
    }

    /**
     * Create notification channel for gateway monitor service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BotDrop Gateway",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when BotDrop is running");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Start the gateway monitor service
     */
    private void startGatewayMonitorService() {
        Intent serviceIntent = new Intent(this, GatewayMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /**
     * Start periodic status refresh
     */
    private void startStatusRefresh() {
        if (!mUiVisible) {
            return;
        }
        mStatusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (mUiVisible) {
                    refreshStatus();
                    mHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
                }
            }
        };
        mHandler.post(mStatusRefreshRunnable);
    }

    /**
     * Refresh gateway status and uptime
     */
    private void refreshStatus() {
        if (!mUiVisible) {
            return;
        }
        if (!mBound || mBotDropService == null) {
            return;
        }

        // Check if gateway is running
        mBotDropService.isGatewayRunning(result -> {
            if (!mUiVisible) {
                return;
            }
            boolean isRunning = result.success && result.stdout.trim().equals("running");
            updateStatusUI(isRunning);
            checkGatewayErrors(isRunning);

            // Get uptime if running
            if (isRunning) {
                mBotDropService.getGatewayUptime(uptimeResult -> {
                    if (!mUiVisible) {
                        return;
                    }
                    if (uptimeResult.success) {
                        String uptime = uptimeResult.stdout.trim();
                        if (!uptime.equals("—")) {
                            mUptimeText.setText("Uptime: " + uptime);
                        } else {
                            mUptimeText.setText("—");
                        }
                    }
                });
            }
        });
    }

    /**
     * Update the status UI based on gateway state
     */
    private void updateStatusUI(boolean isRunning) {
        if (isRunning) {
            mStatusText.setText("Gateway Running");
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_running);
            setButtonState(mStartButton, false, true);
            setButtonState(mStopButton, true, false);
            setButtonState(mRestartButton, true, true);
        } else {
            mStatusText.setText("Gateway Stopped");
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_stopped);
            mUptimeText.setText("—");
            setButtonState(mStartButton, true, true);
            setButtonState(mStopButton, false, false);
            setButtonState(mRestartButton, false, true);
        }
    }

    private void setButtonState(Button button, boolean enabled, boolean isFilled) {
        button.setEnabled(enabled);
        if (enabled) {
            button.setAlpha(1.0f);
            button.setTextColor(isFilled ? ContextCompat.getColor(this, R.color.botdrop_background) : ContextCompat.getColor(this, R.color.botdrop_accent));
        } else {
            button.setAlpha(0.5f);
            button.setTextColor(ContextCompat.getColor(this, R.color.botdrop_secondary_text));
        }
    }

    private void openTelegramChannelConfig() {
        Intent intent = new Intent(this, SetupActivity.class);
        intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_CHANNEL);
        startActivity(intent);
    }

    private void showUpdateBanner(String latestVersion, String downloadUrl) {
        mUpdateBannerText.setText("Update available: v" + latestVersion);
        mUpdateBanner.setVisibility(View.VISIBLE);

        findViewById(R.id.btn_update_download).setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            startActivity(browserIntent);
        });

        findViewById(R.id.btn_update_dismiss).setOnClickListener(v -> {
            mUpdateBanner.setVisibility(View.GONE);
            UpdateChecker.dismiss(this, latestVersion);
        });
    }

    private void hideUpdateBanner() {
        if (mUpdateBanner != null) {
            mUpdateBanner.setVisibility(View.GONE);
        }
    }

    /**
     * Load channel configuration and update UI
     */
    private void loadChannelInfo() {
        mTelegramStatus.setText("○ —");
        mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
        mDiscordStatus.setText("○ —");
        mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));

        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            if (channels == null) {
                return;
            }

            JSONObject telegram = channels.optJSONObject("telegram");
            boolean telegramConnected = telegram != null
                    && telegram.optBoolean("enabled", true)
                    && !TextUtils.isEmpty(telegram.optString("botToken", "").trim());
            if (telegramConnected) {
                mTelegramStatus.setText("● Connected");
                mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            }

            JSONObject discord = channels.optJSONObject("discord");
            boolean discordConnected = discord != null
                    && discord.optBoolean("enabled", true)
                    && !TextUtils.isEmpty(discord.optString("token", "").trim());
            if (discordConnected) {
                mDiscordStatus.setText("● Connected");
                mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load channel info: " + e.getMessage());
        }
    }

    /**
     * Start the gateway
     */
    private void startGateway() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, "Starting gateway...", Toast.LENGTH_SHORT).show();
        mStartButton.setEnabled(false);

        mBotDropService.startGateway(result -> {
            if (result.success) {
                Toast.makeText(this, "Gateway started", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, "Failed to start gateway", Toast.LENGTH_SHORT).show();
                mStartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Start failed: " + result.stderr);
            }
        });
    }

    /**
     * Stop the gateway
     */
    private void stopGateway() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, "Stopping gateway...", Toast.LENGTH_SHORT).show();
        mStopButton.setEnabled(false);

        mBotDropService.stopGateway(result -> {
            if (result.success) {
                Toast.makeText(this, "Gateway stopped", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, "Failed to stop gateway", Toast.LENGTH_SHORT).show();
                mStopButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Stop failed: " + result.stderr);
            }
        });
    }

    /**
     * Restart the gateway (for control button)
     */
    private void restartGatewayForControl() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, "Restarting gateway...", Toast.LENGTH_SHORT).show();
        mRestartButton.setEnabled(false);

        mBotDropService.restartGateway(result -> {
            if (result.success) {
                Toast.makeText(this, "Gateway restarted", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, "Failed to restart gateway", Toast.LENGTH_SHORT).show();
                mRestartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Restart failed: " + result.stderr);
            }
        });
    }

    /**
     * Restart the gateway (for model change)
     */
    private void restartGateway() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        Toast.makeText(this, "Restarting gateway with new model...", Toast.LENGTH_SHORT).show();

        mBotDropService.restartGateway(result -> {
            if (result.success) {
                Toast.makeText(this, "Gateway restarted successfully", Toast.LENGTH_SHORT).show();
                loadCurrentModel();
            } else {
                Toast.makeText(this, "Failed to restart gateway", Toast.LENGTH_SHORT).show();
                Logger.logError(LOG_TAG, "Restart failed: " + result.stderr);
                loadCurrentModel();
            }
        });
    }

    /**
     * Load SSH connection info and display in the dashboard
     */
    private void loadSshInfo() {
        String ip = getDeviceIp();
        if (ip == null) ip = "<device-ip>";

        // Read SSH password from file
        String password = readSshPassword();
        if (password == null) password = "<not set>";

        mSshInfoText.setText("ssh -p 8022 " + ip + "\nPassword: " + password);
        mSshCard.setVisibility(View.VISIBLE);
    }

    private String readSshPassword() {
        try {
            java.io.File pwFile = new java.io.File(
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh_password");
            if (pwFile.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(pwFile));
                String password = reader.readLine();
                reader.close();
                if (password != null) return password.trim();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read SSH password: " + e.getMessage());
        }
        return null;
    }

    private String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to get device IP: " + e.getMessage());
        }
        return null;
    }

    /**
     * Open terminal activity
     */
    private void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
    }

    private void openOpenclawWebUi() {
        if (!mUiVisible) {
            return;
        }

        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawWebUiOpening) {
            Toast.makeText(this, "Web UI is already opening", Toast.LENGTH_SHORT).show();
            return;
        }
        mOpenclawWebUiOpening = true;
        setOpenclawWebUiButtonState(true, OPENCLAW_WEB_UI_BUTTON_TEXT_PENDING);

        mBotDropService.isGatewayRunning(result -> {
            if (!mUiVisible) {
                mOpenclawWebUiOpening = false;
                setOpenclawWebUiButtonState(false, null);
                return;
            }

            if (result == null || !result.success || !"running".equals(result.stdout.trim())) {
                mOpenclawWebUiOpening = false;
                setOpenclawWebUiButtonState(false, null);
                Toast.makeText(this, "OpenClaw gateway is not running", Toast.LENGTH_SHORT).show();
                return;
            }

            resolveOpenclawWebUiUrl(url -> {
                if (!mUiVisible) {
                    mOpenclawWebUiOpening = false;
                    setOpenclawWebUiButtonState(false, null);
                    return;
                }
                openOpenclawUrlWithReadinessCheck(url, 0);
            });
        });
    }

    private void openOpenclawUrlWithReadinessCheck(String webUiUrl, int attempt) {
        if (!mUiVisible) {
            mOpenclawWebUiOpening = false;
            setOpenclawWebUiButtonState(false, null);
            return;
        }

        final String url = TextUtils.isEmpty(webUiUrl) ? OPENCLAW_DEFAULT_WEB_UI_URL : webUiUrl.trim();
        if (TextUtils.isEmpty(url)) {
            mOpenclawWebUiOpening = false;
            setOpenclawWebUiButtonState(false, null);
            openOpenclawUrlInBrowser(OPENCLAW_DEFAULT_WEB_UI_URL);
            return;
        }

        new Thread(() -> {
            final boolean reachable = isOpenclawWebUiReachable(url);
            if (reachable || attempt >= OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT) {
                runOnUiThread(() -> {
                    mOpenclawWebUiOpening = false;
                    if (!isFinishing()) {
                        if (!mUiVisible) {
                            return;
                        }
                        if (!reachable && attempt >= OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT) {
                            Toast.makeText(
                                this,
                                "Web UI is still starting. Opened directly; please refresh in browser if needed.",
                                Toast.LENGTH_LONG
                            ).show();
                        }
                        openOpenclawUrlInBrowser(url);
                    }
                });
                return;
            }

            final int nextAttempt = attempt + 1;
            runOnUiThread(() -> setOpenclawWebUiButtonState(
                true,
                OPENCLAW_WEB_UI_BUTTON_TEXT_PENDING + " (" + nextAttempt + "/" + OPENCLAW_WEB_UI_REACHABILITY_RETRY_COUNT + ")"
            ));
            mHandler.postDelayed(
                () -> openOpenclawUrlWithReadinessCheck(url, nextAttempt),
                OPENCLAW_WEB_UI_REACHABILITY_RETRY_DELAY_MS
            );
        }).start();
    }

    private boolean isOpenclawWebUiReachable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            connection.disconnect();
            return code >= 200 && code < 600;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void resolveOpenclawWebUiUrl(OpenclawWebUiUrlCallback callback) {
        if (callback == null) return;
        if (mBotDropService == null) {
            callback.onUrlResolved(OPENCLAW_DEFAULT_WEB_UI_URL);
            return;
        }

        String configText = BotDropConfig.readConfig().toString();
        String gatewayToken = extractGatewayTokenFromConfig(configText);

        String host = "127.0.0.1";
        int port = OPENCLAW_DEFAULT_WEB_UI_PORT;
        String basePath = OPENCLAW_DEFAULT_WEB_UI_PATH;

        try {
            JSONObject config = new JSONObject(configText);
            String normalizedHost = extractOpenclawHostFromJson(config);
            int configPort = extractOpenclawPortFromJson(config);
            String configBasePath = extractOpenclawControlUiBasePathFromJson(config);
            if (!TextUtils.isEmpty(normalizedHost) && isLocalWebUiHost(normalizedHost)) {
                host = normalizeOpenclawHost(normalizedHost);
                if (!TextUtils.isEmpty(host) && host.indexOf(':') >= 0 && !host.startsWith("[")) {
                    host = "[" + host + "]";
                }
            }
            if (configPort > 0) {
                port = configPort;
            }
            if (!TextUtils.isEmpty(configBasePath)) {
                basePath = normalizeOpenclawControlUiPath(configBasePath);
            }
        } catch (Exception ignored) {
        }

        if (TextUtils.isEmpty(host)) {
            host = "127.0.0.1";
        }
        if (port <= 0) {
            port = OPENCLAW_DEFAULT_WEB_UI_PORT;
        }
        if (TextUtils.isEmpty(basePath)) {
            basePath = OPENCLAW_DEFAULT_WEB_UI_PATH;
        }
        String baseUrl = "http://" + host + ":" + port + basePath;
        callback.onUrlResolved(appendGatewayTokenToWebUiUrl(baseUrl, gatewayToken));
    }

    private String extractOpenclawControlUiBasePathFromJson(JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONObject gateway = root.optJSONObject("gateway");
        if (gateway != null) {
            JSONObject controlUi = gateway.optJSONObject("controlUi");
            if (controlUi != null) {
                String basePath = controlUi.optString("basePath", null);
                String normalized = normalizeOpenclawControlUiPath(basePath);
                if (!TextUtils.isEmpty(normalized)) {
                    return normalized;
                }
            }
        }

        JSONObject controlUi = root.optJSONObject("controlUi");
        if (controlUi != null) {
            String basePath = controlUi.optString("basePath", null);
            String normalized = normalizeOpenclawControlUiPath(basePath);
            if (!TextUtils.isEmpty(normalized)) {
                return normalized;
            }
        }

        String legacyBasePath = root.optString("controlUiBasePath", null);
        if (!TextUtils.isEmpty(legacyBasePath)) {
            String normalized = normalizeOpenclawControlUiPath(legacyBasePath);
            if (!TextUtils.isEmpty(normalized)) {
                return normalized;
            }
        }

        return null;
    }

    private String normalizeOpenclawControlUiPath(String rawPath) {
        if (TextUtils.isEmpty(rawPath)) {
            return OPENCLAW_DEFAULT_WEB_UI_PATH;
        }
        String normalized = rawPath.trim();
        if (TextUtils.isEmpty(normalized)) {
            return OPENCLAW_DEFAULT_WEB_UI_PATH;
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String extractGatewayTokenFromText(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String candidate = extractOpenclawUrlFromText(text);
        if (TextUtils.isEmpty(candidate)) {
            // Keep scanning raw output in case the token is in a non-URL line.
        } else {
            try {
                String token = Uri.parse(candidate.trim()).getQueryParameter(OPENCLAW_WEB_UI_TOKEN_KEY);
                if (!TextUtils.isEmpty(token)) {
                    return token;
                }
            } catch (Exception ignored) {
            }
        }

        Matcher tokenMatcher = GATEWAY_TOKEN_QUERY_PATTERN.matcher(text);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group(1);
            if (!TextUtils.isEmpty(token)) {
                return token.trim();
            }
        }

        return null;
    }

    private String normalizeOpenclawDashboardUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return null;
        }
        String trimmed = trimUrlPunctuation(rawUrl.trim());
        if (TextUtils.isEmpty(trimmed)) {
            return null;
        }

        if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }

        try {
            Uri parsed = Uri.parse(trimmed);
            String scheme = parsed.getScheme();
            if (TextUtils.isEmpty(scheme)) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            String normalizedHost = normalizeOpenclawHost(parsed.getHost());
            if (TextUtils.isEmpty(normalizedHost)) {
                return null;
            }
            if (!isLocalWebUiHost(normalizedHost)) {
                return null;
            }

            Uri.Builder normalizedBuilder = new Uri.Builder();
            normalizedBuilder.scheme(parsed.getScheme());
            String authority = normalizedHost;
            if (parsed.getPort() > 0) {
                authority = authority + ":" + parsed.getPort();
            }
            if (TextUtils.isEmpty(authority)) {
                return null;
            }
            normalizedBuilder.authority(authority);
            return normalizedBuilder.build().toString();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to normalize dashboard URL: " + e.getMessage());
            return null;
        }
    }

    private String normalizeOpenclawString(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return value.trim();
    }

    private String extractGatewayTokenFromConfig(String configText) {
        if (TextUtils.isEmpty(configText)) {
            return null;
        }

        try {
            JSONObject config = new JSONObject(configText);
            return extractGatewayTokenFromJson(config);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse OpenClaw config for gateway token: " + e.getMessage());
            return null;
        }
    }

    private String extractGatewayTokenFromJson(JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONObject gateway = root.optJSONObject("gateway");
        if (gateway == null) {
            return null;
        }

        JSONObject auth = gateway.optJSONObject("auth");
        if (auth == null) {
            return null;
        }

        return normalizeOpenclawString(auth.optString("token", null));
    }

    private String chooseOpenclawWebUiUrl(String rawUrl) {
        String normalized = normalizeOpenclawWebUiUrl(rawUrl);
        return TextUtils.isEmpty(normalized) ? OPENCLAW_DEFAULT_WEB_UI_URL : normalized;
    }

    private String appendGatewayTokenToWebUiUrl(String webUiUrl, String token) {
        if (TextUtils.isEmpty(token)) {
            return webUiUrl;
        }

        if (TextUtils.isEmpty(webUiUrl)) {
            return appendGatewayTokenToWebUiUrl(OPENCLAW_DEFAULT_WEB_UI_URL, token);
        }

        String trimmedUrl = webUiUrl.trim();
        if (TextUtils.isEmpty(trimmedUrl)) {
            return OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        if (hasQueryToken(trimmedUrl)) {
            return trimmedUrl;
        }

        String separator = trimmedUrl.contains("?") ? "&" : "?";
        if (trimmedUrl.endsWith("?") || trimmedUrl.endsWith("&")) {
            separator = "";
        }
        return trimmedUrl + separator + OPENCLAW_WEB_UI_TOKEN_KEY + "=" + token;
    }

    private boolean hasQueryToken(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Uri parsed = Uri.parse(url);
            return !TextUtils.isEmpty(parsed.getQueryParameter(OPENCLAW_WEB_UI_TOKEN_KEY));
        } catch (Exception e) {
            String lowerUrl = url.toLowerCase();
            String marker = OPENCLAW_WEB_UI_TOKEN_KEY.toLowerCase() + "=";
            return lowerUrl.contains(marker);
        }
    }

    private String normalizeOpenclawWebUiUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return null;
        }
        String trimmed = trimUrlPunctuation(rawUrl.trim());
        if (TextUtils.isEmpty(trimmed)) return null;

        if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }

        try {
            Uri parsed = Uri.parse(trimmed);
            String scheme = parsed.getScheme();
            if (TextUtils.isEmpty(scheme)) return null;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;

            String host = parsed.getHost();
            if (TextUtils.isEmpty(host)) return null;
            String normalizedHost = normalizeOpenclawHost(host);
            if (!isLocalWebUiHost(normalizedHost)) {
                return null;
            }
            int port = parsed.getPort();
            if (port <= 0) {
                port = OPENCLAW_DEFAULT_WEB_UI_PORT;
            }

            StringBuilder url = new StringBuilder("http://").append(normalizedHost);
            if (port > 0) {
                url.append(':').append(port);
            }
            return url.toString();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to normalize OpenClaw URL: " + e.getMessage());
            return null;
        }
    }

    private String trimUrlPunctuation(String value) {
        if (TextUtils.isEmpty(value)) return value;
        return value.replaceAll("[\\)\\]\\}\\>,\\.;:\"]+$", "");
    }

    private String extractOpenclawUrlFromConfig(String configText) {
        if (TextUtils.isEmpty(configText)) return null;

        String fromText = extractOpenclawUrlFromText(configText);
        if (!TextUtils.isEmpty(fromText)) {
            String normalized = normalizeOpenclawWebUiUrl(fromText);
            if (!TextUtils.isEmpty(normalized)) return normalized;
        }

        try {
            JSONObject config = new JSONObject(configText);
            String host = extractOpenclawHostFromJson(config);
            int port = extractOpenclawPortFromJson(config);
            if (port <= 0) port = OPENCLAW_DEFAULT_WEB_UI_PORT;
            String normalizedHost = normalizeOpenclawHost(host);
            if (TextUtils.isEmpty(normalizedHost) || !isLocalWebUiHost(normalizedHost)) {
                normalizedHost = "127.0.0.1";
            }
            return "http://" + normalizedHost + ":" + port;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse OpenClaw config for web UI URL: " + e.getMessage());
        }

        return null;
    }

    private String extractOpenclawHostFromJson(JSONObject root) {
        if (root == null) return null;
        String host = firstNonEmpty(
            normalizeOpenclawHost(root.optString("host", null)),
            normalizeOpenclawHost(root.optString("hostname", null)),
            normalizeOpenclawHost(root.optString("listenHost", null)),
            normalizeOpenclawHost(root.optString("address", null)),
            normalizeOpenclawHost(root.optString("bind", null))
        );

        if (TextUtils.isEmpty(host)) {
            String urlValue = root.optString("url", null);
            if (!TextUtils.isEmpty(urlValue)) {
                String normalized = normalizeOpenclawWebUiUrl(urlValue);
                if (!TextUtils.isEmpty(normalized)) {
                    try {
                        host = Uri.parse(normalized).getHost();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (TextUtils.isEmpty(host)) {
            String listen = root.optString("listen", null);
            if (!TextUtils.isEmpty(listen)) {
                String parsed = parseHostFromText(listen);
                if (!TextUtils.isEmpty(parsed)) host = parsed;
            }
        }

        if (TextUtils.isEmpty(host)) {
            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway != null) {
                host = extractOpenclawHostFromJson(gateway);
            }
        }

        if (TextUtils.isEmpty(host)) {
            JSONObject server = root.optJSONObject("server");
            if (server != null) {
                host = extractOpenclawHostFromJson(server);
            }
        }

        if (TextUtils.isEmpty(host)) {
            JSONObject http = root.optJSONObject("http");
            if (http != null) {
                host = extractOpenclawHostFromJson(http);
            }
        }

        return normalizeOpenclawHost(host);
    }

    private int extractOpenclawPortFromJson(JSONObject root) {
        if (root == null) return -1;
        int port = firstPositiveInt(
            root.optInt("port", -1),
            root.optInt("listenPort", -1),
            root.optInt("httpPort", -1),
            root.optInt("gatewayPort", -1)
        );

        if (port <= 0) {
            port = parsePortFromText(root.optString("listen", null));
        }
        if (port <= 0) {
            port = parsePortFromText(root.optString("url", null));
        }
        if (port <= 0) {
            port = parsePortFromText(root.optString("endpoint", null));
        }

        if (port <= 0) {
            JSONObject gateway = root.optJSONObject("gateway");
            if (gateway != null) {
                port = extractOpenclawPortFromJson(gateway);
            }
        }

        if (port <= 0) {
            JSONObject server = root.optJSONObject("server");
            if (server != null) {
                port = extractOpenclawPortFromJson(server);
            }
        }

        if (port <= 0) {
            JSONObject http = root.optJSONObject("http");
            if (http != null) {
                port = extractOpenclawPortFromJson(http);
            }
        }

        return port;
    }

    private String parseHostFromText(String value) {
        String hostPort = extractHostPortFromText(value);
        if (TextUtils.isEmpty(hostPort)) return null;
        int separatorIndex = hostPort.lastIndexOf(':');
        if (separatorIndex <= 0) return null;
        return hostPort.substring(0, separatorIndex);
    }

    private int parsePortFromText(String value) {
        String hostPort = extractHostPortFromText(value);
        if (TextUtils.isEmpty(hostPort)) return -1;
        int separatorIndex = hostPort.lastIndexOf(':');
        if (separatorIndex <= 0 || separatorIndex + 1 >= hostPort.length()) return -1;
        try {
            return Integer.parseInt(hostPort.substring(separatorIndex + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String extractOpenclawUrlFromText(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = WEB_UI_URL_PATTERN.matcher(text);
        String firstMatch = null;
        String bestMatch = null;
        while (matcher.find()) {
            String match = trimUrlPunctuation(matcher.group());
            if (TextUtils.isEmpty(match)) {
                continue;
            }
            if (firstMatch == null) {
                firstMatch = match;
            }
            if (bestMatch == null && isLikelyDashboardLink(match)) {
                bestMatch = match;
            }
        }
        return TextUtils.isEmpty(bestMatch) ? firstMatch : bestMatch;
    }

    private String extractHostPortFromText(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = HOST_PORT_PATTERN.matcher(text);
        while (matcher.find()) {
            String host = normalizeOpenclawHost(matcher.group(1));
            String port = matcher.group(2);
            if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(port)) {
                return host + ":" + port;
            }
        }
        return null;
    }

    private String extractOpenclawUrlFromLog(String logText) {
        String fromText = extractOpenclawUrlFromText(logText);
        if (!TextUtils.isEmpty(fromText)) {
            String normalized = normalizeOpenclawWebUiUrl(fromText);
            if (!TextUtils.isEmpty(normalized)) return normalized;
        }
        String hostPort = extractHostPortFromText(logText);
        if (TextUtils.isEmpty(hostPort)) return null;
        return normalizeOpenclawWebUiUrl(hostPort);
    }

    private int firstPositiveInt(int... values) {
        if (values == null) return -1;
        for (int value : values) {
            if (value > 0) return value;
        }
        return -1;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value.trim();
        }
        return null;
    }

    private String normalizeOpenclawHost(String host) {
        if (TextUtils.isEmpty(host)) return null;
        String normalized = host.trim();
        if ("*".equals(normalized) || "0.0.0.0".equals(normalized)) {
            return "127.0.0.1";
        }
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("'") && normalized.endsWith("'") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isLocalWebUiHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        String normalized = host.toLowerCase();
        if (normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1") || normalized.equals("[::1]")) {
            return true;
        }
        if (normalized.equals("0.0.0.0") || normalized.equals("::") || normalized.equals("[::]")) {
            return true;
        }
        if (normalized.startsWith("localhost.")) {
            return true;
        }
        return normalized.startsWith("192.168.") || normalized.startsWith("10.") || normalized.startsWith("172.");
    }

    private boolean isLikelyDashboardLink(String candidateUrl) {
        if (TextUtils.isEmpty(candidateUrl)) {
            return false;
        }
        String lower = candidateUrl.toLowerCase();
        if (lower.contains("openclaw.ai")) {
            return false;
        }
        try {
            Uri parsed = Uri.parse(candidateUrl.trim());
            String host = parsed.getHost();
            if (TextUtils.isEmpty(host)) {
                return false;
            }
            String normalizedHost = normalizeOpenclawHost(host);
            if (TextUtils.isEmpty(normalizedHost)) {
                return false;
            }
            String path = parsed.getPath();
            if (path != null && !path.isEmpty()) {
                String lowerPath = path.toLowerCase();
                if (lowerPath.contains("/docs") || lowerPath.contains("/documentation")) {
                    return false;
                }
            }
            return isLocalWebUiHost(normalizedHost);
        } catch (Exception e) {
            return false;
        }
    }

    private void openOpenclawUrlInBrowser(String url) {
        if (TextUtils.isEmpty(url)) {
            url = OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        try {
            Uri parsed = Uri.parse(url.trim());
            if (TextUtils.isEmpty(parsed.getScheme()) ||
                !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))) {
                url = OPENCLAW_DEFAULT_WEB_UI_URL;
            }
        } catch (Exception ignored) {
            url = OPENCLAW_DEFAULT_WEB_UI_URL;
        }

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app available to open web links", Toast.LENGTH_SHORT).show();
            Logger.logWarn(LOG_TAG, "No activity found for URL: " + url);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open browser", Toast.LENGTH_SHORT).show();
            Logger.logWarn(LOG_TAG, "Failed to open URL: " + url + "; " + e.getMessage());
        }
    }

    /**
     * Load and display the current model from OpenClaw config
     */
    private void loadCurrentModel() {
        try {
            JSONObject config = BotDropConfig.readConfig();
            String currentModel = null;

            JSONObject agents = config.optJSONObject("agents");
            if (agents != null) {
                JSONObject defaults = agents.optJSONObject("defaults");
                if (defaults != null) {
                    Object modelObj = defaults.opt("model");
                    if (modelObj instanceof JSONObject) {
                        currentModel = ((JSONObject) modelObj).optString("primary", null);
                    } else if (modelObj instanceof String) {
                        currentModel = (String) modelObj;
                    }
                }
            }

            if (TextUtils.isEmpty(currentModel)) {
                ConfigTemplate template = ConfigTemplateCache.loadTemplate(this);
                if (template != null && !TextUtils.isEmpty(template.model)) {
                    currentModel = template.model;
                }
            }

            if (!TextUtils.isEmpty(currentModel) && !"null".equals(currentModel)) {
                mCurrentModelText.setText(currentModel);
                Logger.logInfo(LOG_TAG, "Current model: " + currentModel);
            } else {
                mCurrentModelText.setText("—");
            }
        } catch (Exception e) {
            mCurrentModelText.setText("—");
            Logger.logError(LOG_TAG, "Failed to load current model: " + e.getMessage());
        }
    }

    /**
     * Show the model selector dialog
     */
    private void showModelSelector() {
        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, "Service not available. Please try again.", Toast.LENGTH_SHORT).show();
            return;
        }

        ModelSelectorDialog dialog = new ModelSelectorDialog(this, mBotDropService, true);
        dialog.show((provider, model, apiKey, baseUrl, availableModels) -> {
            if (provider != null && model != null) {
                String fullModel = provider + "/" + model;
                updateModel(fullModel, apiKey, baseUrl, availableModels);
            }
        });
    }

    /**
     * Update model/API key and restart gateway.
     */
    private void updateModel(String fullModel, String optionalApiKey, String optionalBaseUrl, List<String> availableModels) {
        if (!mBound || mBotDropService == null) {
            return;
        }

        mCurrentModelText.setText("Updating...");
        String[] parts = fullModel.split("/", 2);
        if (parts.length != 2) {
            Toast.makeText(this, "Invalid model format", Toast.LENGTH_SHORT).show();
            loadCurrentModel();
            return;
        }

        String provider = parts[0];
        String model = parts[1];
        boolean isCustomProvider = !TextUtils.isEmpty(optionalBaseUrl);
        if (isCustomProvider && (availableModels == null || availableModels.isEmpty())) {
            Toast.makeText(DashboardActivity.this, "No custom model list found", Toast.LENGTH_SHORT).show();
            loadCurrentModel();
            return;
        }

        boolean configured = BotDropConfig.setActiveProvider(
            provider,
            model,
            optionalApiKey,
            isCustomProvider ? optionalBaseUrl : null,
            isCustomProvider ? availableModels : null
        );

        if (!configured) {
            Toast.makeText(DashboardActivity.this, "Failed to update model settings", Toast.LENGTH_SHORT).show();
            Logger.logError(LOG_TAG, "Failed to update model settings for " + fullModel);
            loadCurrentModel();
            return;
        }

        Logger.logInfo(LOG_TAG, "Model updated to: " + fullModel + ", apiKeyUpdated=" +
            (!TextUtils.isEmpty(optionalApiKey)));

        ConfigTemplate template = ConfigTemplateCache.loadTemplate(DashboardActivity.this);
        if (template == null) {
            template = new ConfigTemplate();
        }
        template.provider = provider;
        template.model = fullModel;
        if (!TextUtils.isEmpty(optionalApiKey)) {
            template.apiKey = optionalApiKey;
        }
        if (isCustomProvider && availableModels != null && !availableModels.isEmpty()) {
            template.customModels = new ArrayList<>(availableModels);
        } else if (!isCustomProvider) {
            template.customModels = null;
        }
        if (!TextUtils.isEmpty(optionalBaseUrl)) {
            template.baseUrl = optionalBaseUrl;
        } else {
            template.baseUrl = null;
        }
        ConfigTemplateCache.saveTemplate(DashboardActivity.this, template);

        restartGateway();
    }

    private void showOpenclawLog() {
        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawLogButton != null) {
            mOpenclawLogButton.setEnabled(false);
        }

        mBotDropService.executeCommand(VIEW_OPENCLAW_LOG_COMMAND, result -> {
            if (mOpenclawLogButton != null) {
                mOpenclawLogButton.setEnabled(true);
            }

            String logText = result != null ? result.stdout : null;
            if (result == null) {
                logText = "Failed to read OpenClaw logs.";
            } else if (!result.success) {
                StringBuilder fallback = new StringBuilder();
                if (!TextUtils.isEmpty(result.stderr)) {
                    fallback.append(result.stderr.trim());
                }
                if (!TextUtils.isEmpty(result.stdout)) {
                    if (fallback.length() > 0) {
                        fallback.append("\n\n");
                    }
                    fallback.append(result.stdout.trim());
                }
                logText = fallback.toString();
                if (TextUtils.isEmpty(logText)) {
                    logText = "Failed to read OpenClaw logs. Exit code: " + result.exitCode;
                }
            }

            if (TextUtils.isEmpty(logText)) {
                logText = "No log output available.";
            }

            final String finalLogText = logText;
            View logDialogView = getLayoutInflater().inflate(R.layout.dialog_openclaw_log, null);
            TextView logView = logDialogView.findViewById(R.id.openclaw_log_text);
            logView.setText(finalLogText);
            logView.setMovementMethod(ScrollingMovementMethod.getInstance());

            Button copyButton = logDialogView.findViewById(R.id.openclaw_log_copy_button);
            Button closeButton = logDialogView.findViewById(R.id.openclaw_log_close_button);

            AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(logDialogView)
                .create();

            copyButton.setOnClickListener(v -> copyToClipboard(finalLogText));
            closeButton.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        });
    }

    private void copyToClipboard(String content) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Clipboard unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String textToCopy = content == null ? "" : content;
        ClipData clip = ClipData.newPlainText("OpenClaw Gateway Log", textToCopy);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    // --- OpenClaw update ---

    private void checkOpenclawUpdate() {
        if (!mBound || mBotDropService == null) return;

        // One-time migration: clear stale throttle from previous code that recorded
        // check time even when npm returned invalid output, blocking retries for 24h.
        android.content.SharedPreferences updatePrefs =
            getSharedPreferences("openclaw_update", MODE_PRIVATE);
        if (!updatePrefs.getBoolean("throttle_fix_v1", false)) {
            updatePrefs.edit()
                .remove("last_check_time")
                .putBoolean("throttle_fix_v1", true)
                .apply();
        }

        // Display current version
        String currentVersion = BotDropService.getOpenclawVersion();
        if (currentVersion != null && mOpenclawVersionText != null) {
            mOpenclawVersionText.setText("OpenClaw v" + currentVersion);
        }

        // Also check stored result immediately (in case a previous check found an update)
        String[] stored = OpenClawUpdateChecker.getAvailableUpdate(this);
        if (stored != null) {
            showOpenclawUpdateDialog(stored[0], stored[1], false);
        }

        // Run throttled check
        OpenClawUpdateChecker.check(this, mBotDropService, new OpenClawUpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String current, String latest) {
                showOpenclawUpdateDialog(current, latest, false);
            }

            @Override
            public void onNoUpdate() {
                dismissOpenclawUpdateDialog();
            }
        });
    }

    private void forceCheckOpenclawUpdate() {
        if (!mBound || mBotDropService == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mOpenclawCheckUpdateButton == null) {
            Toast.makeText(this, "Check button unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        mOpenclawCheckUpdateButton.setEnabled(false);
        mOpenclawCheckUpdateButton.setText("Checking OpenClaw...");
        mOpenclawLatestUpdateVersion = null;
        mOpenclawManualCheckRequested = true;

        OpenClawUpdateChecker.check(this, mBotDropService, new OpenClawUpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String current, String latest) {
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText("Check OpenClaw updates");
                mOpenclawManualCheckRequested = false;
                showOpenclawUpdateDialog(current, latest, true);
            }

            @Override
            public void onNoUpdate() {
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText("Check OpenClaw updates");
                mOpenclawManualCheckRequested = false;
                dismissOpenclawUpdateDialog();
                Toast.makeText(DashboardActivity.this, "Already up to date", Toast.LENGTH_SHORT).show();
            }
        }, true);
    }

    private void showOpenclawUpdateDialog(String currentVersion, String latestVersion, boolean manualCheck) {
        if (TextUtils.isEmpty(latestVersion) || isFinishing() || isDestroyed()) {
            return;
        }

        if (!manualCheck && TextUtils.equals(latestVersion, mOpenclawLatestUpdateVersion)) {
            return;
        }
        if (mOpenclawUpdateDialog != null && mOpenclawUpdateDialog.isShowing()) {
            return;
        }

        mOpenclawLatestUpdateVersion = latestVersion;
        String currentPart = TextUtils.isEmpty(currentVersion) ? "Unknown" : currentVersion;
        String content =
            "OpenClaw update available: v" + currentPart + " → v" + latestVersion + "\n\n" +
            "A newer OpenClaw version is available.\nWould you like to update now?";

        dismissOpenclawUpdateDialog();
        final String updateVersion = latestVersion;
        mOpenclawUpdateDialog = new AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(content)
            .setCancelable(true)
            .setPositiveButton("Update", (d, w) -> startOpenclawUpdate(updateVersion))
            .setNeutralButton("Later", null)
            .setNegativeButton("Dismiss", (d, w) -> dismissOpenclawUpdate(updateVersion))
            .setOnDismissListener(dialog -> {
                if (mOpenclawUpdateDialog == dialog) {
                    mOpenclawUpdateDialog = null;
                    mOpenclawManualCheckRequested = false;
                }
            })
            .create();
        mOpenclawUpdateDialog.show();
        if (mOpenclawManualCheckRequested) {
            mOpenclawManualCheckRequested = false;
        }
    }

    private void openBotdropWebsite() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://botdrop.app/"));
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissOpenclawUpdate(String version) {
        if (!TextUtils.isEmpty(version)) {
            OpenClawUpdateChecker.dismiss(this, version);
            Toast.makeText(this, "Dismissed update: v" + version, Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissOpenclawUpdateDialog() {
        if (mOpenclawUpdateDialog != null && mOpenclawUpdateDialog.isShowing()) {
            mOpenclawUpdateDialog.dismiss();
        }
        mOpenclawUpdateDialog = null;
    }

    private void startOpenclawUpdate(String targetVersion) {
        if (TextUtils.isEmpty(targetVersion)) {
            Toast.makeText(this, "No update target version", Toast.LENGTH_SHORT).show();
            return;
        }

        dismissOpenclawUpdateDialog();
        if (!mBound || mBotDropService == null) return;

        // Build step-based progress dialog
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_openclaw_update, null);
        TextView[] stepIcons = {
            dialogView.findViewById(R.id.update_step_0_icon),
            dialogView.findViewById(R.id.update_step_1_icon),
            dialogView.findViewById(R.id.update_step_2_icon),
            dialogView.findViewById(R.id.update_step_3_icon),
            dialogView.findViewById(R.id.update_step_4_icon),
        };
        TextView statusMessage = dialogView.findViewById(R.id.update_status_message);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle("Updating OpenClaw")
            .setView(dialogView)
            .setCancelable(false)
            .create();
        progressDialog.show();

        // Disable control buttons during update
        mStartButton.setEnabled(false);
        mStopButton.setEnabled(false);
        mRestartButton.setEnabled(false);

        // Map step messages to step indices
        final String[] stepMessages = {
            "Stopping gateway...",
            "Installing update...",
            "Finalizing...",
            "Starting gateway...",
            "Refreshing model list...",
        };

        mBotDropService.updateOpenclaw(targetVersion,
            new BotDropService.UpdateProgressCallback() {
            private int currentStep = -1;

            private void advanceTo(String message) {
                // Find which step this message belongs to
                int nextStep = -1;
                for (int i = 0; i < stepMessages.length; i++) {
                    if (stepMessages[i].equals(message)) {
                        nextStep = i;
                        break;
                    }
                }
                if (nextStep < 0) return;

                // Mark all previous steps as complete
                for (int i = 0; i <= currentStep && i < stepIcons.length; i++) {
                    stepIcons[i].setText("\u2713");
                }
                // Mark current step as in-progress
                if (nextStep < stepIcons.length) {
                    stepIcons[nextStep].setText("\u25CF");
                }
                currentStep = nextStep;
            }

            @Override
            public void onStepStart(String message) {
                advanceTo(message);
            }

            @Override
            public void onError(String error) {
                progressDialog.dismiss();
                refreshStatus();
                new AlertDialog.Builder(DashboardActivity.this)
                    .setTitle("Update Failed")
                    .setMessage(error)
                    .setPositiveButton("OK", null)
                    .show();
                checkOpenclawUpdate();
            }

            @Override
            public void onComplete(String newVersion) {
                mOpenclawLatestUpdateVersion = null;
                advanceTo(stepMessages[4]);
                statusMessage.setText("Updated to v" + newVersion + " and refreshing model list...");
                prefetchModelsForUpdate(newVersion, success -> {
                    // Mark all steps complete
                    for (TextView icon : stepIcons) {
                        icon.setText("\u2713");
                    }
                    statusMessage.setText(
                        success
                            ? "Updated to v" + newVersion
                            : "Updated to v" + newVersion + " (model cache refresh failed)"
                    );

                    // Auto-dismiss after 1.5s
                    mHandler.postDelayed(() -> {
                        if (!isFinishing()) {
                            progressDialog.dismiss();
                        }
                        OpenClawUpdateChecker.clearUpdate(DashboardActivity.this);
                        if (mOpenclawVersionText != null) {
                            mOpenclawVersionText.setText("OpenClaw v" + newVersion);
                        }
                        refreshStatus();
                    }, 1500);
                });
            }
        });
    }

    private void prefetchModelsForUpdate(String openclawVersion, ModelListPrefetchCallback callback) {
        final ModelListPrefetchCallback finalCallback = callback == null ? (ModelListPrefetchCallback) success -> {} : callback;

        if (mBotDropService == null) {
            finalCallback.onFinished(false);
            return;
        }

        final String normalizedVersion = normalizeModelCacheKey(openclawVersion);
        mBotDropService.executeCommand(MODEL_LIST_COMMAND, result -> {
            if (!result.success) {
                Logger.logWarn(LOG_TAG, "Model list prefetch failed for v" + openclawVersion + ": exit " + result.exitCode);
                finalCallback.onFinished(false);
                return;
            }

            List<ModelInfo> models = parseModelListForUpdate(result.stdout);
            if (models.isEmpty()) {
                Logger.logWarn(LOG_TAG, "Model list prefetch returned empty output for v" + openclawVersion);
                finalCallback.onFinished(false);
                return;
            }

            Collections.sort(models, (a, b) -> {
                if (a == null || b == null || a.fullName == null || b.fullName == null) return 0;
                return b.fullName.compareToIgnoreCase(a.fullName);
            });

            cacheModelsForUpdate(normalizedVersion, models);
            finalCallback.onFinished(true);
            Logger.logInfo(LOG_TAG, "Prefetched " + models.size() + " models for OpenClaw v" + openclawVersion);
        });
    }

    private List<ModelInfo> parseModelListForUpdate(String output) {
        List<ModelInfo> models = new ArrayList<>();
        if (TextUtils.isEmpty(output)) {
            return models;
        }

        try {
            String[] lines = output.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("#") || trimmed.startsWith("Model ")) {
                    continue;
                }

                String token = trimmed;
                if (trimmed.contains(" ")) {
                    token = trimmed.split("\\s+")[0];
                }

                if (isModelTokenForUpdate(token)) {
                    models.add(new ModelInfo(token));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to parse model list output: " + e.getMessage());
        }
        return models;
    }

    private void cacheModelsForUpdate(String version, List<ModelInfo> models) {
        if (TextUtils.isEmpty(version) || models == null || models.isEmpty()) return;

        try {
            JSONArray list = new JSONArray();
            for (ModelInfo model : models) {
                if (model != null && !TextUtils.isEmpty(model.fullName)) {
                    list.put(model.fullName);
                }
            }

            JSONObject root = new JSONObject();
            root.put("version", version);
            root.put("updated_at", System.currentTimeMillis());
            root.put("models", list);

            getSharedPreferences(MODEL_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(modelCacheKey(version), root.toString())
                .apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to cache prefetched model list: " + e.getMessage());
        }
    }

    private String modelCacheKey(String version) {
        return MODEL_CACHE_KEY_PREFIX + normalizeModelCacheKey(version);
    }

    private String normalizeModelCacheKey(String version) {
        if (TextUtils.isEmpty(version)) {
            return "unknown";
        }
        return version.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean isModelTokenForUpdate(String token) {
        if (token == null || token.isEmpty()) return false;
        if (!token.contains("/")) return false;
        return token.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._:/-]+");
    }

    private void checkGatewayErrors(boolean isRunning) {
        if (!mBound || mBotDropService == null || !isRunning) {
            showGatewayError(null);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - mLastErrorCheckAtMs < ERROR_CHECK_INTERVAL_MS) {
            return;
        }
        mLastErrorCheckAtMs = now;

        mBotDropService.executeCommand(
            "if [ -f ~/.openclaw/gateway.log ]; then tail -n 120 ~/.openclaw/gateway.log; fi",
            result -> {
                if (!result.success) {
                    Logger.logWarn(LOG_TAG, "Failed to read gateway.log: " + result.stderr);
                    return;
                }
                String errorLine = extractRecentGatewayError(result.stdout);
                showGatewayError(errorLine);
            }
        );
    }

    private String extractRecentGatewayError(String logText) {
        if (TextUtils.isEmpty(logText)) return null;

        String[] lines = logText.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String raw = lines[i];
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            String lower = line.toLowerCase();
            boolean looksLikeError =
                lower.contains(" sendmessage failed") ||
                lower.contains(" sendchataction failed") ||
                lower.contains(" fetch failed") ||
                lower.contains("error:") ||
                lower.contains("exception") ||
                lower.contains("unhandled rejection") ||
                lower.contains("network request for");
            if (looksLikeError) {
                if (line.length() > 180) {
                    line = line.substring(0, 180) + "...";
                }
                return line;
            }
        }
        return null;
    }

    private void showGatewayError(String message) {
        if (TextUtils.equals(message, mLastErrorMessage)) {
            return;
        }
        mLastErrorMessage = message;

        if (TextUtils.isEmpty(message)) {
            mGatewayErrorBanner.setVisibility(View.GONE);
            mGatewayErrorText.setText("—");
        } else {
            mGatewayErrorText.setText(message);
            mGatewayErrorBanner.setVisibility(View.VISIBLE);
        }
    }
}
