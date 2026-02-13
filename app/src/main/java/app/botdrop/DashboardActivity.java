package app.botdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

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

    private TextView mStatusText;
    private TextView mUptimeText;
    private View mStatusIndicator;
    private TextView mTelegramStatus;
    private TextView mDiscordStatus;
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
    private String mOpenclawLatestUpdateVersion;
    private AlertDialog mOpenclawUpdateDialog;
    private boolean mOpenclawManualCheckRequested;

    private BotDropService mBotDropService;
    private boolean mBound = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mStatusRefreshRunnable;
    private long mLastErrorCheckAtMs = 0L;
    private String mLastErrorMessage;

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

        // Load channel info
        loadChannelInfo();

        // Load SSH info
        loadSshInfo();

        // Bind to service
        Intent intent = new Intent(this, BotDropService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        // Check for app updates (also picks up results from launcher check)
        UpdateChecker.check(this, (latestVersion, downloadUrl, notes) -> showUpdateBanner(latestVersion, downloadUrl));

        // Also check stored result in case launcher already fetched it
        String[] stored = UpdateChecker.getAvailableUpdate(this);
        if (stored != null) {
            showUpdateBanner(stored[0], stored[1]);
        }
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
        mStatusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                mHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
            }
        };
        mHandler.post(mStatusRefreshRunnable);
    }

    /**
     * Refresh gateway status and uptime
     */
    private void refreshStatus() {
        if (!mBound || mBotDropService == null) {
            return;
        }

        // Check if gateway is running
        mBotDropService.isGatewayRunning(result -> {
            boolean isRunning = result.success && result.stdout.trim().equals("running");
            updateStatusUI(isRunning);
            checkGatewayErrors(isRunning);

            // Get uptime if running
            if (isRunning) {
                mBotDropService.getGatewayUptime(uptimeResult -> {
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

    /**
     * Load channel configuration and update UI
     */
    private void loadChannelInfo() {
        try {
            JSONObject config = BotDropConfig.readConfig();
            if (config.has("channels")) {
                JSONObject channels = config.getJSONObject("channels");

                // Check Telegram
                if (channels.has("telegram")) {
                    mTelegramStatus.setText("● Connected");
                    mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                } else {
                    mTelegramStatus.setText("○ —");
                    mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                }

                // Check Discord
                if (channels.has("discord")) {
                    mDiscordStatus.setText("● Connected");
                    mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                } else {
                    mDiscordStatus.setText("○ —");
                    mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                }
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
        dialog.show((provider, model) -> {
            if (provider != null && model != null) {
                showModelAuthDialog(provider, model);
            }
        });
    }

    /**
     * Ask for optional API key update, then apply model.
     */
    private void showModelAuthDialog(String provider, String model) {
        String fullModel = provider + "/" + model;
        boolean hasExistingKey = BotDropConfig.hasApiKey(provider);

        int horizontalPadding = (int) (20 * getResources().getDisplayMetrics().density);
        int verticalPadding = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0);

        TextView message = new TextView(this);
        message.setText(
            "Selected model: " + fullModel + "\n" +
            (hasExistingKey
                ? "Enter a new API key if you want to replace the current one."
                : "No API key found for provider \"" + provider + "\". Please enter one.")
        );
        message.setPadding(0, 0, 0, verticalPadding);
        container.addView(message);

        EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint(hasExistingKey ? "Leave empty to keep current key" : "Enter API key");
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.addView(apiKeyInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Change model")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save & Apply", null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newApiKey = apiKeyInput.getText().toString().trim();
            if (!hasExistingKey && TextUtils.isEmpty(newApiKey)) {
                apiKeyInput.setError("API key is required for this provider");
                return;
            }

            dialog.dismiss();
            updateModel(fullModel, newApiKey);
        }));

        dialog.show();
    }

    /**
     * Update model/API key and restart gateway.
     */
    private void updateModel(String fullModel, String optionalApiKey) {
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
        boolean providerWritten = BotDropConfig.setProvider(provider, model);
        boolean keyWritten = true;
        if (!TextUtils.isEmpty(optionalApiKey)) {
            keyWritten = BotDropConfig.setApiKey(provider, model, optionalApiKey);
        }

        if (!providerWritten || !keyWritten) {
            Toast.makeText(DashboardActivity.this, "Failed to update model settings", Toast.LENGTH_SHORT).show();
            Logger.logError(LOG_TAG, "Failed to update model. providerWritten=" + providerWritten +
                ", keyWritten=" + keyWritten);
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
        ConfigTemplateCache.saveTemplate(DashboardActivity.this, template);

        restartGateway();
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
        mOpenclawCheckUpdateButton.setText("Checking...");
        mOpenclawLatestUpdateVersion = null;
        mOpenclawManualCheckRequested = true;

        OpenClawUpdateChecker.check(this, mBotDropService, new OpenClawUpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String current, String latest) {
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText("Check for updates");
                mOpenclawManualCheckRequested = false;
                showOpenclawUpdateDialog(current, latest, true);
            }

            @Override
            public void onNoUpdate() {
                mOpenclawCheckUpdateButton.setEnabled(true);
                mOpenclawCheckUpdateButton.setText("Check for updates");
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
                // Mark all steps complete
                for (TextView icon : stepIcons) {
                    icon.setText("\u2713");
                }
                statusMessage.setText("Updated to v" + newVersion);

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
            }
        });
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
